package io.embodit.qubictower.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** Named states of design-package states[] that live in the game (S01 / S10–S13 / S15 are overlays, pages or flags). */
enum class Phase(val id: String) {
    LOADING("S00"), SETUP("S02"), IDLE("S03"), AIMING("S04"), DROPPING("S05"), THINKING("S06"), CLEARING("S07"), RESULT("S09"),
}

/** v0.1.1: no SLIDE / LINES any more (selecting and moving the selection are silent, D51). */
enum class Sound { LIFT, TICK, LAND, RETURN, ILLEGAL, BLOCK, TURN, SNAP, CLEAR, WIN, NOTE, DRAW, HINT }

/** S08 one-liners; [peg] is where the red ring flashes (-1 = none). */
enum class AlertKind { FULL, BUSY, NOT_YOUR_TURN, OVER, START_FIRST, TURNING }

data class Alert(val kind: AlertKind, val peg: Int, val at: Long)

enum class ConfirmKind { RESTART, RESIGN, EXIT, UNDO_2P }

/** Where the aimed bead came from (scene animation). */
data class AimFrom(val traySlot: Int = -1, val peg: Int = -1)

/** A bead sliding down its peg (S05). Committed before it moves. */
data class DropAnim(val side: Int, val peg: Int, val cell: Int, val startedAt: Long)

/** A bead going back to its tray: up its peg (from a cell; undo) or straight from the aim height, then an arc to [slot]. */
data class ReturnItem(val side: Int, val fromCell: Int, val fromPeg: Int, val slot: Int, val startedAt: Long, val upMs: Long, val flyMs: Long)

/** S07: the tower emptied top to bottom, each bead arcing home to its tray slot. */
data class ClearAnim(val items: List<ReturnItem>, val startedAt: Long, val totalMs: Long)

/** Lines of a just-landed bead stay 1.5 s (no input lock). */
data class Linger(val cell: Int, val until: Long)

data class Result(val winner: Int?, val line: Qubic.Line?, val draw: Boolean, val resigned: Boolean, val at: Long, val moves: Int)

/** A turntable motion: a button turn (400 ms, input locked, S14) or the snap after a push (250 ms, not locked). */
data class RotAnim(val fromDeg: Float, val toDeg: Float, val startedAt: Long, val ms: Long, val button: Boolean)

/** What just happened (status sentence facts, design content.tone: only what the engine knows). */
enum class NoteKind { BLOCKED, THREE, DROPPED, UNDONE, REDONE, RESTORED }

data class Note(val kind: NoteKind, val side: Int, val lineKind: Qubic.Kind? = null, val peg: Int = -1)

/** The one state every surface reads (scene, control window, the three tags, debug dump). */
data class PlayState(
    val phase: Phase = Phase.LOADING,
    val config: GameConfig? = null,
    val moves: List<Int> = emptyList(),
    val redo: List<List<Int>> = emptyList(),
    val aim: Int? = null,
    val aimFrom: AimFrom = AimFrom(),
    val aimAt: Long = 0,
    val aiAim: Int? = null,
    val aiAimAt: Long = 0,
    val drop: DropAnim? = null,
    val returns: List<ReturnItem> = emptyList(),
    val clear: ClearAnim? = null,
    val linger: Linger? = null,
    val alert: Alert? = null,
    val result: Result? = null,
    val note: Note? = null,
    val hintOn: Boolean = false,
    val hint: Int? = null,
    val confirm: ConfirmKind? = null,
    val orientation: Int = 0,
    val rot: RotAnim? = null,
    val welcome: Boolean = false,
    val lit: Boolean = false,
    val restored: Boolean = false,
    val savedAvailable: Boolean = false,
    val savedMoves: Int = 0,
    /** Debug: animations run this many times slower (QA on a 1 fps emulator). */
    val slow: Int = 1,
    /** Debug: animations started after [freezeAt] stop [freezeMs] in. */
    val freezeMs: Long? = null,
    val freezeAt: Long = 0,
    val thinkMs: Long = 0,
    val thinkDepth: Int = 0,
) {
    val board: Qubic.Board by lazy { Qubic.boardOf(moves) }
    val side: Int get() = Qubic.sideToMove(moves)
    val humansTurn: Boolean get() = config?.isHuman(side) == true
    val inGame: Boolean get() = config != null && phase in setOf(Phase.IDLE, Phase.AIMING, Phase.DROPPING, Phase.THINKING)
    val hasProgress: Boolean get() = inGame && moves.isNotEmpty()
    val buttonTurning: Boolean get() = rot?.button == true
    /** Beads still in [side]'s tray (the aimed / telegraphed bead counts as taken, design S04 "托盘计数当场减 1"). */
    fun trayCount(s: Int): Int {
        val placed = moves.indices.count { it % 2 == s }
        val out = (if (aim != null && side == s) 1 else 0) + (if (aiAim != null && side == s) 1 else 0)
        return (Qubic.BEADS - placed - out).coerceAtLeast(0)
    }
    val stateId: String get() = when {
        confirm != null -> "S13"
        buttonTurning -> "S14"
        alert != null -> "S08"
        restored && phase == Phase.IDLE -> "S15"
        welcome -> "S01"
        else -> phase.id
    }
}

