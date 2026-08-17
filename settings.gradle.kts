pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    // jitpack for NewPipeExtractor, which is not published to Maven Central. It is what lets the
    // app resolve YouTube ids by itself; before it, every clip needed a server running yt-dlp.
    repositories { google(); mavenCentral(); maven("https://jitpack.io") }
}
rootProject.name = "fieldstation42-tv"
include(":app")
