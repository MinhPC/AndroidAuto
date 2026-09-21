package com.minhphan.launcher.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdFieldTest {
    @Test
    fun switchingAFieldOnKeepsTheOrderOfTheList() {
        val fields = withField(listOf(ObdField.LOAD, ObdField.COOLANT), ObdField.VOLTAGE, on = true)
        assertEquals(listOf(ObdField.COOLANT, ObdField.VOLTAGE, ObdField.LOAD), fields)
    }

    @Test
    fun switchingAFieldOffTakesItOut() {
        assertEquals(listOf(ObdField.COOLANT), withField(listOf(ObdField.COOLANT, ObdField.LOAD), ObdField.LOAD, on = false))
        assertEquals(emptyList<ObdField>(), withField(emptyList(), ObdField.LOAD, on = false))
    }

    @Test
    fun noMoreThanTheMostCanBeOn() {
        // The last nine in the list are on; switching on the first must not push any of them out.
        val nine = ObdField.entries.takeLast(MAX_OBD_FIELDS)
        assertEquals(nine, withField(nine, ObdField.entries.first(), on = true))
        // One already on can be switched on again without changing anything, and off frees a place.
        assertEquals(nine, withField(nine, nine.first(), on = true))
        val freed = withField(nine, nine.last(), on = false)
        assertEquals(MAX_OBD_FIELDS, withField(freed, ObdField.entries.first(), on = true).size)
    }

    @Test
    fun theChoiceSurvivesBeingSavedAndOneNoLongerKnownIsSkipped() {
        val chosen = listOf(ObdField.INTAKE, ObdField.MAP, ObdField.SPEED)
        assertEquals(chosen, decodeObdFields(encodeObdFields(chosen)))
        assertEquals(listOf(ObdField.MAP), decodeObdFields("GONE,MAP"))
        assertEquals(emptyList<ObdField>(), decodeObdFields("")) // the driver switched everything off
        assertEquals(DEFAULT_OBD_FIELDS, decodeObdFields(null)) // never chosen
    }

    @Test
    fun theExtraValuesToReadAreThoseOfTheChosenFields() {
        assertEquals(setOf(Pid.Map, Pid.Oil), extraPidsFor(listOf(ObdField.VOLTAGE, ObdField.MAP, ObdField.OIL)))
    }

    @Test
    fun aFieldReadsItsValueAndTheVoltageComesFromTheAdapter() {
        val values = ObdValues(coolantC = 88, voltage = 12.6f).with(Pid.Map, 35).with(Pid.Timing, 12)
        assertEquals(88f, values.reading(ObdField.COOLANT)!!, 0f)
        assertEquals(12.6f, values.reading(ObdField.VOLTAGE)!!, 0f)
        assertEquals(35f, values.reading(ObdField.MAP)!!, 0f)
        assertEquals(12f, values.reading(ObdField.TIMING)!!, 0f)
        assertNull(values.reading(ObdField.OIL))
        assertNull(values.with(Pid.Map, null).reading(ObdField.MAP))
    }
}
