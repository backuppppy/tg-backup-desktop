plugins {
    kotlin("jvm")
    application
}

group = "org.tgbackup"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

/** Must match the jcef-api version that jcefmaven 152.0.6 depends on. */
val JCEF_NATIVES = "jcef-17e805a+cef-152.0.6+g708dc14+chromium-152.0.7977.83"
val webviewNatives: Configuration by configurations.creating
val jcefNatives: Configuration by configurations.creating

dependencies {
    implementation(project(":export-core"))
    implementation("dev.g000sha256:tdl-coroutines-jvm:15.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.ktor:ktor-server-cio:3.6.1")
    // Embedded-view spike candidates.
    implementation("net.java.dev.jna:jna:5.19.1")
    implementation("me.friwi:jcefmaven:152.0.6")
    testImplementation(kotlin("test"))
    webviewNatives("com.github.webview:webview_java:1.3.0") { isTransitive = false }
    jcefNatives("me.friwi:jcef-natives-windows-amd64:$JCEF_NATIVES") { isTransitive = false }
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
    // The real app points java.io.tmpdir at its own ASCII directory so TDLib's DLLs can be loaded.
    System.getenv("TGB_NATIVE_TMP")?.let {
        doFirst { file(it).mkdirs() }
        jvmArgs("-Djava.io.tmpdir=$it")
    }
}

// ---------------------------------------------------------------------------------------------
// Embedded-view spike packaging (Windows only): app-image via jpackage, installer via Inno Setup
// (WiX is not on windows-latest). Output: build/spike/<candidate>/installer/TGBackup-Setup.exe,
// installing TGBackup.exe. All native libraries are placed inside the app dir at build time.
// ---------------------------------------------------------------------------------------------

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
        mainClass = "org.tgbackup.desktop.spike.WebView2WindowKt",
        excludeJars = listOf("jcef", "commons-", "gson", "gluegen", "jogl"),
        javaOptions = listOf(
            "-Dtgb.natives=\$APPDIR\\natives",
            "-Djna.boot.library.path=\$APPDIR\\natives",
            "-Djna.nounpack=true",
            "-Djna.nosys=true",
        ),
    ),
    SpikeCandidate(
        id = "jcef",
        task = "Jcef",
        mainClass = "org.tgbackup.desktop.spike.JcefWindowKt",
        excludeJars = listOf("jna-"),
        javaOptions = listOf("-Dtgb.jcef=\$APPDIR\\jcef"),
    ),
)

val innoCompiler: String by lazy {
    System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .map { File(it, "ISCC.exe") }.firstOrNull { it.isFile }?.path
        ?: "C:\\Program Files (x86)\\Inno Setup 6\\ISCC.exe"
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
        if (c.id == "webview2") {
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
        } else {
            into("jcef") {
                from({
                    val inner = zipTree(jcefNatives.singleFile).matching { include("*.tar.gz") }.singleFile
                    tarTree(resources.gzip(inner))
                })
            }
            // jcefmaven only reads this marker when installation is not skipped; keep it for parity.
            doLast { base.get().file("input/jcef/install.lock").asFile.writeText("") }
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
            "--add-modules", spikeModules,
            "--jlink-options", "--strip-native-commands --strip-debug --no-man-pages --no-header-files",
        )
        c.javaOptions.forEach { args += listOf("--java-options", it) }
        commandLine(args)
    }

    tasks.register<Exec>("package${c.task}") {
        group = "spike"
        description = "Builds TGBackup-Setup.exe (Inno Setup) for the ${c.task} candidate."
        dependsOn(appImage)
        onlyIf { isWindowsHost }
        val iss = base.map { it.file("TGBackup.iss") }
        val out = base.map { it.dir("installer") }
        doFirst {
            val image = base.get().dir("image/TGBackup").asFile
            iss.get().asFile.writeText(
                """
                [Setup]
                AppId=TGBackup-Spike-${c.id}
                AppName=TG Backup
                AppVersion=${project.version}
                AppPublisher=TG Backup
                DefaultDirName={autopf}\TGBackup
                DisableProgramGroupPage=yes
                OutputDir=${out.get().asFile.path}
                OutputBaseFilename=TGBackup-Setup
                Compression=lzma2
                SolidCompression=yes
                ArchitecturesAllowed=x64
                ArchitecturesInstallIn64BitMode=x64
                PrivilegesRequiredOverridesAllowed=dialog commandline
                UninstallDisplayIcon={app}\TGBackup.exe

                [Files]
                Source: "${image.path}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

                [Icons]
                Name: "{autoprograms}\TG Backup"; Filename: "{app}\TGBackup.exe"
                """.trimIndent() + "\n",
            )
        }
        commandLine(innoCompiler, "/Q", iss.get().asFile.path)
    }
}
