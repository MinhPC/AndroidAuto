package com.minhphan.launcher.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The dial is an arc of this many degrees, open at the bottom, starting at the lower left. */
private const val SWEEP = 270f
private const val START_ANGLE = 135f

/**
 * A round dashboard gauge: a track with numbered ticks and an optional red zone, an arc that grows to [value]
 * with a bright tip, and the reading in the middle. [value] null means unknown and shows [readout] as it is
 * (usually "--") with the arc empty. The arc eases to each new value instead of jumping.
 *
 * @param maxValue the value at the end of the arc.
 * @param majorStep distance between numbered ticks; [tickLabel] formats them.
 * @param redFrom start of the red zone, if the scale has one; the arc turns red past it.
 */
@Composable
fun DialGauge(
    value: Float?,
    maxValue: Float,
    readout: String,
    unit: String,
    label: String,
    majorStep: Float,
    tickLabel: (Float) -> String,
    modifier: Modifier = Modifier,
    redFrom: Float? = null,
) {
    val shown by animateFloatAsState(
        targetValue = (value ?: 0f).coerceIn(0f, maxValue),
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "gauge",
    )
    val accent = MaterialTheme.colorScheme.primary
    val danger = MaterialTheme.colorScheme.error
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val tick = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val tickText = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val diameter = minOf(maxWidth, maxHeight)
        // Type is sized from the dial so it scales from a phone to a wide head unit; a long reading such as
        // 3391 rpm gets a smaller size so it stays inside the ticks.
        val readoutSize = with(density) { (diameter * (if (readout.length > 3) 0.2f else 0.27f)).toSp() }
        val unitSize = with(density) { (diameter * 0.085f).toSp() }
        val tickSize = with(density) { (diameter * 0.07f).toSp() }
        // Measured once per size, not on every new reading.
        val majors = remember(maxValue, majorStep) { generateSequence(0f) { it + majorStep }.takeWhile { it <= maxValue + 0.001f }.toList() }
        val labels = remember(majors, tickSize) {
            majors.map { measurer.measure(tickLabel(it), TextStyle(fontSize = tickSize, fontWeight = FontWeight.Medium)) }
        }

        Box(
            Modifier
                .size(diameter)
                .drawWithCache {
                    val stroke = size.minDimension * 0.075f
                    val radius = (size.minDimension - stroke) / 2f
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    val arcTopLeft = Offset(centre.x - radius, centre.y - radius)
                    val arcSize = Size(radius * 2f, radius * 2f)
                    val style = Stroke(width = stroke, cap = StrokeCap.Round)
                    val tickOuter = radius - stroke * 0.9f
                    val tickInner = tickOuter - stroke * 0.55f
                    val labelRadius = tickInner - stroke * 1.05f

                    fun angleOf(v: Float) = Math.toRadians((START_ANGLE + SWEEP * (v / maxValue)).toDouble()).toFloat()
                    fun pointAt(v: Float, r: Float) = Offset(centre.x + r * cos(angleOf(v)), centre.y + r * sin(angleOf(v)))

                    onDrawBehind {
                        // The track, with the red zone marked on it.
                        drawArc(track, START_ANGLE, SWEEP, false, arcTopLeft, arcSize, style = style)
                        if (redFrom != null) {
                            val from = redFrom / maxValue
                            drawArc(danger.copy(alpha = 0.32f), START_ANGLE + SWEEP * from, SWEEP * (1f - from), false, arcTopLeft, arcSize, style = style)
                        }

                        // Ticks: a long one with a number at every step, a short one halfway between.
                        majors.forEachIndexed { i, v ->
                            val inRed = redFrom != null && v >= redFrom
                            drawLine(if (inRed) danger else tick, pointAt(v, tickInner), pointAt(v, tickOuter), strokeWidth = stroke * 0.14f, cap = StrokeCap.Round)
                            val at = pointAt(v, labelRadius)
                            drawText(labels[i], color = if (inRed) danger else tickText, topLeft = Offset(at.x - labels[i].size.width / 2f, at.y - labels[i].size.height / 2f))
                            val mid = v + majorStep / 2f
                            if (mid < maxValue - 0.001f) {
                                drawLine(tick.copy(alpha = 0.6f), pointAt(mid, tickOuter - stroke * 0.25f), pointAt(mid, tickOuter), strokeWidth = stroke * 0.1f, cap = StrokeCap.Round)
                            }
                        }

                        // The reading: blue up to the red zone, red beyond it.
                        val fraction = shown / maxValue
                        if (fraction > 0.004f) {
                            val redAt = redFrom?.let { it / maxValue } ?: 1f
                            val blueEnd = min(fraction, redAt)
                            drawArc(accent, START_ANGLE, SWEEP * blueEnd, false, arcTopLeft, arcSize, style = style)
                            if (fraction > redAt) {
                                drawArc(danger, START_ANGLE + SWEEP * redAt, SWEEP * (fraction - redAt), false, arcTopLeft, arcSize, style = style)
                            }
                            val tip = pointAt(shown, radius)
                            val tipColor = if (fraction > redAt) danger else accent
                            drawCircle(tipColor.copy(alpha = 0.28f), radius = stroke * 0.95f, center = tip)
                            drawCircle(Color.White, radius = stroke * 0.32f, center = tip)
                        }
                    }
                },
        )

        // The reading and the caption sit inside the dial's own square, whatever the cell around it looks like.
        Box(Modifier.size(diameter)) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(
                    text = readout,
                    style = MaterialTheme.typography.displayMedium.copy(fontSize = readoutSize, lineHeight = readoutSize, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = unitSize, lineHeight = unitSize),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = diameter * 0.015f),
            )
        }
    }
}
