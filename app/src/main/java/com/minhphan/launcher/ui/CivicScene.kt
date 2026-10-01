package com.minhphan.launcher.ui

import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.IntSize
import com.minhphan.launcher.R
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// The pictures: the road by day (road_day.webp) or by night (road_night.webp), and the car on a see-through canvas of
// its own in four states, framed alike so they can take turns without the car moving: by day with its lights off
// (car_day.webp) and on the brakes (car_brake_day.webp), by night with its tail lights on (car_night.webp) and on the
// brakes (car_brake_night.webp). At night the headlights' glow on the road ahead (car_headlights.webp) is drawn behind
// whichever it is, so the headlights stay on when the car brakes.
private const val IMAGE_WIDTH = 1423f
internal const val IMAGE_HEIGHT = 1105f

// The solid car in the car pictures (alpha above half, mirrors and all), in their pixels: its body leans about the
// middle of its tyres' bottom. The pictures (644 by 556) are still larger than the car is ever drawn, even at 1080p.
private const val CAR_LEFT = 2.25f
private const val CAR_RIGHT = 642.75f
private const val CAR_BOTTOM = 555f

// Where the top left of the headlights' glow sits, in the car pictures' pixels: above and either side of the car.
private val HEADLIGHTS_AT = Offset(-34.35f, -139.5f)

// Where the car stands on the road, in the road's pixels: this wide, in the middle of the road (ROAD_MIDDLE), its tyres
// this far down, so its roof sits below the horizon (y 430) and the road ahead shows over it.
private const val CAR_WIDTH_ON_ROAD = 457f
private const val CAR_BOTTOM_ON_ROAD = 911f

// The car's lane is this much wider than the car either side (3.5 m for a car about 1.8 m wide, mirrors and all).
private const val LANE_PER_CAR = 0.972f

/**
 * A road picture, the colour of its top row, which fills the scene above it where the scene is taller than the
 * picture, and the colour of the lane's paint on it ([LaneMarkings]). [shore] is the far shore with the Dragon Bridge
 * painted out, to lay over the picture at [SHORE_AT] where it is mirrored, so the bridge is not seen twice.
 */
private class Road(@DrawableRes val res: Int, @DrawableRes val shore: Int, val skyTop: Color, val lane: Color)

private val DayRoad = Road(R.drawable.road_day, R.drawable.road_day_shore, Color(0xFF6CAEF2), Color(0xFFF2F2F2).copy(alpha = 0.9f))
private val NightRoad =
    Road(R.drawable.road_night, R.drawable.road_night_shore, Color(0xFF021538), Color(0xFFD5DAE3).copy(alpha = 0.6f))

// Where the top left of the shore without the bridge (road_day_shore.webp, road_night_shore.webp) sits on the road
// picture, in its pixels. Its edges are see-through, so it blends in wherever it lands.
private val SHORE_AT = Offset(900f, 340f)

/**
 * How the pictures are placed in a frame of the given size whose first [focusWidth] is where the car is: the road covers
 * the focus, as small as does, against the bottom and with the middle of the road in the middle of the focus as far as
 * the picture allows; what does not fit is cut off. The car drives in the middle of the road. Past the picture's right
 * edge the rest of the frame is the picture again, mirrored ([mirrorAt]), so the road runs on under what is laid there.
 */
private class SceneFrame(val width: Float, val height: Float, focusWidth: Float) {
    val scale = max(focusWidth / IMAGE_WIDTH, height / IMAGE_HEIGHT)
    val drawnWidth = IMAGE_WIDTH * scale
    val drawnHeight = IMAGE_HEIGHT * scale
    // On whole pixels, so the road is copied as it is and not resampled between them.
    val left = floor((focusWidth / 2f - ROAD_MIDDLE * scale).coerceIn(focusWidth - drawnWidth, 0f))
    val top = floor(height - drawnHeight)

    /** The picture's right edge, where its mirror image starts; the mirror is only needed if the frame goes past it. */
    val mirrorAt = left + ceil(drawnWidth)
    val mirrored = mirrorAt < width

    /** Where the part of the scene the car is in ends; what is past it lies under the car's data. */
    val focusRight = focusWidth

    /** The horizon, where the road's blur starts. */
    val horizon = top + ROAD_HORIZON * scale

