plugins {
    kotlin("jvm")
    application
}

group = "org.tgbackup"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":export-core"))
    implementation("dev.g000sha256:tdl-coroutines-jvm:15.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.ktor:ktor-server-cio:3.6.1")
    testImplementation(kotlin("test"))
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
