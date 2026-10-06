package com.cma.kreels.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Fully offline beat finder: decode audio -> 10ms RMS -> positive energy flux -> adaptive peak picking. */
object BeatAnalyzer {

    suspend fun beats(ctx: Context, uri: Uri, maxSec: Float): List<Float> = withContext(Dispatchers.Default) {
        val ex = MediaExtractor()
        val rms = ArrayList<Float>()
        try {
            ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext emptyList()
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()

            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var hop = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) / 100          // 10 ms of frames
            var acc = 0.0; var cnt = 0
            val info = MediaCodec.BufferInfo()
            val limitUs = (maxSec * 1_000_000L).toLong()
            var inDone = false; var outDone = false
            try {
                while (!outDone && isActive) {
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(5_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0 || ex.sampleTime > limitUs) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                            } else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 5_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT); hop = f.getInteger(MediaFormat.KEY_SAMPLE_RATE) / 100
                    } else if (o >= 0) {
                        val sb = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        while (sb.remaining() >= ch) {
                            var m = 0f
                            repeat(ch) { m += sb.get() / 32768f }
                            m /= ch; acc += m * m
                            if (++cnt >= hop) { rms += sqrt(acc / cnt).toFloat(); acc = 0.0; cnt = 0 }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            } finally { runCatching { codec.stop() }; codec.release() }
        } finally { ex.release() }
        val flux = FloatArray(rms.size) { if (it == 0) 0f else max(0f, rms[it] - rms[it - 1]) }
        pickPeaks(flux, 0.01f)
    }

    /** Pure function (unit-tested): local maxima above mean + 1.2*stddev of a ~1s window, min [minGapSec] apart. */
    fun pickPeaks(flux: FloatArray, hopSec: Float, minGapSec: Float = 0.3f): List<Float> {
        val win = (1f / hopSec).toInt().coerceAtLeast(5)
        val out = mutableListOf<Float>(); var last = -1e9f
        for (i in 1 until flux.size - 1) {
            val a = max(0, i - win); val b = min(flux.size, i + win)
            var mean = 0f; for (j in a until b) mean += flux[j]; mean /= (b - a)
            var v = 0f; for (j in a until b) { val d = flux[j] - mean; v += d * d }
            val sd = sqrt(v / (b - a))
            if (flux[i] > mean + 1.2f * sd && flux[i] >= flux[i - 1] && flux[i] >= flux[i + 1]) {
                val t = i * hopSec
                if (t - last >= minGapSec) { out += t; last = t }
            }
        }
        return out
    }
}
