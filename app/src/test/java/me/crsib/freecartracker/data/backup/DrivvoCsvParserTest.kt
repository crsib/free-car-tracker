package me.crsib.freecartracker.data.backup

import me.crsib.freecartracker.domain.model.ChargeType
import me.crsib.freecartracker.domain.model.ExpenseCategory
import me.crsib.freecartracker.domain.model.FuelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DrivvoCsvParserTest {

    private val parser = DrivvoCsvParser(ZoneId.of("UTC"))

    // A trimmed-down but structurally faithful Drivvo export: three sections, an electric
    // refuelling row, a partial fill, and the unescaped inner quotes Drivvo emits for stations.
    private val csv = """
        ##Refuelling
        "Odometer (km)","Date","Fuel","Price / L","Total cost","Volume","Filled tank completely","Second fuel","Price / L","Total cost","Volume","Filled tank completely" 2,"Third fuel","Price / L","Total cost","Volume","Filled tank completely" 3,"Fuel efficiency","Distance","Gas station","Driver","Reason","Payment method","Notes"
        "40137.0","2026-06-18 17:42:08","Gasoline","72.26","3562.418","49.3","Yes","","0","0","0","No","","0","0","0","No","12.024 L/100km","","Neste","Dmitry Vedenko","","",""
        "39331.0","2026-06-17 14:16:37","Gasoline","72.13","3245.85","45","No","","0","0","0","No","","0","0","0","No","11.007 L/100km","396.0","Neste","Dmitry Vedenko","","",""
        "38693.0","2026-06-13 19:00:53","Electric","20","371","18.55","No","","0","0","0","No","","0","0","0","No","0 kWh/100km","53.0","Neste","Dmitry Vedenko","","",""
        "34290.0","2026-05-02 16:39:33","Gasoline","68.69","3192.711","46.48","Yes","","0","0","0","No","","0","0","0","No","8 L/100km","505.0","Азс № 154 "железноводская"","Dmitry Vedenko","","",""

        ##Expense
        "Odometer (km)","Date","Total cost","Type of expense","Expense location","Driver","Reason","Payment method","Notes"
        "38000.0","2026-06-07 13:49:40","150000","Financing","","Dmitry Vedenko","","",""
        "18700.0","2025-08-03 00:15:39","9586.11","Страховка","","Dmitry Vedenko","","",""
        "22600.0","2025-10-23 18:36:03","19120","Tires","","Dmitry Vedenko","","",""

        ##Service
        "Odometer (km)","Date","Total cost","Type of service","Service location","Driver","Payment method","Notes"
        "40265.0","2026-06-19 16:35:33","45000","Inspection","","Dmitry Vedenko","",""
    """.trimIndent()

    @Test
    fun `splits rows across sections by type`() {
        val r = parser.parse(csv, vehicleId = 7, currency = "RUB")
        assertEquals(3, r.fuel.size) // 3 gasoline rows
        assertEquals(1, r.charges.size) // the electric row
        assertEquals(4, r.expenses.size) // 3 expense + 1 service
    }

    @Test
    fun `maps a gasoline fill with currency, full-tank flag and timestamp`() {
        val r = parser.parse(csv, vehicleId = 7, currency = "RUB")
        val fill = r.fuel.first { it.odometerKm == 40137.0 }
        assertEquals(7, fill.vehicleId)
        assertEquals(49.3, fill.volumeL, 1e-9)
        assertEquals(FuelType.PETROL, fill.fuelType)
        assertTrue(fill.isFullTank)
        assertEquals("RUB", fill.currency)
        // RUB has 2 fraction digits; 3562.418 → 356242 minor units (HALF_UP).
        assertEquals(356242L, fill.totalCostMinor)
        assertEquals(Instant.parse("2026-06-18T17:42:08Z").toEpochMilli(), fill.dateMillis)
        assertEquals("Neste", fill.station)
    }

    @Test
    fun `partial fill is not marked full`() {
        val r = parser.parse(csv, vehicleId = 7, currency = "RUB")
        assertFalse(r.fuel.first { it.odometerKm == 39331.0 }.isFullTank)
    }

    @Test
    fun `electric refuelling becomes a charge in kWh`() {
        val r = parser.parse(csv, vehicleId = 7, currency = "RUB")
        val charge = r.charges.single()
        assertEquals(18.55, charge.energyKwh, 1e-9)
        assertEquals(38693.0, charge.odometerKm)
        assertEquals(37100L, charge.totalCostMinor)
        assertEquals(ChargeType.AC, charge.chargeType)
    }

    @Test
    fun `tolerates unescaped inner quotes in station names`() {
        val r = parser.parse(csv, vehicleId = 7, currency = "RUB")
        val fill = r.fuel.first { it.odometerKm == 34290.0 }
        assertTrue("station was: ${fill.station}", fill.station!!.startsWith("Азс № 154"))
    }

    @Test
    fun `maps expense types to categories and preserves the original label`() {
        val r = parser.parse(csv, vehicleId = 7, currency = "RUB")
        val financing = r.expenses.first { it.totalCostMinor == 15_000_000L }
        assertEquals(ExpenseCategory.FINANCING, financing.category)

        val insurance = r.expenses.first { it.description == "Страховка" }
        assertEquals(ExpenseCategory.INSURANCE, insurance.category)

        // Unknown type falls back to OTHER but keeps the label in the description.
        val tires = r.expenses.first { it.description == "Tires" }
        assertEquals(ExpenseCategory.OTHER, tires.category)
    }

    @Test
    fun `service rows are categorised as service with the work in the description`() {
        val r = parser.parse(csv, vehicleId = 7, currency = "RUB")
        val service = r.expenses.first { it.description == "Inspection" }
        assertEquals(ExpenseCategory.SERVICE, service.category)
        assertEquals(40265.0, service.odometerKm)
    }

    @Test
    fun `tokenize discards header suffixes after closing quotes`() {
        val cells = DrivvoCsvParser.tokenize(""""Filled tank completely" 2,"Third fuel"""")
        assertEquals(listOf("Filled tank completely", "Third fuel"), cells)
    }

    @Test
    fun `empty input yields nothing`() {
        val r = parser.parse("", vehicleId = 1, currency = "USD")
        assertEquals(0, r.total)
        assertNull(r.fuel.firstOrNull())
    }
}
