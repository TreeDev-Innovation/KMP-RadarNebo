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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

sealed interface ServerState {
    data object Stopped : ServerState
    data object Starting : ServerState
    data class Running(val port: Int, val since: Long) : ServerState
    data object Stopping : ServerState
    data class Failed(val message: String) : ServerState
}

/**
 * Runs the RadarNebo server inside the console, configured like `:mission-control:server:run` (environment and mission-control/server/.env),
 * and keeps what the console shows: traffic both ways, clients and the server's state.
 */
class ConsoleServer(private val baseConfig: ServerConfig) {
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

    /** alerts.in.ua needs ALERTS_API_TOKEN (environment or mission-control/server/.env). */
    val alertsInUaAvailable get() = !baseConfig.upstream.token.isNullOrBlank()

    val defaultPort get() = baseConfig.port
    val upstreamUrl get() = baseConfig.upstream.baseUrl
    val archive get() = baseConfig.archive.jdbcUrl
    val clientKeys get() = baseConfig.clients.keys.size

    /** A key the console's own client can use; null when the server doesn't ask for one. */
    val clientKey get() = baseConfig.clients.keys.firstOrNull()

    private val recorder = TrafficRecorder { exchange ->
        if (exchange.direction == HttpExchange.Direction.Incoming) noteClient(exchange)
        traffic.record(exchange)
    }

    /** The server's own report, as `/health` would give it; null while stopped. */
    fun health(): Health? = components?.let { health(it.situation, it.poller, it.upstreamBudgetPerMinute) }

    suspend fun start(port: Int) = lock.withLock { startLocked(port) }

    suspend fun stop() = lock.withLock { stopLocked() }

    /** Serves alerts from [kind] from now on; a running server restarts on the same port to switch. */
    suspend fun switchDataSource(kind: DataSourceKind) = lock.withLock {
        if (kind == _dataSource.value || (kind == DataSourceKind.AlertsInUa && !alertsInUaAvailable)) return@withLock
        _dataSource.value = kind
        log.info("Data source set to {}", kind)
        // Under one lock, so no start or stop can slip in between and leave the server on the old source.
        val running = _state.value as? ServerState.Running ?: return@withLock
        stopLocked()
        startLocked(running.port)
    }

    fun clearClients() = _clients.update { emptyMap() }

    /** Only while [lock] is held. */
    private suspend fun startLocked(port: Int) {
        if (engine != null) return
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
                // Nothing else holds these yet, so a failed start (a taken port) would leak the archive and poller.
                server.stop(0, 0)
                built.close()
                throw e
            }
            engine = server
            components = built
            log.info("Server started on port {} ({})", port, config.dataSource)
            _state.value = ServerState.Running(port, System.currentTimeMillis())
        } catch (e: Exception) {
            // CIO reports a taken port by cancelling its own job, so only our own cancellation propagates.
            if (e is CancellationException && !currentCoroutineContext().isActive) {
                _state.value = ServerState.Stopped
                throw e
            }
            log.error("Server failed to start on port {}", port, e)
            _state.value = ServerState.Failed(e.message ?: e::class.simpleName ?: "error")
        }
    }

    /** Only while [lock] is held. Whatever happens while stopping, the console ends up stopped and can start again. */
    private suspend fun stopLocked() {
        val server = engine ?: return
        _state.value = ServerState.Stopping
        try {
            withContext(Dispatchers.IO) { server.stop(gracePeriodMillis = 500, timeoutMillis = 3_000) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Server didn't stop cleanly", e)
        } finally {
            engine = null
            components?.close()
            components = null
            _state.value = ServerState.Stopped
        }
        log.info("Server stopped")
    }

    private fun noteClient(exchange: HttpExchange) {
        val address = exchange.remoteAddress ?: return
        _clients.update { it + (address to it[address].after(exchange, address)) }
    }
}
