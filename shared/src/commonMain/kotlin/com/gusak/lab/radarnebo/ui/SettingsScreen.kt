package com.gusak.lab.radarnebo.ui

import com.gusak.lab.radarnebo.data.MapBackground
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
import androidx.compose.material3.OutlinedButton
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
import com.gusak.lab.radarnebo.data.ColorPalette
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import com.gusak.lab.radarnebo.data.ThemeMode
import com.gusak.lab.radarnebo.tracking.TargetClassifier
import com.gusak.lab.radarnebo.tracking.TargetProfile
import com.gusak.lab.radarnebo.ui.map.TargetIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt

/**
 * Settings, grouped into categories: each a card of rows with a title, a description and the control. Scrolls
 * itself; shown in a [GlassDialog] over the map.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the request inspector; null hides the Developer section. */
    onOpenRequests: (() -> Unit)? = null,
) {
    var serverUrl by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var accessKey by remember(settings.accessKey) { mutableStateOf(settings.accessKey) }
    val serverChanged = serverUrl.trim() != settings.serverUrl.trim() || accessKey.trim() != settings.accessKey.trim()

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection("Appearance", "◐") {
            SettingBlock("Theme", "System follows your device's light or dark setting.") {
                Segmented(
                    options = ThemeMode.entries,
                    selected = settings.themeMode,
                    label = {
                        when (it) {
                            ThemeMode.System -> "System"
                            ThemeMode.Light -> "Light"
                            ThemeMode.Dark -> "Dark"
                        }
                    },
                    onSelect = { mode -> onChange { it.copy(themeMode = mode) } },
                )
            }
            SectionDivider()
            SettingBlock("Color scheme", "Colours of the whole app and the map. Every scheme has a light and a dark variant.") {
                PalettePicker(settings.colorPalette, onSelect = { palette -> onChange { it.copy(colorPalette = palette) } })
            }
        }

        SettingsSection("Map", "◎") {
            SettingBlock("Background", "What the map shows under the oblasts. Map and satellite tiles are downloaded from Esri.") {
                Segmented(
                    options = MapBackground.entries,
                    selected = settings.mapBackground,
                    label = {
                        when (it) {
                            MapBackground.None -> "No map"
                            MapBackground.Streets -> "Map"
                            MapBackground.Satellite -> "Satellite"
                        }
                    },
                    onSelect = { background -> onChange { it.copy(mapBackground = background) } },
                )
            }
            SectionDivider()
            SliderSetting(
                title = "Drones on the map",
                description = "The most drones drawn at once; fewer keeps the map readable and fast.",
                value = settings.maxMapMarkers,
                range = AppSettings.MAX_MARKERS_RANGE,
                format = { "$it" },
                onChange = { v -> onChange { it.copy(maxMapMarkers = v) } },
            )
        }

        SettingsSection("Alerts & tracking", "⚠") {
            SliderSetting(
                title = "Warning radius",
                description = "Threats this close to a favorite place raise a warning.",
                value = settings.proximityRadiusKm,
                range = AppSettings.RADIUS_RANGE,
                format = { "$it km" },
                onChange = { v -> onChange { it.copy(proximityRadiusKm = v) } },
            )
            SectionDivider()
            SwitchSetting(
                title = "Drones only",
                description = "Hide missiles, aviation and other threat types.",
                checked = settings.dronesOnly,
                onCheckedChange = { v -> onChange { it.copy(dronesOnly = v) } },
            )
            SectionDivider()
            SwitchSetting(
                title = "Smart mapping",
                description = "Place district alerts at their district, merge alerts raised together for one drone group and " +
                    "keep tracks on a plausible course. Off: district alerts land anywhere in their oblast and alerts are " +
                    "linked by distance alone.",
                checked = settings.smartMapping,
                onCheckedChange = { v -> onChange { it.copy(smartMapping = v) } },
            )
            SectionDivider()
            SliderSetting(
                title = "Refresh interval",
                description = "The server refreshes its data every 10–15 s; polling faster only uses more battery.",
                value = settings.pollSeconds,
                range = AppSettings.POLL_RANGE,
                format = { "$it s" },
                onChange = { v -> onChange { it.copy(pollSeconds = v) } },
            )
        }

        SettingsSection("Server", "⇄") {
            SettingBlock(
                "Connection",
                "RadarNebo gets alerts and tracked targets from a RadarNebo server, which keeps the history and does the " +
                    "tracking. Run your own and enter its address; ask its operator for an access key if it needs one.",
            ) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = accessKey,
                    onValueChange = { accessKey = it },
                    label = { Text("Access key (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onChange { it.copy(serverUrl = serverUrl.trim(), accessKey = accessKey.trim()) } },
                    enabled = serverChanged,
                    modifier = Modifier.align(Alignment.End),
                ) { Text("Save & reconnect") }
            }
        }

        if (onOpenRequests != null) {
            SettingsSection("Developer", "{ }") {
                SettingRow("Request inspector", "Every request the app sends to its server and the JSON it gets back.") {
                    OutlinedButton(onClick = onOpenRequests) { Text("Open") }
                }
            }
        }

        SettingsSection("Target types", "✈") {
            Text(
                "How each threat is drawn on the map. Red is a red-level alert, amber a yellow-level one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TargetClassifier.all.forEach {
                Spacer(Modifier.height(12.dp))
                TargetTypeRow(it)
            }
        }
    }
}

