package io.embodit.qubictower.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import io.embodit.qubictower.BuildConfig
import io.embodit.qubictower.R
import io.embodit.qubictower.domain.AlertKind
import io.embodit.qubictower.domain.ConfirmKind
import io.embodit.qubictower.domain.Display
import io.embodit.qubictower.domain.Engine
import io.embodit.qubictower.domain.GameSetup
import io.embodit.qubictower.domain.Language
import io.embodit.qubictower.domain.Mode
import io.embodit.qubictower.domain.NoteKind
import io.embodit.qubictower.domain.Phase
import io.embodit.qubictower.domain.PlayState
import io.embodit.qubictower.domain.Qubic
import io.embodit.qubictower.domain.SeatPick
import io.embodit.qubictower.domain.Settings
import io.embodit.qubictower.platform.Graph
import io.embodit.qubictower.platform.TAG
import io.embodit.qubictower.ui.Page
import io.embodit.qubictower.ui.Ui
import io.embodit.qubictower.ui.theme.QtColors
import kotlin.system.exitProcess

/**
 * The control window (Planar WindowContainer right of the tower): all 2D UI, four pages switched by the native SpatialUI
 * TabBar (D8): 对局 / 新局 / 规则 / 设置. Every page is laid out top-to-bottom with SpaceBetween so the blank outside the
 * content stays inside the window padding (blank <= 25 %); confirmations are a whole-page card and lock the tower.
 * Panel buttons call the same GameFlow functions as pinches in the volume; none of them changes the aim (design input).
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // S01: the rules page opens beside the running default game; it never blocks the tower.
    LaunchedEffect(state.welcome) { if (state.welcome) Ui.page = Page.RULES }
    LaunchedEffect(state.phase) {
        when (state.phase) {
            Phase.SETUP -> Ui.page = Page.NEW
            Phase.AIMING, Phase.DROPPING, Phase.THINKING, Phase.CLEARING -> if (Ui.page == Page.NEW && !Ui.newConfirm) Ui.page = Page.PLAY
            else -> Unit
        }
    }
    LaunchedEffect(Ui.exitConfirm, Ui.newConfirm) { graph.flow.uiLocked = Ui.exitConfirm || Ui.newConfirm }
    LaunchedEffect(Ui.page) {
        Log.i(TAG, "PAGE ${Ui.page} ${Ui.page.stateId}")
        if (Ui.page != Page.SETTINGS) Ui.exitConfirm = false
        if (Ui.page != Page.NEW) Ui.newConfirm = false
    }

    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        when (Ui.page) {
            Page.PLAY -> PlayPage(state, settings, graph)
            Page.NEW -> NewPage(state, settings, graph)
            Page.RULES -> RulesPage(state, settings, graph)
            Page.SETTINGS -> SettingsPage(state, settings, graph)
        }
    }

    // The TabBar ornament is composed apart from this window's localized context: resolve the labels here (in the
    // window's language) and hand plain strings in, so English shows English tabs after a cold start too.
    val tabs = listOf(
        Triple(Page.PLAY, R.drawable.ic_tab_play, stringResource(R.string.tab_play)),
        Triple(Page.NEW, R.drawable.ic_tab_new, stringResource(R.string.tab_new)),
        Triple(Page.RULES, R.drawable.ic_tab_rules, stringResource(R.string.tab_rules)),
        Triple(Page.SETTINGS, R.drawable.ic_tab_settings, stringResource(R.string.tab_settings)),
    )
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = Ui.page == page,
                onClick = { Ui.page = page },
                mainContent = { Icon(painter = painterResource(icon), contentDescription = label) },
                supportContent = { Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelMedium) },
            )
        }
    }
}

/** Every page: header at the top, last row at the bottom, blocks share the height in between (blank <= 25 %). */
@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, content = content)
}

/** Page header (56): a title (+ the seat chip on the game page), a small line, and [静音] unless the page has its own. */
@Composable
private fun Header(title: String, tag: String, settings: Settings, graph: Graph, side: Int? = null, sub: String? = null, mute: Boolean = true) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (side != null) { SeatChip(side, 24.dp); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Txt(title, "$tag/title", style = PicoTheme.typography.titleLarge)
            if (sub != null) Txt(sub, "$tag/sub", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary)
        }
        if (mute) ToggleButton(stringResource(R.string.btn_mute), "$tag/mute", on = !settings.sound, onClick = { graph.settings.update { it.copy(sound = !it.sound) } }, icon = R.drawable.ic_volume_off)
    }
}

