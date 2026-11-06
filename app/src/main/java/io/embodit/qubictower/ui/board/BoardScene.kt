package io.embodit.qubictower.ui.board

import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.qubictower.domain.Display
import io.embodit.qubictower.domain.Layout
import io.embodit.qubictower.domain.Phase
import io.embodit.qubictower.domain.PlayState
import io.embodit.qubictower.domain.Qubic
import io.embodit.qubictower.domain.Settings
import io.embodit.qubictower.domain.Turntable
import io.embodit.qubictower.domain.V3
import io.embodit.qubictower.platform.TAG
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The GLB templates (tools/build_models.py). */
internal class Models(
    val plinth: Entity, val trays: Entity, val trayPegs: Entity, val base: Entity, val turntable: Entity, val peg: Entity, val bead: Entity,
)

/**
 * Every entity of the volume (design spatialLayout). Compose hands over [PlayState] + [Settings] through [apply];
 * everything that moves (beads, the turntable, beams, rings) is placed in [onFrame], driven by [BoardFrameSystem]
 * (per-frame motion never in Compose).
 *
 * ```
 * root (volume centre) └ sceneRoot (shifted so the plinth sits on the volume floor)
 *   ├ plinth.glb (+ wedge), trays.glb, traypegs.glb, plinth collider "plinth", key light, 3 tags (front face)
 *   ├ tiltRoot (P, +20° about X) └ towerRoot (−O): base.glb, 12 rim colliders "grip"
 *   │                               └ turnRoot (about Y): turntable.glb, 16 pegs "peg:<n>" (frosted mesh + collider + hover)
 *   └ 64 beads, 8 beams, rings: scene children placed through Layout (turned + tilted maths)
 * ```
 */
internal class BoardScene {
    val root: Entity = Entity().apply { setName("root") }
    private val sceneRoot = Entity().apply { setName("sceneRoot") }
    private val tiltRoot = Entity().apply { setName("tiltRoot") }
    private val towerRoot = Entity().apply { setName("towerRoot") }
    private val turnRoot = Entity().apply { setName("turnRoot") }

    private var res: SceneResources? = null
    private var fallback: List<Entity> = emptyList()
    private var built = false
    private var tags: List<Pair<Entity, V3>> = emptyList()

    private val tiltQ = Quat(Vector3(1f, 0f, 0f), Layout.THETA)
    private var state = PlayState()
    private var settings = Settings()
    private var regionHalfY = -1f
    /** Debug: x-ray beams (depth test off) / see-through dots; eye override for see-through ("E0" .. null = E0 + E1). */
    @Volatile var xray = true
    @Volatile var seeThrough = true
    @Volatile var eye: String? = null

    private val beads = ArrayList<Entity>()          // 0..31 amber, 32..63 celadon
    private val beadMesh = ArrayList<Entity?>()
    private val beadStyle = IntArray(64) { -1 }
    private val beams = ArrayList<ModelEntity>()
    private val beamTint = arrayOfNulls<Display.Tint>(Display.MAX_BEAMS)
    private var ghostRing: ModelEntity? = null
    private var hintRing: ModelEntity? = null
    private var hintDot: ModelEntity? = null
    private var blockRing: ModelEntity? = null
    private var alertRing: ModelEntity? = null
    private var hoverRing: ModelEntity? = null
    private val litRings = ArrayList<ModelEntity>()

    // live push (turntable drag), main thread
    @Volatile private var pushing = false
    @Volatile private var pushStartDeg = 0f
    @Volatile private var pushDelta = 0f
    private var clock = 0f

    @Volatile private var hoverPeg: Int? = null
    @Volatile private var hoverAt = 0L

    init {
        root.addChild(sceneRoot)
        sceneRoot.addChild(tiltRoot)
        tiltRoot.addChild(towerRoot)
        towerRoot.addChild(turnRoot)
        place(tiltRoot, Layout.PIVOT)
        tiltRoot.transform().setQuaternion(tiltQ)
        place(towerRoot, Layout.ORIGIN * -1f)
    }

    val ready: Boolean get() = built

    /** The volume's real half height: keep the plinth on the floor (design floor y = −0.21). */
    fun setRegion(halfY: Float) {
        if (abs(halfY - regionHalfY) < 1e-4f) return
        regionHalfY = halfY
        sceneRoot.transform().setPosition(Vector3(0f, -halfY + Layout.VOLUME.y / 2, 0f))
        Log.i(TAG, "SCENE region halfY=$halfY")
    }

