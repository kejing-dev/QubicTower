package io.embodit.qubictower.ui.board

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.qubictower.domain.Display
import io.embodit.qubictower.domain.Layout
import io.embodit.qubictower.ui.theme.QtColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!

internal fun Color.c4(alpha: Float = this.alpha): Color4 = Color4(red, green, blue, alpha)

/** The first entity (this one or a descendant) that carries a ModelComponent: the mesh node of a GLB clone. */
internal fun Entity.meshNode(): Entity? {
    if (components[ModelComponent::class.java] != null) return this
    for (c in getChildren()) c.meshNode()?.let { return it }
    return null
}

/**
 * Materials, marks and collider shapes of the volume. The visible models (plinth, trays, base, turntable, pegs, bead)
 * are GLBs from tools/build_models.py; the app gives the beads and pegs their own materials (lacquer / glaze / frosted
 * glass) and draws only abstract marks (x-ray beams, rings) with SDK meshes. Creating a resource waits on the renderer,
 * so everything is created concurrently on Dispatchers.IO.
 */
internal class SceneResources private constructor() {
    private val all = ArrayList<Resource>()

    // marks
    lateinit var beam: MeshResource; private set
    lateinit var ringCell: MeshResource; private set
    lateinit var ringTop: MeshResource; private set
    lateinit var dot: MeshResource; private set
    /** Beam materials by tint, x-ray (no depth test) and depth-tested. */
    lateinit var beamXray: Map<Display.Tint, UnlitMaterial>; private set
    lateinit var beamDepth: Map<Display.Tint, UnlitMaterial>; private set
    lateinit var ghost: Material; private set
    lateinit var hover: Material; private set
    lateinit var green: Material; private set
    lateinit var red: Material; private set
    lateinit var lit: Material; private set

    // beads: [side][style] style 0 normal, 1 glow, 2 dim, 3 see-through (MASKED dots)
    lateinit var bead: List<List<Material>>; private set
    lateinit var frost: Material; private set

    // colliders
    lateinit var pegShape: ShapeResource; private set
    lateinit var gripShape: ShapeResource; private set
    lateinit var plinthShape: ShapeResource; private set
    lateinit var physics: PhysicsMaterialResource; private set

