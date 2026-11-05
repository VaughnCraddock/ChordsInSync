package com.music.bitchord

import com.music.bitchord.data.bpm.TempoEstimator
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The tempo estimator against synthetic PCM whose tempo is known exactly:
 * click tracks (a short decaying noise burst per beat) and a soft-onset
 * track (slow-attack sine bursts — the case with no transient to lean on).
 * The tolerance is the feature spec's ±2%; the measured errors are
 * recorded in FORK-NOTES.
 */
class TempoEstimatorTest {

    private val rate = TempoEstimator.ANALYSIS_RATE

    @Test
    fun clickTrack_90bpm() = assertTempo(90.0, clickTrack(90.0, seconds = 30))

    @Test
    fun clickTrack_120bpm() = assertTempo(120.0, clickTrack(120.0, seconds = 30))

    @Test
    fun clickTrack_128bpm() = assertTempo(128.0, clickTrack(128.0, seconds = 30))

    @Test
    fun clickTrack_174bpm() = assertTempo(174.0, clickTrack(174.0, seconds = 30))

    @Test
    fun softOnsets_100bpm() = assertTempo(100.0, softTrack(100.0, seconds = 30))

    @Test
    fun silence_hasNoTempo() {
        val estimate = TempoEstimator.estimate(FloatArray(rate * 10), rate)
        assertTrue("silence must not yield a tempo, got $estimate", estimate == null)
    }

    @Test
    fun resamplesFromCdRate() {
        // The device path decodes at the container's rate; the estimator
        // must land on the same answer after its own resample.
        val pcm = clickTrack(120.0, seconds = 20, sampleRate = 44_100)
        val estimate = TempoEstimator.estimate(pcm, 44_100)
        assertNotNull(estimate)
        val error = kotlin.math.abs(estimate!!.bpm - 120.0) / 120.0
        assertTrue("expected within 2% of 120 BPM at 44.1kHz, got ${estimate.bpm}", error <= 0.02)
    }

    private fun assertTempo(expected: Double, pcm: FloatArray) {
        val estimate = TempoEstimator.estimate(pcm, rate)
        assertNotNull("no tempo found for a $expected BPM track", estimate)
        val error = kotlin.math.abs(estimate!!.bpm - expected) / expected
        assertTrue(
            "expected within 2% of $expected BPM, got ${estimate.bpm} (confidence ${estimate.confidence})",
            error <= 0.02,
        )
        assertTrue("confidence should be meaningful, was ${estimate.confidence}", estimate.confidence > 0.2f)
    }

    /** One decaying broadband burst per beat, on a deterministic noise source so the test never flakes. */
    private fun clickTrack(bpm: Double, seconds: Int, sampleRate: Int = rate): FloatArray {
        val out = FloatArray(seconds * sampleRate)
        val period = 60.0 / bpm
        val burstSamples = (0.03 * sampleRate).toInt()
        var seed = 0x12345678
        fun noise(): Float {
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            return (seed / 0x40000000f) - 1f
        }
        var beat = 0
        while (true) {
            val start = (beat * period * sampleRate).toInt()
            if (start >= out.size) break
            for (i in 0 until burstSamples) {
                val index = start + i
                if (index >= out.size) break
                val decay = exp(-i.toDouble() / (0.006 * sampleRate)).toFloat()
                out[index] += noise() * decay * 0.9f
            }
            beat++
        }
        return out
    }

    /** Slow-attack sine bursts: onsets with no click, the hard case for a flux envelope. */
    private fun softTrack(bpm: Double, seconds: Int): FloatArray {
        val out = FloatArray(seconds * rate)
        val period = 60.0 / bpm
        val burstSeconds = 0.3
        val burstSamples = (burstSeconds * rate).toInt()
        var beat = 0
        while (true) {
            val start = (beat * period * rate).toInt()
            if (start >= out.size) break
            for (i in 0 until burstSamples) {
                val index = start + i
                if (index >= out.size) break
                val t = i.toDouble() / rate
                // Raised-cosine envelope: ~90 ms attack, the rest decay.
                val attack = (t / 0.09).coerceAtMost(1.0)
                val envelope = (0.5 - 0.5 * kotlin.math.cos(PI * attack)) *
                    exp(-maxOf(0.0, t - 0.09) / 0.08)
                out[index] += (sin(2.0 * PI * 180.0 * t) * envelope * 0.8).toFloat()
            }
            beat++
        }
        return out
    }
}