@Composable
private fun sideName(side: Int): String = stringResource(if (side == Qubic.AMBER) R.string.who_amber else R.string.who_celadon)

@Composable
private fun levelName(level: Int): String = stringResource(when (level) { Engine.HARD -> R.string.level_hard; Engine.SERIOUS -> R.string.level_serious; else -> R.string.level_easy })

@Composable
private fun levelFull(level: Int): String = stringResource(when (level) { Engine.HARD -> R.string.level_hard_full; Engine.SERIOUS -> R.string.level_serious_full; else -> R.string.level_easy_full })

@Composable
private fun kindName(k: Qubic.Kind): String = stringResource(when (k) {
    Qubic.Kind.COLUMN -> R.string.kind_column; Qubic.Kind.ROW -> R.string.kind_row; Qubic.Kind.FLAT -> R.string.kind_flat
    Qubic.Kind.CROSS -> R.string.kind_cross; Qubic.Kind.SPACE -> R.string.kind_space
})

@Composable
private fun modeLine(state: PlayState): String {
    val cfg = state.config ?: return ""
    return if (cfg.mode == Mode.PVP) stringResource(R.string.mode_pvp) else stringResource(R.string.mode_pve, levelFull(cfg.level))
}

@Composable
private fun turnTitle(state: PlayState): String {
    val cfg = state.config ?: return stringResource(R.string.title_ready)
    val name = sideName(state.side)
    return when {
        cfg.mode == Mode.PVP -> stringResource(R.string.title_pvp_turn, name)
        cfg.isHuman(state.side) -> stringResource(R.string.title_your_turn, name)
        else -> stringResource(R.string.title_ai_turn, name)
    }
}

// ------------------------------------------------------------------ Game

@Composable
private fun PlayPage(state: PlayState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    if (state.config == null || state.phase in setOf(Phase.SETUP, Phase.LOADING)) {
        PageColumn {
            Header(stringResource(R.string.title_ready), "play", settings, graph)
            Card(Modifier.fillMaxWidth().weight(1f).padding(vertical = 16.dp)) {
                Txt(stringResource(R.string.rules_1), "play/empty", style = PicoTheme.typography.titleMedium, color = QtColors.Ink, maxLines = 2)
                Txt(stringResource(R.string.new_time), "play/empty-time", style = PicoTheme.typography.bodyMedium, color = QtColors.InkSecondary)
            }
            PrimaryButton(stringResource(R.string.btn_start), "play/new", onClick = { Ui.page = Page.NEW }, modifier = Modifier.fillMaxWidth())
        }
        return
    }
    val confirm = state.confirm
    if (confirm != null) { ConfirmPage(confirm, state, settings, graph, onYes = { flow.confirm() }, onNo = { flow.cancelConfirm() }); return }
    if (state.result != null) { ResultPage(state, settings, graph); return }
    val busy = state.buttonTurning
    PageColumn {
        Header(turnTitle(state), "play", settings, graph, side = state.side, sub = modeLine(state), mute = false)
        InfoRow(state)
        StatusCard(state, Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.btn_undo), "play/undo", onClick = { flow.requestUndo() }, enabled = flow.canUndo && !busy, icon = R.drawable.ic_undo, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_redo), "play/redo", onClick = { flow.redo() }, enabled = flow.canRedo && !busy, icon = R.drawable.ic_redo, modifier = Modifier.weight(1f))
            ToggleButton(stringResource(if (state.hintOn) R.string.hint_on else R.string.hint_off), "play/hint", on = state.hintOn, onClick = { flow.setHint(!state.hintOn) }, icon = R.drawable.ic_bulb, modifier = Modifier.weight(1.1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.btn_left), "play/left", onClick = { flow.turnButton(1) }, enabled = !busy, icon = R.drawable.ic_rotate_left, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_right), "play/right", onClick = { flow.turnButton(-1) }, enabled = !busy, icon = R.drawable.ic_rotate_right, modifier = Modifier.weight(1f))
            ToggleButton(stringResource(R.string.btn_mute), "play/mute", on = !settings.sound, onClick = { graph.settings.update { it.copy(sound = !it.sound) } }, icon = R.drawable.ic_volume_off, modifier = Modifier.weight(0.8f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.btn_resign), "play/resign", onClick = { flow.requestConfirm(ConfirmKind.RESIGN) }, enabled = !busy && state.phase != Phase.CLEARING, icon = R.drawable.ic_flag, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_restart), "play/restart", onClick = { flow.requestConfirm(ConfirmKind.RESTART) }, enabled = !busy && state.phase != Phase.CLEARING, icon = R.drawable.ic_replay, modifier = Modifier.weight(1f))
        }
    }
}

