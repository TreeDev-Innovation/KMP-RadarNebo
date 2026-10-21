package com.gusak.lab.radarnebo.ui.preview

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.gusak.lab.radarnebo.data.ThemeMode
import com.gusak.lab.radarnebo.tracking.Place
import com.gusak.lab.radarnebo.tracking.TrackPredictor
import com.gusak.lab.radarnebo.ui.RadarTheme
import com.gusak.lab.radarnebo.ui.TargetsPanel
import com.gusak.lab.radarnebo.ui.preview.PreviewData.Targets

// The targets panel for each target state in PreviewData.scenario(): the list, and one detail view per state.

@Composable
private fun Panel(selectedTrackId: String?, theme: ThemeMode = ThemeMode.Dark) {
    val state = PreviewData.scenario(theme)
    val places = state.favorites.map { Place(it.name, it.point, isFavorite = true) } + TrackPredictor.cityPlaces
    RadarTheme(theme) {
        Surface {
            TargetsPanel(state.tracks, state.clockOffsetMs, places, selectedTrackId, onSelect = {}, onClose = {})
        }
    }
}

@Preview(name = "Targets panel · list · every state", widthDp = 380, heightDp = 900)
@Composable
private fun TargetsListEveryStatePreview() = Panel(selectedTrackId = null)

@Preview(name = "Targets panel · list · every state · light", widthDp = 380, heightDp = 900)
@Composable
private fun TargetsListEveryStateLightPreview() = Panel(selectedTrackId = null, theme = ThemeMode.Light)

@Preview(name = "Target · Shahed in flight, arrival predicted", widthDp = 380, heightDp = 1100)
@Composable
private fun TargetShahedPreview() = Panel(Targets.SHAHED)

@Preview(name = "Target · Gerbera decoy", widthDp = 380, heightDp = 1100)
@Composable
private fun TargetDecoyPreview() = Panel(Targets.DECOY)

@Preview(name = "Target · jet Shahed", widthDp = 380, heightDp = 1100)
@Composable
private fun TargetJetPreview() = Panel(Targets.JET)

@Preview(name = "Target · reconnaissance drone, single report", widthDp = 380, heightDp = 1100)
@Composable
private fun TargetReconPreview() = Panel(Targets.RECON)

@Preview(name = "Target · cleared", widthDp = 380, heightDp = 1100)
@Composable
private fun TargetClearedPreview() = Panel(Targets.CLEARED)

@Preview(name = "Target · cruise missile", widthDp = 380, heightDp = 1100)
@Composable
private fun TargetMissilePreview() = Panel(Targets.MISSILE)

@Preview(name = "Target · cruise missile · light", widthDp = 380, heightDp = 1100)
@Composable
private fun TargetMissileLightPreview() = Panel(Targets.MISSILE, theme = ThemeMode.Light)
