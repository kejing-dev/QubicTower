package io.embodit.qubictower.platform

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.qubictower.domain.Dir
import io.embodit.qubictower.domain.Phase
import io.embodit.qubictower.domain.StickStepper
import io.embodit.qubictower.ui.board.input.BoardInputLog
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Controller thumbstick / buttons in the confirm phase (v0.1.1, board-input README「精确选择」):
 * stick up / down / left / right = move the selected peg one step as the user sees the tower (past 0.6, back to centre
 * before the next step); trigger / A = confirm (drop); B = cancel.
 *
 * Shared Space does not document whether a volume window gets the thumbstick (feedback/pico-cli-feedback.md 待核实
 * 「手柄摇杆方向事件」), so every route the platform could use is wired and logged with a `PAD` line (logcat + the
 * board-input file log, readable on the headset with run-as):
 *  1. the SDK's ControllerTrackingProvider action listener (start result + support state logged once);
 *  2. Android key events reaching the focused window (DPAD_*, BUTTON_A / B / R2, ENTER, ESCAPE, BACK in the confirm phase);
 *  3. Android generic motion (SOURCE_JOYSTICK / GAMEPAD: AXIS_X / Y, HAT_X / Y, right stick Z / RZ).
 * Whatever does not arrive is covered by the panel's arrow keys (all three pointer inputs can press them).
 */
class PadInput(private val graph: Graph) {
    private val stickL = StickStepper()
    private val stickR = StickStepper()
    private val stickAndroid = StickStepper()
    private val hat = StickStepper(push = 0.5f, rearm = 0.2f)
    private var provider: ControllerTrackingProvider? = null
    private var listener: ControllerTrackingProvider.ControllerActionListener? = null
    @Volatile private var lastBoardTapMs = 0L
    private var triggerDown = BooleanArray(2)
    private var aDown = BooleanArray(2)
    private var bDown = BooleanArray(2)
    private var seenProviderAction = false
    private var keyLogs = 0
    private var motionLogs = 0

    @Volatile var providerStatus: String = "not started"
        private set

    private fun log(msg: String) {
        BoardInputLog.write(TAG, "PAD $msg")   // logcat + files/input-log.txt
    }

    private val aiming: Boolean get() = graph.flow.state.value.phase == Phase.AIMING

    /** A pinch / trigger on a volume collider just went through the board-input template (the system's own ray tap). */
    fun noteBoardTap() { lastBoardTapMs = SystemClock.uptimeMillis() }

    // ------------------------------------------------------------------ the three actions (one path for every source)

    fun step(dir: Dir, via: String) { graph.flow.nudge(dir, via) }
    fun confirm(via: String) { graph.flow.confirmAim(via) }
    fun cancel(via: String) { graph.flow.cancelSelection(via) }

    // ------------------------------------------------------------------ 1. SDK controller provider

    fun start() {
        if (provider != null) return
        runCatching {
            val p = ControllerTrackingProvider()
            val r = p.start()
            providerStatus = "start=$r support=${p.supportState} state=${p.state}"
            log("provider $providerStatus")
            val l = ControllerTrackingProvider.ControllerActionListener { data -> graph.scope.launch { onAction(data) } }
            p.addControllerActionListener(l)
            provider = p; listener = l
        }.onFailure { providerStatus = "failed: $it"; log("provider $providerStatus") }
    }

    fun stop() {
        runCatching { listener?.let { provider?.removeControllerActionListener(it) }; provider?.stop() }
        provider = null; listener = null
    }

