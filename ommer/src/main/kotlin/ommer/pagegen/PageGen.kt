package ommer.pagegen

import com.github.mustachejava.DefaultMustacheFactory
import java.io.StringWriter

data class PageData(
    val rows: List<RowData>,
)

data class RowData(
    val podcasts: List<PodcastData>,
)

data class PodcastData(
    val slug: String,
    val title: String,
)

fun main() {
    val factory = DefaultMustacheFactory()
    val mustache = factory.compile("template.html.mustache")
    val writer = StringWriter()
    mustache.execute(writer, PageData(listOf(RowData(listOf(PodcastData("sara-og-monopolet", "Sara Og Monopolet"))))))
    println(writer.buffer.toString())
}