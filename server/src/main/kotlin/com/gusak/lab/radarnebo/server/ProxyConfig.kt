package com.gusak.lab.radarnebo.server

import com.gusak.lab.radarnebo.data.AlertsApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * alerts.in.ua limits: soft limit 8-10 requests per minute per IP, hard limit 12 per minute per IP.
 * The proxy never exceeds [HARD_CEILING_PER_MINUTE], which sits at the bottom of the soft range, so a
 * misconfiguration can't get the token or IP blocked.
 */
data class ProxyConfig(
    val upstreamToken: String?,
    val upstreamBaseUrl: String = AlertsApi.DEFAULT_BASE_URL,
    val port: Int = 8080,
    val maxUpstreamPerMinute: Int = DEFAULT_PER_MINUTE,
    val pollInterval: Duration = 15.seconds,
    /** When non-empty, clients must send `Authorization: Bearer <key>` or `?token=<key>`. */
    val clientKeys: Set<String> = emptySet(),
    val clientRequestsPerMinute: Int = 120,
) {
    val demo get() = upstreamToken.isNullOrBlank()

    /** Upstream budget after clamping to the safe ceiling. */
    val effectivePerMinute get() = maxUpstreamPerMinute.coerceIn(1, HARD_CEILING_PER_MINUTE)

    /** Poll no faster than the budget allows, evenly spaced through the minute. */
    val effectivePollInterval: Duration
        get() = maxOf(pollInterval, (60_000.0 / effectivePerMinute).milliseconds)

    companion object {
        const val HARD_CEILING_PER_MINUTE = 8
        const val DEFAULT_PER_MINUTE = 6

        fun fromEnv(env: Map<String, String> = System.getenv()) = ProxyConfig(
            upstreamToken = env["ALERTS_API_TOKEN"]?.takeIf { it.isNotBlank() },
            upstreamBaseUrl = env["UPSTREAM_BASE_URL"]?.takeIf { it.isNotBlank() } ?: AlertsApi.DEFAULT_BASE_URL,
            port = env["PORT"]?.toIntOrNull() ?: 8080,
            maxUpstreamPerMinute = env["UPSTREAM_MAX_PER_MINUTE"]?.toIntOrNull() ?: DEFAULT_PER_MINUTE,
            pollInterval = (env["POLL_INTERVAL_SECONDS"]?.toIntOrNull() ?: 15).seconds,
            clientKeys = env["PROXY_CLIENT_KEYS"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            clientRequestsPerMinute = env["CLIENT_REQUESTS_PER_MINUTE"]?.toIntOrNull() ?: 120,
        )
    }
}
