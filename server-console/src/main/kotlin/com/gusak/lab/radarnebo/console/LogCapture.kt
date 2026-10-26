package com.gusak.lab.radarnebo.console

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.slf4j.LoggerFactory

data class LogLine(val id: Long, val time: Long, val level: String, val logger: String, val message: String)

/** Everything the server logs (through Logback), the latest [capacity] lines, for the console's Logs tab. */
class LogCapture(private val capacity: Int = 3_000) {
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()
    private var nextId = 0L

    private val appender = object : AppenderBase<ILoggingEvent>() {
        override fun append(event: ILoggingEvent) {
            val message = event.formattedMessage + (event.throwableProxy?.let { "\n" + ThrowableProxyUtil.asString(it) } ?: "")
            val logger = event.loggerName.substringAfterLast('.')
            synchronized(this@LogCapture) {
                val line = LogLine(++nextId, event.timeStamp, event.level.toString(), logger, message)
                _lines.update { (it + line).takeLast(capacity) }
            }
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
