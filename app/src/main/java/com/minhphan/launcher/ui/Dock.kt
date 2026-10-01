package com.minhphan.launcher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.minhphan.launcher.R
import kotlin.math.cos
import kotlin.math.sin

/** The dock: a card of glass as tall as a big touch target (well over the 88 dp the car's buttons want). */
private val DockHeight = 104.dp
private val DockMargin = 12.dp
private val DockShape = RoundedCornerShape(24.dp)

private const val YOUTUBE_PACKAGE = "com.google.android.youtube"

/** The icon's chip, and the line icon on it. */
private val ChipSize = 44.dp
private val ChipShape = RoundedCornerShape(16.dp)
private val DockIcon = 24.dp

// Each button has its own colour, as a car's screen gives each of its apps one, so the eye finds it without reading:
// its icon in it, on a chip tinted with it. They read on the light card and the dark one alike.
private val MapsGreen = Color(0xFF2FBF71)
private val MusicOrange = Color(0xFFFF8A3D)
private val YouTubeRed = Color(0xFFFF0033)
private val AppsBlue = Color(0xFF5B8CFF)
private val SettingsGrey = Color(0xFF8E9BAE)

/**
 * The bar along the bottom of Home: a card of glass like the car's data above it, with the same six buttons always:
 * Home (the page on show, on a chip filled in the accent with a bar under it; pressing it closes any page over Home),
 * the maps app, the music app, YouTube, all apps and settings, each an icon on a chip of its own colour over its name.
 * Maps and music open the apps the system has for them, so they follow whatever the car has installed; a button with
 * no app behind it says so.
 *
 * Nothing in it moves but the pressed button, so it costs nothing while the car's data and the scene redraw.
 */
@Composable
fun HomeDock(
    onHome: () -> Unit,
    onOpenAllApps: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            // Clear of the navigation bar, if the head unit shows one, and of the screen's edge.
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .padding(start = DockMargin, end = DockMargin, bottom = DockMargin)
            .height(DockHeight)
            .clip(DockShape)
            // Glass, like the car's data above it, over the scene that runs on under both.
            .background(colors.surface.copy(alpha = 0.82f))
            .border(1.dp, colors.outlineVariant, DockShape)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DockButton(stringResource(R.string.dock_home), colors.primary, selected = true, onClick = onHome) { HomeIcon(it) }
        DockButton(stringResource(R.string.dock_maps), MapsGreen, onClick = { openMaps(context) }) { PinIcon(it) }
        DockButton(stringResource(R.string.dock_music), MusicOrange, onClick = { openMusic(context) }) { MusicIcon(it) }
        DockButton(stringResource(R.string.dock_youtube), YouTubeRed, onClick = { openYouTube(context) }) { YouTubeIcon() }
        DockButton(stringResource(R.string.dock_apps), AppsBlue, onClick = onOpenAllApps) { GridIcon(it) }
        DockButton(stringResource(R.string.dock_settings), SettingsGrey, onClick = onOpenSettings) { GearIcon(it) }
    }
}

/**
 * One dock button: its [icon], drawn in the colour it is given, on a chip of [tint] over its label. The page on show
 * has the chip filled in [tint] and a short bar of it under the label.
 */
@Composable
private fun RowScope.DockButton(
    label: String,
    tint: Color,
    onClick: () -> Unit,
    selected: Boolean = false,
    icon: @Composable (Color) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val chipAlpha = if (LocalDarkTheme.current) 0.20f else 0.13f
    TileLayout(
        label = label,
        iconSize = ChipSize,
        onClick = onClick,
        labelStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
        labelColor = if (selected) tint else colors.onSurfaceVariant,
        decoration = if (selected) Modifier.drawBehind { selectedBar(tint) } else Modifier,
        modifier = Modifier.weight(1f).fillMaxHeight(),
    ) {
        Box(
            Modifier
                .size(ChipSize)
                .clip(ChipShape)
                .background(if (selected) tint else tint.copy(alpha = chipAlpha)),
            contentAlignment = Alignment.Center,
        ) {
            icon(if (selected) colors.onPrimary else tint)
        }
    }
}

/** The short rounded bar under the page on show's label, at the foot of its button. */
private fun DrawScope.selectedBar(color: Color) {
    val width = 20.dp.toPx()
    val height = 3.dp.toPx()
    drawRoundRect(
        color,
        topLeft = Offset((size.width - width) / 2f, size.height - height - 2.dp.toPx()),
        size = Size(width, height),
        cornerRadius = CornerRadius(height / 2f),
    )
}

internal fun Intent.newTask() = addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun openMaps(context: Context) = openOrSay(
    context,
    Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS).newTask(),
    Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")).newTask(),
)

@Suppress("DEPRECATION") // INTENT_ACTION_MUSIC_PLAYER is the fallback for music apps that predate APP_MUSIC.
private fun openMusic(context: Context) = openOrSay(
    context,
    Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC).newTask(),
    Intent(MediaStore.INTENT_ACTION_MUSIC_PLAYER).newTask(),
)

/** The YouTube app, or youtube.com in the browser where the app is not installed. */
private fun openYouTube(context: Context) = openOrSay(
    context,
    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(YOUTUBE_PACKAGE).newTask(),
    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")).newTask(),
)

