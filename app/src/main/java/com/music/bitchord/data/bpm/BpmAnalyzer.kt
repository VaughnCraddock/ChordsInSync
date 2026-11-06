package com.music.bitchord.data.bpm

import android.content.Context
import android.net.Uri
import com.music.bitchord.playback.smart.AudioDecoder
import com.music.bitchord.playback.smart.LocalAudioSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs [TempoEstimator] over one local file, on-device and offline.
 *
 * The decode is the app's own: [LocalAudioSource] opens the file for the
 * platform extractor (the same reader Automix analysis uses), and
 * [AudioDecoder.decodeRegion] decodes it to mono PCM through
 * MediaExtractor/MediaCodec. No FFmpeg route is needed — the smart-playback
 * decoder already covers every container the platform can play, and the
 * estimator resamples whatever rate comes back down to its analysis rate.
 *
 * Only a window is decoded: the whole track when it's short, otherwise a
 * ~75 s stretch starting a quarter of the way in — past the intro, where a
 * DJ track's tempo is the tempo. Batch runs stay fast because no file costs
 * more than that window.
 *
 * The outcome keeps two very different answers apart, because the first
 * field failure (2026-11-05) hid behind their confusion: a file whose audio
 * could not be turned into PCM at all (unreadable, undecodable, a decoder
 * failure) is [Outcome.Unreadable], while a file that decoded fine and
 * simply has no measurable periodicity is [Outcome.NoTempo]. Reporting the
 * first as "no tempo could be detected" sends the user hunting through
 * their music for a fault that lives in the decode, so the UI words them
 * differently and means it.
 */
object BpmAnalyzer {

    private const val WINDOW_SECONDS = 75.0

    /** What one analysis run concluded — every run ends in exactly one of these. */
    sealed interface Outcome {
        /** A tempo was measured; [estimate] carries the value and its confidence. */
        data class Found(val estimate: TempoEstimator.Estimate) : Outcome

        /** The file decoded, but no periodicity worth naming was in it. */
        data object NoTempo : Outcome

        /** The file could not be opened or decoded into PCM at all. */
        data object Unreadable : Outcome
    }

    /** Analyzes the file at [uriString]; see [Outcome] for the three ways it can end. */
    suspend fun analyze(context: Context, uriString: String): Outcome =
        withContext(Dispatchers.IO) {
            val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext Outcome.Unreadable
            val source = LocalAudioSource.open(context.contentResolver, uri) ?: return@withContext Outcome.Unreadable
            try {
                val duration = AudioDecoder.containerDurationSeconds(source)
                val (start, end) = windowFor(duration)
                val decoded = AudioDecoder.decodeRegion(source, start, end) ?: return@withContext Outcome.Unreadable
                val pcm = decoded.first
                val estimate = TempoEstimator.estimate(pcm.samples, pcm.sampleRate.toInt())
                if (estimate != null) Outcome.Found(estimate) else Outcome.NoTempo
            } catch (error: Exception) {
                Outcome.Unreadable
            } finally {
                runCatching { source.close() }
            }
        }

    private fun windowFor(durationSeconds: Double?): Pair<Double, Double> {
        if (durationSeconds == null || durationSeconds <= WINDOW_SECONDS) {
            return 0.0 to (durationSeconds ?: WINDOW_SECONDS)
        }
        val start = durationSeconds * 0.25
        return start to minOf(durationSeconds, start + WINDOW_SECONDS)
    }
}
