package io.embodit.qubictower.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A small vector for layout maths (metres). Kept free of SDK types so it can be unit tested. */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    fun length(): Float = sqrt(x * x + y * y + z * z)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    override fun toString() = "(%.3f, %.3f, %.3f)".format(x, y, z)
}

/** An axis-aligned box. */
data class Box3(val min: V3, val max: V3) {
    fun contains(p: V3, eps: Float = 0f) = p.x > min.x - eps && p.x < max.x + eps && p.y > min.y - eps && p.y < max.y + eps && p.z > min.z - eps && p.z < max.z + eps

    /** Does the segment a-b pass through the box (slab test)? */
    fun hitBySegment(a: V3, b: V3): Boolean {
        var t0 = 0f; var t1 = 1f
        val d = b - a
        val ad = floatArrayOf(a.x, a.y, a.z); val dd = floatArrayOf(d.x, d.y, d.z)
        val lo = floatArrayOf(min.x, min.y, min.z); val hi = floatArrayOf(max.x, max.y, max.z)
        for (i in 0..2) {
            if (abs(dd[i]) < 1e-9f) { if (ad[i] < lo[i] || ad[i] > hi[i]) return false; continue }
            var ta = (lo[i] - ad[i]) / dd[i]; var tb = (hi[i] - ad[i]) / dd[i]
            if (ta > tb) { val t = ta; ta = tb; tb = t }
            t0 = maxOf(t0, ta); t1 = minOf(t1, tb)
            if (t0 > t1) return false
        }
        return true
    }

    fun corners(): List<V3> = listOf(
        V3(min.x, min.y, min.z), V3(max.x, min.y, min.z), V3(min.x, max.y, min.z), V3(max.x, max.y, min.z),
        V3(min.x, min.y, max.z), V3(max.x, min.y, max.z), V3(min.x, max.y, max.z), V3(max.x, max.y, max.z),
    )

    companion object {
        fun around(c: V3, size: V3) = Box3(c - size * 0.5f, c + size * 0.5f)
    }
}

/**
 * Where everything sits in the volume (design-package spatialLayout). Scene = design coordinates: origin at the volume
 * centre, +X right, +Y up, +Z toward the user, volume x ±0.30, y ±0.21, z ±0.23.
 *
 *   sceneRoot (volume centre)
 *   ├ plinth 0.54 x 0.04 x 0.30 (top y −0.17) + wedge, trays + their 16 short pegs (flat), three tags on the front face
 *   └ tiltRoot at the wedge's front-bottom edge P = (0, −0.17, +0.13), tipped +20° about X (the back rises)
 *     └ towerLocal (offset −O, O = (0, −0.14, +0.13)): base box, grip band colliders
 *       └ turnRoot (about local Y): turntable, 16 pegs, peg colliders; beads, beams and rings are computed through it
 *
 * Tower-local ("local"): base bottom y −0.14, base top −0.11, turntable top −0.10, pegs −0.10…+0.05, layer centres
 * −0.084 / −0.052 / −0.020 / +0.012, the aimed bead +0.10. Scene = P + Rx(20°)·(local − O).
 */
object Layout {
    const val TILT_DEG = 20f
    val THETA: Float = Math.toRadians(TILT_DEG.toDouble()).toFloat()
    val PIVOT = V3(0f, -0.17f, 0.13f)
    val ORIGIN = V3(0f, -0.14f, 0.13f)
    val VOLUME = V3(0.60f, 0.42f, 0.46f)

    const val PITCH = 0.06f
    const val PEG_R = 0.005f
    const val PEG_BOTTOM = -0.10f
    const val PEG_TOP = 0.05f
    const val BEAD_R = 0.025f
    const val BEAD_H = 0.032f
    const val HOLE_R = 0.006f
    val LAYER_Y = floatArrayOf(-0.084f, -0.052f, -0.020f, 0.012f)
    const val HOVER_Y = 0.10f
    const val LIFT_TOP_Y = 0.146f
    const val BASE_W = 0.26f
    const val BASE_BOTTOM = -0.14f
    const val BASE_TOP = -0.11f
    const val TABLE_R = 0.12f
    const val TABLE_Y = -0.105f
    const val TABLE_TOP = -0.10f
    const val GRIP_IN = 0.10f
    const val GRIP_OUT = 0.13f
    const val GRIP_Y = -0.0995f
    const val COLLIDER_W = 0.05f
    const val COLLIDER_BOTTOM = -0.10f
    const val COLLIDER_TOP = 0.146f
    /** Beam: from the first to the fourth cell centre, plus 1.5 cm at each end. */
    const val BEAM_EXTRA = 0.015f

