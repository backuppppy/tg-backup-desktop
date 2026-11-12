import java.security.MessageDigest

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.4.21"
    application
}

group = "org.tgbackup"
version = providers.gradleProperty("tgbVersion").get()

kotlin {
    jvmToolchain(17)
}

/**
 * Hashes of the DLLs taken from com.github.webview:webview_java:1.3.0 (jitpack; jar SHA-256
 * 9d5a09fe1a470a4d862dcac67d0ee1f1533b251c747a275574ca6fb34bb1b0e6), computed from the files that
 * :desktop:stageWebView2 staged from that jar (the same build that fed green CI run 41665060301).
 * A changed artifact behind the tag fails the build.
 */
val WEBVIEW_DLL_SHA256 = mapOf(
    "webview.dll" to "92e1ce93eb671e4c5120d0fb6a739e31173cf4fc7f7f08f6365b115d8d4d82ca",
    "WebView2Loader.dll" to "fb82ac089963e1dee932acce2f86ba34c128770cec4b60bcfd86f95d29e971ab",
)
val webviewNatives: Configuration by configurations.creating

// The DSN of the Sentry project (a public ingest key); -Ptgb.sentryDsn=... overrides it.
val sentryDsn = providers.gradleProperty("tgb.sentryDsn")
    .orElse("https://4d012b67000db5cb50794b15ff6c165c@o4510215210598400.ingest.de.sentry.io/4512414932729936")
val generateDesktopBuildConfig = tasks.register("generateDesktopBuildConfig") {
    val outDir = layout.buildDirectory.dir("generated/desktopbuildconfig")
    val dsn = sentryDsn
    inputs.property("sentryDsn", dsn)
    outputs.dir(outDir)
    doLast {
        val f = outDir.get().file("org/tgbackup/desktop/DesktopBuildConfig.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(
            """
            package org.tgbackup.desktop

            object DesktopBuildConfig {
                const val SENTRY_DSN: String = "${dsn.get()}"
            }
            """.trimIndent() + "\n",
        )
    }
}
sourceSets["main"].kotlin.srcDir(generateDesktopBuildConfig)

