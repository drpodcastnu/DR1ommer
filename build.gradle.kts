import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.20"
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
        jvmToolchain(25)
	compilerOptions {
	    jvmTarget = JvmTarget.JVM_21
	}
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
