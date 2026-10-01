package com.minhphan.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DockAppsTest {
    private val waze = "com.waze/.FreeMapAppActivity@0"
    private val zalo = "com.zing.zalo/.ui.ZaloLauncherActivity@0"

    @Test
    fun neverSavedIsTheMapsTheMusicAndYouTube() {
        assertEquals(listOf("@maps", "@music", "@youtube"), decodeDockApps(null))
    }

    @Test
    fun anEmptiedDockStaysEmpty() {
        assertEquals(emptyList<String>(), decodeDockApps(encodeDockApps(emptyList())))
    }

    @Test
    fun theOrderSurvivesSavingAndReading() {
        val ids = listOf(zalo, "@maps", waze)
        assertEquals(ids, decodeDockApps(encodeDockApps(ids)))
    }

    @Test
    fun anAppIsAddedAtTheEndOnceAndNotPastTheLimit() {
        val three = listOf("@maps", "@music", "@youtube")
        val four = withDockApp(three, waze, on = true)
        assertEquals(three + waze, four)
        assertEquals(four, withDockApp(four, waze, on = true))
        assertEquals(four, withDockApp(four, zalo, on = true)) // full
        assertEquals(listOf("@maps", "@youtube", waze), withDockApp(four, "@music", on = false))
    }

    @Test
    fun anAppMovesBetweenItsNeighboursAndStopsAtTheEnds() {
        val ids = listOf("@maps", waze, "@music")
        assertEquals(listOf(waze, "@maps", "@music"), movedDockApp(ids, waze, -1))
        assertEquals(listOf("@maps", "@music", waze), movedDockApp(ids, waze, 1))
        assertEquals(ids, movedDockApp(ids, "@maps", -1))
        assertEquals(ids, movedDockApp(ids, zalo, 1)) // not on the dock
    }

    @Test
    fun aSavedListThatIsTooLongOrRepeatsIsTrimmed() {
        val saved = encodeDockApps(listOf("@maps", "@maps", waze, zalo, "@music", "@youtube"))
        assertEquals(listOf("@maps", waze, zalo, "@music"), decodeDockApps(saved))
    }
}
