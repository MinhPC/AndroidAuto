package com.minhphan.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minhphan.launcher.R
import com.minhphan.launcher.data.SpeedSource
import com.minhphan.launcher.data.CarSpeed
import com.minhphan.launcher.obd.ObdField
import com.minhphan.launcher.obd.ObdProblem
import com.minhphan.launcher.obd.ObdState
import com.minhphan.launcher.obd.ObdValues
import com.minhphan.launcher.obd.homeOrder
import com.minhphan.launcher.obd.reading
import java.text.NumberFormat
import java.util.EnumMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** How faded the values are while the connection is being re-established. */
private const val STALE_ALPHA = 0.45f

// Where each smaller reading turns amber and red.
private const val COOLANT_WARN_C = 100f
private const val COOLANT_HOT_C = 105f
private const val INTAKE_WARN_C = 60f
private const val INTAKE_HOT_C = 75f
private const val TRIM_WARN_PERCENT = 10f
private const val TRIM_BAD_PERCENT = 20f
private const val TRIM_SPAN_PERCENT = 25f
private const val OIL_WARN_C = 125f
private const val OIL_HOT_C = 140f
private const val FUEL_WARN_PERCENT = 20f
private const val FUEL_LOW_PERCENT = 10f
private const val SPEED_MAX_KMH = 240f
private const val RPM_MAX = 8000f
private const val RPM_REDLINE = 6500f
private const val VOLTAGE_LOW = 11.8f
private const val VOLTAGE_HIGH = 15f
private const val CATALYST_WARN_C = 850f
private const val CATALYST_HOT_C = 950f

/**
 * The gap between the rows of the panel and between the blocks in a row, so the tiles line up under the gauges; the
 * road shows through it.
 */
private val PanelGap = 12.dp

/** The gap on a short screen, where every row needs the height. */
private val CompactPanelGap = 8.dp

/** How much of the road shows through a block of the panel: a little, like the dock's glass. */
private const val GLASS_ALPHA = 0.82f

/** A block of the panel: glass of [tint] (the card's colour unless a reading tints it) with an edge of [edge]. */
@Composable
private fun Modifier.glass(
    shape: Shape,
    tint: Color = MaterialTheme.colorScheme.surface,
    edge: Color = MaterialTheme.colorScheme.outlineVariant,
) = clip(shape).background(tint.copy(alpha = GLASS_ALPHA)).border(1.dp, edge, shape)

/** How a reading is coloured: normal, worth a look, or wrong. */
private enum class Tone { Normal, Warn, Danger }

/**
 * The right half of Home, read from the OBD adapter: the car's speed ([speed]: the adapter's, or the GPS's) and the
 * engine speed side by side on two large tiles, each with a bar of lit segments (the engine's up to its red line), then
 * the values the driver chose in Settings as tiles, each with its picture and a thin bar of how high it
 * stands, in [homeOrder] (by default coolant beside battery voltage, engine load beside intake air, two to a row; more
 * than four go three to a row, and without the pictures, which would crowd the narrower tiles). The adapter's state is
 * beside the panel's title. Values the car does not report show "--";
 * while there is no connection, in place of the tiles the panel says why and, where the user can fix it, offers the
 * way. While the link is only being re-established the last reading stays up, faded.
 *
 * [compact], for a short screen (a head unit at 240 dpi): no title, the adapter's state on the speed's tile instead,
 * tighter gaps, and the tiles without their dials, so the figures keep their room.
 */
