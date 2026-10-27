package com.gusak.lab.radarnebo.console

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.UnsynchronizedAppenderBase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

data class LogLine(val id: Long, val time: Long, val level: Level, val logger: String, val message: String)

/** Everything the server logs (through Logback), the latest [capacity] lines, for the console's Logs tab. */
class LogCapture(private val capacity: Int = 3_000) {
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()
    private val nextId = AtomicLong()

    private val appender = object : UnsynchronizedAppenderBase<ILoggingEvent>() {
        override fun append(event: ILoggingEvent) {
            val message = event.formattedMessage + (event.throwableProxy?.let { "\n" + ThrowableProxyUtil.asString(it) } ?: "")
            val logger = event.loggerName.substringAfterLast('.')
            // Ids come from an atomic and the list from a compare-and-set update, so appending needs no lock.
            val line = LogLine(nextId.incrementAndGet(), event.timeStamp, event.level, logger, message)
            _lines.update { (it + line).takeLast(capacity) }
        }
    }

    /** Starts copying log lines here; the console output keeps getting them too. */
    fun install() {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        appender.context = context
        appender.name = "server-console"
        appender.start()
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender)
    }

    fun clear() = _lines.update { emptyList() }
}
