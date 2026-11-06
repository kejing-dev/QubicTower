package io.embodit.qubictower.data

import android.content.Context
import android.util.Log
import io.embodit.qubictower.domain.SeatPick
import io.embodit.qubictower.domain.GameSetup
import io.embodit.qubictower.domain.GameStore
import io.embodit.qubictower.domain.Language
import io.embodit.qubictower.domain.Mode
import io.embodit.qubictower.domain.SaveCodec
import io.embodit.qubictower.domain.SavedGame
import io.embodit.qubictower.domain.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * files/game.txt, written atomically after every step: a temp file, a read-back check, then a rename; the previous
 * good copy stays as game.bak (Chess Stores.kt pattern).
 */
class FileGameStore(context: Context) : GameStore {
    private val dir = context.filesDir
    private val file = File(dir, "game.txt")
    private val backup = File(dir, "game.bak")

    override fun load(): SavedGame? =
        listOf(file, backup).firstNotNullOfOrNull { f -> runCatching { if (f.exists()) SaveCodec.decode(f.readText()) else null }.getOrNull() }

    override fun save(game: SavedGame) {
        val text = SaveCodec.encode(game)
        runCatching {
            val tmp = File(dir, "game.tmp")
            tmp.writeText(text)
            check(SaveCodec.decode(tmp.readText()) == game) { "read-back mismatch" }
            if (file.exists()) file.copyTo(backup, overwrite = true)
            if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) }
        }.onFailure { Log.e("QubicTower", "SAVE failed", it) }
    }

    override fun clear() {
        file.delete()
        backup.delete()
    }
}

/** App settings in SharedPreferences, exposed as a StateFlow so every window recomposes on change. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val value: Settings get() = _settings.value

    fun update(change: (Settings) -> Settings) = set(change(_settings.value))

    fun set(s: Settings) {
        _settings.value = s
        prefs.edit()
            .putString("language", s.language.name)
            .putBoolean("sound", s.sound)
            .putInt("volume", s.volume)
            .putBoolean("reducedMotion", s.reducedMotion)
            .putBoolean("firstRunDone", s.firstRunDone)
            .putBoolean("litDone", s.litDone)
            .putString("setupMode", s.lastSetup.mode.name)
            .putInt("setupLevel", s.lastSetup.level)
            .putString("setupSeat", s.lastSetup.seat.name)
            .apply()
    }

    private fun read(): Settings = runCatching {
        Settings(
            language = Language.valueOf(prefs.getString("language", Language.SYSTEM.name)!!),
            sound = prefs.getBoolean("sound", true),
            volume = prefs.getInt("volume", 2).coerceIn(0, 2),
            reducedMotion = prefs.getBoolean("reducedMotion", false),
            firstRunDone = prefs.getBoolean("firstRunDone", false),
            litDone = prefs.getBoolean("litDone", false),
            lastSetup = GameSetup(
                mode = Mode.valueOf(prefs.getString("setupMode", Mode.PVE.name)!!),
                level = prefs.getInt("setupLevel", 1).coerceIn(1, 3),
                seat = SeatPick.valueOf(prefs.getString("setupSeat", SeatPick.AMBER.name)!!),
            ),
        )
    }.getOrElse { Settings() }
}
