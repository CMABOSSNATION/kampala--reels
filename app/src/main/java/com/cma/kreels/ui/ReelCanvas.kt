package com.cma.kreels.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.cma.kreels.model.ReelSpec
import com.cma.kreels.render.ReelRenderer
import kotlinx.coroutines.launch

private const val PREVIEW_W = 540     // half-res preview; export is 1080x1920
private const val PREVIEW_H = 960

/** 9:16 preview. Plays the timeline when [playing]; scrubs to [playheadSec] otherwise. */
@Composable
fun ReelCanvas(renderer: ReelRenderer, spec: ReelSpec, playing: Boolean, playheadSec: Float,
               onTick: (Float) -> Unit, onError: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier.aspectRatio(9f / 16f),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.setFixedSize(PREVIEW_W, PREVIEW_H)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) { scope.launch { renderer.attachPreview(h.surface); ready = true } }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) { ready = false; scope.launch { renderer.detachPreview() } }
                })
            }
        })

    LaunchedEffect(spec.environment.id, spec.avatarPath) {
        runCatching { renderer.load(spec) }.onFailure { onError("Load failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    LaunchedEffect(ready, playing, spec, if (playing) 0f else playheadSec) {
        if (!ready) return@LaunchedEffect
        if (!playing) { renderer.drawPreview(spec, playheadSec, PREVIEW_W, PREVIEW_H); return@LaunchedEffect }
        val t0 = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val t = (playheadSec + (now - t0) / 1e9f) % spec.durationSec
            renderer.drawPreview(spec, t, PREVIEW_W, PREVIEW_H); onTick(t)
        }
    }
}
