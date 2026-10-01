package com.minhphan.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/** On and off once a second: slow enough not to pull the driver's eye from the road. */
private const val BLINK_HALF_PERIOD_MS = 500L

/** A problem that lasts is not news any more: after this the light stays on, steady. */
private const val BLINK_FOR_MS = 60_000L

/** How faint the light is when "off": still there to find. */
private const val BLINK_OFF_ALPHA = 0.2f

/**
 * Makes a status light blink while [active] (something is wrong: no GPS fix, the OBD link down), for
 * [BLINK_FOR_MS] from when it became so, then holds it steady; steady too while all is well.
 *
 * Kept nearly free on a head unit: it switches between on and off (two frames a second, nothing in between, where a
 * smooth fade would draw 60), the alpha is read in the layer only, so it neither recomposes nor redraws anything else,
 * and once it has stopped it draws nothing at all.
 */
@Composable
internal fun Modifier.blinkWhile(active: Boolean): Modifier {
    val on = remember { mutableStateOf(true) }
    LaunchedEffect(active) {
        on.value = true
        if (!active) return@LaunchedEffect
        var elapsed = 0L
        while (elapsed < BLINK_FOR_MS) {
            delay(BLINK_HALF_PERIOD_MS)
            elapsed += BLINK_HALF_PERIOD_MS
            on.value = !on.value
        }
        on.value = true
    }
    return graphicsLayer { alpha = if (on.value) 1f else BLINK_OFF_ALPHA }
}
