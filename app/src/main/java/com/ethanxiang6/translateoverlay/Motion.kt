package com.ethanxiang6.translateoverlay

import kotlin.math.abs

/**
 * Vertical scroll estimation between two luma grids. A positive dy means the content moved
 * down by dy pixels between the previous and the current frame.
 */
object Motion {
    class Result(val dy: Int, val lost: Boolean)

    private const val STATIC_COST = 1.5f
    private const val ACCEPT_COST = 14f

    /**
     * @param mask optional coarse mask, one entry per 4 rows by [cols] columns, true where
     * the overlay boxes cover the screen. Masked cells are ignored on both frames.
     */
    fun estimate(
        prev: ByteArray,
        cur: ByteArray,
        rows: Int,
        cols: Int,
        mask: BooleanArray?,
        prevD: Int,
    ): Result {
        val minCells = ((rows / 4) * (cols / 2) * 0.08f).toInt().coerceAtLeast(30)

        val c0 = cost(prev, cur, rows, cols, mask, 0, minCells)
        if (c0 < 0f) return Result(0, false) // not enough uncovered pixels to tell
        if (c0 <= STATIC_COST) return Result(0, false)

        var best = c0
        var bestD = 0

        // Scrolling is smooth, so try shifts near the previous one first.
        for (d in prevD - 10..prevD + 10) {
            if (d == 0) continue
            val c = cost(prev, cur, rows, cols, mask, d, minCells)
            if (c >= 0f && c < best) {
                best = c
                bestD = d
            }
        }
        if (best <= ACCEPT_COST && best < c0 * 0.6f) return Result(bestD, false)

        // Wide search, coarse then refine.
        val range = (rows / 3).coerceAtMost(420)
        var d = -range
        while (d <= range) {
            val c = cost(prev, cur, rows, cols, mask, d, minCells)
            if (c >= 0f && c < best) {
                best = c
                bestD = d
            }
            d += 4
        }
        for (e in bestD - 3..bestD + 3) {
            val c = cost(prev, cur, rows, cols, mask, e, minCells)
            if (c >= 0f && c < best) {
                best = c
                bestD = e
            }
        }
        return if (bestD != 0 && best <= ACCEPT_COST && best < c0 * 0.6f) Result(bestD, false)
        else Result(0, true)
    }

    /** Mean absolute luma difference over every 4th row and every 2nd column, or -1 if too few cells. */
    private fun cost(
        prev: ByteArray,
        cur: ByteArray,
        rows: Int,
        cols: Int,
        mask: BooleanArray?,
        d: Int,
        minCells: Int,
    ): Float {
        var sum = 0L
        var n = 0
        var y = 0
        while (y < rows) {
            val py = y - d
            if (py in 0 until rows) {
                val a = y * cols
                val b = py * cols
                val mr = (y shr 2) * cols
                val mp = (py shr 2) * cols
                var c = 0
                while (c < cols) {
                    if (mask == null || !(mask[mr + c] || mask[mp + c])) {
                        sum += abs((cur[a + c].toInt() and 0xFF) - (prev[b + c].toInt() and 0xFF))
                        n++
                    }
                    c += 2
                }
            }
            y += 4
        }
        return if (n < minCells) -1f else sum.toFloat() / n
    }

    /** Overall change between two frames, no mask. */
    fun frameDiff(prev: ByteArray, cur: ByteArray, rows: Int, cols: Int): Float {
        var sum = 0L
        var n = 0
        var y = 0
        while (y < rows) {
            val a = y * cols
            var c = 0
            while (c < cols) {
                sum += abs((cur[a + c].toInt() and 0xFF) - (prev[a + c].toInt() and 0xFF))
                n++
                c += 2
            }
            y += 4
        }
        return if (n == 0) 0f else sum.toFloat() / n
    }
}
