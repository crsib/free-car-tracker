package me.crsib.freecartracker.telemetry.obd.scan

/**
 * Candidate ECUs to probe on a Li Auto (Lixiang) vehicle.
 *
 * What we know: the extracted DTC database lists ~52 ECU modules by *name* — BMS, LBMS, EMS, MCUF,
 * MCUR, XCU, ESP, IBOOSTER, FSD, … — but **not** their CAN arbitration ids. Mapping name → id is
 * exactly what discovery does. So scanning is two phases:
 *
 *  1. [discoveryHeaders] — probe the standard 11-bit physical request ids (0x7E0..0x7E7) with a
 *     near-universal DID (VIN, 0xF190) to learn which addresses answer at all. Whatever responds is
 *     a live ECU; label it later by correlating the data you read against known state.
 *  2. Sweep DIDs on the responders (see [DidScanner]).
 *
 * The names below are the standard OBD-II physical-addressing slots, not confirmed Li Auto mappings.
 * Li Auto may also use 29-bit ids (0x18DAxxF1) or OEM-specific 11-bit ids outside this block; add
 * those as [EcuTarget]s once a CAN trace reveals them.
 */
object LixiangEcuSeeds {

    /** Standard 11-bit physical request ids 0x7E0..0x7E7 (responses at +8: 0x7E8..0x7EF). */
    val standardPhysical: List<EcuTarget> = (0x7E0..0x7E7).map { id ->
        EcuTarget(name = "ECU_%03X".format(id), requestHeader = "%03X".format(id))
    }

    /** OBD functional broadcast — every ISO-TP ECU may answer; useful for a first liveness sweep. */
    val functionalBroadcast = EcuTarget(name = "FUNCTIONAL", requestHeader = "7DF", note = "broadcast")

    /** Sensible default discovery set: the eight physical slots. */
    val discoveryHeaders: List<EcuTarget> = standardPhysical

    /** DIDs that most UDS ECUs implement — handy probes for liveness / labelling a responder. */
    object WellKnownDids {
        const val VIN = 0xF190
        const val ECU_SERIAL = 0xF18C
        const val SW_VERSION = 0xF195
        const val ECU_NAME = 0xF197
        val all = listOf(VIN, ECU_SERIAL, SW_VERSION, ECU_NAME)
    }
}
