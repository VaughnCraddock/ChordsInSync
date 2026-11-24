package com.music.bitchord.data.bpm

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.music.bitchord.playback.smart.PcmConvert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Converts an Opus file to AAC in an MP4 sibling — the file DJ software
 * can read — without touching the original.
 *
 * Why this exists: Opus is a fine codec that Vaughn's DJ tools refuse to
 * speak (rekordbox and Serato have no Opus support at all; Engine DJ's is
 * unofficial), and [BpmTags] cannot write a BPM into an Ogg container, so
 * an Opus track could be analyzed but never tagged. Converting first
 * makes the whole flow one step: convert → analyze the AAC → write its
 * `tmpo`. The original file is **never** deleted, renamed or replaced;
 * the copy lands beside it under [OpusFiles.outputNameFor], and when that
 * sibling already exists it is treated as the conversion (batch re-runs
 * stay idempotent) rather than overwritten.
 *
 * ### The route, and why not FFmpeg
 *
 * The transcode is the platform's own: [MediaExtractor] demuxes,
 * [MediaCodec] decodes Opus (an AOSP decoder since API 21) and encodes
 * AAC-LC at 256 kb/s, [MediaMuxer] writes the MP4. No new dependency, no
 * APK growth. The FFmpeg routes were checked first (2026-11-23, see
 * FORK-NOTES "Opus→AAC conversion"): FFmpegKit was retired in April 2025
 * and its binaries pulled from Maven Central, its official successor
 * ships source only, the surviving community fork is young and would add
 * a large native supply chain for a job the platform does natively, and
 * the FFmpeg this app already vendors (librempeg) is a decode-only
 * Media3 extension with no encoder or muxer to call. The pump below is
 * [com.music.bitchord.playback.smart.AudioDecoder]'s decode loop with an
 * encoder and muxer behind it, and it honours the same hard-won rule:
 * decoded bytes are interpreted by the encoding the decoder *declares*
 * ([PcmConvert]), never assumed to be 16-bit.
 *
 * Tags: the caller passes the source's title/artist/album (the library's
 * own metadata for the song); they are written into the MP4 as iTunes
 * text atoms by [BpmTags.withMp4TextTags] before the file is published,
 * so the copy keeps the identity its source had. BPM is *not* written
 * here — that is the analysis flow's job, on the converted file, through
 * the verified [BpmTagStore] path like any other MP4.
 */
object OpusAacConverter {

    private const val TAG = "OpusAacConverter"
    private const val AAC_MIME = "audio/mp4a-latm"
    private const val OPUS_MIME = "audio/opus"
    private const val AAC_BIT_RATE = 256_000
    private const val TIMEOUT_US = 10_000L

    /** Decoded PCM allowed to wait ahead of the encoder: ~20 s of stereo 48 kHz, enough to smooth codec jitter, never a whole track. */
    private const val PENDING_CAP_BYTES = 4 * 1024 * 1024

    /** Above this the tag carry-over is skipped (the audio still converts): DJ tracks never get near it. */
    private const val MAX_TAG_CARRY_BYTES = 96 * 1024 * 1024

    /** How one conversion ended — every attempt ends in exactly one of these. */
    sealed interface Outcome {
        /** The AAC sibling at [uriString]; [alreadyExisted] when a previous run's copy was reused, not rewritten. */
        data class Converted(val uriString: String, val displayName: String, val alreadyExisted: Boolean) : Outcome

        /** The file's audio track is not Opus (a `.ogg` holding Vorbis, say): nothing to convert. */
        data object NotOpus : Outcome

        /** The conversion was attempted and refused; [reason] says why, in the batch flow's own vocabulary. */
        data class Failed(val reason: BpmFailure) : Outcome
    }

    /**
     * Converts the file at [uriString] to its AAC sibling, carrying
     * [title]/[artist]/[album] into the copy's tags. See [Outcome].
     */
    suspend fun convert(
        context: Context,
        uriString: String,
        title: String?,
        artist: String?,
        album: String?,
    ): Outcome = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return@withContext Outcome.Failed(BpmFailure.UNREADABLE)
        val sourceName = displayNameOf(context, uri)
            ?: return@withContext Outcome.Failed(BpmFailure.UNREADABLE)
        val outputName = OpusFiles.outputNameFor(sourceName)