/** Move number + both trays (48). */
@Composable
private fun InfoRow(state: PlayState) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Pill(stringResource(R.string.info_move, state.moves.size + 1), "play/move", Modifier.weight(0.8f))
        Row(Modifier.weight(1.4f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(QtColors.CardFace).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            SeatChip(Qubic.AMBER, 14.dp); Spacer(Modifier.width(6.dp))
            Txt(state.trayCount(Qubic.AMBER).toString(), "play/tray-a", style = PicoTheme.typography.titleSmall, color = QtColors.Ink)
            Spacer(Modifier.width(18.dp))
            SeatChip(Qubic.CELADON, 14.dp); Spacer(Modifier.width(6.dp))
            Txt(state.trayCount(Qubic.CELADON).toString(), "play/tray-c", style = PicoTheme.typography.titleSmall, color = QtColors.Ink)
        }
    }
}

@Composable
private fun Pill(text: String, tag: String, modifier: Modifier = Modifier) {
    Box(modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(QtColors.CardFace), contentAlignment = Alignment.Center) {
        Txt(text, tag, style = PicoTheme.typography.titleSmall, color = QtColors.Ink)
    }
}

@Composable
private fun Card(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(QtColors.CardFace).padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically), content = content)
}

/** The status sentence: only facts the engine knows (design content.tone). */
@Composable
private fun StatusCard(state: PlayState, modifier: Modifier) {
    val cfg = state.config ?: return
    val alert = state.alert
    val block = Display.mustBlock(state).firstOrNull()
    val title: String
    var line: String? = null
    var color = QtColors.Ink
    var spinner = false
    when {
        alert != null -> {
            color = QtColors.ErrorText
            title = when (alert.kind) {
                AlertKind.FULL -> stringResource(R.string.alert_full, Qubic.pegName(alert.peg))
                AlertKind.NOT_YOUR_TURN, AlertKind.BUSY, AlertKind.TURNING -> stringResource(R.string.alert_not_turn)
                AlertKind.OVER -> stringResource(R.string.alert_over)
                AlertKind.START_FIRST -> stringResource(R.string.alert_start_first)
            }
        }
        state.phase == Phase.CLEARING -> title = stringResource(R.string.status_clearing)
        state.buttonTurning -> title = stringResource(R.string.status_turning)
        state.phase == Phase.THINKING -> {
            spinner = true
            title = stringResource(R.string.status_thinking, levelName(cfg.level))
            if (cfg.level == Engine.HARD) line = stringResource(R.string.hard_note)
        }
        state.phase == Phase.DROPPING -> title = stringResource(R.string.status_dropping)
        state.phase == Phase.AIMING && state.aim != null -> {
            title = aimSentence(state, state.aim)
            if (block != null) { line = stringResource(R.string.status_must_block_title, kindName(block.first.kind)); color = QtColors.ErrorText }
        }
        block != null -> {
            color = QtColors.ErrorText
            title = stringResource(R.string.status_must_block_title, kindName(block.first.kind))
            line = stringResource(R.string.status_must_block)
        }
        else -> {
            val note = state.note
            title = when {
                state.restored -> stringResource(R.string.status_restored)
                note?.kind == NoteKind.UNDONE -> stringResource(R.string.status_undone)
                note?.kind == NoteKind.REDONE -> stringResource(R.string.status_redone)
                note?.kind == NoteKind.THREE && note.lineKind != null -> if (cfg.isHuman(note.side) && cfg.mode == Mode.PVE) stringResource(R.string.status_three_you, kindName(note.lineKind)) else stringResource(R.string.status_three, sideName(note.side))
                note?.kind == NoteKind.BLOCKED -> if (cfg.isHuman(note.side) && cfg.mode == Mode.PVE) stringResource(R.string.status_blocked_you) else stringResource(R.string.status_blocked, sideName(note.side))
                cfg.mode == Mode.PVP -> stringResource(R.string.status_idle_pvp, sideName(state.side))
                else -> stringResource(R.string.status_idle)
            }
            line = when {
                state.lit -> stringResource(R.string.status_lit)
                state.hintOn && state.hint != null -> stringResource(R.string.status_hint)
                note?.kind in setOf(NoteKind.THREE, NoteKind.BLOCKED, NoteKind.UNDONE, NoteKind.REDONE) || state.restored -> stringResource(if (cfg.mode == Mode.PVP) R.string.status_idle_pvp else R.string.status_idle, sideName(state.side))
                else -> stringResource(R.string.push_note)
            }
            if (cfg.mode == Mode.PVE && cfg.level == Engine.HARD && line == stringResource(R.string.push_note)) line = stringResource(R.string.hard_note)
        }
    }
    Row(modifier.clip(RoundedCornerShape(24.dp)).background(QtColors.CardFace).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        if (spinner) { CircularProgressIndicator(modifier = Modifier.size(30.dp)); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f).fillMaxSize().padding(vertical = 14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(title, "play/status", style = PicoTheme.typography.titleLarge, color = color, maxLines = 3)
                if (line != null) Txt(line, "play/status-line", style = PicoTheme.typography.bodyLarge, color = if (color == QtColors.ErrorText && block != null && state.phase != Phase.AIMING) QtColors.ErrorText else QtColors.InkSecondary, maxLines = 3)
            }
            LineFacts(state)
            LineLegend()
        }
    }
}

