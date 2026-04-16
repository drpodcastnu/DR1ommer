import ommer.build.Dependencies

plugins {
    application
    id("com.gradleup.shadow") version "9.4.1"
}

dependencies {
    implementation(Dependencies.slf4j.api)
    implementation(Dependencies.logback.classic)
    implementation(Dependencies.http4k.core)
    implementation(Dependencies.http4k.clientJetty)
    implementation(Dependencies.http4k.formatGson)
    implementation(Dependencies.mustache.compiler)
}

application {
    mainClass = "ommer.client.ClientKt"
}

tasks.shadowJar {
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = mainClass
    }
}

