package io.embodit.qubictower.domain

/**
 * Positions for QA screenshots (debug `win <kind>`, `import`) and the rules tests. Built by the same rules engine, so
 * every list is a legal game.
 */
object DebugPositions {
    /** A 64-move game with no four anywhere (found offline: a 32 / 32 colouring without a one-colour line, played bottom-up). */
    const val DRAW_64 = "200101011242526334343545a5a6676879797b8b8899aacbbcdcceddedeeffff"

    fun parse(hex: String): List<Int> = hex.map { it.digitToInt(16) }

    /** A legal move list in which Amber's last move completes a line of [kind] (and nobody won earlier). */
    fun winOf(kind: Qubic.Kind): List<Int> {
        val target = when (kind) {
            Qubic.Kind.COLUMN -> Qubic.LINES.first { it.kind == kind && Qubic.pegOf(it.cells[0]) == Qubic.peg(1, 2) }
            Qubic.Kind.ROW -> Qubic.LINES.first { it.kind == kind && it.cells.all { c -> Qubic.layerOf(c) == 0 && Qubic.jOf(Qubic.pegOf(c)) == 2 && it.cells.map { x -> Qubic.iOf(Qubic.pegOf(x)) }.distinct().size == 4 } }
            Qubic.Kind.FLAT -> Qubic.LINES.first { it.kind == kind && it.cells.all { c -> Qubic.layerOf(c) == 1 } }
            Qubic.Kind.CROSS -> Qubic.LINES.first { it.kind == kind && it.cells.map { c -> Qubic.jOf(Qubic.pegOf(c)) }.distinct() == listOf(3) }
            Qubic.Kind.SPACE -> Qubic.LINES.first { it.kind == kind }
        }
        return build(target) ?: error("no sequence for $kind")
    }

    /** Greedy: Amber fills supports / target cells, Celadon fills supports or harmless far pegs; Amber completes last. */
    private fun build(line: Qubic.Line): List<Int>? {
        val targets = line.cells.toSet()
        val supportPegs = line.cells.map { Qubic.pegOf(it) }.toSet()
        var b = Qubic.Board()
        val moves = ArrayList<Int>()
        for (step in 0 until 64) {
            val side = moves.size % 2
            val legal = Qubic.legalPegs(b)
            fun safe(p: Int): Boolean {
                val nb = b.drop(side, p)
                val out = Qubic.outcome(nb)
                return out.winner == null && !out.draw && Qubic.threats(nb, side).isEmpty()
            }
            val pick: Int? = if (side == Qubic.AMBER) {
                val open = targets.filter { b.sideAt(it) < 0 }
                if (open.size == 1 && Qubic.playable(b, open[0])) { moves += Qubic.pegOf(open[0]); return moves }
                // a target that is playable (not the last one), else a support below a target on any peg, else a far peg
                open.firstOrNull { Qubic.playable(b, it) && open.size > 1 && safeAmber(b, it, targets) }?.let { Qubic.pegOf(it) }
                    ?: legal.firstOrNull { p -> p in supportPegs && b.landing(p) !in targets && safe(p) }
                    ?: legal.firstOrNull { p -> p !in supportPegs && safe(p) }
            } else {
                legal.firstOrNull { p -> p in supportPegs && b.landing(p) !in targets && safe(p) }
                    ?: legal.sortedByDescending { p -> distance(p, supportPegs) }.firstOrNull { p -> p !in supportPegs && safe(p) }
            }
            if (pick == null) return null
            b = b.drop(side, pick)
            moves += pick
        }
        return null
    }

    private fun safeAmber(b: Qubic.Board, cell: Int, targets: Set<Int>): Boolean {
        val nb = b.drop(Qubic.AMBER, Qubic.pegOf(cell))
        return Qubic.outcome(nb).winner == null && Qubic.threats(nb, Qubic.AMBER).all { (l, _) -> l.cells.toSet() == targets }
    }

    private fun distance(p: Int, set: Set<Int>): Int = set.minOf { q -> kotlin.math.abs(Qubic.iOf(p) - Qubic.iOf(q)) + kotlin.math.abs(Qubic.jOf(p) - Qubic.jOf(q)) }
}
