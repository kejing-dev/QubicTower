package io.embodit.qubictower.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.1 精确选择 (board-input README, board-game §15.5): direction -> neighbouring peg as the user sees the tower, at the
 * edges (no wrap) and after each quarter turn; full pegs stepped over; the 0.6 / back-to-centre thumbstick stepper.
 */
class NudgeTest {
    private fun p(s: String) = Qubic.parsePeg(s)!!
    private fun name(peg: Int?) = peg?.let { Qubic.pegName(it) }

    /** Where a peg is seen (x right, z toward the user), straight from the turned layout — the ground truth. */
    private fun seen(peg: Int, k: Int): Pair<Float, Float> = Turntable.rotY(Layout.pegX(peg), Layout.pegZ(peg), k * 90f)

    @Test fun orientation0MatchesTheGrid() {
        // turntable at 0: i grows to the right (+X), j grows toward the user (+Z)
        assertEquals("B2", name(Nudge.adjacent(p("B1"), Dir.DOWN, 0)))
        assertEquals("B1", name(Nudge.adjacent(p("B2"), Dir.UP, 0)))
        assertEquals("C2", name(Nudge.adjacent(p("B2"), Dir.RIGHT, 0)))
        assertEquals("A2", name(Nudge.adjacent(p("B2"), Dir.LEFT, 0)))
    }

    @Test fun everyStepMovesOnePitchTheWayTheUserSees() {
        for (k in 0..3) for (peg in 0 until 16) for (d in Dir.entries) {
            val to = Nudge.adjacent(peg, d, k)
            val (x0, z0) = seen(peg, k)
            val tx = x0 + d.dx * Layout.PITCH; val tz = z0 + d.dz * Layout.PITCH
            val inside = kotlin.math.abs(tx) < 2 * Layout.PITCH && kotlin.math.abs(tz) < 2 * Layout.PITCH
            if (!inside) { assertNull("edge k=$k ${name(peg)} $d", to); continue }
            val (x1, z1) = seen(to!!, k)
            assertEquals("x k=$k ${name(peg)} $d", tx, x1, 1e-4f)
            assertEquals("z k=$k ${name(peg)} $d", tz, z1, 1e-4f)
        }
    }

    @Test fun edgesDoNotWrapAtAnyQuarterTurn() {
        for (k in 0..3) {
            // the peg the user sees at the far-left corner: up and left go nowhere
            val farLeft = (0 until 16).minBy { seen(it, k).let { (x, z) -> x + z } }
            assertNull(Nudge.adjacent(farLeft, Dir.UP, k)); assertNull(Nudge.adjacent(farLeft, Dir.LEFT, k))
            val nearRight = (0 until 16).maxBy { seen(it, k).let { (x, z) -> x + z } }
            assertNull(Nudge.adjacent(nearRight, Dir.DOWN, k)); assertNull(Nudge.adjacent(nearRight, Dir.RIGHT, k))
            // four steps the same way from anywhere always hit the edge (4 x 4, no wrap)
            for (peg in 0 until 16) for (d in Dir.entries) {
                var q: Int? = peg; var n = 0
                while (q != null) { q = Nudge.adjacent(q, d, k); n++ }
                assertTrue(n in 1..4)
            }
        }
    }

    @Test fun directionsFollowTheTurntable() {
        // after one quarter turn (+90°, front edge to the right) the same "right" is a different grid step
        val a = Nudge.adjacent(p("B2"), Dir.RIGHT, 0); val b = Nudge.adjacent(p("B2"), Dir.RIGHT, 1)
        assertTrue(a != b)
        // turning four times is identity; turning twice mirrors (right becomes the grid's left)
        for (peg in 0 until 16) for (d in Dir.entries) assertEquals(Nudge.adjacent(peg, d, 0), Nudge.adjacent(peg, d, 4))
        val mirror = mapOf(Dir.UP to Dir.DOWN, Dir.DOWN to Dir.UP, Dir.LEFT to Dir.RIGHT, Dir.RIGHT to Dir.LEFT)
        for (peg in 0 until 16) for (d in Dir.entries) assertEquals(Nudge.adjacent(peg, d, 0), Nudge.adjacent(peg, mirror.getValue(d), 2))
        // up then down (and left then right) returns, at every quarter turn
        for (k in 0..3) for (peg in 0 until 16) for ((d, back) in mirror) {
            val q = Nudge.adjacent(peg, d, k) ?: continue
            assertEquals(peg, Nudge.adjacent(q, back, k))
        }
    }

    @Test fun fullPegsAreSteppedOverAndTheEdgeStops() {
        val full = setOf(p("C2"))
        assertEquals("D2", name(Nudge.next(p("B2"), Dir.RIGHT, 0) { it !in full }))
        assertNull(Nudge.next(p("C2"), Dir.RIGHT, 0) { it != p("D2") })   // only D2 to the right, and it is full
        assertNull(Nudge.next(p("D2"), Dir.RIGHT, 0) { true })
    }

    @Test fun stickNeedsPoint6AndBackToCentre() {
        val s = StickStepper()
        assertNull(s.feed(0.5f, 0f))
        assertEquals(Dir.RIGHT, s.feed(0.7f, 0.1f))
        assertNull(s.feed(0.95f, 0f))      // held: no auto-repeat
        assertNull(s.feed(0.4f, 0f))       // not back to centre yet
        assertNull(s.feed(0.1f, 0.1f))     // re-armed
        assertEquals(Dir.UP, s.feed(0.1f, 0.8f))
        assertNull(s.feed(0f, 0f))
        assertEquals(Dir.DOWN, s.feed(0.2f, -0.65f))
        assertNull(s.feed(0f, 0f))
        assertEquals(Dir.LEFT, s.feed(-0.9f, 0.5f))   // the larger axis wins
    }
}