dependencies {
    implementation(project(":export-core"))
    implementation(project(":engine"))
    implementation("dev.g000sha256:tdl-coroutines-jvm:15.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("io.ktor:ktor-server-cio:3.6.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.6.1")
    implementation("io.ktor:ktor-server-sse:3.6.1")
    implementation("io.ktor:ktor-server-auto-head-response:3.6.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("io.sentry:sentry:8.61.0")
    implementation("net.java.dev.jna:jna:5.19.1")
    implementation("net.java.dev.jna:jna-platform:5.19.1")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:3.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    webviewNatives("com.github.webview:webview_java:1.3.0") { isTransitive = false }
}

application {
    mainClass.set("org.tgbackup.desktop.MainKt")
}

tasks.test {
    useJUnitPlatform()
    // Paths with Hebrew characters need a UTF-8 file-name encoding on POSIX hosts.
    environment("LC_ALL", "C.UTF-8")
    // CI: run the program under a Hebrew+space data dir without touching Gradle's own env.
    System.getenv("TGB_TEST_LOCALAPPDATA")?.let { environment("LOCALAPPDATA", it) }
}

tasks.register<JavaExec>("tdlibSmoke") {
    group = "verification"
    description = "Loads TDLib on this JVM and prints its version."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.tgbackup.desktop.spike.TdlibSmokeKt")
    environment("LC_ALL", "C.UTF-8")
    System.getenv("TGB_TEST_LOCALAPPDATA")?.let {
        environment("LOCALAPPDATA", it)
        // TDLib's native DLL is extracted to java.io.tmpdir, which Windows derives from TEMP/TMP.
        environment("TEMP", "$it\\tmp")
        environment("TMP", "$it\\tmp")
    }
    // The program itself moves java.io.tmpdir to an ASCII dir (NativeTmp); TGB_NATIVE_TMP_OFF=1 disables
    // that so CI can probe the library's behaviour with the Hebrew TEMP above.
    System.getenv("TGB_NATIVE_TMP_OFF")?.let { environment("TGB_NATIVE_TMP_OFF", it) }
}

tasks.register<JavaExec>("engineSmoke") {
    group = "verification"
    description = "Starts DesktopApp with a dummy account and waits for TDLib to answer."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.tgbackup.desktop.EngineSmokeKt")
    environment("LC_ALL", "C.UTF-8")
    System.getenv("TGB_TEST_LOCALAPPDATA")?.let {
        environment("LOCALAPPDATA", it)
        // TDLib's native DLL is extracted to java.io.tmpdir, which Windows derives from TEMP/TMP.
        environment("TEMP", "$it\\tmp")
        environment("TMP", "$it\\tmp")
    }
    // The program itself moves java.io.tmpdir to an ASCII dir (NativeTmp); TGB_NATIVE_TMP_OFF=1 disables
    // that so CI can probe the library's behaviour with the Hebrew TEMP above.
    System.getenv("TGB_NATIVE_TMP_OFF")?.let { environment("TGB_NATIVE_TMP_OFF", it) }
}

tasks.register<JavaExec>("devServer") {
    group = "application"
    description = "Runs the engine and the server on 127.0.0.1:8765 (TGB_DEV_PORT) without a window; prints DEV_URL."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.tgbackup.desktop.DevServerKt")
    environment("LC_ALL", "C.UTF-8")
    System.getenv("TGB_TEST_LOCALAPPDATA")?.let {
        environment("LOCALAPPDATA", it)
        // TDLib's native DLL is extracted to java.io.tmpdir, which Windows derives from TEMP/TMP.
        environment("TEMP", "$it\\tmp")
        environment("TMP", "$it\\tmp")
    }
    System.getenv("TGB_NATIVE_TMP_OFF")?.let { environment("TGB_NATIVE_TMP_OFF", it) }
}

// ---------------------------------------------------------------------------------------------
// Windows packaging (TGBackup.exe = org.tgbackup.desktop.MainKt): app-image via jpackage, then the installer via
// Inno Setup with desktop/installer/TGBackup.iss.
// Output: build/spike/<candidate>/installer/TGBackup-Setup.exe (+ installer-kind.txt: inno),
// installing TGBackup.exe. All native libraries are placed inside the app dir at build time.
// ---------------------------------------------------------------------------------------------

/** The program icon (exe, installer, shortcuts, Add/Remove Programs), made by tools/make-icons.py. */
val programIcon = layout.projectDirectory.file("src/main/icons/TGBackup.ico")

/** The window's own icons (taskbar button, also pinned; title bar; Alt+Tab): the tray's cloud, by make-icons.py. */
val windowIcon = layout.projectDirectory.file("src/main/icons/TGBackup-window.ico")

val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val spikeLauncher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
val spikeModules = listOf(
    "java.base", "java.desktop", "java.logging", "java.management", "java.naming", "java.net.http",
    "java.sql", "java.xml", "jdk.charsets", "jdk.crypto.ec", "jdk.unsupported", "jdk.zipfs",
).joinToString(",")

data class SpikeCandidate(
    val id: String,
    val task: String,
    val mainClass: String,
    val excludeJars: List<String>,
    val javaOptions: List<String>,
)

val spikeCandidates = listOf(
    SpikeCandidate(
        id = "webview2",
        task = "WebView2",
        mainClass = "org.tgbackup.desktop.MainKt",
        excludeJars = emptyList(),
        javaOptions = listOf(
            "-Dtgb.natives=\$APPDIR\\natives",
            "-Djna.boot.library.path=\$APPDIR\\natives",
            "-Djna.nounpack=true",
            "-Djna.nosys=true",
        ),
    ),
)

fun onPath(exe: String): String? =
    System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .map { File(it, exe) }.firstOrNull { it.isFile }?.path

/** The installer script; the package task passes AppVersion, ImageDir, OutDir and IconFile with /D. */
val installerScript = layout.projectDirectory.file("installer/TGBackup.iss")
/** The real Inno Setup 6 first: an ISCC.exe on PATH can be a Chocolatey shim with no Languages\Hebrew.isl beside it. */
val innoCompiler: String by lazy {
    val default = "C:\\Program Files (x86)\\Inno Setup 6\\ISCC.exe"
    if (File(default).isFile) default else onPath("ISCC.exe") ?: default
}

for (c in spikeCandidates) {
    val base = layout.buildDirectory.dir("spike/${c.id}")

    val stage = tasks.register<Sync>("stage${c.task}") {
        group = "spike"
        description = "Collects jars and natives for the ${c.task} candidate."
        into(base.map { it.dir("input") })
        from(tasks.jar)
        from(configurations.runtimeClasspath.map { cp ->
            cp.filter { f -> c.excludeJars.none { f.name.startsWith(it) } }
        })
        run {
            into("natives") {
                from({ webviewNatives.map { zipTree(it) } }) {
                    include("natives/amd64/windows/*.dll")
                }
                from({
                    configurations.runtimeClasspath.get().filter { it.name.startsWith("jna-") }.map { zipTree(it) }
                }) {
                    include("com/sun/jna/win32-x86-64/jnidispatch.dll")
                }
                eachFile { path = "natives/$name" }
                includeEmptyDirs = false
            }
            // The program icon, and the window's (AppWindow loads it from -Dtgb.natives at run time).
            into("natives") { from(programIcon, windowIcon) }
            // jitpack builds from a movable tag: pin the DLLs we ship to known SHA-256 hashes.
            doLast {
                val dir = base.get().dir("input/natives").asFile
                for ((dll, expected) in WEBVIEW_DLL_SHA256) {
                    val f = File(dir, dll)
                    check(f.isFile) { "$dll missing from ${dir.path}" }
                    val actual = MessageDigest.getInstance("SHA-256")
                        .digest(f.readBytes()).joinToString("") { "%02x".format(it) }
                    check(actual == expected) {
                        "SHA-256 mismatch for $dll: expected $expected, got $actual"
                    }
                    println("SHA256_OK $dll $actual")
                }
            }
        }
    }

    val appImage = tasks.register<Exec>("appImage${c.task}") {
        group = "spike"
        description = "jpackage app-image for the ${c.task} candidate."
        dependsOn(stage)
        onlyIf { isWindowsHost }
        val dest = base.map { it.dir("image") }
        doFirst { delete(dest) }
        val args = mutableListOf(
            spikeLauncher.get().metadata.installationPath.file("bin/jpackage.exe").asFile.path,
            "--type", "app-image",
            "--name", "TGBackup",
            "--app-version", project.version.toString(),
            "--vendor", "TG Backup",
            "--dest", dest.get().asFile.path,
            "--input", base.get().dir("input").asFile.path,
            "--main-jar", tasks.jar.get().archiveFileName.get(),
            "--main-class", c.mainClass,
            "--icon", programIcon.asFile.path,
            "--add-modules", spikeModules,
            "--jlink-options", "--strip-native-commands --strip-debug --no-man-pages --no-header-files",
        )
        c.javaOptions.forEach { args += listOf("--java-options", it) }
        commandLine(args)
    }

    tasks.register<Exec>("package${c.task}") {
        group = "spike"
        description = "Builds TGBackup-Setup.exe (Inno Setup, desktop/installer/TGBackup.iss) for the ${c.task} candidate."
        dependsOn(appImage)
        onlyIf { isWindowsHost }
        val out = base.map { it.dir("installer") }
        val image = base.map { it.dir("image/TGBackup") }
        inputs.file(installerScript)
        doFirst {
            delete(out)
            out.get().asFile.mkdirs()
        }
        commandLine(
            innoCompiler,
            "/DAppVersion=${project.version}",
            "/DImageDir=${image.get().asFile.path}",
            "/DOutDir=${out.get().asFile.path}",
            "/DIconFile=${programIcon.asFile.path}",
            installerScript.asFile.path,
        )
        doLast {
            val dir = out.get().asFile
            check(File(dir, "TGBackup-Setup.exe").isFile) { "TGBackup-Setup.exe was not produced" }
            File(dir, "installer-kind.txt").writeText("inno")
            println("INSTALLER_KIND inno")
        }
    }
}
