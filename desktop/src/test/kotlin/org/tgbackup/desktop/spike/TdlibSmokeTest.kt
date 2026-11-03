package org.tgbackup.desktop.spike

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class TdlibSmokeTest {
    @Test
    fun tdlibReportsAVersion() {
        val root = Files.createTempDirectory("tdlib-smoke-test")
        val dir = Files.createDirectories(root.resolve("תיקייה עם רווח"))
        try {
            val version = runBlocking { withTimeout(30_000) { tdlibVersion(dir) } }
            assertTrue(Regex("""\d+\.\d+\.\d+""").containsMatchIn(version), "unexpected version: $version")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
