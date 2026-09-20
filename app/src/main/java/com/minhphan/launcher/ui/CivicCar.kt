package com.minhphan.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

/** Width / length of a Honda Civic sedan (8th generation, 2006-2011): 1750 mm x 4540 mm. */
private const val WIDTH_TO_LENGTH = 0.385f

/** Where the car sits in the scene, as a fraction of the scene height (a bit low, so the road ahead shows). */
private const val CAR_CENTER_Y = 0.62f

private val BodyEdge = Color(0xFF0A0B0D)
private val BodyMid = Color(0xFF2A2F37)
private val Glass = Color(0xFF16222E)
private val GlassTop = Color(0xFF263B50)
private val TailLightOn = Color(0xFFFF2A2A)
private val TailLightDim = Color(0xFF8E1A1A)
private val HeadLightOn = Color(0xFFFFF3C9)
private val HeadLightOff = Color(0xFFB9C4CF)

/**
 * A black Honda Civic seen from above, heading up, centred in a full-size layer. Drawn from Bezier paths
 * in the car's own unit square (u across, v along; -1..1) so it scales to any scene size. Everything is
 * built once per size and cached, so the moving road behind it never forces the car to be re-recorded.
 * [braking] lights the tail lamps bright red (the car is stopped); [lightsOn] adds headlight beams (night).
 */
