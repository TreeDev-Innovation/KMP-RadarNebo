package com.gusak.lab.radarnebo.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.gusak.lab.radarnebo.data.AppSettings
import com.gusak.lab.radarnebo.data.FavoriteLocation
import com.gusak.lab.radarnebo.ui.MapScreen
import com.gusak.lab.radarnebo.ui.RadarTheme
import com.gusak.lab.radarnebo.ui.UiState
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class RadiusControlTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun warningRadiusCanBeChangedFromTheMap() {
        val saved = mutableListOf<Int>()
        var state by mutableStateOf(
            UiState(
                settings = AppSettings(proximityRadiusKm = 50),
                favorites = listOf(FavoriteLocation("home", "Home", 50.45, 30.52)),
            ),
        )
        rule.setContent {
            RadarTheme {
                MapScreen(
                    state,
                    onModeChange = {},
                    onAddFavorite = { _, _ -> },
                    onRadiusChange = { km ->
                        saved += km
                        state = state.copy(settings = state.settings.copy(proximityRadiusKm = km))
                    },
                )
            }
        }

        rule.onNodeWithContentDescription("Warning radius, 50 km").performClick()
        rule.onNodeWithText("Warning radius").assertIsDisplayed()
        rule.onNodeWithText("You're warned when a threat comes this close to your favorite place.").assertIsDisplayed()

        // A preset saves straight away and the map button follows.
        rule.onNodeWithText("25 km").performClick()
        assertEquals(listOf(25), saved)
        rule.onNodeWithContentDescription("Warning radius, 25 km").assertIsDisplayed()

        // Dragging the slider saves the value it ends on.
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(140f) }
        rule.waitForIdle()
        assertEquals(140, saved.last())
        rule.onNodeWithContentDescription("Warning radius, 140 km").assertIsDisplayed()

        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("Warning radius").assertDoesNotExist()
    }
}