    // plinth / trays (scene, flat)
    val PLINTH_SIZE = V3(0.54f, 0.04f, 0.30f)
    val PLINTH_CENTRE = V3(0f, -0.19f, 0f)
    const val PLINTH_TOP = -0.17f
    const val PLINTH_FRONT = 0.15f
    val TRAY_SIZE = V3(0.11f, 0.02f, 0.22f)
    const val TRAY_X = 0.205f
    const val TRAY_Z = -0.03f
    const val TRAY_TOP = -0.15f
    const val TRAY_PEG_TOP = -0.02f
    val TRAY_COL_DX = floatArrayOf(-0.0275f, 0.0275f)
    val TRAY_ROW_DZ = floatArrayOf(-0.0825f, -0.0275f, 0.0275f, 0.0825f)

    // tags on the plinth front face (attachment panels, facing +Z)
    const val TAG_Z = 0.151f
    const val TAG_Y = -0.19f
    /** Measured attachment scale (royal-ur v0.1.0 emulator, tag vs a 20 cm tray): ~0.00069 m per dp. */
    const val DP_M = 0.00069f
    const val SEAT_W_DP = 376
    const val SEAT_H_DP = 43
    const val COUNT_W_DP = 86
    const val COUNT_H_DP = 43
    const val TAG_FONT_SP = 23   // 1.6 cm cap-to-descender at DP_M

    // animation (design spatialOnly / gameSpec)
    const val G_ALONG_PEG = 2.5f
    const val LIFT_MS = 200L
    const val SLIDE_MS = 120L
    const val BOUNCE_MS = 90L
    const val BOUNCE_M = 0.002f
    const val UNDO_UP_MS = 300L
    const val UNDO_FLY_MS = 200L
    const val CLEAR_FLY_MS = 300L
    const val CLEAR_TOTAL_MS = 1000L
    const val TELEGRAPH_MS = 300L
    const val LINGER_MS = 1500L
    const val WIN_STEP_MS = 120L

    private fun rotX(v: V3, a: Float): V3 { val c = cos(a); val s = sin(a); return V3(v.x, v.y * c - v.z * s, v.y * s + v.z * c) }

    /** Tower-local (already turned) -> scene. */
    fun toScene(local: V3): V3 = PIVOT + rotX(local - ORIGIN, THETA)

    /** Scene -> tower-local (not turned). */
    fun toLocal(scene: V3): V3 = rotX(scene - PIVOT, -THETA) + ORIGIN

    /** Turned local (on the turntable at [deg]) -> tower-local. */
    fun turn(v: V3, deg: Float): V3 { val (x, z) = Turntable.rotY(v.x, v.z, deg); return V3(x, v.y, z) }

    fun onTable(v: V3, deg: Float): V3 = toScene(turn(v, deg))

    fun pegX(peg: Int) = (Qubic.iOf(peg) - 1.5f) * PITCH
    fun pegZ(peg: Int) = (Qubic.jOf(peg) - 1.5f) * PITCH

    /** Turntable-local centre of a cell's bead. */
    fun cellLocal(cell: Int): V3 { val p = Qubic.pegOf(cell); return V3(pegX(p), LAYER_Y[Qubic.layerOf(cell)], pegZ(p)) }
    fun pegLocal(peg: Int, y: Float): V3 = V3(pegX(peg), y, pegZ(peg))

    /** The fall of a dropped bead along its (tilted) peg: from the aim height to its layer under 2.5 m/s². */
    fun fallMs(layer: Int): Long = (sqrt(2.0 * (HOVER_Y - LAYER_Y[layer]) / G_ALONG_PEG) * 1000).toLong()
    fun dropMs(layer: Int): Long = fallMs(layer) + BOUNCE_MS

    /** Height (local y) of a dropping bead [ms] after release (bounce of 2 mm at the end). */
    fun dropY(layer: Int, ms: Long): Float {
        val fall = fallMs(layer)
        if (ms < fall) { val t = ms / 1000f; return HOVER_Y - 0.5f * G_ALONG_PEG * t * t }
        val u = ((ms - fall).toFloat() / BOUNCE_MS).coerceIn(0f, 1f)
        return LAYER_Y[layer] + BOUNCE_M * 4f * u * (1f - u)
    }

    /** Times (ms after release) when a dropping bead passes the empty layers above its landing layer, top first. */
    fun passTimes(layer: Int): List<Long> = (3 downTo layer + 1).map { l -> (sqrt(2.0 * (HOVER_Y - LAYER_Y[l]) / G_ALONG_PEG) * 1000).toLong() }

    // ---------------------------------------------------------------- trays

