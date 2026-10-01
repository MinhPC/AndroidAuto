package com.minhphan.launcher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.minhphan.launcher.R
import com.minhphan.launcher.data.AppInfo
import com.minhphan.launcher.data.DockEntry
import com.minhphan.launcher.data.DockShortcut
import com.minhphan.launcher.data.MAX_DOCK_APPS
import kotlin.math.cos
import kotlin.math.sin

/** A floating dock with consistent chips and space for large touch targets. */
private val DockHeight = 92.dp
private val DockMargin = 12.dp
private val DockShape = RoundedCornerShape(20.dp)

// On a short screen (a 1024 x 600 or 1280 x 720 head unit at 240 dpi is only 400 to 480 dp tall) the dock gives up
// height to the car's data above it: still well over the 64 dp a finger needs.
private val CompactDockHeight = 80.dp
private val CompactDockMargin = 8.dp
private val CompactChipSize = 40.dp
private val CompactChipShape = RoundedCornerShape(14.dp)

/** A button narrower than this has no room for its name under its icon, which is then only read out. */
private val LabelMinWidth = 60.dp

/** Under this a button's name ("Ứng dụng") does not fit in the large label style, and takes the next smaller one. */
private val LargeLabelMinWidth = 84.dp

private const val YOUTUBE_PACKAGE = "com.google.android.youtube"

/** The icon's chip, and the line icon on it. */
private val ChipSize = 48.dp
private val ChipShape = RoundedCornerShape(16.dp)
private val DockIcon = 26.dp
/** The most buttons the dock has: the driver's apps, then all apps and settings. */
private const val DOCK_SLOTS = MAX_DOCK_APPS + 2

// Each button has its own colour, as a car's screen gives each of its apps one, so the eye finds it without reading:
// its icon in it, on a chip tinted with it. They read on the light card and the dark one alike.
private val MapsGreen = Color(0xFF2FBF71)
private val MusicOrange = Color(0xFFFF8A3D)
private val YouTubeRed = Color(0xFFFF0033)
private val AppsBlue = Color(0xFF5B8CFF)
private val SettingsGrey = Color(0xFF8E9BAE)

/**
 * The bar along the bottom of Home: an opaque card like the car's data above it. First the buttons the driver chose
 * in Settings ([entries], at most [MAX_DOCK_APPS]): a shortcut (maps, music, YouTube) opens whatever app the head unit
 * has for the job, so it follows what is installed, and says so where there is none; an app opens that app
 * ([onLaunch]). Then, separated by a short divider, all apps and settings. Each is a chip over its name: a shortcut's line icon in its own
 * colour on a chip tinted with it, an app's own icon. Home itself is the head unit's Home button, which also closes
 * any page over it.
 *
 * Every button is as wide as when the dock is full, so a dock with fewer is spaced out and its buttons do not grow.
 * Only the pressed icon scales; the label remains sharp and stationary.
 * [compact] makes it lower, for a short screen; a button too narrow for its name shows only its icon.
 */
@Composable
fun HomeDock(
    entries: List<DockEntry>,
    onLaunch: (AppInfo) -> Unit,
    onOpenAllApps: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val margin = if (compact) CompactDockMargin else DockMargin
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            // Clear of the navigation bar, if the head unit shows one, and of the screen's edge.
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .padding(start = margin, end = margin, top = 4.dp, bottom = margin)
            .height(if (compact) CompactDockHeight else DockHeight)
            .clip(DockShape)
            // An opaque surface keeps the labels readable without a backdrop blur.
            .background(colors.surface)
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.5f), DockShape)
            .padding(horizontal = 6.dp, vertical = if (compact) 4.dp else 6.dp),
    ) {
        val groupGap = if (compact) 4.dp else 8.dp
        val buttonWidth = (maxWidth - groupGap * 2 - 1.dp) / DOCK_SLOTS
        val look = DockLook(
            compact = compact,
            buttonWidth = buttonWidth,
            showLabels = buttonWidth >= LabelMinWidth,
            largeLabels = buttonWidth >= LargeLabelMinWidth,
        )
        Row(
            Modifier.fillMaxWidth().fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(groupGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                entries.forEach { entry -> key(entry.id) { DockEntryButton(entry, look, onLaunch) } }
            }
            Box(Modifier.width(1.dp).height(32.dp).background(if (entries.isEmpty()) Color.Transparent else colors.outlineVariant))
            DockButton(stringResource(R.string.dock_apps), look, onClick = onOpenAllApps) { TintedChip(AppsBlue, look) { GridIcon(it) } }
            DockButton(stringResource(R.string.dock_settings), look, onClick = onOpenSettings) { TintedChip(SettingsGrey, look) { GearIcon(it) } }
        }
    }
}

