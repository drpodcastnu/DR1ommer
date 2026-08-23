package ommer.client

import com.github.mustachejava.DefaultMustacheFactory
import ommer.drapi.Episodes
import ommer.drapi.Item
import ommer.drapi.SeriesList
import ommer.drapi.Show
import ommer.graphics.generatePodcastImage
import ommer.pagegen.PageData
import ommer.pagegen.PodcastData
import ommer.rss.Feed
import ommer.rss.FeedItem
import ommer.rss.generate
import ommer.slugs.loadPins
import ommer.slugs.recoverPins
import ommer.slugs.savePins
import org.http4k.client.JettyClient
import org.http4k.core.Body
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Uri
import org.http4k.core.query
import org.http4k.format.Gson.auto
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Properties
import kotlin.math.abs

private val log = LoggerFactory.getLogger("ommer")

private val indexDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private operator fun Uri.div(suffix: String): Uri = path("$path/$suffix")
private operator fun File.div(relative: String): File = resolve(relative)

private val episodes = Body.auto<Episodes>().toLens()
private val seriesList = Body.auto<SeriesList>().toLens()

private fun fetchEpisodes(
    client: HttpHandler,
    baseUri: Uri,
    urn: String,
    apiKey: String,
): Sequence<Item> = sequence {
    var currentUri = (baseUri / urn / "episodes").query("limit", "256")
    while (true) {
        log.info("Getting $currentUri")
        val response = episodes(client(Request(GET, currentUri).header("x-apikey", apiKey)))
        log.info("Got ${response.items.size} items")
        response.items.forEach { yield(it) }
        currentUri = response.next ?: break
    }
}

private fun fetchSeries(
    client: HttpHandler,
    baseUri: Uri,
    apiKey: String,
): Sequence<Show> = sequence {
    var currentUri = (baseUri / "series").query("limit", "256")
    while (true) {
        log.info("Getting $currentUri")
        val response = seriesList(client(Request(GET, currentUri).header("x-apikey", apiKey)))
        log.info("Got ${response.items.size} series")
        response.items.forEach { yield(it) }
        currentUri = response.next ?: break
    }
}

private const val slugFileName = "slugs.json"

private val trailingNumber = Regex("-\\d*$")

/**
 * Slugs DR renamed before we started pinning them in slugs.json. Only consulted for series that
 * have no pin yet, so this can be deleted once slugs.json is in place everywhere.
 */
private val slugRenames = mapOf(
    "sara-og-monopolet-podcast" to "sara-og-monopolet",
    "mads-monopolet-podcast" to "sara-og-monopolet",
    "hjernekassen-paa-p1" to "hjernekassen",
    "hjernekassen-pa-p1" to "hjernekassen",
    "moerklagt-agent-samsam" to "moerklagt",
)

private fun Show.feedSlug(): String {
    // podcastUrl isn't present for all shows
    val derived = podcastUrl?.substringAfterLast('/')
        ?: (slug ?: id.substringAfterLast(':')).replace(trailingNumber, "")
    return slugRenames[derived] ?: derived
}

/**
 * Every series DR currently lists, one per feed slug. A series keeps the slug it was first
 * published under, so a rename on DR's side never moves one of our feeds.
 */
private fun discoverPodcasts(
    client: HttpHandler,
    apiUri: Uri,
    apiKey: String,
    outputDirectory: File,
): List<Pair<Podcast, Show>> {
    val discovered = fetchSeries(client, apiUri, apiKey).toList()
    log.info("Discovered ${discovered.size} series")
    val pins = loadPins(outputDirectory / slugFileName)
    val unpinned = discovered.filter { it.id !in pins }
    val recovered = if (unpinned.isEmpty()) emptyMap() else recoverPins(outputDirectory)

    val holders = mutableMapOf<String, Show>()
    val slugs = mutableMapOf<String, String>()
    fun claim(showInfo: Show, slug: String) {
        val holder = holders[slug]
        if (holder != null) {
            log.warn("Slug $slug is held by \"${holder.title}\". Skipping \"${showInfo.title}\"")
            return
        }
        holders[slug] = showInfo
        slugs[showInfo.id] = slug
    }

    // Series we have published before keep their slug, whatever DR calls them now.
    discovered.forEach { showInfo ->
        val pinned = pins[showInfo.id]?.slug ?: showInfo.productionNumber?.let { recovered[it] } ?: return@forEach
        if (pinned != showInfo.feedSlug()) {
            log.info("DR renamed \"${showInfo.title}\" to ${showInfo.feedSlug()}. Keeping $pinned")
        }
        claim(showInfo, pinned)
    }
    // The rest are new to us, and are pinned to DR's current slug from here on.
    discovered.forEach { showInfo -> if (showInfo.id !in slugs) claim(showInfo, showInfo.feedSlug()) }
    if (unpinned.isNotEmpty()) log.info("${unpinned.size} series not yet pinned in $slugFileName")

    return discovered.mapNotNull { showInfo -> slugs[showInfo.id]?.let { Podcast(showInfo.id, it) to showInfo } }
}

fun Duration.formatHMS(): String =
    String.format("%02d:%02d:%02d", toHours(), toMinutesPart(), toSecondsPart())

data class Podcast(
    val urn: String,
    val slug: String,
    val titleSuffix: String? = null,
    val descriptionSuffix: String? = null,
)

data class Podcasts(val descriptionSuffix: String?)

