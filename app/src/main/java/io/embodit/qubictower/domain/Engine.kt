package io.embodit.qubictower.domain

import io.embodit.qubictower.domain.Qubic.Board
import kotlin.random.Random

/**
 * Qubic AI (design gameSpec.ai): 64-bit bitboards, the 76 line masks, negamax alpha-beta with iterative deepening, a
 * transposition table and move ordering (win > block > table move > centre). Three levels:
 *  - EASY "只看一层": wins if it can, blocks the opponent's immediate four but misses cross-layer / space diagonals 40 %
 *    of the time, otherwise a random peg weighted toward the centre four.
 *  - SERIOUS "看四步": depth 4 / 300 ms, line evaluation; 20 % of the moves pick from the top two (never when the best
 *    move wins or the second one loses outright).
 *  - HARD "它让你先": iterative deepening, 1.5 s, target depth 6+.
 * One Engine instance is not thread-safe (its table); the flow keeps one for the AI and one for hints.
 */
class Engine(ttBits: Int = 18) {
    companion object {
        const val EASY = 1
        const val SERIOUS = 2
        const val HARD = 3
        const val WIN = 1_000_000
        const val SERIOUS_MS = 300L
        const val HARD_MS = 1500L
        const val SERIOUS_DEPTH = 4
        const val HARD_MAX_DEPTH = 12
        /** Threat-space search depth (own threes in a row, each answered by the forced block). */
        const val VCF_DEPTH = 10
        /** Line weights x10 for 0..3 own beads (design eval 0 / 1 / 3 / 9). */
        private val W = intArrayOf(0, 10, 30, 90)
        /** A three whose empty cell is not playable yet counts 0.3 (design eval). */
        private const val W3_DORMANT = 27
        /** Peg order: centre four, corners, edges (design: 中间四根最值钱，角上次之). */
        val PEG_ORDER = intArrayOf(5, 6, 9, 10, 0, 3, 12, 15, 1, 2, 4, 7, 8, 11, 13, 14)
        private val EASY_WEIGHT = IntArray(16) { p -> when (p) { 5, 6, 9, 10 -> 4; 0, 3, 12, 15 -> 2; else -> 1 } }
        fun levelOk(l: Int) = l in EASY..HARD
    }

    data class Result(val peg: Int, val score: Int, val depth: Int, val nodes: Long, val ms: Long)

    private val size = 1 shl ttBits
    private val mask = size - 1
    private val keyA = LongArray(size)
    private val keyC = LongArray(size)
    private val data = IntArray(size)   // score (16 bits signed, clipped) | depth 5 | flag 2 | peg+1 5
    private val scoreT = IntArray(size)
    private var nodes = 0L
    private var deadline = Long.MAX_VALUE
    private var cancelled: () -> Boolean = { false }
    private var aborted = false
    /** Deterministic budget (tests: a node count standing in for the headset's 1.5 s). */
    private var nodeLimit = Long.MAX_VALUE

    /** Picks a peg for [side] (the side to move). [timeMs] / [maxDepth] override the level's limits (tests). */
    fun choose(
        b: Board, side: Int, level: Int, rnd: Random,
        cancelled: () -> Boolean = { false }, timeMs: Long? = null, maxDepth: Int? = null, nodeLimit: Long = Long.MAX_VALUE,
    ): Result {
        val t0 = System.nanoTime()
        val legal = Qubic.legalPegs(b)
        require(legal.isNotEmpty()) { "no legal peg" }
        nodes = 0
        this.nodeLimit = nodeLimit
        when (level) {
            EASY -> return Result(easy(b, side, rnd), 0, 1, 0, (System.nanoTime() - t0) / 1_000_000)
            SERIOUS -> {
                val r = search(b, side, maxDepth ?: SERIOUS_DEPTH, timeMs ?: SERIOUS_MS, cancelled, rootScores = true)
                var pick = r.first
                val scores = r.second
                if (scores.size >= 2 && rnd.nextFloat() < 0.20f) {
                    val sorted = scores.entries.sortedByDescending { it.value }
                    val best = sorted[0]; val second = sorted[1]
                    if (best.value < WIN / 2 && second.value > -WIN / 2) pick = if (rnd.nextBoolean()) best.key else second.key
                }
                return Result(pick, scores[pick] ?: 0, r.third, nodes, (System.nanoTime() - t0) / 1_000_000)
            }
            else -> {
                deadline = System.nanoTime() + (timeMs ?: HARD_MS) * 1_000_000
                this.cancelled = cancelled; aborted = false
                // threat-space first (how Qubic is won): a forced win by a chain of threes, then avoid moves that hand the
                // opponent one; the alpha-beta search picks among the safe moves
                val mustBlock = Qubic.threats(b, Qubic.other(side)).map { Qubic.pegOf(it.second) }.distinct()
                if (mustBlock.isEmpty() && Qubic.threats(b, side).isEmpty()) {
                    val win = vcf(b, side, VCF_DEPTH)
                    if (win >= 0) return Result(win, WIN / 2, VCF_DEPTH, nodes, (System.nanoTime() - t0) / 1_000_000)
                }
                val safe = legal.filter { p -> mustBlock.isEmpty() || p in mustBlock }.filter { p ->
                    val nb = b.drop(side, p)
                    Qubic.winLine(nb, side) != null || vcf(nb, Qubic.other(side), VCF_DEPTH - 2) < 0
                }
                val r = search(b, side, maxDepth ?: HARD_MAX_DEPTH, timeMs ?: HARD_MS, cancelled, rootScores = false, allowed = safe.ifEmpty { null })
                return Result(r.first, r.second[r.first] ?: 0, r.third, nodes, (System.nanoTime() - t0) / 1_000_000)
            }
        }
    }

