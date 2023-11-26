plugins {
    kotlin("jvm") version "1.9.21"
}

group = "dr1ommer"
version = "0.1"

allprojects {
    apply(plugin = "kotlin")

    repositories {
        mavenCentral()
    }

    tasks.test {
        useJUnitPlatform()
    }

    kotlin {
        jvmToolchain(17)
    }
}
