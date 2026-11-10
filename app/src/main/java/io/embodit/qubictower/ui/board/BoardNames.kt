package io.embodit.qubictower.ui.board

/**
 * The one place entity names are made and read (board-input README「必带单测」): the scene names its pickable holders
 * with these functions and the input resolver reads them back with [hitOf]. Pure Kotlin, replayed in BoardTargetTest
 * with resolvePath / parseLoggedPath (logged `path=` chains from the headset input-log).
 */
object BoardNames {
    sealed interface Hit
    data class PegHit(val peg: Int) : Hit
    data object PlinthHit : Hit
    data object GripHit : Hit
    data object NoHit : Hit

    /** Collider holder of peg [p] (Collision + Interactable + system HoverEffect). */
    fun peg(p: Int) = "peg:$p"
    /** The frosted peg model under the holder. */
    fun pegModel(p: Int) = "pegModel:$p"
    /** The Ø 5 mm marker on the peg top under the holder. */
    fun pegMark(p: Int) = "pegMark:$p"
    const val PLINTH = "plinth"
    const val GRIP = "grip"

    /** A name -> what it is; GLB sub-nodes and unknown names are NoHit (resolveUp then climbs to the parent). */
    fun hitOf(name: String?): Hit {
        if (name == null) return NoHit
        val f = name.split(":")
        return when (f[0]) {
            // "pegHit" = the v0.1.0 collider child name (kept so v0.1.0 headset logs still replay)
            "peg", "pegHit", "pegModel", "pegMark" -> f.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..15 }?.let { PegHit(it) } ?: NoHit
            PLINTH -> PlinthHit
            GRIP -> GripHit
            else -> NoHit
        }
    }

    /** The resolver's pure half: name -> target string the router knows ("peg:<n>", "plinth", "grip"), or null. */
    fun resolveName(name: String): String? = when (val h = hitOf(name)) {
        is PegHit -> peg(h.peg)
        PlinthHit -> PLINTH
        GripHit -> GRIP
        NoHit -> null
    }

    /** Target -> the peg it stands for (the "square" of this board), or null for plinth / rim / nothing. */
    fun pegOf(target: String?): Int? = (hitOf(target) as? PegHit)?.peg
}
