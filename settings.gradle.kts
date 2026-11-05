pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // The WebView2 window: webview.dll + WebView2Loader.dll are taken from this jar.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.webview") }
        }
    }
}

rootProject.name = "tg-backup-desktop"
include(":export-core", ":engine", ":desktop")
