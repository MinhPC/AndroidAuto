package com.minhphan.launcher.ui

import kotlin.math.ceil

/** Draw at a whole-number divisor of vsync, avoiding alternating short/long frame intervals. */
internal class FrameCadence(refreshHz: Float, targetFps: Int) {
    private val framesPerUpdate = framesPerUpdate(refreshHz, targetFps)
    private var frames = 0

    fun shouldDraw(): Boolean {
        frames++
        if (frames < framesPerUpdate) return false
        frames = 0
        return true
    }
}

internal fun framesPerUpdate(refreshHz: Float, targetFps: Int): Int {
    require(targetFps > 0)
    val hz = refreshHz.takeIf { it.isFinite() && it > 0f } ?: 60f
    // Display rates such as 60.000004 should still draw every vsync at a 60 fps target.
    return ceil((hz - 0.5f) / targetFps).toInt().coerceAtLeast(1)
}
