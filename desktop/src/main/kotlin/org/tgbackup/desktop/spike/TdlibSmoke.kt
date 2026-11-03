package org.tgbackup.desktop.spike

import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.OptionValueString
import kotlinx.coroutines.runBlocking
import org.tgbackup.desktop.AppDirs
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** Returns TDLib's `version` option without logging in. [dbDir] is only created, TDLib is not given parameters. */
suspend fun tdlibVersion(dbDir: Path): String {
    Files.createDirectories(dbDir)
    val client = TdlClient.create()
    try {
        return when (val r = client.getOption("version")) {
            is TdlResult.Success -> (r.result as? OptionValueString)?.value
                ?: error("version option is not a string: ${r.result}")
            is TdlResult.Failure -> error("getOption failed: ${r.code}: ${r.message}")
        }
    } finally {
        client.close()
    }
}

fun main() {
    try {
        val v = runBlocking { tdlibVersion(AppDirs.dataDir().resolve("tdlib-smoke")) }
        println("TDLIB_OK $v")
        exitProcess(0)
    } catch (t: Throwable) {
        println("TDLIB_FAIL ${t::class.simpleName}: ${t.message}")
        exitProcess(1)
    }
}