    val carWidth = CAR_WIDTH_ON_ROAD * scale
    val carScale = carWidth / (CAR_RIGHT - CAR_LEFT)
    val pivot = Offset(left + ROAD_MIDDLE * scale, top + CAR_BOTTOM_ON_ROAD * scale)
    /** Where the top left of the car's canvas is drawn. */
    val carAt = Offset(pivot.x - (CAR_LEFT + CAR_RIGHT) / 2f * carScale, pivot.y - CAR_BOTTOM * carScale)

    // The car's lane, where it crosses the bottom of the road picture, in its pixels: widening from the car down to it.
    val laneMiddle = ROAD_MIDDLE
    val laneHalfWidth = LANE_PER_CAR * CAR_WIDTH_ON_ROAD * (IMAGE_HEIGHT - ROAD_HORIZON) / (CAR_BOTTOM_ON_ROAD - ROAD_HORIZON)
}

/**
 * The black Civic seen from behind on the riverside road, by day in the light theme and by night in the dark one, its
 * tail lights on at night and its brake lights on the brakes. The road fills the scene; the car is in the middle of its
 * first [focusFraction], so the rest can be laid over.
 *
 * While the car is [moving] the body rides the road on its springs ([Suspension]), at a speed that eases towards
 * [targetSpeedKmh] on a spring, closely behind the speedometer ([quickSpeed]) and more loosely behind the GPS, and
 * the dashed lines of its lane come on at that speed ([LaneMarkings]).
 *
 * Kept light for a head unit, whose GPU is what limits it: everything that does not move (the road, its mirror image
 * and the seam between them) is drawn once into one opaque picture the size of the scene ([Baked]), so a frame copies
 * that, lays the road's blur over the road alone, and draws the dashes, the shadow and the car. The animation runs only
 * while the car moves and the scene is not [covered]; nothing is drawn at all while the car stands.
 */
