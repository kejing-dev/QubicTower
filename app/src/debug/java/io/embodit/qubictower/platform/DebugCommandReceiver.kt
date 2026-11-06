package io.embodit.qubictower.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.embodit.qubictower.domain.ConfirmKind
import io.embodit.qubictower.domain.DebugPositions
import io.embodit.qubictower.domain.Engine
import io.embodit.qubictower.domain.GameConfig
import io.embodit.qubictower.domain.GameSetup
import io.embodit.qubictower.domain.Language
import io.embodit.qubictower.domain.Mode
import io.embodit.qubictower.domain.Qubic
import io.embodit.qubictower.domain.SeatPick
import io.embodit.qubictower.domain.Settings
import io.embodit.qubictower.domain.Turntable
import io.embodit.qubictower.ui.Page
import io.embodit.qubictower.ui.Ui
import io.embodit.qubictower.ui.board.SceneHolder
import io.embodit.qubictower.ui.board.route
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug builds only. Every command goes through the same GameFlow calls the volume and the window use (D29).
 *
 *   adb shell am broadcast -a io.embodit.qubictower.DEBUG -p io.embodit.qubictower --es cmd <cmd> [--es a "<arg>"]
 *
 *   goto S01..S15 · aim <peg 0–15|A1–D4> (= a pinch on that peg) · drop [peg] · tap peg:<n>|plinth|grip · cancel
 *   turn 0–3|left|right · push <deg> (a push of the rim released at <deg>) · eye E0|E1|E2|off · xray on|off · see on|off
 *   undo · redo · hint on|off · new "pve|pvp 1-3 amber|celadon|random" · import <hex pegs> · win column|row|flat|cross|space
 *   draw · level 1-3 · lang zh|en|system · sound on|off · volume 0-2 · reduced on|off · slow 1|2|4 · freeze MS|off
 *   hold on|off · s00hold on|off · lit on|off · welcome · resign · restart · confirm · no · exitask · page play|new|rules|settings
 *   closepanel · reopen · hover <peg|off> · seed N · dump|state · evidence
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim().trim('"')
        val on = a == "on" || a == "true" || a == "1"
        Log.i(TAG, "CMD $cmd $a")
        fun settings(change: (Settings) -> Settings) = graph.settings.update(change)
        fun later(ms: Long, block: () -> Unit) { graph.scope.launch { delay(ms); block() } }
        fun pve(level: Int = Engine.EASY) = GameConfig(Mode.PVE, level, Qubic.AMBER)
        fun peg(s: String): Int? = Qubic.parsePeg(s)
        fun moves(hex: String) = DebugPositions.parse(hex)
        when (cmd) {
            "goto" -> when (a.uppercase().substringBefore(' ')) {
                "S00" -> Log.i(TAG, "S00 is the first 0.5 s after launch: s00hold on, force-stop and start, then capture")
                "S01" -> { settings { it.copy(firstRunDone = false, litDone = false) }; flow.freeze(null); flow.start(); Ui.page = Page.RULES }
                "S02" -> { flow.toMenu(); Ui.page = Page.NEW }
                "S03" -> { flow.freeze(null); flow.importMoves(moves("5a69"), pve()); Ui.page = Page.PLAY }
                "S04" -> { flow.freeze(null); flow.importMoves(moves("5a69"), pve()); Ui.page = Page.PLAY; flow.tapPeg(0) }
                "S05" -> { flow.freeze(null); flow.setSlow(4); flow.importMoves(moves("5a69"), pve()); Ui.page = Page.PLAY; flow.tapPeg(6); later(1200) { flow.tapPeg(6) } }
                "S06" -> { flow.freeze(null); flow.holdThinking = true; flow.importMoves(moves("5a6"), pve(Engine.HARD)); Ui.page = Page.PLAY }
                "S07" -> { flow.freeze(null); flow.setSlow(4); flow.importMoves(moves("5a69f03c"), GameConfig(Mode.PVP)); Ui.page = Page.PLAY; later(300) { flow.again() } }
                "S08" -> { flow.freeze(null); flow.importMoves(moves("4091e2"), pve()); Ui.page = Page.PLAY }
                "S09" -> { flow.freeze(null); flow.importMoves(DebugPositions.winOf(Qubic.Kind.CROSS), pve()); Ui.page = Page.PLAY }
                "S10" -> graph.window.closeControlOnly()
                "S11" -> Ui.page = Page.SETTINGS
                "S12" -> { flow.welcomeDone(); Ui.page = Page.RULES }
                "S13" -> { flow.importMoves(moves("5a69"), pve()); Ui.page = Page.PLAY; flow.requestConfirm(ConfirmKind.RESTART) }
                "S14" -> { flow.importMoves(moves("5a69"), pve()); Ui.page = Page.PLAY; flow.setSlow(4); flow.turnButton(1) }
                "S15" -> { Log.i(TAG, "restore=${flow.continueSaved()}"); Ui.page = Page.PLAY }
                else -> Log.w(TAG, "unknown state $a")
            }
            "aim", "tapPeg" -> peg(a)?.let { flow.tapPeg(it) }
            "drop" -> { val p = peg(a) ?: flow.state.value.aim; if (p != null) { if (flow.state.value.aim != p) flow.tapPeg(p); flow.tapPeg(p) } }
            "tap" -> route(graph, a)
            "cancel" -> flow.tapPlinth()
            "turn" -> when (a) { "left" -> flow.turnButton(1); "right" -> flow.turnButton(-1); else -> a.toIntOrNull()?.let { flow.turnTo(Turntable.normalize(it)) } }
            "push" -> a.toFloatOrNull()?.let { d -> val s0 = flow.displayDeg(); SceneHolder.scene?.beginPush(s0); SceneHolder.scene?.pushTo(d); later(600) { SceneHolder.scene?.endPush(); flow.pushRelease(s0, d) } }
            "pushhold" -> a.toFloatOrNull()?.let { d -> val s0 = flow.displayDeg(); SceneHolder.scene?.beginPush(s0); SceneHolder.scene?.pushTo(d) }
            "eye" -> SceneHolder.scene?.eye = a.uppercase().takeIf { it.startsWith("E") }
            "xray" -> SceneHolder.scene?.xray = on
            "see" -> SceneHolder.scene?.seeThrough = on
            "hover" -> SceneHolder.scene?.setHover(peg(a))
            "undo" -> flow.requestUndo()
            "redo" -> flow.redo()
            "hint" -> flow.setHint(on)
            "new" -> {
                val f = a.split(" ").filter { it.isNotBlank() }
                flow.newGame(GameSetup(
                    mode = if (f.getOrNull(0) == "pvp") Mode.PVP else Mode.PVE,
                    level = f.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 3) ?: Engine.EASY,
                    seat = when (f.getOrNull(2)) { "celadon" -> SeatPick.CELADON; "random" -> SeatPick.RANDOM; else -> SeatPick.AMBER },
                ))
                Ui.page = Page.PLAY
            }
            "import" -> runCatching { flow.importMoves(moves(a)) }.onFailure { Log.w(TAG, "import failed: $it") }
            "win" -> {
                val k = when (a) { "row" -> Qubic.Kind.ROW; "flat" -> Qubic.Kind.FLAT; "cross" -> Qubic.Kind.CROSS; "space" -> Qubic.Kind.SPACE; else -> Qubic.Kind.COLUMN }
                flow.importMoves(DebugPositions.winOf(k), flow.state.value.config ?: pve()); Ui.page = Page.PLAY
            }
            "draw" -> { flow.importMoves(moves(DebugPositions.DRAW_64.dropLast(1)), GameConfig(Mode.PVP)); Ui.page = Page.PLAY }
            "level" -> a.toIntOrNull()?.let { l -> flow.debugState { it.copy(config = (it.config ?: pve()).copy(level = l.coerceIn(1, 3))) } }
            "resign" -> flow.requestConfirm(ConfirmKind.RESIGN)
            "restart" -> flow.requestConfirm(ConfirmKind.RESTART)
            "confirm" -> flow.confirm()
            "no" -> flow.cancelConfirm()
            "exitask" -> { Ui.page = Page.SETTINGS; Ui.exitConfirm = true }
            "newask" -> { Ui.page = Page.NEW; if (flow.state.value.hasProgress) Ui.newConfirm = true }
            "page" -> Ui.page = when (a) { "new" -> Page.NEW; "rules" -> Page.RULES; "settings" -> Page.SETTINGS; else -> Page.PLAY }
            "lang" -> settings { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "sound" -> settings { it.copy(sound = on) }
            "volume" -> a.toIntOrNull()?.let { v -> settings { it.copy(volume = v.coerceIn(0, 2)) } }
            "reduced" -> settings { it.copy(reducedMotion = on) }
            "lit" -> { settings { it.copy(litDone = !on) }; flow.debugState { it.copy(lit = on) } }
            "slow" -> flow.setSlow(a.toIntOrNull() ?: 1)
            "seed" -> a.toIntOrNull()?.let { flow.reseed(it) }
            "freeze" -> flow.freeze(if (a == "off") null else a.toLongOrNull())
            "hold" -> flow.holdThinking = on
            "s00hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", on).apply()
            "welcome" -> flow.welcomeDone()
            "reopen" -> graph.window.reopen()
            "closepanel" -> graph.window.closeControlOnly()
            "dump", "state" -> Log.i(TAG, "DUMP ${flow.dump()} page=${Ui.page} window=${graph.window.panelState}")
            "evidence" -> Log.i(TAG, "EVIDENCE ${SceneHolder.scene?.evidence() ?: "no scene"}")
            else -> Log.w(TAG, "unknown debug command: $cmd")
        }
    }
}
