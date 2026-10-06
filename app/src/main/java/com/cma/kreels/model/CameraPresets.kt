package com.cma.kreels.model

enum class CameraPreset(val id: String, val label: String, val from: V3, val to: V3) {
    PUSH_IN("push_in", "Push in", V3(0f, 1.4f, 4.2f), V3(0f, 1.3f, 2.0f)),
    PULL_OUT("pull_out", "Pull out", V3(0f, 1.3f, 2.0f), V3(0f, 1.4f, 4.2f)),
    SWEEP_R("sweep_r", "Sweep right", V3(-3.2f, 1.4f, 3.0f), V3(3.2f, 1.4f, 3.0f)),
    SWEEP_L("sweep_l", "Sweep left", V3(3.2f, 1.4f, 3.0f), V3(-3.2f, 1.4f, 3.0f)),
    RISE("rise", "Rise", V3(0f, 0.4f, 3.0f), V3(0f, 2.2f, 3.4f)),
    HERO_LOW("hero_low", "Hero low", V3(0.8f, 0.3f, 3.5f), V3(0f, 0.7f, 1.8f));

    fun make(start: Float, end: Float) = CameraMove(start, end, from, to, preset = id)

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id } ?: PUSH_IN

        /** One camera move per 4 beats (or every 2s without beats), cycling through the presets. */
        fun autoCuts(beats: List<Float>, duration: Float): List<CameraMove> {
            val marks = if (beats.size >= 2) beats.filterIndexed { i, _ -> i % 4 == 0 }
                        else generateSequence(2f) { it + 2f }.takeWhile { it < duration }.toList()
            val points = (listOf(0f) + marks.filter { it > 0.3f && it < duration - 0.5f } + duration).distinct()
            return points.zipWithNext().filter { (a, b) -> b - a >= 0.5f }
                .mapIndexed { i, (a, b) -> entries[i % entries.size].make(a, b) }
        }
    }
}
