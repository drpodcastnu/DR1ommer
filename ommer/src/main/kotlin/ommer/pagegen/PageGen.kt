package ommer.pagegen

import com.github.mustachejava.DefaultMustacheFactory
import java.io.StringWriter

data class PageData(
    val podcasts: List<PodcastData>,
)

data class PodcastData(
    val slug: String,
    val title: String,
    val description: String,
    val episodes: Int,
    val latestEpisode: String,
)

fun main() {
    val factory = DefaultMustacheFactory()
    val mustache = factory.compile("template.html.mustache")
    val writer = StringWriter()
    val podcast = PodcastData(
        slug = "sara-og-monopolet",
        title = "Sara Og Monopolet",
        description = "Sara og hendes gæster giver råd.",
        episodes = 512,
        latestEpisode = "2026-08-21 05:00",
    )
    mustache.execute(writer, PageData(listOf(podcast)))
    println(writer.buffer.toString())
}
