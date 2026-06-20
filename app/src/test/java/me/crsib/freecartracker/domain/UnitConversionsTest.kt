package me.crsib.freecartracker.domain

import me.crsib.freecartracker.domain.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitConversionsTest {

    @Test
    fun `km to miles round trips`() {
        val km = 100.0
        val mi = UnitConversions.kmToDisplay(km, UnitSystem.IMPERIAL)
        assertEquals(62.137, mi, 1e-3)
        assertEquals(km, UnitConversions.displayToKm(mi, UnitSystem.IMPERIAL), 1e-9)
    }

    @Test
    fun `litres to gallons round trips`() {
        val litres = 37.85411784
        val gal = UnitConversions.litresToDisplay(litres, UnitSystem.IMPERIAL)
        assertEquals(10.0, gal, 1e-9)
        assertEquals(litres, UnitConversions.displayToLitres(gal, UnitSystem.IMPERIAL), 1e-9)
    }

    @Test
    fun `metric passes values through unchanged`() {
        assertEquals(123.0, UnitConversions.kmToDisplay(123.0, UnitSystem.METRIC), 1e-9)
        assertEquals(45.0, UnitConversions.litresToDisplay(45.0, UnitSystem.METRIC), 1e-9)
    }

    @Test
    fun `fuel economy converts L per 100km to MPG`() {
        // 8 L/100km is about 29.4 US MPG
        val mpg = UnitConversions.fuelEconomyToDisplay(8.0, UnitSystem.IMPERIAL)!!
        assertEquals(29.4, mpg, 0.1)
        assertEquals(8.0, UnitConversions.fuelEconomyToDisplay(8.0, UnitSystem.METRIC)!!, 1e-9)
        assertNull(UnitConversions.fuelEconomyToDisplay(0.0, UnitSystem.METRIC))
        assertNull(UnitConversions.fuelEconomyToDisplay(null, UnitSystem.IMPERIAL))
    }

    @Test
    fun `electric economy converts kWh per 100km to miles per kWh`() {
        // 20 kWh/100km -> 100km is 62.137 mi over 20 kWh -> ~3.107 mi/kWh
        val miPerKwh = UnitConversions.electricEconomyToDisplay(20.0, UnitSystem.IMPERIAL)!!
        assertEquals(3.107, miPerKwh, 1e-3)
    }
}
