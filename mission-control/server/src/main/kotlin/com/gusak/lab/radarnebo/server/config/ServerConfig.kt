package com.gusak.lab.radarnebo.server.config

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Where the server's alerts come from. Clients never see this: they only talk to the server.
 *
 * [key] is stored as the archive partition and served as `/health`'s `source`, so it must never change,
 * even if an entry is renamed.
 */
enum class DataSourceKind(val key: String) {
    AlertsInUa("AlertsInUa"),
    Simulation("Simulation"),
}

/** How the server builds tracks from what its source reports (`TRACKING`). */
enum class TrackingMode {
    /** Sightings when the source has surveillance, alerts otherwise. */
    Auto,

    /** From alerts alone, even when the source also sights targets. */
    Alerts,

    /** From sightings alone. */
    Sightings,

    /** Alerts and sightings fused into one picture. */
    Fused,
}

/** Everything the server is configured with; built from the environment by [fromEnv]. */
data class ServerConfig(
    val port: Int = 8080,
    val upstream: UpstreamConfig = UpstreamConfig(),
    val simulation: SimulationConfig = SimulationConfig(),
    val archive: ArchiveConfig = ArchiveConfig(),
    val clients: ClientApiConfig = ClientApiConfig(),
    /** Explicit choice; by default alerts.in.ua when a token is set, the simulation otherwise. */
    val dataSourceOverride: DataSourceKind? = null,
    /** Logs every alerts.in.ua request and response, body included, with the token masked. */
    val logHttp: Boolean = false,
    val tracking: TrackingMode = TrackingMode.Auto,
    /** Public Telegram warning channels read as extra sources for tracking (`TELEGRAM_CHANNELS`, e.g. `kpszsu`). */
    val telegramChannels: List<String> = emptyList(),
) {
    init {
        // 0 asks for any free port, as the tests do.
        require(port in 0..65_535) { "PORT must be between 0 and 65535" }
    }

    val dataSource: DataSourceKind
        get() = dataSourceOverride ?: if (upstream.token.isNullOrBlank()) DataSourceKind.Simulation else DataSourceKind.AlertsInUa

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            val errors = mutableListOf<String>()
            fun text(name: String) = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            // Unset or blank means the data-class default; a set value that doesn't parse is reported below.
            fun <T : Any> parse(name: String, kind: String, parse: (String) -> T?): T? =
                text(name)?.let { value -> parse(value).also { if (it == null) errors += "$name='$value' is not $kind" } }
            fun int(name: String) = parse(name, "an integer", String::toIntOrNull)
            fun bool(name: String) = parse(name, "true or false") { it.lowercase().toBooleanStrictOrNull() } ?: false
            val defaults = ServerConfig()
            val config = ServerConfig(
                port = int("PORT") ?: defaults.port,
                upstream = UpstreamConfig(
                    token = text("ALERTS_API_TOKEN"),
                    baseUrl = text("UPSTREAM_BASE_URL") ?: defaults.upstream.baseUrl,
                    maxPerMinute = int("UPSTREAM_MAX_PER_MINUTE") ?: defaults.upstream.maxPerMinute,
                    pollInterval = int("POLL_INTERVAL_SECONDS")?.seconds ?: defaults.upstream.pollInterval,
                ),
                simulation = SimulationConfig(
                    seed = parse("SIM_SEED", "an integer", String::toLongOrNull) ?: defaults.simulation.seed,
                    strikePeriod = int("SIM_STRIKE_PERIOD_MINUTES")?.minutes ?: defaults.simulation.strikePeriod,
                    tick = int("SIM_TICK_SECONDS")?.seconds ?: defaults.simulation.tick,
                    warmUp = int("SIM_WARM_UP_MINUTES")?.minutes ?: defaults.simulation.warmUp,
                ),
                archive = ArchiveConfig(
                    jdbcUrl = text("ARCHIVE_JDBC_URL") ?: defaults.archive.jdbcUrl,
                    retention = int("ARCHIVE_RETENTION_DAYS")?.days ?: defaults.archive.retention,
                ),
                clients = ClientApiConfig(
                    keys = (text("CLIENT_KEYS") ?: text("PROXY_CLIENT_KEYS")).orEmpty()
                        .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                    requestsPerMinute = int("CLIENT_REQUESTS_PER_MINUTE") ?: defaults.clients.requestsPerMinute,
                    historyRequestsPerMinute = int("HISTORY_REQUESTS_PER_MINUTE") ?: defaults.clients.historyRequestsPerMinute,
                    archiveRequestsPerMinute = int("ARCHIVE_REQUESTS_PER_MINUTE") ?: defaults.clients.archiveRequestsPerMinute,
                    trustProxyHeaders = bool("TRUST_PROXY_HEADERS"),
                ),
                dataSourceOverride = parse("DATA_SOURCE", "simulation, alerts_in_ua, alerts.in.ua or upstream") {
                    when (it.lowercase()) {
                        "simulation" -> DataSourceKind.Simulation
                        "alerts_in_ua", "alerts.in.ua", "upstream" -> DataSourceKind.AlertsInUa
                        else -> null
                    }
                },
                logHttp = bool("LOG_HTTP"),
                tracking = parse("TRACKING", "auto, alerts, sightings or fused") { value ->
                    TrackingMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                } ?: defaults.tracking,
                telegramChannels = text("TELEGRAM_CHANNELS").orEmpty().split(',').map { it.trim().removePrefix("@") }.filter { it.isNotEmpty() },
            )
            require(errors.isEmpty()) { "Invalid configuration: ${errors.joinToString("; ")}" }
            return config
        }
    }
}

