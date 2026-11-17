package org.tgbackup.desktop

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.AbstractLogger
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Where Ktor's WARN and ERROR lines go (set by DesktopApp). Without a provider SLF4J drops everything, so a dead
 * accept loop of the phone server left no trace (17/11). DEBUG, TRACE and INFO are dropped.
 */
object KtorLogSink {
    @Volatile
    var sink: (level: Level, logger: String, message: String, error: Throwable?) -> Unit = { _, _, _, _ -> }

    /** Never throws into the caller (Ktor): a failing sink is ignored. */
    fun emit(level: Level, logger: String, message: String, error: Throwable?) {
        if (level != Level.WARN && level != Level.ERROR) return
        runCatching { sink(level, logger, message, error) }
    }
}

internal class SinkLogger(private val loggerName: String) : AbstractLogger() {
    init {
        name = loggerName
    }

    override fun isTraceEnabled() = false
    override fun isTraceEnabled(marker: Marker?) = false
    override fun isDebugEnabled() = false
    override fun isDebugEnabled(marker: Marker?) = false
    override fun isInfoEnabled() = false
    override fun isInfoEnabled(marker: Marker?) = false
    override fun isWarnEnabled() = true
    override fun isWarnEnabled(marker: Marker?) = true
    override fun isErrorEnabled() = true
    override fun isErrorEnabled(marker: Marker?) = true

    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any?>?,
        throwable: Throwable?,
    ) {
        val text = runCatching { MessageFormatter.basicArrayFormat(messagePattern, arguments) }.getOrNull() ?: messagePattern.orEmpty()
        KtorLogSink.emit(level, loggerName, text, throwable)
    }
}

class KtorLogProvider : SLF4JServiceProvider {
    private val loggers = ConcurrentHashMap<String, Logger>()
    private val factory = ILoggerFactory { n -> loggers.getOrPut(n) { SinkLogger(n) } }
    private val markers = BasicMarkerFactory()
    private val mdc = NOPMDCAdapter()

    override fun getLoggerFactory(): ILoggerFactory = factory
    override fun getMarkerFactory(): IMarkerFactory = markers
    override fun getMDCAdapter(): MDCAdapter = mdc
    override fun getRequestedApiVersion(): String = "2.0.99"
    override fun initialize() = Unit
}
