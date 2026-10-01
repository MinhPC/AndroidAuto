package com.minhphan.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.minhphan.launcher.obd.ObdField
import com.minhphan.launcher.obd.ObdGroup

/** The small line pictures on the gauges and the tiles of Home. */
enum class ObdIcon { Car, Engine, Coolant, Thermometer, Wind, Battery, Fuel, Warning }

/** The picture for a tile: coolant has its own, the rest go by their group. */
val ObdField.icon: ObdIcon
    get() = when {
        this == ObdField.COOLANT -> ObdIcon.Coolant
        this == ObdField.INTAKE -> ObdIcon.Wind
        else -> when (group) {
            ObdGroup.ENGINE -> ObdIcon.Engine
            ObdGroup.TEMPERATURE -> ObdIcon.Thermometer
            ObdGroup.FUEL -> ObdIcon.Fuel
            ObdGroup.AIR -> ObdIcon.Wind
            ObdGroup.ELECTRICAL -> ObdIcon.Battery
            ObdGroup.FAULTS -> ObdIcon.Warning
        }
    }

/**
 * [icon] drawn in lines of [color], on a 24-unit square scaled to the modifier's size (as the dock's icons are), so
 * it needs no icon library and stays crisp at any size.
 */
@Composable
fun ObdIconImage(icon: ObdIcon, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        withTransform({ scale(u, u, pivot = Offset.Zero) }) {
            // In units: the scale above makes the line 1.8 / 24 of the icon, as on the dock.
            val line = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (icon) {
                ObdIcon.Car -> car(color, line)
                ObdIcon.Engine -> engine(color, line)
                ObdIcon.Coolant -> coolant(color, line)
                ObdIcon.Thermometer -> thermometer(color, line)
                ObdIcon.Wind -> wind(color, line)
                ObdIcon.Battery -> battery(color, line)
                ObdIcon.Fuel -> fuel(color, line)
                ObdIcon.Warning -> warning(color, line)
            }
        }
    }
}

/** The car from the front: body, windscreen, lamps and the two wheels under it. */
private fun DrawScope.car(color: Color, line: Stroke) {
    val body = Path().apply {
        moveTo(4f, 17f)
        lineTo(4f, 12.5f)
        lineTo(6f, 7.5f)
        quadraticTo(6.5f, 6f, 8f, 6f)
        lineTo(16f, 6f)
        quadraticTo(17.5f, 6f, 18f, 7.5f)
        lineTo(20f, 12.5f)
        lineTo(20f, 17f)
        close()
    }
    drawPath(body, color, style = line)
    drawLine(color, Offset(5.2f, 11.5f), Offset(18.8f, 11.5f), strokeWidth = line.width, cap = StrokeCap.Round)
    drawCircle(color, 1.2f, Offset(7.5f, 14.3f))
    drawCircle(color, 1.2f, Offset(16.5f, 14.3f))
    drawRoundRect(color, Offset(5f, 17f), Size(3.2f, 3f), CornerRadius(0.8f))
    drawRoundRect(color, Offset(15.8f, 17f), Size(3.2f, 3f), CornerRadius(0.8f))
}

/** An engine block from the side, with its filler cap on top. */
private fun DrawScope.engine(color: Color, line: Stroke) {
    val block = Path().apply {
        moveTo(7f, 9f)
        lineTo(15f, 9f)
        lineTo(17f, 11f)
        lineTo(19f, 11f)
        lineTo(19f, 9.5f)
        lineTo(21f, 9.5f)
        lineTo(21f, 16.5f)
        lineTo(19f, 16.5f)
        lineTo(19f, 15f)
        lineTo(17f, 15f)
        lineTo(15f, 18f)
        lineTo(9f, 18f)
        lineTo(7f, 16f)
        lineTo(5f, 16f)
        lineTo(5f, 14f)
        lineTo(3f, 14f)
        lineTo(3f, 11f)
        lineTo(5f, 11f)
        lineTo(5f, 9f)
        close()
    }
    drawPath(block, color, style = line)
    drawLine(color, Offset(8f, 6f), Offset(14f, 6f), strokeWidth = line.width, cap = StrokeCap.Round)
    drawLine(color, Offset(11f, 6f), Offset(11f, 9f), strokeWidth = line.width, cap = StrokeCap.Round)
}