/**
 * alerts.in.ua limits: soft limit 8-10 requests per minute per IP, hard limit 12 per minute per IP.
 * The server never exceeds [HARD_CEILING_PER_MINUTE], which sits at the bottom of the soft range, so a
 * misconfiguration can't get the token or IP blocked.
 */
data class UpstreamConfig(
    val token: String? = null,
    val baseUrl: String = DEFAULT_BASE_URL,
    val maxPerMinute: Int = DEFAULT_PER_MINUTE,
    val pollInterval: Duration = 15.seconds,
) {
    /** Upstream budget after clamping to the safe ceiling. */
    val effectivePerMinute get() = maxPerMinute.coerceIn(1, HARD_CEILING_PER_MINUTE)

    /** Poll no faster than the budget allows, evenly spaced through the minute. */
    val effectivePollInterval: Duration
        get() = maxOf(pollInterval, (60_000.0 / effectivePerMinute).milliseconds)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.alerts.in.ua"
        const val HARD_CEILING_PER_MINUTE = 8
        const val DEFAULT_PER_MINUTE = 6
    }
}

/**
 * The combined-strike simulation. A massive drone and missile strike starts every [strikePeriod]; front-line
 * activity (guided bombs, tactical aviation, reconnaissance drones, small drone groups) runs continuously.
 * Everything is seeded from [seed] and the time slot, so restarts see the same attacks.
 */
data class SimulationConfig(
    val seed: Long = DEFAULT_SEED,
    val strikePeriod: Duration = 6.hours,
    /** How often the simulated feed is refreshed. */
    val tick: Duration = 10.seconds,
    /** History replayed on start, so tracks and alerts already have a past on the first request. */
    val warmUp: Duration = 2.hours,
) {
    init {
        require(strikePeriod >= MIN_STRIKE_PERIOD) { "A combined strike takes about 5 hours; SIM_STRIKE_PERIOD_MINUTES must be at least ${MIN_STRIKE_PERIOD.inWholeMinutes}" }
        require(tick.isPositive()) { "SIM_TICK_SECONDS must be positive" }
    }

    companion object {
        const val DEFAULT_SEED = 2022_02_24L
        val MIN_STRIKE_PERIOD = 3.hours
    }
}

/** The SQLite archive of finished alerts and tracks. */
data class ArchiveConfig(
    val jdbcUrl: String = DEFAULT_JDBC_URL,
    val retention: Duration = 45.days,
) {
    init {
        // Pruning keeps only what finished within the retention, so zero or less would empty the archive.
        require(retention.isPositive()) { "ARCHIVE_RETENTION_DAYS must be positive" }
    }

    /** True for ARCHIVE_JDBC_URL=memory: the archive is kept in memory, not in a database. */
    val inMemory: Boolean get() = jdbcUrl == IN_MEMORY

    companion object {
        const val DEFAULT_JDBC_URL = "jdbc:sqlite:data/radarnebo-archive.db"
        const val IN_MEMORY = "memory"
    }
}

/** What the server asks of its own clients (the app). */
data class ClientApiConfig(
    /** When non-empty, clients must send `Authorization: Bearer <key>` or `?token=<key>`, like alerts.in.ua. */
    val keys: Set<String> = emptySet(),
    val requestsPerMinute: Int = 120,
    /** alerts.in.ua allows 2 history requests per minute; the server keeps the same contract. */
    val historyRequestsPerMinute: Int = 2,
    /** Track archive reads are the heaviest calls (up to a week of tracks each), so they get their own budget. */
    val archiveRequestsPerMinute: Int = 10,
    /**
     * Behind a reverse proxy (Caddy in the deployment), every call comes from the proxy, so the client's IP is
     * taken from the last `X-Forwarded-For` entry, the one the proxy added. Only set it when the server can't be
     * reached around the proxy: otherwise a client could pick its own IP and dodge the rate limits.
     */
    val trustProxyHeaders: Boolean = false,
) {
    init {
        require(requestsPerMinute > 0) { "CLIENT_REQUESTS_PER_MINUTE must be positive" }
        require(historyRequestsPerMinute > 0) { "HISTORY_REQUESTS_PER_MINUTE must be positive" }
        require(archiveRequestsPerMinute > 0) { "ARCHIVE_REQUESTS_PER_MINUTE must be positive" }
    }
}
