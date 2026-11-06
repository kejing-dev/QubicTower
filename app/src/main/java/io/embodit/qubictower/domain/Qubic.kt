package io.embodit.qubictower.domain

/**
 * Qubic 4 x 4 x 4 rules (design gameSpec). Pure Kotlin, no Android.
 *
 * Pegs: p = j * 4 + i, i = 0..3 along +X (letters A–D), j = 0..3 along +Z toward the user (numbers 1–4), so A1 is the
 * far-left peg and D4 the near-right one. Layers: L = 0 (bottom) .. 3 (top). Cell c = L * 16 + p, bit c of a Long.
 * Amber (0) always moves first, Celadon (1) second; the side to move is the parity of the move count.
 */
object Qubic {
    const val AMBER = 0
    const val CELADON = 1
    const val N = 4
    const val PEGS = 16
    const val CELLS = 64
    const val BEADS = 32
    const val FULL: Long = -1L

    fun other(side: Int) = 1 - side
    fun peg(i: Int, j: Int) = j * 4 + i
    fun cell(peg: Int, layer: Int) = layer * 16 + peg
    fun pegOf(cell: Int) = cell and 15
    fun layerOf(cell: Int) = cell shr 4
    fun iOf(peg: Int) = peg and 3
    fun jOf(peg: Int) = peg shr 2

    /** "A1" .. "D4" (letter = x column, number = z row from the far side). */
    fun pegName(peg: Int): String = "${'A' + iOf(peg)}${jOf(peg) + 1}"

    fun parsePeg(s: String): Int? {
        val t = s.trim().uppercase()
        t.toIntOrNull()?.let { return it.takeIf { v -> v in 0..15 } }
        if (t.length != 2) return null
        val i = t[0] - 'A'; val j = t[1] - '1'
        return if (i in 0..3 && j in 0..3) peg(i, j) else null
    }

    enum class Kind { COLUMN, ROW, FLAT, CROSS, SPACE }

    /** A winning line: its four cells in order and its mask. */
    class Line(val index: Int, val cells: IntArray, val kind: Kind) {
        val mask: Long = cells.fold(0L) { m, c -> m or (1L shl c) }
    }

    /** The 76 lines, generated (16 columns, 32 rows, 8 flat diagonals, 16 cross-layer diagonals, 4 space diagonals). */
    val LINES: List<Line> = buildLines()

    /** Indices of the lines through each cell (4 or 7). */
    val LINES_THROUGH: Array<IntArray> = Array(CELLS) { c -> LINES.filter { (it.mask shr c) and 1L == 1L }.map { it.index }.toIntArray() }

