package com.music.bitchord

import com.music.bitchord.data.bpm.TapTempo
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** TapTempo against synthetic tap trains with known answers. */
class TapTempoTest {

    @Test
    fun steadyTaps_120bpm() {
        val tempo = TapTempo()
        var bpm: Double? = null
        for (k in 0 until 10) bpm = tempo.tap(k * 500L)
        assertNotNull(bpm)
        assertTrue("expected 120 ± 0.5, got $bpm", abs(bpm!! - 120.0) <= 0.5)
    }

    @Test
    fun steadyTaps_128bpm() {
        val tempo = TapTempo()
        var bpm: Double? = null
        // 468.75 ms per beat, on a whole-millisecond clock.
        for (k in 0 until 10) bpm = tempo.tap((k * 468.75).toLong())
        assertNotNull(bpm)
        assertTrue("expected 128 ± 0.5, got $bpm", abs(bpm!! - 128.0) <= 0.5)
    }

    @Test
    fun jitteredTaps_stayClose() {
        val tempo = TapTempo()
        // ±15 ms of human jitter around a true 500 ms beat.
        val jitters = longArrayOf(0, 12, -9, 15, -14, 6, -3, 11, -12, 4, 0)
        var at = 0L
        var bpm: Double? = null
        for (jitter in jitters) {
            bpm = tempo.tap(at)
            at += 500 + jitter
        }
        assertNotNull(bpm)
        assertTrue("expected 120 ± 1.5 under jitter, got $bpm", abs(bpm!! - 120.0) <= 1.5)
    }

    @Test
    fun aWildInterval_doesNotDragTheTempo() {
        val tempo = TapTempo()
        var bpm: Double? = null
        // One flinch (a 120 ms double-tap) inside a steady 120 BPM train.
        val taps = longArrayOf(0, 500, 1000, 1120, 1620, 2120, 2620, 3120, 3620)
        for (tap in taps) bpm = tempo.tap(tap)
        assertNotNull(bpm)
        assertTrue("expected ~120 despite the flinch, got $bpm", abs(bpm!! - 120.0) <= 2.0)
    }

    @Test
    fun aLongPause_resetsTheTrain() {
        val tempo = TapTempo()
        tempo.tap(0)
        tempo.tap(500)
        val before = tempo.tap(1000)
        assertNotNull(before)
        // 2.5 s of silence: the next tap starts a new train, so there is
        // no value again until three fresh taps have landed.
        assertNull(tempo.tap(3500))
        assertNull(tempo.tap(4000))
        val after = tempo.tap(4500)
        assertNotNull(after)
        assertTrue("expected ~120 after the reset, got $after", abs(after!! - 120.0) <= 0.5)
    }

    @Test
    fun fewerThanThreeTaps_noValue() {
        val tempo = TapTempo()
        assertNull(tempo.tap(0))
        assertNull(tempo.tap(500))
        assertNotNull(tempo.tap(1000))
    }
}