/** A category: a glyph and title over a translucent card holding its settings. */
@Composable
private fun SettingsSection(title: String, glyph: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Row(Modifier.padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(glyph, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(8.dp))
            Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f))
                .border(1.dp, radarColors.glassRimShade, RoundedCornerShape(20.dp))
                .padding(16.dp),
            content = content,
        )
    }
}

@Composable
private fun SectionDivider() = HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

@Composable
private fun SettingTitle(title: String, description: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (description != null) {
            Spacer(Modifier.height(2.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Title and description with the control beside them. */
@Composable
private fun SettingRow(title: String, description: String?, modifier: Modifier = Modifier, control: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SettingTitle(title, description, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        control()
    }
}

/** Title and description with a wide control below them. */
@Composable
private fun SettingBlock(title: String, description: String?, control: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SettingTitle(title, description)
        Spacer(Modifier.height(12.dp))
        control()
    }
}

/** The whole row toggles the switch. */
@Composable
private fun SwitchSetting(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SettingRow(
        title,
        description,
        Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun SliderSetting(title: String, description: String, value: Int, range: IntRange, format: (Int) -> String, onChange: (Int) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth()) {
        SettingRow(title, description) {
            Text(
                format(draft.roundToInt()),
                Modifier
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onChange(draft.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label(option), maxLines = 1) }
        }
    }
}

/** Display name of a colour scheme. */
fun ColorPalette.label() = when (this) {
    ColorPalette.Radar -> "Radar"
    ColorPalette.Nord -> "Nord"
    ColorPalette.Dracula -> "Dracula"
    ColorPalette.Solarized -> "Solarized"
    ColorPalette.Catppuccin -> "Catppuccin"
    ColorPalette.ObsidianPlum -> "Obsidian Plum"
    ColorPalette.SmokedCopper -> "Smoked Copper"
    ColorPalette.BlueHonor -> "Blue Honor"
}

/** Every scheme as a tile showing its background and accent, in the current light or dark variant. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PalettePicker(selected: ColorPalette, onSelect: (ColorPalette) -> Unit) {
    val dark = radarColors.isDark
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ColorPalette.entries.forEach { palette ->
            val (accent, background) = paletteSwatch(palette, dark)
            val isSelected = palette == selected
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier
                    .clip(shape)
                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                    .border(if (isSelected) 2.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
                    .selectable(isSelected, role = Role.RadioButton, onClick = { onSelect(palette) })
                    .padding(start = 8.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(background).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)) {
                    Box(Modifier.size(12.dp).align(Alignment.Center).clip(CircleShape).background(accent))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    palette.label(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
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
