package com.gusak.lab.radarnebo.server.api

import com.gusak.lab.radarnebo.server.application.SituationPoller
import com.gusak.lab.radarnebo.server.application.SituationService
import com.gusak.lab.radarnebo.server.config.DataSourceKind
import io.ktor.http.ContentType
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** For operators and load balancers; not part of the client API, so it needs no client key. */
@Serializable
data class Health(
    val status: String,
    val source: String,
    val simulated: Boolean,
    val upstreamBudgetPerMinute: Int?,
    val upstreamRequestsLastMinute: Int,
    val upstreamRequestsTotal: Long,
    val pollIntervalSeconds: Long,
    val lastAttemptAt: Long?,
    val nextPollAt: Long?,
    val dataVersion: Long?,
    val dataConfirmedAt: Long?,
    val alerts: Int,
    val tracks: Int,
    val activeTracks: Int,
    val lastError: String?,
)

internal fun Route.healthRoutes(situation: SituationService, poller: SituationPoller, upstreamBudgetPerMinute: Int?) {
    get("/health") {
        call.respond(health(situation, poller, upstreamBudgetPerMinute))
    }
    // The same report for people: open the server's address in a browser to see whether it is running and getting data.
    get("/") {
        call.respondText(statusPage(health(situation, poller, upstreamBudgetPerMinute)), ContentType.Text.Html)
    }
}

/** What `/health` reports; the server console shows it too. */
fun health(situation: SituationService, poller: SituationPoller, upstreamBudgetPerMinute: Int?): Health {
    val board = situation.board
    val status = poller.status
    val source = poller.source
    return Health(
        status = when {
            board == null -> "warming_up"
            status.lastError != null -> "degraded"
            else -> "ok"
        },
        source = source.kind.key,
        simulated = situation.simulated,
        upstreamBudgetPerMinute = upstreamBudgetPerMinute,
        upstreamRequestsLastMinute = source.requestsInLastMinute,
        upstreamRequestsTotal = source.requestsTotal,
        pollIntervalSeconds = source.pollInterval.inWholeSeconds,
        lastAttemptAt = status.lastAttemptAt,
        nextPollAt = status.nextPollAt,
        dataVersion = board?.version,
        dataConfirmedAt = board?.confirmedAt?.toEpochMilliseconds(),
        alerts = board?.alerts?.size ?: 0,
        tracks = situation.tracks.size,
        activeTracks = situation.tracks.count { it.active },
        lastError = status.lastError,
    )
}

private fun statusPage(h: Health): String {
    val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    fun at(ms: Long?) = ms?.let { time.format(Instant.ofEpochMilli(it)) } ?: "–"
    fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val (color, headline) = when (h.status) {
        "ok" -> "#2e9d5b" to "Running · getting data"
        "warming_up" -> "#d08a1f" to "Running · waiting for the first data"
        else -> "#d08a1f" to "Running · its data source is failing"
    }
    val rows = listOfNotNull(
        "Source" to if (h.simulated) "Simulation (not the real situation)" else if (h.source == DataSourceKind.AlertsInUa.key) "alerts.in.ua" else h.source,
        h.upstreamBudgetPerMinute?.let { "Upstream" to "${h.upstreamRequestsLastMinute} of $it requests in the last minute · ${h.upstreamRequestsTotal} in total" },
        "Polled" to "every ${h.pollIntervalSeconds} s · last ${at(h.lastAttemptAt)} · next ${at(h.nextPollAt)}",
        "Data confirmed" to at(h.dataConfirmedAt),
        "Alerts" to h.alerts.toString(),
        "Tracks" to "${h.activeTracks} active of ${h.tracks}",
        h.lastError?.let { "Last error" to it },
    )
    return """
        <!doctype html>
        <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <meta http-equiv="refresh" content="5"><title>RadarNebo server</title>
        <style>
          :root { color-scheme: light dark; --bg: #f6f7f9; --fg: #1b1f24; --muted: #5f6b7a; --card: #fff; }
          @media (prefers-color-scheme: dark) { :root { --bg: #0d1117; --fg: #e6edf3; --muted: #8b98a8; --card: #161b22; } }
          body { margin: 0; padding: 32px 16px; background: var(--bg); color: var(--fg); font: 15px/1.5 system-ui, sans-serif; }
          main { max-width: 560px; margin: 0 auto; background: var(--card); border-radius: 12px; padding: 20px 24px; }
          h1 { font-size: 20px; margin: 0 0 4px; } .status { display: flex; align-items: center; gap: 8px; font-weight: 600; }
          .dot { width: 10px; height: 10px; border-radius: 50%; background: $color; }
          table { width: 100%; border-collapse: collapse; margin-top: 16px; } td { padding: 4px 0; vertical-align: top; }
          td:first-child { color: var(--muted); width: 130px; } footer { margin-top: 16px; color: var(--muted); font-size: 13px; }
        </style></head><body><main>
        <h1>RadarNebo server</h1>
        <div class="status"><span class="dot"></span>${esc(headline)}</div>
        <table>${rows.joinToString("") { (k, v) -> "<tr><td>${esc(k)}</td><td>${esc(v)}</td></tr>" }}</table>
        <footer>Refreshes every 5 s · JSON at <a href="/health">/health</a></footer>
        </main></body></html>
    """.trimIndent()
}
