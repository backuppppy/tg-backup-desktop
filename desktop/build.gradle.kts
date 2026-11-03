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
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("org.tgbackup.desktop.MainKt")
}

tasks.test {
    useJUnitPlatform()
    // Paths with Hebrew characters need a UTF-8 file-name encoding on POSIX hosts.
    environment("LC_ALL", "C.UTF-8")
}

tasks.register<JavaExec>("tdlibSmoke") {
    group = "verification"
    description = "Loads TDLib on this JVM and prints its version."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.tgbackup.desktop.spike.TdlibSmokeKt")
    environment("LC_ALL", "C.UTF-8")
}
