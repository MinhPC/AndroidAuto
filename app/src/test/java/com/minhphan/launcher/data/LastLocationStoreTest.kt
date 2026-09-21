package com.minhphan.launcher.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LastLocationStoreTest {
    private val hanoi = Coordinates(21.03, 105.85)

    @Test
    fun aPositionBarelyMovedIsNotWorthSaving() {
        assertFalse(movedEnoughToSave(hanoi, Coordinates(21.05, 105.87)))
        assertFalse(movedEnoughToSave(hanoi, hanoi))
    }

    @Test
    fun aPositionTensOfKilometresAwayIsSavedInEitherAxis() {
        assertTrue(movedEnoughToSave(hanoi, Coordinates(21.20, 105.85)))
        assertTrue(movedEnoughToSave(hanoi, Coordinates(21.03, 105.70)))
    }
}
