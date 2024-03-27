package ommer.build

object Dependencies {
    open class FromGroup(private val group: String, private val version: String) {
        fun name(name: String) = lazy { "$group:$name:$version" }
        fun name(name: String, version: String) = lazy { "$group:$name:$version" }
    }

    object slf4j : FromGroup("org.slf4j", Versions.slf4j) {
        val api by name("slf4j-api")
    }

    object logback : FromGroup("ch.qos.logback", Versions.logback) {
        val core by name("logback-core")
        val classic by name("logback-classic")
    }

    object http4k : FromGroup("org.http4k", Versions.http4k) {
        val core by name("http4k-core")
        val clientJetty by name("http4k-client-jetty")
        val formatGson by name("http4k-format-gson")
    }

    object mustache : FromGroup("com.github.spullara.mustache.java", Versions.mustacheJava) {
        val compiler by name("compiler")
    }
}
