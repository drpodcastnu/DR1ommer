package ommer.client

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

fun main(args: Array<String>) {
    if (args.isEmpty()) throw Error("No configuration file specified")
    val props = Properties().apply { FileInputStream(args[0]).use { stream -> load(stream) } }
    val apiKey = props.getProperty("apiKey") ?: throw Error("Missing API key")
    val apiUri = Uri.of(props.getProperty("apiUrl") ?: throw Error("Missing API URL"))
    val outputDirectory = File(props.getProperty("outputDirectory") ?: throw Error("Missing output directory"))

    val podcasts = mutableListOf<Podcast>()
    var current = 1
    while (true) {
        podcasts.add(
            Podcast(
                urn = props.getProperty("urn$current") ?: break,
                slug = props.getProperty("slug$current") ?: throw Error("Missing slug"),
                titleSuffix = props.getProperty("titleSuffix$current"),
                descriptionSuffix = props.getProperty("descriptionSuffix$current"),
            ),
        )
        current++
    }

    val rssDateTimeFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z")
    JettyClient().use { client ->
        podcasts.forEach { podcast ->
            val feedDirectory = outputDirectory / podcast.slug
            feedDirectory.mkdirs()
            val feedFile = outputDirectory / podcast.slug / "feed.xml"
            val imageFile = outputDirectory / podcast.slug / "image.jpg"
            log.info("Processing podcast ${podcast.slug}. Target feed: $feedFile")
            val response = client(Request(GET, apiUri / "series" / podcast.urn).header("x-apikey", apiKey))
            val showInfo = show(response)
            val feed = with(showInfo) {
                Feed(
                    link = presentationUrl,
                    title = "$title",
                    description = "$description",
                    email = "podcast@dr.dk",
                    lastBuildDate = ZonedDateTime
                        .parse(latestEpisodeStartTime)
                        .withZoneSameInstant(ZoneId.of("Europe/Copenhagen"))
                        .format(rssDateTimeFormatter),
                    feedUrl = "https://api.dr.dk/podcasts/v1/feeds/genstart.xml?format=podcast",
                    imageUrl = "https://api.dr.dk/podcasts/v1/images/urn:dr:podcast:image:6593ba22846e9f8fc0338958.jpg",
                    imageLink = presentationUrl,
                    items = fetchEpisodes(client, apiUri / "series", podcast.urn, apiKey).mapNotNull { item ->
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
                                link = presentationUrl.toString(),
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
    }
}
