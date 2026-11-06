package io.embodit.qubictower.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** design acceptance M3 (lines on screen) + M9 (push snapping with σ 4 / 8 mm hand jitter). */
class TurntableDisplayTest {
    @Test fun snapNeedsFiftyDegreesToLeaveAQuarter() {
        assertEquals(0, Turntable.snapSteps(0f))
        assertEquals(0, Turntable.snapSteps(44f))
        assertEquals(0, Turntable.snapSteps(50f))
        assertEquals(1, Turntable.snapSteps(51f))
        assertEquals(1, Turntable.snapSteps(90f))
        assertEquals(1, Turntable.snapSteps(140f))
        assertEquals(2, Turntable.snapSteps(141f))
        assertEquals(-1, Turntable.snapSteps(-60f))
        assertEquals(-2, Turntable.snapSteps(-170f))
    }

    @Test fun dragAngleFollowsTheHandAroundTheCentre() {
        // pushing the front edge 2 cm to the right turns the table counter-clockwise from above (+)
        assertTrue(Turntable.dragAngle(0.02f, 0f) > 9f)
        assertTrue(Turntable.dragAngle(-0.02f, 0f) < -9f)
        // a quarter circle of travel = 90°
        val r = Turntable.GRIP_R
        assertEquals(90f, Turntable.dragAngle(r, -r), 0.5f)
    }

    /** Jump rate: releases that land on a different quarter than intended, with σ jitter on the hand position. */
    private fun jumpRate(sigma: Float, intendedDeg: Float, trials: Int = 2000): Double {
        val rnd = Random(7)
        val r = Turntable.GRIP_R
        val want = Turntable.snapSteps(intendedDeg)
        var jumps = 0
        repeat(trials) {
            val a = Math.toRadians(intendedDeg.toDouble())
            val dx = (r * sin(a)).toFloat() + gauss(rnd) * sigma
            val dz = (r * cos(a) - r).toFloat() + gauss(rnd) * sigma
            if (Turntable.snapSteps(Turntable.dragAngle(dx, dz)) != want) jumps++
        }
        return jumps.toDouble() / trials
    }

    private fun gauss(rnd: Random): Float {
        val u = rnd.nextDouble().coerceAtLeast(1e-9); val v = rnd.nextDouble()
        return (kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * cos(2 * Math.PI * v)).toFloat()
    }

    @Test fun jitterDoesNotFlipTheSnap() {
        for (sigma in listOf(0.004f, 0.008f)) for (deg in listOf(0f, 20f, 90f, -90f, 70f, 110f)) {
            val rate = jumpRate(sigma, deg)
            println("JITTER σ=${(sigma * 1000).toInt()} mm push ${deg.toInt()}°: jump rate ${"%.3f".format(rate)}")
            assertTrue("σ $sigma at $deg°: $rate", rate <= 0.10)
        }
    }

    @Test fun aimShowsEveryLineThroughTheLandingCellColouredByOwner() {
        val rnd = Random(3)
        var moves = emptyList<Int>()
        repeat(40) {
            val b = Qubic.boardOf(moves)
            for (p in Qubic.legalPegs(b)) {
                val st = PlayState(phase = Phase.AIMING, config = GameConfig(Mode.PVP), moves = moves, aim = p)
                val beams = Display.beams(st, 0)
                val cell = b.landing(p)
                val through = Qubic.LINES_THROUGH[cell].size
                val block = Display.mustBlock(st).isNotEmpty()
                assertTrue(beams.size <= Display.MAX_BEAMS)
                assertTrue(beams.size == through || (block && beams.size in through..through + 1))
                for (bm in beams.filter { it.tint != Display.Tint.RED }) {
                    val s = Qubic.lineState(b, bm.line)
                    val want = when { s.empty -> Display.Tint.WHITE; s.dead -> Display.Tint.GREY; s.owner == Qubic.AMBER -> Display.Tint.AMBER; else -> Display.Tint.CELADON }
                    assertEquals(want, bm.tint)
                    if (!s.empty && !s.dead) assertEquals(0.002f + 0.001f * s.count, bm.diameter, 1e-6f)
                }
            }
            val legal = Qubic.legalPegs(b)
            val next = legal[rnd.nextInt(legal.size)]
            val nb = b.drop(Qubic.sideToMove(moves), next)
            if (Qubic.outcome(nb).winner != null) return
            moves = moves + next
        }
    }

    @Test fun emptyTowerAimShowsOnlyThinWhiteLines() {
        val st = PlayState(phase = Phase.AIMING, config = GameConfig(), aim = 0)
        val beams = Display.beams(st, 0)
        assertEquals(7, beams.size)
        assertTrue(beams.all { it.tint == Display.Tint.WHITE && it.diameter == 0.002f })
        val edge = PlayState(phase = Phase.AIMING, config = GameConfig(), aim = 1)
        assertEquals(4, Display.beams(edge, 0).size)
    }
}
