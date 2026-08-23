package ommer.slugs

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import ommer.drapi.Show
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("ommer")

/**
 * The slug a series is published under, pinned to DR's stable series id the first time we see it.
 * DR renames its own slugs from time to time; ours never change, so feed URLs keep working.
 */
data class PinnedSlug(val slug: String, val productionNumber: String?, val title: String?)

private val gson = GsonBuilder().setPrettyPrinting().create()
private val pinnedSlugs = object : TypeToken<Map<String, PinnedSlug>>() {}.type

fun loadPins(file: File): Map<String, PinnedSlug> =
    if (!file.isFile) emptyMap() else gson.fromJson(file.readText(), pinnedSlugs) ?: emptyMap()

/** Merge the slugs just published into [file]. Series DR has delisted keep their pin. */
fun savePins(file: File, published: List<Pair<String, Show>>) {
    val pins = loadPins(file) + published.associate { (slug, show) ->
        show.id to PinnedSlug(slug, show.productionNumber, show.title)
    }
    val sorted = pins.entries.sortedBy { it.value.slug }.associate { it.key to it.value }
    file.writeText(gson.toJson(sorted, pinnedSlugs) + "\n")
}

private val channelLink = Regex("<link>([^<]*)</link>")

/**
 * Production number to published slug, read back out of the feeds already in [outputDirectory].
 * Every feed's channel link is DR's presentation URL, which ends in the production number, so the
 * published site can reconstruct its own pins if [loadPins] ever comes up empty.
 */
fun recoverPins(outputDirectory: File): Map<String, String> {
    val recovered = mutableMapOf<String, String>()
    val feedDirectories = outputDirectory.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: return emptyMap()
    feedDirectories.forEach { directory ->
        val feed = directory / "feed.xml"
        if (!feed.isFile) return@forEach
        val link = channelLink.find(feed.readText().substringBefore("<item>"))?.groupValues?.get(1) ?: return@forEach
        val holder = recovered.putIfAbsent(link.substringAfterLast('-'), directory.name)
        if (holder != null) {
            log.warn("Feeds $holder and ${directory.name} share a production number. Recovering $holder")
        }
    }
    if (recovered.isNotEmpty()) log.info("Recovered ${recovered.size} slugs from published feeds")
    return recovered
}

private operator fun File.div(relative: String): File = resolve(relative)
