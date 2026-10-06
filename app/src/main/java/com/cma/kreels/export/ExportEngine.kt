package com.cma.kreels.export

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.*
import android.net.Uri
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.*
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.*
import com.cma.kreels.model.ReelSpec
import com.cma.kreels.model.TextClip
import com.cma.kreels.render.EXPORT_H
import com.cma.kreels.render.EXPORT_W
import com.cma.kreels.render.ReelRenderer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ExportEngine(private val ctx: Context, private val renderer: ReelRenderer) {

    /** Call from any coroutine. Heavy work runs on the render thread / IO; UI never blocks. Returns the gallery Uri. */
    suspend fun export(spec: ReelSpec, onProgress: (Float) -> Unit): Uri {
        val pass1 = File(ctx.cacheDir, "pass1.mp4").apply { delete() }
        val pass2 = File(ctx.cacheDir, "pass2.mp4").apply { delete() }
        try {
            renderer.load(spec)
            renderPass(spec, pass1) { onProgress(it * 0.7f) }
            val needsPass2 = spec.audioUri != null || spec.texts.isNotEmpty()
            val finalFile = if (needsPass2) { composePass(spec, pass1, pass2) { onProgress(0.7f + it * 0.3f) }; pass2 } else pass1
            return saveToGallery(finalFile)
        } finally { pass1.delete(); pass2.delete() }
    }

    // ---- Pass 1: Filament -> MediaCodec surface -> MP4 (video only) ---------------------------------

    private suspend fun renderPass(spec: ReelSpec, out: File, onProgress: (Float) -> Unit) = coroutineScope {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, EXPORT_W, EXPORT_H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 14_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // Drain on its own thread: if the encoder queue is full, eglSwap in the render loop blocks until we drain.
        val running = AtomicBoolean(true)
        val drainer = launch(Dispatchers.IO) { runCatching { drain(enc, muxer, spec.fps, running) } }
        try {
            withContext(renderer.dispatcher) {
                val sc = renderer.createSwapChain(surface)
                val total = (spec.durationSec * spec.fps).toInt()
                try {
                    for (i in 0 until total) {
                        ensureActive()
                        val t = i.toFloat() / spec.fps
                        renderer.draw(sc, spec, t, (t * 1e9).toLong())
                        if (i % 5 == 0) onProgress(i / total.toFloat())
                    }
                    enc.signalEndOfInputStream()
                    drainer.join()
                } finally { renderer.destroySwapChain(sc) }
            }
        } finally {
            running.set(false)                                   // lets the drain loop exit on cancel
            withContext(NonCancellable) { drainer.join() }
            runCatching { enc.stop() }; enc.release(); surface.release()
            runCatching { muxer.stop() }; muxer.release()
        }
    }

    private fun drain(enc: MediaCodec, muxer: MediaMuxer, fps: Int, running: AtomicBoolean) {
        val info = MediaCodec.BufferInfo(); var track = -1; var n = 0L
        while (running.get()) {
            val idx = enc.dequeueOutputBuffer(info, 10_000)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(enc.outputFormat); muxer.start() }
                idx >= 0 -> {
                    val buf = enc.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && track >= 0) {
                        info.presentationTimeUs = n++ * 1_000_000L / fps    // exact timeline, independent of render speed
                        muxer.writeSampleData(track, buf, info)
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    // ---- Pass 2: Media3 Transformer — timed text + MP3->AAC + mux -----------------------------------

    private suspend fun composePass(spec: ReelSpec, video: File, out: File, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.Main) {                       // Transformer needs a Looper thread
            suspendCancellableCoroutine<Unit> { cont ->
                val overlays = spec.texts.map { TimedText(it) }
                val videoItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(video)))
                    .setRemoveAudio(true)
                    .setEffects(Effects(emptyList(), if (overlays.isEmpty()) emptyList() else listOf(OverlayEffect(ImmutableList.copyOf<TextureOverlay>(overlays)))))
                    .build()
                val sequences = mutableListOf(EditedMediaItemSequence.Builder(videoItem).build())
                spec.audioUri?.let { a ->
                    val clip = MediaItem.ClippingConfiguration.Builder().setEndPositionMs((spec.durationSec * 1000).toLong()).build()
                    val audioItem = EditedMediaItem.Builder(MediaItem.Builder().setUri(a).setClippingConfiguration(clip).build()).build()
                    sequences += EditedMediaItemSequence.Builder(audioItem).build()
                }
                val enc = DefaultEncoderFactory.Builder(ctx)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(14_000_000).build()).build()
                val tr = Transformer.Builder(ctx).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(enc)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(c: Composition, r: ExportResult) { if (cont.isActive) cont.resume(Unit) }
                        override fun onError(c: Composition, r: ExportResult, e: ExportException) { if (cont.isActive) cont.resumeWithException(e) }
                    }).build()
                tr.start(Composition.Builder(sequences).build(), out.path)
                cont.invokeOnCancellation { tr.cancel() }
                val h = ProgressHolder()
                CoroutineScope(Dispatchers.Main).launch {
                    while (cont.isActive) { if (tr.getProgress(h) != Transformer.PROGRESS_STATE_NOT_STARTED) onProgress(h.progress / 100f); delay(250) }
                }
            }
        }

    /** Full-frame transparent bitmap with the text drawn on it; visible only inside [start,end]. */
    private class TimedText(private val c: TextClip) : BitmapOverlay() {
        private val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        private val bmp by lazy {
            Bitmap.createBitmap(EXPORT_W, EXPORT_H, Bitmap.Config.ARGB_8888).also { b ->
                val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = c.sizePx; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(8f, 0f, 4f, Color.BLACK) }
                val l = StaticLayout.Builder.obtain(c.text, 0, c.text.length, p, EXPORT_W - 160).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
                Canvas(b).apply { translate(80f, EXPORT_H * c.yFrac - l.height / 2f); l.draw(this) }
            }
        }
        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val t = presentationTimeUs / 1_000_000f
            return if (t in c.start..c.end) bmp else empty
        }
    }

    // ---- Gallery -----------------------------------------------------------------------------------

    private fun saveToGallery(f: File): Uri {
        val r = ctx.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "reel_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/KampalaReels")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)!!
        r.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
        r.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
}
