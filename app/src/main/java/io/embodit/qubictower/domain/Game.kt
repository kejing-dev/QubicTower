package io.embodit.qubictower.domain

enum class Mode { PVE, PVP }

enum class SeatPick { AMBER, CELADON, RANDOM }

enum class Language { SYSTEM, ZH, EN }

/** One game's setup with the seat resolved. In PVE the user plays [human]; Amber always moves first. */
data class GameConfig(
    val mode: Mode = Mode.PVE,
    val level: Int = Engine.EASY,
    val human: Int = Qubic.AMBER,
) {
    fun isHuman(side: Int): Boolean = mode == Mode.PVP || side == human
    val ai: Int get() = Qubic.other(human)
}

/** The new-game page's choices (remembered between games). Hard always lets you go first (design gameSpec.turn). */
data class GameSetup(
    val mode: Mode = Mode.PVE,
    val level: Int = Engine.EASY,
    val seat: SeatPick = SeatPick.AMBER,
)

/** App settings (SharedPreferences). */
data class Settings(
    val language: Language = Language.SYSTEM,
    val sound: Boolean = true,
    val volume: Int = 2,
    val reducedMotion: Boolean = false,
    val firstRunDone: Boolean = false,
    /** The first game's lit peg tops were shown (they fade out after three drops of the user). */
    val litDone: Boolean = false,
    val lastSetup: GameSetup = GameSetup(),
)

/** What a game looks like on disk (files/game.txt): the move list is the whole position. */
data class SavedGame(
    val config: GameConfig,
    val moves: List<Int>,
    val redo: List<List<Int>> = emptyList(),
    val orientation: Int = 0,
    val hintOn: Boolean = false,
    val resigned: Boolean = false,
)

interface GameStore {
    fun load(): SavedGame?
    fun save(game: SavedGame)
    fun clear()
}

/**
 * Line codec: `qt1|mode|level|human|<moves>|<redo>|orientation|hint|resigned` where moves are hex peg digits
 * ("5a3" = peg 5, peg 10, peg 3) and redo is groups of hex digits separated by `;`.
 */
object SaveCodec {
    private fun hex(l: List<Int>) = l.joinToString("") { it.toString(16) }
    private fun parseHex(s: String): List<Int> = s.map { it.digitToInt(16) }.also { v -> require(v.all { it in 0..15 }) }

    fun encode(g: SavedGame): String = listOf(
        "qt1", g.config.mode.name, g.config.level.toString(), g.config.human.toString(), hex(g.moves),
        g.redo.joinToString(";") { hex(it) }, g.orientation.toString(), if (g.hintOn) "1" else "0", if (g.resigned) "1" else "0",
    ).joinToString("|")

    fun decode(text: String): SavedGame? = runCatching {
        val f = text.trim().split("|")
        if (f.size < 9 || f[0] != "qt1") return null
        val moves = if (f[4].isEmpty()) emptyList() else parseHex(f[4])
        Qubic.boardOf(moves) // throws on an impossible list (a full peg)
        SavedGame(
            config = GameConfig(Mode.valueOf(f[1]), f[2].toInt().coerceIn(1, 3), f[3].toInt().coerceIn(0, 1)),
            moves = moves,
            redo = if (f[5].isBlank()) emptyList() else f[5].split(";").filter { it.isNotEmpty() }.map { parseHex(it) },
            orientation = Turntable.normalize(f[6].toInt()),
            hintOn = f[7] == "1",
            resigned = f[8] == "1",
        )
    }.getOrNull()
}
