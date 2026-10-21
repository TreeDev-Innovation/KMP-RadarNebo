package com.gusak.lab.radarnebo.desktop

import com.gusak.lab.radarnebo.geo.UkraineCities
import com.gusak.lab.radarnebo.ui.map.CityLabelFader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CityLabelFaderTest {
    private val kyiv = UkraineCities.all.first { it.name == "Kyiv" }
    private val lviv = UkraineCities.all.first { it.name == "Lviv" }

    @Test
    fun namesFadeInWhenRevealedAndOutWhenHidden() {
        val fader = CityLabelFader(fadeInMs = 200f, fadeOutMs = 100f)
        assertEquals(0f, fader.update(setOf(kyiv), 1_000)[kyiv])
        assertEquals(0.5f, fader.update(setOf(kyiv), 1_100)[kyiv]!!, 0.001f)
        assertEquals(1f, fader.update(setOf(kyiv), 1_300)[kyiv])
        assertFalse(fader.isAnimating)

        // Kyiv's name goes away while Lviv's appears: both are drawn mid-fade.
        val crossFade = fader.update(setOf(lviv), 1_350)
        assertEquals(0.5f, crossFade[kyiv]!!, 0.001f)
        assertTrue(lviv in crossFade)
        assertTrue(fader.isAnimating)

        val after = fader.update(setOf(lviv), 1_400)
        assertFalse(kyiv in after, "fully faded names are dropped")
    }

    @Test
    fun longPausesDoNotSkipTheAnimation() {
        val fader = CityLabelFader(fadeInMs = 1_000f)
        fader.update(setOf(kyiv), 1_000)
        // A frame after a long stall advances at most 250 ms, so the fade is still visible.
        assertEquals(0.25f, fader.update(setOf(kyiv), 60_000)[kyiv]!!, 0.001f)
    }
}
