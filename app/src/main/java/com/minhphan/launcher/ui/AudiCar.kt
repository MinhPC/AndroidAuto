package com.minhphan.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.minhphan.launcher.R
import kotlin.math.max

/** How much of the scene width the car takes at most; the road's perspective may make it smaller. */
private const val MAX_WIDTH_FRACTION = 0.34f

// Where the two tail lamps are on car_audi.webp, as a fraction of its width and height.
private val LampLeft = Offset(0.170f, 0.557f)
private val LampRight = Offset(0.823f, 0.557f)
private const val LAMP_GLOW_RADIUS = 0.14f

private val BrakeRed = Color(0xFFFF2B2B)

// Headlights: the car is seen from behind, so its beams show as pools of light on the road ahead of it, and as a
// glow where that road meets the horizon. All of it is drawn behind the car picture, which is left untouched.
private val HeadlightWhite = Color(0xFFFFF1CF)
private const val BEAM_OFFSET = 0.22f
private const val BEAM_RADIUS = 0.19f
private const val BEAM_ALPHA = 0.6f
private const val HAZE_ALPHA = 0.3f

/**
 * The car: a picture of the black Audi A5 seen from behind (car_audi.webp, background removed with a smooth
 * anti-aliased edge, 2x the source size so it stays sharp), standing on the middle lane of the road in
 * [RoadLayer]. Its size and position come from the road's perspective. The tail lamps get a red glow: strong
 * when [braking] (the car is stopped), soft when it is driving at night. At night ([dark]) the picture gets the
 * same tint as the road so it does not look pasted on, and the headlights come on: they fade in and out with the
 * change of day and night.
 */
@Composable
fun AudiCarLayer(braking: Boolean, dark: Boolean, modifier: Modifier = Modifier) {
    val photo = ImageBitmap.imageResource(R.drawable.car_audi)
    val headlights by animateFloatAsState(if (dark) 1f else 0f, tween(durationMillis = 900), label = "headlights")
    Canvas(modifier) {
        val g = SceneGeometry(size.width, size.height)
        val carW = g.carWidth(MAX_WIDTH_FRACTION)
        val carH = carW * photo.height / photo.width
        val bottom = g.carBottom(carW)
        val left = g.vanishX - carW / 2f
        val top = bottom - carH

        // Contact shadow: a round gradient squashed into an oval under the bumper.
        val shadowCentre = Offset(g.vanishX, bottom)
        withTransform({ scale(1f, 0.12f, pivot = shadowCentre) }) {
            drawCircle(
                Brush.radialGradient(0f to Color.Black.copy(alpha = 0.95f), 0.6f to Color.Black.copy(alpha = 0.5f), 1f to Color.Transparent, center = shadowCentre, radius = carW * 0.66f),
                radius = carW * 0.66f,
                center = shadowCentre,
            )
        }

        // Read here, in the draw phase, so the fade only redraws and does not recompose.
        val beamStrength = headlights
        if (beamStrength > 0.01f) headlightBeams(g, carW, top, beamStrength)

        drawImage(
            photo,
            dstOffset = IntOffset(left.toInt(), top.toInt()),
            dstSize = IntSize(carW.toInt(), carH.toInt()),
            colorFilter = if (dark) ColorFilter.tint(NightTint, BlendMode.Modulate) else null,
            filterQuality = FilterQuality.High,
        )

        val glow = if (braking) 0.75f else if (dark) 0.35f else 0f
        if (glow > 0f) {
            val radius = carW * LAMP_GLOW_RADIUS
            for (lamp in listOf(LampLeft, LampRight)) {
                val at = Offset(left + lamp.x * carW, top + lamp.y * carH)
                drawCircle(
                    Brush.radialGradient(listOf(BrakeRed.copy(alpha = glow), Color.Transparent), center = at, radius = radius),
                    radius = radius,
                    center = at,
                    blendMode = BlendMode.Plus,
                )
            }
        }
    }
}

/**
 * Two pools of light on the road ahead, one either side of the car's centre line and reaching from just above its
 * roof towards the horizon, and a faint glow at the vanishing point where the beams run out. They are added to
 * the picture (BlendMode.Plus), so they brighten the dark road instead of covering it.
 */
private fun DrawScope.headlightBeams(g: SceneGeometry, carW: Float, carTop: Float, strength: Float) {
    // The stretch of road that shows between the car's roof and the horizon.
    val reach = max(carTop - g.vanishY, carW * 0.12f)
    val radius = carW * BEAM_RADIUS

    for (side in -1..1 step 2) {
        val centre = Offset(g.vanishX + side * carW * BEAM_OFFSET, carTop - reach * 0.2f)
        withTransform({ scale(1f, reach * 0.9f / radius, pivot = centre) }) {
            drawCircle(
                Brush.radialGradient(
                    listOf(HeadlightWhite.copy(alpha = BEAM_ALPHA * strength), Color.Transparent),
                    center = centre,
                    radius = radius,
                ),
                radius = radius,
                center = centre,
                blendMode = BlendMode.Plus,
            )
        }
    }

    val haze = Offset(g.vanishX, g.vanishY + reach * 0.15f)
    val hazeRadius = carW * 0.5f
    withTransform({ scale(1f, reach * 0.55f / hazeRadius, pivot = haze) }) {
        drawCircle(
            Brush.radialGradient(
                listOf(HeadlightWhite.copy(alpha = HAZE_ALPHA * strength), Color.Transparent),
                center = haze,
                radius = hazeRadius,
            ),
            radius = hazeRadius,
            center = haze,
            blendMode = BlendMode.Plus,
        )
    }
}
