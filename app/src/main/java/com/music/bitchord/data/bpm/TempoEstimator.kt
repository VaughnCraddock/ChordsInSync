package com.music.bitchord.data.bpm

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Estimates a track's tempo from decoded mono PCM, on-device and offline.
 *
 * The pipeline is the classic one, sized to what a phone can do in a couple
 * of seconds rather than to what a research paper would: resample to
 * [ANALYSIS_RATE], build an onset envelope by spectral flux, then score
 * every candidate period in the 40–200 BPM range by the envelope's
 * autocorrelation and pick the best. A gentle log-tempo prior centred on
 * 120 BPM breaks near-ties between a period and its double or half — the
 * octave error is the known failure mode of this family of estimator, and
 * the prior only nudges; the editor's ÷2/×2 buttons exist because no
 * estimator in this class can promise never to land an octave out.
 *
 * Pure Kotlin on purpose: no Android types, so the estimator is exercised
 * directly by unit tests on synthetic click tracks at known tempos.
 */
object TempoEstimator {

    /** The outcome of one estimate: a tempo and how strongly the evidence agreed on it. */
    data class Estimate(val bpm: Double, val confidence: Float)

    /** The rate the analysis itself runs at, whatever the source was decoded at. */
    const val ANALYSIS_RATE = 11_025

    const val MIN_BPM = 40.0
    const val MAX_BPM = 200.0

    /** STFT window for the flux envelope — ~93 ms at the analysis rate. */
    private const val WINDOW = 1024

    /** Envelope frames per second is ANALYSIS_RATE / HOP ≈ 86. */
    private const val HOP = 128

    /** Less audio than this cannot hold two beats of the slowest candidate. */
    private const val MIN_SECONDS = 4.0

    /** Below this autocorrelation peak the envelope has no period worth naming. */
    private const val MIN_CONFIDENCE = 0.08f

    /**
     * Estimates the tempo of [samples] (mono, any rate), or null when there
     * is too little audio or no periodicity to find (silence, noise, speech).
     */
    fun estimate(samples: FloatArray, sampleRate: Int): Estimate? {
        if (sampleRate <= 0) return null
        val pcm = if (sampleRate == ANALYSIS_RATE) samples else resample(samples, sampleRate, ANALYSIS_RATE)
        if (pcm.size < (ANALYSIS_RATE * MIN_SECONDS).toInt()) return null
        val envelope = onsetEnvelope(pcm)
        if (envelope.size < 64) return null
        return tempoOf(envelope)
    }

    /**
     * Resamples mono PCM from [fromRate] to [toRate]. An exact integer ratio
     * gets block-average decimation (the anti-aliasing the onset envelope
     * wants); anything else gets linear interpolation, which an onset
     * envelope survives comfortably.
     */
    internal fun resample(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return samples
        if (fromRate > toRate && fromRate % toRate == 0) {
            val factor = fromRate / toRate
            val out = FloatArray(samples.size / factor)
            for (i in out.indices) {
                var sum = 0.0
                val base = i * factor
                for (k in 0 until factor) sum += samples[base + k]
                out[i] = (sum / factor).toFloat()
            }
            return out
        }
        val outSize = (samples.size.toDouble() * toRate / fromRate).toInt()
        if (outSize <= 0) return FloatArray(0)
        val out = FloatArray(outSize)
        val step = fromRate.toDouble() / toRate
        for (i in out.indices) {
            val pos = i * step
            val index = pos.toInt()
            val frac = (pos - index).toFloat()
            val a = samples[index]
            val b = if (index + 1 < samples.size) samples[index + 1] else a
            out[i] = a + (b - a) * frac
        }
        return out
    }

    /**
     * Spectral-flux onset envelope: for each windowed frame, the sum of the
     * positive magnitude increases across the spectrum. A beat is new energy
     * arriving; sustained tones and steady noise flux at ~zero, which is
     * what makes the autocorrelation below a tempo measurement rather than
     * a loudness one.
     */
    private fun onsetEnvelope(pcm: FloatArray): FloatArray {
        val frames = (pcm.size - WINDOW) / HOP + 1
        if (frames <= 0) return FloatArray(0)
        val envelope = FloatArray(frames)
        val windowed = FloatArray(WINDOW)
        var previous = FloatArray(WINDOW / 2 + 1)
        val fft = Fft(WINDOW)
        var peak = 0f
        for (frame in 0 until frames) {
            val start = frame * HOP
            for (i in 0 until WINDOW) {
                // Hann window.
                val hann = 0.5f - 0.5f * cos(2.0 * PI * i / (WINDOW - 1)).toFloat()
                windowed[i] = pcm[start + i] * hann
            }
            val magnitudes = fft.magnitudes(windowed)
            var flux = 0f
            for (bin in 1 until magnitudes.size) {
                val rise = magnitudes[bin] - previous[bin]
                if (rise > 0f) flux += rise
            }
            previous = magnitudes
            envelope[frame] = flux
            if (flux > peak) peak = flux
        }
        if (peak <= 0f) return FloatArray(0)
        // A light 3-tap smooth: one-frame codec artefacts shouldn't read as
        // onsets, and real onsets are wider than a frame anyway.
        val smoothed = FloatArray(frames)
        for (i in 0 until frames) {
            val a = if (i > 0) envelope[i - 1] else envelope[i]
            val b = if (i + 1 < frames) envelope[i + 1] else envelope[i]
            smoothed[i] = (a + 2f * envelope[i] + b) * 0.25f
        }
        return smoothed
    }

