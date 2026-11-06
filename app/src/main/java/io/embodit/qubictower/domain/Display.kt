package io.embodit.qubictower.domain

/**
 * Which x-ray beams are on (design gameSpec.lines): pure, so "每个落点格亮的线数 = 经过该格的线数 / 线色按归属 / 同屏 ≤ 8"
 * is unit tested and the scene only draws the list.
 */
object Display {
    enum class Tint { WHITE, AMBER, CELADON, GREY, RED }

    /** One beam: a line, its tint, its diameter (m), its opacity, and whether it pulses (must-block). */
    data class Beam(val line: Qubic.Line, val tint: Tint, val diameter: Float, val alpha: Float, val pulse: Boolean = false, val win: Boolean = false)

    const val MAX_BEAMS = 8

    /** Colour / thickness of a line as seen from the board (design: empty white Ø2, own 3 / 4 / 5 mm, both grey). */
    fun styled(b: Qubic.Board, l: Qubic.Line): Beam {
        val s = Qubic.lineState(b, l)
        return when {
            s.empty -> Beam(l, Tint.WHITE, 0.002f, 0.35f)
            s.dead -> Beam(l, Tint.GREY, 0.002f, 0.30f)
            else -> Beam(l, if (s.owner == Qubic.AMBER) Tint.AMBER else Tint.CELADON, 0.002f + 0.001f * s.count, 0.9f)
        }
    }

    /** The lines through [cell] styled on [b] (3–7 of them; 4 or 7 on a 4 x 4 x 4 board). */
    fun through(b: Qubic.Board, cell: Int): List<Beam> = Qubic.linesThrough(cell).map { styled(b, it) }

    /** The cell whose lines are shown right now, or -1: the aim, the AI's telegraph, or the 1.5 s linger after a landing. */
    fun focusCell(st: PlayState, now: Long): Int {
        val b = st.board
        st.aim?.let { p -> return b.landing(p) }
        st.aiAim?.let { p -> return b.landing(p) }
        st.linger?.let { if (now < it.until) return it.cell }
        return -1
    }

    /** Every beam to draw at [now]: focus lines (≤ 7) + one must-block or win line; never more than [MAX_BEAMS]. */
    fun beams(st: PlayState, now: Long): List<Beam> {
        val out = ArrayList<Beam>()
        val b = st.board
        st.result?.let { r -> r.line?.let { out += Beam(it, if (r.winner == Qubic.AMBER) Tint.AMBER else Tint.CELADON, 0.006f, 1f, win = true) }; return out }
        val focus = focusCell(st, now)
        if (focus >= 0) out += through(b, focus)
        mustBlock(st).firstOrNull()?.let { (l, _) ->
            out.removeAll { it.line.index == l.index }
            out += Beam(l, Tint.RED, 0.005f, 0.95f, pulse = true)
        }
        return out.take(MAX_BEAMS)
    }

    /** Must-block lines (rule fact, all levels, never behind the hint switch): the opponent of a human mover has three + a playable fourth. */
    fun mustBlock(st: PlayState): List<Pair<Qubic.Line, Int>> {
        val cfg = st.config ?: return emptyList()
        if (st.result != null || st.clear != null) return emptyList()
        if (st.phase !in setOf(Phase.IDLE, Phase.AIMING)) return emptyList()
        val side = st.side
        if (!cfg.isHuman(side)) return emptyList()
        return Qubic.threats(st.board, Qubic.other(side))
    }
}
