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
 */
object BpmAnalyzer {

    private const val WINDOW_SECONDS = 75.0

    /** The estimated tempo of the file at [uriString], or null when it can't be read or has no findable tempo. */
    suspend fun analyze(context: Context, uriString: String): TempoEstimator.Estimate? =
        withContext(Dispatchers.IO) {
            val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext null
            val source = LocalAudioSource.open(context.contentResolver, uri) ?: return@withContext null
            try {
                val duration = AudioDecoder.containerDurationSeconds(source)
                val (start, end) = windowFor(duration)
                val decoded = AudioDecoder.decodeRegion(source, start, end) ?: return@withContext null
                val pcm = decoded.first
                TempoEstimator.estimate(pcm.samples, pcm.sampleRate.toInt())
            } catch (error: Exception) {
                null
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
