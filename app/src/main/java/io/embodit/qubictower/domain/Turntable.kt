package io.embodit.qubictower.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ceil
import kotlin.math.sign
import kotlin.math.sin

/**
 * Push-the-turntable maths (design input.rotate, T3). Pure Kotlin so the snapping and its hysteresis are unit tested.
 *
 * The hand grabs the turntable rim (collider band Ø 0.20–0.26 m). The system only reports the hand's travel since the
 * press, not where the press was, so the grab point is the rim point facing the user (+Z, the front edge) unless the
 * caller knows better. The turntable follows the hand's azimuth around the centre; on release it snaps to the nearest
 * quarter turn, but leaving the current quarter needs 45° + 5° (hysteresis), so a hand shaking around 45° does not flip.
 */
object Turntable {
    const val GRIP_R = 0.115f
    const val HYSTERESIS_DEG = 5f
    const val SNAP_MS = 250L
    const val BUTTON_MS = 400L
    const val TICK_DEG = 15f

    /** Turntable angle (deg, + = counter-clockwise from above = the front edge moves to +X) for a hand travel in the plane. */
    fun dragAngle(dxPlane: Float, dzPlane: Float, grabX: Float = 0f, grabZ: Float = GRIP_R): Float {
        val a0 = atan2(grabX.toDouble(), grabZ.toDouble())
        val a1 = atan2((grabX + dxPlane).toDouble(), (grabZ + dzPlane).toDouble())
        var d = Math.toDegrees(a1 - a0)
        while (d > 180) d -= 360
        while (d < -180) d += 360
        return d.toFloat()
    }

    /** Quarter turns to add when released [deltaDeg] away from the start: 0 within ±50°, then one per 90°. */
    fun snapSteps(deltaDeg: Float): Int {
        val a = abs(deltaDeg)
        if (a <= 45f + HYSTERESIS_DEG) return 0
        return (sign(deltaDeg) * ceil((a - (45f + HYSTERESIS_DEG)) / 90f)).toInt()
    }

    fun normalize(k: Int): Int = ((k % 4) + 4) % 4

    /** Rotates a turntable-plane vector by [deg] about +Y (x, z). */
    fun rotY(x: Float, z: Float, deg: Float): Pair<Float, Float> {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        // +deg moves the front point (0, R) toward +X: x' = x c + z s, z' = -x s + z c
        return (x * c + z * s) to (-x * s + z * c)
    }

    /** Ease in-out (button turns / snaps). */
    fun ease(t: Float): Float { val u = t.coerceIn(0f, 1f); return u * u * (3f - 2f * u) }
}
