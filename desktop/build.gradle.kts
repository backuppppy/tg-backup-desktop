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
