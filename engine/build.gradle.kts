// The Android app's engine (data, engine, tg, export), copied verbatim from tg-backup-apk
// (see docs/PORTING.md) and built on a plain JVM.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.4.21"
}

group = "org.tgbackup"
version = providers.gradleProperty("tgbVersion").get()

kotlin {
    jvmToolchain(17)
}

// The copied files import org.tgbackup.app.BuildConfig; generate it from the project version.
val generateBuildConfig = tasks.register("generateBuildConfig") {
    val outDir = layout.buildDirectory.dir("generated/buildconfig")
    val versionName = project.version.toString()
    inputs.property("versionName", versionName)
    outputs.dir(outDir)
    doLast {
        val f = outDir.get().file("org/tgbackup/app/BuildConfig.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(
            """
            package org.tgbackup.app

            object BuildConfig {
                const val VERSION_NAME: String = "$versionName"
            }
            """.trimIndent() + "\n",
        )
    }
}
sourceSets["main"].kotlin.srcDir(generateBuildConfig)

dependencies {
    implementation(project(":export-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("dev.g000sha256:tdl-coroutines-jvm:15.0.0")
    implementation("io.sentry:sentry:8.61.0")
    implementation("net.java.dev.jna:jna:5.19.1")
    implementation("net.java.dev.jna:jna-platform:5.19.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Paths with Hebrew characters need a UTF-8 file-name encoding on POSIX hosts.
    environment("LC_ALL", "C.UTF-8")
}
