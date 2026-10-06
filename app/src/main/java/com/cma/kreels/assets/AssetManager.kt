package com.cma.kreels.assets

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.cma.kreels.model.Environment
import java.io.File
import java.util.zip.ZipInputStream

class AssetManager(private val ctx: Context) {
    val root = File(ctx.filesDir, "imports").apply { mkdirs() }
    private val envRoot = File(ctx.filesDir, "environments").apply { mkdirs() }

    private val lowRam = (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).let {
        it.isLowRamDevice || it.memoryClass < 192
    }
    private val maxGlbBytes = if (lowRam) 25L shl 20 else 60L shl 20

    /** Copies a user-picked .glb (from ACTION_OPEN_DOCUMENT) into private storage after validating it. */
    fun importAvatar(uri: Uri): Result<File> = runCatching {
        val dest = File(root, "avatar.glb")
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            dest.outputStream().use { out ->
                var total = 0L
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    total += n
                    require(total <= maxGlbBytes) { "Model too large (max ${maxGlbBytes shr 20} MB). Shrink textures to 1024px." }
                    out.write(buf, 0, n)
                }
            }
        }
        // GLB header: magic 'glTF' (0x46546C67 LE), version 2
        dest.inputStream().use {
            val h = ByteArray(8); require(it.read(h) == 8) { "Not a GLB" }
            require(h[0] == 'g'.code.toByte() && h[1] == 'l'.code.toByte() && h[2] == 'T'.code.toByte() && h[3] == 'F'.code.toByte()) { "Not a GLB file" }
            require(h[4].toInt() == 2) { "Only glTF 2.0 supported" }
        }
        dest
    }.onFailure { File(root, "avatar.glb").delete() }

    /** Reference photo: downsampled on decode so a 12MP shot never sits in RAM at full size. */
    fun importPhoto(uri: Uri, name: String = "ref.jpg", maxEdge: Int = 1024): Result<File> = runCatching {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val bmp = cr.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }!!
        File(root, name).also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bmp.recycle() }
    }

    fun bundledEnvironments(): List<Environment> =
        (ctx.assets.list("environments") ?: emptyArray()).map { Environment(it, it, true) }

    fun importedEnvironments(): List<Environment> =
        envRoot.listFiles { f -> f.isDirectory }?.map { Environment(it.name, it.absolutePath, false) } ?: emptyList()

    /** Environment pack = .zip with ibl.ktx, sky.ktx and optional set.glb. */
    fun importEnvironmentPack(uri: Uri, id: String): Result<Environment> = runCatching {
        val dir = File(envRoot, id).apply { deleteRecursively(); mkdirs() }
        val allowed = setOf("ibl.ktx", "sky.ktx", "set.glb", "env.json")
        ZipInputStream(ctx.contentResolver.openInputStream(uri)!!).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e ->
                val name = File(e.name).name                       // flattens paths: no zip-slip
                if (!e.isDirectory && name in allowed) File(dir, name).outputStream().use { zip.copyTo(it) }
            }
        }
        require(File(dir, "set.glb").exists() || (File(dir, "ibl.ktx").exists() && File(dir, "sky.ktx").exists())) { "Pack needs set.glb, or ibl.ktx + sky.ktx" }
        Environment(id, dir.absolutePath, false)
    }
}
