package com.gusak.lab.radarnebo.console

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.qos.logback.classic.Level
import com.gusak.lab.radarnebo.server.api.Health
import com.gusak.lab.radarnebo.server.config.DataSourceKind
import com.gusak.lab.radarnebo.ui.debug.TrafficInspector
import com.gusak.lab.radarnebo.ui.formatClock
import com.gusak.lab.radarnebo.ui.radarColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI

private val log = LoggerFactory.getLogger("Console")

private const val REFRESH_MS = 1_000L

private enum class ConsoleTab(val label: String) { Requests("Requests"), Clients("Clients"), Logs("Logs") }

@Composable
fun ConsoleScreen(server: ConsoleServer, logs: LogCapture) {
    val state by server.state.collectAsState()
    val running = state as? ServerState.Running
    // The server's own report, refreshed every second while it runs; not polled while it doesn't.
    val health by produceState<Health?>(null, running) {
        // The state outlives a change of key, so the last report is cleared by hand once the server stops.
        if (running == null) {
            value = null
            return@produceState
        }
        while (true) {
            value = server.health()
            delay(REFRESH_MS)
        }
    }
    var tab by rememberSaveable { mutableStateOf(ConsoleTab.Requests) }
    var clientView by rememberSaveable { mutableStateOf(ClientView.Hidden) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopBar(server, state, clientView, onClientViewChange = { clientView = it })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // The console stays composed under a fullscreen client, so its tabs and filters are kept.
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Overview(server, state, health)
                        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                            ConsoleTab.entries.forEach { entry ->
                                Tab(selected = tab == entry, onClick = { tab = entry }, text = { Text(entry.label) })
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (tab) {
                                ConsoleTab.Requests -> {
                                    val exchanges by server.traffic.exchanges.collectAsState()
                                    TrafficInspector(exchanges, onClear = server.traffic::clear, modifier = Modifier.fillMaxSize(), showDirections = true)
                                }
                                ConsoleTab.Clients -> ClientsTab(server)
                                ConsoleTab.Logs -> LogsTab(logs)
                            }
                        }
                    }
                    if (clientView == ClientView.Embedded) {
                        VerticalDivider()
                        Spacer(Modifier.width(EmbeddedClientWidth))
                    }
                }
                // One call site for both visible views, so switching between them keeps the client as it is.
                if (clientView != ClientView.Hidden) {
                    val size = if (clientView == ClientView.Fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxHeight().width(EmbeddedClientWidth)
                    ClientPane(Modifier.align(Alignment.TopEnd).then(size))
                }
            }
        }
    }
}

@Composable
private fun TopBar(server: ConsoleServer, state: ServerState, clientView: ClientView, onClientViewChange: (ClientView) -> Unit) {
    val scope = rememberCoroutineScope()
    var port by rememberSaveable { mutableStateOf(server.defaultPort.toString()) }
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65_535 }
    val stopped = state is ServerState.Stopped || state is ServerState.Failed
    val (color, label) = when (state) {
        is ServerState.Running -> radarColors.ok to "Running on port ${state.port}"
        ServerState.Starting -> radarColors.warning to "Starting…"
        ServerState.Stopping -> radarColors.warning to "Stopping…"
        ServerState.Stopped -> MaterialTheme.colorScheme.onSurfaceVariant to "Stopped"
        is ServerState.Failed -> radarColors.danger to "Failed: ${state.message}"
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("RadarNebo server", style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(label, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DataSourceToggle(server, enabled = state is ServerState.Running || stopped)
        ClientViewToggle(clientView, onClientViewChange)
        OutlinedTextField(
            port,
            onValueChange = { value -> port = value.filter(Char::isDigit).take(5) },
            label = { Text("Port") },
            singleLine = true,
            enabled = stopped,
            modifier = Modifier.width(110.dp),
        )
        if (stopped) {
            Button(
                onClick = { portNumber?.let { scope.launch { server.start(it) } } },
                enabled = portNumber != null,
            ) { Text("Start server") }
        } else {
            Button(
                onClick = { scope.launch { server.stop() } },
                enabled = state is ServerState.Running,
                colors = ButtonDefaults.buttonColors(containerColor = radarColors.danger, contentColor = Color.White),
            ) { Text("Stop server") }
        }
    }
    HorizontalDivider()
}

/** Switches the server between alerts.in.ua and the simulation; a running server restarts to switch. */
@Composable
private fun DataSourceToggle(server: ConsoleServer, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    val current by server.dataSource.collectAsState()
    val options = DataSourceKind.entries
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SingleChoiceSegmentedButtonRow {
            options.forEachIndexed { index, kind ->
                SegmentedButton(
                    selected = current == kind,
                    onClick = { scope.launch { server.switchDataSource(kind) } },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    enabled = enabled && (kind != DataSourceKind.AlertsInUa || server.alertsInUaAvailable),
                ) { Text(kind.label) }
            }
        }
        if (!server.alertsInUaAvailable) {
            Text("alerts.in.ua needs ALERTS_API_TOKEN", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Overview(server: ConsoleServer, state: ServerState, health: Health?) {
    val running = state as? ServerState.Running
    val dataSource by server.dataSource.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(REFRESH_MS)
        }
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InfoCard("Network") {
            if (running == null) {
                Fact("Listening", "–")
            } else {
                Fact("Listening", "0.0.0.0:${running.port} (all interfaces)")
                Link("http://localhost:${running.port}")
                // Looked up once per run rather than on every one-second recomposition.
                val lan = remember(running.port) { lanAddresses() }
                lan.forEach { Link("http://$it:${running.port}") }
                Fact("Up for", duration(now - running.since))
            }
        }
        InfoCard("Data source") {
            Fact("Source", dataSource.label)
            if (dataSource == DataSourceKind.AlertsInUa) Fact("Upstream", server.upstreamUrl)
            Fact("Client keys", if (server.clientKeys == 0) "none (open to everyone)" else "${server.clientKeys} configured")
            Fact("Archive", server.archive)
        }
        InfoCard("Upstream") {
            if (health == null) {
                Fact("Polling", "–")
            } else {
                health.upstreamBudgetPerMinute?.let { budget ->
                    Fact("Requests", "${health.upstreamRequestsLastMinute} of $budget in the last minute · ${health.upstreamRequestsTotal} total")
                }
                Fact("Polled", "every ${health.pollIntervalSeconds} s")
                Fact("Last poll", health.lastAttemptAt?.let(::formatClock) ?: "–")
                Fact("Next poll", health.nextPollAt?.let(::formatClock) ?: "–")
                health.lastError?.let { Fact("Error", it, radarColors.danger) }
            }
        }
        InfoCard("Situation") {
            if (health == null) {
                Fact("Status", "–")
            } else {
                Fact("Status", health.status)
                Fact("Alerts", health.alerts.toString())
                Fact("Tracks", "${health.activeTracks} active of ${health.tracks}")
                Fact("Data from", health.dataConfirmedAt?.let(::formatClock) ?: "–")
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.widthIn(min = 260.dp, max = 420.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.size(4.dp))
            content()
        }
    }
}

@Composable
private fun Fact(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row {
        Text(label, Modifier.width(90.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, color = color)
    }
}

@Composable
private fun Link(url: String) {
    Text(
        url,
        Modifier.padding(start = 90.dp).clickable { browse(url) },
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.primary,
    )
}

private fun browse(url: String) {
    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        log.warn("Couldn't open {}: this desktop has no browser to open it with", url)
        return
    }
    runCatching { Desktop.getDesktop().browse(URI(url)) }.onFailure { log.warn("Couldn't open {}", url, it) }
}

private val DataSourceKind.label: String
    get() = when (this) {
        DataSourceKind.AlertsInUa -> "alerts.in.ua"
        DataSourceKind.Simulation -> "Simulation"
    }

/** Addresses other devices can use to reach this machine, IPv4 only. */
private fun lanAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress }
}.getOrDefault(emptyList())

private fun duration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min ${s % 60} s"
        else -> "${s / 3600} h ${s / 60 % 60} min"
    }
}

