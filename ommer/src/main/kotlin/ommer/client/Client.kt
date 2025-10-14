package ommer.client

import com.google.gson.GsonBuilder
import ommer.drapi.Episodes
import ommer.drapi.Item
import ommer.drapi.Show
import ommer.graphics.generatePodcastImage
import ommer.rss.Feed
import ommer.rss.FeedItem
import ommer.rss.generate
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

private operator fun Uri.div(suffix: String): Uri = path("$path/$suffix")
private operator fun File.div(relative: String): File = resolve(relative)

private val episodes = Body.auto<Episodes>().toLens()
private val show = Body.auto<Show>().toLens()

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

fun Duration.formatHMS(): String =
    String.format("%02d:%02d:%02d", toHours(), toMinutesPart(), toSecondsPart())

data class Podcast(val urn: String, val slug: String, val titleSuffix: String?, val descriptionSuffix: String?)
data class Podcasts(val descriptionSuffix: String?, val podcasts: List<Podcast>)

data class PodcastRecord(
    val slug: String,
    val title: String,
    val episodeCount: Int,
    val lastUpdatedOrder: Long,
    val lastUpdatedText: String,
    val presentationUrl: String,
)

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
    val displayDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    val podcastData = JettyClient().use { client ->
        podcasts.podcasts.map { podcast ->
            val defaultDescriptionSuffix = podcasts.descriptionSuffix
            val descriptionSuffix = podcast.descriptionSuffix ?: defaultDescriptionSuffix
            val feedDirectory = outputDirectory / podcast.slug
            feedDirectory.mkdirs()
            val feedFile = outputDirectory / podcast.slug / "feed.xml"
            log.info("Processing podcast ${podcast.slug}. Target feed: $feedFile")
            val showInfo = show(client(Request(GET, apiUri / "series" / podcast.urn).header("x-apikey", apiKey)))
            val episodeItems = fetchEpisodes(client, apiUri / "series", podcast.urn, apiKey).toList()

            val latestEpisode = showInfo.latestEpisodeStartTime?.let { latest ->
                ZonedDateTime.parse(latest).withZoneSameInstant(ZoneId.of("Europe/Copenhagen"))
            }

            generateFeedFile(
                showInfo = showInfo,
                podcast = podcast,
                descriptionSuffix = descriptionSuffix,
                rssDateTimeFormatter = rssDateTimeFormatter,
                episodes = episodeItems,
                feedFile = feedFile,
                latestEpisode = latestEpisode,
            )

            val imageFile = outputDirectory / podcast.slug / "image.jpg"
            generatePodcastImage(
                imageFile,
                showInfo.visualIdentity?.gradient?.colors?.getOrNull(0) ?: "#000000",
                showInfo.visualIdentity?.gradient?.colors?.getOrNull(1) ?: "#FFFFFF",
                showInfo.title,
            )
            PodcastRecord(
                slug = podcast.slug,
                title = showInfo.title,
                episodeCount = episodeItems.size,
                lastUpdatedOrder = latestEpisode?.toEpochSecond() ?: 0,
                lastUpdatedText = latestEpisode?.format(displayDateTimeFormatter) ?: "N/A",
                presentationUrl = showInfo.presentationUrl,
            )
        }
    }

    val indexFile = outputDirectory / "index.html"
    val dataFile = outputDirectory / "data.json"
    writeIndexHtml(indexFile)
    writeDataJson(podcastData.sortedBy { it.title }, dataFile)
}

private fun writeIndexHtml(indexFile: File) {
    val indexHtml = PodcastRecord::class.java.getResource("/index.html")?.readText()
        ?: throw IllegalStateException("Missing index.html resource")
    FileWriter(indexFile).use { writer ->
        writer.write(indexHtml)
    }
}

private fun writeDataJson(podcastData: List<PodcastRecord>, dataFile: File) {
    FileWriter(dataFile).use { writer ->
        GsonBuilder().setPrettyPrinting().create().toJson(podcastData, writer)
    }
}

private fun generateFeedFile(
    showInfo: Show,
    podcast: Podcast,
    descriptionSuffix: String?,
    rssDateTimeFormatter: DateTimeFormatter?,
    episodes: List<Item>,
    feedFile: File,
    latestEpisode: ZonedDateTime?,
) {
    if (latestEpisode == null) {
        log.warn("Podcast has zero episodes: ${podcast.slug}")
        return
    }
    val feed = with(showInfo) {
        Feed(
            link = presentationUrl,
            title = "$title${podcast.titleSuffix?.let { s -> " $s" } ?: ""}",
            description = "$description${descriptionSuffix?.let { s -> "\n$s" } ?: ""}",
            email = "no-reply@drpodcast.nu",
            lastBuildDate = latestEpisode.format(rssDateTimeFormatter),
            feedUrl = "https://drpodcast.nu/${podcast.slug}/feed.xml",
            imageUrl = "https://drpodcast.nu/${podcast.slug}/image.jpg",
            imageLink = presentationUrl,
            items = episodes.asSequence().mapNotNull { item ->
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
                            .parse(publishTime)
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
}
