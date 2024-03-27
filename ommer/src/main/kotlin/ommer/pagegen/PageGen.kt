package ommer.pagegen

import com.github.mustachejava.DefaultMustacheFactory
import java.io.StringWriter

class PageGen {

}

data class PageData(
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
    mustache.execute(writer, PageData(listOf(PodcastData("sara-og-monopolet", "Sara Og Monopolet"))))
    println(writer.buffer.toString())
}