/** A fixed-width column of the clients table; the user agent follows them and takes the rest of the row. */
private class ClientColumn(val title: String, val width: Dp, val value: (ClientInfo) -> String)

private val clientColumns = listOf(
    ClientColumn("Address", 160.dp) { it.address },
    ClientColumn("Requests", 80.dp) { it.requests.toString() },
    ClientColumn("Errors", 70.dp) { it.errors.toString() },
    ClientColumn("Last seen", 90.dp) { formatClock(it.lastSeen) },
    ClientColumn("Last request", 300.dp) { it.lastRequest },
    ClientColumn("Status", 60.dp) { it.lastStatus?.toString() ?: "–" },
    ClientColumn("Key", 50.dp) { if (it.usedKey) "yes" else "no" },
)

@Composable
private fun ClientsTab(server: ConsoleServer) {
    val clients by server.clients.collectAsState()
    val rows = clients.values.sortedByDescending { it.lastSeen }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${rows.size} clients since the console started", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = server::clearClients) { Text("Clear") }
        }
        Row(Modifier.padding(horizontal = 16.dp)) {
            clientColumns.forEach { column -> Text(column.title, Modifier.width(column.width), fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
            Text("User agent", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
        if (rows.isEmpty()) Text("No client has called the server yet.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                items(rows, key = { it.address }) { client ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        clientColumns.forEach { column ->
                            Text(column.value(client), Modifier.width(column.width), fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(client.userAgent ?: "–", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogsTab(logs: LogCapture) {
    val lines by logs.lines.collectAsState()
    var minLevel by rememberSaveable { mutableStateOf(Level.INFO.levelStr) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(lines, minLevel, query) {
        val min = Level.toLevel(minLevel)
        val q = query.lowercase()
        lines.filter { it.level.isGreaterOrEqual(min) && (q.isBlank() || q in it.message.lowercase() || q in it.logger.lowercase()) }
    }
    val listState = rememberLazyListState()
    // Keyed on the newest line, not the count: once the buffer is full the count stays the same as lines arrive.
    LaunchedEffect(shown.lastOrNull()?.id, follow) {
        if (follow && shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(Level.INFO, Level.WARN, Level.ERROR).forEach { level ->
                FilterChip(minLevel == level.levelStr, onClick = { minLevel = level.levelStr }, label = { Text("${level.levelStr}+") })
            }
            OutlinedTextField(query, onValueChange = { query = it }, placeholder = { Text("Search logs") }, singleLine = true, modifier = Modifier.weight(1f))
            FilterChip(follow, onClick = { follow = !follow }, label = { Text("Follow") })
            TextButton(onClick = logs::clear) { Text("Clear") }
        }
        HorizontalDivider()
        SelectionContainer {
            LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                items(shown, key = { it.id }) { line ->
                    val color = when (line.level) {
                        Level.ERROR -> radarColors.danger
                        Level.WARN -> radarColors.warning
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text(formatClock(line.time), Modifier.width(72.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(line.level.levelStr, Modifier.width(52.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold)
                        Text(line.logger, Modifier.width(150.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(line.message, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color)
                    }
                }
            }
        }
    }
}