    /** Hint engine (design: SERIOUS depth, deterministic, never the HARD search). */
    fun hint(b: Board, side: Int, cancelled: () -> Boolean = { false }): Int =
        search(b, side, SERIOUS_DEPTH, SERIOUS_MS, cancelled, rootScores = false).first

    // ---------------------------------------------------------------- EASY

    private fun easy(b: Board, side: Int, rnd: Random): Int {
        val legal = Qubic.legalPegs(b)
        // 1. a win
        for (p in legal) if (Qubic.winLine(b.drop(side, p), side) != null) return p
        // 2. block what it sees (cross-layer / space diagonals are missed 40 % of the time)
        for ((line, cell) in Qubic.threats(b, Qubic.other(side))) {
            val seen = when (line.kind) {
                Qubic.Kind.CROSS, Qubic.Kind.SPACE -> rnd.nextFloat() >= 0.40f
                else -> true
            }
            if (seen) return Qubic.pegOf(cell)
        }
        // 3. random, weighted to the centre
        val total = legal.sumOf { EASY_WEIGHT[it] }
        var r = rnd.nextInt(total)
        for (p in legal) { r -= EASY_WEIGHT[p]; if (r < 0) return p }
        return legal.last()
    }

    // ---------------------------------------------------------------- search

    /** Returns (best peg, root scores when asked, completed depth). */
    private fun search(b: Board, side: Int, maxDepth: Int, timeMs: Long, cancel: () -> Boolean, rootScores: Boolean, allowed: List<Int>? = null): Triple<Int, Map<Int, Int>, Int> {
        deadline = System.nanoTime() + timeMs * 1_000_000
        cancelled = cancel
        aborted = false
        val legal = Qubic.legalPegs(b)
        // immediate win / single forced block need no search
        for (p in legal) if (Qubic.winLine(b.drop(side, p), side) != null) return Triple(p, mapOf(p to WIN), 1)
        var best = orderedMoves(b, side, -1).first { allowed == null || it in allowed }
        var bestScores: Map<Int, Int> = mapOf(best to 0)
        var done = 0
        val empties = 64 - java.lang.Long.bitCount(b.all)
        for (depth in 1..minOf(maxDepth, empties)) {
            val scores = LinkedHashMap<Int, Int>()
            var alpha = -WIN - 1
            var bestHere = -1
            var bestScore = -WIN - 1
            val moves = orderedMoves(b, side, best).filter { allowed == null || it in allowed }
            for (p in moves) {
                val nb = b.drop(side, p)
                val s = if (rootScores) -negamax(nb, Qubic.other(side), depth - 1, -WIN - 1, WIN + 1, 1, Qubic.cell(p, b.height(p)))
                        else -negamax(nb, Qubic.other(side), depth - 1, -WIN - 1, -alpha, 1, Qubic.cell(p, b.height(p)))
                if (aborted) break
                scores[p] = s
                if (s > bestScore) { bestScore = s; bestHere = p }
                if (s > alpha) alpha = s
            }
            if (aborted) {
                // an unfinished depth only counts when no depth finished at all
                if (done == 0 && bestHere >= 0) { best = bestHere; bestScores = scores }
                break
            }
            best = bestHere
            bestScores = scores
            done = depth
            if (bestScore >= WIN - 100 || bestScore <= -WIN + 100) break   // decided
        }
        return Triple(best, bestScores, done)
    }

    /** Pegs ordered: wins, blocks, the table's / caller's move, then centre-first. */
    private fun orderedMoves(b: Board, side: Int, first: Int): List<Int> {
        val legal = BooleanArray(16) { b.canDrop(it) }
        val out = ArrayList<Int>(16)
        fun add(p: Int) { if (p in 0..15 && legal[p] && p !in out) out += p }
        for (p in PEG_ORDER) if (legal[p] && Qubic.winLine(b.drop(side, p), side) != null) add(p)
        for ((_, c) in Qubic.threats(b, Qubic.other(side))) add(Qubic.pegOf(c))
        add(first)
        for (p in PEG_ORDER) add(p)
        return out
    }

