package me.crsib.freecartracker.telemetry.obd

import com.github.eltonvs.obd.command.ObdProtocols
import com.github.eltonvs.obd.command.Switcher
import com.github.eltonvs.obd.command.at.ResetAdapterCommand
import com.github.eltonvs.obd.command.at.SelectProtocolCommand
import com.github.eltonvs.obd.command.at.SetEchoCommand
import com.github.eltonvs.obd.command.at.SetHeadersCommand
import com.github.eltonvs.obd.command.at.SetLineFeedCommand
import com.github.eltonvs.obd.command.at.SetSpacesCommand
import com.github.eltonvs.obd.connection.ObdDeviceConnection

/**
 * Runs the ELM327 AT setup handshake once after a transport connects: reset, echo/linefeed/
 * spaces off (faster, simpler parsing), headers off, automatic protocol detection.
 */
class Elm327Initializer {
    suspend fun initialize(connection: ObdDeviceConnection) {
        connection.run(ResetAdapterCommand(), delayTime = RESET_DELAY_MS)
        connection.run(SetEchoCommand(Switcher.OFF))
        connection.run(SetLineFeedCommand(Switcher.OFF))
        connection.run(SetSpacesCommand(Switcher.OFF))
        connection.run(SetHeadersCommand(Switcher.OFF))
        connection.run(SelectProtocolCommand(ObdProtocols.AUTO))
    }

    companion object {
        private const val RESET_DELAY_MS = 500L
    }
}