/** A thermometer: a stem closed at the top, a bulb, and the column of mercury. */
private fun DrawScope.thermometer(color: Color, line: Stroke) {
    // The stem's sides meet the bulb (centre 12, 17.5; radius 3.5) where x is 10 and 14.
    val glass = Path().apply {
        moveTo(10f, 14.63f)
        lineTo(10f, 5f)
        arcTo(Rect(10f, 3f, 14f, 7f), 180f, 180f, false)
        lineTo(14f, 14.63f)
        arcTo(Rect(8.5f, 14f, 15.5f, 21f), -55f, 290f, false)
        close()
    }
    drawPath(glass, color, style = line)
    drawCircle(color, 1.6f, Offset(12f, 17.5f))
    drawLine(color, Offset(12f, 17.5f), Offset(12f, 9f), strokeWidth = line.width, cap = StrokeCap.Round)
}

/** The thermometer standing in water: smaller, with two waves under it. */
private fun DrawScope.coolant(color: Color, line: Stroke) {
    withTransform({
        scale(0.72f, 0.72f, pivot = Offset(12f, 2f))
    }) {
        thermometer(color, line)
    }
    for (y in floatArrayOf(19.5f, 22.5f)) {
        val wave = Path().apply {
            moveTo(3f, y)
            var x = 3f
            var up = true
            while (x < 21f) {
                quadraticTo(x + 1.5f, if (up) y - 1.4f else y + 1.4f, x + 3f, y)
                x += 3f
                up = !up
            }
        }
        drawPath(wave, color, style = line)
    }
}

/** Three gusts, each ending in a curl. */
private fun DrawScope.wind(color: Color, line: Stroke) {
    val gusts = Path().apply {
        moveTo(3f, 8.5f)
        lineTo(13.5f, 8.5f)
        arcTo(Rect(11.5f, 4.5f, 15.5f, 8.5f), 90f, -270f, false)
        moveTo(3f, 12.5f)
        lineTo(18f, 12.5f)
        arcTo(Rect(16f, 12.5f, 20f, 16.5f), -90f, 270f, false)
        moveTo(3f, 16.5f)
        lineTo(10f, 16.5f)
        arcTo(Rect(8f, 16.5f, 12f, 20.5f), -90f, 270f, false)
    }
    drawPath(gusts, color, style = line)
}

/** A car battery: the case, its two terminals, and minus and plus. */
private fun DrawScope.battery(color: Color, line: Stroke) {
    drawRoundRect(color, Offset(3f, 7.5f), Size(18f, 12f), CornerRadius(2f), style = line)
    drawRoundRect(color, Offset(6f, 5f), Size(3.2f, 2.5f), CornerRadius(0.6f))
    drawRoundRect(color, Offset(14.8f, 5f), Size(3.2f, 2.5f), CornerRadius(0.6f))
    drawLine(color, Offset(6.3f, 13.5f), Offset(9.7f, 13.5f), strokeWidth = line.width, cap = StrokeCap.Round)
    drawLine(color, Offset(14.3f, 13.5f), Offset(17.7f, 13.5f), strokeWidth = line.width, cap = StrokeCap.Round)
    drawLine(color, Offset(16f, 11.8f), Offset(16f, 15.2f), strokeWidth = line.width, cap = StrokeCap.Round)
}

/** A fuel pump: the pump with its window, and the hose hanging from its side. */
private fun DrawScope.fuel(color: Color, line: Stroke) {
    drawRoundRect(color, Offset(4f, 4f), Size(9f, 16f), CornerRadius(1.5f), style = line)
    drawRoundRect(color, Offset(6.3f, 6.5f), Size(4.4f, 4f), CornerRadius(0.6f), style = line)
    drawLine(color, Offset(2.5f, 20.5f), Offset(14.5f, 20.5f), strokeWidth = line.width, cap = StrokeCap.Round)
    val hose = Path().apply {
        moveTo(13f, 10f)
        lineTo(15.5f, 10f)
        lineTo(15.5f, 16.5f)
        cubicTo(15.5f, 18.8f, 19f, 18.8f, 19f, 16.5f)
        lineTo(19f, 8f)
        lineTo(16.5f, 5.5f)
    }
    drawPath(hose, color, style = line)
}

/** A warning triangle with an exclamation mark. */
private fun DrawScope.warning(color: Color, line: Stroke) {
    val triangle = Path().apply {
        moveTo(12f, 3.5f)
        lineTo(21.5f, 20f)
        lineTo(2.5f, 20f)
        close()
    }
    drawPath(triangle, color, style = line)
    drawLine(color, Offset(12f, 9.5f), Offset(12f, 14f), strokeWidth = line.width, cap = StrokeCap.Round)
    drawCircle(color, 1.1f, Offset(12f, 17f))
}
