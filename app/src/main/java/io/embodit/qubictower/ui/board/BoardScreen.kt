package io.embodit.qubictower.ui.board

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.qubictower.R
import io.embodit.qubictower.domain.Layout
import io.embodit.qubictower.domain.Mode
import io.embodit.qubictower.domain.Phase
import io.embodit.qubictower.domain.PlayState
import io.embodit.qubictower.domain.Qubic
import io.embodit.qubictower.domain.Sound
import io.embodit.qubictower.domain.Turntable
import io.embodit.qubictower.platform.Graph
import io.embodit.qubictower.platform.TAG
import io.embodit.qubictower.ui.board.input.BoardInputConfig
import io.embodit.qubictower.ui.board.input.BoardInputListener
import io.embodit.qubictower.ui.board.input.InputSource
import io.embodit.qubictower.ui.board.input.Offset3M
import io.embodit.qubictower.ui.board.input.TargetResolver
import io.embodit.qubictower.ui.board.input.boardInput
import io.embodit.qubictower.ui.theme.QtColors
import io.embodit.qubictower.ui.theme.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Runs the volume's per-frame motion inside the ECS (never in Compose) and feeds the window's level gate. */
class BoardFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        SceneHolder.scene?.onFrame(context.deltaTime)
        SceneHolder.graph?.window?.stepFrame()
    }
}

internal object SceneHolder {
    @Volatile var scene: BoardScene? = null
    @Volatile var graph: Graph? = null
}

private const val SEAT = "seatTag"
private const val COUNT_A = "countAmber"
private const val COUNT_C = "countCeladon"

/**
 * The board volume (default container). Opens the control window beside it through the level gate, shows the tower,
 * and routes every pinch / trigger on a collider (peg, plinth top, turntable rim) to [GameFlow]; a drag on the rim
 * pushes the turntable.
 */
@Composable
fun BoardScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scene = remember { BoardScene() }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(navigator) {
        graph.window.attach(navigator)
        graph.window.request("start")
        onDispose { graph.window.detach() }
    }

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.flow.onBackground() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> { Log.i(TAG, "LIFECYCLE foreground"); graph.flow.onForeground() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(scene) {
        SceneHolder.scene = scene
        SceneHolder.graph = graph
        registerSystem<BoardFrameSystem>()
        onDispose {
            unregisterSystem<BoardFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            scene.dispose()
        }
    }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            // D50: the factory board-input template (taps + short drags the system reports as drags, targets resolved
            // up the parent chain, hover) for hand ray, controller ray and eye-hand alike.
            .boardInput(BoardTargets, remember(graph, scene) { BoardListener(graph, scene) }, BoardInputConfig(logTag = TAG)),
        attachments = {
            AttachmentPanel(SEAT) { TagRoot(graph) { st -> SeatTag(st, graph) } }
            AttachmentPanel(COUNT_A) { TagRoot(graph) { st -> CountTag(st, Qubic.AMBER) } }
            AttachmentPanel(COUNT_C) { TagRoot(graph) { st -> CountTag(st, Qubic.CELADON) } }
        },
        initial = { content, attachments ->
            content.addEntity(scene.root)
            val quick = withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    // gate:allow geometry S00 loading stand-in (design lifecycle "首帧先出 3 个基本盒子兜底"), removed when the GLBs are in
                    val box = MeshResource.createBox(Vector3(1f, 1f, 1f), cornerRadius = 0f)
                    val mat = PhysicallyBasedMaterial.create().apply { setBaseColor(QtColors.Graphite.c4(1f)); setRoughness(0.7f) }
                    box to mat
                }.getOrNull()
            }
            quick?.let { (b, m) -> scene.showFallback(b, m) }
            val t0 = android.os.SystemClock.elapsedRealtime()
            suspend fun load(n: String): Entity = Entity.loadSuspend("asset://models/$n.glb").also { it.enabled = false; content.addEntity(it) }
            val models = runCatching {
                Models(load("plinth"), load("trays"), load("traypegs"), load("base"), load("turntable"), load("peg"), load("bead"))
            }.onFailure { Log.e(TAG, "MODELS failed", it) }.getOrNull()
            val resources = withContext(NonCancellable + Dispatchers.Default) { SceneResources.create() }
            Log.i(TAG, "MODELS loaded=${models != null} in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            val prefs = context.getSharedPreferences("debug", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 10 s"); kotlinx.coroutines.delay(10_000) }
            if (models != null) scene.install(resources, models, listOf(
                attachments.entity(SEAT) to Layout.seatCentre(),
                attachments.entity(COUNT_A) to Layout.countCentre(Qubic.AMBER),
                attachments.entity(COUNT_C) to Layout.countCentre(Qubic.CELADON),
            ).mapNotNull { (e, at) -> e?.let { it to at } })
        },
        update = { content, _ ->
            if (viewSize != IntSize.Zero) {
                val corner = content.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                scene.setRegion(abs(corner.y))
            }
            scene.apply(state, settings)
        },
    )
}