    fun close() {
        synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() }
    }

    companion object {
        const val STYLE_NORMAL = 0
        const val STYLE_GLOW = 1
        const val STYLE_DIM = 2
        const val STYLE_SEE = 3
        const val GRIP_SEGMENTS = 12
        const val GRIP_R = 0.1375f

        private fun unlit(color: Color, opacity: Float, xray: Boolean = false): UnlitMaterial =
            UnlitMaterial.create(if (opacity < 1f) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
                setBaseColor(color.c4(1f))
                if (opacity < 1f) setOpacity(opacity)
                setCullingMode(MaterialCullingMode.NONE)
                setApplyToneMapping(false)
                if (xray) { setDepthTest(false); setDepthWrite(false) }
            }

        private fun pbr(color: Color, rough: Float, metal: Float = 0f, emissive: Color? = null): PhysicallyBasedMaterial =
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.c4(1f)); setRoughness(rough); setMetallic(metal)
                if (emissive != null) setEmissiveColor(emissive.c4(1f))
            }

        /** 4 x 4 checker with alpha 0 / 1 (the see-through dots, design T16: 50 % alpha cut, not BLEND). */
        private fun checker(): Bitmap {
            val n = 64
            val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            for (y in 0 until n) for (x in 0 until n) {
                val on = ((x / 8) + (y / 8)) % 2 == 0
                bmp.setPixel(x, y, if (on) 0xFFFFFFFF.toInt() else 0x00FFFFFF)
            }
            return bmp
        }

        suspend fun create(): SceneResources = coroutineScope {
            val r = SceneResources()
            fun <T : Resource> T.keep(): T { toGlobal(); synchronized(r.all) { r.all += this }; return this }
            val io = Dispatchers.IO
            val tints = mapOf(
                Display.Tint.WHITE to QtColors.BeamWhite, Display.Tint.AMBER to QtColors.Amber, Display.Tint.CELADON to QtColors.Celadon,
                Display.Tint.GREY to QtColors.BeamGrey, Display.Tint.RED to QtColors.Danger,
            )
            val beamX = async(io) { tints.mapValues { (t, c) -> unlit(c, if (t == Display.Tint.WHITE || t == Display.Tint.GREY) 0.45f else 0.92f, xray = true).keep() } }
            val beamD = async(io) { tints.mapValues { (t, c) -> unlit(c, if (t == Display.Tint.WHITE || t == Display.Tint.GREY) 0.45f else 0.92f).keep() } }
            val tex = async(io) { TextureResource.create(checker()).keep() }
            listOf(
                // gate:allow geometry x-ray line beam (abstract indicator, unlit, no collider): a unit cylinder scaled per line
                async(io) { r.beam = MeshResource.createCylinder(radius = 0.5f, height = 1f).keep() },
                async(io) { r.ringCell = MeshResource.createTorus(outerRingRadius = 0.0285f, innerRingRadius = 0.0255f).keep() },
                async(io) { r.ringTop = MeshResource.createTorus(outerRingRadius = 0.0095f, innerRingRadius = 0.0065f).keep() },
                async(io) { r.dot = MeshResource.createTorus(outerRingRadius = 0.0050f, innerRingRadius = 0.0005f).keep() },
                async(io) { r.ghost = unlit(QtColors.BeamWhite, 0.85f).keep() },
                async(io) { r.hover = unlit(QtColors.HoverCream, 0.55f).keep() },
                async(io) { r.green = unlit(QtColors.HintGreen, 0.95f).keep() },
                async(io) { r.red = unlit(QtColors.Danger, 0.95f).keep() },
                async(io) { r.lit = unlit(QtColors.LitWarm, 0.9f).keep() },
                async(io) {
                    r.frost = PhysicallyBasedMaterial.create(BlendingMode.TRANSPARENT).apply {
                        setBaseColor(QtColors.Frost.c4(1f)); setOpacity(0.35f); setRoughness(0.8f); setMetallic(0f)
                    }.keep()
                },
                async(io) { r.pegShape = ShapeResource.createBox(Vector3(Layout.COLLIDER_W, Layout.COLLIDER_TOP - Layout.COLLIDER_BOTTOM, Layout.COLLIDER_W)).keep() },
                async(io) { r.gripShape = ShapeResource.createBox(Vector3(0.016f, 0.012f, (2 * Math.PI * GRIP_R / GRIP_SEGMENTS).toFloat() * 1.02f)).keep() },
                async(io) { r.plinthShape = ShapeResource.createBox(Vector3(Layout.PLINTH_SIZE.x, 0.002f, Layout.PLINTH_SIZE.z)).keep() },
                async(io) { r.physics = PhysicsMaterialResource().keep() },
            ).awaitAll()
            r.beamXray = beamX.await(); r.beamDepth = beamD.await()
            val checkerTex = tex.await()
            r.bead = listOf(QtColors.Amber to 0.35f, QtColors.Celadon to 0.30f).map { (c, rough) ->
                listOf(
                    async(io) { pbr(c, rough).keep() },
                    async(io) { pbr(c, rough, emissive = Color(c.red * 0.30f, c.green * 0.30f, c.blue * 0.30f)).keep() },
                    async(io) { pbr(Color(c.red * 0.4f, c.green * 0.4f, c.blue * 0.4f), rough + 0.2f).keep() },
                    async(io) {
                        PhysicallyBasedMaterial.create(BlendingMode.MASKED).apply {
                            setBaseColor(c.c4(1f)); setBaseColorTexture(checkerTex); setOpacityThreshold(0.5f); setRoughness(rough)
                            setCullingMode(MaterialCullingMode.NONE)
                        }.keep()
                    },
                ).awaitAll()
            }
            r
        }
    }
}
