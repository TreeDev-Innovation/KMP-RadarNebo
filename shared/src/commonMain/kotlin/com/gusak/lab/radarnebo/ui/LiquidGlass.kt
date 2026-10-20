package com.gusak.lab.radarnebo.ui

import androidx.compose.foundation.layout.fillMaxSize
import com.gusak.lab.radarnebo.data.ThemeMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Shared snapshot of the content behind glass surfaces. The content area records itself into
 * [content] every time it draws ([glassBackdropSource]); glass surfaces redraw it blurred behind them.
 */
@Stable
class GlassBackdrop internal constructor(
    internal val content: GraphicsLayer,
    internal val blurred: GraphicsLayer,
) {
    internal var sourceOrigin by mutableStateOf(Offset.Zero)

    /** Bumped on every source draw so glass surfaces, drawn in other nodes, redraw with it. */
    internal var version by mutableIntStateOf(0)
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val content = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    return remember(content, blurred) { GlassBackdrop(content, blurred) }
}

/** Records everything this node (and its children) draws, for glass surfaces placed above it. */
fun Modifier.glassBackdropSource(backdrop: GlassBackdrop): Modifier = this
    .onGloballyPositioned { backdrop.sourceOrigin = it.positionInRoot() }
    .drawWithContent {
        backdrop.content.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.content)
        backdrop.version++
    }

private val saturate = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.6f) })

/**
 * iOS-style liquid glass: the backdrop is blurred, saturated and slightly magnified (a cheap
 * stand-in for refraction), tinted, lit by a top highlight and outlined by a specular rim.
 */
@Composable
fun Modifier.liquidGlass(
    backdrop: GlassBackdrop,
    shape: Shape,
    blurRadius: Dp = 18.dp,
    magnification: Float = 1.08f,
): Modifier {
    val colors = radarColors
    var position by remember { mutableStateOf(Offset.Zero) }
    return this
        .shadow(18.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
        .onGloballyPositioned { position = it.positionInRoot() }
        .drawBehind {
            backdrop.version // re-draw whenever the content behind changes
            val outline = shape.createOutline(size, layoutDirection, this)
            val clip = Path().apply { addOutline(outline) }
            val radius = blurRadius.toPx()
            backdrop.blurred.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
            backdrop.blurred.colorFilter = saturate
            val source = backdrop.content
            backdrop.blurred.record(source.size) { drawLayer(source) }
            val offset = position - backdrop.sourceOrigin
            clipPath(clip) {
                withTransform({
                    scale(magnification, magnification, pivot = center)
                    translate(-offset.x, -offset.y)
                }) {
                    drawLayer(backdrop.blurred)
                }
                drawRect(colors.glassTint)
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = if (colors.isDark) 0.10f else 0.35f), 0.55f to Color.Transparent))
            }
            drawOutline(
                outline,
                Brush.linearGradient(
                    0f to colors.glassRimLight,
                    0.35f to colors.glassRimShade,
                    0.65f to colors.glassRimShade,
                    1f to colors.glassRimLight.copy(alpha = colors.glassRimLight.alpha * 0.6f),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
                style = Stroke(1.dp.toPx()),
            )
        }
}

data class GlassNavItem(val label: String, val glyph: String, val badge: Int = 0)

object GlassNavBarDefaults {
    val ItemWidth = 84.dp
    val Height = 64.dp
    val Margin = 12.dp

    /** Space content should leave below itself so it can scroll clear of the floating bar. */
    val ContentInset = Height + Margin * 2
}

/** Floating pill-shaped navigation bar made of liquid glass, with a lens that glides between items. */
@Composable
fun GlassNavigationBar(
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: GlassBackdrop,
    modifier: Modifier = Modifier,
) {
    val colors = radarColors
    val shape = RoundedCornerShape(percent = 50)
    val itemWidth = GlassNavBarDefaults.ItemWidth
    val lens = remember { Animatable(selectedIndex.toFloat()) }
    LaunchedEffect(selectedIndex) {
        lens.animateTo(selectedIndex.toFloat(), spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow))
    }
    // The lens stretches while travelling, like a droplet, and settles back to round.
    val stretch = 1f + (abs(lens.targetValue - lens.value).coerceAtMost(1f)) * 0.35f

    Box(
        modifier
            .height(GlassNavBarDefaults.Height)
            .liquidGlass(backdrop, shape)
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .offset(x = itemWidth * lens.value)
                .width(itemWidth)
                .height(GlassNavBarDefaults.Height - 12.dp)
                .graphicsLayer { scaleX = stretch; scaleY = 1f / (1f + (stretch - 1f) * 0.4f) }
                .background(colors.glassSelection, shape)
                .border(1.dp, Brush.verticalGradient(listOf(colors.glassRimLight, colors.glassRimShade)), shape),
        )
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.Start) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                val tint by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
                Column(
                    Modifier
                        .width(itemWidth)
                        .height(GlassNavBarDefaults.Height - 12.dp)
                        .selectable(
                            selected = selected,
                            onClick = { onSelect(index) },
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box {
                        Text(item.glyph, color = tint, fontSize = 20.sp)
                        if (item.badge > 0) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 10.dp, y = (-4).dp)
                                    .size(16.dp)
                                    .background(colors.danger, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(item.badge.coerceAtMost(9).toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Text(item.label, color = tint, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun GlassBarPreviewContent(theme: ThemeMode) {
    RadarTheme(theme) {
        val backdrop = rememberGlassBackdrop()
        Box(Modifier.size(width = 420.dp, height = 160.dp)) {
            // Something colourful behind the glass so the blur is visible.
            Box(
                Modifier
                    .fillMaxSize()
                    .glassBackdropSource(backdrop)
                    .background(Brush.horizontalGradient(listOf(radarColors.danger, radarColors.favorite, radarColors.warning))),
            )
            GlassNavigationBar(
                items = listOf(GlassNavItem("Map", "◎"), GlassNavItem("Favorites", "★", badge = 2), GlassNavItem("Settings", "⚙")),
                selectedIndex = 0,
                onSelect = {},
                backdrop = backdrop,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Preview(name = "Glass navigation bar · dark")
@Composable
private fun GlassNavigationBarDarkPreview() = GlassBarPreviewContent(ThemeMode.Dark)

@Preview(name = "Glass navigation bar · light")
@Composable
private fun GlassNavigationBarLightPreview() = GlassBarPreviewContent(ThemeMode.Light)