/**
 * The lines in numbers (same source as the beams, D38): the lines through the focused spot as chips (kind + beads, in
 * the owner's colour), or, with nothing aimed, each side's open threes and twos.
 */
@Composable
private fun LineFacts(state: PlayState) {
    val b = state.board
    val focus = Display.focusCell(state, android.os.SystemClock.uptimeMillis())
    if (focus >= 0 && state.result == null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(stringResource(R.string.lines_through, Qubic.LINES_THROUGH[focus].size), "play/lines-title", style = PicoTheme.typography.labelMedium, color = QtColors.InkSecondary)
            Qubic.linesThrough(focus).map { Qubic.lineState(b, it) }.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { ls ->
                        val c = when { ls.dead -> Color(0xFF5A6068); ls.empty -> QtColors.InkSecondary; ls.owner == Qubic.AMBER -> QtColors.AmberInk; else -> QtColors.CeladonInk }
                        Box(Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                            Txt(kindName(ls.line.kind) + if (ls.empty) "" else " ${ls.count}/4", "play/line-chip", style = PicoTheme.typography.labelSmall, color = c)
                        }
                    }
                }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (side in 0..1) {
                val own = b.of(side); val opp = b.of(Qubic.other(side))
                var threes = 0; var twos = 0
                for (l in Qubic.LINES) {
                    if (opp and l.mask != 0L) continue
                    when (java.lang.Long.bitCount(own and l.mask)) { 3 -> threes++; 2 -> twos++ }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SeatChip(side, 14.dp); Spacer(Modifier.width(8.dp))
                    Txt(stringResource(R.string.line_summary, sideName(side), threes, twos), "play/summary", style = PicoTheme.typography.bodyMedium, color = QtColors.Ink)
                }
            }
        }
    }
}

/** What the beam colours mean (design gameSpec.lines.colors), always at the foot of the status card. */
@Composable
private fun LineLegend() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(QtColors.BeamGrey.copy(alpha = 0.6f) to R.string.legend_empty, QtColors.Amber to R.string.legend_amber, QtColors.Celadon to R.string.legend_celadon, QtColors.Danger to R.string.legend_block).forEach { (c, id) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(18.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c))
                Spacer(Modifier.width(5.dp))
                Txt(stringResource(id), "play/legend", style = PicoTheme.typography.labelSmall, color = QtColors.InkSecondary)
            }
        }
    }
}

/** What the aimed spot means: wins, extends your line, blocks theirs, or just where it lands. */
@Composable
private fun aimSentence(state: PlayState, peg: Int): String {
    val b = state.board
    val cell = b.landing(peg)
    if (cell < 0) return stringResource(R.string.alert_full, Qubic.pegName(peg))
    val side = state.side
    if (Qubic.winLine(b.drop(side, peg), side) != null) return stringResource(R.string.status_aim_win)
    val states = Qubic.linesThrough(cell).map { Qubic.lineState(b, it) }
    val own = states.filter { !it.dead && !it.empty && it.owner == side }.maxByOrNull { it.count }
    val opp = states.filter { !it.dead && !it.empty && it.owner == Qubic.other(side) }.maxByOrNull { it.count }
    return when {
        opp != null && opp.count >= 2 && (own == null || opp.count > own.count) -> stringResource(R.string.status_aim_block, kindName(opp.line.kind))
        own != null -> stringResource(R.string.status_aim_own, kindName(own.line.kind), own.count)
        else -> stringResource(R.string.status_aim_plain, Qubic.pegName(peg), Qubic.layerOf(cell) + 1)
    }
}

