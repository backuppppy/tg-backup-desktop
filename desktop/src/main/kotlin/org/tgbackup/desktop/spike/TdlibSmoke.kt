package org.tgbackup.desktop.spike

import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.LogStreamFile
import dev.g000sha256.tdl.dto.OptionValueString
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.tgbackup.desktop.AppDirs
import org.tgbackup.desktop.NativeTmp
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Returns TDLib's `version` option without logging in. TDLib gets no parameters; [dbDir] reaches it as the
 * log file `tdlib.log`, so the path (including non-ASCII characters) is really opened by TDLib.
 */
suspend fun tdlibVersion(dbDir: Path): String {
    Files.createDirectories(dbDir)
    val client = TdlClient.create()
    try {
        val log = client.setLogStream(LogStreamFile(dbDir.resolve("tdlib.log").toString(), 1L shl 20, false))
        if (log is TdlResult.Failure) error("setLogStream failed: ${log.code}: ${log.message}")
        client.setLogVerbosityLevel(5)
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
    if (System.getenv("TGB_NATIVE_TMP_OFF") != "1") println("NATIVE_TMP ${NativeTmp.apply() ?: "default"}")
    println("JAVA_TMPDIR ${System.getProperty("java.io.tmpdir")}")
    try {
        val v = runBlocking { withTimeout(30_000) { tdlibVersion(AppDirs.dataDir().resolve("tdlib-smoke")) } }
        println("TDLIB_OK $v")
        exitProcess(0)
    } catch (t: TimeoutCancellationException) {
        println("TDLIB_FAIL timeout")
        exitProcess(1)
    } catch (t: Throwable) {
        println("TDLIB_FAIL ${t::class.simpleName}: ${t.message}")
        exitProcess(1)
    }
}
