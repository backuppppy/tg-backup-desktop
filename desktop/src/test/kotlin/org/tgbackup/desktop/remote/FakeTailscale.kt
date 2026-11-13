package org.tgbackup.desktop.remote

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** A [TailscaleCli] the tests steer: [status] and [serve] are read on every call; `serveOn` makes 443 ours. */
class FakeTailscale(
    @Volatile var status: TsStatus = TsStatus.NotInstalled,
    @Volatile var serve: ServeConfig = ServeConfig.None,
) : TailscaleCli {
    val serveOnCalls = AtomicInteger()
    val serveOffCalls = AtomicInteger()

    /** While true, `status` and `serveStatus` hang (as a stuck CLI would) until interrupted. */
    @Volatile var hang = false

    /** Counted down when a call starts hanging. */
    val hanging = CountDownLatch(1)

    private fun maybeHang() {
        if (!hang) return
        hanging.countDown()
        Thread.sleep(60_000)
    }

    override fun status(): TsStatus {
        maybeHang()
        return status
    }

    override fun serveStatus(dnsName: String?): ServeConfig {
        maybeHang()
        return serve
    }

    override fun serveOn(port: Int) {
        serveOnCalls.incrementAndGet()
        serve = ServeConfig.Ours
    }

    override fun serveOff() {
        serveOffCalls.incrementAndGet()
        serve = ServeConfig.None
    }
}
