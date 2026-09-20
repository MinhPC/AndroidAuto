package com.minhphan.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Both repeating things on the road (the dashes and the tree spacing) divide this, so the scroll offset can
 * wrap around without a visible jump.
 */
private const val SCROLL_PERIOD_PX = 320f
private const val DASH_PX = 80f
private const val PX_PER_KMH_PER_SECOND = 7f
private const val ROAD_WIDTH_FRACTION = 0.8f
private const val LINE_WIDTH_PX = 6f

private class RoadPalette(
    val roadside: Color,
    val asphalt: Color,
    val edge: Color,
    val dash: Color,
    val treeDark: Color,
    val treeLight: Color,
)

private val DayPalette = RoadPalette(
    roadside = Color(0xFF5E8F50), asphalt = Color(0xFF3C4149), edge = Color(0xFFECECEC),
    dash = Color(0xFFF2F2F2), treeDark = Color(0xFF2C5F30), treeLight = Color(0xFF3F8443),
)
private val NightPalette = RoadPalette(
    roadside = Color(0xFF0E1B13), asphalt = Color(0xFF1B1F26), edge = Color(0xFF9AA0A6),
    dash = Color(0xFFB4B9BF), treeDark = Color(0xFF0A2314), treeLight = Color(0xFF123222),
)

/**
 * A straight three-lane road seen from above, scrolling downwards (the car heads up). [speedKmh] is read
 * every frame; the frame loop runs only while [active], so a parked car costs no animation work at all.
 */
@Composable
fun RoadLayer(speedKmh: () -> Float, active: Boolean, dark: Boolean, modifier: Modifier = Modifier) {
    var scroll by remember { mutableFloatStateOf(0f) }
    val currentSpeed by rememberUpdatedState(speedKmh)

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val seconds = (now - last) / 1_000_000_000f
            last = now
            scroll = (scroll + currentSpeed() * PX_PER_KMH_PER_SECOND * seconds) % SCROLL_PERIOD_PX
        }
    }

    val palette = if (dark) NightPalette else DayPalette
    Canvas(modifier) {
        // Reading `scroll` here (not in composition) means each frame only redraws, it does not recompose.
        val offset = scroll
        val w = size.width
        val h = size.height
        val roadW = w * ROAD_WIDTH_FRACTION
        val left = (w - roadW) / 2f
        val right = left + roadW

        drawRect(palette.roadside)
        drawRect(palette.asphalt, Offset(left, 0f), Size(roadW, h))
        drawRect(palette.edge, Offset(left + 6f, 0f), Size(LINE_WIDTH_PX, h))
        drawRect(palette.edge, Offset(right - 6f - LINE_WIDTH_PX, 0f), Size(LINE_WIDTH_PX, h))

        // Drawn bottom-to-top: a growing dash phase moves the pattern towards the start, i.e. downwards.
        val dashes = PathEffect.dashPathEffect(floatArrayOf(DASH_PX, DASH_PX), offset)
        for (lane in 1..2) {
            val x = left + roadW * lane / 3f
            drawLine(palette.dash, Offset(x, h), Offset(x, 0f), strokeWidth = LINE_WIDTH_PX, pathEffect = dashes)
        }

        // Trees at the roadside, two per period so the edge never looks empty.
        for (k in -1..(h / SCROLL_PERIOD_PX).toInt() + 1) {
            val y = k * SCROLL_PERIOD_PX + offset
            drawTree(palette, Offset(left / 2f, y), radius = 20f)
            drawTree(palette, Offset(right + left / 2f, y + SCROLL_PERIOD_PX / 2f), radius = 24f)
        }
    }
}

private fun DrawScope.drawTree(palette: RoadPalette, center: Offset, radius: Float) {
    drawCircle(palette.treeDark, radius + 3f, center)
    drawCircle(palette.treeLight, radius, center)
}
