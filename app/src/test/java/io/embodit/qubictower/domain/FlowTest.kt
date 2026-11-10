package io.embodit.qubictower.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Flow rules: two-step drop, refusals (M1), commit-before-animation (M4), input lock per turn (M9), undo / redo, turns. */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowTest {
    private class MemStore : GameStore {
        var saved: SavedGame? = null
        override fun load() = saved
        override fun save(game: SavedGame) { saved = game }
        override fun clear() { saved = null }
    }

    private class Harness(scope: TestScope, val store: MemStore = MemStore(), var settings: Settings = Settings(firstRunDone = true, litDone = true)) {
        val sounds = ArrayList<Sound>()
        val logs = ArrayList<String>()
        val flow = GameFlow(
            scope = scope.backgroundScope,
            engineDispatcher = StandardTestDispatcher(scope.testScheduler),
            clock = { scope.testScheduler.currentTime },
            store = store,
            settings = { settings },
            onSettings = { settings = it },
            onSound = { s, _ -> sounds += s },
            log = { logs += it },
            hardDepthCap = 3,
        )
        val st get() = flow.state.value
    }

    private fun TestScope.dropAndLand(h: Harness, peg: Int) {
        h.flow.tapPeg(peg); runCurrent()
        h.flow.tapPeg(peg); runCurrent()
        advanceTimeBy(Layout.dropMs(0) + 20); runCurrent()
    }

    @Test fun firstRunOpensTheDefaultGameWithTheRulesPage() = runTest {
        val h = Harness(this, settings = Settings())
        h.flow.start(); runCurrent()
        assertTrue(h.st.welcome)
        assertEquals(Phase.IDLE, h.st.phase)
        assertEquals(GameConfig(Mode.PVE, Engine.EASY, Qubic.AMBER), h.st.config)
        assertTrue(h.st.lit)
        h.flow.tapPeg(5); runCurrent()
        assertFalse(h.st.welcome)
        assertTrue(h.settings.firstRunDone)
    }

    @Test fun twoStepDropAimMoveCancelAndDrop() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.PVP)); runCurrent()
        assertEquals(32, h.st.trayCount(0))
        h.flow.tapPeg(5); runCurrent()
        assertEquals(Phase.AIMING, h.st.phase); assertEquals(5, h.st.aim)
        assertEquals(31, h.st.trayCount(0))
        h.flow.tapPeg(6); runCurrent()
        assertEquals(6, h.st.aim)
        h.flow.tapPlinth(); runCurrent()
        assertEquals(Phase.IDLE, h.st.phase); assertNull(h.st.aim)
        assertEquals(32, h.st.trayCount(0))
        h.flow.tapPeg(6); runCurrent(); h.flow.tapPeg(6); runCurrent()
        assertEquals(Phase.DROPPING, h.st.phase)
        // committed and saved before the bead moves (M4)
        assertEquals(listOf(6), h.store.saved!!.moves)
        assertEquals(31, h.st.trayCount(0))
        // a pinch while it falls does nothing
        h.flow.tapPeg(7); runCurrent()
        assertNull(h.st.aim)
        advanceTimeBy(Layout.fallMs(0) + 5); runCurrent()
        assertEquals(Phase.IDLE, h.st.phase)
        assertEquals(Qubic.CELADON, h.st.side)
        assertNotNull(h.st.linger)
    }

    @Test fun humanInputLockIsUnder400msAndTheAiTurnUnder700ms() = runTest {
        val h = Harness(this)
        assertTrue((0..3).all { Layout.fallMs(it) <= 400 })
        h.flow.newGame(GameConfig(Mode.PVE, Engine.SERIOUS, Qubic.AMBER)); runCurrent()
        h.flow.tapPeg(5); runCurrent(); h.flow.tapPeg(5); runCurrent()
        val t0 = currentTime
        while (h.st.phase == Phase.DROPPING) { advanceTimeBy(5); runCurrent() }
        assertTrue("human lock ${currentTime - t0}", currentTime - t0 <= 400)
        assertEquals(Phase.THINKING, h.st.phase)
        // AI: think >= 400 ms (overlapping the line linger), then telegraph 300 + fall <= 384 locked
        while (h.st.aiAim == null) { advanceTimeBy(5); runCurrent() }
        val t1 = currentTime
        while (h.st.phase != Phase.IDLE) { advanceTimeBy(5); runCurrent() }
        assertTrue("ai lock ${currentTime - t1}", currentTime - t1 <= 700)
        assertEquals(2, h.st.moves.size)
    }

    @Test fun fullPegBusyTurningAndGameOverAreRefused() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.PVP)); runCurrent()
        repeat(4) { dropAndLand(h, 5) }
        h.flow.tapPeg(5); runCurrent()
        assertEquals(AlertKind.FULL, h.st.alert?.kind)
        assertNull(h.st.aim)
        assertTrue(h.sounds.contains(Sound.ILLEGAL))
        // during a button turn (S14) a pinch is ignored
        h.flow.turnButton(1); runCurrent()
        assertTrue(h.st.buttonTurning)
        h.flow.tapPeg(0); runCurrent()
        assertNull(h.st.aim)
        advanceTimeBy(Turntable.BUTTON_MS + 10); runCurrent()
        assertFalse(h.st.buttonTurning)
        // game over: no more drops
        h.flow.importMoves(DebugPositions.winOf(Qubic.Kind.ROW)); runCurrent()
        assertEquals(Phase.RESULT, h.st.phase)
        h.flow.tapPeg(0); runCurrent()
        assertEquals(AlertKind.OVER, h.st.alert?.kind)
        assertEquals(Phase.RESULT, h.st.phase)
    }

    @Test fun thinkingRefusesPinchesAndUndoThereTakesBackOnlyYourMove() = runTest {
        val h = Harness(this)
        h.flow.holdThinking = true
        h.flow.newGame(GameConfig(Mode.PVE, Engine.EASY, Qubic.AMBER)); runCurrent()
        dropAndLand(h, 5)
        assertEquals(Phase.THINKING, h.st.phase)
        h.flow.tapPeg(6); runCurrent()
        assertNull(h.st.aim)
        h.flow.requestUndo(); runCurrent()
        assertEquals(Phase.IDLE, h.st.phase)
        assertTrue(h.st.moves.isEmpty())
        assertEquals(listOf(listOf(5)), h.st.redo)
    }

    @Test fun undoTakesBackAWholeRoundAndRedoReplaysItWithoutSearching() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.PVE, Engine.EASY, Qubic.AMBER)); runCurrent()
        dropAndLand(h, 5)
        advanceTimeBy(3000); runCurrent()
        assertEquals(2, h.st.moves.size)
        val ai = h.st.moves[1]
        h.flow.requestUndo(); runCurrent()
        assertTrue(h.st.moves.isEmpty())
        assertEquals(2, h.st.returns.size)
        assertEquals(Qubic.Board(), h.st.board)
        h.flow.redo(); runCurrent()
        assertEquals(listOf(5, ai), h.st.moves)
        assertEquals(Phase.IDLE, h.st.phase)
        // a new drop clears the redo stack
        h.flow.requestUndo(); runCurrent(); advanceTimeBy(1000); runCurrent()
        dropAndLand(h, 0)
        assertTrue(h.st.redo.isEmpty())
    }

    @Test fun twoPlayerUndoAsksFirst() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.PVP)); runCurrent()
        dropAndLand(h, 5); dropAndLand(h, 6)
        h.flow.requestUndo(); runCurrent()
        assertEquals(ConfirmKind.UNDO_2P, h.st.confirm)
        assertEquals("S13", h.st.stateId)
        h.flow.tapPeg(0); runCurrent()
        assertNull(h.st.aim)
        h.flow.confirm(); runCurrent()
        assertEquals(listOf(5), h.st.moves)
    }

    @Test fun turnsAreSavedBeforeTheyMoveAndRestoreAfterAKill() = runTest {
        val store = MemStore()
        val h = Harness(this, store)
        h.flow.newGame(GameConfig(Mode.PVP)); runCurrent()
        dropAndLand(h, 5)
        h.flow.turnButton(1); runCurrent()
        assertEquals(1, store.saved!!.orientation)
        h.flow.pushRelease(90f, -60f); runCurrent()
        assertEquals(0, store.saved!!.orientation)
        h.flow.tapPeg(6); runCurrent(); h.flow.tapPeg(6); runCurrent()
        // killed while the bead falls: a fresh flow on the same store has the move and the orientation
        val h2 = Harness(this, store)
        h2.flow.start(); runCurrent()
        assertEquals(listOf(5, 6), h2.st.moves)
        assertEquals(0, h2.st.orientation)
        assertEquals("S15", h2.st.stateId)
    }

    @Test fun mustBlockShowsOnAllLevelsAndAiWinHasNoChord() = runTest {
        val h = Harness(this)
        // Celadon (AI) has three in a row on the bottom far row, Amber to move must block D1
        h.flow.importMoves(listOf(4, 0, 9, 1, 14, 2), GameConfig(Mode.PVE, Engine.EASY, Qubic.AMBER)); runCurrent()
        assertEquals(1, Display.mustBlock(h.st).size)
        assertTrue(Display.beams(h.st, currentTime).any { it.tint == Display.Tint.RED })
        // ignore it: the AI takes the four, no win chord
        h.flow.tapPeg(15); runCurrent(); h.flow.tapPeg(15); runCurrent()
        advanceTimeBy(5000); runCurrent()
        assertEquals(Qubic.CELADON, h.st.result?.winner)
        advanceTimeBy(1000); runCurrent()
        assertTrue(h.sounds.contains(Sound.NOTE))
        assertFalse(h.sounds.contains(Sound.WIN))
    }

    @Test fun againSweepsTheTowerThenStarts() = runTest {
        val h = Harness(this)
        h.flow.importMoves(DebugPositions.winOf(Qubic.Kind.COLUMN), GameConfig(Mode.PVP)); runCurrent()
        assertEquals(Phase.RESULT, h.st.phase)
        h.flow.again(); runCurrent()
        assertEquals(Phase.CLEARING, h.st.phase)
        assertEquals(7, h.st.clear!!.items.size)
        assertEquals(32, h.st.trayCount(0))
        advanceTimeBy(Layout.CLEAR_TOTAL_MS + 50); runCurrent()
        assertEquals(Phase.IDLE, h.st.phase)
        assertNull(h.st.clear)
        // every flight ends by 1.0 s and the slots are distinct per side
        val c = Harness(this).also { it.flow.importMoves(DebugPositions.parse(DebugPositions.DRAW_64).take(40), GameConfig(Mode.PVP)) }
        runCurrent(); c.flow.again(); runCurrent()
        val items = c.st.clear!!.items
        assertTrue(items.all { it.startedAt + it.flyMs - c.st.clear!!.startedAt <= Layout.CLEAR_TOTAL_MS })
        for (side in 0..1) assertEquals(items.count { it.side == side }, items.filter { it.side == side }.map { it.slot }.toSet().size)
    }

    @Test fun hardAlwaysLetsYouStartAndRandomSeatIsResolvedOnce() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameSetup(Mode.PVE, Engine.HARD, SeatPick.CELADON)); runCurrent()
        assertEquals(Qubic.AMBER, h.st.config!!.human)
        assertEquals(Phase.IDLE, h.st.phase)
        h.flow.newGame(GameSetup(Mode.PVE, Engine.EASY, SeatPick.CELADON)); runCurrent()
        assertEquals(Qubic.CELADON, h.st.config!!.human)
        assertEquals(Phase.THINKING, h.st.phase)
    }

    @Test fun hintComesFromTheSeriousSearch() = runTest {
        val h = Harness(this)
        h.flow.importMoves(listOf(0, 4, 1, 5, 2), GameConfig(Mode.PVP)); runCurrent()
        h.flow.setHint(true); runCurrent(); advanceUntilIdle()
        assertEquals(3, h.st.hint)   // Celadon must block D1
    }

    /** v0.1.1 精确选择: select silently, nudge in the confirm phase (view-relative, after a turn too), confirm drops, cancel. */
    @Test fun confirmPhaseNudgeConfirmAndCancel() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.PVP)); runCurrent()
        val B2 = Qubic.parsePeg("B2")!!
        // nothing selected: the stick / arrows do nothing
        assertFalse(h.flow.nudge(Dir.RIGHT, "test")); assertEquals(Phase.IDLE, h.st.phase)
        h.sounds.clear()
        h.flow.tapPeg(B2); runCurrent()
        assertEquals(Phase.AIMING, h.st.phase)
        assertTrue(h.flow.nudge(Dir.RIGHT, "test")); runCurrent()
        assertEquals("C2", Qubic.pegName(h.st.aim!!))
        assertTrue(h.flow.nudge(Dir.UP, "test")); runCurrent()
        assertEquals("C1", Qubic.pegName(h.st.aim!!))
        assertFalse(h.flow.nudge(Dir.UP, "test"))               // the far edge: stays
        assertEquals("C1", Qubic.pegName(h.st.aim!!))
        assertFalse(h.flow.canNudge(Dir.UP)); assertTrue(h.flow.canNudge(Dir.DOWN))
        // D51: selecting, moving the selection and hitting the edge are silent
        assertTrue("selection sounds ${h.sounds}", h.sounds.isEmpty())
        // a quarter turn: the same "right" now follows the tower as seen
        h.flow.turnButton(1); advanceTimeBy(Turntable.BUTTON_MS + 50); runCurrent()
        val before = h.st.aim!!
        assertTrue(h.flow.nudge(Dir.RIGHT, "test")); runCurrent()
        assertEquals(Nudge.adjacent(before, Dir.RIGHT, h.st.orientation), h.st.aim)
        // cancel: back to nothing selected, bead home, silent
        h.sounds.clear()
        h.flow.cancelSelection("test"); runCurrent()
        assertEquals(Phase.IDLE, h.st.phase); assertNull(h.st.aim); assertEquals(32, h.st.trayCount(0))
        assertTrue(h.sounds.isEmpty())
        // select again and confirm = the drop (and only the drop makes sound: ticks + land)
        h.flow.tapPeg(B2); runCurrent()
        h.flow.confirmAim("test"); runCurrent()
        assertEquals(Phase.DROPPING, h.st.phase); assertEquals(listOf(B2), h.st.moves)
        advanceTimeBy(Layout.dropMs(0) + 20); runCurrent()
        assertTrue(h.sounds.contains(Sound.LAND))
        assertTrue(h.sounds.all { it == Sound.LAND || it == Sound.TICK })
        // confirm with nothing selected does nothing
        h.flow.confirmAim("test"); runCurrent()
        assertEquals(1, h.st.moves.size)
    }

    @Test fun nudgeStepsOverFullPegsAndIsLockedByAConfirm() = runTest {
        val h = Harness(this)
        val C2 = Qubic.parsePeg("C2")!!
        h.flow.importMoves(List(4) { C2 }, GameConfig(Mode.PVP)); runCurrent()   // C2 full
        h.flow.tapPeg(Qubic.parsePeg("B2")!!); runCurrent()
        assertTrue(h.flow.nudge(Dir.RIGHT, "test")); runCurrent()
        assertEquals("D2", Qubic.pegName(h.st.aim!!))
        h.flow.requestConfirm(ConfirmKind.RESIGN); runCurrent()  // aim cancelled, tower locked
        assertFalse(h.flow.nudge(Dir.LEFT, "test"))
    }
}
