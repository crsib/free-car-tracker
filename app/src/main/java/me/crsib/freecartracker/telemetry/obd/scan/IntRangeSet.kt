package me.crsib.freecartracker.telemetry.obd.scan

/**
 * A set of ints stored as sorted, non-overlapping inclusive ranges. The DID skip-set is almost all
 * contiguous — long runs of unsupported `0x31` DIDs around a handful of live ones — so run-length
 * ranges keep it tiny to persist and O(log n) to query, versus a 64K-entry hash set.
 */
class IntRangeSet private constructor(private val ranges: MutableList<IntArray>) {
    constructor() : this(mutableListOf())

    /** Add the inclusive range [from]..[to], coalescing with overlapping or adjacent ranges. */
    fun add(from: Int, to: Int) {
        var lo = minOf(from, to)
        var hi = maxOf(from, to)
        val merged = ArrayList<IntArray>(ranges.size + 1)
        var placed = false
        for (r in ranges) {
            when {
                r[1] < lo - 1 -> merged.add(r) // strictly before, no touch
                r[0] > hi + 1 -> { // strictly after, no touch
                    if (!placed) { merged.add(intArrayOf(lo, hi)); placed = true }
                    merged.add(r)
                }
                else -> { // overlaps or is adjacent — absorb into [lo,hi]
                    lo = minOf(lo, r[0]); hi = maxOf(hi, r[1])
                }
            }
        }
        if (!placed) merged.add(intArrayOf(lo, hi))
        ranges.clear(); ranges.addAll(merged)
    }

    /** True if [x] falls in any range. Binary search over the sorted ranges. */
    fun contains(x: Int): Boolean {
        var lo = 0; var hi = ranges.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = ranges[mid]
            when {
                x < r[0] -> hi = mid - 1
                x > r[1] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    /** Ranges as (start, end) inclusive pairs, ascending. */
    fun pairs(): List<Pair<Int, Int>> = ranges.map { it[0] to it[1] }

    /** Total number of ints covered. */
    val count: Int get() = ranges.sumOf { it[1] - it[0] + 1 }

    companion object {
        fun fromPairs(pairs: List<Pair<Int, Int>>): IntRangeSet =
            IntRangeSet().apply { pairs.forEach { add(it.first, it.second) } }
    }
}
