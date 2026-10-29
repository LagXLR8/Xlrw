pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

plugins {
    // One code base, one jar per Minecraft version (https://stonecutter.kikugie.dev)
    id("dev.kikugie.stonecutter") version "0.9.8"
    // Applies the right Loom variant for each Minecraft version
    id("dev.kikugie.loom-back-compat") version "0.4.2"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        versions("1.21.1", "1.21.11")
        // The code in src/ is written for this version; other versions are produced by Stonecutter comments
        vcsVersion = "1.21.1"
    }
}

rootProject.name = "xlrw"