    private fun onAction(d: ControllerActionData) {
        if (!seenProviderAction) { seenProviderAction = true; log("provider first action $d") }
        listOf(d.left, d.right).forEachIndexed { hand, a ->
            val via = if (hand == 0) "stick-left" else "stick-right"
            val st = a.thumbstickValue
            (if (hand == 0) stickL else stickR).feed(st.x, st.y)?.let { dir ->
                log("stick hand=$hand x=${"%.2f".format(st.x)} y=${"%.2f".format(st.y)} -> $dir aiming=$aiming")
                step(dir, via)
            }
            // A / X = confirm, B / Y = cancel (the SDK has no system back key, pico-cli-feedback「手柄没有可读的返回键」)
            val aNow = if (hand == 0) a.xButtonPressed else a.aButtonPressed
            val bNow = if (hand == 0) a.yButtonPressed else a.bButtonPressed
            if (aNow && !aDown[hand]) { log("button ${if (hand == 0) "X" else "A"} aiming=$aiming"); confirm("button-a") }
            if (bNow && !bDown[hand]) { log("button ${if (hand == 0) "Y" else "B"} aiming=$aiming"); cancel("button-b") }
            aDown[hand] = aNow; bDown[hand] = bNow
            // the trigger also makes the system's ray tap: confirm only if no board tap came with it (the ray was not on
            // a peg), so trigger-on-another-peg still re-selects and trigger-on-the-same-peg still drops once.
            val tNow = a.triggerPressed
            if (tNow && !triggerDown[hand]) {
                val at = SystemClock.uptimeMillis()
                log("trigger hand=$hand aiming=$aiming")
                graph.scope.launch {
                    kotlinx.coroutines.delay(TRIGGER_WINDOW_MS)
                    if (lastBoardTapMs >= at - 50) log("trigger hand=$hand -> board tap handled it")
                    else confirm("trigger")
                }
            }
            triggerDown[hand] = tNow
        }
    }

    /** Debug `stick x y`: one thumbstick sample through the right-hand stepper (same path as the provider). */
    fun debugStick(x: Float, y: Float) {
        stickR.feed(x, y)?.let { dir -> log("stick debug x=$x y=$y -> $dir aiming=$aiming"); step(dir, "stick-debug") }
    }

    // ------------------------------------------------------------------ 2. Android key events (focused window)

    /** Returns true when consumed (only in the confirm phase; otherwise the system keeps its keys). */
    fun onKey(e: KeyEvent, window: String): Boolean {
        if (keyLogs < 40) { keyLogs++; log("key ${KeyEvent.keyCodeToString(e.keyCode)} action=${e.action} src=0x${Integer.toHexString(e.source)} window=$window aiming=$aiming") }
        if (!aiming) return false
        if (e.action != KeyEvent.ACTION_DOWN) return isOurs(e.keyCode)
        if (e.repeatCount > 0) return isOurs(e.keyCode)
        val via = "key-$window"
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> step(Dir.UP, via)
            KeyEvent.KEYCODE_DPAD_DOWN -> step(Dir.DOWN, via)
            KeyEvent.KEYCODE_DPAD_LEFT -> step(Dir.LEFT, via)
            KeyEvent.KEYCODE_DPAD_RIGHT -> step(Dir.RIGHT, via)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> confirm(via)
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> cancel(via)
            else -> return false
        }
        return true
    }

    private fun isOurs(code: Int) = code in setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK,
    )

    // ------------------------------------------------------------------ 3. Android generic motion (joystick axes)

    fun onMotion(e: MotionEvent, window: String): Boolean {
        val joy = e.isFromSource(InputDevice.SOURCE_JOYSTICK) || e.isFromSource(InputDevice.SOURCE_GAMEPAD)
        if (!joy) return false
        val x = e.getAxisValue(MotionEvent.AXIS_X); val y = -e.getAxisValue(MotionEvent.AXIS_Y)
        val rx = e.getAxisValue(MotionEvent.AXIS_Z); val ry = -e.getAxisValue(MotionEvent.AXIS_RZ)
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X); val hy = -e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (motionLogs < 40 && (abs(x) + abs(y) + abs(rx) + abs(ry) + abs(hx) + abs(hy)) > 0.2f) {
            motionLogs++
            log("motion src=0x${Integer.toHexString(e.source)} x=${"%.2f".format(x)} y=${"%.2f".format(y)} rx=${"%.2f".format(rx)} ry=${"%.2f".format(ry)} hat=${hx}/${hy} window=$window")
        }
        val (sx, sy) = if (abs(rx) + abs(ry) > abs(x) + abs(y)) rx to ry else x to y
        stickAndroid.feed(sx, sy)?.let { step(it, "motion-$window") }
        hat.feed(hx, hy)?.let { step(it, "hat-$window") }
        return aiming
    }

    companion object {
        /** A trigger press that the system also turns into a ray tap shows up as a TAP well within this. */
        const val TRIGGER_WINDOW_MS = 300L
    }
}