    private fun buildLines(): List<Line> {
        val out = ArrayList<Line>()
        // directions (dx, dy, dz) with the first non-zero component positive
        val dirs = ArrayList<IntArray>()
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            if (dx == 0 && dy == 0 && dz == 0) continue
            val first = listOf(dx, dy, dz).first { it != 0 }
            if (first > 0) dirs += intArrayOf(dx, dy, dz)
        }
        val seen = HashSet<Long>()
        val tmp = ArrayList<Pair<IntArray, Kind>>()
        for (d in dirs) for (x0 in 0..3) for (y0 in 0..3) for (z0 in 0..3) {
            val cells = IntArray(4)
            var ok = true
            for (k in 0..3) {
                val x = x0 + d[0] * k; val y = y0 + d[1] * k; val z = z0 + d[2] * k
                if (x !in 0..3 || y !in 0..3 || z !in 0..3) { ok = false; break }
                cells[k] = cell(peg(x, z), y)
            }
            if (!ok) continue
            val mask = cells.fold(0L) { m, c -> m or (1L shl c) }
            if (!seen.add(mask)) continue
            val nz = d.count { it != 0 }
            val kind = when {
                nz == 1 && d[1] != 0 -> Kind.COLUMN
                nz == 1 -> Kind.ROW
                nz == 2 && d[1] == 0 -> Kind.FLAT
                nz == 2 -> Kind.CROSS
                else -> Kind.SPACE
            }
            // cells bottom-up / far-to-near for display
            tmp += cells.sortedWith(compareBy({ layerOf(it) }, { jOf(pegOf(it)) }, { iOf(pegOf(it)) })).toIntArray() to kind
        }
        val order = listOf(Kind.COLUMN, Kind.ROW, Kind.FLAT, Kind.CROSS, Kind.SPACE)
        tmp.sortedWith(compareBy({ order.indexOf(it.second) }, { it.first[0] }, { it.first[3] })).forEachIndexed { i, (c, k) -> out += Line(i, c, k) }
        return out
    }

    /** Bitboards of both sides. */
    data class Board(val amber: Long = 0L, val celadon: Long = 0L) {
        val all: Long get() = amber or celadon
        fun of(side: Int): Long = if (side == AMBER) amber else celadon
        fun height(peg: Int): Int {
            var h = 0
            while (h < 4 && (all shr cell(peg, h)) and 1L == 1L) h++
            return h
        }
        fun canDrop(peg: Int): Boolean = height(peg) < 4
        /** The cell a bead dropped on [peg] lands in, or -1 when the peg is full. */
        fun landing(peg: Int): Int = height(peg).let { if (it >= 4) -1 else cell(peg, it) }
        fun drop(side: Int, peg: Int): Board {
            val c = landing(peg)
            require(c >= 0) { "peg ${pegName(peg)} is full" }
            val b = 1L shl c
            return if (side == AMBER) copy(amber = amber or b) else copy(celadon = celadon or b)
        }
        fun sideAt(cell: Int): Int = when {
            (amber shr cell) and 1L == 1L -> AMBER
            (celadon shr cell) and 1L == 1L -> CELADON
            else -> -1
        }
        fun count(side: Int): Int = java.lang.Long.bitCount(of(side))
        val isFull: Boolean get() = all == FULL
    }

    fun boardOf(moves: List<Int>): Board = moves.foldIndexed(Board()) { k, b, p -> b.drop(k % 2, p) }

    fun sideToMove(moves: List<Int>): Int = moves.size % 2

    fun legalPegs(b: Board): List<Int> = (0 until PEGS).filter { b.canDrop(it) }

    /** First line (table order) that [side] holds completely, or null. */
    fun winLine(b: Board, side: Int): Line? {
        val bits = b.of(side)
        return LINES.firstOrNull { (bits and it.mask) == it.mask }
    }

    /** The outcome of a move list: winner side + line, a draw, or still going. */
    data class Outcome(val winner: Int?, val line: Line?, val draw: Boolean)

    fun outcome(b: Board): Outcome {
        winLine(b, AMBER)?.let { return Outcome(AMBER, it, false) }
        winLine(b, CELADON)?.let { return Outcome(CELADON, it, false) }
        return Outcome(null, null, b.isFull)
    }

    /** Is [cell] empty and the lowest empty cell of its peg (a bead could land there now)? */
    fun playable(b: Board, cell: Int): Boolean = b.landing(pegOf(cell)) == cell

    /**
     * Lines where [side] has three and the fourth cell is playable right now: the threats the other side must block
     * (design gameSpec.lines S08). Returns (line, empty cell).
     */
    fun threats(b: Board, side: Int): List<Pair<Line, Int>> {
        val own = b.of(side); val opp = b.of(other(side))
        val out = ArrayList<Pair<Line, Int>>()
        for (l in LINES) {
            if (opp and l.mask != 0L) continue
            if (java.lang.Long.bitCount(own and l.mask) != 3) continue
            val empty = java.lang.Long.numberOfTrailingZeros(l.mask and own.inv())
            if (playable(b, empty)) out += l to empty
        }
        return out
    }

    /** What a line looks like right now (display: colour by owner, thickness by count). */
    data class LineState(val line: Line, val amber: Int, val celadon: Int) {
        val empty: Boolean get() = amber == 0 && celadon == 0
        val dead: Boolean get() = amber > 0 && celadon > 0
        /** The only owner (or -1 when empty / dead). */
        val owner: Int get() = when { dead || empty -> -1; amber > 0 -> AMBER; else -> CELADON }
        val count: Int get() = maxOf(amber, celadon)
    }

    fun lineState(b: Board, l: Line): LineState =
        LineState(l, java.lang.Long.bitCount(b.amber and l.mask), java.lang.Long.bitCount(b.celadon and l.mask))

    /** The lines through [cell] in table order (design: "经过落点格的线", 4 or 7). */
    fun linesThrough(cell: Int): List<Line> = LINES_THROUGH[cell].map { LINES[it] }
}
