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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gusak.lab.radarnebo.server.api.Health
import com.gusak.lab.radarnebo.server.config.DataSourceKind
import com.gusak.lab.radarnebo.ui.debug.TrafficInspector
import com.gusak.lab.radarnebo.ui.formatClock
import com.gusak.lab.radarnebo.ui.radarColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

private enum class ConsoleTab(val label: String) { Requests("Requests"), Clients("Clients"), Logs("Logs") }

@Composable
fun ConsoleScreen(server: ConsoleServer, logs: LogCapture) {
    val state by server.state.collectAsState()
    val running = state as? ServerState.Running
    // The server's own report, refreshed every second while it runs.
    val health by produceState<Health?>(null, running) {
        while (true) {
            value = server.health()
            delay(1_000)
        }
    }
    var tab by rememberSaveable { mutableStateOf(ConsoleTab.Requests) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopBar(server, state)
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
    }
}

@Composable
private fun TopBar(server: ConsoleServer, state: ServerState) {
    val scope = rememberCoroutineScope()
    var port by rememberSaveable { mutableStateOf(server.defaultPort.toString()) }
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
                onClick = { scope.launch { server.start(port.toIntOrNull() ?: server.defaultPort) } },
                enabled = port.toIntOrNull() in 1..65_535,
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
    val options = listOf(DataSourceKind.AlertsInUa to "alerts.in.ua", DataSourceKind.Simulation to "Simulation")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SingleChoiceSegmentedButtonRow {
            options.forEachIndexed { index, (kind, label) ->
                SegmentedButton(
                    selected = current == kind,
                    onClick = { scope.launch { server.switchDataSource(kind) } },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    enabled = enabled && (kind != DataSourceKind.AlertsInUa || server.alertsInUaAvailable),
                ) { Text(label) }
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
            delay(1_000)
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
                ConsoleServer.lanAddresses().forEach { Link("http://$it:${running.port}") }
                Fact("Up for", duration(now - running.since))
            }
        }
        InfoCard("Data source") {
            Fact("Source", if (dataSource == DataSourceKind.AlertsInUa) "alerts.in.ua" else "Simulation")
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
        Modifier.padding(start = 90.dp).clickable { runCatching { Desktop.getDesktop().browse(URI(url)) } },
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.primary,
    )
}

private fun duration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min ${s % 60} s"
        else -> "${s / 3600} h ${s / 60 % 60} min"
    }
}

@Composable
private fun ClientsTab(server: ConsoleServer) {
    val clients by server.clients.collectAsState()
    val rows = clients.values.sortedByDescending { it.lastSeen }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${rows.size} clients since the console started", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = server::clearClients) { Text("Clear") }
        }
        val header = listOf("Address" to 160.dp, "Requests" to 80.dp, "Errors" to 70.dp, "Last seen" to 90.dp, "Last request" to 300.dp, "Status" to 60.dp, "Key" to 50.dp)
        Row(Modifier.padding(horizontal = 16.dp)) {
            header.forEach { (title, width) -> Text(title, Modifier.width(width), fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
            Text("User agent", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
        if (rows.isEmpty()) Text("No client has called the server yet.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                items(rows, key = { it.address }) { client ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        val cells = listOf(
                            client.address, client.requests.toString(), client.errors.toString(), formatClock(client.lastSeen),
                            client.lastRequest, client.lastStatus?.toString() ?: "–", if (client.usedKey) "yes" else "no",
                        )
                        cells.zip(header).forEach { (value, column) ->
                            Text(value, Modifier.width(column.second), fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    var minLevel by rememberSaveable { mutableStateOf("INFO") }
    var follow by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    val levels = listOf("DEBUG", "INFO", "WARN", "ERROR")
    val shown = lines.filter { line ->
        levels.indexOf(line.level).coerceAtLeast(0) >= levels.indexOf(minLevel) &&
            (query.isBlank() || query.lowercase() in line.message.lowercase() || query.lowercase() in line.logger.lowercase())
    }
    val listState = rememberLazyListState()
    LaunchedEffect(shown.size, follow) {
        if (follow && shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            levels.drop(1).forEach { level -> FilterChip(minLevel == level, onClick = { minLevel = level }, label = { Text("$level+") }) }
            OutlinedTextField(query, onValueChange = { query = it }, placeholder = { Text("Search logs") }, singleLine = true, modifier = Modifier.weight(1f))
            FilterChip(follow, onClick = { follow = !follow }, label = { Text("Follow") })
            TextButton(onClick = logs::clear) { Text("Clear") }
        }
        HorizontalDivider()
        SelectionContainer {
            LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                items(shown, key = { it.id }) { line ->
                    val color = when (line.level) {
                        "ERROR" -> radarColors.danger
                        "WARN" -> radarColors.warning
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text(formatClock(line.time), Modifier.width(72.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(line.level, Modifier.width(52.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold)
                        Text(line.logger, Modifier.width(150.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(line.message, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color)
                    }
                }
            }
        }
    }
}