    private fun place(e: Entity, v: V3) = e.transform().setPosition(Vector3(v.x, v.y, v.z))
    private fun yQ(deg: Float) = Quat(Vector3(0f, 1f, 0f), Math.toRadians(deg.toDouble()).toFloat())

    /** D50 hover: the peg under a resting pointer (light feedback only, expires after 250 ms without updates). */
    fun setHover(peg: Int?) {
        if (peg != hoverPeg && peg != null) Log.i(TAG, "HOVER peg=${Qubic.pegName(peg)}")
        hoverPeg = peg; hoverAt = SystemClock.uptimeMillis()
    }

    // ---- push the turntable (the board screen feeds the drag)

    fun beginPush(startDeg: Float) { pushing = true; pushStartDeg = startDeg; pushDelta = 0f }
    fun pushTo(delta: Float) { pushDelta = delta }
    fun endPush(): Pair<Float, Float> { pushing = false; return pushStartDeg to pushDelta }

    /** S00 stand-in: three plain boxes (plinth, wedge base, tower envelope) until the models are in. */
    fun showFallback(box: MeshResource, material: Material) {
        if (built || fallback.isNotEmpty()) return
        fun slab(name: String, at: V3, size: V3, tilted: Boolean) = ModelEntity(box, material).apply {
            setName(name); place(this, at); transform().setScaleVector(Vector3(size.x, size.y, size.z)); if (tilted) transform().setQuaternion(tiltQ)
        }
        fallback = listOf(
            slab("fallbackPlinth", Layout.PLINTH_CENTRE, Layout.PLINTH_SIZE, false),
            slab("fallbackBase", Layout.toScene(V3(0f, -0.125f, 0f)), V3(0.26f, 0.03f, 0.26f), true),
            slab("fallbackTower", Layout.toScene(V3(0f, -0.025f, 0f)), V3(0.23f, 0.15f, 0.23f), true),
        )
        fallback.forEach { sceneRoot.addChild(it) }
        Log.i(TAG, "SCENE fallback shown S00")
    }

    private fun collider(e: Entity, shape: ShapeResource, r: SceneResources, hover: Boolean = false) {
        e.components.set(CollisionComponent(collisionShape = listOf(shape), physicsMaterial = r.physics))
        e.components.set(InteractableComponent())
        if (hover) e.components.set(HoverEffectComponent())
    }

