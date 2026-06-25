package me.crsib.freecartracker.data.webdav

import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavPathsTest {

    @Test
    fun didScansDir_underVehicleWhenKnown() {
        assertEquals("car-123/did-scans/", WebDavPaths.didScansDir("car-123"))
    }

    @Test
    fun didScansDir_topLevelWhenCarless() {
        assertEquals("did-scans/", WebDavPaths.didScansDir(null))
    }

    @Test
    fun didScansDir_sitsBesideTripsRoot_notInsideIt() {
        // The pull walk only descends trips/, so did-scans must be a sibling, not under it.
        val v = "car-123"
        assertEquals("$v/", WebDavPaths.vehicleDir(v))
        assertEquals("$v/trips/", WebDavPaths.tripsRoot(v))
        assertEquals("$v/did-scans/", WebDavPaths.didScansDir(v))
    }
}