/** S13: a whole-page confirmation card; the tower is locked while it is open. */
@Composable
private fun ConfirmPage(kind: ConfirmKind?, state: PlayState, settings: Settings, graph: Graph, onYes: () -> Unit, onNo: () -> Unit, newGame: Boolean = false) {
    val other = sideName(Qubic.other(state.side))
    val q: String; val line: String; val yes: String
    when {
        newGame -> { q = stringResource(R.string.confirm_new_game); line = stringResource(R.string.confirm_new_line); yes = stringResource(R.string.confirm_yes_new) }
        kind == ConfirmKind.RESTART -> { q = stringResource(R.string.confirm_restart); line = stringResource(R.string.confirm_restart_line); yes = stringResource(R.string.confirm_yes_restart) }
        kind == ConfirmKind.RESIGN -> { q = stringResource(R.string.confirm_resign); line = stringResource(R.string.confirm_resign_line); yes = stringResource(R.string.confirm_yes_resign) }
        kind == ConfirmKind.EXIT -> { q = stringResource(R.string.confirm_exit); line = stringResource(R.string.confirm_exit_line); yes = stringResource(R.string.confirm_yes_exit) }
        else -> { q = stringResource(R.string.confirm_undo_2p, other); line = stringResource(R.string.confirm_undo_line); yes = stringResource(R.string.confirm_yes_undo) }
    }
    val icon = when { newGame || kind == ConfirmKind.RESTART -> R.drawable.ic_replay; kind == ConfirmKind.RESIGN -> R.drawable.ic_flag; kind == ConfirmKind.EXIT -> R.drawable.ic_exit; else -> R.drawable.ic_undo }
    val danger = kind != ConfirmKind.UNDO_2P || newGame
    val title = when { kind == ConfirmKind.EXIT -> stringResource(R.string.settings_title); newGame -> stringResource(R.string.new_title); else -> stringResource(R.string.tab_play) }
    PageColumn {
        Header(title, "confirm", settings, graph)
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 14.dp).clip(RoundedCornerShape(28.dp)).background(QtColors.CardFace).padding(24.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(56.dp), tint = QtColors.Ink)
            Txt(q, "confirm/${kind?.name ?: "NEW"}", style = PicoTheme.typography.titleLarge.copy(fontSize = 26.sp), color = QtColors.Ink, maxLines = 2)
            Txt(line, "confirm/line", style = PicoTheme.typography.bodyLarge, color = QtColors.InkSecondary, maxLines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(if (kind == ConfirmKind.UNDO_2P && !newGame) R.string.btn_never else R.string.btn_cancel), "confirm/cancel", onClick = onNo, modifier = Modifier.weight(1f).height(64.dp))
                SecondaryButton(yes, "confirm/yes", danger = danger, onClick = onYes, modifier = Modifier.weight(1f).height(64.dp))
            }
        }
        Txt(stringResource(R.string.lock_line), "confirm/lock", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** S09: the result card (whole page). After the game you can still undo and turn the tower. */
@Composable
private fun ResultPage(state: PlayState, settings: Settings, graph: Graph) {
    val r = state.result ?: return
    val cfg = state.config ?: return
    val flow = graph.flow
    val title = stringResource(when {
        r.draw -> R.string.result_draw
        cfg.mode == Mode.PVE && r.winner == cfg.human -> R.string.result_you_win
        cfg.mode == Mode.PVE -> R.string.result_ai_win
        r.winner == Qubic.AMBER -> R.string.result_amber_win
        else -> R.string.result_celadon_win
    })
    val line = when {
        r.draw -> stringResource(R.string.result_draw_line)
        r.resigned -> stringResource(R.string.result_resigned, r.moves)
        r.line != null && r.winner != null -> stringResource(R.string.result_line, sideName(r.winner), kindName(r.line.kind), r.moves)
        else -> ""
    }
    PageColumn {
        Header(stringResource(R.string.tab_play), "result", settings, graph, sub = modeLine(state))
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp).clip(RoundedCornerShape(28.dp)).background(QtColors.CardFace).padding(22.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r.winner != null) { SeatChip(r.winner, 30.dp); Spacer(Modifier.width(14.dp)) }
                Txt(title, "result/title", style = PicoTheme.typography.titleLarge.copy(fontSize = 36.sp, fontWeight = FontWeight.Bold), color = QtColors.Ink)
            }
            Txt(line, "result/line", style = PicoTheme.typography.titleMedium, color = QtColors.Ink, maxLines = 2)
            if (!r.resigned && !r.draw) Txt(stringResource(R.string.result_footnote), "result/footnote", style = PicoTheme.typography.bodyMedium, color = QtColors.InkSecondary, maxLines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(stringResource(R.string.btn_again), "result/again", onClick = { flow.again() }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_menu), "result/menu", onClick = { flow.toMenu() }, modifier = Modifier.weight(1f).height(56.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.btn_undo), "result/undo", onClick = { flow.requestUndo() }, enabled = flow.canUndo, icon = R.drawable.ic_undo, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_left), "result/left", onClick = { flow.turnButton(1) }, enabled = !state.buttonTurning, icon = R.drawable.ic_rotate_left, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_right), "result/right", onClick = { flow.turnButton(-1) }, enabled = !state.buttonTurning, icon = R.drawable.ic_rotate_right, modifier = Modifier.weight(1f))
        }
    }
}