    /** Scene centre of tray slot [k] (0..31) of [side]: peg (row, column) and height. Amber left, Celadon right. */
    fun traySlot(side: Int, k: Int): V3 {
        val pegIdx = k / 4; val level = k % 4
        val col = pegIdx % 2; val row = pegIdx / 2
        val sx = if (side == Qubic.AMBER) -1f else 1f
        val x = sx * TRAY_X + TRAY_COL_DX[col]
        return V3(x, TRAY_TOP + BEAD_H / 2 + level * BEAD_H, TRAY_Z + TRAY_ROW_DZ[row])
    }

    /** Short tray peg centres (x, z) of [side]. */
    fun trayPegs(side: Int): List<Pair<Float, Float>> {
        val sx = if (side == Qubic.AMBER) -1f else 1f
        return (0 until 8).map { i -> (sx * TRAY_X + TRAY_COL_DX[i % 2]) to (TRAY_Z + TRAY_ROW_DZ[i / 2]) }
    }

    /**
     * The order beads leave a tray (design: front row first, the column next to the tower first, top bead first).
     * With n beads left, slots REMOVAL[32 − n ..] are full and REMOVAL[32 − n] is the next one taken.
     */
    fun removalOrder(side: Int): IntArray {
        val inner = if (side == Qubic.AMBER) 1 else 0  // the column with the smaller |x|
        val out = ArrayList<Int>(32)
        for (row in 3 downTo 0) for (col in listOf(inner, 1 - inner)) for (level in 3 downTo 0) out += (row * 2 + col) * 4 + level
        return out.toIntArray()
    }

    private val REMOVAL = arrayOf(removalOrder(0), removalOrder(1))

    /** Slots occupied by [n] beads left in [side]'s tray. */
    fun traySlots(side: Int, n: Int): List<Int> = REMOVAL[side].takeLast(n.coerceIn(0, 32))

    /** The slot the next bead is taken from when [n] are left. */
    fun nextSlot(side: Int, n: Int): Int = REMOVAL[side][(32 - n).coerceIn(0, 31)]

    /** The slot a returning bead fills when [n] are left before it returns. */
    fun returnSlot(side: Int, n: Int): Int = REMOVAL[side][(31 - n).coerceIn(0, 31)]

    // ---------------------------------------------------------------- tags

    fun seatCentre(): V3 = V3(0f, TAG_Y, TAG_Z)
    fun countCentre(side: Int): V3 = V3(if (side == Qubic.AMBER) -TRAY_X else TRAY_X, TAG_Y, TAG_Z)
    fun seatSize(): V3 = V3(SEAT_W_DP * DP_M, SEAT_H_DP * DP_M, 0f)
    fun countSize(): V3 = V3(COUNT_W_DP * DP_M, COUNT_H_DP * DP_M, 0f)
    fun corners(centre: V3, size: V3): List<V3> = listOf(
        centre + V3(-size.x / 2, -size.y / 2, 0f), centre + V3(size.x / 2, -size.y / 2, 0f),
        centre + V3(size.x / 2, size.y / 2, 0f), centre + V3(-size.x / 2, size.y / 2, 0f),
    )

    /** A flight between two scene points: a parabola peaking [arc] above the chord. */
    fun arc(a: V3, b: V3, t: Float, arc: Float): V3 {
        val k = t.coerceIn(0f, 1f)
        return a.lerp(b, k) + V3(0f, 4f * arc * k * (1f - k), 0f)
    }

    // ---------------------------------------------------------------- eyes, obstacles, see-through

    /** Design occlusionCheck eyes (volume on a table: centre = table + 0.21) + E0 = the system's default placement. */
    val EYES: Map<String, V3> = linkedMapOf(
        "E0" to V3(0f, 0f, 0.75f),
        "E1" to V3(0f, 0.29f, 0.75f), "E2" to V3(0f, 0.66f, 0.75f),
        "E1L" to V3(-0.375f, 0.29f, 0.65f), "E1R" to V3(0.375f, 0.29f, 0.65f),
        "E2L" to V3(-0.375f, 0.66f, 0.65f), "E2R" to V3(0.375f, 0.66f, 0.65f),
    )

    /** An obstacle box: flat (scene AABB) or on the tower (AABB in turntable-local space, turned with the table). */
    class Obstacle(val name: String, val box: Box3, val onTable: Boolean, val tiltedStatic: Boolean = false)

