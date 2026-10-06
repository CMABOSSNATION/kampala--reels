package com.cma.kreels.model

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Auto-saves the timeline as project.json so edits survive app restarts. */
object ProjectStore {
    fun toJson(s: ReelSpec): String = JSONObject().apply {
        put("env", JSONObject().put("id", s.environment.id).put("dir", s.environment.dir).put("bundled", s.environment.bundled))
        put("duration", s.durationSec.toDouble()); put("fps", s.fps); put("avatarClip", s.avatarClip)
        s.avatarPath?.let { put("avatar", it) }
        s.audioUri?.let { put("audio", it.toString()) }
        put("camera", JSONArray(s.camera.map { JSONObject().put("s", it.start.toDouble()).put("e", it.end.toDouble()).put("p", it.preset) }))
        put("texts", JSONArray(s.texts.map {
            JSONObject().put("s", it.start.toDouble()).put("e", it.end.toDouble()).put("t", it.text).put("y", it.yFrac.toDouble()).put("z", it.sizePx.toDouble())
        }))
    }.toString()

    fun fromJson(json: String): ReelSpec? = runCatching {
        val o = JSONObject(json); val e = o.getJSONObject("env")
        val env = Environment(e.getString("id"), e.getString("dir"), e.getBoolean("bundled"))
        val cam = o.getJSONArray("camera"); val txt = o.getJSONArray("texts")
        ReelSpec(
            environment = env, durationSec = o.getDouble("duration").toFloat(), fps = o.getInt("fps"),
            avatarPath = o.optString("avatar").ifEmpty { null }, avatarClip = o.optInt("avatarClip"),
            audioUri = o.optString("audio").ifEmpty { null }?.let { Uri.parse(it) },
            camera = (0 until cam.length()).map { cam.getJSONObject(it).let { c -> CameraPreset.byId(c.getString("p")).make(c.getDouble("s").toFloat(), c.getDouble("e").toFloat()) } },
            texts = (0 until txt.length()).map { txt.getJSONObject(it).let { t ->
                TextClip(t.getDouble("s").toFloat(), t.getDouble("e").toFloat(), t.getString("t"), t.getDouble("y").toFloat(), t.getDouble("z").toFloat()) } },
        )
    }.getOrNull()

    fun save(ctx: Context, s: ReelSpec) = runCatching { File(ctx.filesDir, "project.json").writeText(toJson(s)) }

    /** Returns null if nothing saved or the saved environment/avatar files are gone. */
    fun load(ctx: Context): ReelSpec? {
        val f = File(ctx.filesDir, "project.json").takeIf { it.exists() } ?: return null
        val s = fromJson(f.readText()) ?: return null
        if (!s.environment.bundled && !File(s.environment.dir).exists()) return null
        return if (s.avatarPath != null && !File(s.avatarPath).exists()) s.copy(avatarPath = null) else s
    }
}
