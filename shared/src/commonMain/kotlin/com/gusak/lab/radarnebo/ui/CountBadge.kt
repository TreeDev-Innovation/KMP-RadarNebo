package com.gusak.lab.radarnebo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * A count in a red circle, widening to a pill for two digits. Text is laid out by its line box, which leaves
 * room above and below for ascenders and descenders that digits don't have, so centring the box leaves the
 * digits off-centre: the digits' cap height is centred instead, measured from the baseline.
 */
@Composable
fun CountBadge(count: Int, color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val fontSize = 10.sp
    // Bright reds (dark themes) are darkened until the white count on them is readable.
    val fill = color.readableOn(listOf(Color.White), dark = false)
    Layout(
        content = { Text(count.toString(), color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Bold, softWrap = false) },
        modifier = modifier.background(fill, CircleShape),
    ) { measurables, _ ->
        val text = measurables.single().measure(Constraints())
        val height = size.roundToPx()
        val width = maxOf(height, text.width + 2 * 4.dp.roundToPx())
        val capHeight = fontSize.toPx() * DIGIT_CAP_HEIGHT
        layout(width, height) {
            text.place((width - text.width) / 2, (height / 2f + capHeight / 2f - text[FirstBaseline]).roundToInt())
        }
    }
}

/** Height of the digits as a share of the font size, about the same across common UI fonts. */
private const val DIGIT_CAP_HEIGHT = 0.71f
