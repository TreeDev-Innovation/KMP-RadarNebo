package com.gusak.lab.radarnebo.server.infrastructure.upstream

import com.gusak.lab.radarnebo.server.application.AlertsDataSource
import com.gusak.lab.radarnebo.server.application.FetchResult
import com.gusak.lab.radarnebo.server.application.SituationReport
import com.gusak.lab.radarnebo.server.config.DataSourceKind
import com.gusak.lab.radarnebo.server.domain.alert.AirAlert
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A public Telegram channel of air-threat warnings, read from its web preview (`https://t.me/s/<channel>`, no
 * account or key): the Ukrainian Air Force's `kpszsu` names the weapon and where it is or is heading
 * ([AirForceMessages]). Each message is an alert for [activeFor] after it was posted, so the tracker sees a report
 * appear when the message does. Meant as an extra source ([com.gusak.lab.radarnebo.server.infrastructure.CompositeAlertsSource]),
 * whose alerts feed tracking only. The source owns the [client] it is given and closes it.
 */
class TelegramChannelSource(
    private val channel: String,
    private val client: HttpClient = AlertsInUaSource.defaultClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val activeFor: Duration = 20.minutes,
    override val pollInterval: Duration = 60.seconds,
) : AlertsDataSource {
    /** Only a primary source's kind is ever reported; this is a real-world feed like alerts.in.ua. */
    override val kind = DataSourceKind.AlertsInUa
    override val description get() = "t.me/s/$channel"
    override val providesObservations = false

    override suspend fun fetch(lastModified: String?): FetchResult = try {
        val response = client.get("https://t.me/s/$channel") { header(HttpHeaders.UserAgent, USER_AGENT) }
        if (response.status != HttpStatusCode.OK) {
            FetchResult.Failed("t.me/s/$channel answered ${response.status}", minWaitMs = pollInterval.inWholeMilliseconds)
        } else {
            val now = clock()
            val alerts = parsePage(response.bodyAsText(), channel).filter { now - it.startedAt.toEpochMilliseconds() in 0..activeFor.inWholeMilliseconds }
            FetchResult.Fresh(SituationReport(now, alerts))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FetchResult.Failed("t.me/s/$channel: ${e.message}", minWaitMs = pollInterval.inWholeMilliseconds)
    }

    override fun close() = client.close()

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (RadarNebo)"
        private val message = Regex(
            """data-post="([^"/]+)/(\d+)".*?<div class="tgme_widget_message_text[^"]*"[^>]*>(.*?)</div>.*?<time datetime="([^"]+)"""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val entity = Regex("""&(#\d+|amp|lt|gt|quot|#39|nbsp);""")

        /** Every message on a channel page that reads as an alert, oldest first. */
        internal fun parsePage(html: String, channel: String): List<AirAlert> = message.findAll(html).mapNotNull { m ->
            val (post, id, body, time) = m.destructured
            if (!post.equals(channel, ignoreCase = true)) return@mapNotNull null
            val text = unescape(body.replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), ""))
            val at = runCatching { Instant.parse(time) }.getOrNull() ?: return@mapNotNull null
            AirForceMessages.parse(id.toLong(), text, at)
        }.toList()

        private fun unescape(s: String) = entity.replace(s) { e ->
            when (val name = e.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "#39" -> "'"
                "nbsp" -> " "
                else -> name.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: e.value
            }
        }
    }
}
