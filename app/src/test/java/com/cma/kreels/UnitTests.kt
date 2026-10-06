package com.cma.kreels

import com.cma.kreels.audio.BeatAnalyzer
import com.cma.kreels.model.*
import org.junit.Assert.*
import org.junit.Test

class BeatAnalyzerTest {
    @Test fun findsThreeSpikes() {
        val flux = FloatArray(400).also { it[50] = 1f; it[150] = 1f; it[250] = 1f }
        val p = BeatAnalyzer.pickPeaks(flux, 0.01f)
        assertEquals(3, p.size)
        listOf(0.5f, 1.5f, 2.5f).forEachIndexed { i, t -> assertEquals(t, p[i], 0.02f) }
    }
    @Test fun silenceHasNoBeats() = assertTrue(BeatAnalyzer.pickPeaks(FloatArray(500), 0.01f).isEmpty())
}

class CameraPresetsTest {
    @Test fun autoCutsAreContiguousWithBeats() {
        val beats = (1..12).map { it * 0.5f }
        val c = CameraPreset.autoCuts(beats, 8f)
        assertEquals(0f, c.first().start, 0f); assertEquals(8f, c.last().end, 0f)
        c.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start, 0f) }
    }
    @Test fun autoCutsFallbackWithoutBeats() {
        val c = CameraPreset.autoCuts(emptyList(), 7f)
        assertTrue(c.size >= 3); assertEquals(7f, c.last().end, 0f)
    }
}

class ProjectStoreTest {
    @Test fun roundTrip() {
        val s = ReelSpec(
            environment = Environment("nakasero", "nakasero", true), durationSec = 12f, fps = 30,
            avatarPath = "/x/avatar.glb", avatarClip = 2, audioUri = null,
            camera = listOf(CameraPreset.RISE.make(1f, 4f)), texts = listOf(TextClip(0.5f, 3f, "Hello", 0.75f, 96f)),
        )
        assertEquals(s, ProjectStore.fromJson(ProjectStore.toJson(s)))
    }
    @Test fun garbageReturnsNull() = assertNull(ProjectStore.fromJson("not json"))
}
