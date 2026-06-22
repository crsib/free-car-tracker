package me.crsib.freecartracker.telemetry.obd.transport

import kotlinx.serialization.Serializable
import me.crsib.freecartracker.domain.model.ObdTransportKind

/** Persisted description of how to reach the user's ELM327 adapter. */
@Serializable
data class ObdConnectionConfig(
    val kind: ObdTransportKind = ObdTransportKind.BT_CLASSIC,
    /** MAC address for BT_CLASSIC / BLE. */
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val wifiHost: String = "192.168.0.10",
    val wifiPort: Int = 35000,
    // BLE clones vary; defaults cover the common FFF0 serial service.
    val bleServiceUuid: String = "0000fff0-0000-1000-8000-00805f9b34fb",
    val bleNotifyUuid: String = "0000fff1-0000-1000-8000-00805f9b34fb",
    val bleWriteUuid: String = "0000fff2-0000-1000-8000-00805f9b34fb",
)