    /** Autocorrelation peak-picking over the candidate range, with parabolic refinement. */
    private fun tempoOf(envelope: FloatArray): Estimate? {
        val mean = envelope.average().toFloat()
        val centered = FloatArray(envelope.size) { envelope[it] - mean }
        var energy = 0.0
        for (value in centered) energy += value.toDouble() * value
        if (energy <= 0.0) return null

        val frameRate = ANALYSIS_RATE.toDouble() / HOP
        val minLag = (60.0 / MAX_BPM * frameRate).toInt().coerceAtLeast(2)
        val maxLag = (60.0 / MIN_BPM * frameRate).toInt().coerceAtMost(centered.size / 2)
        if (maxLag <= minLag) return null

        // Biased autocorrelation, normalised by the zero-lag energy: a longer
        // lag is scored on fewer overlapping terms, which is the natural
        // pressure against answering with a multiple of the true period.
        // Computed to three times the longest candidate lag, because the
        // scoring below reads a candidate's harmonics as well as the
        // candidate itself.
        val corrMax = minOf(3 * maxLag + 1, centered.size - 1)
        val correlation = DoubleArray(corrMax + 1)
        for (lag in minLag..corrMax) {
            var sum = 0.0
            val limit = centered.size - lag
            for (i in 0 until limit) sum += centered[i].toDouble() * centered[i + lag]
            correlation[lag] = sum / energy
        }

        // Harmonic reinforcement: a candidate period is also scored on its
        // multiples, discounted. A true period's multiples are periodic too,
        // so it collects the whole ladder; a doubled period — the classic
        // octave error, and the one a bare autocorrelation genuinely prefers
        // at fast tempos, where a 174 BPM track measured as 87 with high
        // confidence before this was added — starts halfway up its own
        // ladder and collects strictly less.
        fun reinforced(lag: Int): Double {
            var value = correlation[lag]
            if (2 * lag <= corrMax) value += correlation[2 * lag] / 2.0
            if (3 * lag <= corrMax) value += correlation[3 * lag] / 3.0
            return value
        }

        var bestLag = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            val bpm = 60.0 * frameRate / lag
            val score = reinforced(lag) * octavePrior(bpm)
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag < 0) return null
        val confidence = correlation[bestLag].toFloat()
        if (confidence < MIN_CONFIDENCE) return null

        // Parabolic interpolation around the peak: at ~86 envelope frames a
        // second, one lag step near 120 BPM is worth ~5 BPM, far coarser
        // than the answer deserves.
        val refinedLag = parabolicPeak(correlation[bestLag - 1], correlation[bestLag], correlation[bestLag + 1], bestLag)
        val bpm = 60.0 * frameRate / refinedLag
        if (bpm < MIN_BPM - 5 || bpm > MAX_BPM + 10) return null
        return Estimate(bpm = bpm, confidence = confidence.coerceIn(0f, 1f))
    }

    /**
     * The gentle octave tie-breaker: a Gaussian in log2-tempo centred on
     * 120 BPM, one octave wide. It moves a decision only when two candidate
     * periods explain the envelope almost equally well.
     */
    private fun octavePrior(bpm: Double): Double {
        val octaves = ln(bpm / 120.0) / ln(2.0)
        return exp(-0.5 * octaves * octaves)
    }

    /** The vertex of the parabola through three samples around a discrete peak at [index]. */
    private fun parabolicPeak(before: Double, at: Double, after: Double, index: Int): Double {
        val denominator = before - 2.0 * at + after
        if (denominator == 0.0) return index.toDouble()
        val shift = 0.5 * (before - after) / denominator
        return index + shift.coerceIn(-1.0, 1.0)
    }

    /**
     * An iterative radix-2 FFT, just enough of one for magnitude spectra.
     * [magnitudes] reuses scratch buffers; the returned array is the
     * caller's to keep.
     */
    private class Fft(private val size: Int) {
        private val real = FloatArray(size)
        private val imaginary = FloatArray(size)
        private val levels = (0..30).first { (1 shl it) == size }

        fun magnitudes(input: FloatArray): FloatArray {
            input.copyInto(real)
            imaginary.fill(0f)
            // Bit-reversal permutation.
            for (i in 0 until size) {
                val j = Integer.reverse(i) ushr (32 - levels)
                if (j > i) {
                    val tr = real[i]; real[i] = real[j]; real[j] = tr
                    val ti = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = ti
                }
            }
            var length = 2
            while (length <= size) {
                val angle = -2.0 * PI / length
                val wr = cos(angle).toFloat()
                val wi = sin(angle).toFloat()
                val half = length / 2
                var i = 0
                while (i < size) {
                    var cwr = 1f
                    var cwi = 0f
                    for (j in 0 until half) {
                        val ur = real[i + j]
                        val ui = imaginary[i + j]
                        val xr = real[i + j + half]
                        val xi = imaginary[i + j + half]
                        val vr = xr * cwr - xi * cwi
                        val vi = xr * cwi + xi * cwr
                        real[i + j] = ur + vr
                        imaginary[i + j] = ui + vi
                        real[i + j + half] = ur - vr
                        imaginary[i + j + half] = ui - vi
                        val nwr = cwr * wr - cwi * wi
                        cwi = cwr * wi + cwi * wr
                        cwr = nwr
                    }
                    i += length
                }
                length = length shl 1
            }
            val out = FloatArray(size / 2 + 1)
            for (bin in out.indices) {
                out[bin] = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
            }
            return out
        }
    }
}
