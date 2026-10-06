package com.cma.kreels.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.cma.kreels.service.KeepAliveService
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cma.kreels.assets.PhotoAvatar
import com.cma.kreels.audio.BeatAnalyzer
import com.cma.kreels.model.*
import com.cma.kreels.render.ReelRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private fun <T> List<T>.set(i: Int, v: T) = toMutableList().also { it[i] = v }
private fun <T> List<T>.removeAt2(i: Int) = toMutableList().also { it.removeAt(i) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(renderer: ReelRenderer, st: EditorState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var inited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { renderer.init(); inited = true }
    val spec = st.spec
    val dur = spec.durationSec
    var cutout by remember { mutableStateOf(true) }

    val pickGlb = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        uri?.let { scope.launch {
            withContext(Dispatchers.IO) { st.assets.importAvatar(it) }
                .onSuccess { f -> st.update { copy(avatarPath = f.absolutePath, avatarClip = 0) }; st.status = "Avatar loaded" }
                .onFailure { e -> st.status = e.message ?: "Import failed" }
        } }
    }
    val pickPhoto = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        uri?.let { scope.launch {
            st.status = "Building avatar from photo..."
            runCatching {
                val f = withContext(Dispatchers.IO) { st.assets.importPhoto(it).getOrThrow() }
                PhotoAvatar.build(f, java.io.File(st.assets.root, "avatar_photo_${System.currentTimeMillis()}.glb"), cutout)
            }.onSuccess { g -> st.update { copy(avatarPath = g.absolutePath, avatarClip = 0) }; st.status = "Photo avatar ready" }
             .onFailure { e -> st.status = "Photo avatar failed: ${e.message}" }
        } }
    }
    val pickEnv = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        uri?.let { scope.launch {
            withContext(Dispatchers.IO) { st.assets.importEnvironmentPack(it, "pack_${System.currentTimeMillis()}") }
                .onSuccess { e -> st.update { copy(environment = e) }; st.status = "Environment loaded" }
                .onFailure { e -> st.status = e.message ?: "Import failed" }
        } }
    }
    val pickAudio = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        uri?.let {
            runCatching { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            st.update { copy(audioUri = it) }; st.status = "Music selected"
        }
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun startExport() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        st.exportJob = scope.launch {
            KeepAliveService.start(ctx)
            st.playing = false; st.progress = 0f; st.status = "Exporting..."
            try {
                st.exporter.export(st.spec) { st.progress = it }
                st.status = "Saved to Movies/KampalaReels"
            } catch (e: CancellationException) { st.status = "Export cancelled"; throw e
            } catch (e: Throwable) { st.status = "Export failed: ${e.message ?: e.javaClass.simpleName}"
            } finally { st.progress = null; KeepAliveService.stop(ctx) }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (inited) {
            ReelCanvas(renderer, spec, st.playing, st.playhead, onTick = { st.playhead = it },
                onError = { st.status = it }, modifier = Modifier.fillMaxWidth(0.6f).align(Alignment.CenterHorizontally))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { st.playing = !st.playing }) { Text(if (st.playing) "Pause" else "Play") }
            Text("%.1fs / %.0fs".format(st.playhead, dur))
        }
        Slider(value = st.playhead.coerceIn(0f, dur), onValueChange = { st.playing = false; st.playhead = it }, valueRange = 0f..dur)

        Header("Scene")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            st.environments().forEach { e ->
                FilterChip(selected = e.id == spec.environment.id, onClick = { st.update { copy(environment = e) } }, label = { Text(e.id) })
            }
        }
        OutlinedButton(onClick = { pickEnv.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Import environment pack (.zip)") }

        Header("Avatar")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { pickGlb.launch(arrayOf("*/*")) }) { Text("Import .glb") }
            OutlinedButton(onClick = { pickPhoto.launch(arrayOf("image/*")) }) { Text("From photo") }
            if (spec.avatarPath != null) TextButton(onClick = { st.update { copy(avatarPath = null) } }) { Text("Remove") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = cutout, onCheckedChange = { cutout = it }); Text("Remove photo background")
        }
        if (spec.avatarPath != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Animation clip ${spec.avatarClip}")
            OutlinedButton(onClick = { st.update { copy(avatarClip = (avatarClip - 1).coerceAtLeast(0)) } }) { Text("-") }
            OutlinedButton(onClick = { st.update { copy(avatarClip = avatarClip + 1) } }) { Text("+") }
        }

        Header("Music")
        OutlinedButton(onClick = { pickAudio.launch(arrayOf("audio/*")) }) { Text(if (spec.audioUri == null) "Pick MP3" else "Change MP3") }
        if (spec.audioUri != null) OutlinedButton(onClick = { scope.launch {
            st.status = "Analysing beats..."
            val beats = runCatching { BeatAnalyzer.beats(ctx, st.spec.audioUri!!, st.spec.durationSec) }.getOrDefault(emptyList())
            st.update { copy(camera = CameraPreset.autoCuts(beats, durationSec)) }
            st.status = if (beats.size >= 2) "Camera cut to ${beats.size} beats" else "No clear beat found; used 2s cuts"
        } }) { Text("Auto-cut camera to beats") }

        Header("Length: ${dur.roundToInt()}s")
        Slider(value = dur, valueRange = 5f..60f, onValueChange = {
            val d = it.roundToInt().toFloat()
            st.update { copy(durationSec = d,
                camera = camera.map { m -> m.copy(start = m.start.coerceAtMost(d - 0.5f), end = m.end.coerceAtMost(d)) },
                texts = texts.map { t -> t.copy(start = t.start.coerceAtMost(d - 0.5f), end = t.end.coerceAtMost(d)) }) }
        })

        Header("Camera moves")
        spec.camera.forEachIndexed { i, m ->
            Card { Column(Modifier.padding(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CameraPreset.entries.forEach { p ->
                        FilterChip(selected = m.preset == p.id, onClick = { st.update { copy(camera = camera.set(i, p.make(m.start, m.end))) } }, label = { Text(p.label) })
                    }
                }
                RangeSlider(value = m.start..m.end, valueRange = 0f..dur, onValueChange = { r ->
                    if (r.endInclusive - r.start >= 0.5f) st.update { copy(camera = camera.set(i, CameraPreset.byId(m.preset).make(r.start, r.endInclusive))) }
                })
                Row { Text("%.1fs - %.1fs".format(m.start, m.end), Modifier.weight(1f)); TextButton(onClick = { st.update { copy(camera = camera.removeAt2(i)) } }) { Text("Delete") } }
            } }
        }
        OutlinedButton(onClick = {
            val s = st.playhead.coerceAtMost(dur - 1f); st.update { copy(camera = camera + CameraPreset.PUSH_IN.make(s, (s + 3f).coerceAtMost(dur))) }
        }) { Text("+ Add camera move at playhead") }

        Header("Text overlays")
        spec.texts.forEachIndexed { i, t ->
            Card { Column(Modifier.padding(8.dp)) {
                OutlinedTextField(value = t.text, onValueChange = { v -> st.update { copy(texts = texts.set(i, t.copy(text = v))) } }, modifier = Modifier.fillMaxWidth())
                RangeSlider(value = t.start..t.end, valueRange = 0f..dur, onValueChange = { r ->
                    if (r.endInclusive - r.start >= 0.5f) st.update { copy(texts = texts.set(i, t.copy(start = r.start, end = r.endInclusive))) }
                })
                Text("Vertical position")
                Slider(value = t.yFrac, valueRange = 0.1f..0.9f, onValueChange = { v -> st.update { copy(texts = texts.set(i, t.copy(yFrac = v))) } })
                Row { Text("%.1fs - %.1fs".format(t.start, t.end), Modifier.weight(1f)); TextButton(onClick = { st.update { copy(texts = texts.removeAt2(i)) } }) { Text("Delete") } }
            } }
        }
        OutlinedButton(onClick = {
            val s = st.playhead.coerceAtMost(dur - 1f); st.update { copy(texts = texts + TextClip(s, (s + 3f).coerceAtMost(dur), "New text")) }
        }) { Text("+ Add text at playhead") }

        HorizontalDivider()
        val p = st.progress
        if (p == null) {
            Button(onClick = { startExport() }, modifier = Modifier.fillMaxWidth(), enabled = spec.environment.id != "none") { Text("Export 1080x1920 MP4") }
        } else {
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { st.exportJob?.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel export") }
        }
        if (st.status.isNotEmpty()) Text(st.status)
    }
}

@Composable private fun Header(t: String) = Text(t, style = MaterialTheme.typography.titleMedium)