fun main(args: Array<String>) {
    if (args.isEmpty()) throw Error("No configuration file specified")
    val props = Properties().apply { FileInputStream(args[0]).use { stream -> load(stream) } }
    val apiKey = props.getProperty("apiKey") ?: throw Error("Missing API key")
    val apiUri = Uri.of(props.getProperty("apiUrl") ?: throw Error("Missing API URL"))
    val outputDirectory = File(props.getProperty("outputDirectory") ?: throw Error("Missing output directory"))
    outputDirectory.mkdirs()
    val podcasts =
        com.google.gson.Gson().fromJson(
            Podcasts::class.java.getResource("/podcasts.json")?.readText()!!,
            Podcasts::class.java,
        )

    val rssDateTimeFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z")

    val podcastData = JettyClient().use { client ->
        val discovered = discoverPodcasts(client, apiUri, apiKey, outputDirectory)
        if (args.getOrNull(1) == "--list") {
            discovered.forEach { (podcast, showInfo) -> println("${showInfo.title} (${podcast.slug})") }
            return
        }
        savePins(outputDirectory / slugFileName, discovered.map { (podcast, showInfo) -> podcast.slug to showInfo })
        discovered.map { (podcast, showInfo) ->
            val descriptionSuffix = podcast.descriptionSuffix ?: podcasts.descriptionSuffix
            val feedDirectory = outputDirectory / podcast.slug
            feedDirectory.mkdirs()
            val feedFile = feedDirectory / "feed.xml"
            log.info("Processing podcast ${podcast.slug}. Target feed: $feedFile")
            val episodes = fetchEpisodes(client, apiUri / "series", podcast.urn, apiKey)
            val feed = generateFeedFile(showInfo, podcast, descriptionSuffix, rssDateTimeFormatter, episodes, feedFile)

            val imageFile = feedDirectory / "image.jpg"
            generatePodcastImage(
                imageFile,
                showInfo.visualIdentity?.gradient?.colors?.getOrNull(0) ?: "#000000",
                showInfo.visualIdentity?.gradient?.colors?.getOrNull(1) ?: "#FFFFFF",
                showInfo.title,
            )
            PodcastData(
                slug = podcast.slug,
                title = showInfo.title,
                description = showInfo.description,
                episodes = feed?.items?.size ?: 0,
                latestEpisode = showInfo.latestEpisodeStartTime?.let { latest ->
                    ZonedDateTime
                        .parse(latest)
                        .withZoneSameInstant(ZoneId.of("Europe/Copenhagen"))
                        .format(indexDateTimeFormatter)
                } ?: "",
            )
        }
    }

    val indexFile = outputDirectory / "index.html"
    generateIndexFile(podcastData, indexFile)
}

private fun generateIndexFile(podcastData: List<PodcastData>, indexFile: File) {
    val pageData = PageData(
        podcastData.sortedWith(compareByDescending<PodcastData> { it.latestEpisode }.thenBy { it.title }),
    )
    FileWriter(indexFile).use { writer ->
        val mustache = DefaultMustacheFactory().compile("template.html.mustache")
        mustache.execute(writer, pageData)
    }
}

private fun generateFeedFile(
    showInfo: Show,
    podcast: Podcast,
    descriptionSuffix: String?,
    rssDateTimeFormatter: DateTimeFormatter?,
    episodes: Sequence<Item>,
    feedFile: File,
): Feed? {
    if (showInfo.latestEpisodeStartTime == null) {
        log.warn("Podcast has zero episodes: ${podcast.slug}")
        return null
    }
    val feed = with(showInfo) {
        Feed(
            link = presentationUrl,
            title = "$title${podcast.titleSuffix?.let { s -> " $s" } ?: ""}",
            description = "$description${descriptionSuffix?.let { s -> "\n$s" } ?: ""}",
            email = "no-reply@drpodcast.nu",
            lastBuildDate = ZonedDateTime
                .parse(latestEpisodeStartTime!!)
                .withZoneSameInstant(ZoneId.of("Europe/Copenhagen"))
                .format(rssDateTimeFormatter),
            feedUrl = "https://drpodcast.nu/${podcast.slug}/feed.xml",
            imageUrl = "https://drpodcast.nu/${podcast.slug}/image.jpg",
            imageLink = presentationUrl,
            items = episodes.mapNotNull { item ->
                with(item) {
                    val audioAsset = audioAssets
                        .filter { it.target == "Progressive" }
                        .filter { it.format == "mp3" }
                        // Select asset which is closest to bitrate 192
                        .minByOrNull { abs(it.bitrate - 192) } ?: run {
                        log.warn("No audio asset for ${item.id} (${item.title})")
                        return@mapNotNull null
                    }
                    FeedItem(
                        guid = productionNumber,
                        link = presentationUrl?.toString() ?: showInfo.presentationUrl,
                        title = title,
                        description = description,
                        pubDate = ZonedDateTime
                            .parse(startTime)
                            .withZoneSameInstant(ZoneId.of("Europe/Copenhagen"))
                            .format(rssDateTimeFormatter),
                        duration = Duration.of(durationMilliseconds, ChronoUnit.MILLIS).formatHMS(),
                        enclosureUrl = audioAsset.url.toString(),
                        enclosureByteLength = audioAsset.fileSize,
                    )
                }
            }.toList(),
        )
    }
    feed.generate(feedFile)
    return feed
}
