package me.crsib.freecartracker.data.backup

/** Quote a CSV cell if it contains a delimiter, quote, or newline (RFC 4180 style). */
internal fun csvEscape(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }

/**
 * Split one CSV record into cells, honoring double-quoted fields and doubled-quote (`""`)
 * escapes. Assumes a single physical line (no embedded newlines), which holds for the
 * telemetry exports — cells are numbers and short tokens.
 */
internal fun splitCsvLine(line: String): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            inQuotes -> when {
                c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = false
                else -> sb.append(c)
            }
            c == '"' -> inQuotes = true
            c == ',' -> { out.add(sb.toString()); sb.setLength(0) }
            else -> sb.append(c)
        }
        i++
    }
    out.add(sb.toString())
    return out
}

/**
 * Format a number for CSV/GPX in a locale-independent way (always `.` decimal). Whole values
 * print without a trailing `.0` for compactness; both forms parse back via [String.toDouble].
 */
internal fun num(value: Double): String =
    if (value.isFinite() && value == Math.floor(value) && Math.abs(value) < 1e15) {
        value.toLong().toString()
    } else {
        value.toString()
    }
