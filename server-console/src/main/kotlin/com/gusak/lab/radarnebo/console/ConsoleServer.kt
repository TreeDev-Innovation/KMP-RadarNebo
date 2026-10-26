package com.gusak.lab.radarnebo.console

import com.gusak.lab.radarnebo.debug.HttpExchange
import com.gusak.lab.radarnebo.debug.TrafficLog
import com.gusak.lab.radarnebo.debug.TrafficRecorder
import com.gusak.lab.radarnebo.server.ServerComponents
import com.gusak.lab.radarnebo.server.api.Health
import com.gusak.lab.radarnebo.server.api.health
import com.gusak.lab.radarnebo.server.config.DataSourceKind
import com.gusak.lab.radarnebo.server.config.ServerConfig
import com.gusak.lab.radarnebo.server.infrastructure.upstream.UpstreamRateLimiter
import com.gusak.lab.radarnebo.server.radarServer
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.NetworkInterface

/** A client seen since the console started, from the requests it made. */
data class ClientInfo(
    val address: String,
    val requests: Int,
    val errors: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val lastRequest: String,
    val lastStatus: Int?,
    val userAgent: String?,
    val usedKey: Boolean,
)

sealed interface ServerState {
    data object Stopped : ServerState
    data object Starting : ServerState
    data class Running(val port: Int, val since: Long) : ServerState
    data object Stopping : ServerState
    data class Failed(val message: String) : ServerState
}

/**
 * Runs the RadarNebo server inside the console, configured like `:server:run` (environment and server/.env),
 * and keeps what the console shows: traffic both ways, clients and the server's state.
 */
class ConsoleServer(private val baseConfig: ServerConfig = ServerConfig.fromEnv()) {
    private val log = LoggerFactory.getLogger("Console")
    private val lock = Mutex()
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Volatile
    private var components: ServerComponents? = null

    // One limiter for every run, so stopping and starting again can't exceed the alerts.in.ua budget.
    private val upstreamLimiter = UpstreamRateLimiter(baseConfig.upstream.effectivePerMinute)

    val traffic = TrafficLog(capacity = 500)

    private val _clients = MutableStateFlow<Map<String, ClientInfo>>(emptyMap())
    val clients: StateFlow<Map<String, ClientInfo>> = _clients.asStateFlow()

    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val _dataSource = MutableStateFlow(baseConfig.dataSource)

    /** Where the server gets its alerts; [switchDataSource] changes it, restarting a running server. */
    val dataSource: StateFlow<DataSourceKind> = _dataSource.asStateFlow()

    /** alerts.in.ua needs ALERTS_API_TOKEN (environment or server/.env). */
    val alertsInUaAvailable get() = !baseConfig.upstream.token.isNullOrBlank()

    val defaultPort get() = baseConfig.port
    val upstreamBudgetPerMinute get() = baseConfig.upstream.effectivePerMinute
    val upstreamUrl get() = baseConfig.upstream.baseUrl
    val archive get() = baseConfig.archive.jdbcUrl
    val clientKeys get() = baseConfig.clients.keys.size

    private val recorder = TrafficRecorder { exchange ->
        if (exchange.direction == HttpExchange.Direction.Incoming) noteClient(exchange)
        traffic.record(exchange)
    }

    /** The server's own report, as `/health` would give it; null while stopped. */
    fun health(): Health? = components?.let { health(it.situation, it.poller, it.upstreamBudgetPerMinute) }

    suspend fun start(port: Int) = lock.withLock {
        if (engine != null) return@withLock
        _state.value = ServerState.Starting
        val config = baseConfig.copy(port = port, dataSourceOverride = _dataSource.value)
        try {
            val built = withContext(Dispatchers.IO) {
                ServerComponents.create(config, traffic = recorder, upstreamLimiter = upstreamLimiter)
            }
            // Started outside withContext: it would wait for the server's coroutines, which run until it stops.
            val server = embeddedServer(CIO, port = port, host = "0.0.0.0") { radarServer(built) }
            try {
                server.start(wait = false)
            } catch (e: Throwable) {
                server.stop(0, 0)
                throw e
            }
            engine = server
            components = built
            log.info("Server started on port {} ({})", port, config.dataSource)
            _state.value = ServerState.Running(port, System.currentTimeMillis())
        } catch (e: Throwable) {
            log.error("Server failed to start on port $port", e)
            _state.value = ServerState.Failed(e.message ?: e::class.simpleName ?: "error")
        }
    }

    suspend fun stop() = lock.withLock {
        val server = engine ?: return@withLock
        _state.value = ServerState.Stopping
        withContext(Dispatchers.IO) { server.stop(gracePeriodMillis = 500, timeoutMillis = 3_000) }
        engine = null
        components = null
        log.info("Server stopped")
        _state.value = ServerState.Stopped
    }

    /** Serves alerts from [kind] from now on; a running server restarts on the same port to switch. */
    suspend fun switchDataSource(kind: DataSourceKind) {
        if (kind == _dataSource.value || (kind == DataSourceKind.AlertsInUa && !alertsInUaAvailable)) return
        _dataSource.value = kind
        log.info("Data source set to {}", kind)
        val running = _state.value as? ServerState.Running ?: return
        stop()
        start(running.port)
    }

    fun clearClients() = _clients.update { emptyMap() }

    private fun noteClient(exchange: HttpExchange) {
        val address = exchange.remoteAddress ?: return
        val failed = exchange.status == null || exchange.status!! >= 400
        val usedKey = exchange.requestHeaders.any { it.first.equals("Authorization", ignoreCase = true) } || "token=" in exchange.url
        _clients.update { all ->
            val old = all[address]
            all + (address to ClientInfo(
                address = address,
                requests = (old?.requests ?: 0) + 1,
                errors = (old?.errors ?: 0) + if (failed) 1 else 0,
                firstSeen = old?.firstSeen ?: exchange.startedAt,
                lastSeen = exchange.startedAt,
                lastRequest = "${exchange.method} ${exchange.path}",
                lastStatus = exchange.status,
                userAgent = exchange.userAgent ?: old?.userAgent,
                usedKey = usedKey || old?.usedKey == true,
            ))
        }
    }

    companion object {
        /** Addresses other devices can use to reach this machine, IPv4 only. */
        fun lanAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
        }.getOrDefault(emptyList())
    }
}