    fun obstacles(): List<Obstacle> {
        val out = ArrayList<Obstacle>()
        out += Obstacle("plinth", Box3.around(PLINTH_CENTRE, PLINTH_SIZE), false)
        out += Obstacle("wedge", Box3(V3(-0.13f, -0.17f, -0.114f), V3(0.13f, -0.081f, 0.13f)), false)
        out += Obstacle("base", Box3(V3(-BASE_W / 2, BASE_BOTTOM, -BASE_W / 2), V3(BASE_W / 2, BASE_TOP, BASE_W / 2)), false, tiltedStatic = true)
        out += Obstacle("turntable", Box3(V3(-TABLE_R, TABLE_Y - 0.005f, -TABLE_R), V3(TABLE_R, TABLE_TOP, TABLE_R)), true)
        for (p in 0 until 16) {
            out += Obstacle("peg ${Qubic.pegName(p)}", Box3(pegLocal(p, PEG_BOTTOM) - V3(PEG_R, 0f, PEG_R), pegLocal(p, PEG_TOP) + V3(PEG_R, 0f, PEG_R)), true)
            for (l in 0..3) out += Obstacle("bead ${Qubic.pegName(p)}${l + 1}", Box3.around(cellLocal(Qubic.cell(p, l)), V3(2 * BEAD_R, BEAD_H, 2 * BEAD_R)), true)
            out += Obstacle("aimed bead ${Qubic.pegName(p)}", Box3(pegLocal(p, HOVER_Y - BEAD_H / 2) - V3(BEAD_R, 0f, BEAD_R), pegLocal(p, LIFT_TOP_Y) + V3(BEAD_R, 0f, BEAD_R)), true)
        }
        out += Obstacle("beams", Box3(V3(-0.1f, -0.10f, -0.1f), V3(0.1f, 0.03f, 0.1f)), true)
        for (side in 0..1) {
            val sx = if (side == 0) -1f else 1f
            out += Obstacle("tray $side", Box3.around(V3(sx * TRAY_X, -0.16f, TRAY_Z), TRAY_SIZE), false)
            out += Obstacle("tray beads $side", Box3(V3(sx * TRAY_X - 0.0525f, TRAY_TOP, TRAY_Z - 0.1075f), V3(sx * TRAY_X + 0.0525f, TRAY_PEG_TOP, TRAY_Z + 0.1075f)), false)
        }
        // S07 sweep: the arcs between the tower and the trays (local peak +0.13, design newGameSweep)
        out += Obstacle("sweep arcs", Box3(V3(-0.26f, -0.15f, -0.14f), V3(0.26f, 0.17f, 0.115f)), false)
        return out
    }

    /** Is the segment eye -> target (scene) blocked by any obstacle at turntable angle [deg]? Returns its name. */
    fun blockedBy(eye: V3, target: V3, deg: Float): String? = obstacles().firstOrNull { o ->
        when {
            o.onTable -> o.box.hitBySegment(turn(toLocal(eye), -deg), turn(toLocal(target), -deg))
            o.tiltedStatic -> o.box.hitBySegment(toLocal(eye), toLocal(target))
            else -> o.box.hitBySegment(eye, target)
        }
    }?.name

    /** Every corner of an obstacle in scene space (for the volume fit test). */
    fun sceneCorners(o: Obstacle, deg: Float): List<V3> = o.box.corners().map { c ->
        when { o.onTable -> onTable(c, deg); o.tiltedStatic -> toScene(c); else -> c }
    }

    fun volume(): Box3 = Box3(VOLUME * -0.5f, VOLUME * 0.5f)

    /**
     * See-through (design T16): beads (occupied cells other than [target]) whose ellipsoid the segment eye -> target
     * cell centre passes through, nearest the target first, at most [max]. [eye] is in scene space.
     */
    fun blockers(occupied: List<Int>, target: Int, eye: V3, deg: Float, max: Int = 6): List<Int> {
        val e = turn(toLocal(eye), -deg)
        val t = cellLocal(target)
        val out = ArrayList<Pair<Int, Float>>()
        for (c in occupied) {
            if (c == target) continue
            val cc = cellLocal(c)
            // unit-sphere test in the bead's scaled space
            val ax = (e.x - cc.x) / BEAD_R; val ay = (e.y - cc.y) / (BEAD_H / 2); val az = (e.z - cc.z) / BEAD_R
            val bx = (t.x - cc.x) / BEAD_R; val by = (t.y - cc.y) / (BEAD_H / 2); val bz = (t.z - cc.z) / BEAD_R
            val dx = bx - ax; val dy = by - ay; val dz = bz - az
            val a = dx * dx + dy * dy + dz * dz
            val b = 2 * (ax * dx + ay * dy + az * dz)
            val c2 = ax * ax + ay * ay + az * az - 1f
            val disc = b * b - 4 * a * c2
            if (disc < 0) continue
            val s = sqrt(disc)
            val t1 = (-b - s) / (2 * a); val t2 = (-b + s) / (2 * a)
            if (t2 < 0f || t1 > 0.97f) continue
            out += c to (cc - t).length()
        }
        return out.sortedBy { it.second }.take(max).map { it.first }
    }
}
