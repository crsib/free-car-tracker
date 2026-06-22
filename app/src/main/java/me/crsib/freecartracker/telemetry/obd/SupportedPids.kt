package me.crsib.freecartracker.telemetry.obd

/**
 * Parses "available PIDs" bitmask responses (Mode 01 PID 00/20/40/...) into the set of
 * supported PID codes. Pure, so it is unit-testable without an adapter.
 *
 * We parse the bitmask ourselves rather than via the library's AvailablePIDsCommand, whose
 * handler folds the `41 00` response header into the bit math and so mis-maps the bits.
 */
object SupportedPids {
    /**
     * @param processedValue cleaned hex of one response, e.g. "4100BE3EB811" — a 2-byte
     *   response header (`41` + range pid) followed by a 4-byte bitmask.
     * @param basePid the range base (0x00, 0x20, 0x40, ...). The mask's MSB => basePid+1,
     *   its LSB => basePid+32.
     */
    fun parseBitmask(processedValue: String, basePid: Int): Set<String> {
        val hex = processedValue.uppercase().filter { it.isDigit() || it in 'A'..'F' }
        val mask = hex.drop(HEADER_HEX_CHARS).take(MASK_HEX_CHARS)
        if (mask.length < MASK_HEX_CHARS) return emptySet()
        val bits = mask.toLongOrNull(radix = 16) ?: return emptySet()
        val result = mutableSetOf<String>()
        for (i in 0 until 32) {
            if ((bits shr (31 - i)) and 1L == 1L) {
                result += "%02X".format(basePid + i + 1)
            }
        }
        return result
    }

    /** Merge several (processedValue, basePid) range responses into one supported set. */
    fun merge(responses: List<Pair<String, Int>>): Set<String> =
        responses.flatMap { (value, base) -> parseBitmask(value, base) }.toSet()

    private const val HEADER_HEX_CHARS = 4 // "41" + range pid
    private const val MASK_HEX_CHARS = 8 // 4 mask bytes
}
