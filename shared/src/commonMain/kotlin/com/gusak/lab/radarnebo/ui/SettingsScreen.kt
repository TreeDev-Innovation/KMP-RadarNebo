package com.gusak.lab.radarnebo.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.gusak.lab.radarnebo.data.AppSettings
import com.gusak.lab.radarnebo.data.ThemeMode
import com.gusak.lab.radarnebo.tracking.TargetClassifier
import com.gusak.lab.radarnebo.tracking.TargetProfile
import com.gusak.lab.radarnebo.ui.map.TargetIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    bottomInset: Dp = 0.dp,
) {
    var token by remember(settings.apiToken) { mutableStateOf(settings.apiToken) }
    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = settings.themeMode == mode,
                    onClick = { onChange { it.copy(themeMode = mode) } },
                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                ) {
                    Text(
                        when (mode) {
                            ThemeMode.System -> "System"
                            ThemeMode.Light -> "Light"
                            ThemeMode.Dark -> "Dark"
                        }
                    )
                }
            }
        }
        Text("System follows your device's light or dark setting.", style = MaterialTheme.typography.bodySmall)

        Text("Data source", style = MaterialTheme.typography.titleMedium)
        Text(
            "Request a personal token at devs.alerts.in.ua. Without a token the app shows synthetic demo data. " +
                "For a public release, run the RadarNebo proxy (:server) and set its URL below: it caches alerts, " +
                "keeps alerts.in.ua within its rate limits and adds drone tracks, which animate smoothly between updates.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("API token or proxy client key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("API base URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onChange { it.copy(apiToken = token.trim(), baseUrl = baseUrl.trim()) } }) { Text("Save & reconnect") }

        Text("Map", style = MaterialTheme.typography.titleMedium)
        IntSlider(
            label = "Max drones shown on map",
            value = settings.maxMapMarkers,
            range = AppSettings.MAX_MARKERS_RANGE,
            suffix = "",
            onChange = { v -> onChange { it.copy(maxMapMarkers = v) } },
        )
        IntSlider(
            label = "Proximity radius around favorites",
            value = settings.proximityRadiusKm,
            range = AppSettings.RADIUS_RANGE,
            suffix = " km",
            onChange = { v -> onChange { it.copy(proximityRadiusKm = v) } },
        )
        IntSlider(
            label = "Refresh interval",
            value = settings.pollSeconds,
            range = AppSettings.POLL_RANGE,
            suffix = " s",
            onChange = { v -> onChange { it.copy(pollSeconds = v) } },
        )
        Text("The API allows about 8–10 requests per minute; keep the interval at 15 s or more.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Drones only")
                Text("Hide missiles, aviation and other threat types", style = MaterialTheme.typography.bodySmall)
            }
            Switch(settings.dronesOnly, onCheckedChange = { v -> onChange { it.copy(dronesOnly = v) } })
        }

        Text("Target types", style = MaterialTheme.typography.titleMedium)
        Text(
            "How each threat is drawn on the map. Red is a red-level alert, amber a yellow-level one.",
            style = MaterialTheme.typography.bodySmall,
        )
        TargetClassifier.all.forEach { TargetTypeRow(it) }
        Spacer(Modifier.height(bottomInset))
    }
}

/** Legend entry: the icon in both alert colours, the class, typical models and what it does. */
@Composable
private fun TargetTypeRow(profile: TargetProfile) {
    Row(verticalAlignment = Alignment.Top) {
        Row(Modifier.background(radarColors.mapBackground, RoundedCornerShape(8.dp)).padding(4.dp)) {
            TargetIcon(profile.kind, headingDeg = 0f, color = radarColors.danger, size = 30.dp)
            TargetIcon(profile.kind, headingDeg = 0f, color = radarColors.warning, size = 30.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.kind.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(profile.model, profile.cruiseSpeedKmh?.let { "${it.first}–${it.last} km/h" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(profile.role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun IntSlider(label: String, value: Int, range: IntRange, suffix: String, onChange: (Int) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Row {
            Text(label, Modifier.weight(1f))
            Text("${draft.roundToInt()}$suffix", style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onChange(draft.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

@Preview(name = "Settings · dark", widthDp = 420, heightDp = 1000)
@Composable
private fun SettingsScreenDarkPreview() {
    RadarTheme(ThemeMode.Dark) { Surface { SettingsScreen(AppSettings(themeMode = ThemeMode.Dark), onChange = {}) } }
}

@Preview(name = "Settings · target types", widthDp = 420, heightDp = 1000)
@Composable
private fun SettingsTargetTypesPreview() {
    RadarTheme(ThemeMode.Dark) {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TargetClassifier.all.forEach { TargetTypeRow(it) }
            }
        }
    }
}

@Preview(name = "Settings · light", widthDp = 420, heightDp = 1000)
@Composable
private fun SettingsScreenLightPreview() {
    RadarTheme(ThemeMode.Light) { Surface { SettingsScreen(AppSettings(themeMode = ThemeMode.Light), onChange = {}) } }
}
