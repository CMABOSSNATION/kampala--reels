package com.cma.kreels.ui

import android.content.Context
import androidx.compose.runtime.*
import com.cma.kreels.assets.AssetManager
import com.cma.kreels.export.ExportEngine
import com.cma.kreels.model.*
import com.cma.kreels.render.ReelRenderer
import kotlinx.coroutines.Job

class EditorState(val ctx: Context, val renderer: ReelRenderer) {
    val assets = AssetManager(ctx)
    val exporter = ExportEngine(ctx, renderer)

    var spec by mutableStateOf(ProjectStore.load(ctx) ?: defaultSpec())
    var playing by mutableStateOf(false)
    var playhead by mutableFloatStateOf(0f)
    var status by mutableStateOf("")
    var progress by mutableStateOf<Float?>(null)
    var exportJob: Job? = null

    fun environments() = assets.bundledEnvironments() + assets.importedEnvironments()

    fun update(block: ReelSpec.() -> ReelSpec) { spec = spec.block(); ProjectStore.save(ctx, spec) }

    private fun defaultSpec(): ReelSpec {
        val env = assets.bundledEnvironments().firstOrNull() ?: Environment("none", "none", true)
        return ReelSpec(
            environment = env, durationSec = 10f,
            camera = listOf(CameraPreset.PUSH_IN.make(0f, 6f)),
            texts = listOf(TextClip(1f, 8f, "Kampala Nights")),
        )
    }
}
