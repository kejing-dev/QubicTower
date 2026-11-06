package io.embodit.qubictower.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * design acceptance M6: D35 (three tags x every box x turntable 4 orientations x 7 eyes incl. E0) + geometry
 * (bead hole > peg, layer pitch = bead height, four beads below the peg top, tray beads inside the tray, beam ends on
 * the base, every box inside the volume, the aimed bead's top / front inside the volume).
 */
class LayoutTest {
    private val L = Layout

    @Test fun tagsAreNeverHiddenOrInsideABox() {
        var rays = 0
        val tags = listOf("seat" to (L.seatCentre() to L.seatSize()), "amber count" to (L.countCentre(0) to L.countSize()), "celadon count" to (L.countCentre(1) to L.countSize()))
        for (k in 0..3) for ((eyeName, eye) in L.EYES) for ((tag, cs) in tags) for (corner in L.corners(cs.first, cs.second) + cs.first) {
            rays++
            assertNull("$tag $eyeName turn ${k * 90}°", L.blockedBy(eye, corner, k * 90f))
            for (o in L.obstacles()) {
                val inside = when { o.onTable -> o.box.contains(L.turn(L.toLocal(corner), -k * 90f)); o.tiltedStatic -> o.box.contains(L.toLocal(corner)); else -> o.box.contains(corner) }
                assertTrue("$tag corner inside ${o.name}", !inside)
            }
        }
        println("D35 rays: $rays")
        // tags do not overlap each other
        val seat = L.corners(L.seatCentre(), L.seatSize()); val left = L.corners(L.countCentre(0), L.countSize()); val right = L.corners(L.countCentre(1), L.countSize())
        assertTrue(left.maxOf { it.x } < seat.minOf { it.x } && seat.maxOf { it.x } < right.minOf { it.x })
        // and they sit on the plinth face (y −0.21 … −0.17)
        for (c in seat + left + right) assertTrue(c.y > -0.21f && c.y < L.PLINTH_TOP)
    }

    @Test fun everyBoxFitsTheVolumeAtEveryOrientation() {
        val vol = L.volume()
        for (k in 0..3) for (o in L.obstacles()) for (c in L.sceneCorners(o, k * 90f)) assertTrue("${o.name} $c at ${k * 90}°", vol.contains(c, 1e-3f))
        // the aimed bead's highest point and the front row's front edge (design: +0.174 < 0.21, +0.215 < 0.23)
        val top = (0 until 16).maxOf { p -> L.onTable(L.pegLocal(p, L.LIFT_TOP_Y + L.BEAD_H / 2), 0f).y }
        val front = (0 until 16).maxOf { p -> L.onTable(L.pegLocal(p, L.HOVER_Y) + V3(0f, -L.BEAD_H / 2, L.BEAD_R), 0f).z }
        println("aimed bead top y=%.3f front z=%.3f".format(top, front))
        assertTrue(top < 0.21f); assertTrue(front < 0.23f)
    }

    @Test fun beadsFitThePegsAndTheTrays() {
        assertTrue(2 * L.HOLE_R > 2 * L.PEG_R)
        for (i in 1..3) assertEquals(L.BEAD_H, L.LAYER_Y[i] - L.LAYER_Y[i - 1], 1e-5f)
        assertEquals(L.TABLE_TOP + L.BEAD_H / 2, L.LAYER_Y[0], 1e-5f)
        assertTrue(L.LAYER_Y[3] + L.BEAD_H / 2 < L.PEG_TOP)
        for (side in 0..1) for (k in 0 until 32) {
            val c = L.traySlot(side, k)
            val cx = if (side == 0) -L.TRAY_X else L.TRAY_X
            assertTrue(abs(c.x - cx) + L.BEAD_R <= L.TRAY_SIZE.x / 2 + 1e-4f)
            assertTrue(abs(c.z - L.TRAY_Z) + L.BEAD_R <= L.TRAY_SIZE.z / 2 + 1e-4f)
            assertTrue(c.y + L.BEAD_H / 2 <= L.TRAY_PEG_TOP + 1e-4f)
        }
        // the removal order visits every slot once, front row + tower-side column first
        for (side in 0..1) {
            val r = L.removalOrder(side)
            assertEquals(32, r.toSet().size)
            val first = L.traySlot(side, r[0])
            assertTrue(first.z > L.TRAY_Z + 0.08f && abs(first.x) < L.TRAY_X)
            assertEquals(r[0], L.nextSlot(side, 32)); assertEquals(r[0], L.returnSlot(side, 31))
        }
    }

    @Test fun beamEndsStayOnTheBase() {
        var far = 0f
        for (l in Qubic.LINES) {
            val a = L.cellLocal(l.cells[0]); val b = L.cellLocal(l.cells[3])
            val d = (b - a) * (1f / (b - a).length())
            for (e in listOf(a - d * L.BEAM_EXTRA, b + d * L.BEAM_EXTRA)) {
                far = maxOf(far, abs(e.x), abs(e.z))
                assertTrue(abs(e.x) <= L.BASE_W / 2 && abs(e.z) <= L.BASE_W / 2)
                assertTrue(e.y >= L.TABLE_TOP - 0.02f && e.y <= L.PEG_TOP)
            }
        }
        println("beam end max |x|,|z| = %.3f (base half 0.13)".format(far))
    }

    @Test fun sceneAndLocalInvert() {
        val p = V3(0.07f, -0.02f, -0.05f)
        val q = L.toLocal(L.toScene(p))
        assertTrue((q - p).length() < 1e-5f)
        // the turntable's front edge, turned +90° (counter-clockwise from above), moves to +X
        val f = L.turn(V3(0f, 0f, 0.1f), 90f)
        assertTrue(f.x > 0.099f && abs(f.z) < 1e-4f)
        // the wedge: the base's back-bottom corner lands where the design says (−0.081, −0.114)
        val back = L.toScene(V3(0f, L.BASE_BOTTOM, -0.13f))
        assertEquals(-0.0811f, back.y, 2e-3f); assertEquals(-0.1143f, back.z, 2e-3f)
    }

    @Test fun seeThroughFindsAtMostSixBlockersAndNeverTheTarget() {
        val all = (0 until 64).toList()
        for ((name, eye) in L.EYES) for (k in 0..3) for (t in listOf(Qubic.cell(0, 0), Qubic.cell(5, 1), Qubic.cell(15, 0))) {
            val bl = L.blockers(all, t, eye, k * 90f)
            assertTrue(bl.size <= 6 && t !in bl)
        }
        // E0 (level with the volume centre), a back-row bottom cell behind a full front: something is in the way
        val bl = L.blockers(all, Qubic.cell(Qubic.peg(1, 0), 0), L.EYES.getValue("E0"), 0f)
        assertTrue(bl.isNotEmpty())
        // an empty board hides nothing
        assertTrue(L.blockers(emptyList(), Qubic.cell(0, 0), L.EYES.getValue("E1"), 0f).isEmpty())
        val dist = sqrt(2f) * 0.0f
        assertEquals(0f, dist)
    }
}
