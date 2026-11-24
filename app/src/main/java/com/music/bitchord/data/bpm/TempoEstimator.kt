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
 * The pipeline: resample to [ANALYSIS_RATE], build a spectral-flux onset
 * envelope, then score every candidate period in the 40–200 BPM range by
 * the envelope's autocorrelation and pick the best. Three refinements sit
 * on top of that classic core, each added because a measurement demanded
 * it (the corpus and the numbers live in FORK-NOTES, "BPM rework"):
 *
 *  - **Harmonic reinforcement.** A candidate also collects its multiples'
 *    autocorrelation, discounted (R(τ) + R(2τ)/2 + R(3τ)/3): a true
 *    period's multiples are periodic too, so it outscores its own double
 *    — the classic octave error, which a bare autocorrelation genuinely
 *    prefers at fast tempos.
 *  - **A low-band ladder.** The same reinforcement computed on the flux
 *    of the spectrum's low band alone (the kick's neighbourhood, under
 *    ~170 Hz), added to the broadband score at half weight. A steady
 *    house track's kick ladder is what
 *    separates its true period from a 4:3 metric impostor that the
 *    broadband envelope alone can prefer on some windows. It is added at
 *    the *score* level, not blended into the envelope: blended, the low
 *    band's syncopation hijacks breakbeats (measured — see FORK-NOTES).
 *  - **Multi-window consensus.** [estimateWindows] scores several windows
 *    of a track and sums their normalised score curves, so one unlucky
 *    stretch (a breakdown, a sparse intro) cannot outvote the rest of
 *    the song. The reported confidence folds in how much the windows
 *    agreed with the consensus, which a single window cannot know.
 *
 * A gentle log-tempo prior centred on 120 BPM breaks near-ties between a
 * period and its double or half. It only nudges; the editor's ÷2/×2
 * buttons exist because no estimator in this class can promise never to
 * land an octave out — half-time and two-step patterns remain genuinely
 * ambiguous to period scoring, and the corpus says so out loud.
 *
 * Pure Kotlin on purpose: no Android types, so the estimator is exercised
 * directly by unit tests on synthetic tracks at known tempos.
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

    /** The least share of windows that must back the consensus period for it to be named. */
    private const val MIN_AGREEMENT = 0.55

    /** The raw-autocorrelation floor for a multi-window consensus answer; see the gate. */
    private const val MIN_CONSENSUS_RAW = 0.15

    /** The low band's edge: flux below this frequency is the kick's ladder. */
    private const val LOW_BAND_HZ = 170.0

    /** How much of the final score the low band's ladder contributes. */
    private const val LOW_LADDER_WEIGHT = 0.5

    /** Tracks longer than this are estimated by window consensus, not whole. */
    private const val MULTI_WINDOW_SECONDS = 45.0

    /** The length of each consensus window, and where they start (fractions of the track). */
    private const val WINDOW_SECONDS = 30.0
    private val WINDOW_STARTS = doubleArrayOf(0.08, 0.38, 0.68)

    /**
     * Estimates the tempo of [samples] (mono, any rate), or null when there
     * is too little audio or no periodicity to find (silence, noise, speech).
     * A long input is not scored whole: it is cut into the same consensus
     * windows [estimateWindows] would use, so the answer does not depend
     * on which door the audio came in through.
     */
    fun estimate(samples: FloatArray, sampleRate: Int): Estimate? {
        if (sampleRate <= 0) return null
        val pcm = if (sampleRate == ANALYSIS_RATE) samples else resample(samples, sampleRate, ANALYSIS_RATE)
        if (pcm.size < (ANALYSIS_RATE * MIN_SECONDS).toInt()) return null
        val seconds = pcm.size.toDouble() / ANALYSIS_RATE
        if (seconds <= MULTI_WINDOW_SECONDS) return estimateWindows(listOf(pcm), ANALYSIS_RATE)
        val windowSamples = (WINDOW_SECONDS * ANALYSIS_RATE).toInt()
        val windows = WINDOW_STARTS.map { fraction ->
            val start = (pcm.size * fraction).toInt()
            pcm.copyOfRange(start, minOf(pcm.size, start + windowSamples))
        }
        return estimateWindows(windows, ANALYSIS_RATE)
    }

    /**
     * Estimates a tempo from several decoded windows of one track: each
     * window's candidate scores are normalised and summed, the consensus
     * peak wins, and the confidence reflects both the autocorrelation's
     * strength at the winning period and how many windows individually
     * agreed with it. Windows too short to hold a tempo are skipped; if
     * none qualify, or the winning period's raw autocorrelation is under
     * the floor, the answer is null rather than a guess.
     */
    fun estimateWindows(windows: List<FloatArray>, sampleRate: Int): Estimate? {
        if (sampleRate <= 0) return null
        val frameRate = ANALYSIS_RATE.toDouble() / HOP
        val minLag = (60.0 / MAX_BPM * frameRate).toInt().coerceAtLeast(2)
        val maxLag = (60.0 / MIN_BPM * frameRate).toInt()

        var summed: DoubleArray? = null
        var windowsUsed = 0
        val perWindow = mutableListOf<WindowScore>()
        for (window in windows) {
            val pcm = if (sampleRate == ANALYSIS_RATE) window else resample(window, sampleRate, ANALYSIS_RATE)
            if (pcm.size < (ANALYSIS_RATE * MIN_SECONDS).toInt()) continue
            val (envelope, lowEnvelope) = onsetEnvelopes(pcm)
            if (envelope.size < 64) continue
            val usableMaxLag = minOf(maxLag, envelope.size / 2)
            if (usableMaxLag <= minLag) continue
            val scored = scoreCurve(envelope, lowEnvelope, minLag, usableMaxLag)
            val peak = (minLag..usableMaxLag).maxOf { scored.scores[it] }
            if (peak <= 0.0) continue
            val curve = DoubleArray(maxLag + 1)
            for (lag in minLag..usableMaxLag) curve[lag] = scored.scores[lag] / peak
            val acc = summed
            if (acc == null) {
                summed = curve
            } else {
                for (lag in minLag..maxLag) acc[lag] += curve[lag]
            }
            perWindow += WindowScore(
                curve = scored.scores,
                raw = scored.rawCorrelation,
                minLag = minLag,
                maxLag = usableMaxLag,
            )
            windowsUsed++
        }
        val total = summed ?: return null
        if (windowsUsed == 0) return null

        var bestLag = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            if (total[lag] > bestScore) {
                bestScore = total[lag]
                bestLag = lag
            }
        }
        if (bestLag < 0) return null

        // The gate stays on the raw broadband autocorrelation, the quantity
        // it has always measured: speech and noise keep failing it even
        // though the ladder now helps choose *which* lag to test.
        var rawSum = 0.0
        var agreements = 0
        val consensusBpm = 60.0 * frameRate / bestLag
        for (windowScore in perWindow) {
            // A short window's autocorrelation may not reach the consensus
            // lag at all; it contributes nothing there rather than crashing.
            rawSum += if (bestLag < windowScore.raw.size) windowScore.raw[bestLag] else 0.0
            val own = pickFromCurve(windowScore, frameRate)
            if (own != null && kotlin.math.abs(own - consensusBpm) / consensusBpm <= 0.02) agreements++
        }
        val meanRaw = rawSum / perWindow.size
        if (meanRaw < MIN_CONFIDENCE) return null
        // A consensus answer needs stronger raw evidence than a single
        // window's: segmentation shortens each window's averaging, and
        // speech's phrase rhythm (~1.3 s cadence) is periodic enough to
        // clear the single-window floor with a junk period all three
        // windows can half-agree on. Real music clears this bar several
        // times over; the voice notes that motivated it sit under it.
        if (perWindow.size > 1 && meanRaw < MIN_CONSENSUS_RAW) return null
        // The consensus gate: with several windows, the summed curve's peak
        // (each window contributes at most 1) must show the windows mostly
        // voted for the same period. Windows that each found a different
        // junk period — speech is full of them — sum to a peak little
        // bigger than one window's worth, however strong each looked alone.
        if (perWindow.size > 1 && bestScore / perWindow.size < MIN_AGREEMENT) return null

        // Parabolic interpolation around the consensus peak of the summed
        // curve: at ~86 envelope frames a second, one lag step near 120 BPM
        // is worth ~5 BPM, far coarser than the answer deserves.
        val refinedLag = if (bestLag in (minLag + 1) until maxLag) {
            parabolicPeak(total[bestLag - 1], total[bestLag], total[bestLag + 1], bestLag)
        } else {
            bestLag.toDouble()
        }
        val bpm = 60.0 * frameRate / refinedLag
        if (bpm < MIN_BPM - 5 || bpm > MAX_BPM + 10) return null

        val agreement = if (perWindow.size <= 1) 1.0 else agreements.toDouble() / perWindow.size
        val confidence = (meanRaw * (0.6 + 0.4 * agreement)).toFloat().coerceIn(0f, 1f)
        return Estimate(bpm = bpm, confidence = confidence)
    }

    /** One window's scoring products, kept until the consensus lag is known. */
    private class WindowScore(
        val curve: DoubleArray,
        /** The window's raw broadband autocorrelation, for the gate and the confidence. */
        val raw: DoubleArray,
        val minLag: Int,
        val maxLag: Int,
    )

    /** A window's own best tempo from its (unnormalised) score curve, parabolically refined. */
    private fun pickFromCurve(windowScore: WindowScore, frameRate: Double): Double? {
        var bestLag = -1
        var best = Double.NEGATIVE_INFINITY
        for (lag in windowScore.minLag..windowScore.maxLag) {
            if (windowScore.curve[lag] > best) {
                best = windowScore.curve[lag]
                bestLag = lag
            }
        }
        if (bestLag < 0) return null
        val refined = if (bestLag in (windowScore.minLag + 1) until windowScore.maxLag) {
            parabolicPeak(
                windowScore.curve[bestLag - 1],
                windowScore.curve[bestLag],
                windowScore.curve[bestLag + 1],
                bestLag,
            )
        } else {
            bestLag.toDouble()
        }
        return 60.0 * frameRate / refined
    }

    /**
     * The score of every candidate lag for one window: the broadband
     * autocorrelation with harmonic reinforcement, plus the low band's
     * ladder at [LOW_LADDER_WEIGHT], all under the octave prior. The raw
     * broadband autocorrelation rides along on the result for the gate.
     */
    private class ScoredCurve(val scores: DoubleArray, val rawCorrelation: DoubleArray)

    private fun scoreCurve(envelope: FloatArray, lowEnvelope: FloatArray, minLag: Int, maxLag: Int): ScoredCurve {
        // The broadband autocorrelation runs to three times the longest
        // candidate lag — exactly its ladder's reach. The low band's runs
        // one rung further (see reinforcedLow), so it is computed longer.
        val correlation = autocorrelation(envelope, 3 * maxLag + 1)
        val lowCorrelation = autocorrelation(lowEnvelope, 4 * maxLag + 1)
        val frameRate = ANALYSIS_RATE.toDouble() / HOP
        val scores = DoubleArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var value = reinforced(correlation, lag)
            value += LOW_LADDER_WEIGHT * reinforcedLow(lowCorrelation, lag)
            val bpm = 60.0 * frameRate / lag
            scores[lag] = value * octavePrior(bpm)
        }
        return ScoredCurve(scores, correlation)
    }

    /**
     * The low band's ladder, one rung longer than the broadband one — with
     * a guard the corpus bought: the fourth rung (R(4τ)/4) speaks only for
     * candidates at 60 BPM and faster. It is real evidence there (a
     * breakbeat's kick pattern repeats at the bar, four beats up), but at
     * slow candidate periods the fourth multiple is two bars and more,
     * where *every* repeating pattern is periodic — an unguarded rung paid
     * a 174 BPM two-step's bar rate (43.5) over both its true tempo and
     * its 3:2 impostor.
     */
    private fun reinforcedLow(correlation: DoubleArray, lag: Int): Double {
        var value = reinforced(correlation, lag)
        val frameRate = ANALYSIS_RATE.toDouble() / HOP
        val bpm = 60.0 * frameRate / lag
        if (bpm >= 60.0 && 4 * lag < correlation.size) value += correlation[4 * lag] / 4.0
        return value
    }

    /** R(τ) + R(2τ)/2 + R(3τ)/3 — the ladder a true period collects in full. */
    private fun reinforced(correlation: DoubleArray, lag: Int): Double {
        var value = correlation[lag]
        if (2 * lag < correlation.size) value += correlation[2 * lag] / 2.0
        if (3 * lag < correlation.size) value += correlation[3 * lag] / 3.0
        return value
    }

    /**
     * Biased autocorrelation, normalised by the zero-lag energy: a longer
     * lag is scored on fewer overlapping terms, which is the natural
     * pressure against answering with a multiple of the true period.
     */
    private fun autocorrelation(envelope: FloatArray, corrMax: Int): DoubleArray {
        val mean = envelope.average().toFloat()
        val centered = FloatArray(envelope.size) { envelope[it] - mean }
        var energy = 0.0
        for (value in centered) energy += value.toDouble() * value
        val out = DoubleArray(minOf(corrMax, centered.size - 1) + 1)
        if (energy <= 0.0) return out
        val limit = minOf(corrMax, centered.size - 1)
        for (lag in 1..limit) {
            var sum = 0.0
            val n = centered.size - lag
            for (i in 0 until n) sum += centered[i].toDouble() * centered[i + lag]
            out[lag] = sum / energy
        }
        return out
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
     * Spectral-flux onset envelopes, broadband and low-band: for each
     * windowed frame, the sum of the positive magnitude increases across
     * the spectrum (and across the low bins alone). A beat is new energy
     * arriving; sustained tones and steady noise flux at ~zero, which is
     * what makes the autocorrelation a tempo measurement rather than a
     * loudness one.
     */
    private fun onsetEnvelopes(pcm: FloatArray): Pair<FloatArray, FloatArray> {
        val frames = (pcm.size - WINDOW) / HOP + 1
        if (frames <= 0) return FloatArray(0) to FloatArray(0)
        val envelope = FloatArray(frames)
        val lowEnvelope = FloatArray(frames)
        val windowed = FloatArray(WINDOW)
        var previous = FloatArray(WINDOW / 2 + 1)
        val fft = Fft(WINDOW)
        // Bins whose centre frequency is at or under the low band's edge.
        val lowBins = (LOW_BAND_HZ * WINDOW / ANALYSIS_RATE).toInt().coerceIn(1, WINDOW / 2)
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
            var lowFlux = 0f
            for (bin in 1 until magnitudes.size) {
                val rise = magnitudes[bin] - previous[bin]
                if (rise > 0f) {
                    flux += rise
                    if (bin <= lowBins) lowFlux += rise
                }
            }
            previous = magnitudes
            envelope[frame] = flux
            lowEnvelope[frame] = lowFlux
            if (flux > peak) peak = flux
        }
        if (peak <= 0f) return FloatArray(0) to FloatArray(0)
        // A light 3-tap smooth: one-frame codec artefacts shouldn't read as
        // onsets, and real onsets are wider than a frame anyway.
        fun smooth(source: FloatArray): FloatArray {
            val out = FloatArray(frames)
            for (i in 0 until frames) {
                val a = if (i > 0) source[i - 1] else source[i]
                val b = if (i + 1 < frames) source[i + 1] else source[i]
                out[i] = (a + 2f * source[i] + b) * 0.25f
            }
            return out
        }
        return smooth(envelope) to smooth(lowEnvelope)
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