@Composable
fun CivicScene(
    targetSpeedKmh: () -> Float,
    quickSpeed: Boolean,
    moving: Boolean,
    covered: Boolean,
    modifier: Modifier = Modifier,
    focusFraction: Float = 1f,
    energySaving: Boolean = false,
) {
    val speed = remember { SpeedFollower() }
    // The body on its springs; see Suspension.
    val suspension = remember { Suspension() }
    // What changes from frame to frame, as state read only in the draw, so a frame redraws and does not recompose.
    var bob by remember { mutableFloatStateOf(0f) }
    var roll by remember { mutableFloatStateOf(0f) }
    var lanePhase by remember { mutableFloatStateOf(0f) }
    var laneSpeedKmh by remember { mutableFloatStateOf(0f) }
    var laneFrameSeconds by remember { mutableFloatStateOf(1f / 60f) }
    var braking by remember { mutableStateOf(false) }
    val target by rememberUpdatedState(targetSpeedKmh)
    val quick by rememberUpdatedState(quickSpeed)

    // The dark theme is the night, or the headlights on: the tail lights are on with them.
    val night = LocalDarkTheme.current
    val view = LocalView.current
    var refreshHz by remember(view) { mutableFloatStateOf(view.display?.refreshRate ?: 60f) }
    DisposableEffect(view) {
        val manager = view.context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == view.display?.displayId) refreshHz = view.display?.refreshRate ?: 60f
            }
        }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager.unregisterDisplayListener(listener) }
    }
    val resources = LocalContext.current.resources
    var sceneSize by remember { mutableStateOf(IntSize.Zero) }
    // Only final render assets survive preparation; decoded originals and intermediate roads
    // become collectible. Keep the previous scene visible until its replacement is ready.
    val pictures by produceState<ScenePictures?>(null, resources, night, sceneSize, focusFraction, energySaving) {
        if (sceneSize.width > 0 && sceneSize.height > 0) {
            value = withContext(Dispatchers.Default) {
                prepareScene(resources, night, sceneSize, focusFraction, energySaving)
            }
        }
    }

    LaunchedEffect(moving, covered, energySaving, refreshHz) {
        // Out of sight nothing needs drawing: the scene keeps its last frame and picks up from it when shown again.
        if (covered) return@LaunchedEffect
        var last = withFrameNanos { it }
        val cadence = FrameCadence(refreshHz, if (energySaving) 30 else 60)
        while (moving || speed.kmh > REST_KMH) {
            val now = withFrameNanos { it }
            if (!cadence.shouldDraw()) continue
            val seconds = ((now - last) / 1_000_000_000f).coerceAtMost(MAX_FRAME_SECONDS)
            last = now
            speed.follow(if (moving) target() else 0f, seconds, if (quick) OBD_OMEGA else GPS_OMEGA)
            suspension.step(speed.kmh, seconds)
            bob = suspension.bob
            roll = suspension.roll
            lanePhase = (suspension.distance % DASH_PERIOD_M).toFloat()
            laneSpeedKmh = speed.kmh
            laneFrameSeconds = seconds
            braking = suspension.braking
        }
        // Come to a stop: the body at rest, and no more frames.
        speed.stop()
        suspension.reset()
        bob = 0f
        roll = 0f
        laneSpeedKmh = 0f
        braking = false
    }

    Spacer(
        modifier
            // Its own layer, so the gauges and clock beside it redrawing do not make it record its drawing again.
            .onSizeChanged { sceneSize = it }
            .graphicsLayer()
            .drawWithCache {
                val p = pictures
                val frame = p?.frame
                val lanes = frame?.let { LaneMarkings(it.scale, p.roadKind.lane, it.laneMiddle, it.laneHalfWidth) }
                val shadowCentre = frame?.pivot ?: Offset.Zero
                val shade = if (night) 0.7f else 1f
                val softShadow = Brush.radialGradient(
                    0f to Color.Black.copy(alpha = 0.65f * shade), 0.6f to Color.Black.copy(alpha = 0.4f * shade), 1f to Color.Transparent,
                    center = shadowCentre, radius = (frame?.carWidth ?: 1f) * 0.62f,
                )
                val contactShadow = Brush.radialGradient(
                    0f to Color.Black.copy(alpha = 0.9f * shade), 0.75f to Color.Black.copy(alpha = 0.65f * shade), 1f to Color.Transparent,
                    center = shadowCentre, radius = (frame?.carWidth ?: 1f) * 0.5f,
                )

                onDrawBehind {
                    if (p == null || frame == null || lanes == null) {
                        drawRect(if (night) NightRoad.skyTop else DayRoad.skyTop)
                        return@onDrawBehind
                    }
                    drawImage(p.still)
                    // The road rushing by, blurred out from the horizon, more of it the faster the car goes.
                    val blur = (laneSpeedKmh / FULL_BLUR_KMH).coerceIn(0f, 1f) * MOST_BLUR
                    // Fainter than the eye can see it is not worth blending the road over again.
                    if (!energySaving && blur > LEAST_VISIBLE_BLUR) p.roadBlurred?.let { drawImage(it, p.blurAt, alpha = blur) }
                    translate(frame.left, frame.top) {
                        // The lines run on past the sides of the picture; keep their dashes on it.
                        clipRect(right = frame.drawnWidth, bottom = frame.drawnHeight) {
                            with(lanes) { drawDashes(lanePhase, laneSpeedKmh / 3.6f, laneFrameSeconds) }
                        }
                    }
                    // The shadow stays on the road while the body moves on its springs above it.
                    scale(scaleX = 1f, scaleY = 0.22f, pivot = shadowCentre) {
                        drawCircle(softShadow, radius = frame.carWidth * 0.62f, center = shadowCentre)
                    }
                    scale(scaleX = 1f, scaleY = 0.08f, pivot = shadowCentre) {
                        drawCircle(contactShadow, radius = frame.carWidth * 0.5f, center = shadowCentre)
                    }
                    // The body rides on its springs, rising, settling and leaning about the middle of the rear axle.
                    translate(top = bob * frame.carWidth) {
                        rotate(roll, pivot = frame.pivot) {
                            p.headlights?.let { drawImage(it, p.headlightsAt) }
                            // Better filtering only for the small rotating car, not the full background.
                            val car = if (braking) p.braking else p.car
                            drawImage(car, dstOffset = androidx.compose.ui.unit.IntOffset(frame.carAt.x.toInt(), frame.carAt.y.toInt()),
                                dstSize = IntSize(car.width, car.height), filterQuality = FilterQuality.Medium)
                        }
                    }
                }
            },
    )
}

private class ScenePictures(
    val frame: SceneFrame,
    val roadKind: Road,
    val still: ImageBitmap,
    val roadBlurred: ImageBitmap?,
    val blurAt: Offset,
    val car: ImageBitmap,
    val braking: ImageBitmap,
    val headlights: ImageBitmap?,
    val headlightsAt: Offset,
)

