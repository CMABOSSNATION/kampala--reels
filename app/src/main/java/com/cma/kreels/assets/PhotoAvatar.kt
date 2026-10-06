package com.cma.kreels.assets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Photo -> "standee": the person cut out of the photo (ML Kit selfie segmentation, model bundled in the APK,
 * works offline) on a flat quad inside a generated .glb. The renderer loads it like any avatar.
 */
object PhotoAvatar {

    suspend fun build(photo: File, out: File, cutout: Boolean): File = withContext(Dispatchers.Default) {
        var bmp = BitmapFactory.decodeFile(photo.path).copy(Bitmap.Config.ARGB_8888, true)
        if (cutout) bmp = runCatching { segment(bmp) }.getOrDefault(bmp)     // on failure keep the full photo
        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        writeQuadGlb(out, png, bmp.width, bmp.height)
        out
    }

    private suspend fun segment(src: Bitmap): Bitmap {
        val seg = Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
        try {
            val mask = seg.process(InputImage.fromBitmap(src, 0)).await()
            val w = mask.width; val h = mask.height
            val scaled = if (src.width == w && src.height == h) src else Bitmap.createScaledBitmap(src, w, h, true)
            val px = IntArray(w * h).also { scaled.getPixels(it, 0, w, 0, 0, w, h) }
            val buf = mask.buffer.also { it.rewind() }
            for (i in px.indices) {
                val c = buf.float
                val t = ((c - 0.3f) / 0.4f).coerceIn(0f, 1f)                      // soft edge
                val a = (t * t * (3f - 2f * t) * 255f).toInt()
                px[i] = (a shl 24) or (px[i] and 0x00FFFFFF)
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        } finally { seg.close() }
    }

    private fun writeQuadGlb(out: File, png: ByteArray, w: Int, h: Int) {
        val height = 1.7f; val width = height * w / h; val hw = width / 2
        val geo = ByteBuffer.allocate(48 + 48 + 32 + 12).order(ByteOrder.LITTLE_ENDIAN).apply {
            floatArrayOf(-hw, 0f, 0f, hw, 0f, 0f, hw, height, 0f, -hw, height, 0f).forEach { putFloat(it) }
            repeat(4) { putFloat(0f); putFloat(0f); putFloat(1f) }
            floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f).forEach { putFloat(it) }
            shortArrayOf(0, 1, 2, 0, 2, 3).forEach { putShort(it) }
        }.array()
        val bin = ByteArrayOutputStream().apply { write(geo); write(png); while (size() % 4 != 0) write(0) }.toByteArray()
        val json = """{"asset":{"version":"2.0"},"extensionsUsed":["KHR_materials_unlit"],"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
"meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2},"indices":3,"material":0}]}],
"materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0},"metallicFactor":0,"roughnessFactor":1},"alphaMode":"MASK","alphaCutoff":0.5,"doubleSided":true,"extensions":{"KHR_materials_unlit":{}}}],
"textures":[{"source":0,"sampler":0}],"images":[{"bufferView":4,"mimeType":"image/png"}],
"samplers":[{"magFilter":9729,"minFilter":9987,"wrapS":33071,"wrapT":33071}],
"buffers":[{"byteLength":${bin.size}}],
"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":48,"target":34962},{"buffer":0,"byteOffset":48,"byteLength":48,"target":34962},{"buffer":0,"byteOffset":96,"byteLength":32,"target":34962},{"buffer":0,"byteOffset":128,"byteLength":12,"target":34963},{"buffer":0,"byteOffset":140,"byteLength":${png.size}}],
"accessors":[{"bufferView":0,"componentType":5126,"count":4,"type":"VEC3","min":[${-hw},0,0],"max":[$hw,$height,0]},{"bufferView":1,"componentType":5126,"count":4,"type":"VEC3"},{"bufferView":2,"componentType":5126,"count":4,"type":"VEC2"},{"bufferView":3,"componentType":5123,"count":6,"type":"SCALAR"}]}"""
        var js = json.replace("\n", "").toByteArray()
        if (js.size % 4 != 0) js += ByteArray(4 - js.size % 4) { ' '.code.toByte() }
        val total = 12 + 8 + js.size + 8 + bin.size
        val bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0x46546C67).putInt(2).putInt(total)
        bb.putInt(js.size).putInt(0x4E4F534A).put(js)
        bb.putInt(bin.size).putInt(0x004E4942).put(bin)
        out.writeBytes(bb.array())
    }
}
