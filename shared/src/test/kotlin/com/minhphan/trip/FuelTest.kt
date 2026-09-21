package com.minhphan.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuelTest {
    @Test
    fun theFirstFullFillUpOnlyMarksWhereToMeasureFrom() {
        val book = FuelBook().record(at = 1_000, liters = 40.0, amountVnd = 900_000, full = true, odometerKm = 120.0)

        assertEquals(120.0, book.baselineKm!!, 0.0)
        assertNull(book.last!!.kmPerLiter)
        assertNull(book.averageKmPerLiter)
    }

    @Test
    fun aSecondFullFillUpGivesTheEconomyOfTheKilometresBetween() {
        val book = FuelBook()
            .record(1_000, 40.0, 900_000, full = true, odometerKm = 120.0)
            .record(2_000, 35.0, 805_000, full = true, odometerKm = 620.0)

        val last = book.last!!
        assertEquals(500.0, last.distanceKm!!, 0.001)
        assertEquals(500.0 / 35.0, last.kmPerLiter!!, 0.001)
        assertEquals(23_000, last.pricePerLiter)
        assertEquals(500.0 / 35.0, book.averageKmPerLiter!!, 0.001)
        assertEquals(620.0, book.baselineKm!!, 0.0)
    }

    @Test
    fun partFillUpsAddTheirFuelToThePeriodTheyBelongTo() {
        val book = FuelBook()
            .record(1_000, 40.0, 900_000, full = true, odometerKm = 0.0)
            .record(2_000, 10.0, 230_000, full = false, odometerKm = 200.0)
            .record(3_000, 30.0, 690_000, full = true, odometerKm = 600.0)

        assertEquals(600.0, book.last!!.distanceKm!!, 0.001)
        assertEquals(40.0, book.last!!.litersInPeriod!!, 0.001)
        assertEquals(15.0, book.last!!.kmPerLiter!!, 0.001)
        assertEquals(0.0, book.partialLiters, 0.0)
    }

    @Test
    fun aPartFillUpBeforeAnyFullOneIsRecordedButMeasuresNothing() {
        val book = FuelBook().record(1_000, 10.0, 230_000, full = false, odometerKm = 5.0)

        assertEquals(10.0, book.last!!.liters, 0.0)
        assertNull(book.baselineKm)
        assertEquals(0.0, book.partialLiters, 0.0)
    }

    @Test
    fun theDriverCanCorrectTheDistanceTheOdometerGuessed() {
        val book = FuelBook(baselineKm = 100.0)
            .record(2_000, 30.0, 690_000, full = true, odometerKm = 400.0, distanceKm = 450.0)

        assertEquals(450.0, book.last!!.distanceKm!!, 0.0)
        assertEquals(15.0, book.last!!.kmPerLiter!!, 0.001)
    }

    @Test
    fun noDistanceMeansNoEconomyButTheMarkMovesOn() {
        val book = FuelBook(baselineKm = 100.0)
            .record(2_000, 30.0, 690_000, full = true, odometerKm = 100.0)

        assertNull(book.last!!.kmPerLiter)
        assertNull(book.averageKmPerLiter)
        assertEquals(100.0, book.baselineKm!!, 0.0)
    }

    @Test
    fun theAverageIsTotalDistanceOverTotalFuelNotTheMeanOfTheRatios() {
        val book = FuelBook()
            .record(1, 40.0, 0, true, 0.0)
            .record(2, 10.0, 0, true, 100.0) // 10 km/L over 10 L
            .record(3, 50.0, 0, true, 700.0) // 12 km/L over 50 L

        assertEquals(700.0 / 60.0, book.averageKmPerLiter!!, 0.001)
    }

    @Test
    fun suggestedDistanceIsNullWithoutABaselineOrWhenTheOdometerHasNotMoved() {
        assertNull(FuelBook().suggestedKm(50.0))
        assertNull(FuelBook(baselineKm = 50.0).suggestedKm(50.0))
        assertEquals(30.0, FuelBook(baselineKm = 20.0).suggestedKm(50.0)!!, 0.0)
    }

    @Test
    fun theTankIsThoughtFullRightAfterAFullFillUpAndEmptiesAtTheAverageEconomy() {
        val book = FuelBook()
            .record(1, 40.0, 0, true, 0.0)
            .record(2, 35.0, 0, true, 500.0) // 500 km on 35 L: 14.29 km/L
        assertEquals(500.0 / 35.0, book.averageKmPerLiter!!, 0.001)

        val fresh = book.estimate(odometerKm = 500.0, tankLiters = 50.0)!!
        assertEquals(50.0, fresh.liters, 0.001)
        assertEquals(100, fresh.percent)
        assertEquals(50.0 * 500.0 / 35.0, fresh.rangeKm, 0.01)
        assertEquals(false, fresh.assumed)

        val later = book.estimate(odometerKm = 500.0 + 300.0, tankLiters = 50.0)!! // 300 km at 14.29 km/L is 21 L
        assertEquals(50.0 - 300.0 / (500.0 / 35.0), later.liters, 0.001)
        assertEquals(58, later.percent)
        assertEquals(later.liters * (500.0 / 35.0), later.rangeKm, 0.01)
    }

    @Test
    fun aTypicalEconomyStandsInUntilTheDriversOwnIsKnownAndSaysSo() {
        val book = FuelBook().record(1, 40.0, 0, true, 100.0)
        val estimate = book.estimate(odometerKm = 220.0, tankLiters = 50.0)!!

        assertEquals(true, estimate.assumed)
        assertEquals(DEFAULT_KM_PER_LITER, estimate.kmPerLiter, 0.0)
        assertEquals(40.0, estimate.liters, 0.001) // 120 km at 12 km/L is 10 L
        assertEquals(480.0, estimate.rangeKm, 0.001)
    }

    @Test
    fun partFillUpsPutFuelBackAndTheTankNeverHoldsMoreThanItsSize() {
        // Economy 12 km/L; 20 L were added by part fill-ups since the last full one.
        val book = FuelBook(baselineKm = 0.0, trackedKm = 120.0, trackedLiters = 10.0, partialLiters = 20.0)

        assertEquals(40.0, book.estimate(odometerKm = 360.0, tankLiters = 50.0)!!.liters, 0.001) // 50 - 30 + 20
        assertEquals(50.0, book.estimate(odometerKm = 120.0, tankLiters = 50.0)!!.liters, 0.001) // 50 - 10 + 20, capped
    }

    @Test
    fun noFullFillUpMeansNoEstimateAndTheTankRunsDryAtZero() {
        assertNull(FuelBook().estimate(100.0, 50.0))
        assertNull(FuelBook(baselineKm = 0.0).estimate(100.0, 0.0))

        val dry = FuelBook(baselineKm = 0.0, trackedKm = 120.0, trackedLiters = 10.0).estimate(odometerKm = 5_000.0, tankLiters = 50.0)!!
        assertEquals(0.0, dry.liters, 0.0)
        assertEquals(0.0, dry.rangeKm, 0.0)
        assertEquals(0, dry.percent)
    }
}
