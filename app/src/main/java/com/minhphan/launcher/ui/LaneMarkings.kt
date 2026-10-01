package com.minhphan.launcher.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope

// Where the road meets the horizon in the pictures, in their pixels (road_day.webp and road_night.webp are framed alike):
// the middle of the road, where the car drives.
internal const val ROAD_MIDDLE = 718f
internal const val ROAD_HORIZON = 430f
private const val VANISH_X = ROAD_MIDDLE
private const val VANISH_Y = ROAD_HORIZON

// A painted line is about 12 cm wide: this many of the picture's pixels at its bottom.
private const val LINE_WIDTH = 44f

/**
 * The road seen as a flat plane: a point [metres] ahead is `DEPTH_K / metres` pixels below the horizon. Taken so the
 * bottom of the picture is 3 m ahead, which with the road's width in the picture puts the camera about 1.5 m up.
 */
private const val BOTTOM_METRES = 3f
private const val DEPTH_K = BOTTOM_METRES * (IMAGE_HEIGHT - VANISH_Y)

// The dashes: 3 m of paint and 4.5 m of gap, until they are too small to see.
internal const val DASH_PERIOD_M = 7.5f
private const val DASH_LENGTH_M = 3f
private const val FARTHEST_M = 60f

// A frame's worth of travel is added to each dash, so a quick one smears as the eye would see it and does not jump.
private const val SMEAR_SECONDS = 1f / 60f

// The dashes fade in from the horizon, where they are too small to read and would shimmer.
private const val FULL_FROM = 0.1f

/** How far below the top of the picture, in its pixels, the road is [metres] ahead. */
internal fun laneY(metres: Float): Float = VANISH_Y + DEPTH_K / metres

/**
 * The dashes along a lane line with the car [phaseMetres] into the period, each as the stretch of road it covers,
 * nearest first: [action] gets its near and far ends in metres ahead. Only the ones in the picture, and a near end
 * that has gone under the bottom of it is left there.
 */
internal inline fun forEachDash(phaseMetres: Float, lengthMetres: Float, action: (near: Float, far: Float) -> Unit) {
    var near = BOTTOM_METRES - phaseMetres
    while (near < FARTHEST_M) {
        val far = near + lengthMetres
        if (far > BOTTOM_METRES) action(near.coerceAtLeast(BOTTOM_METRES), far)
        near += DASH_PERIOD_M
    }
}

/**
 * The dashed white lines of the car's lane, painted on the road in perspective. They come on as the road would, slow
 * far off and quick close by, at the car's true speed; standing still they stand still. The lane is [halfWidth] either
 * side of [middle], both where they cross the bottom of the picture; like every line on the road they run to the
 * horizon's middle.
 *
 * Kept light for a head unit: a frame draws one path of a few quadrilaterals, rebuilt in place, so it allocates
 * nothing. Everything is in the picture's pixels times [scale], with its top left at 0, 0.
 */
internal class LaneMarkings(private val scale: Float, color: Color, middle: Float, halfWidth: Float) {
    private val leftX = middle - halfWidth
    private val rightX = middle + halfWidth
    private val dashes = Path()
    private val brush = Brush.verticalGradient(
        0f to color.copy(alpha = 0f),
        1f to color,
        startY = VANISH_Y * scale,
        endY = (VANISH_Y + FULL_FROM * (IMAGE_HEIGHT - VANISH_Y)) * scale,
    )

    /**
     * The dashes, [phaseMetres] into their period, smeared over a frame at [speedMps]. The scope must be translated so
     * the picture's top left is at 0, 0.
     */
    fun DrawScope.drawDashes(phaseMetres: Float, speedMps: Float, frameSeconds: Float = SMEAR_SECONDS) {
        dashes.rewind()
        forEachDash(phaseMetres, DASH_LENGTH_M + speedMps * frameSeconds.coerceIn(0f, 0.05f)) { near, far ->
            dashes.addDash(leftX, laneY(far), laneY(near))
            dashes.addDash(rightX, laneY(far), laneY(near))
        }
        drawPath(dashes, brush)
    }

    /**
     * Adds the stretch of the lane line that crosses the bottom at [bottomX], from [yFar] down to [yNear], narrowing to
     * nothing at the horizon.
     */
    private fun Path.addDash(bottomX: Float, yFar: Float, yNear: Float) {
        fun x(y: Float) = VANISH_X + (bottomX - VANISH_X) * (y - VANISH_Y) / (IMAGE_HEIGHT - VANISH_Y)
        fun half(y: Float) = LINE_WIDTH / 2f * (y - VANISH_Y) / (IMAGE_HEIGHT - VANISH_Y)
        moveTo((x(yFar) - half(yFar)) * scale, yFar * scale)
        lineTo((x(yFar) + half(yFar)) * scale, yFar * scale)
        lineTo((x(yNear) + half(yNear)) * scale, yNear * scale)
        lineTo((x(yNear) - half(yNear)) * scale, yNear * scale)
        close()
    }
}
