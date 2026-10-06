package com.cma.kreels.render

import android.content.Context
import android.view.Surface
import com.cma.kreels.model.*
import com.google.android.filament.*
import com.google.android.filament.gltfio.*
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Utils
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.max

const val EXPORT_W = 1080
const val EXPORT_H = 1920

/** Owns Filament. EVERY call below must run on [dispatcher] (single render thread). */
class ReelRenderer(private val ctx: Context) {
    val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "reel-render") }.asCoroutineDispatcher()

    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var camera: Camera
    private lateinit var assetLoader: AssetLoader
    private lateinit var resourceLoader: ResourceLoader

    private var ibl: IndirectLight? = null
    private var sky: Skybox? = null
    private var avatar: FilamentAsset? = null
    private var setAsset: FilamentAsset? = null
    private var previewSwap: SwapChain? = null
    private var loadedEnvId: String? = null
    private var loadedAvatar: String? = null

    suspend fun init() = withContext(dispatcher) {
        Utils.init()
        engine = Engine.create()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView().also { it.scene = scene }
        camera = engine.createCamera(engine.entityManager.create()).also { view.camera = it }
        assetLoader = AssetLoader(engine, UbershaderProvider(engine), EntityManager.get())
        resourceLoader = ResourceLoader(engine)
        // Sun-ish key light; IBL does the rest
        val sun = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL).color(1f, 0.96f, 0.9f).intensity(90_000f)
            .direction(-0.4f, -1f, -0.5f).castShadows(false).build(engine, sun)
        scene.addEntity(sun)
    }

    // ---- Content ------------------------------------------------------------------------------

    private fun open(env: Environment, name: String): InputStream =
        if (env.bundled) ctx.assets.open("environments/${env.dir}/$name") else File(env.dir, name).inputStream()

    private fun direct(s: InputStream): ByteBuffer {
        val bytes = s.use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { rewind() }
    }

    suspend fun load(spec: ReelSpec) = withContext(dispatcher) {
        if (loadedEnvId != spec.environment.id) { loadEnvironment(spec.environment); loadedEnvId = spec.environment.id }
        if (loadedAvatar != spec.avatarPath) { loadAvatar(spec.avatarPath); loadedAvatar = spec.avatarPath }
    }

    private fun loadEnvironment(env: Environment) {
        ibl?.let { engine.destroyIndirectLight(it) }   // also destroy its reflections texture in production
        sky?.let { engine.destroySkybox(it) }
        setAsset?.let { scene.removeEntities(it.entities); assetLoader.destroyAsset(it) }

        val cfg = runCatching { JSONObject(open(env, "env.json").use { it.readBytes().decodeToString() }) }.getOrNull()
        fun col(key: String, d: FloatArray) = cfg?.optJSONArray(key)?.let { a -> FloatArray(3) { a.getDouble(it).toFloat() } } ?: d
        val hasKtx = runCatching { open(env, "ibl.ktx").close(); open(env, "sky.ktx").close() }.isSuccess
        if (hasKtx) {                                   // HDRI-based pack (cmgen output)
            ibl = KTX1Loader.createIndirectLight(engine, direct(open(env, "ibl.ktx")), KTX1Loader.Options()).also { it.intensity = 30_000f }
            sky = KTX1Loader.createSkybox(engine, direct(open(env, "sky.ktx")), KTX1Loader.Options())
        } else {                                        // procedural: flat sky colour + constant ambient (tune intensity on device)
            val s = col("sky", floatArrayOf(0.55f, 0.75f, 0.95f)); val a = col("ambient", floatArrayOf(0.9f, 0.9f, 1f))
            sky = Skybox.Builder().color(s[0], s[1], s[2], 1f).build(engine)
            ibl = IndirectLight.Builder().irradiance(1, a).intensity(30_000f).build(engine)
        }
        scene.indirectLight = ibl; scene.skybox = sky

        runCatching { open(env, "set.glb") }.getOrNull()?.let { s ->      // optional low-poly set (buildings, boda, taxis)
            setAsset = assetLoader.createAsset(direct(s))?.also {
                resourceLoader.loadResources(it); it.releaseSourceData(); scene.addEntities(it.entities)
            }
        }
    }

    private fun loadAvatar(path: String?) {
        avatar?.let { scene.removeEntities(it.entities); assetLoader.destroyAsset(it) }
        avatar = null
        if (path == null) return
        val a = assetLoader.createAsset(direct(File(path).inputStream())) ?: return
        resourceLoader.loadResources(a); a.releaseSourceData()
        scene.addEntities(a.entities)
        // Normalise to ~1.7 units tall, feet on the origin
        val bb = a.boundingBox
        val s = 1.7f / max(0.01f, bb.halfExtent[1] * 2f)
        val tm = engine.transformManager
        val ty = -(bb.center[1] - bb.halfExtent[1]) * s
        tm.setTransform(tm.getInstance(a.root), floatArrayOf(s,0f,0f,0f, 0f,s,0f,0f, 0f,0f,s,0f, -bb.center[0]*s,ty,-bb.center[2]*s,1f))
        avatar = a
    }

    // ---- Preview surface ------------------------------------------------------------------------

    suspend fun attachPreview(surface: Surface) = withContext(dispatcher) { previewSwap = engine.createSwapChain(surface) }
    suspend fun detachPreview() = withContext(dispatcher) {
        previewSwap?.let { engine.destroySwapChain(it); engine.flushAndWait() }; previewSwap = null
    }

    suspend fun drawPreview(spec: ReelSpec, t: Float, w: Int, h: Int) = withContext(dispatcher) {
        previewSwap?.let { draw(it, spec, t, System.nanoTime(), w, h) }
    }

    // ---- Export surface -------------------------------------------------------------------------

    fun createSwapChain(s: Surface): SwapChain = engine.createSwapChain(s)
    fun destroySwapChain(sc: SwapChain) { engine.destroySwapChain(sc); engine.flushAndWait() }

    // ---- Frame ------------------------------------------------------------------------------------

    fun draw(sc: SwapChain, spec: ReelSpec, t: Float, frameNs: Long, w: Int = EXPORT_W, h: Int = EXPORT_H) {
        view.viewport = Viewport(0, 0, w, h)
        camera.setProjection(45.0, w.toDouble() / h, 0.1, 200.0, Camera.Fov.VERTICAL)
        applyCamera(spec, t)
        avatar?.instance?.animator?.let { an ->
            if (an.animationCount > 0) {
                val i = spec.avatarClip.coerceIn(0, an.animationCount - 1)
                an.applyAnimation(i, t % max(0.01f, an.getAnimationDuration(i))); an.updateBoneMatrices()
            }
        }
        if (renderer.beginFrame(sc, frameNs)) { renderer.render(view); renderer.endFrame() }
    }

    private fun applyCamera(spec: ReelSpec, t: Float) {
        val m = spec.camera.lastOrNull { it.start <= t }
        if (m == null) { camera.lookAt(0.0, 1.4, 3.2, 0.0, 1.1, 0.0, 0.0, 1.0, 0.0); return }
        val k = ((t - m.start) / max(0.001f, m.end - m.start)).coerceIn(0f, 1f)
        val e = k * k * (3f - 2f * k)                                   // smoothstep
        fun l(a: Float, b: Float) = (a + (b - a) * e).toDouble()
        camera.lookAt(l(m.fromEye.x, m.toEye.x), l(m.fromEye.y, m.toEye.y), l(m.fromEye.z, m.toEye.z),
            m.target.x.toDouble(), m.target.y.toDouble(), m.target.z.toDouble(), 0.0, 1.0, 0.0)
    }

    suspend fun release() {
        withContext(dispatcher) {
            avatar?.let { assetLoader.destroyAsset(it) }; setAsset?.let { assetLoader.destroyAsset(it) }
            ibl?.let { engine.destroyIndirectLight(it) }; sky?.let { engine.destroySkybox(it) }
            previewSwap?.let { engine.destroySwapChain(it) }
            assetLoader.destroy(); resourceLoader.destroy()
            engine.destroyRenderer(renderer); engine.destroyView(view); engine.destroyScene(scene); engine.destroy()
        }
        dispatcher.close()
    }
}