        // Codec check first: candidacy was by file name, and only the
        // container knows whether the audio inside is really Opus.
        val extractor = MediaExtractor()
        try {
            when (uri.scheme) {
                "file" -> extractor.setDataSource(uri.path ?: return@withContext Outcome.Failed(BpmFailure.UNREADABLE))
                "content" -> extractor.setDataSource(context, uri, null)
                else -> return@withContext Outcome.Failed(BpmFailure.UNREADABLE)
            }
        } catch (error: Exception) {
            runCatching { extractor.release() }
            return@withContext Outcome.Failed(BpmFailure.UNREADABLE)
        }
        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        val sourceFormat = trackIndex?.let { extractor.getTrackFormat(it) }
        val mime = sourceFormat?.getString(MediaFormat.KEY_MIME)
        if (trackIndex == null || sourceFormat == null || mime == null) {
            runCatching { extractor.release() }
            return@withContext Outcome.Failed(BpmFailure.UNREADABLE)
        }
        if (mime != OPUS_MIME) {
            runCatching { extractor.release() }
            return@withContext Outcome.NotOpus
        }
        extractor.selectTrack(trackIndex)

        // An existing sibling is a previous conversion: reuse it, never
        // clobber it. (For content:// the check is a MediaStore query on
        // the source's own folder and the output name.)
        existingSibling(context, uri, outputName)?.let { sibling ->
            runCatching { extractor.release() }
            return@withContext Outcome.Converted(sibling, outputName, alreadyExisted = true)
        }

