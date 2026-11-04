pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Only for the WebView2 spike: webview.dll + WebView2Loader.dll are taken from this jar.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.webview") }
        }
    }
}

rootProject.name = "tg-backup-desktop"
include(":export-core", ":engine", ":desktop")
