package io.embodit.qubictower.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.random.Random

/**
 * design acceptance M2 (AI ladder, 200 games each, fixed seeds, colours alternate, a draw = half a point):
 * HARD vs SERIOUS >= 85 %, SERIOUS vs EASY >= 80 %, EASY vs "take a four + block every four + random" 35–60 %.
 * HARD runs at a fixed depth here (JVM time, not headset time); its real 1.5 s budget is measured on the headset.
 */
class AiStrengthTest {
    companion object {
        const val GAMES = 200
        /**
         * HARD in the ladder: a fixed node budget instead of the 1.5 s clock (deterministic; ~0.25 s on this Mac, an
         * estimate of what the headset searches in 1.5 s; the real depth / time is measured on the headset).
         */
        const val HARD_NODES = 400_000L
    }

    private fun script(b: Qubic.Board, side: Int, rnd: Random): Int {
        val legal = Qubic.legalPegs(b)
        legal.firstOrNull { Qubic.winLine(b.drop(side, it), side) != null }?.let { return it }
        Qubic.threats(b, Qubic.other(side)).firstOrNull()?.let { return Qubic.pegOf(it.second) }
        return legal[rnd.nextInt(legal.size)]
    }

    /** 1 = A wins, 0.5 draw, 0 = B wins. */
    private fun game(a: Int, bLevel: Int, aFirst: Boolean, seed: Int): Double {
        val ea = Engine(16); val eb = Engine(16)
        val rnd = Random(seed)
        var board = Qubic.Board()
        var side = Qubic.AMBER
        val aSide = if (aFirst) Qubic.AMBER else Qubic.CELADON
        while (true) {
            val lvl = if (side == aSide) a else bLevel
            val e = if (side == aSide) ea else eb
            val p = when (lvl) {
                0 -> script(board, side, rnd)
                Engine.HARD -> e.choose(board, side, lvl, rnd, timeMs = 60_000, nodeLimit = HARD_NODES).peg
                else -> e.choose(board, side, lvl, rnd, timeMs = 60_000).peg
            }
            board = board.drop(side, p)
            val o = Qubic.outcome(board)
            if (o.winner != null) return if (o.winner == aSide) 1.0 else 0.0
            if (o.draw) return 0.5
            side = Qubic.other(side)
        }
    }

    private fun ladder(a: Int, b: Int, seed0: Int): Pair<Double, IntArray> {
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceAtMost(8))
        val fs: List<Future<Double>> = (0 until GAMES).map { i -> pool.submit<Double> { game(a, b, i % 2 == 0, seed0 + i) } }
        val r = fs.map { it.get() }
        pool.shutdown()
        val wdl = intArrayOf(r.count { it == 1.0 }, r.count { it == 0.5 }, r.count { it == 0.0 })
        val first = r.filterIndexed { i, _ -> i % 2 == 0 }.sum(); val second = r.filterIndexed { i, _ -> i % 2 == 1 }.sum()
        println("LADDER $a vs $b: as first ${"%.1f".format(first)} / ${GAMES / 2}, as second ${"%.1f".format(second)} / ${GAMES / 2}")
        return r.sum() / GAMES to wdl
    }

    @Test fun hardBeatsSerious() {
        val (score, wdl) = ladder(Engine.HARD, Engine.SERIOUS, 1000)
        println("LADDER hard vs serious: ${"%.1f".format(score * 100)} % (W/D/L ${wdl.joinToString("/")})")
        assertTrue("hard vs serious ${score * 100} %", score >= 0.85)
    }

    @Test fun seriousBeatsEasy() {
        val (score, wdl) = ladder(Engine.SERIOUS, Engine.EASY, 2000)
        println("LADDER serious vs easy: ${"%.1f".format(score * 100)} % (W/D/L ${wdl.joinToString("/")})")
        assertTrue("serious vs easy ${score * 100} %", score >= 0.80)
    }

    @Test fun easyIsBeatableAndCanWin() {
        val (score, wdl) = ladder(Engine.EASY, 0, 3000)
        println("LADDER easy vs script: ${"%.1f".format(score * 100)} % (W/D/L ${wdl.joinToString("/")})")
        assertTrue("easy vs script ${score * 100} %", score in 0.35..0.60)
    }

    @Test fun engineTakesWinsAndBlocks() {
        // Amber A1 B1 C1 (layer 0), Celadon to move must block D1; Amber to move wins D1
        val b = Qubic.boardOf(listOf(0, 4, 1, 5, 2))
        for (lvl in listOf(Engine.SERIOUS, Engine.HARD)) assertEquals(3, Engine().choose(b, Qubic.CELADON, lvl, Random(1), maxDepth = 4, timeMs = 10_000).peg)
        val b2 = Qubic.boardOf(listOf(0, 4, 1, 5, 2, 6))
        for (lvl in Engine.EASY..Engine.HARD) assertEquals(3, Engine().choose(b2, Qubic.AMBER, lvl, Random(1), maxDepth = 4, timeMs = 10_000).peg)
    }

    @Test fun searchStopsWithin50msWhenCancelled() {
        val e = Engine()
        var stop = false
        val t = Thread { Thread.sleep(30); stop = true }
        t.start()
        val t0 = System.nanoTime()
        e.choose(Qubic.Board(), Qubic.AMBER, Engine.HARD, Random(1), cancelled = { stop }, timeMs = 10_000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("stopped after $ms ms", ms < 30 + 50)
    }

    @Test fun hardThinkTimeOnThisMachine() {
        // informative only (the 1.5 s budget is measured on the headset): mid-game, full budget
        val b = Qubic.boardOf(DebugPositions.parse(DebugPositions.DRAW_64).take(10))
        val r = Engine().choose(b, Qubic.AMBER, Engine.HARD, Random(1))
        println("HARD mid-game (JVM): depth ${r.depth} in ${r.ms} ms, ${r.nodes} nodes")
        assertTrue(r.ms <= 2500)
    }
}
