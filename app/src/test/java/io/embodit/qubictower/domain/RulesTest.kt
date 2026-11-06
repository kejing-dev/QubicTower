package io.embodit.qubictower.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** design acceptance M1: the line table, every line wins, full peg, full-board draw, bitboards vs cells. */
class RulesTest {
    @Test fun seventySixLinesFourCellsEachThreeHundredFourIncidences() {
        assertEquals(76, Qubic.LINES.size)
        assertTrue(Qubic.LINES.all { it.cells.size == 4 && java.lang.Long.bitCount(it.mask) == 4 })
        assertEquals(76, Qubic.LINES.map { it.mask }.toSet().size)
        assertEquals(304, (0 until 64).sumOf { Qubic.LINES_THROUGH[it].size })
        val seven = (0 until 64).filter { Qubic.LINES_THROUGH[it].size == 7 }
        assertEquals(16, seven.size)
        assertTrue((0 until 64).all { Qubic.LINES_THROUGH[it].size in setOf(4, 7) })
        val byKind = Qubic.LINES.groupingBy { it.kind }.eachCount()
        assertEquals(mapOf(Qubic.Kind.COLUMN to 16, Qubic.Kind.ROW to 32, Qubic.Kind.FLAT to 8, Qubic.Kind.CROSS to 16, Qubic.Kind.SPACE to 4), byKind)
        // the 7-line cells are the 8 corners and the 8 inner (body-centre) cells
        val corners = seven.count { c -> Qubic.layerOf(c) in setOf(0, 3) && Qubic.iOf(Qubic.pegOf(c)) in setOf(0, 3) && Qubic.jOf(Qubic.pegOf(c)) in setOf(0, 3) }
        assertEquals(8, corners)
    }

    /** Every one of the 76 lines can be completed in a legal game and is then reported as the win. */
    @Test fun everyLineWins() {
        for (l in Qubic.LINES) {
            // fill the line's cells with Amber, anything under them with Celadon (column by column); check the winner
            var amber = 0L; var celadon = 0L
            for (c in l.cells) {
                amber = amber or (1L shl c)
                for (below in 0 until Qubic.layerOf(c)) {
                    val u = Qubic.cell(Qubic.pegOf(c), below)
                    if (amber shr u and 1L == 0L) celadon = celadon or (1L shl u)
                }
            }
            val b = Qubic.Board(amber, celadon)
            val w = Qubic.winLine(b, Qubic.AMBER)
            assertNotNull("line ${l.index}", w)
            assertTrue("line ${l.index} -> ${w!!.index}", (b.amber and w.mask) == w.mask)
            assertNull(Qubic.winLine(b, Qubic.CELADON)?.takeIf { java.lang.Long.bitCount(celadon) < 4 })
        }
    }

    @Test fun fiveKindsBuildLegalWinningGames() {
        for (k in Qubic.Kind.entries) {
            val moves = DebugPositions.winOf(k)
            val b = Qubic.boardOf(moves)
            val o = Qubic.outcome(b)
            assertEquals("$k winner", Qubic.AMBER, o.winner)
            assertEquals("$k kind", k, o.line!!.kind)
            // nobody won before the last move
            assertNull(Qubic.outcome(Qubic.boardOf(moves.dropLast(1))).winner)
        }
    }

    @Test fun fullPegIsRefusedAndGravityStacks() {
        var b = Qubic.Board()
        for (k in 0..3) { assertEquals(Qubic.cell(5, k), b.landing(5)); b = b.drop(k % 2, 5) }
        assertFalse(b.canDrop(5))
        assertEquals(-1, b.landing(5))
        assertTrue(runCatching { b.drop(0, 5) }.isFailure)
        assertEquals(15, Qubic.legalPegs(b).size)
    }

    @Test fun sixtyFourMoveDrawHasNoWinner() {
        val moves = DebugPositions.parse(DebugPositions.DRAW_64)
        assertEquals(64, moves.size)
        for (n in 1..64) assertNull("prefix $n", Qubic.outcome(Qubic.boardOf(moves.take(n))).winner)
        val o = Qubic.outcome(Qubic.boardOf(moves))
        assertTrue(o.draw)
        assertTrue(Qubic.legalPegs(Qubic.boardOf(moves)).isEmpty())
    }

    @Test fun bitboardsAgreeWithCells() {
        val moves = DebugPositions.parse(DebugPositions.DRAW_64).take(37)
        val b = Qubic.boardOf(moves)
        val h = IntArray(16)
        moves.forEachIndexed { k, p -> val c = Qubic.cell(p, h[p]++); assertEquals(k % 2, b.sideAt(c)) }
        for (p in 0 until 16) assertEquals(h[p], b.height(p))
        assertEquals(37, java.lang.Long.bitCount(b.all))
        assertEquals(0L, b.amber and b.celadon)
    }

    @Test fun pegNamesRoundTrip() {
        for (p in 0 until 16) assertEquals(p, Qubic.parsePeg(Qubic.pegName(p)))
        assertEquals("A1", Qubic.pegName(0)); assertEquals("D4", Qubic.pegName(15))
    }

    @Test fun threatsNeedAPlayableFourthCell() {
        // Amber has three in the bottom row of peg row j=0 (A1 B1 C1), D1 empty and playable
        val b = Qubic.boardOf(listOf(0, 4, 1, 5, 2))
        val t = Qubic.threats(b, Qubic.AMBER)
        assertEquals(1, t.size)
        assertEquals(Qubic.cell(3, 0), t[0].second)
        // a three one layer up whose fourth cell has nothing under it is dormant
        val b2 = Qubic.boardOf(listOf(0, 0, 1, 1, 2, 2, 8, 8))  // celadon has A1L1 B1L1 C1L1; D1 layer 0 empty
        assertTrue(Qubic.threats(b2, Qubic.CELADON).none { (_, c) -> c == Qubic.cell(3, 1) })
    }

    @Test fun saveCodecRoundTrips() {
        val g = SavedGame(GameConfig(Mode.PVP, 2, 1), listOf(5, 6, 5, 15), listOf(listOf(3, 4), listOf(9)), 3, true)
        assertEquals(g, SaveCodec.decode(SaveCodec.encode(g)))
        assertNull(SaveCodec.decode("qt1|PVE|1|0|55555|||0|0|0".replace("|||", "||0|")))
    }
}
