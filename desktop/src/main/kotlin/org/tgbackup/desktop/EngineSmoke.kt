package org.tgbackup.desktop

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.tgbackup.app.data.Account
import org.tgbackup.app.tg.AuthUi
import kotlin.system.exitProcess

/**
 * Starts the real [DesktopApp] with one dummy account so TDLib comes up through the engine, then waits until the
 * account's auth state leaves [AuthUi.Starting]. No login is attempted (no phone, dummy api id/hash).
 */
fun main(args: Array<String>) {
    NativeTmp.ensureAsciiTmpdir("org.tgbackup.desktop.EngineSmokeKt", args)
    println("JAVA_TMPDIR ${System.getProperty("java.io.tmpdir")}")
    // Whole-run watchdog: lingering non-daemon threads or a hung close() can never block the run.
    Thread {
        Thread.sleep(90_000)
        println("ENGINE_FAIL timeout")
        System.out.flush()
        Runtime.getRuntime().halt(1)
    }.apply { isDaemon = true }.start()

    var code = 0
    try {
        val app = DesktopApp(AppDirs.dataDir().resolve("engine-smoke"), sleepOverride = false)
        try {
            app.store.updateConfig {
                it.copy(accounts = listOf(Account(id = "smoke", name = "smoke", apiId = 1, apiHash = "0".repeat(32), phone = "")))
            }
            app.start()
            val state = runBlocking {
                withTimeout(60_000) {
                    var s: AuthUi? = null
                    while (true) {
                        s = app.accounts.clients.value["smoke"]?.auth?.value
                        if (s != null && s !is AuthUi.Starting) break
                        delay(200)
                    }
                    s
                }
            }
            println("ENGINE_OK ${state::class.simpleName}")
        } finally {
            app.close()
        }
    } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
        println("ENGINE_FAIL timeout waiting for auth state")
        code = 1
    } catch (t: Throwable) {
        println("ENGINE_FAIL ${t::class.simpleName}: ${t.message}")
        code = 1
    }
    System.out.flush()
    Runtime.getRuntime().halt(code)
}
