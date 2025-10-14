import ommer.build.Dependencies

plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    implementation(Dependencies.slf4j.api)
    implementation(Dependencies.logback.classic)
    implementation(Dependencies.http4k.core)
    implementation(Dependencies.http4k.clientJetty)
    implementation(Dependencies.http4k.formatGson)
}

val mainClass = "ommer.client.ClientKt"

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = mainClass
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = mainClass
    }
}