/** CPU bitmap preparation runs away from the UI thread; only the finished assets are retained. */
private fun prepareScene(resources: Resources, night: Boolean, dimensions: IntSize, focusFraction: Float, energySaving: Boolean): ScenePictures {
    val roadKind = if (night) NightRoad else DayRoad
    fun load(@DrawableRes id: Int) = ImageBitmap.imageResource(resources, id)
    val road = load(roadKind.res)
    val shore = load(roadKind.shore)
    val car = load(if (night) R.drawable.car_night else R.drawable.car_day)
    val carBraking = load(if (night) R.drawable.car_brake_night else R.drawable.car_brake_day)
    val headlights = if (night) load(R.drawable.car_headlights) else null
    val scaledRoad = Scaled()
    val blurredRoad = ZoomBlurred()
    val seamPatch = SeamPatch()
    val scaledShore = Scaled()
    val mirrorRoad = Retouched()
    val backdrop = Baked()
    val scaledCar = Scaled()
    val scaledCarBraking = Scaled()
    val scaledHeadlights = Scaled()
    val frame = SceneFrame(dimensions.width.toFloat(), dimensions.height.toFloat(), dimensions.width * focusFraction)
    fun scaled(cache: Scaled, picture: ImageBitmap, scale: Float) = cache.get(
        picture,
        ceil(picture.width * scale).toInt().coerceAtLeast(1),
        ceil(picture.height * scale).toInt().coerceAtLeast(1),
    )
    val roadPicture = scaled(scaledRoad, road, frame.scale)
    // What is mirrored is the road without the bridge, so it is not seen a second time beside the first.
    val mirrorPicture = if (frame.mirrored) {
        mirrorRoad.get(roadPicture, scaled(scaledShore, shore, frame.scale), SHORE_AT * frame.scale)
    } else {
        null
    }
    val seam = mirrorPicture?.let { seamPatch.get(it) }
    val sky = roadKind.skyTop
    val at = Offset(frame.left, frame.top)
    // All that stands still, drawn once: the road, and past its right edge its mirror image without the bridge, the seam patched over.
    val still = backdrop.get(listOf(roadPicture, mirrorPicture, seam, frame.left, frame.top), dimensions.width, dimensions.height) {
        if (frame.top > 0f) drawRect(0f, 0f, dimensions.width.toFloat(), frame.top + 1f, Paint().apply { color = sky })
        drawImage(roadPicture, at, Paint())
        if (mirrorPicture != null) {
            save()
            translate(frame.mirrorAt, 0f)
            scale(-1f, 1f)
            translate(-frame.mirrorAt, 0f)
            drawImage(mirrorPicture, at, Paint())
            restore()
            seam?.let { drawImage(it, Offset(frame.mirrorAt - it.width / 2, frame.top), Paint()) }
        }
        // Bake the sky shade across the full width: no vertical seam at the panel edge
        // and no extra gradient blending on each animation frame.
        val shadeBottom = dimensions.height * 0.38f
        drawRect(0f, 0f, dimensions.width.toFloat(), shadeBottom, Paint().apply {
            shader = LinearGradientShader(
                from = Offset.Zero,
                to = Offset(0f, shadeBottom),
                colors = listOf(Color.Black.copy(alpha = if (night) 0.12f else 0.22f), Color.Transparent),
            )
        })
    }
    // The blur, of the road alone: from the horizon down, and only across the car's part of the scene, since
    // past it the road is under the car's data.
    val blurFrom = (-frame.left).toInt()
    val roadBlurred = if (energySaving) null else blurredRoad.get(
        roadPicture,
        centre = Offset(ROAD_MIDDLE, ROAD_HORIZON) * frame.scale,
        fromX = blurFrom,
        toX = (min(frame.mirrorAt, frame.focusRight) - frame.left).toInt(),
    )
    val blurAt = Offset(frame.left + blurFrom, frame.top + (ROAD_HORIZON * frame.scale).toInt())
    val carPicture = scaled(scaledCar, car, frame.carScale)
    val brakingPicture = scaled(scaledCarBraking, carBraking, frame.carScale)
    val headlightsPicture = headlights?.let { scaled(scaledHeadlights, it, frame.carScale) }
    val headlightsAt = frame.carAt + HEADLIGHTS_AT * frame.carScale

    return ScenePictures(frame, roadKind, still, roadBlurred, blurAt, carPicture, brakingPicture, headlightsPicture, headlightsAt)
}