internal fun openOrSay(context: Context, vararg intents: Intent) {
    if (!startFirstAvailable(context, *intents)) {
        Toast.makeText(context, R.string.dock_no_app, Toast.LENGTH_SHORT).show()
    }
}

// The icons are drawn, 24 units square scaled to the canvas, so they need no icon library and stay crisp.

private fun DrawScope.unit() = size.minDimension / 24f

/** A house with a door, filled in [color]. */
@Composable
private fun HomeIcon(color: Color) {
    Canvas(Modifier.size(DockIcon)) {
        val u = unit()
        val house = Path().apply {
            moveTo(12f * u, 2.5f * u)
            lineTo(22.5f * u, 11.5f * u)
            lineTo(19.5f * u, 11.5f * u)
            lineTo(19.5f * u, 21f * u)
            lineTo(14.5f * u, 21f * u)
            lineTo(14.5f * u, 15f * u)
            lineTo(9.5f * u, 15f * u)
            lineTo(9.5f * u, 21f * u)
            lineTo(4.5f * u, 21f * u)
            lineTo(4.5f * u, 11.5f * u)
            lineTo(1.5f * u, 11.5f * u)
            close()
        }
        drawPath(house, color)
    }
}

/** A map pin: a round head narrowing to a point, with a hole; in lines of [color]. Shared with the header. */
@Composable
internal fun PinIcon(color: Color, modifier: Modifier = Modifier.size(DockIcon)) {
    Canvas(modifier) {
        val u = unit()
        val pin = Path().apply {
            moveTo(12f * u, 22f * u)
            cubicTo(7f * u, 16f * u, 4.5f * u, 12.5f * u, 4.5f * u, 9.5f * u)
            cubicTo(4.5f * u, 5.3f * u, 7.9f * u, 2f * u, 12f * u, 2f * u)
            cubicTo(16.1f * u, 2f * u, 19.5f * u, 5.3f * u, 19.5f * u, 9.5f * u)
            cubicTo(19.5f * u, 12.5f * u, 17f * u, 16f * u, 12f * u, 22f * u)
            close()
        }
        drawPath(pin, color, style = Stroke(1.8f * u, join = StrokeJoin.Round))
        drawCircle(color, radius = 2.6f * u, center = Offset(12f * u, 9.5f * u), style = Stroke(1.8f * u))
    }
}

/** Two beamed quavers, in lines of [color]. */
@Composable
private fun MusicIcon(color: Color) {
    Canvas(Modifier.size(DockIcon)) {
        val u = unit()
        val stroke = Stroke(1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val beam = Path().apply {
            moveTo(9f * u, 18f * u)
            lineTo(9f * u, 5f * u)
            lineTo(20f * u, 3f * u)
            lineTo(20f * u, 16f * u)
        }
        drawPath(beam, color, style = stroke)
        drawCircle(color, radius = 3f * u, center = Offset(6f * u, 18f * u), style = Stroke(1.8f * u))
        drawCircle(color, radius = 3f * u, center = Offset(17f * u, 16f * u), style = Stroke(1.8f * u))
    }
}

/** The red rounded screen with the white play triangle; in its own colours, like the app's icon, on its chip. */
@Composable
private fun YouTubeIcon() {
    Canvas(Modifier.size(DockIcon)) {
        val u = unit()
        drawRoundRect(YouTubeRed, topLeft = Offset(1f * u, 5f * u), size = Size(22f * u, 15f * u), cornerRadius = CornerRadius(4.5f * u))
        val play = Path().apply {
            moveTo(10f * u, 9f * u)
            lineTo(16f * u, 12.5f * u)
            lineTo(10f * u, 16f * u)
            close()
        }
        drawPath(play, Color.White)
    }
}

/** Four rounded squares in lines of [color]: "all apps". */
@Composable
private fun GridIcon(color: Color) {
    Canvas(Modifier.size(DockIcon)) {
        val u = unit()
        for (row in 0..1) for (col in 0..1) {
            drawRoundRect(
                color,
                topLeft = Offset((3f + col * 10f) * u, (3f + row * 10f) * u),
                size = Size(8f * u, 8f * u),
                cornerRadius = CornerRadius(2f * u),
                style = Stroke(1.8f * u),
            )
        }
    }
}

/** A cog with eight teeth round a hole, in lines of [color]. Shared with the header. */
@Composable
internal fun GearIcon(color: Color, modifier: Modifier = Modifier.size(DockIcon)) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val inner = r * 0.72f
        val outer = r * 0.94f
        fun point(radius: Float, degrees: Float): Offset {
            val a = Math.toRadians(degrees.toDouble())
            return Offset(center.x + radius * cos(a).toFloat(), center.y + radius * sin(a).toFloat())
        }
        val gear = Path().apply {
            for (i in 0 until 8) {
                val a = i * 45f
                val base = point(inner, a - 15f)
                if (i == 0) moveTo(base.x, base.y) else lineTo(base.x, base.y)
                for ((radius, degrees) in listOf(outer to a - 9f, outer to a + 9f, inner to a + 15f, inner to a + 22.5f)) {
                    val p = point(radius, degrees)
                    lineTo(p.x, p.y)
                }
            }
            close()
        }
        drawPath(gear, color, style = Stroke(r * 0.12f, join = StrokeJoin.Round))
        drawCircle(color, radius = r * 0.3f, style = Stroke(r * 0.12f))
    }
}