// ------------------------------------------------------------------ New game

@Composable
private fun NewPage(state: PlayState, settings: Settings, graph: Graph) {
    val setup = settings.lastSetup
    fun set(change: (GameSetup) -> GameSetup) = graph.settings.update { it.copy(lastSetup = change(it.lastSetup)) }
    fun start() { Ui.newConfirm = false; graph.flow.newGame(setup); Ui.page = Page.PLAY }
    if (Ui.newConfirm) {
        // D44: starting over drops the running game, ask first (whole-page card).
        ConfirmPage(null, state, settings, graph, onYes = { start() }, onNo = { Ui.newConfirm = false }, newGame = true)
        return
    }
    val pve = setup.mode == Mode.PVE
    val hard = pve && setup.level == Engine.HARD
    PageColumn {
        Header(stringResource(R.string.new_title), "new", settings, graph)
        if (state.savedAvailable && state.phase == Phase.SETUP) {
            SecondaryButton(stringResource(R.string.btn_continue_n, state.savedMoves + 1), "new/continue", onClick = { graph.flow.continueSaved(); Ui.page = Page.PLAY }, modifier = Modifier.fillMaxWidth().height(52.dp))
        }
        Group(stringResource(R.string.new_who), "new/opponent") {
            // gate:allow tabs-segment same-page option of the new game (titled group: opponent / level / seat), not a page switch
            Segments(listOf(Mode.PVE to stringResource(R.string.new_vs_ai), Mode.PVP to stringResource(R.string.new_pvp)), setup.mode, "new/opponent", { m -> set { it.copy(mode = m) } }, Modifier.fillMaxWidth())
        }
        if (pve) {
            Group(stringResource(R.string.new_level), "new/level") {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // gate:allow tabs-segment same-page option of the new game (titled group: opponent / level / seat), not a page switch
                    Segments(listOf(Engine.EASY to stringResource(R.string.level_easy), Engine.SERIOUS to stringResource(R.string.level_serious), Engine.HARD to stringResource(R.string.level_hard)), setup.level, "new/level", { l -> set { it.copy(level = l) } }, Modifier.fillMaxWidth())
                    Txt(stringResource(when (setup.level) { Engine.HARD -> R.string.level_hard_line; Engine.SERIOUS -> R.string.level_serious_line; else -> R.string.level_easy_line }), "new/level-line",
                        style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
                }
            }
            Group(stringResource(R.string.new_seat), "new/seat") {
                if (hard) {
                    Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(QtColors.CardFace).padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
                        Row(verticalAlignment = Alignment.CenterVertically) { SeatChip(Qubic.AMBER, 16.dp); Spacer(Modifier.width(10.dp)); Txt(stringResource(R.string.new_hard_seat), "new/hard-seat", style = PicoTheme.typography.labelLarge, color = QtColors.Ink) }
                    }
                } else {
                    // gate:allow tabs-segment same-page option of the new game (titled group: opponent / level / seat), not a page switch
                    Segments(listOf(SeatPick.AMBER to stringResource(R.string.seat_amber_first), SeatPick.CELADON to stringResource(R.string.seat_celadon), SeatPick.RANDOM to stringResource(R.string.seat_random)),
                        setup.seat, "new/seat", { f -> set { it.copy(seat = f) } }, Modifier.fillMaxWidth())
                }
            }
        } else {
            Card(Modifier.fillMaxWidth().height(132.dp)) {
                Txt(stringResource(R.string.mode_pvp), "new/pvp-title", style = PicoTheme.typography.titleSmall, color = QtColors.Ink)
                Txt(stringResource(R.string.new_pvp_note), "new/pvp-note", style = PicoTheme.typography.bodyMedium, color = QtColors.InkSecondary, maxLines = 3)
            }
        }
        PrimaryButton(stringResource(R.string.btn_start), "new/start", onClick = {
            if (state.hasProgress) { Ui.newConfirm = true; Log.i(TAG, "CONFIRM NEW_GAME S13") } else start()
        }, modifier = Modifier.fillMaxWidth())
        Txt(stringResource(R.string.new_time), "new/time", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** A titled option group: title 20 + a full-width control. */
@Composable
private fun Group(title: String, tag: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(title, "$tag/title", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth()) { content() }
    }
}

// ------------------------------------------------------------------ Rules

@Composable
private fun RulesPage(state: PlayState, settings: Settings, graph: Graph) {
    PageColumn {
        Header(stringResource(if (state.welcome) R.string.welcome_title else R.string.rules_title), "rules", settings, graph)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(R.string.rules_1, R.string.rules_2, R.string.rules_3).forEachIndexed { i, id ->
                Column(Modifier.weight(1f).height(150.dp).clip(RoundedCornerShape(18.dp)).background(QtColors.CardFace).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Canvas(Modifier.fillMaxWidth().height(62.dp)) { diagram(i) }
                    Txt(stringResource(id), "rules/step$i", style = PicoTheme.typography.bodySmall, color = QtColors.Ink, maxLines = 4)
                }
            }
        }
        Txt(stringResource(R.string.rules_mistakes), "rules/mistakes", style = PicoTheme.typography.titleSmall)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(R.string.mistake_1, R.string.mistake_2, R.string.mistake_3).forEachIndexed { i, id ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(QtColors.Primary), contentAlignment = Alignment.Center) {
                        Txt("${i + 1}", "rules/n", style = PicoTheme.typography.labelSmall, color = QtColors.OnPrimary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Txt(stringResource(id), "rules/mistake", style = PicoTheme.typography.bodySmall, maxLines = 2, modifier = Modifier.weight(1f))
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(stringResource(R.string.rules_drag), "rules/drag", style = PicoTheme.typography.bodySmall, color = QtColors.Ink, maxLines = 2)
            Txt(stringResource(R.string.rules_hard), "rules/hard", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
            Txt(stringResource(R.string.rules_pvp), "rules/pvp", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        }
        // S01 [开始] only switches to the game page: the easy game (you first) is already set up behind it.
        PrimaryButton(stringResource(if (state.welcome) R.string.btn_start_easy else R.string.btn_back_game), "rules/start", onClick = { graph.flow.welcomeDone(); Ui.page = Page.PLAY }, modifier = Modifier.fillMaxWidth())
    }
}

/** The three rule cells: pinch a peg / the bead slides down / four in a cross-layer diagonal + the turntable. */
private fun DrawScope.diagram(i: Int) {
    val w = size.width; val h = size.height
    val peg = Color(0xFF9AA3AE); val amber = QtColors.Amber; val celadon = QtColors.Celadon; val ink = QtColors.Ink
    fun bead(cx: Float, cy: Float, c: Color) {
        drawOval(c, Offset(cx - 13f, cy - 8f), androidx.compose.ui.geometry.Size(26f, 16f))
    }
    when (i) {
        0 -> {
            for (k in 0..2) drawLine(peg, Offset(w * (0.3f + 0.2f * k), h * 0.2f), Offset(w * (0.3f + 0.2f * k), h * 0.95f), strokeWidth = 4f)
            bead(w * 0.5f, h * 0.12f, amber)
            // a pinch: two fingertips
            drawCircle(ink, 5f, Offset(w * 0.68f, h * 0.18f)); drawCircle(ink, 5f, Offset(w * 0.76f, h * 0.30f))
        }
        1 -> {
            drawLine(peg, Offset(w * 0.5f, h * 0.05f), Offset(w * 0.5f, h * 0.95f), strokeWidth = 4f)
            bead(w * 0.5f, h * 0.84f, celadon)
            bead(w * 0.5f, h * 0.62f, amber)
            val p = Path().apply { moveTo(w * 0.68f, h * 0.15f); lineTo(w * 0.68f, h * 0.55f) }
            drawPath(p, ink, style = Stroke(3f))
            drawPath(Path().apply { moveTo(w * 0.64f, h * 0.47f); lineTo(w * 0.68f, h * 0.56f); lineTo(w * 0.72f, h * 0.47f) }, ink, style = Stroke(3f))
        }
        else -> {
            for (k in 0..3) {
                val x = w * (0.2f + 0.2f * k)
                drawLine(peg, Offset(x, h * 0.1f), Offset(x, h * 0.95f), strokeWidth = 4f)
                for (l in 0 until k) bead(x, h * (0.86f - 0.18f * l), celadon)
                bead(x, h * (0.86f - 0.18f * k), amber)
            }
            drawLine(amber, Offset(w * 0.12f, h * 0.92f), Offset(w * 0.88f, h * 0.24f), strokeWidth = 3f)
        }
    }
}

// ------------------------------------------------------------------ Settings

@Composable
private fun SettingsPage(state: PlayState, settings: Settings, graph: Graph) {
    val context = LocalContext.current
    fun upd(change: (Settings) -> Settings) { graph.settings.update(change) }
    if (Ui.exitConfirm) {
        ConfirmPage(ConfirmKind.EXIT, state, settings, graph, onYes = { exitApp(context, graph) }, onNo = { Ui.exitConfirm = false })
        return
    }
    val onOff = listOf(true to stringResource(R.string.opt_on), false to stringResource(R.string.opt_off))
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Header(stringResource(R.string.settings_title), "settings", settings, graph)
        GroupHead(stringResource(R.string.settings_group_sound), "settings/g-sound")
        OptionRow(stringResource(R.string.settings_sound), "settings/sound", labelWidth = 130.dp) {
            Segments(onOff, settings.sound, "settings/sound", { v -> upd { it.copy(sound = v) } }, Modifier.width(150.dp))
            Spacer(Modifier.width(12.dp))
            VolumeSlider(settings.volume, onChange = { v -> upd { it.copy(volume = v) } }, modifier = Modifier.weight(1f))
        }
        GroupHead(stringResource(R.string.settings_group_display), "settings/g-display")
        OptionRow(stringResource(R.string.settings_language), "settings/lang", labelWidth = 130.dp) {
            Segments(listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en)), settings.language, "settings/lang", { l -> upd { it.copy(language = l) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_reduced), "settings/reduced", labelWidth = 130.dp) {
            Segments(onOff, settings.reducedMotion, "settings/reduced", { v -> upd { it.copy(reducedMotion = v) } }, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(stringResource(R.string.settings_reopen), "settings/reopen", onClick = { graph.window.reopen() }, icon = R.drawable.ic_open_in_new, modifier = Modifier.width(250.dp))
            Spacer(Modifier.width(12.dp))
            Txt(stringResource(R.string.settings_reopen_hint), "settings/reopen-hint", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.settings_about_label), "settings/about-label", style = PicoTheme.typography.labelLarge, modifier = Modifier.width(130.dp))
            Txt(stringResource(R.string.settings_about_text, BuildConfig.VERSION_NAME), "settings/about", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, modifier = Modifier.weight(1f))
        }
        // D26: quit is the last row; the confirmation is a whole-page card.
        SecondaryButton(stringResource(R.string.settings_exit), "settings/exit", onClick = { Ui.exitConfirm = true; Log.i(TAG, "CONFIRM EXIT S13") }, icon = R.drawable.ic_exit, modifier = Modifier.fillMaxWidth(), danger = true)
    }
}

@Composable
private fun GroupHead(text: String, tag: String) {
    Txt(text, tag, style = PicoTheme.typography.titleSmall, color = QtColors.InkSecondary, modifier = Modifier.height(24.dp))
}

/** D26 / D44 exit: save, release, close both window containers, remove every task, then end the process. */
internal fun exitApp(context: Context, graph: Graph) {
    Log.i(TAG, "EXIT release + close windows + finishAndRemoveTask + exitProcess")
    runCatching { graph.flow.onBackground() }
    runCatching { graph.sounds.release() }
    runCatching { graph.window.closeAll() }
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 600)
}
