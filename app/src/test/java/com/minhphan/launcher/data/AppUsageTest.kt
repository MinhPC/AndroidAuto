package com.minhphan.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUsageTest {
    private val byName = listOf("Chrome", "Maps", "Music", "Phone", "Zalo")

    private fun order(counts: Map<String, Int> = emptyMap()) = smartOrder(byName, { it }, counts)

    @Test
    fun withNothingKnownTheListStaysInNameOrder() {
        assertEquals(byName, order())
    }

    @Test
    fun theMostLaunchedAppsComeFirstAndTheRestKeepTheirNameOrder() {
        val counts = mapOf("Phone" to 9, "Music" to 9, "Chrome" to 2)
        // Phone and Music tie on nine launches, so they stay in name order; the unused ones follow, by name.
        assertEquals(listOf("Music", "Phone", "Chrome", "Maps", "Zalo"), order(counts = counts))
    }

    @Test
    fun aLaunchAddsOneToTheApp() {
        val counts = recordLaunch(recordLaunch(emptyMap(), "Maps"), "Maps")
        assertEquals(mapOf("Maps" to 2), counts)
        assertEquals(mapOf("Maps" to 2, "Zalo" to 1), recordLaunch(counts, "Zalo"))
    }

    @Test
    fun oldHabitsFadeOnceTheCountsAddUpToALot() {
        val old = mapOf("Old app" to 299, "Zalo" to 1)
        val next = recordLaunch(old, "Zalo") // the sum would be 301: everything is halved, rounding up
        assertEquals(150, next.getValue("Old app"))
        assertEquals(1, next.getValue("Zalo"))
        assertEquals(1, recordLaunch(mapOf("Rare" to 1, "Zalo" to 300), "Rare").getValue("Rare"))
    }
}
