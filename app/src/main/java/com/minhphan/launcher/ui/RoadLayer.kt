package com.minhphan.launcher.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.minhphan.launcher.R
import kotlin.math.max
import kotlin.math.min

/** Where the road's vanishing point sits on the screen, as a fraction of the scene height (if the photo allows it). */
const val HORIZON_FRACTION = 0.46f

// The photo (road_scene.webp, Pexels photo 1955134, free to use) was measured once: pixel position of the road's vanishing point, and how fast the
// road widens below it (half the road width, in pixels, per pixel below the vanishing point). Its own painted
// dashes (the centre ones and the ones at the left edge) were painted out, so the dashes drawn here are the
// only ones and can move. The fork where the road crests the horizon was also closed into one road.
// The untouched photo is kept in art-source/road_scene_original.webp.
private const val IMAGE_WIDTH = 1000
private const val IMAGE_HEIGHT = 1500
private const val VANISH_X = 522.14f
private const val VANISH_Y = 779.29f
private const val ROAD_SLOPE = 2.142f

/** The painted lane lines are 2% of the road width, like the real centre line in the photo. */
private const val LINE_WIDTH_PER_PX = 0.089f

/** The road is split into three lanes; the car drives in the middle one and the two lane lines flank it. */
private const val LANE_LINE_OFFSET = 1f / 3f
private const val CAR_LANE_FILL = 0.72f

private const val Z_NEAR = 0.85f
private const val Z_FAR = 14f
private const val DASH_PERIOD = 0.6f
private const val DASH_LENGTH = 0.3f
private const val Z_PER_KMH_PER_SECOND = 0.012f

/** Multiplied over the photo (and the car) at night: dark and a little blue. */
internal val NightTint = Color(0xFF56658F)

/**
 * How the road photo is placed in a scene of the given size: scaled to cover it, with the road's centre in the
 * middle and its vanishing point at [HORIZON_FRACTION] of the height when possible. The car layer uses the same
 * numbers, so the car sits on the road in perspective.
 */
internal class SceneGeometry(val width: Float, val height: Float) {
    val scale = max(width / (2f * min(VANISH_X, IMAGE_WIDTH - VANISH_X)), height / IMAGE_HEIGHT)
    val left = width / 2f - VANISH_X * scale
    val top = (height * HORIZON_FRACTION - VANISH_Y * scale).coerceIn(height - IMAGE_HEIGHT * scale, 0f)
    val vanishX = width / 2f
    val vanishY = top + VANISH_Y * scale

    /** Screen y at depth z, where z = 1 is the bottom edge of the scene and larger z is further away. */
    fun yAt(z: Float) = vanishY + (height - vanishY) / z

    /** Screen x of a point at [side] (-1..1, the road's left edge to its right edge) and depth z. */
    fun xAt(side: Float, z: Float) = vanishX + side * ROAD_SLOPE * (height - vanishY) / z

    /** Width of the car so it fills [CAR_LANE_FILL] of the middle lane at the y where its bottom edge sits. */
    fun carWidth(maxFraction: Float): Float {
        val width = min(width * maxFraction, (height * 0.92f - vanishY) * CAR_LANE_FILL * (2f / 3f) * ROAD_SLOPE)
        return max(width, 1f)
    }

    fun carBottom(carWidth: Float) = vanishY + carWidth / (CAR_LANE_FILL * (2f / 3f) * ROAD_SLOPE)
}

/**
 * The road photo with the lane lines painted over it in perspective; the dashes stream towards the viewer at
 * [speedKmh]. The speed is read every frame and the frame loop runs only while [active], so a parked car costs
 * no animation work at all.
 */
@Composable
fun RoadLayer(speedKmh: () -> Float, active: Boolean, dark: Boolean, modifier: Modifier = Modifier) {
    var scroll by remember { mutableFloatStateOf(0f) }
    val currentSpeed by rememberUpdatedState(speedKmh)
    val photo = ImageBitmap.imageResource(R.drawable.road_scene)

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val seconds = (now - last) / 1_000_000_000f
            last = now
            scroll = (scroll + currentSpeed() * Z_PER_KMH_PER_SECOND * seconds) % DASH_PERIOD
        }
    }

    // The geometry, shades and path are built once per size (and per `dark`) and reused every frame, so the
    // animation allocates nothing: a head unit that garbage-collects during the animation would stutter.
    Spacer(
        modifier.drawWithCache {
            val g = SceneGeometry(size.width, size.height)
            val photoOffset = IntOffset(g.left.toInt(), g.top.toInt())
            val photoSize = IntSize((IMAGE_WIDTH * g.scale).toInt() + 1, (IMAGE_HEIGHT * g.scale).toInt() + 1)
            // Darker for the night, and soft shades at the top and bottom so the text reads on any photo.
            val topShade = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent), startY = 0f, endY = size.height * 0.4f)
            val bottomShade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)), startY = size.height * 0.8f, endY = size.height)
            val dash = Path()

            onDrawBehind {
                // Reading `scroll` here (not in composition) means each frame only redraws, it does not recompose.
                val offset = scroll
                drawImage(photo, dstOffset = photoOffset, dstSize = photoSize, filterQuality = FilterQuality.High)

                // The dash pattern moves towards the viewer: every dash sits at n * period minus the scroll.
                // Worn paint: not pure white. (A fainter, wider copy under each dash used to soften the edges, but it
                // showed as a ghost outline around every dash, so the dashes are drawn once, with anti-aliased edges.)
                var n = 0
                while (true) {
                    val start = n * DASH_PERIOD - offset
                    if (start >= Z_FAR) break
                    val z0 = max(start, Z_NEAR)
                    val z1 = start + DASH_LENGTH
                    if (z1 > Z_NEAR) {
                        dash.rewind()
                        addDash(dash, g, -LANE_LINE_OFFSET, z0, z1)
                        addDash(dash, g, LANE_LINE_OFFSET, z0, z1)
                        drawPath(dash, DashColor)
                    }
                    n++
                }

                if (dark) drawRect(NightTint, blendMode = BlendMode.Modulate)
                drawRect(topShade)
                drawRect(bottomShade)
            }
        },
    )
}

private val DashColor = Color(0xFFF0F0EA).copy(alpha = 0.80f)

/** Adds one painted dash between depths [z0] and [z1] (z0 < z1) on the lane line at [side]; it narrows with distance. */
private fun addDash(path: Path, g: SceneGeometry, side: Float, z0: Float, z1: Float) = path.apply {
    val half0 = LINE_WIDTH_PER_PX * (g.height - g.vanishY) / z0 / 2f
    val half1 = LINE_WIDTH_PER_PX * (g.height - g.vanishY) / z1 / 2f
    moveTo(g.xAt(side, z0) - half0, g.yAt(z0))
    lineTo(g.xAt(side, z0) + half0, g.yAt(z0))
    lineTo(g.xAt(side, z1) + half1, g.yAt(z1))
    lineTo(g.xAt(side, z1) - half1, g.yAt(z1))
    close()
}
