package com.gusak.lab.radarnebo.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.ui.MapScreen
import com.gusak.lab.radarnebo.ui.RadarTheme
import com.gusak.lab.radarnebo.ui.UiState
import com.gusak.lab.radarnebo.ui.describePlace
import com.gusak.lab.radarnebo.ui.map.MapCamera
import com.gusak.lab.radarnebo.ui.map.MapProjection
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class AddFavoriteTest {

    @Test
    fun longPressAsksToAddThePlaceAndEnterAddsIt() {
        val added = mutableListOf<Pair<String, GeoPoint>>()
        val size = Size(1000f, 800f)
        val press = Offset(500f, 400f)
        val expectedPoint = MapProjection.toGeo(MapCamera.Overview.toWorld(press, size))
        ImageComposeScene(width = 1000, height = 800, density = Density(1f)) {
            RadarTheme { MapScreen(UiState(), onModeChange = {}, onAddFavorite = { name, point -> added += name to point }) }
        }.use { scene ->
            var t = 0L
            fun frame() = scene.render(t).also { t += 16_000_000L }
            repeat(3) { frame() }
            scene.sendPointerEvent(PointerEventType.Enter, press)
            scene.sendPointerEvent(PointerEventType.Press, press, button = PointerButton.Primary)
            repeat(80) { frame() }
            scene.sendPointerEvent(PointerEventType.Release, press, button = PointerButton.Primary)
            repeat(20) { frame() }
            val out = File(System.getProperty("snapshot.dir") ?: "build/snapshots").apply { mkdirs() }
            File(out, "add-favorite-card.png").writeBytes(frame().encodeToData(EncodedImageFormat.PNG)!!.bytes)

            // Enter accepts the suggested name.
            for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
                scene.sendKeyEvent(KeyEvent(Key.Enter, type))
                frame()
            }
        }
        assertEquals(1, added.size, "Enter in the card adds exactly one favorite")
        val (name, point) = added.single()
        assertEquals(describePlace(expectedPoint).suggestedName, name)
        assertTrue(GeoMath.distanceKm(point, expectedPoint) < 1.0, "favorite is placed where the map was pressed")
    }

    @Test
    fun placesAreDescribedByTheNearestCity() {
        val kyiv = describePlace(GeoPoint(50.45, 30.52))
        assertEquals("Kyiv", kyiv.suggestedName)
        assertTrue(kyiv.where.startsWith("In Kyiv"), kyiv.where)

        // About 15 km north-east of Uman, in Cherkasy oblast.
        val field = describePlace(GeoPoint(48.86, 30.36))
        assertTrue(field.suggestedName.startsWith("Near "), field.suggestedName)
        assertTrue(field.where.contains(" km ") && field.where.endsWith("Cherkasy Oblast"), field.where)
        assertTrue(field.inUkraine)

        assertTrue(!describePlace(GeoPoint(45.2, 31.6)).inUkraine, "open sea is outside Ukraine")
    }
}
