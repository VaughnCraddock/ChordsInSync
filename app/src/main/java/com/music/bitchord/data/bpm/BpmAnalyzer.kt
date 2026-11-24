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
 * Which stretches get decoded follows the rework's measurements (see
 * FORK-NOTES, "BPM rework"): a single window's answer can be dragged to a
 * metric impostor by one unlucky stretch of a song — the first field
 * failure's track measured 162.7 BPM in the old 75 s window and 122.0
 * over the whole song — so a long track is sampled at three windows
 * (30 s each, starting 8 %, 38 % and 68 % in) and the estimator takes
 * their consensus. A short track is decoded whole; a track of unknown
 * duration keeps the old single 75 s probe, there being nothing to plan
 * the windows against. Total decoded audio is ~90 s either way, so batch
 * runs cost what they always cost.
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

    /** One consensus window's length, for tracks long enough to plan windows over. */
    private const val WINDOW_SECONDS = 30.0

    /** Where the consensus windows start, as fractions of the track's duration. */
    private val WINDOW_STARTS = doubleArrayOf(0.08, 0.38, 0.68)

    /** At or under this duration the whole track is the window — simpler, and every second counts. */
    private const val WHOLE_TRACK_SECONDS = 100.0

    /** The fallback probe when the container won't say how long it is. */
    private const val UNKNOWN_DURATION_PROBE_SECONDS = 75.0

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
                val windows = mutableListOf<AudioDecoder.Pcm>()
                for ((start, end) in windowPlan(duration)) {
                    val decoded = AudioDecoder.decodeRegion(source, start, end)
                    if (decoded != null) windows += decoded.first
                }
                // Every planned window failing to decode is a decode fault,
                // not a property of the music; one surviving window is
                // enough for the estimator to speak.
                if (windows.isEmpty()) return@withContext Outcome.Unreadable
                val sampleRate = windows.first().sampleRate
                val estimate = TempoEstimator.estimateWindows(
                    windows.map { it.samples },
                    sampleRate.toInt(),
                )
                if (estimate != null) Outcome.Found(estimate) else Outcome.NoTempo
            } catch (error: Exception) {
                Outcome.Unreadable
            } finally {
                runCatching { source.close() }
            }
        }

    /** The (start, end) second ranges to decode for a track of [durationSeconds]. */
    private fun windowPlan(durationSeconds: Double?): List<Pair<Double, Double>> {
        if (durationSeconds == null) return listOf(0.0 to UNKNOWN_DURATION_PROBE_SECONDS)
        if (durationSeconds <= WHOLE_TRACK_SECONDS) return listOf(0.0 to durationSeconds)
        return WINDOW_STARTS.map { fraction ->
            val start = durationSeconds * fraction
            start to minOf(durationSeconds, start + WINDOW_SECONDS)
        }
    }
}
