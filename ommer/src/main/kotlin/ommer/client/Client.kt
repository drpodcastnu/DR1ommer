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

data class Podcast(
    val urns: List<String>? = null,
    val urn: String? = null,
    val slug: String,
    val titleSuffix: String?,
    val descriptionSuffix: String?,
) {
    val seriesUrns: List<String>
        get() = when {
            !urns.isNullOrEmpty() -> urns
            !urn.isNullOrBlank() -> listOf(urn)
            else -> emptyList()
        }

    val primaryUrn: String
        get() = seriesUrns.firstOrNull()
            ?: throw IllegalStateException("Podcast $slug must define at least one URN")
}
data class Podcasts(val descriptionSuffix: String?, val podcasts: List<Podcast>)

private data class CombinedEpisode(
    val show: Show,
    val item: Item,
    val publishTime: ZonedDateTime,
)

data class PodcastRecord(
    val slug: String,
    val title: String,
    val episodeCount: Int,
    val lastUpdated: LastUpdated,
    val presentationUrl: String,
)

data class LastUpdated(
    val display: String,
    val sort: Long,
)

fun main(args: Array<String>) {
    if (args.isEmpty()) throw Error("No configuration file specified")
    val props = Properties().apply { FileInputStream(args[0]).use { stream -> load(stream) } }
    val apiKey = props.getProperty("apiKey") ?: throw Error("Missing API key")
    val apiUri = Uri.of(props.getProperty("apiUrl") ?: throw Error("Missing API URL"))
    val outputDirectory = File(props.getProperty("outputDirectory") ?: throw Error("Missing output directory"))
    val podcasts =
        com.google.gson.Gson().fromJson(
            Podcasts::class.java.getResource("/podcasts.json")?.readText()!!,
            Podcasts::class.java,
        )

    val rssDateTimeFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z")
    val displayDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    outputDirectory.mkdirs()

    val podcastData = JettyClient().use { client ->
        podcasts.podcasts.map { podcast ->
            val defaultDescriptionSuffix = podcasts.descriptionSuffix
            val descriptionSuffix = podcast.descriptionSuffix ?: defaultDescriptionSuffix
            val feedDirectory = outputDirectory / podcast.slug
            feedDirectory.mkdirs()
            val feedFile = outputDirectory / podcast.slug / "feed.xml"
            log.info("Processing podcast ${podcast.slug}. Target feed: $feedFile")
            log.debug("Using URNs ${podcast.seriesUrns}")
            val showInfos = podcast.seriesUrns.associateWith { urn ->
                show(client(Request(GET, apiUri / "series" / urn).header("x-apikey", apiKey)))
            }
            val primaryShowInfo = showInfos.getValue(podcast.primaryUrn)
            val combinedEpisodes =
                podcast.seriesUrns.asSequence()
                    .flatMap { urn ->
                        val showInfo = showInfos.getValue(urn)
                        fetchEpisodes(client, apiUri / "series", urn, apiKey).map { item ->
                            CombinedEpisode(showInfo, item, ZonedDateTime.parse(item.publishTime))
                        }
                    }
                    .distinctBy { it.item.productionNumber }
                    .sortedByDescending { it.publishTime }
                    .toList()
            generateFeedFile(
                primaryShowInfo,
                podcast,
                descriptionSuffix,
                rssDateTimeFormatter,
                combinedEpisodes,
                feedFile,
            )

            val imageFile = outputDirectory / podcast.slug / "image.jpg"
            generatePodcastImage(
                imageFile,
                primaryShowInfo.visualIdentity?.gradient?.colors?.getOrNull(0) ?: "#000000",
                primaryShowInfo.visualIdentity?.gradient?.colors?.getOrNull(1) ?: "#FFFFFF",
                primaryShowInfo.title,
            )
            val latestEpisode = combinedEpisodes.firstOrNull()?.publishTime?.withZoneSameInstant(ZoneId.of("Europe/Copenhagen"))
            PodcastRecord(
                slug = podcast.slug,
                title = primaryShowInfo.title,
                episodeCount = combinedEpisodes.size,
                lastUpdated = LastUpdated(
                    display = latestEpisode?.format(displayDateTimeFormatter) ?: "N/A",
                    sort = latestEpisode?.toInstant()?.epochSecond ?: 0,
                ),
                presentationUrl = primaryShowInfo.presentationUrl,
            )
        }
    }

    val dataFile = outputDirectory / "data.json"
    writeDataJson(podcastData.sortedBy { it.title }, dataFile)
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
    rssDateTimeFormatter: DateTimeFormatter,
    episodes: List<CombinedEpisode>,
    feedFile: File,
) {
    if (episodes.isEmpty()) {
        log.warn("Podcast has zero episodes: ${podcast.slug}")
        return
    }
    val feed = with(showInfo) {
        val latestBuildDate = episodes.maxOf { it.publishTime }
        val formattedBuildDate = latestBuildDate
            .withZoneSameInstant(ZoneId.of("Europe/Copenhagen"))
            .format(rssDateTimeFormatter)
        Feed(
            link = presentationUrl,
            title = "$title${podcast.titleSuffix?.let { s -> " $s" } ?: ""}",
            description = "$description${descriptionSuffix?.let { s -> "\n$s" } ?: ""}",
            email = "no-reply@drpodcast.nu",
            lastBuildDate = formattedBuildDate,
            feedUrl = "https://drpodcast.nu/${podcast.slug}/feed.xml",
            imageUrl = "https://drpodcast.nu/${podcast.slug}/image.jpg",
            imageLink = presentationUrl,
            items = episodes.mapNotNull { combinedEpisode ->
                with(combinedEpisode.item) {
                    val audioAsset = audioAssets
                        .filter { it.target == "Progressive" }
                        .filter { it.format == "mp3" }
                        // Select asset which is closest to bitrate 192
                        .minByOrNull { abs(it.bitrate - 192) } ?: run {
                        log.warn("No audio asset for ${combinedEpisode.item.id} (${combinedEpisode.item.title})")
                        return@mapNotNull null
                    }
                    FeedItem(
                        guid = productionNumber,
                        link = presentationUrl?.toString() ?: combinedEpisode.show.presentationUrl,
                        title = title,
                        description = description,
                        pubDate = combinedEpisode.publishTime
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