// The road's blur at speed: none standing still, growing to MOST_BLUR of the blurred road over the sharp one at
// FULL_BLUR_KMH and above.
private const val FULL_BLUR_KMH = 100f
private const val MOST_BLUR = 0.4f
private const val LEAST_VISIBLE_BLUR = 0.03f

// Stop drawing at rest; the optional resource-saving mode uses a lower cadence without road blur.
private const val MAX_FRAME_SECONDS = 0.1f
private const val REST_KMH = 0.2f

// How closely the body's speed follows the car's: a critically damped spring with a time constant of about 0.2 s behind
// the speedometer, which reads several times a second, and 0.4 s behind the GPS, which reads once a second.
private const val OBD_OMEGA = 5f
private const val GPS_OMEGA = 2.5f

/**
 * The speed the body rides the road at, following the car's on a critically damped spring: it carries its pace from
 * one reading to the next, so the ride neither lurches at a new reading nor stutters in time with them.
 */
private class SpeedFollower {
    var kmh = 0f
        private set
    private var rate = 0f

    fun follow(target: Float, seconds: Float, omega: Float) {
        var remaining = seconds
        while (remaining > 0f) {
            val h = min(remaining, 1f / 120f)
            rate += (omega * omega * (target - kmh) - 2f * omega * rate) * h
            kmh += rate * h
            remaining -= h
        }
        if (kmh < 0f) {
            kmh = 0f
            rate = 0f
        }
    }

    fun stop() {
        kmh = 0f
        rate = 0f
    }
}

/**
 * The road blurred as it looks rushing by: copies of it zoomed a little further each out from the horizon's middle
 * ([centre]), averaged, so the near road smears along its own lines and the far road hardly at all. Only the road below
 * the horizon is kept, fading in beneath it, so the sky and the city stay sharp, and only from [fromX] to [toX] of the
 * picture, the part that shows: the picture is cut to that, so a frame blends no more than the road it covers. Made once
 * for each road picture; a frame only lays it over the sharp one, its top left at the horizon and [fromX].
 */
private class ZoomBlurred {
    private var picture: ImageBitmap? = null
    private var key: Any? = null

    fun get(from: ImageBitmap, centre: Offset, fromX: Int, toX: Int): ImageBitmap {
        val top = centre.y.toInt()
        val wanted = listOf(from, top, fromX, toX)
        picture?.let { if (key == wanted) return it }
        val bitmap = ImageBitmap((toX - fromX).coerceAtLeast(1), (from.height - top).coerceAtLeast(1))
        val canvas = Canvas(bitmap)
        canvas.translate(-fromX.toFloat(), -top.toFloat())
        val paint = Paint().apply { filterQuality = FilterQuality.Low }
        // Each copy drawn over the ones before at 1 / (i + 1) leaves them all weighing the same.
        for (i in 0 until BLUR_COPIES) {
            val zoom = 1f + ZOOM * i / (BLUR_COPIES - 1)
            paint.alpha = 1f / (i + 1)
            canvas.save()
            canvas.translate(centre.x, centre.y)
            canvas.scale(zoom, zoom)
            canvas.translate(-centre.x, -centre.y)
            canvas.drawImage(from, Offset.Zero, paint)
            canvas.restore()
        }
        val fade = Paint().apply {
            blendMode = BlendMode.DstIn
            shader = LinearGradientShader(
                from = Offset(0f, centre.y),
                to = Offset(0f, centre.y + (from.height - centre.y) * 0.4f),
                colors = listOf(Color.Transparent, Color.Black),
            )
        }
        canvas.drawRect(fromX.toFloat(), top.toFloat(), toX.toFloat(), from.height.toFloat(), fade)
        // And out towards its right edge, where the car's part of the scene ends: cut off straight there, the blurred
        // road would meet the sharp one in a line, which shows between the cards laid over it.
        val edge = (toX - fromX) * EDGE_FADE
        val sideFade = Paint().apply {
            blendMode = BlendMode.DstIn
            shader = LinearGradientShader(
                from = Offset(toX - edge, 0f),
                to = Offset(toX.toFloat(), 0f),
                colors = listOf(Color.Black, Color.Transparent),
            )
        }
        canvas.drawRect(toX - edge, top.toFloat(), toX.toFloat(), from.height.toFloat(), sideFade)
        picture = bitmap
        key = wanted
        return bitmap
    }