    private fun clone(e: Entity): Entity = e.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true))!!.also { it.enabled = true }

    private fun mark(mesh: MeshResource, m: Material, name: String): ModelEntity =
        ModelEntity(mesh, m).apply { setName(name); enabled = false; sceneRoot.addChild(this) }

    fun install(r: SceneResources, models: Models, tagEntities: List<Pair<Entity, V3>>) {
        if (built) { r.close(); return }
        res = r
        val t0 = SystemClock.elapsedRealtime()
        sceneRoot.addChild(clone(models.plinth).apply { setName("plinthModel") })
        sceneRoot.addChild(clone(models.trays).apply { setName("traysModel") })
        sceneRoot.addChild(clone(models.trayPegs).apply { setName("trayPegsModel"); meshNode()?.components?.get(ModelComponent::class.java)?.materials?.set(0, r.frost) })
        sceneRoot.addChild(Entity().apply { setName("plinth"); collider(this, r.plinthShape, r); place(this, V3(0f, Layout.PLINTH_TOP + 0.001f, 0f)) })
        towerRoot.addChild(clone(models.base).apply { setName("baseModel") })
        // the rim band: 12 boxes around the turntable edge (tiltRoot side: they do not turn)
        repeat(SceneResources.GRIP_SEGMENTS) { k ->
            val a = 2 * PI * (k + 0.5) / SceneResources.GRIP_SEGMENTS
            // gate:allow geometry invisible collider (no mesh): the turntable rim band "grip"
            towerRoot.addChild(Entity().apply {
                setName("grip"); collider(this, r.gripShape, r)
                place(this, V3((SceneResources.GRIP_R * sin(a)).toFloat(), -0.105f, (SceneResources.GRIP_R * kotlin.math.cos(a)).toFloat()))
                transform().setQuaternion(Quat(Vector3(0f, 1f, 0f), a.toFloat()))
            })
        }
        towerRoot.addChild(turnRoot)
        turnRoot.addChild(clone(models.turntable).apply { setName("turntableModel") })
        for (p in 0 until 16) {
            val peg = clone(models.peg).apply { setName("peg:$p") }
            place(peg, V3(Layout.pegX(p), 0f, Layout.pegZ(p)))
            val mesh = peg.meshNode() ?: peg
            mesh.components[ModelComponent::class.java]?.materials?.set(0, r.frost)
            // design pegColliders: 5 x 24.6 x 5 cm, local y −0.10 … +0.146 (covers the beads and the aimed bead);
            // on the peg's mesh node so the system hover effect lights the peg itself (board-input README)
            val hit = Entity().apply { setName("pegHit:$p"); place(this, V3(0f, (Layout.COLLIDER_BOTTOM + Layout.COLLIDER_TOP) / 2, 0f)) }
            collider(hit, r.pegShape, r)
            mesh.components.set(HoverEffectComponent())
            peg.addChild(hit)
            turnRoot.addChild(peg)
        }
        for (side in 0..1) repeat(Qubic.BEADS) { i ->
            val e = clone(models.bead).apply { setName("bead:$side:$i"); enabled = false }
            sceneRoot.addChild(e)
            beads += e
            beadMesh += e.meshNode()
        }
        repeat(Display.MAX_BEAMS) { i -> beams += mark(r.beam, r.beamXray.getValue(Display.Tint.WHITE), "beam:$i") }
        ghostRing = mark(r.ringCell, r.ghost, "ghostRing")
        hintRing = mark(r.ringCell, r.green, "hintRing")
        hintDot = mark(r.dot, r.green, "hintDot")
        blockRing = mark(r.ringCell, r.red, "blockRing")
        alertRing = mark(r.ringTop, r.red, "alertRing")
        hoverRing = mark(r.ringCell, r.hover, "hoverRing")
        repeat(16) { litRings += mark(r.ringTop, r.lit, "litTop:$it") }
        tags = tagEntities
        tags.forEach { (e, at) -> sceneRoot.addChild(e); place(e, at) }
        buildLight()

        fallback.forEach { it.removeFromParent() }
        fallback = emptyList()
        built = true
        Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - t0} ms")
        onFrame(0f)
    }

    private fun buildLight() {
        runCatching {
            val light = Entity().apply { setName("keyLight") }
            // B24: a moderate warm key light from the front right, above (no blown-out highlights on the beads)
            light.components.set(DirectionalLightComponent(Color4(1f, 0.95f, 0.88f, 1f), 0.85f, true))
            val q = Quat(Vector3(0f, 1f, 0f), (30 * PI / 180).toFloat()).times(Quat(Vector3(1f, 0f, 0f), (-55 * PI / 180).toFloat()))
            light.transform().setQuaternion(q)
            root.addChild(light)
        }.onFailure { Log.w(TAG, "LIGHT unavailable: $it") }
    }

    fun apply(next: PlayState, s: Settings) {
        state = next
        settings = s
        if (!built) return
        onFrame(0f)
    }

    /** The turntable angle shown now: the flow's (button turn / snap) plus a live push. */
    fun shownDeg(now: Long): Float {
        val st = state
        val base = st.rot?.let { r -> r.fromDeg + (r.toDeg - r.fromDeg) * Turntable.ease(((now - r.startedAt).toFloat() / r.ms).coerceIn(0f, 1f)) } ?: (st.orientation * 90f)
        return if (pushing) pushStartDeg + pushDelta else base
    }

    // ---------------------------------------------------------------- per frame

    private class Place(val at: V3, val tilted: Boolean, val style: Int)

    fun onFrame(dt: Float) {
        clock += dt
        if (!built) return
        val r = res ?: return
        val st = state
        val now = SystemClock.uptimeMillis()
        val reduced = settings.reducedMotion
        val slow = st.slow.coerceAtLeast(1)
        fun elapsed(start: Long): Long = (now - start).let { e -> st.freezeMs?.takeIf { start >= st.freezeAt }?.let { min(e, it) } ?: e }
        fun anim(start: Long): Long = elapsed(start) / slow
        val deg = shownDeg(now)
        turnRoot.transform().setQuaternion(yQ(deg))
        val b = st.board

        fun cellAt(c: Int) = Layout.onTable(Layout.cellLocal(c), deg)
        fun pegAt(p: Int, y: Float) = Layout.onTable(Layout.pegLocal(p, y), deg)
        fun tray(side: Int, slot: Int) = Layout.traySlot(side, slot)

        // ---- which beads are lit, dimmed or see-through
        val beams = if (st.clear != null) emptyList() else Display.beams(st, now)
        val focus = Display.focusCell(st, now)
        val result = st.result
        val glow = HashSet<Int>()
        val dim = result?.line != null
        if (result?.line != null) {
            // the four light up one by one from the earliest dropped (120 ms each)
            val order = result.line.cells.sortedBy { c -> moveIndexOf(st.moves)[c] }
            order.forEachIndexed { k, c -> if (reduced || anim(result.at) >= k * Layout.WIN_STEP_MS) glow += c }
        } else for (bm in beams) for (c in bm.line.cells) if (b.sideAt(c) >= 0) glow += c
        val see = HashSet<Int>()
        if (seeThrough && result == null && st.clear == null) {
            val target = if (focus >= 0) focus else Display.mustBlock(st).firstOrNull()?.second ?: -1
            if (target >= 0) {
                val occupied = (0 until 64).filter { b.sideAt(it) >= 0 }
                val eyes = eye?.let { listOfNotNull(Layout.EYES[it]) } ?: listOf(Layout.EYES.getValue("E0"), Layout.EYES.getValue("E1"))
                eyes.flatMap { Layout.blockers(occupied, target, it, deg) }.distinct().take(6).forEach { see += it }
                glow.removeAll(see)
            }
        }
        fun styleOf(c: Int) = when {
            c in see -> SceneResources.STYLE_SEE
            c in glow -> SceneResources.STYLE_GLOW
            dim -> SceneResources.STYLE_DIM
            else -> SceneResources.STYLE_NORMAL
        }

        // ---- bead places per side
        val places = List(2) { ArrayList<Place>(32) }
        val drop = st.drop
        val dropping = drop != null && anim(drop.startedAt) < (if (reduced) 120 else Layout.dropMs(Qubic.layerOf(drop.cell))) && b.sideAt(drop.cell) == drop.side
        val inFlight = IntArray(2)
        // returns (undo / cancel): up the peg then an arc home
        for (it in st.returns) {
            val e = anim(it.startedAt)
            val up = it.upMs / slow; val fly = it.flyMs / slow
            if (e >= up + fly) continue
            inFlight[it.side]++
            val peg = if (it.fromCell >= 0) Qubic.pegOf(it.fromCell) else it.fromPeg
            val pos = when {
                e < 0 -> if (it.fromCell >= 0) cellAt(it.fromCell) else pegAt(peg, Layout.HOVER_Y)
                e < up -> { val u = (e.toFloat() / up).coerceIn(0f, 1f); val y0 = Layout.LAYER_Y[Qubic.layerOf(it.fromCell)]; pegAt(peg, y0 + (Layout.HOVER_Y - y0) * (1 - (1 - u) * (1 - u))) }
                else -> Layout.arc(pegAt(peg, Layout.HOVER_Y), tray(it.side, it.slot), ((e - up).toFloat() / fly).coerceIn(0f, 1f), 0.05f)
            }
            places[it.side] += Place(pos, e < up + fly / 2, SceneResources.STYLE_NORMAL)
        }
        // S07 sweep
        val clearing = HashSet<Int>()
        st.clear?.let { cl ->
            for (it in cl.items) {
                val e = elapsed(it.startedAt)
                if (e >= it.flyMs) continue
                clearing += it.fromCell
                inFlight[it.side]++
                val pos = if (e < 0) cellAt(it.fromCell) else Layout.arc(cellAt(it.fromCell), tray(it.side, it.slot), (e.toFloat() / it.flyMs).coerceIn(0f, 1f), 0.07f)
                places[it.side] += Place(pos, e < it.flyMs / 2, SceneResources.STYLE_NORMAL)
            }
        }
        // the tower
        for (c in 0 until 64) {
            val side = b.sideAt(c)
            if (side < 0 || c in clearing) continue
            if (dropping && c == drop!!.cell) continue
            places[side] += Place(cellAt(c), true, styleOf(c))
        }
        // the falling bead
        if (dropping) {
            val d = drop!!
            val y = if (reduced) Layout.LAYER_Y[Qubic.layerOf(d.cell)] else Layout.dropY(Qubic.layerOf(d.cell), anim(d.startedAt))
            places[d.side] += Place(pegAt(d.peg, y), true, styleOf(d.cell))
        }
        // the aimed bead (yours) and the AI's telegraph
        fun lifted(side: Int, peg: Int, fromSlot: Int, fromPeg: Int, at: Long) {
            val e = anim(at)
            val hover = pegAt(peg, Layout.HOVER_Y)
            val pos = when {
                reduced -> hover
                fromPeg >= 0 && e < Layout.SLIDE_MS -> pegAt(fromPeg, Layout.HOVER_Y).lerp(hover, Turntable.ease(e.toFloat() / Layout.SLIDE_MS))
                fromSlot >= 0 && e < Layout.LIFT_MS -> Layout.arc(tray(side, fromSlot), hover, Turntable.ease(e.toFloat() / Layout.LIFT_MS), 0.04f)
                else -> hover
            }
            places[side] += Place(pos, e >= Layout.LIFT_MS / 2 || fromPeg >= 0, SceneResources.STYLE_NORMAL)
        }
        st.aim?.let { lifted(st.side, it, st.aimFrom.traySlot, st.aimFrom.peg, st.aimAt) }
        st.aiAim?.let { lifted(st.side, it, Layout.nextSlot(st.side, st.trayCount(st.side) + 1), -1, st.aiAimAt) }
        // the trays (beads in flight home are not in the tray yet)
        for (side in 0..1) {
            val n = (st.trayCount(side) - inFlight[side]).coerceAtLeast(0)
            val next = Layout.nextSlot(side, n)
            val raise = st.phase == Phase.THINKING && st.aiAim == null && side == st.side && !reduced
            for (slot in Layout.traySlots(side, n)) {
                val at = tray(side, slot) + if (raise && slot == next) V3(0f, 0.008f + 0.002f * sin(clock * 6f), 0f) else V3(0f, 0f, 0f)
                places[side] += Place(at, false, SceneResources.STYLE_NORMAL)
            }
        }
        for (side in 0..1) for (i in 0 until Qubic.BEADS) {
            val idx = side * 32 + i
            val e = beads[idx]
            val pl = places[side].getOrNull(i)
            if (pl == null) { e.enabled = false; continue }
            e.enabled = true
            place(e, pl.at)
            e.transform().setQuaternion(if (pl.tilted) tiltQ.times(yQ(deg)) else Quat.identity())
            if (beadStyle[idx] != pl.style) {
                beadStyle[idx] = pl.style
                beadMesh[idx]?.components?.get(ModelComponent::class.java)?.materials?.set(0, r.bead[side][pl.style])
            }
        }

        // ---- beams (x-ray: depth test off, drawn through the beads)
        this.beams.forEachIndexed { i, ent ->
            val bm = beams.getOrNull(i)
            if (bm == null) { ent.enabled = false; return@forEachIndexed }
            val a = Layout.cellLocal(bm.line.cells[0]); val z = Layout.cellLocal(bm.line.cells[3])
            val dl = (z - a) * (1f / (z - a).length())
            val p0 = Layout.onTable(a - dl * Layout.BEAM_EXTRA, deg); val p1 = Layout.onTable(z + dl * Layout.BEAM_EXTRA, deg)
            val d = p1 - p0
            val len = d.length()
            val dir = d * (1f / len)
            val pulse = if (bm.pulse && !reduced) 1f + 0.35f * (0.5f + 0.5f * sin(clock * 7f)) else 1f
            val dia = bm.diameter * pulse
            place(ent, p0.lerp(p1, 0.5f))
            ent.transform().setQuaternion(fromY(dir))
            ent.transform().setScaleVector(Vector3(dia, len, dia))
            if (beamTint[i] != bm.tint || ent.getName() != (if (xray) "beam:$i" else "beam:$i:d")) {
                beamTint[i] = bm.tint
                ent.setName(if (xray) "beam:$i" else "beam:$i:d")
                ent.components[ModelComponent::class.java]?.materials?.set(0, (if (xray) r.beamXray else r.beamDepth).getValue(bm.tint))
            }
            ent.enabled = true
        }

        // ---- rings (flat around the peg at a cell / a peg top; position follows the peg, round = no facing)
        fun ring(e: ModelEntity?, at: V3?, k: Float = 1f) {
            if (e == null) return
            if (at == null) { e.enabled = false; return }
            place(e, at); e.transform().setQuaternion(tiltQ.times(yQ(deg))); e.transform().setScaleVector(Vector3(k, 1f, k)); e.enabled = true
        }
        val aimPeg = st.aim ?: st.aiAim
        ring(ghostRing, aimPeg?.let { p -> b.landing(p).takeIf { it >= 0 }?.let { cellAt(it) } }, if (reduced) 1f else 1f + 0.04f * sin(clock * 4f))
        val hint = st.hint?.takeIf { st.hintOn && st.humansTurn && st.phase in setOf(Phase.IDLE, Phase.AIMING) }
        ring(hintRing, hint?.let { p -> b.landing(p).takeIf { it >= 0 }?.let { cellAt(it) } })
        ring(hintDot, hint?.let { pegAt(it, Layout.PEG_TOP + 0.006f) })
        val block = Display.mustBlock(st).firstOrNull()?.second
        ring(blockRing, block?.let { cellAt(it) }, if (reduced) 1.06f else 1.06f + 0.05f * sin(clock * 7f))
        val al = st.alert
        val flash = al != null && al.peg >= 0 && (st.freezeMs != null || (now - al.at < 600 && ((now - al.at) / 150) % 2 == 0L))
        ring(alertRing, if (flash) pegAt(al!!.peg, Layout.PEG_TOP + 0.004f) else null, 1.3f)
        val hv = hoverPeg?.takeIf { now - hoverAt < 250 && it != aimPeg && st.phase in setOf(Phase.IDLE, Phase.AIMING) }
        ring(hoverRing, hv?.let { p -> b.landing(p).takeIf { it >= 0 }?.let { cellAt(it) } })
        val litOn = st.lit && st.humansTurn && st.phase in setOf(Phase.IDLE, Phase.AIMING)
        litRings.forEachIndexed { p, e -> ring(e, if (litOn && b.canDrop(p)) pegAt(p, Layout.PEG_TOP + 0.004f) else null, if (reduced) 1f else 1f + 0.12f * sin(clock * 3f + p)) }
    }

    private var moveIdxKey: List<Int>? = null
    private var moveIdx = IntArray(64)

    /** For each cell, the index of the move that filled it (99 = empty); cached per move list. */
    private fun moveIndexOf(moves: List<Int>): IntArray {
        if (moveIdxKey === moves) return moveIdx
        val h = IntArray(16); val out = IntArray(64) { 99 }
        moves.forEachIndexed { k, p -> if (h[p] < 4) out[Qubic.cell(p, h[p]++)] = k }
        moveIdxKey = moves; moveIdx = out
        return out
    }

    /** Rotation taking +Y to [d] (a unit vector). */
    private fun fromY(d: V3): Quat {
        val cosA = d.y.coerceIn(-1f, 1f)
        val ax = V3(d.z, 0f, -d.x)   // Y x d
        val l = sqrt(ax.x * ax.x + ax.z * ax.z)
        if (l < 1e-6f) return if (cosA > 0) Quat.identity() else Quat(Vector3(1f, 0f, 0f), PI.toFloat())
        return Quat(Vector3(ax.x / l, 0f, ax.z / l), acos(cosA))
    }

    fun evidence(): String {
        val deg = shownDeg(SystemClock.uptimeMillis())
        return "deg=$deg halfY=$regionHalfY A1L0=${Layout.onTable(Layout.cellLocal(0), deg)} D4L3=${Layout.onTable(Layout.cellLocal(63), deg)} " +
            "seat=${Layout.seatCentre()} xray=$xray see=$seeThrough eye=${eye ?: "E0+E1"} beads=${beads.count { it.enabled }}"
    }

    fun dispose() {
        res?.close()
        res = null
    }

    companion object {
        sealed interface Hit
        data class PegHit(val peg: Int) : Hit
        data object PlinthHit : Hit
        data object GripHit : Hit
        data object NoHit : Hit

        fun hitOf(name: String?): Hit {
            if (name == null) return NoHit
            val f = name.split(":")
            return when (f[0]) {
                "peg", "pegHit" -> f.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..15 }?.let { PegHit(it) } ?: NoHit
                "plinth" -> PlinthHit
                "grip" -> GripHit
                else -> NoHit
            }
        }
    }
}
