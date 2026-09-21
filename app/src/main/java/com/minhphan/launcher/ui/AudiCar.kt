package com.minhphan.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.minhphan.launcher.R

/** How much of the scene width the car takes at most; the road's perspective may make it smaller. */
private const val MAX_WIDTH_FRACTION = 0.34f

// Where the two tail lamps are on car_audi.webp, as a fraction of its width and height.
private val LampLeft = Offset(0.170f, 0.557f)
private val LampRight = Offset(0.823f, 0.557f)
private const val LAMP_GLOW_RADIUS = 0.14f

private val BrakeRed = Color(0xFFFF2B2B)

/**
 * The car: a picture of the black Audi A5 seen from behind (car_audi.webp, background removed with a smooth
 * anti-aliased edge, 2x the source size so it stays sharp), standing on the middle lane of the road in
 * [RoadLayer]. Its size and position come from the road's perspective. The tail lamps get a red glow: strong
 * when [braking] (the car is stopped), soft when it is driving at night. At night ([dark]) the picture gets the
 * same tint as the road so it does not look pasted on.
 */
@Composable
fun AudiCarLayer(braking: Boolean, dark: Boolean, modifier: Modifier = Modifier) {
    val photo = ImageBitmap.imageResource(R.drawable.car_audi)
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
