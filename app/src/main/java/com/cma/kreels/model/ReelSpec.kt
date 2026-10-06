package com.cma.kreels.model

import android.net.Uri

/** dir = folder containing ibl.ktx, sky.ktx and optional set.glb (assets/environments/<dir> if bundled, else absolute path). */
data class Environment(val id: String, val dir: String, val bundled: Boolean)

data class V3(val x: Float, val y: Float, val z: Float)

data class CameraMove(
    val start: Float, val end: Float,
    val fromEye: V3, val toEye: V3,
    val target: V3 = V3(0f, 1.1f, 0f),
    val preset: String = "push_in",
)

data class TextClip(
    val start: Float, val end: Float, val text: String,
    val yFrac: Float = 0.75f, val sizePx: Float = 96f,
)

data class ReelSpec(
    val environment: Environment,
    val durationSec: Float = 15f,
    val fps: Int = 30,
    val avatarPath: String? = null,   // imported .glb in app-private storage
    val avatarClip: Int = 0,          // animation index inside the .glb
    val audioUri: Uri? = null,        // user-picked MP3 (content:// URI)
    val camera: List<CameraMove> = emptyList(),
    val texts: List<TextClip> = emptyList(),
)