@Composable
fun CivicCarLayer(braking: Boolean, lightsOn: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.drawWithCache {
            val w = size.width
            val h = size.height
            // The car fills 82% of one lane of a three-lane road that is 80% of the scene width.
            var carW = w * 0.8f / 3f * 0.82f
            var carL = carW / WIDTH_TO_LENGTH
            if (carL > h * 0.55f) {
                carL = h * 0.55f
                carW = carL * WIDTH_TO_LENGTH
            }
            val cx = w / 2f
            val cy = h * CAR_CENTER_Y
            fun px(u: Float) = cx + u * carW / 2f
            fun py(v: Float) = cy + v * carL / 2f
            fun Path.pt(move: Boolean, u: Float, v: Float) = if (move) moveTo(px(u), py(v)) else lineTo(px(u), py(v))
            fun polygon(vararg uv: Float) = Path().apply {
                for (i in uv.indices step 2) pt(i == 0, uv[i], uv[i + 1])
                close()
            }
            /** Mirrors a polygon given for the right side onto the left side as well. */
            fun pair(vararg uv: Float) = listOf(
                polygon(*uv),
                polygon(*FloatArray(uv.size) { if (it % 2 == 0) -uv[it] else uv[it] }),
            )

            val body = Path().apply {
                moveTo(px(0f), py(-1f))
                cubicTo(px(0.50f), py(-1.00f), px(0.75f), py(-0.99f), px(0.86f), py(-0.95f))
                cubicTo(px(0.95f), py(-0.90f), px(1.00f), py(-0.80f), px(1.00f), py(-0.62f))
                cubicTo(px(1.00f), py(-0.30f), px(1.00f), py(0.10f), px(1.00f), py(0.35f))
                cubicTo(px(1.00f), py(0.60f), px(0.97f), py(0.80f), px(0.90f), py(0.88f))
                cubicTo(px(0.75f), py(0.98f), px(0.45f), py(1.00f), px(0f), py(1f))
                cubicTo(px(-0.45f), py(1.00f), px(-0.75f), py(0.98f), px(-0.90f), py(0.88f))
                cubicTo(px(-0.97f), py(0.80f), px(-1.00f), py(0.60f), px(-1.00f), py(0.35f))
                cubicTo(px(-1.00f), py(0.10f), px(-1.00f), py(-0.30f), px(-1.00f), py(-0.62f))
                cubicTo(px(-1.00f), py(-0.80f), px(-0.95f), py(-0.90f), px(-0.86f), py(-0.95f))
                cubicTo(px(-0.75f), py(-0.99f), px(-0.50f), py(-1.00f), px(0f), py(-1f))
                close()
            }
            val bodyBrush = Brush.horizontalGradient(
                0f to BodyEdge, 0.30f to Color(0xFF1B1F25), 0.5f to BodyMid, 0.70f to Color(0xFF1B1F25), 1f to BodyEdge,
                startX = px(-1f),
                endX = px(1f),
            )
            val windshield = Path().apply {
                moveTo(px(-0.78f), py(-0.50f))
                quadraticTo(px(0f), py(-0.56f), px(0.78f), py(-0.50f))
                lineTo(px(0.70f), py(-0.20f))
                quadraticTo(px(0f), py(-0.23f), px(-0.70f), py(-0.20f))
                close()
            }
            val rearWindow = Path().apply {
                moveTo(px(-0.70f), py(0.30f))
                quadraticTo(px(0f), py(0.33f), px(0.70f), py(0.30f))
                lineTo(px(0.77f), py(0.52f))
                quadraticTo(px(0f), py(0.56f), px(-0.77f), py(0.52f))
                close()
            }
            val roof = Path().apply {
                moveTo(px(-0.70f), py(-0.20f))
                quadraticTo(px(0f), py(-0.23f), px(0.70f), py(-0.20f))
                lineTo(px(0.70f), py(0.30f))
                quadraticTo(px(0f), py(0.33f), px(-0.70f), py(0.30f))
                close()
            }
            val hoodShine = polygon(-0.50f, -0.90f, 0.50f, -0.90f, 0.62f, -0.56f, -0.62f, -0.56f)
            val headlights = pair(0.56f, -0.965f, 0.90f, -0.90f, 0.88f, -0.80f, 0.60f, -0.86f)
            val tailLights = pair(0.45f, 0.975f, 0.86f, 0.90f, 0.90f, 0.80f, 0.50f, 0.90f)
            val beam = Path().apply {
                moveTo(px(-0.55f), py(-0.98f))
                lineTo(px(0.55f), py(-0.98f))
                lineTo(px(1.9f), 0f)
                lineTo(px(-1.9f), 0f)
                close()
            }
            val wheelSize = Size(carW * 0.13f, carL * 0.14f)

            onDrawBehind {
                if (lightsOn) {
                    drawPath(
                        beam,
                        Brush.verticalGradient(
                            listOf(Color(0x00FFF6D8), Color(0x66FFF6D8)),
                            startY = 0f,
                            endY = py(-0.98f),
                        ),
                    )
                }
                // Soft ground shadow.
                drawRoundRect(
                    Color.Black.copy(alpha = 0.35f),
                    topLeft = Offset(px(-1f) + carW * 0.05f, py(-1f) + carL * 0.03f),
                    size = Size(carW, carL),
                    cornerRadius = CornerRadius(carW * 0.3f),
                )
                // Wheels first: only a sliver sticks out beyond the body.
                for (v in listOf(-0.56f, 0.56f)) {
                    for (side in listOf(-1f, 1f)) {
                        drawRoundRect(
                            Color(0xFF050506),
                            topLeft = Offset(px(side * 1.03f) - wheelSize.width / 2f, py(v) - wheelSize.height / 2f),
                            size = wheelSize,
                            cornerRadius = CornerRadius(carW * 0.03f),
                        )
                    }
                }
                // Door mirrors.
                for (side in listOf(-1f, 1f)) {
                    drawRoundRect(
                        BodyEdge,
                        topLeft = Offset(px(side * 1.10f) - carW * 0.05f, py(-0.40f)),
                        size = Size(carW * 0.10f, carL * 0.05f),
                        cornerRadius = CornerRadius(carW * 0.03f),
                    )
                }
                drawPath(body, bodyBrush)
                drawPath(hoodShine, Color.White.copy(alpha = 0.06f))
                drawPath(body, Color.White.copy(alpha = 0.20f), style = Stroke(width = 1.5f))
                // Roof, then glass on top of it.
                drawPath(roof, Brush.verticalGradient(listOf(Color(0xFF20252C), Color(0xFF14171C)), startY = py(-0.2f), endY = py(0.3f)))
                val glassBrush = Brush.verticalGradient(listOf(GlassTop, Glass), startY = py(-0.5f), endY = py(-0.2f))
                drawPath(windshield, glassBrush)
                drawPath(rearWindow, Glass)
                // Panel creases and the chrome bar in the nose.
                drawLine(Color.White.copy(alpha = 0.12f), Offset(px(-0.85f), py(0.55f)), Offset(px(0.85f), py(0.55f)), strokeWidth = 1f)
                drawLine(Color(0xFFD0D4DA).copy(alpha = 0.7f), Offset(px(-0.30f), py(-0.955f)), Offset(px(0.30f), py(-0.955f)), strokeWidth = 2f)
                // Lamps.
                headlights.forEach { drawPath(it, if (lightsOn) HeadLightOn else HeadLightOff) }
                tailLights.forEach { drawPath(it, if (braking) TailLightOn else TailLightDim) }
                if (braking) {
                    for (side in listOf(-1f, 1f)) {
                        drawCircle(
                            Brush.radialGradient(
                                listOf(TailLightOn.copy(alpha = 0.45f), Color.Transparent),
                                center = Offset(px(side * 0.70f), py(0.98f)),
                                radius = carW * 0.55f,
                            ),
                            radius = carW * 0.55f,
                            center = Offset(px(side * 0.70f), py(0.98f)),
                        )
                    }
                }
            }
        },
    )
}