/** Hit entity (or an ancestor) -> the collider name the game knows: peg:<n>, plinth, grip. */
internal object BoardTargets : TargetResolver<String> {
    override fun resolve(entity: Entity): String? {
        val name = runCatching { entity.getName() }.getOrNull() ?: return null
        return when (val h = BoardScene.hitOf(name)) {
            is BoardScene.Companion.PegHit -> "peg:${h.peg}"
            BoardScene.Companion.PlinthHit -> "plinth"
            BoardScene.Companion.GripHit -> "grip"
            BoardScene.Companion.NoHit -> null
        }
    }
}

/** Every resolved tap goes through [route] (the same calls the panel and the debug broadcast make); a rim drag pushes. */
internal class BoardListener(private val graph: Graph, private val scene: BoardScene) : BoardInputListener<String> {
    private var lastTick = 0
    override fun onTap(target: String?, source: InputSource, position: Offset3D?) {
        Log.i(TAG, "PINCH target=$target src=${source.label}")
        route(graph, target)
    }

    override fun onHover(target: String?, source: InputSource) {
        scene.setHover((BoardScene.hitOf(target) as? BoardScene.Companion.PegHit)?.peg)
    }

    override fun onDragStart(target: String, source: InputSource): Boolean {
        if (target != "grip") return false
        if (!graph.window.panelOpen) { graph.window.request("pinch"); return false }
        if (!graph.flow.canPush()) return false
        val start = graph.flow.displayDeg()
        scene.beginPush(start)
        lastTick = 0
        Log.i(TAG, "PUSH start deg=$start src=${source.label}")
        return true
    }

    override fun onDragMove(totalM: Offset3M) {
        // the hand's travel in the (tilted) turntable plane
        val c = cos(-Layout.THETA); val s = sin(-Layout.THETA)
        val dz = totalM.y * s + totalM.z * c
        val delta = Turntable.dragAngle(totalM.x, dz)
        scene.pushTo(delta)
        val tick = floor(delta / Turntable.TICK_DEG).toInt()
        if (tick != lastTick) { lastTick = tick; graph.sounds.play(Sound.TURN, 0.9f + 0.02f * (tick % 5)) }
    }

    override fun onDragEnd() {
        val (start, delta) = scene.endPush()
        graph.flow.pushRelease(start, delta)
    }

    override fun onDragCancel() {
        val (start, _) = scene.endPush()
        graph.flow.pushRelease(start, 0f)
    }
}

/** Collider name -> the one flow call (the same calls the debug broadcast makes). The first pinch after the system ×
 *  only reopens the control window (S10, design input). */