    private fun negamax(b: Board, side: Int, depth: Int, alphaIn: Int, beta: Int, ply: Int, lastCell: Int): Int {
        // the previous mover (other(side)) may have just completed a line through lastCell
        val prev = b.of(Qubic.other(side))
        for (li in Qubic.LINES_THROUGH[lastCell]) { val m = Qubic.LINES[li].mask; if (prev and m == m) return -(WIN - ply) }
        if (b.isFull) return 0
        nodes++
        if ((nodes and 1023L) == 0L && (System.nanoTime() > deadline || nodes > nodeLimit || cancelled())) aborted = true
        if (aborted) return 0
        // threat logic: a playable three of the side to move wins next move; two different playable threes of the
        // opponent cannot both be blocked; one must be blocked now (forced move, searched without using depth)
        if (Qubic.threats(b, side).isNotEmpty()) return WIN - ply - 1
        val oppCells = Qubic.threats(b, Qubic.other(side)).map { it.second }.distinct()
        if (oppCells.size >= 2) return -(WIN - ply - 2)
        if (oppCells.size == 1 && ply < 60) {
            val p = Qubic.pegOf(oppCells[0])
            return -negamax(b.drop(side, p), Qubic.other(side), depth, -beta, -alphaIn, ply + 1, oppCells[0])
        }
        if (depth <= 0) return eval(b, side)

        var alpha = alphaIn
        val h = hash(b.amber, b.celadon)
        val slot = (h and mask.toLong()).toInt()
        var ttMove = -1
        if (keyA[slot] == b.amber && keyC[slot] == b.celadon && data[slot] != 0) {
            val d = data[slot]
            val tDepth = (d shr 7) and 31
            val flag = (d shr 5) and 3
            ttMove = (d and 31) - 1
            val ts = scoreT[slot]
            if (tDepth >= depth) {
                when (flag) {
                    0 -> return ts
                    1 -> if (ts >= beta) return ts
                    2 -> if (ts <= alpha) return ts
                }
            }
        }
        // forced: if the opponent threatens a playable four, only blocks (or our own wins) make sense
        var best = -WIN - 1
        var bestMove = -1
        for (p in orderedMoves(b, side, ttMove)) {
            val c = Qubic.cell(p, b.height(p))
            val s = -negamax(b.drop(side, p), Qubic.other(side), depth - 1, -beta, -alpha, ply + 1, c)
            if (aborted) return 0
            if (s > best) { best = s; bestMove = p }
            if (s > alpha) alpha = s
            if (alpha >= beta) break
        }
        val flag = when { best <= alphaIn -> 2; best >= beta -> 1; else -> 0 }
        keyA[slot] = b.amber; keyC[slot] = b.celadon
        scoreT[slot] = best
        data[slot] = (depth.coerceAtMost(31) shl 7) or (flag shl 5) or (bestMove + 1) or (1 shl 13)
        return best
    }

    /**
     * Victory by continuous threats: [side] (to move, not facing a threat) makes a playable three each move, the
     * opponent must block, until two threes are open at once. Returns the first peg of such a win, or -1.
     */
    private fun vcf(b: Board, side: Int, depth: Int): Int {
        if (depth <= 0 || aborted) return -1
        val opp = Qubic.other(side)
        for (p in PEG_ORDER) {
            if (!b.canDrop(p)) continue
            nodes++
            if ((nodes and 1023L) == 0L && (System.nanoTime() > deadline || nodes > nodeLimit || cancelled())) { aborted = true; return -1 }
            val nb = b.drop(side, p)
            if (Qubic.winLine(nb, side) != null) return p
            if (Qubic.threats(nb, opp).isNotEmpty()) continue
            val cells = Qubic.threats(nb, side).map { it.second }.distinct()
            if (cells.isEmpty()) continue
            if (cells.size >= 2) return p
            val reply = nb.drop(opp, Qubic.pegOf(cells[0]))
            if (Qubic.winLine(reply, opp) != null || Qubic.threats(reply, opp).isNotEmpty()) continue
            if (vcf(reply, side, depth - 1) >= 0) return p
        }
        return -1
    }

    /** Line evaluation from [side]'s point of view (design gameSpec.ai.eval). */
    fun eval(b: Board, side: Int): Int {
        var s = 0
        val a = b.amber; val c = b.celadon
        for (l in Qubic.LINES) {
            val m = l.mask
            val na = java.lang.Long.bitCount(a and m)
            val nc = java.lang.Long.bitCount(c and m)
            if (na > 0 && nc > 0) continue
            if (na > 0) s += weight(b, l, na, a) else if (nc > 0) s -= weight(b, l, nc, c)
        }
        return if (side == Qubic.AMBER) s else -s
    }

    private fun weight(b: Board, l: Qubic.Line, n: Int, own: Long): Int {
        if (n < 3) return W[n]
        val empty = java.lang.Long.numberOfTrailingZeros(l.mask and own.inv())
        return if (Qubic.playable(b, empty)) W[3] else W3_DORMANT
    }

    private fun hash(a: Long, c: Long): Long {
        var h = a * -0x61c8864680b583ebL xor (c * 0x5851f42d4c957f2dL)
        h = h xor (h ushr 29)
        h *= -0x40a7b892e31b1a47L
        return h xor (h ushr 32)
    }

    fun clearTable() { java.util.Arrays.fill(data, 0) }
}