/** How the dock's buttons are drawn: smaller on a short screen, and with or without their names. */
private data class DockLook(val compact: Boolean, val buttonWidth: Dp, val showLabels: Boolean, val largeLabels: Boolean) {
    val chipSize: Dp get() = if (compact) CompactChipSize else ChipSize
    val chipShape get() = if (compact) CompactChipShape else ChipShape
}

/** A button the driver put on the dock: a shortcut, or an app. */
@Composable
private fun DockEntryButton(entry: DockEntry, look: DockLook, onLaunch: (AppInfo) -> Unit) {
    val context = LocalContext.current
    DockButton(dockEntryLabel(entry), look, onClick = {
        when (entry) {
            is DockEntry.Shortcut -> openShortcut(context, entry.shortcut)
            is DockEntry.App -> onLaunch(entry.app)
        }
    }) {
        DockEntryIcon(entry, look.chipSize, look.chipShape)
    }
}

/** One dock button: its [chip] over its label. */
@Composable
private fun DockButton(label: String, look: DockLook, onClick: () -> Unit, chip: @Composable () -> Unit) {
    val labelStyle = when {
        look.compact -> MaterialTheme.typography.labelSmall
        look.largeLabels -> MaterialTheme.typography.labelLarge
        else -> MaterialTheme.typography.labelMedium
    }
    TileLayout(
        label = label,
        iconSize = look.chipSize,
        onClick = onClick,
        labelStyle = labelStyle.copy(fontWeight = FontWeight.Normal),
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        showLabel = look.showLabels,
        verticalPadding = 4.dp,
        horizontalPadding = if (look.compact) 2.dp else 6.dp,
        labelGap = if (look.compact) 4.dp else 6.dp,
        modifier = Modifier.width(look.buttonWidth).fillMaxHeight(),
        iconOnlyPress = true,
    ) {
        chip()
    }
}

/** A line icon in [tint] on a chip tinted with it. */
@Composable
private fun TintedChip(tint: Color, look: DockLook, icon: @Composable (Color) -> Unit) =
    TintedChip(tint, look.chipSize, look.chipShape, icon)

@Composable
private fun TintedChip(tint: Color, size: Dp, shape: Shape, icon: @Composable (Color) -> Unit) {
    val chipAlpha = if (LocalDarkTheme.current) 0.20f else 0.13f
    Box(Modifier.size(size).clip(shape).background(tint.copy(alpha = chipAlpha)), contentAlignment = Alignment.Center) {
        icon(tint)
    }
}

/** A dock button's picture, as on the dock: a shortcut's chip, or the app's own icon. Shared with Settings. */
@Composable
internal fun DockEntryIcon(entry: DockEntry, size: Dp = ChipSize, shape: Shape = ChipShape) {
    when (entry) {
        is DockEntry.Shortcut -> TintedChip(entry.shortcut.tint, size, shape) { ShortcutIcon(entry.shortcut, it) }
        is DockEntry.App -> Box(
            Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Image(bitmap = entry.app.icon, contentDescription = null,
                modifier = Modifier.size(size - 10.dp).clip(RoundedCornerShape(8.dp)))
        }
    }
}

/** A dock button's name: a shortcut's, or the app's own. */
@Composable
internal fun dockEntryLabel(entry: DockEntry): String = when (entry) {
    is DockEntry.Shortcut -> stringResource(entry.shortcut.label)
    is DockEntry.App -> entry.app.label
}

internal val DockShortcut.label: Int
    get() = when (this) {
        DockShortcut.Maps -> R.string.dock_maps
        DockShortcut.Music -> R.string.dock_music
        DockShortcut.YouTube -> R.string.dock_youtube
    }

private val DockShortcut.tint: Color
    get() = when (this) {
        DockShortcut.Maps -> MapsGreen
        DockShortcut.Music -> MusicOrange
        DockShortcut.YouTube -> YouTubeRed
    }

@Composable
private fun ShortcutIcon(shortcut: DockShortcut, color: Color) = when (shortcut) {
    DockShortcut.Maps -> PinIcon(color)
    DockShortcut.Music -> MusicIcon(color)
    DockShortcut.YouTube -> YouTubeIcon()
}

private fun openShortcut(context: Context, shortcut: DockShortcut) = when (shortcut) {
    DockShortcut.Maps -> openMaps(context)
    DockShortcut.Music -> openMusic(context)
    DockShortcut.YouTube -> openYouTube(context)
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