@Composable
fun ObdPanel(
    obd: StateFlow<ObdState>,
    speed: () -> CarSpeed,
    fields: List<ObdField>,
    bluetooth: BluetoothPermission,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    // Collected here, not by the caller, so a new reading recomposes only this panel and not the whole home screen.
    val state by obd.collectAsStateWithLifecycle()
    val (heldReading, expiresInMs) = when (val s = state) {
        is ObdState.Connected -> null to 0L
        is ObdState.Connecting -> s.last to s.lastExpiresInMs
        is ObdState.Problem -> s.last to s.lastExpiresInMs
    }
    // The connection can stay silent for longer than a reading is worth showing, so the panel drops it on its own.
    var expired by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (heldReading != null) {
            delay(expiresInMs)
            expired = true
        }
    }
    val last = heldReading.takeUnless { expired }
    val values = (state as? ObdState.Connected)?.values ?: last ?: ObdValues()
    val dim = if (last != null) STALE_ALPHA else 1f
    val problem = (state as? ObdState.Problem)?.takeIf { last == null }?.problem
    // Speed and engine speed have their own large tiles; picked as small ones too they would only say it twice.
    // Laid out once for the driver's choice, not again on every reading.
    val tiles = remember(fields) { fields.filter { it != ObdField.SPEED && it != ObdField.RPM } }

    val density = LocalDensity.current
    // No card of its own: the title and each tile are blocks of glass apart, so the road shows between them.
    BoxWithConstraints(modifier) {
        // Type is sized from the panel height, so it fills the panel on a wide screen and a tall one; with more rows
        // of tiles each is shorter, so the type is smaller.
        val columns = if (tiles.size <= 4) 2 else 3
        val rows = (tiles.size + columns - 1) / columns
        val tileRows = remember(tiles, columns) { homeOrder(tiles).chunked(columns) }
        val tileValueSize = with(density) { (maxHeight * (if (rows > 2) 0.052f else 0.068f)).toSp() }
        val gap = if (compact) CompactPanelGap else PanelGap

        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            if (!compact) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(18.dp))
                        .padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.obd_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    ObdStatus(state, Modifier.padding(start = 12.dp))
                }
            }
            Row(
                // With a problem to explain, the dials (with little on them) give up height to the words and the button.
                Modifier.weight(
                    when {
                        problem != null -> if (compact) 0.8f else 1f
                        rows > 2 -> 1.5f
                        else -> 1.9f
                    },
                ).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                SpeedTile(
                    speed,
                    Modifier.weight(1f).fillMaxHeight(),
                    compact = compact,
                    status = if (compact) ({ ObdStatus(state, Modifier, size = 20.dp) }) else null,
                )
                RpmTile(values.rpm, Modifier.weight(1f).fillMaxHeight().alpha(dim), compact)
            }
            if (problem != null) {
                Recovery(
                    problem,
                    bluetooth,
                    onOpenSettings,
                    Modifier.weight(1.4f).fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(if (compact) 12.dp else 16.dp),
                    compact = compact,
                )
                return@Column
            }

            // The values the driver chose in Settings; an empty place keeps the tiles the same width.
            // Each tile is given its own reading only, so a new reading redraws the tiles whose value changed and no other.
            tileRows.forEach { row ->
                TileRow(dim, gap) {
                    row.forEach { field ->
                        FieldTile(field, values.reading(field), tileValueSize, roomy = columns == 2 && !compact, compact = compact)
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** How a value is written on its tile, how full its bar is (null: a count, with no bar), and when it turns amber and red. */
private class FieldStyle(
    val text: (Float) -> String,
    val fraction: (Float) -> Float?,
    val tone: (Float) -> Tone = { Tone.Normal },
)

private fun whole(value: Float) = value.roundToInt().toString()

private fun signed(value: Float) = "%+d".format(value.roundToInt())

private fun tenths(value: Float) = "%.1f".format(value)

private fun hundredths(value: Float) = "%.2f".format(value)

private fun above(value: Float, warn: Float, danger: Float) = when {
    value >= danger -> Tone.Danger
    value >= warn -> Tone.Warn
    else -> Tone.Normal
}

private fun below(value: Float, warn: Float, danger: Float) = when {
    value <= danger -> Tone.Danger
    value <= warn -> Tone.Warn
    else -> Tone.Normal
}

/** Each field's style, made once: a tile looks its own up on every reading. */
private val FieldStyles: Map<ObdField, FieldStyle> = ObdField.entries.associateWithTo(EnumMap(ObdField::class.java), ::makeStyle)

private fun styleOf(field: ObdField): FieldStyle = FieldStyles.getValue(field)

private fun makeStyle(field: ObdField): FieldStyle = when (field) {
    ObdField.COOLANT -> FieldStyle(::whole, { (it - 40) / 80f }, { above(it, COOLANT_WARN_C, COOLANT_HOT_C) })
    ObdField.INTAKE -> FieldStyle(::whole, { it / 80f }, { above(it, INTAKE_WARN_C, INTAKE_HOT_C) })
    ObdField.VOLTAGE, ObdField.MODULE_VOLTAGE -> FieldStyle(
        ::tenths,
        { (it - 10f) / 6f },
        { if (it < VOLTAGE_LOW || it > VOLTAGE_HIGH) Tone.Danger else Tone.Normal },
    )
    ObdField.LOAD, ObdField.THROTTLE, ObdField.ABSOLUTE_LOAD, ObdField.RELATIVE_THROTTLE, ObdField.THROTTLE_ACTUATOR,
    ObdField.PEDAL, ObdField.EGR, ObdField.EVAP_PURGE, ObdField.ETHANOL, ObdField.TORQUE, ObdField.DEMAND_TORQUE,
    -> FieldStyle(::whole, { it / 100f })
    // The bar sits half full at zero: filled more when the engine adds fuel, less when it takes it away.
    ObdField.FUEL_TRIM, ObdField.SHORT_TRIM, ObdField.FUEL_TRIM_2, ObdField.SHORT_TRIM_2 ->
        FieldStyle(::signed, { (it + TRIM_SPAN_PERCENT) / (2 * TRIM_SPAN_PERCENT) }, { above(abs(it), TRIM_WARN_PERCENT, TRIM_BAD_PERCENT) })
    ObdField.MAP -> FieldStyle(::whole, { it / 110f })
    ObdField.TIMING -> FieldStyle(::signed, { (it + 10f) / 60f })
    ObdField.MAF -> FieldStyle(::whole, { it / 100f })
    ObdField.AMBIENT -> FieldStyle(::whole, { (it + 10f) / 60f })
    ObdField.BARO -> FieldStyle(::whole, { (it - 80f) / 40f })
    ObdField.OIL -> FieldStyle(::whole, { (it - 40f) / 110f }, { above(it, OIL_WARN_C, OIL_HOT_C) })
    ObdField.FUEL_LEVEL -> FieldStyle(::whole, { it / 100f }, { below(it, FUEL_WARN_PERCENT, FUEL_LOW_PERCENT) })
    ObdField.SPEED -> FieldStyle(::whole, { it / SPEED_MAX_KMH })
    ObdField.RPM -> FieldStyle(::whole, { it / RPM_MAX }, { above(it, RPM_REDLINE, RPM_REDLINE) })
    // Lambda 1 is the stoichiometric mix, so the bar sits half full there: fuller when lean, emptier when rich.
    ObdField.LAMBDA -> FieldStyle(::hundredths, { (it - 0.7f) / 0.6f })
    ObdField.FUEL_RATE -> FieldStyle(::tenths, { it / 20f })
    ObdField.FUEL_PRESSURE -> FieldStyle(::whole, { it / 600f })
    ObdField.RAIL_PRESSURE -> FieldStyle(::whole, { it / 250f })
    ObdField.CATALYST -> FieldStyle(::whole, { it / 1000f }, { above(it, CATALYST_WARN_C, CATALYST_HOT_C) })
    ObdField.REFERENCE_TORQUE -> FieldStyle(::whole, { null })
    ObdField.RUN_TIME -> FieldStyle(::whole, { it / 120f })
    // Any distance or time with the fault light on means the car has had a fault since the codes were cleared.
    ObdField.MIL_DISTANCE, ObdField.MIL_TIME -> FieldStyle(::whole, { null }, { above(it, 1f, 1f) })
    ObdField.CLEARED_DISTANCE, ObdField.CLEARED_TIME, ObdField.WARM_UPS -> FieldStyle(::whole, { null })
}

/**
 * One chosen value. On a tile with room ([roomy]: two to a row): its name over the value and unit on the left, and on
 * the right a small dial like the large ones, an arc filled to how high the value stands round the value's picture,
 * all in the tone's colour, so a hot engine shows in the arc, the picture and the figure at once. On a narrow tile
 * (three to a row) there is no room for the dial, and a thin bar under the value says how high it stands instead.
 */
@Composable
private fun RowScope.FieldTile(field: ObdField, reading: Float?, valueSize: TextUnit, roomy: Boolean, compact: Boolean) {
    val style = styleOf(field)
    val tone = reading?.let(style.tone) ?: Tone.Normal
    val colors = MaterialTheme.colorScheme
    val toneColor = toneColor(tone)
    val fraction = reading?.let(style.fraction)?.coerceIn(0f, 1f)
    // A count (the warm-ups, say) has no level to show.
    val hasLevel = style.fraction(0f) != null || fraction != null
    val shape = RoundedCornerShape(18.dp)
    // A value worth a look tints its whole tile, so it is seen at a glance and not only when its figure is read.
    val warned = reading != null && tone != Tone.Normal
    Row(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .glass(
                shape,
                tint = if (warned) lerp(colors.surface, toneColor, 0.14f) else colors.surface,
                edge = if (warned) toneColor.copy(alpha = 0.55f) else colors.outlineVariant,
            )
            .alpha(if (reading == null) 0.55f else 1f)
            .padding(start = 16.dp, end = 12.dp, top = if (compact) 6.dp else 10.dp, bottom = if (compact) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(field.label),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The unit sits on the value's baseline, as printed figures do.
            Row {
                Text(
                    text = reading?.let(style.text) ?: "--",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = valueSize, lineHeight = valueSize, fontWeight = FontWeight.Bold, fontFeatureSettings = TabularFigures,
                    ),
                    color = if (tone == Tone.Normal) colors.onSurface else toneColor,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
                Text(
                    text = field.unit,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.alignByBaseline().padding(start = 4.dp),
                    maxLines = 1,
                )
            }
            if (!roomy && hasLevel) LevelBar(fraction, toneColor) else Spacer(Modifier.height(0.dp))
        }
        if (roomy) {
            LevelRing(
                fraction = if (hasLevel) fraction ?: 0f else null,
                color = toneColor,
                modifier = Modifier.padding(start = 8.dp).fillMaxHeight(0.86f).aspectRatio(1f),
            ) {
                ObdIconImage(field.icon, toneColor, Modifier.fillMaxSize(0.42f))
            }
        }
    }
}

/**
 * A small dial round [content]: the large dials' open arc, its track faint and filled to [fraction] in [color]; with
 * [fraction] null (a count, with no level) only a faint ring. Drawn, not composed, and only when the reading changes.
 */
@Composable
private fun LevelRing(fraction: Float?, color: Color, modifier: Modifier, content: @Composable () -> Unit) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val chip = color.copy(alpha = 0.12f)
    Box(
        modifier.drawWithCache {
            val width = size.minDimension * 0.085f
            val inset = width / 2f
            val arcSize = Size(size.width - width, size.height - width)
            val topLeft = Offset(inset, inset)
            val line = Stroke(width, cap = StrokeCap.Round)
            onDrawBehind {
                // The picture's chip inside the ring, in the tone's colour.
                drawCircle(chip, radius = size.minDimension / 2f - width * 1.9f)
                if (fraction == null) {
                    drawCircle(track, radius = size.minDimension / 2f - inset, style = Stroke(width))
                } else {
                    drawArc(track, DIAL_START, DIAL_SWEEP, useCenter = false, topLeft = topLeft, size = arcSize, style = line)
                    if (fraction > 0f) {
                        drawArc(color, DIAL_START, DIAL_SWEEP * fraction, useCenter = false, topLeft = topLeft, size = arcSize, style = line)
                    }
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** A thin bar filled to [fraction] (empty while unknown), the way an electric car's screen shows a level. */
@Composable
private fun LevelBar(fraction: Float?, color: Color) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    Canvas(Modifier.fillMaxWidth().height(5.dp)) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(track, cornerRadius = radius)
        if (fraction != null && fraction > 0f) {
            drawRoundRect(
                Brush.horizontalGradient(listOf(color.copy(alpha = 0.7f), color), endX = size.width * fraction),
                size = Size(size.width * fraction, size.height),
                cornerRadius = radius,
            )
        }
    }
}

@Composable
private fun toneColor(tone: Tone) = when (tone) {
    Tone.Normal -> MaterialTheme.colorScheme.primary
    Tone.Warn -> StatusWarn
    Tone.Danger -> MaterialTheme.colorScheme.error
}

/**
 * The adapter's state as a dot in its colour (green connected, amber connecting, red a problem) in a soft ring of it,
 * the dot blinking for a while when not connected. The state in words is only read out, for a screen reader.
 */
@Composable
private fun ObdStatus(state: ObdState, modifier: Modifier, size: Dp = 28.dp) {
    val (color, text) = when (state) {
        is ObdState.Connected -> StatusGood to R.string.obd_header_connected
        is ObdState.Connecting -> StatusWarn to R.string.obd_header_connecting
        is ObdState.Problem -> MaterialTheme.colorScheme.error to R.string.obd_header_problem
    }
    val description = stringResource(text)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f))
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        // Blinks for a while when the link is not up (being made, or a problem), steady once it is.
        Box(Modifier.size(size * 0.43f).blinkWhile(state !is ObdState.Connected).clip(CircleShape).background(color))
    }
}

/** The engine speed on its dial, red past the red line. */
@Composable
private fun RpmTile(rpm: Int?, modifier: Modifier, compact: Boolean) {
    val rpmFormat = remember { NumberFormat.getIntegerInstance() }
    GaugeTile(
        label = stringResource(R.string.obd_rpm),
        value = rpm?.let { rpmFormat.format(it) } ?: "--",
        unit = ObdField.RPM.unit,
        fraction = (rpm ?: 0) / RPM_MAX,
        redFrom = RPM_REDLINE / RPM_MAX,
        danger = rpm != null && rpm >= RPM_REDLINE,
        modifier = modifier,
        compact = compact,
    )
}

/**
 * The car's speed on its dial, from the scene: the adapter's, or the GPS's while there is no adapter, which the tile
 * then says. Read here only, so a new speed recomposes this tile and nothing else. [status], where given, sits at the
 * end of its name: the adapter's state, when the panel has no title for it.
 */
@Composable
private fun SpeedTile(speed: () -> CarSpeed, modifier: Modifier, compact: Boolean, status: (@Composable () -> Unit)?) {
    val current = speed()
    val known = current.source != SpeedSource.None
    GaugeTile(
        label = stringResource(R.string.obd_speed),
        badge = if (current.source == SpeedSource.Gps) stringResource(R.string.speed_from_gps) else null,
        value = if (known) current.kmh.roundToInt().toString() else "--",
        unit = ObdField.SPEED.unit,
        fraction = if (known) current.kmh / SPEED_MAX_KMH else 0f,
        redFrom = null,
        danger = false,
        modifier = modifier,
        compact = compact,
        trailing = status,
    )
}

/** The dial's arc: open at the bottom, from lower left round over the top to lower right. */
private const val DIAL_START = 150f
private const val DIAL_SWEEP = 240f

/** The arc's height for its radius: from its top down to where its ends are, half the radius under the centre. */
private const val DIAL_HEIGHT_PER_RADIUS = 1.5f
private const val DIAL_TICKS = 8

/** A step smaller than this (idle rpm wandering, say) is shown at once; a bigger one sweeps there. */
private const val DIAL_SNAP = 0.01f
private const val DIAL_SWEEP_MS = 220

/**
 * A dial tile, as a car's cluster has them: an arc filled to [fraction] in the accent (from [redFrom] on the track is
 * red, and the fill turns red when [danger]), the [value] large in its middle with the [unit] under it, and [label]
 * (and a small [badge]) above it, with [trailing] at the far end of that line.
 *
 * Kept light: the arc is drawn, not composed, and the fill moves in the draw phase only, so its sweep to a new reading
 * redraws the arc and nothing else; small steps do not animate at all, so an idling engine does not keep it drawing.
 */
@Composable
private fun GaugeTile(
    label: String,
    value: String,
    unit: String,
    fraction: Float,
    redFrom: Float?,
    danger: Boolean,
    modifier: Modifier,
    badge: String? = null,
    compact: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val target = fraction.coerceIn(0f, 1f)
    val fill = remember { Animatable(target) }
    LaunchedEffect(target) {
        if (abs(target - fill.value) < DIAL_SNAP) fill.snapTo(target) else fill.animateTo(target, tween(DIAL_SWEEP_MS))
    }
    val accent = if (danger) colors.error else colors.primary
    val track = colors.onSurface.copy(alpha = 0.10f)
    val redTrack = colors.error.copy(alpha = 0.32f)
    val tick = colors.onSurfaceVariant.copy(alpha = 0.45f)

    Column(
        modifier
            .glass(shape)
            .padding(horizontal = 14.dp, vertical = if (compact) 6.dp else 10.dp),
    ) {
        // The name above the dial, where a short tile still has room for it, clear of the number.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant, maxLines = 1)
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.14f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (trailing != null) {
                Spacer(Modifier.weight(1f))
                trailing()
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            // As large as the tile lets it: two radii across, one and a half down.
            val radius = minOf(maxWidth / 2f, maxHeight / DIAL_HEIGHT_PER_RADIUS)
            val stroke = radius * 0.11f
            // The number fills the dial's middle, smaller for a longer one ("6,500") so it stays inside the arc, and
            // the unit under it in proportion, so the two never run into the arc's ends however small the dial.
            val valueSize = with(density) { minOf(radius * 0.6f, radius * 1.4f / (value.length.coerceAtLeast(2) * 0.6f)).toSp() }
            val unitSize = with(density) { (radius * 0.2f).coerceIn(10.dp, 18.dp).toSp() }
            Box(Modifier.width(radius * 2f).height(radius * DIAL_HEIGHT_PER_RADIUS), contentAlignment = Alignment.Center) {
                Spacer(
                    Modifier.fillMaxSize().drawWithCache {
                        val width = stroke.toPx()
                        val r = size.width / 2f - width / 2f
                        val topLeft = Offset(width / 2f, width / 2f)
                        val arcSize = Size(r * 2f, r * 2f)
                        val line = Stroke(width, cap = StrokeCap.Round)
                        val centre = Offset(size.width / 2f, width / 2f + r)
                        // The marks inside the arc, at even steps along it.
                        val marks = Path().apply {
                            for (i in 0..DIAL_TICKS) {
                                val a = Math.toRadians((DIAL_START + DIAL_SWEEP * i / DIAL_TICKS).toDouble())
                                val direction = Offset(cos(a).toFloat(), sin(a).toFloat())
                                val from = centre + direction * (r - width * 1.2f)
                                val to = centre + direction * (r - width * 1.75f)
                                moveTo(from.x, from.y)
                                lineTo(to.x, to.y)
                            }
                        }
                        val markLine = Stroke(width * 0.22f, cap = StrokeCap.Round)
                        onDrawBehind {
                            drawArc(track, DIAL_START, DIAL_SWEEP, useCenter = false, topLeft = topLeft, size = arcSize, style = line)
                            redFrom?.let {
                                drawArc(redTrack, DIAL_START + DIAL_SWEEP * it, DIAL_SWEEP * (1f - it), useCenter = false, topLeft = topLeft, size = arcSize, style = line)
                            }
                            drawPath(marks, tick, style = markLine)
                            val swept = DIAL_SWEEP * fill.value
                            if (swept > 0.5f) {
                                drawArc(accent, DIAL_START, swept, useCenter = false, topLeft = topLeft, size = arcSize, style = line)
                            }
                        }
                    },
                )
                // Centred on the dial's middle, not the box's: the arc is open at the bottom, so its middle sits lower.
                Column(Modifier.offset(y = radius * 0.2f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontSize = valueSize, lineHeight = valueSize, fontWeight = FontWeight.Bold, fontFeatureSettings = TabularFigures,
                        ),
                        color = if (danger) colors.error else colors.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        unit,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = unitSize, lineHeight = unitSize),
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Figures all the same width, so a value that changes does not shift the unit beside it or jitter as it counts. */
private const val TabularFigures = "tnum"

@Composable
private fun ColumnScope.TileRow(alpha: Float, gap: Dp, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.weight(1f).fillMaxWidth().alpha(alpha),
        horizontalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/**
 * Says what is wrong and what the user can do about it: nothing (it is retried), or a button. The button always has its
 * room; the words take what is left, smaller on a [compact] screen, and are cut short rather than push it out.
 */
@Composable
private fun Recovery(
    problem: ObdProblem,
    bluetooth: BluetoothPermission,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
    compact: Boolean = false,
) {
    val hint = when (problem) {
        ObdProblem.NoPermission -> R.string.obd_hint_permission
        ObdProblem.NoAdapter -> R.string.obd_hint_no_adapter
        ObdProblem.NoVehicle -> R.string.obd_hint_no_vehicle
        ObdProblem.BluetoothOff -> R.string.obd_hint_bluetooth_off
        ObdProblem.CannotConnect, ObdProblem.Lost -> R.string.obd_hint_retrying
    }
    val buttonHeight = if (compact) 56.dp else 64.dp
    val buttonText = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(hint),
            style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            // Measured after the button, so a long hint is cut short instead of pushing the button off the card.
            modifier = Modifier.weight(1f, fill = false),
        )
        when (problem) {
            ObdProblem.NoPermission ->
                Button(onClick = bluetooth.request, modifier = Modifier.heightIn(min = buttonHeight)) {
                    Text(stringResource(R.string.obd_grant), style = buttonText)
                }
            ObdProblem.NoAdapter, ObdProblem.CannotConnect ->
                Button(onClick = onOpenSettings, modifier = Modifier.heightIn(min = buttonHeight)) {
                    Text(stringResource(R.string.obd_open_settings), style = buttonText)
                }
            else -> Unit
        }
    }
}
