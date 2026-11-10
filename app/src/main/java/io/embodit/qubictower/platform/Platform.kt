package io.embodit.qubictower.platform

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.qubictower.data.FileGameStore
import io.embodit.qubictower.data.SettingsStore
import io.embodit.qubictower.domain.GameFlow
import io.embodit.qubictower.domain.Sound
import io.embodit.qubictower.mainApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.random.Random

const val TAG = "QubicTower"

class LaunchActivity : SpatialLaunchActivity()

class SpatialApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        io.embodit.qubictower.ui.board.input.BoardInputLog.attach(this)
        graph = Graph(this)
        launch(::mainApp)
    }
}

/** The process-wide objects (native-window-move.md §3.4: state shared by the volume and the window lives here). */
class Graph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(context)
    val sounds = SoundPlayer(context)
    val store = FileGameStore(context)
    val flow = GameFlow(
        scope = scope,
        engineDispatcher = Dispatchers.Default,
        clock = { SystemClock.uptimeMillis() },
        store = store,
        settings = { settings.value },
        onSettings = { settings.set(it) },
        onSound = { s, rate -> sounds.play(s, rate) },
        log = { Log.i(TAG, it) },
    )
    val window = WindowCtl()
    /** Thumbstick / controller buttons / D-pad keys in the confirm phase (v0.1.1). */
    val pad = PadInput(this)

    init {
        scope.launch {
            settings.settings.collect { s ->
                sounds.enabled = s.sound
                sounds.volumeLevel = s.volume
            }
        }
        flow.start()
    }

    companion object {
        fun from(context: Context): Graph = (context.applicationContext as SpatialApplication).graph
    }
}

/** Volume percent of each snap stop (Settings.volume 0..2). */
val VOLUME_PERCENT = intArrayOf(35, 65, 100)

/**
 * Short effects from assets/sounds/<name>.wav (+ _2 / _3 / _4 variants) through a SoundPool. Physical sounds (CC0
 * recordings: lift, slide, tick, land, return, turn, snap, clear) rotate their variants and get a random ±5 % pitch on
 * top of the caller's rate, never the same variant twice in a row (design sound.variation, profile D51). One stream per
 * sound at a time (a new one stops the old: rapid drops never stack, five quick drops peak <= -1 dBFS).
 */
class SoundPlayer(context: Context) {
    @Volatile var enabled = true
    @Volatile var volumeLevel = 2

    private val pool = SoundPool.Builder().setMaxStreams(10)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<Sound, List<Int>>()
    private val loaded = HashSet<Int>()
    private val random = Random(11)
    private val lastVariant = HashMap<Sound, Int>()
    private val physical = setOf(Sound.LIFT, Sound.TICK, Sound.LAND, Sound.RETURN, Sound.TURN, Sound.SNAP, Sound.CLEAR)

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        for (s in Sound.entries) {
            val base = s.name.lowercase()
            val found = ArrayList<Int>()
            for (name in listOf("$base.wav", "${base}_2.wav", "${base}_3.wav", "${base}_4.wav")) {
                runCatching { context.assets.openFd("sounds/$name").use { fd -> found += pool.load(fd, 1) } }
            }
            if (found.isNotEmpty()) ids[s] = found else Log.w(TAG, "no sound for $s")
        }
    }

    fun play(s: Sound, rate: Float = 1f) {
        if (!enabled) return
        val variants = ids[s] ?: return
        // §15.2 layering: the drop (LAND) is the loudest; the passing ticks sit under it
        val vol = VOLUME_PERCENT[volumeLevel.coerceIn(0, 2)] / 100f * (GAIN[s] ?: 1f)
        var r = rate
        var v = 0
        if (s in physical) {
            r *= 0.95f + random.nextFloat() * 0.10f
            v = random.nextInt(variants.size)
            if (v == lastVariant[s] && variants.size > 1) v = (v + 1) % variants.size
            lastVariant[s] = v
        }
        val id = variants[v]
        if (synchronized(loaded) { id !in loaded }) return
        // D51 同类不叠加: one instance per sound at a time — the new one stops the previous stream of the same sound
        // a bead's passing ticks and its landing are one class ("drop"): the land stops the last tick (no summed peak)
        val group = GROUP[s] ?: s.name
        synchronized(streams) {
            streams[group]?.let { pool.stop(it) }
            val sid = pool.play(id, vol, vol, 1, 0, r.coerceIn(0.5f, 2f))
            if (sid != 0) streams[group] = sid
        }
    }

    private val streams = HashMap<String, Int>()

    companion object {
        /** Relative playback gain per sound (board-game §15.2); everything else 1.0. tools/loudness.py uses the same table. */
        val GAIN = mapOf(Sound.TICK to 0.6f, Sound.RETURN to 0.7f)
        /** Sounds sharing one stream (a new one stops the other); everything else is its own class. */
        val GROUP = mapOf(Sound.TICK to "drop", Sound.LAND to "drop")
    }

    fun release() = runCatching { pool.release() }
}