    private companion object {
        const val BLUR_COPIES = 8
        // The furthest copy is this much larger: at the bottom corners, a smear of about a twentieth of the way to the horizon.
        const val ZOOM = 0.05f
        // How much of its width fades out at its right edge.
        const val EDGE_FADE = 0.12f
    }
}

/**
 * A narrow patch of the road to lay over where the picture meets its mirror image, taken from a little further left in
 * the same picture and fading out to both sides, so the seam is covered by asphalt of its own: as sharp as the road
 * either side and from the same height in it, so its grain is the same size. (Blurring the seam instead left a dull
 * band down the road, which showed under the dock.) Made once for each road picture; a frame only lays it over the seam.
 */
private class SeamPatch {
    private var picture: ImageBitmap? = null
    private var source: ImageBitmap? = null

    fun get(from: ImageBitmap): ImageBitmap {
        picture?.let { if (source === from) return it }
        val half = (from.width * HALF_WIDTH).toInt().coerceAtLeast(2)
        val width = half * 2
        // The road a patch's width left of the seam: the same shade of it, but not the asphalt mirrored at the seam.
        val bitmap = ImageBitmap(width, from.height)
        val canvas = Canvas(bitmap)
        canvas.drawImage(from, Offset((3 * half - from.width).toFloat(), 0f), Paint())
        // Fading out to both sides, so it blends into the picture either side of it.
        val fade = Paint().apply {
            blendMode = BlendMode.DstIn
            shader = LinearGradientShader(
                from = Offset.Zero,
                to = Offset(width.toFloat(), 0f),
                colors = listOf(Color.Transparent, Color.Black, Color.Black, Color.Transparent),
                colorStops = listOf(0f, 0.35f, 0.65f, 1f),
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), from.height.toFloat(), fade)
        picture = bitmap
        source = from
        return bitmap
    }

    private companion object {
        // Half the patch, as a share of the picture's width.
        const val HALF_WIDTH = 0.04f
    }
}

/**
 * A picture scaled once to the size it is drawn at, so a frame copies it and does not scale the larger source again.
 * It is halved step by step first while it is more than twice that size, as filtering in one step from far larger
 * leaves fine detail (the number plate, the edges) jagged, and it shimmers when the car moves.
 */
private class Scaled {
    private var picture: ImageBitmap? = null
    private var source: ImageBitmap? = null

    fun get(from: ImageBitmap, width: Int, height: Int): ImageBitmap {
        picture?.let { if (source === from && it.width == width && it.height == height) return it }
        val paint = Paint().apply { filterQuality = FilterQuality.High }
        var step = from
        while (step.width >= width * 2 && step.height >= height * 2) {
            val half = ImageBitmap(step.width / 2, step.height / 2)
            Canvas(half).drawImageRect(step, dstSize = IntSize(half.width, half.height), paint = paint)
            step = half
        }
        val bitmap = ImageBitmap(width, height)
        Canvas(bitmap).drawImageRect(step, dstSize = IntSize(width, height), paint = paint)
        picture = bitmap
        source = from
        return bitmap
    }
}

/**
 * The road picture with the shore without the bridge laid over it at [at] (in the picture's pixels), for its mirror
 * image and the seam. Made once for each road picture.
 */
private class Retouched {
    private var picture: ImageBitmap? = null
    private var key: Any? = null

    fun get(from: ImageBitmap, shore: ImageBitmap, at: Offset): ImageBitmap {
        val wanted = listOf(from, shore, at)
        picture?.let { if (key == wanted) return it }
        val bitmap = ImageBitmap(from.width, from.height)
        Canvas(bitmap).apply {
            drawImage(from, Offset.Zero, Paint())
            drawImage(shore, at, Paint())
        }
        picture = bitmap
        key = wanted
        return bitmap
    }
}

/**
 * A picture drawn once by [draw] and kept while its key stays the same: opaque, the size of the scene, so a frame
 * copies it without blending.
 */
private class Baked {
    private var picture: ImageBitmap? = null
    private var key: Any? = null

    fun get(key: Any, width: Int, height: Int, draw: Canvas.() -> Unit): ImageBitmap {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        picture?.let { if (this.key == key && it.width == w && it.height == h) return it }
        val bitmap = ImageBitmap(w, h, hasAlpha = false)
        Canvas(bitmap).draw()
        picture = bitmap
        this.key = key
        return bitmap
    }
}
