package io.embodit.qubictower.ui.board

import io.embodit.qubictower.domain.Qubic
import io.embodit.qubictower.ui.board.input.parseLoggedPath
import io.embodit.qubictower.ui.board.input.resolvePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * board-input 必带单测「命中目标 → 格子回放」: every hit the volume can produce — the peg holder (collider), its Ø 5 mm
 * marker, the frosted peg model and any GLB node under it, the plinth, the rim — resolves through the same pure
 * BoardNames.resolveName the scene's TargetResolver uses, and a peg hit gives the right peg ("square").
 */
class BoardTargetTest {
    private fun square(chain: List<String>): String = BoardNames.pegOf(resolvePath(chain, BoardNames::resolveName))?.let { Qubic.pegName(it) } ?: "-"

    @Test fun everyPegHitResolvesToItsPeg() {
        for (p in 0 until 16) {
            val want = Qubic.pegName(p)
            val holder = BoardNames.peg(p)
            val model = BoardNames.pegModel(p)
            assertEquals(want, square(listOf(holder, "turnRoot", "towerRoot", "tiltRoot", "sceneRoot", "root")))
            assertEquals(want, square(listOf(BoardNames.pegMark(p), holder, "turnRoot")))
            assertEquals(want, square(listOf(model, holder, "turnRoot")))
            // the GLB's own nodes (unknown names) climb to the model / holder
            assertEquals(want, square(listOf("Mesh_0", "peg", model, holder, "turnRoot")))
            assertEquals(want, square(listOf("Peg", "Scene", model, holder)))
        }
    }

    @Test fun nonPegTargets() {
        assertEquals("plinth", resolvePath(listOf("plinth", "sceneRoot"), BoardNames::resolveName))
        assertEquals("grip", resolvePath(listOf("grip", "towerRoot"), BoardNames::resolveName))
        assertEquals("-", square(listOf("plinth", "sceneRoot")))
        // beads, beams, rings, the bare models and the roots are not targets
        for (n in listOf("bead:0:3", "beam:2", "selColumn", "ghostRing", "plinthModel", "turntableModel", "root", "peg", "peg:99", "peg:-1", "pegMark:16"))
            assertNull(n, resolvePath(listOf(n), BoardNames::resolveName))
    }

    @Test fun namesAreMadeOnlyFromThePegIndex() {
        // 斗兽棋 v0.1.3: a name built inside Entity().apply {} took the ECS id. Here every name is BoardNames.<fn>(p).
        for (p in 0 until 16) {
            assertEquals("peg:$p", BoardNames.peg(p))
            assertEquals(p, BoardNames.pegOf(BoardNames.resolveName(BoardNames.pegMark(p))))
        }
    }

    /**
     * The headset's own input-log (PB324XJGL2090081G, v0.1.0, 2026-11-10 01:38–01:43, pulled with run-as before the
     * v0.1.1 install; apps/qubic-tower/build/headset-input-log-v0.1.0.txt): every TAP / DRAG->TAP line as logged — hand
     * ray 14, eye-hand 12, poke 17, direct pinch 5, controller 0 — replayed through today's resolver. Each must give the
     * peg the template logged as hit= (square=- would be the 斗兽棋 v0.1.3 bug).
     */
    @Test fun replayHeadsetInputLogV010() {
        val lines = listOf(
            "TAP src=hand-ray hit=peg:14 path=pegHit:14", "TAP src=hand-ray hit=peg:15 path=pegHit:15", "TAP src=hand-ray hit=peg:13 path=pegHit:13",
            "DRAG->TAP src=hand-ray hit=peg:14 path=pegHit:14", "TAP src=direct-pinch hit=peg:13 path=pegHit:13", "TAP src=direct-pinch hit=peg:9 path=pegHit:9",
            "TAP src=hand-ray hit=peg:10 path=pegHit:10", "TAP src=hand-ray hit=peg:9 path=pegHit:9", "TAP src=hand-ray hit=peg:6 path=pegHit:6",
            "TAP src=hand-ray hit=peg:5 path=pegHit:5", "TAP src=eye-hand hit=peg:15 path=pegHit:15", "TAP src=eye-hand hit=peg:14 path=pegHit:14",
            "TAP src=eye-hand hit=peg:10 path=pegHit:10", "TAP src=eye-hand hit=peg:11 path=pegHit:11", "TAP src=eye-hand hit=peg:7 path=pegHit:7",
            "TAP src=eye-hand hit=peg:13 path=pegHit:13", "TAP src=eye-hand hit=peg:9 path=pegHit:9", "TAP src=eye-hand hit=peg:8 path=pegHit:8",
            "DRAG->TAP src=poke hit=peg:14 path=pegHit:14", "DRAG->TAP src=poke hit=peg:13 path=pegHit:13", "TAP src=direct-pinch hit=peg:5 path=pegHit:5",
            "TAP src=direct-pinch hit=peg:2 path=pegHit:2", "DRAG->TAP src=poke hit=peg:8 path=pegHit:8", "TAP src=poke hit=peg:9 path=pegHit:9",
            "TAP src=poke hit=peg:15 path=pegHit:15", "DRAG->TAP src=poke hit=peg:12 path=pegHit:12", "DRAG->TAP src=poke hit=peg:4 path=pegHit:4",
            "TAP src=poke hit=peg:12 path=pegHit:12",
        )
        for (l in lines) {
            val path = l.substringAfter("path=")
            val want = Qubic.pegName(l.substringAfter("hit=peg:").substringBefore(" ").toInt())
            assertEquals(l, want, square(parseLoggedPath(path)))
        }
    }

    /** v0.1.1's structure as `replay <peg>` prints it (holder "peg:<n>" with the model and the marker as children). */
    @Test fun replayHeadsetLogPaths() {
        val logged = listOf(
            "pegHit:0 < peg:0 < turnRoot < towerRoot < tiltRoot < sceneRoot < root" to "A1",
            "pegHit:5 < peg:5" to "B2",
            "pegHit:15 < peg:15 < turnRoot" to "D4",
            "peg:6" to "C2",
            "peg:9 < turnRoot < towerRoot < tiltRoot < sceneRoot < root" to "B3",
            "pegMark:9 < peg:9" to "B3",
            "pegModel:12 < peg:12" to "A4",
            "peg < pegModel:3 < peg:3" to "D1",
            "plinth" to "-",
            "grip" to "-",
        )
        for ((path, want) in logged) assertEquals(path, want, square(parseLoggedPath(path)))
    }
}
