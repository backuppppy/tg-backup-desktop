package org.tgbackup.desktop.remote

import java.util.concurrent.atomic.AtomicInteger

/** A [TailscaleCli] the tests steer: [status] and [serve] are read on every call; `serveOn` makes 443 ours. */
class FakeTailscale(
    @Volatile var status: TsStatus = TsStatus.NotInstalled,
    @Volatile var serve: ServeConfig = ServeConfig.None,
) : TailscaleCli {
    val serveOnCalls = AtomicInteger()
    val serveOffCalls = AtomicInteger()

    override fun status(): TsStatus = status

    override fun serveStatus(): ServeConfig = serve

    override fun serveOn(port: Int) {
        serveOnCalls.incrementAndGet()
        serve = ServeConfig.Ours
    }

    override fun serveOff() {
        serveOffCalls.incrementAndGet()
        serve = ServeConfig.None
    }
}
