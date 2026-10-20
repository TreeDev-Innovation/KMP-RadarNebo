package com.gusak.lab.radarnebo.ui

import androidx.compose.material3.Surface
import com.gusak.lab.radarnebo.data.ThemeMode
import com.gusak.lab.radarnebo.ui.preview.PreviewData
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.geo.Regions

@Composable
fun FavoritesScreen(
    state: UiState,
    onAdd: (String, GeoPoint) -> Unit,
    onRemove: (String) -> Unit,
    bottomInset: Dp = 0.dp,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showCustom by remember { mutableStateOf(false) }
    val hits = remember(state.proximity) { state.proximity.associateBy { it.favorite.id } }
    val savedNames = state.favorites.map { it.name }.toSet()
    val suggestions = Regions.cities.filter { it.name !in savedNames && query.isNotBlank() && it.name.contains(query.trim(), ignoreCase = true) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + bottomInset), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Favorite locations", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Threats within ${state.settings.proximityRadiusKm} km, or region-wide threats in the same oblast, are flagged. " +
                    "Tip: long-press the map to add any point.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search city") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        items(suggestions, key = { "s:" + it.name }) { city ->
            Text(
                "+ ${city.name} · ${Regions.oblast(city.oblastUid)?.nameEn.orEmpty()}",
                modifier = Modifier.fillMaxWidth().clickable { onAdd(city.name, city.point); query = "" }.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        item {
            TextButton(onClick = { showCustom = true }) { Text("Add by coordinates") }
        }
        if (state.favorites.isEmpty()) {
            item { Text("No favorites yet. Add your home, family or work locations.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(state.favorites, key = { it.id }) { favorite ->
            val hit = hits[favorite.id]
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (hit != null) radarColors.danger.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(favorite.name, fontWeight = FontWeight.SemiBold)
                        Text("${formatCoord(favorite.lat)}, ${formatCoord(favorite.lon)}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (hit == null) "Clear" else "${hit.threat.type.label} · " +
                                if (hit.insideRegion) "region-wide in ${hit.threat.oblastName}" else "${hit.distanceKm.toInt()} km – ${hit.threat.title}",
                            color = if (hit == null) radarColors.ok else radarColors.danger,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { onRemove(favorite.id) }) { Text("Remove") }
                }
            }
        }
        if (state.favorites.isEmpty()) {
            item {
                Text("Quick add", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Regions.cities.take(4).forEach { city -> TextButton(onClick = { onAdd(city.name, city.point) }) { Text(city.name) } }
                }
            }
        }
    }

    if (showCustom) {
        CoordinatesDialog(onDismiss = { showCustom = false }, onConfirm = { name, point -> onAdd(name, point); showCustom = false })
    }
}

@Composable
private fun CoordinatesDialog(onDismiss: () -> Unit, onConfirm: (String, GeoPoint) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var lat by rememberSaveable { mutableStateOf("") }
    var lon by rememberSaveable { mutableStateOf("") }
    val point = lat.replace(',', '.').toDoubleOrNull()?.let { la ->
        lon.replace(',', '.').toDoubleOrNull()?.let { lo -> GeoPoint(la, lo) }
    }?.takeIf { it.lat in 44.0..53.0 && it.lon in 22.0..41.0 }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add by coordinates") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(lat, { lat = it }, label = { Text("Latitude (44–53)") }, singleLine = true)
                OutlinedTextField(lon, { lon = it }, label = { Text("Longitude (22–41)") }, singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { point?.let { onConfirm(name, it) } }, enabled = point != null) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Preview(name = "Favorites · dark", widthDp = 420, heightDp = 860)
@Composable
private fun FavoritesScreenDarkPreview() {
    RadarTheme(ThemeMode.Dark) { Surface { FavoritesScreen(PreviewData.state(), onAdd = { _, _ -> }, onRemove = {}) } }
}

@Preview(name = "Favorites · light", widthDp = 420, heightDp = 860)
@Composable
private fun FavoritesScreenLightPreview() {
    RadarTheme(ThemeMode.Light) { Surface { FavoritesScreen(PreviewData.state(ThemeMode.Light), onAdd = { _, _ -> }, onRemove = {}) } }
}

@Preview(name = "Favorites · empty", widthDp = 420, heightDp = 860)
@Composable
private fun FavoritesScreenEmptyPreview() {
    RadarTheme(ThemeMode.Light) { Surface { FavoritesScreen(UiState(), onAdd = { _, _ -> }, onRemove = {}) } }
}