internal fun route(graph: Graph, name: String?) {
    val flow = graph.flow
    val hit = BoardScene.hitOf(name)
    if (hit != BoardScene.Companion.NoHit && !graph.window.panelOpen) {
        Log.i(TAG, "S10 pinch reopens the control window (consumed)")
        graph.window.request("pinch")
        return
    }
    when (hit) {
        is BoardScene.Companion.PegHit -> flow.tapPeg(hit.peg)
        BoardScene.Companion.PlinthHit -> flow.tapPlinth()
        BoardScene.Companion.GripHit -> flow.tapRim()
        BoardScene.Companion.NoHit -> Unit
    }
}

/** A tag is its own composition: it reads state + language itself so a language switch always reaches it. */
@Composable
private fun TagRoot(graph: Graph, content: @Composable (PlayState) -> Unit) {
    val base = LocalContext.current
    val st by graph.flow.state.collectAsStateWithLifecycle()
    val s by graph.settings.settings.collectAsStateWithLifecycle()
    key(s.language) {
        CompositionLocalProvider(LocalContext provides base.localized(s.language)) { content(st) }
    }
}

@Composable
private fun tagText(text: String, tag: String, size: Int = Layout.TAG_FONT_SP, color: Color = QtColors.TagInk) {
    Text(text, color = color, fontSize = size.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") })
}

/** Seat tag (design attachments): whose turn (+ "pass it on" in two-player), the result, or "open the panel" after ×. */
@Composable
private fun SeatTag(st: PlayState, graph: Graph) {
    val window = graph.window
    Box(
        Modifier.size(Layout.SEAT_W_DP.dp, Layout.SEAT_H_DP.dp).clip(RoundedCornerShape(8.dp)).background(QtColors.TagFace),
        contentAlignment = Alignment.Center,
    ) {
        val cfg = st.config
        val text = when {
            window.levelHint -> stringResource(R.string.level_hint)
            !window.panelOpen && window.panelState != "never" -> stringResource(R.string.seat_open_panel)
            cfg == null || st.phase == Phase.SETUP || st.phase == Phase.LOADING -> stringResource(R.string.seat_ready)
            st.result != null -> resultLine(st)
            st.phase == Phase.CLEARING -> stringResource(R.string.seat_clearing)
            cfg.mode == Mode.PVP -> stringResource(if (st.side == Qubic.AMBER) R.string.seat_pass_amber else R.string.seat_pass_celadon)
            cfg.isHuman(st.side) -> stringResource(R.string.seat_you)
            else -> stringResource(R.string.seat_ai)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
            if (cfg != null && st.result == null && st.phase != Phase.SETUP && window.panelOpen) {
                Box(Modifier.size(16.dp).clip(CircleShape).background(if (st.side == Qubic.AMBER) QtColors.Amber else QtColors.Celadon).border(1.dp, QtColors.TagInk, CircleShape))
                Spacer(Modifier.width(8.dp))
            }
            tagText(text, "seat/turn")
        }
    }
}

@Composable
private fun resultLine(st: PlayState): String {
    val r = st.result ?: return ""
    val cfg = st.config
    return when {
        r.draw -> stringResource(R.string.seat_draw)
        cfg?.mode == Mode.PVE && r.winner == cfg.human -> stringResource(R.string.seat_you_won)
        cfg?.mode == Mode.PVE -> stringResource(R.string.seat_ai_won)
        r.winner == Qubic.AMBER -> stringResource(R.string.seat_amber_won)
        else -> stringResource(R.string.seat_celadon_won)
    }
}

/** Tray counter (design attachments): beads left in that tray, counting down from 32 (the aimed bead already counts). */
@Composable
private fun CountTag(st: PlayState, side: Int) {
    Box(
        Modifier.size(Layout.COUNT_W_DP.dp, Layout.COUNT_H_DP.dp).clip(RoundedCornerShape(8.dp)).background(QtColors.TagFace),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (side == Qubic.AMBER) QtColors.Amber else QtColors.Celadon))
            Spacer(Modifier.width(6.dp))
            tagText(st.trayCount(side).toString(), "count/$side")
        }
    }
}
