package io.embodit.qubictower.domain

import kotlin.math.abs

/**
 * Precise selection (board-input README「精确选择」, board-game §15.5; user on the headset 2026-11-09: "加个手柄摇杆上下左右在
 * 确定阶段控制的输入，然后眼手的话就是有 上下左右 UI 可以点，现在选柱子这个角度不太好选的准").
 *
 * In the confirm phase (S04, a bead hangs over the selected peg) up / down / left / right move the selection to the
 * neighbouring peg AS THE USER SEES THE TOWER: up = the row farther away, down = nearer, left / right = left / right,
 * whatever the turntable's quarter turn is. Pure maths, unit tested (NudgeTest).
 */
enum class Dir(val dx: Int, val dz: Int) {
    /** Farther from the user (−Z in the volume). */
    UP(0, -1),
    /** Nearer to the user (+Z). */
    DOWN(0, 1),
    LEFT(-1, 0),
    RIGHT(1, 0);

    companion object {
        fun parse(s: String): Dir? = when (s.trim().lowercase()) {
            "up", "u", "far", "上" -> UP
            "down", "d", "near", "下" -> DOWN
            "left", "l", "左" -> LEFT
            "right", "r", "右" -> RIGHT
            else -> null
        }
    }
}

object Nudge {
    /** Where peg [peg] is seen at the turntable's quarter turn [orientation] (0..3): grid steps (x right, z toward the user). */
    fun viewCell(peg: Int, orientation: Int): Pair<Int, Int> {
        val (x, z) = Turntable.rotY(Layout.pegX(peg), Layout.pegZ(peg), Turntable.normalize(orientation) * 90f)
        return Math.round(x / Layout.PITCH * 2f) to Math.round(z / Layout.PITCH * 2f)   // half-steps: ±1, ±3
    }

    /** The peg seen one step [dir] from [peg] (null at the edge), ignoring whether it is full. */
    fun adjacent(peg: Int, dir: Dir, orientation: Int): Int? {
        val (x, z) = viewCell(peg, orientation)
        val tx = x + 2 * dir.dx; val tz = z + 2 * dir.dz
        if (abs(tx) > 3 || abs(tz) > 3) return null
        return (0 until Qubic.PEGS).first { viewCell(it, orientation) == tx to tz }
    }

    /**
     * The next selectable peg from [peg] toward [dir]: full pegs are stepped over (a bead cannot hang there); null when
     * there is none before the edge (the selection stays, no wrap-around).
     */
    fun next(peg: Int, dir: Dir, orientation: Int, canDrop: (Int) -> Boolean): Int? {
        var p = peg
        while (true) {
            p = adjacent(p, dir, orientation) ?: return null
            if (canDrop(p)) return p
        }
    }
}

/**
 * Thumbstick -> one step per push (README: past 0.6, then back to centre before the next one counts). One instance per
 * stick. [feed] returns the direction to apply for this sample, or null.
 */
class StickStepper(private val push: Float = 0.6f, private val rearm: Float = 0.3f) {
    private var armed = true

    /** [x] right +, [y] forward / up + (the SDK's thumbstick: push away = +y). */
    fun feed(x: Float, y: Float): Dir? {
        val ax = abs(x); val ay = abs(y)
        if (!armed) {
            if (ax < rearm && ay < rearm) armed = true
            return null
        }
        if (ax < push && ay < push) return null
        armed = false
        return if (ax >= ay) (if (x > 0) Dir.RIGHT else Dir.LEFT) else (if (y > 0) Dir.UP else Dir.DOWN)
    }

    fun reset() { armed = true }
}