        val temp = File.createTempFile("opus-convert-", ".m4a", context.cacheDir)
        try {
            if (!transcode(extractor, sourceFormat, temp)) {
                return@withContext Outcome.Failed(BpmFailure.CONVERT_FAILED)
            }
            carryTags(temp, title, artist, album)
            publish(context, uri, outputName, temp)
        } catch (denied: SecurityException) {
            Outcome.Failed(BpmFailure.PERMISSION_DENIED)
        } catch (error: Exception) {
            Log.w(TAG, "Conversion of $sourceName failed", error)
            Outcome.Failed(BpmFailure.CONVERT_FAILED)
        } finally {
            runCatching { extractor.release() }
            runCatching { temp.delete() }
        }
    }

    /** The file's display name — the format fallback and the sibling-naming source. */
    fun displayNameOf(context: Context, uri: Uri): String? = when (uri.scheme) {
        "file" -> uri.path?.let { File(it).name }
        "content" -> runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        else -> null
    }

    // ------------------------------------------------------------ publishing

    /** The uri of an already-converted sibling, or null when there is none. */
    private fun existingSibling(context: Context, uri: Uri, outputName: String): String? = when (uri.scheme) {
        "file" -> {
            val parent = uri.path?.let { File(it).parentFile } ?: return null
            val sibling = File(parent, outputName)
            if (sibling.isFile) Uri.fromFile(sibling).toString() else null
        }
        "content" -> {
            val relativePath = relativePathOf(context, uri) ?: return null
            runCatching {
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(relativePath, outputName),
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0).toString()).toString()
                    } else {
                        null
                    }
                }
            }.getOrNull()
        }
        else -> null
    }

    private fun relativePathOf(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    /** Moves the finished temp file to its sibling home and returns the outcome. */
    private fun publish(context: Context, sourceUri: Uri, outputName: String, temp: File): Outcome =
        when (sourceUri.scheme) {
            "file" -> {
                val parent = sourceUri.path?.let { File(it).parentFile }
                    ?: return Outcome.Failed(BpmFailure.IO_ERROR)
                val target = File(parent, outputName)
                val staging = File(parent, ".$outputName.part")
                staging.delete()
                temp.inputStream().use { input -> staging.outputStream().use { input.copyTo(it) } }
                if (!staging.renameTo(target)) {
                    staging.delete()
                    return Outcome.Failed(BpmFailure.IO_ERROR)
                }
                MediaScannerConnection.scanFile(context, arrayOf(target.path), null, null)
                Outcome.Converted(Uri.fromFile(target).toString(), outputName, alreadyExisted = false)
            }
            "content" -> {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, outputName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                    relativePathOf(context, sourceUri)?.let { put(MediaStore.MediaColumns.RELATIVE_PATH, it) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                val newUri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return Outcome.Failed(BpmFailure.IO_ERROR)
                try {
                    context.contentResolver.openOutputStream(newUri, "wt")?.use { out ->
                        temp.inputStream().use { it.copyTo(out) }
                    } ?: throw java.io.IOException("no output stream for $newUri")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                        context.contentResolver.update(newUri, done, null, null)
                    }
                    Outcome.Converted(newUri.toString(), outputName, alreadyExisted = false)
                } catch (error: Exception) {
                    runCatching { context.contentResolver.delete(newUri, null, null) }
                    throw error
                }
            }
            else -> Outcome.Failed(BpmFailure.UNREADABLE)
        }

    /** Writes the source's identity into the converted MP4's text atoms, best-effort. */
    private fun carryTags(temp: File, title: String?, artist: String?, album: String?) {
        if (title.isNullOrBlank() && artist.isNullOrBlank() && album.isNullOrBlank()) return
        if (temp.length() > MAX_TAG_CARRY_BYTES) return
        runCatching {
            val tagged = BpmTags.withMp4TextTags(temp.readBytes(), title, artist, album)
            if (tagged != null) temp.writeBytes(tagged)
        }.onFailure { Log.w(TAG, "Tag carry-over failed; the audio is unaffected", it) }
    }

    // -------------------------------------------------------------- the pump

    /**
     * Demux → decode → encode → mux, streaming: decoded buffers are
     * converted and handed to the encoder as they arrive, so the track
     * never sits in memory whole. Returns false on any codec or container
     * refusal; the caller reports it as one conversion failure.
     */
    private fun transcode(extractor: MediaExtractor, sourceFormat: MediaFormat, output: File): Boolean {
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            decoder = MediaCodec.createDecoderByType(OPUS_MIME)
            decoder.configure(sourceFormat, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var pcmRate = sourceFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 48_000
            var pcmChannels = sourceFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 2
            var pcmEncoding = sourceFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT

            var inputDone = false
            var decodeDone = false
            var encodeDone = false
            var muxerStarted = false
            var muxerTrack = -1
            // The encoder is only configured once the decoder's real
            // output format is known: an Opus container's declared rate
            // is the *original* recording rate, while the decoder hands
            // back 48 kHz PCM regardless, and an encoder built on the
            // declared number would stamp the file with a lie.
            var decoderFormatKnown = false

            // PCM waiting for an encoder input buffer, and how much of it
            // has been handed over; the encoder's timestamps are counted
            // in frames from zero, which is the decode's own timeline.
            var pending = ByteArray(0)
            var pendingOffset = 0
            var framesQueued = 0L
            var encoderInputEos = false

            fun ensureEncoder(): MediaCodec? {
                if (encoder != null) return encoder
                val channelsOut = if (pcmChannels <= 1) 1 else 2
                val format = MediaFormat.createAudioFormat(AAC_MIME, pcmRate, channelsOut).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                }
                encoder = MediaCodec.createEncoderByType(AAC_MIME).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
                muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                return encoder
            }

            while (!encodeDone) {
                // Feed the decoder until it has no free input slot.
                while (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(0L)
                    if (inputIndex < 0) break
                    val inputBuffer = decoder.getInputBuffer(inputIndex) ?: break
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                // Drain the decoder into the pending PCM — but only while
                // the pending PCM is small: the transcode streams, so a
                // slow encoder must slow the decode down rather than let
                // a whole track pile up in memory ahead of it.
                var progressed = false
                var pendingFull = false
                while (!pendingFull) {
                    val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, if (progressed) 0L else TIMEOUT_US)
                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    progressed = true
                    when (outputIndex) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = decoder.outputFormat
                            pcmRate = newFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: pcmRate
                            pcmChannels = newFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: pcmChannels
                            pcmEncoding = newFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING) ?: pcmEncoding
                            decoderFormatKnown = true
                        }
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        else -> if (outputIndex >= 0) {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decodeDone = true
                            if (bufferInfo.size > 0) {
                                if (!PcmConvert.isSupported(pcmEncoding)) {
                                    Log.w(TAG, "Opus decoder outputs unsupported PCM encoding $pcmEncoding")
                                    return false
                                }
                                decoder.getOutputBuffer(outputIndex)?.let { buffer ->
                                    pending += decodeBufferToPcm16(buffer, bufferInfo, pcmChannels, pcmEncoding)
                                }
                                if (pending.size - pendingOffset > PENDING_CAP_BYTES) pendingFull = true
                            }
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }

                // Hand pending PCM to the encoder, in input-buffer-sized
                // slices; timestamps are frame counts, so a slice boundary
                // can fall anywhere without disturbing the timeline. A
                // slot is only dequeued when there is something to put in
                // it — a dequeued-but-never-queued input buffer is lost
                // to the codec for good.
                // No format change by end-of-stream means the output is
                // the configured format, so the source values are right.
                val enc = if (decoderFormatKnown || decodeDone) ensureEncoder() else null
                while (enc != null) {
                    val channelsOut = if (pcmChannels <= 1) 1 else 2
                    val frameBytes = PcmInterleave.frameBytes(channelsOut)
                    val availableFrames = (pending.size - pendingOffset) / frameBytes
                    val sendEos = decodeDone && availableFrames == 0 && !encoderInputEos
                    if (availableFrames == 0 && !sendEos) break
                    val inputIndex = enc.dequeueInputBuffer(if (availableFrames > 0) 0L else TIMEOUT_US)
                    if (inputIndex < 0) break
                    val inputBuffer = enc.getInputBuffer(inputIndex) ?: break
                    val pts = PcmInterleave.presentationTimeUs(framesQueued, pcmRate)
                    if (sendEos) {
                        enc.queueInputBuffer(inputIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        encoderInputEos = true
                        break
                    }
                    val frames = minOf(inputBuffer.capacity() / frameBytes, availableFrames)
                    if (frames <= 0) break
                    inputBuffer.clear()
                    inputBuffer.put(pending, pendingOffset, frames * frameBytes)
                    pendingOffset += frames * frameBytes
                    framesQueued += frames
                    enc.queueInputBuffer(inputIndex, 0, frames * frameBytes, pts, 0)
                    if (pendingOffset >= pending.size) {
                        pending = ByteArray(0)
                        pendingOffset = 0
                    }
                }

                // Drain the encoder into the muxer; the first dequeue of
                // a pass may wait, so a codec that needs a moment gets one
                // instead of the outer loop spinning against it.
                var drainProgressed = false
                while (enc != null) {
                    val outputIndex = enc.dequeueOutputBuffer(bufferInfo, if (drainProgressed) 0L else TIMEOUT_US)
                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    drainProgressed = true
                    when (outputIndex) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                muxerTrack = muxer!!.addTrack(enc.outputFormat)
                                muxer!!.start()
                                muxerStarted = true
                            }
                        }
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        else -> if (outputIndex >= 0) {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encodeDone = true
                            if (bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                if (!muxerStarted) return false
                                enc.getOutputBuffer(outputIndex)?.let { buffer ->
                                    buffer.position(bufferInfo.offset)
                                    buffer.limit(bufferInfo.offset + bufferInfo.size)
                                    muxer!!.writeSampleData(muxerTrack, buffer, bufferInfo)
                                }
                            }
                            enc.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }

            }
            return muxerStarted
        } catch (error: Exception) {
            Log.w(TAG, "Transcode failed", error)
            return false
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    /** One decoded buffer → interleaved 16-bit PCM, keeping the source's channels (mono stays mono; 2+ keeps the first two). */
    private fun decodeBufferToPcm16(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channels: Int,
        encoding: Int,
    ): ByteArray {
        if (channels <= 1) {
            val mono = PcmConvert.toMono(buffer, info.offset, info.size, 1, encoding)
            return PcmInterleave.toPcm16Bytes(mono, null)
        }
        val left = mutableListOf<FloatArray>()
        val right = mutableListOf<FloatArray>()
        PcmConvert.toStereo(buffer, info.offset, info.size, channels, encoding, left, right)
        val leftAll = flatten(left)
        val rightAll = flatten(right)
        return PcmInterleave.toPcm16Bytes(leftAll, rightAll)
    }

    private fun flatten(chunks: List<FloatArray>): FloatArray {
        val samples = FloatArray(chunks.sumOf { it.size })
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(samples, offset)
            offset += chunk.size
        }
        return samples
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null
}