/**
 * Turn sequencing (rules - state - render: this is the middle layer). Main-thread confined: every public call and every
 * coroutine it launches runs on [scope]'s dispatcher; the AI runs on [engineDispatcher]. Debug commands call the same
 * functions the volume and the window call (profile D29). Moves are committed and saved before anything animates.
 */
class GameFlow(
    private val scope: CoroutineScope,
    private val engineDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val store: GameStore,
    private val settings: () -> Settings,
    private val onSettings: (Settings) -> Unit = {},
    private val onSound: (Sound, Float) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = {},
    seed: Int = 20261105,
    /** Tests cap the HARD search (depth) so 200-game ladders finish; null = the shipped 1.5 s iterative deepening. */
    private val hardDepthCap: Int? = null,
) {
    companion object {
        const val MIN_THINK_MS = 400L
        const val ALERT_MS = 1600L
        const val UNDO_LIMIT = 64
        const val LIT_DROPS = 3
    }

    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()
    private val s: PlayState get() = _state.value

    private var rng = Random(seed)
    var aiSeed: Int = seed
    var holdThinking: Boolean = false
    /** A window-only confirmation (exit / new game) is open: the volume is locked too. */
    @Volatile var uiLocked: Boolean = false

    private val engine = Engine()
    private val hintEngine = Engine(ttBits = 16)
    private var turnJob: Job? = null
    private var dropJob: Job? = null
    private var alertJob: Job? = null
    private var hintJob: Job? = null
    private var rotJob: Job? = null
    private var animJob: Job? = null

    private fun set(next: PlayState) { _state.value = next }
    private val reduced: Boolean get() = settings().reducedMotion
    private fun sound(x: Sound, rate: Float = 1f) = onSound(x, rate)
    private fun ms(base: Long): Long = if (reduced) minOf(base, 120L) * s.slow else base * s.slow

    // ---------------------------------------------------------------- lifecycle

    /** S00 exits: first run -> default game + rules page (S01); a save -> S15; otherwise S02. */
    fun start() {
        val saved = store.load()
        val st = settings()
        if (!st.firstRunDone) {
            newGame(GameConfig(Mode.PVE, Engine.EASY, Qubic.AMBER), sweep = false)
            set(s.copy(welcome = true, savedAvailable = false))
            log("STATE S01 (default game, rules page open)")
            return
        }
        if (saved != null && Qubic.outcome(Qubic.boardOf(saved.moves)).let { it.winner == null && !it.draw } && !saved.resigned) restore(saved)
        else { set(PlayState(phase = Phase.SETUP, orientation = saved?.orientation ?: 0)); log("STATE S02") }
    }

    /** [开始] on the first-run rules page, or the first pinch on a peg: the welcome is over (the game already runs). */
    fun welcomeDone() {
        if (!s.welcome && settings().firstRunDone) return
        onSettings(settings().copy(firstRunDone = true))
        set(s.copy(welcome = false))
        log("WELCOME done")
    }

    fun continueSaved(): Boolean {
        val saved = store.load() ?: return false
        if (Qubic.outcome(Qubic.boardOf(saved.moves)).winner != null) return false
        restore(saved)
        return true
    }

    private fun restore(saved: SavedGame) {
        cancelJobs()
        set(PlayState(
            phase = Phase.IDLE, config = saved.config, moves = saved.moves, redo = saved.redo, orientation = saved.orientation,
            hintOn = saved.hintOn, restored = true, savedAvailable = true, lit = litActive(saved.config, saved.moves),
            note = Note(NoteKind.RESTORED, Qubic.sideToMove(saved.moves)),
        ))
        log("GAME restored moves=${saved.moves.size} side=${s.side} orientation=${saved.orientation} S15")
        nextTurn()
    }

    /** New-game page [开始] (after its confirm): the seat is resolved here (random picks once, Hard always lets you start). */
    fun newGame(setup: GameSetup) {
        val human = when {
            setup.mode == Mode.PVE && setup.level == Engine.HARD -> Qubic.AMBER
            setup.seat == SeatPick.AMBER -> Qubic.AMBER
            setup.seat == SeatPick.CELADON -> Qubic.CELADON
            else -> if (rng.nextBoolean()) Qubic.AMBER else Qubic.CELADON
        }
        onSettings(settings().copy(lastSetup = setup))
        newGame(GameConfig(setup.mode, setup.level.coerceIn(1, 3), human))
    }

    /** Starts [config]; beads still on the tower are swept home first (S07). */
    fun newGame(config: GameConfig, sweep: Boolean = true) {
        cancelJobs()
        val old = s
        val items = if (sweep) sweepItems(old) else emptyList()
        val now = clock()
        val clearMs = if (items.isEmpty()) 0L else ms(Layout.CLEAR_TOTAL_MS)
        set(PlayState(
            phase = if (items.isEmpty()) Phase.IDLE else Phase.CLEARING, config = config, orientation = old.orientation,
            hintOn = old.hintOn, welcome = old.welcome, lit = litActive(config, emptyList()), slow = old.slow,
            freezeMs = old.freezeMs, freezeAt = old.freezeAt, clear = if (items.isEmpty()) null else ClearAnim(items, now, clearMs),
        ))
        log("GAME new mode=${config.mode} level=${config.level} human=${config.human} sweep=${items.size}")
        persist()
        if (items.isEmpty()) { nextTurn(); return }
        log("STATE S07 clearing ${items.size} beads")
        turnJob = scope.launch {
            // the beads land in the trays one after another: a rattle every ~150 ms while they fly
            val step = (clearMs / 6).coerceAtLeast(60)
            var t = 0L
            while (t + step <= clearMs) { sound(Sound.CLEAR); delay(step); t += step }
            delay(clearMs - t)
            awaitUnfrozen()
            set(s.copy(clear = null, phase = Phase.IDLE))
            nextTurn()
        }
    }

    /** The S07 flights: top layer first, each side's beads refill its tray from the last-removed slot back. */
    private fun sweepItems(old: PlayState): List<ReturnItem> {
        val b = old.board
        val cells = (0 until 64).filter { b.sideAt(it) >= 0 }.sortedByDescending { Qubic.layerOf(it) * 100 - it }
        if (cells.isEmpty()) return emptyList()
        val n = cells.size
        val total = ms(Layout.CLEAR_TOTAL_MS)
        val fly = ms(Layout.CLEAR_FLY_MS)
        val stagger = if (n <= 1) 0L else minOf(ms(60), (total - fly) / (n - 1))
        val left = IntArray(2) { side -> Qubic.BEADS - b.count(side) }
        val now = clock()
        return cells.mapIndexed { i, c ->
            val side = b.sideAt(c)
            val slot = Layout.returnSlot(side, left[side]); left[side]++
            ReturnItem(side, c, -1, slot, now + i * stagger, 0L, fly)
        }
    }

    /** [再来一局]: same setup (S07 then S03 / S06). */
    fun again() { s.config?.let { newGame(it) } }

    fun toMenu() {
        cancelJobs()
        val saved = store.load()
        set(s.copy(phase = Phase.SETUP, config = null, aim = null, aiAim = null, result = s.result, confirm = null, alert = null,
            savedAvailable = saved != null, savedMoves = saved?.moves?.size ?: 0, hint = null, linger = null, restored = false))
        log("STATE S02")
    }

    /** Going to the background: stop the AI, land a bead in flight (already committed), keep the game. */
    fun onBackground() {
        if (s.phase == Phase.THINKING) { turnJob?.cancel(); set(s.copy(aiAim = null)) }
        persist()
        log("BACKGROUND phase=${s.phase.id}")
    }

    fun onForeground() {
        if (s.config == null) return
        if (s.phase == Phase.THINKING && turnJob?.isActive != true) aiTurn()
    }

    // ---------------------------------------------------------------- input (volume, panel, debug: one path)

    private fun locked(): Boolean {
        if (s.confirm != null || uiLocked) { log("LOCKED by confirm (S13)"); return true }
        return false
    }

    /** A pinch / trigger on a peg collider (or the bead hanging over it). */
    fun tapPeg(peg: Int) {
        val st = s
        log("TAP peg=${Qubic.pegName(peg)} phase=${st.phase.id} aim=${st.aim?.let { Qubic.pegName(it) }}")
        if (locked()) return
        if (st.welcome) welcomeDone()
        if (st.buttonTurning) { log("IGNORED turning (S14)"); return }
        when (st.phase) {
            Phase.IDLE -> {
                if (!st.humansTurn) { alert(AlertKind.NOT_YOUR_TURN, -1); return }
                if (!st.board.canDrop(peg)) { alert(AlertKind.FULL, peg); return }
                aimAt(peg)
            }
            Phase.AIMING -> when {
                st.aim == peg -> drop(peg)
                !st.board.canDrop(peg) -> alert(AlertKind.FULL, peg)
                else -> aimAt(peg)
            }
            Phase.DROPPING, Phase.THINKING, Phase.CLEARING -> log("IGNORED busy ${st.phase.id}")
            Phase.RESULT -> alert(AlertKind.OVER, -1)
            Phase.SETUP -> alert(AlertKind.START_FIRST, -1)
            Phase.LOADING -> Unit
        }
    }

    /** A pinch on the empty plinth top: the aimed bead goes home (S04 -> S03). */
    fun tapPlinth() {
        log("TAP plinth phase=${s.phase.id}")
        if (locked()) return
        if (s.phase == Phase.AIMING) cancelAim()
    }

    /** A pinch (not a push) on the turntable rim: a hint that it turns. */
    fun tapRim() { log("TAP rim phase=${s.phase.id}") }

    private fun aimAt(peg: Int) {
        val st = s
        val from = if (st.aim != null) AimFrom(peg = st.aim) else AimFrom(traySlot = Layout.nextSlot(st.side, st.trayCount(st.side)))
        set(st.copy(phase = Phase.AIMING, aim = peg, aimFrom = from, aimAt = clock(), alert = null, linger = null, restored = false, note = null))
        // D51 / board-input 精确选择: selecting and moving the selection are silent; only the drop sounds (v0.1.1)
        val cell = st.board.landing(peg)
        log("AIM ${Qubic.pegName(peg)} cell=$cell lines=${Qubic.LINES_THROUGH[cell].size} S04")
    }

    /**
     * Confirm phase (S04): move the selection one peg [dir] as the user sees the tower (thumbstick / the panel's arrow
     * keys / debug `nudge`). Full pegs are stepped over; at the edge nothing moves. Returns whether it moved.
     */
    fun nudge(dir: Dir, via: String = "panel"): Boolean {
        val st = s
        if (locked()) return false
        if (st.buttonTurning) { log("NUDGE ignored turning (S14) via=$via"); return false }
        val from = st.aim
        if (st.phase != Phase.AIMING || from == null) { log("NUDGE ignored phase=${st.phase.id} via=$via"); return false }
        val to = Nudge.next(from, dir, st.orientation) { st.board.canDrop(it) }
        if (to == null) { log("NUDGE $dir edge at ${Qubic.pegName(from)} orientation=${st.orientation} via=$via"); return false }
        log("NUDGE $dir ${Qubic.pegName(from)} -> ${Qubic.pegName(to)} orientation=${st.orientation} via=$via")
        aimAt(to)
        return true
    }

    /** Whether [nudge] would move now (the panel greys an arrow out at the edge). */
    fun canNudge(dir: Dir, st: PlayState = s): Boolean {
        val from = st.aim ?: return false
        if (st.phase != Phase.AIMING || st.buttonTurning || st.confirm != null) return false
        return Nudge.next(from, dir, st.orientation) { st.board.canDrop(it) } != null
    }

    /** [确定] / trigger / A in the confirm phase: drop the selected bead (same as a second pinch on the same peg). */
    fun confirmAim(via: String = "panel") {
        val st = s
        if (locked()) return
        val peg = st.aim
        if (st.phase != Phase.AIMING || peg == null || st.buttonTurning) { log("CONFIRM-AIM ignored phase=${st.phase.id} via=$via"); return }
        log("CONFIRM-AIM ${Qubic.pegName(peg)} via=$via")
        drop(peg)
    }

    /** [取消] / B in the confirm phase: the bead goes home, nothing selected (S04 -> S03). */
    fun cancelSelection(via: String = "panel") {
        if (locked()) return
        if (s.phase != Phase.AIMING) { log("CANCEL-AIM ignored phase=${s.phase.id} via=$via"); return }
        log("CANCEL-AIM via=$via")
        cancelAim()
    }

    fun cancelAim() {
        val st = s
        val peg = st.aim ?: return
        val n = st.trayCount(st.side)  // already excludes the aimed bead
        val item = ReturnItem(st.side, -1, peg, Layout.returnSlot(st.side, n), clock(), 0L, ms(Layout.LIFT_MS))
        set(st.copy(phase = Phase.IDLE, aim = null, returns = st.returns + item))
        // silent: cancelling a selection is part of choosing (D51 预选无声), not a move
        scheduleReturnCleanup()
        log("CANCEL aim -> S03")
    }

    /** Drop the aimed bead (second pinch on the same peg, or the panel / debug). */
    fun drop(peg: Int = s.aim ?: -1) {
        val st = s
        if (peg !in 0..15 || !st.board.canDrop(peg)) { if (peg in 0..15) alert(AlertKind.FULL, peg); return }
        commit(peg, byHuman = true)
    }

    private fun commit(peg: Int, byHuman: Boolean) {
        val st = s
        val side = st.side
        val before = st.board
        val cell = before.landing(peg)
        val threatsBefore = Qubic.threats(before, Qubic.other(side)).map { it.second }.toSet()
        val moves = st.moves + peg
        val after = before.drop(side, peg)
        val now = clock()
        val blocked = cell in threatsBefore
        val made3 = Qubic.threats(after, side).filter { (l, _) -> (l.mask shr cell) and 1L == 1L }.map { it.first }.firstOrNull()
        val note = when {
            blocked -> Note(NoteKind.BLOCKED, side, peg = peg)
            made3 != null -> Note(NoteKind.THREE, side, made3.kind, peg)
            else -> Note(NoteKind.DROPPED, side, peg = peg)
        }
        set(st.copy(
            phase = Phase.DROPPING, moves = moves, redo = if (byHuman) emptyList() else st.redo, aim = null, aiAim = null,
            drop = DropAnim(side, peg, cell, now), linger = null, alert = null, hint = null, restored = false, note = note,
        ))
        log("DROP side=$side peg=${Qubic.pegName(peg)} cell=$cell layer=${Qubic.layerOf(cell)} by=${if (byHuman) "human" else "ai"} S05")
        // committed and saved before the bead moves (Cloud Air rule)
        val out = Qubic.outcome(after)
        if (out.winner == null && !out.draw) persist() else store.clear()
        if (byHuman && st.lit && st.config?.isHuman(side) == true) {
            val drops = moves.indices.count { it % 2 == side }
            if (drops >= LIT_DROPS) { onSettings(settings().copy(litDone = true)); set(s.copy(lit = false)) }
        }
        val layer = Qubic.layerOf(cell)
        dropJob?.cancel()
        dropJob = scope.launch {
            var t = 0L
            if (!reduced) for ((i, at) in Layout.passTimes(layer).withIndex()) {
                delay(at * s.slow - t); t = at * s.slow
                sound(Sound.TICK, 1.12f - 0.08f * i)
            }
            val fall = if (reduced) ms(120) else Layout.fallMs(layer) * s.slow
            delay((fall - t).coerceAtLeast(0))
            awaitUnfrozen()
            sound(Sound.LAND, 1.05f - 0.04f * layer)
            landed(cell, out)
            delay(ms(Layout.BOUNCE_MS))
            if (s.drop?.cell == cell) set(s.copy(drop = null))
        }
    }

    private fun landed(cell: Int, out: Qubic.Outcome) {
        if (out.winner != null || out.draw) { finish(out.winner, out.line, out.draw, resigned = false); return }
        val linger = Linger(cell, clock() + ms(Layout.LINGER_MS))
        set(s.copy(linger = linger))
        scope.launch { delay(ms(Layout.LINGER_MS)); if (s.linger == linger) set(s.copy(linger = null)) }
        if (s.note?.kind == NoteKind.THREE) sound(Sound.BLOCK)
        nextTurn()
    }

    private fun nextTurn() {
        val st = s
        val cfg = st.config ?: return
        if (st.phase == Phase.RESULT || st.phase == Phase.CLEARING) return
        if (cfg.isHuman(st.side)) {
            set(st.copy(phase = Phase.IDLE, aim = null))
            log("STATE ${if (st.restored) "S15" else "S03"} side=${st.side} moves=${st.moves.size}${if (cfg.mode == Mode.PVP) " pass" else ""}")
            if (Display.mustBlock(s).isNotEmpty()) { sound(Sound.BLOCK, 0.9f); log("ALERT must-block ${Display.mustBlock(s).first().first.kind} S08") }
            refreshHint()
            persist()
        } else aiTurn()
    }

    // ---------------------------------------------------------------- AI

    private fun aiTurn() {
        val st = s
        val cfg = st.config ?: return
        val board = st.board
        val side = st.side
        val moveNo = st.moves.size
        set(st.copy(phase = Phase.THINKING, aiAim = null))
        log("THINK level=${cfg.level} S06")
        turnJob?.cancel()
        turnJob = scope.launch {
            val t0 = clock()
            val job = coroutineContext
            val r = withContext(engineDispatcher) {
                val cap = if (cfg.level == Engine.HARD) hardDepthCap else null
                engine.choose(board, side, cfg.level, Random(aiSeed + moveNo * 31), cancelled = { !job.isActive }, maxDepth = cap,
                    timeMs = if (cap != null) 60_000L else null)
            }
            if (!isActive) return@launch
            val waited = clock() - t0
            if (waited < MIN_THINK_MS) delay(MIN_THINK_MS - waited)
            while (holdThinking) delay(100)
            if (s.phase != Phase.THINKING || s.moves.size != moveNo) return@launch
            set(s.copy(aiAim = r.peg, aiAimAt = clock(), linger = null, thinkMs = r.ms, thinkDepth = r.depth))
            sound(Sound.LIFT)
            log("AI pick ${Qubic.pegName(r.peg)} level=${cfg.level} depth=${r.depth} nodes=${r.nodes} ${r.ms} ms")
            delay(ms(Layout.TELEGRAPH_MS))
            awaitUnfrozen()
            if (s.phase != Phase.THINKING || s.moves.size != moveNo) return@launch
            commit(r.peg, byHuman = false)
        }
    }

    // ---------------------------------------------------------------- hint

    private fun refreshHint() {
        hintJob?.cancel()
        val st = s
        if (!st.hintOn || !st.humansTurn || st.phase !in setOf(Phase.IDLE, Phase.AIMING)) { if (st.hint != null) set(st.copy(hint = null)); return }
        val board = st.board; val side = st.side; val n = st.moves.size
        hintJob = scope.launch {
            val job = coroutineContext
            val p = withContext(engineDispatcher) { hintEngine.hint(board, side) { !job.isActive } }
            if (s.moves.size == n && s.hintOn) { set(s.copy(hint = p)); sound(Sound.HINT); log("HINT ${Qubic.pegName(p)}") }
        }
    }

    fun setHint(on: Boolean) {
        set(s.copy(hintOn = on, hint = if (on) s.hint else null))
        log("HINT ${if (on) "on" else "off"}")
        refreshHint()
        persist()
    }

    // ---------------------------------------------------------------- turntable

    /** Panel [左转 90°] (+1, counter-clockwise from above) / [右转 90°] (−1): 400 ms, input locked (S14). */
    fun turnButton(dir: Int) {
        if (locked()) return
        val st = s
        if (st.buttonTurning) return
        val from = displayDeg(st, clock())
        val k = Turntable.normalize(st.orientation + dir)
        val target = Math.round(from / 90f) * 90f + 90f * dir
        set(st.copy(orientation = k, rot = RotAnim(from, target, clock(), ms(Turntable.BUTTON_MS), button = true)))
        sound(Sound.TURN)
        log("TURN button dir=$dir -> $k S14")
        persist()
        rotJob?.cancel()
        rotJob = scope.launch { delay(ms(Turntable.BUTTON_MS)); awaitUnfrozen(); set(s.copy(rot = null)); sound(Sound.SNAP) }
    }

    /** Debug `turn 0..3`: straight to an orientation (same path as a button turn when it is one step). */
    fun turnTo(k: Int) {
        val st = s
        val d = Turntable.normalize(k - st.orientation)
        when (d) { 0 -> Unit; 1 -> turnButton(1); 3 -> turnButton(-1); else -> { turnButton(1); scope.launch { delay(ms(Turntable.BUTTON_MS) + 20); turnButton(1) } } }
    }

    /** May a push start now? (not during a button turn or a confirmation) */
    fun canPush(): Boolean = !s.buttonTurning && s.confirm == null && !uiLocked && s.phase != Phase.LOADING

    /** The hand let go of the rim [deltaDeg] away from where the push started: snap to a quarter turn (hysteresis ±5°). */
    fun pushRelease(startDeg: Float, deltaDeg: Float) {
        val st = s
        val steps = Turntable.snapSteps(deltaDeg)
        val k = Turntable.normalize(st.orientation + steps)
        val from = startDeg + deltaDeg
        val to = Math.round(startDeg / 90f) * 90f + 90f * steps
        set(st.copy(orientation = k, rot = RotAnim(from, to, clock(), ms(Turntable.SNAP_MS), button = false)))
        log("PUSH release delta=${"%.1f".format(deltaDeg)} steps=$steps -> $k")
        persist()
        rotJob?.cancel()
        rotJob = scope.launch { delay(ms(Turntable.SNAP_MS)); set(s.copy(rot = null)); sound(Sound.SNAP) }
    }

    /** The turntable angle the scene shows at [now] (without a live push). */
    fun displayDeg(st: PlayState = s, now: Long = clock()): Float {
        val r = st.rot ?: return st.orientation * 90f
        val t = ((now - r.startedAt).toFloat() / r.ms).coerceIn(0f, 1f)
        return r.fromDeg + (r.toDeg - r.fromDeg) * Turntable.ease(t)
    }

    // ---------------------------------------------------------------- undo / redo

    val canUndo: Boolean get() {
        val st = s
        val cfg = st.config ?: return false
        if (st.moves.isEmpty() || st.confirm != null || st.buttonTurning) return false
        if (st.phase !in setOf(Phase.IDLE, Phase.AIMING, Phase.THINKING, Phase.RESULT)) return false
        if (st.result?.resigned == true) return false
        return cfg.mode == Mode.PVP || st.moves.indices.any { cfg.isHuman(it % 2) }
    }

    val canRedo: Boolean get() = s.redo.isNotEmpty() && s.humansTurn && s.phase in setOf(Phase.IDLE, Phase.AIMING) && s.confirm == null

    fun requestUndo() {
        val cfg = s.config ?: return
        if (!canUndo) { log("UNDO not available"); return }
        if (cfg.mode == Mode.PVP) requestConfirm(ConfirmKind.UNDO_2P) else undo()
    }

    private fun undo() {
        val st = s
        val cfg = st.config ?: return
        turnJob?.cancel(); dropJob?.cancel(); hintJob?.cancel()
        val moves = st.moves.toMutableList()
        val popped = ArrayList<Int>()
        val board0 = st.board
        if (cfg.mode == Mode.PVP || st.phase == Phase.THINKING) popped.add(0, moves.removeAt(moves.lastIndex))
        else while (moves.isNotEmpty()) {
            val side = moves.lastIndex % 2
            popped.add(0, moves.removeAt(moves.lastIndex))
            if (cfg.isHuman(side)) break
        }
        // the aimed bead (if any) goes straight home; the popped beads rise up their pegs and fly home (newest first)
        val now = clock()
        val raw = ArrayList<ReturnItem>()
        st.aim?.let { p -> raw += ReturnItem(st.side, -1, p, -1, now, 0L, ms(Layout.LIFT_MS)) }
        for (k in popped.indices.reversed()) {
            val side = (moves.size + k) % 2
            val cell = Qubic.boardOf(moves + popped.take(k)).landing(popped[k])
            raw += ReturnItem(side, cell, -1, -1, now + (popped.size - 1 - k) * ms(80), ms(Layout.UNDO_UP_MS), ms(Layout.UNDO_FLY_MS))
        }
        // tray slots: each side refills from the last removed slot back, in arrival order
        val base = IntArray(2) { side -> Qubic.BEADS - moves.indices.count { it % 2 == side } - raw.count { it.side == side } }
        val fixed = raw.sortedBy { it.startedAt + it.upMs + it.flyMs }.map { r -> r.copy(slot = Layout.returnSlot(r.side, base[r.side]).also { base[r.side]++ }) }
        set(st.copy(
            phase = Phase.IDLE, moves = moves, redo = (listOf(popped.toList()) + st.redo).take(UNDO_LIMIT), aim = null, aiAim = null, drop = null,
            returns = st.returns + fixed, result = null, linger = null, alert = null, hint = null, confirm = null, note = Note(NoteKind.UNDONE, Qubic.sideToMove(moves)),
        ))
        sound(Sound.RETURN)
        scheduleReturnCleanup()
        log("UNDO popped=${popped.joinToString(",") { Qubic.pegName(it) }} moves=${moves.size} redo=${s.redo.size} board0=${board0.all != s.board.all}")
        persist()
        nextTurn()
    }

    fun redo() {
        val st = s
        if (!canRedo) return
        cancelJobs()
        val group = st.redo.first()
        val moves = st.moves + group
        val last = group.last()
        val cellLast = Qubic.boardOf(st.moves + group.dropLast(1)).landing(last)
        set(st.copy(moves = moves, redo = st.redo.drop(1), aim = null, phase = Phase.IDLE, note = Note(NoteKind.REDONE, Qubic.sideToMove(st.moves)),
            drop = DropAnim((moves.size - 1) % 2, last, cellLast, clock())))
        log("REDO ${group.joinToString(",") { Qubic.pegName(it) }} moves=${moves.size}")
        sound(Sound.LAND)
        val out = Qubic.outcome(s.board)
        dropJob = scope.launch { delay(Layout.dropMs(Qubic.layerOf(cellLast)) * s.slow); if (s.drop?.cell == cellLast) set(s.copy(drop = null)) }
        if (out.winner != null || out.draw) { finish(out.winner, out.line, out.draw, false); return }
        persist()
        nextTurn()
    }

    private fun scheduleReturnCleanup() {
        animJob?.cancel()
        val until = s.returns.maxOfOrNull { it.startedAt + it.upMs + it.flyMs } ?: return
        animJob = scope.launch {
            delay((until - clock()).coerceAtLeast(0) + 20)
            awaitUnfrozen()
            val now = clock()
            set(s.copy(returns = s.returns.filter { it.startedAt + it.upMs + it.flyMs > now }))
        }
    }

    // ---------------------------------------------------------------- confirm, resign, result

    fun requestConfirm(kind: ConfirmKind) {
        if (s.aim != null && kind != ConfirmKind.EXIT) cancelAim()
        set(s.copy(confirm = kind)); log("CONFIRM $kind S13")
    }

    fun cancelConfirm() { set(s.copy(confirm = null)); log("CONFIRM cancel") }

    /** Confirms the open dialog. EXIT is finished by the window (it owns the navigator); it only saves here. */
    fun confirm() {
        val kind = s.confirm ?: return
        set(s.copy(confirm = null))
        log("CONFIRM yes $kind")
        when (kind) {
            ConfirmKind.UNDO_2P -> undo()
            ConfirmKind.RESIGN -> {
                val cfg = s.config ?: return
                val loser = if (cfg.mode == Mode.PVE) cfg.human else s.side
                finish(Qubic.other(loser), null, draw = false, resigned = true)
            }
            ConfirmKind.RESTART -> again()
            ConfirmKind.EXIT -> onBackground()
        }
    }

    private fun finish(winner: Int?, line: Qubic.Line?, draw: Boolean, resigned: Boolean) {
        turnJob?.cancel(); hintJob?.cancel()
        val st = s
        set(st.copy(phase = Phase.RESULT, result = Result(winner, line, draw, resigned, clock(), st.moves.size), aim = null, aiAim = null,
            hint = null, confirm = null, linger = null, alert = null))
        val cfg = st.config
        val aiWon = cfg != null && cfg.mode == Mode.PVE && winner != null && winner != cfg.human
        log("RESULT winner=$winner line=${line?.kind}${line?.cells?.joinToString(",", "[", "]")} draw=$draw resigned=$resigned moves=${st.moves.size} S09")
        if (draw) sound(Sound.DRAW)
        else if (!resigned) scope.launch {
            // four beads light up one by one with a rising note each; the chord only when you win (D44)
            for (i in 0..3) { sound(Sound.NOTE, 1f + 0.125f * i * (if (i == 3) 1.33f else 1f)); delay(ms(Layout.WIN_STEP_MS)) }
            if (!aiWon) sound(Sound.WIN)
        }
        store.clear()
    }

    // ---------------------------------------------------------------- alerts

    private fun alert(kind: AlertKind, peg: Int) {
        val at = clock()
        set(s.copy(alert = Alert(kind, peg, at)))
        sound(Sound.ILLEGAL)
        log("ALERT $kind ${if (peg >= 0) Qubic.pegName(peg) else ""} S08")
        alertJob?.cancel()
        alertJob = scope.launch {
            delay(ALERT_MS)
            awaitUnfrozen()
            if (s.alert?.at == at) set(s.copy(alert = null))
        }
    }

    // ---------------------------------------------------------------- debug

    fun freeze(ms: Long?) { set(s.copy(freezeMs = ms, freezeAt = clock())); log("FREEZE ${ms ?: "off"}") }
    fun setSlow(k: Int) { set(s.copy(slow = k.coerceIn(1, 8))); log("SLOW ${s.slow}") }
    private suspend fun awaitUnfrozen() { while (coroutineContext.isActive && s.freezeMs != null) delay(50) }

    /** Debug `import <pegs>`: replace the game with this move list (PVE easy unless a game runs), back to S03 / S06. */
    fun importMoves(moves: List<Int>, config: GameConfig = s.config ?: GameConfig()) {
        cancelJobs()
        Qubic.boardOf(moves)
        set(PlayState(phase = Phase.IDLE, config = config, moves = moves, orientation = s.orientation, hintOn = s.hintOn, slow = s.slow,
            freezeMs = s.freezeMs, freezeAt = s.freezeAt))
        log("IMPORT ${moves.size} moves")
        val out = Qubic.outcome(s.board)
        if (out.winner != null || out.draw) { finish(out.winner, out.line, out.draw, false); return }
        persist()
        nextTurn()
    }

    fun debugState(mutator: (PlayState) -> PlayState) = set(mutator(s))

    fun reseed(seed: Int) { rng = Random(seed); aiSeed = seed; log("SEED $seed") }

    fun dump(): String {
        val st = s
        return "state=${st.stateId} phase=${st.phase.id} cfg=${st.config} side=${st.side} moves=${st.moves.joinToString("") { it.toString(16) }} " +
            "aim=${st.aim?.let { Qubic.pegName(it) }} aiAim=${st.aiAim?.let { Qubic.pegName(it) }} orient=${st.orientation} rot=${st.rot != null} " +
            "trays=${st.trayCount(0)}/${st.trayCount(1)} hint=${st.hint?.let { Qubic.pegName(it) }} alert=${st.alert?.kind} confirm=${st.confirm} " +
            "redo=${st.redo.size} result=${st.result?.let { "${it.winner}/${it.line?.kind}/${it.draw}" }} mustBlock=${Display.mustBlock(st).size} " +
            "welcome=${st.welcome} lit=${st.lit} think=${st.thinkMs}ms/d${st.thinkDepth}"
    }

    private fun litActive(cfg: GameConfig, moves: List<Int>): Boolean =
        !settings().litDone && cfg.mode == Mode.PVE && moves.indices.count { it % 2 == cfg.human } < LIT_DROPS

    private fun persist() {
        val st = s
        val cfg = st.config ?: return
        if (st.phase == Phase.RESULT || st.phase == Phase.SETUP) return
        store.save(SavedGame(cfg, st.moves, st.redo, st.orientation, st.hintOn))
    }

    private fun cancelJobs() {
        turnJob?.cancel(); alertJob?.cancel(); hintJob?.cancel(); dropJob?.cancel(); rotJob?.cancel(); animJob?.cancel()
    }
}
