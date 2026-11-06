package com.music.bitchord.playback.smart

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Turns a decoder's raw output bytes into float samples.
 *
 * MediaCodec does **not** promise 16-bit PCM. The output format's
 * `KEY_PCM_ENCODING` says what the decoder actually hands back, and it is a
 * device property: many decoders answer 16-bit, but some — the AAC decoders
 * in recent Samsung/Qualcomm stacks among them — answer float. Reading float
 * bytes as 16-bit shorts produces garbage with no periodicity in it, which
 * downstream analysis then (correctly, for garbage) reports as "nothing
 * found", with no error anywhere: playback is unaffected, because the
 * playback pipeline negotiates the encoding properly, so the file plays
 * perfectly while analysis silently sees noise. That failure shipped in the
 * BPM analyzer's first field test (2026-11-05) and is why this converter
 * exists: every consumer of [AudioDecoder] gets samples converted according
 * to the encoding the decoder declared, and an encoding nobody recognises
 * fails the decode outright instead of feeding noise onward.
 *
 * Pure JVM on purpose — the encoding constants below mirror
 * `android.media.AudioFormat.ENCODING_PCM_*` as plain ints so the conversion
 * is unit-testable without Android types.
 */
internal object PcmConvert {

    /** Mirrors AudioFormat.ENCODING_PCM_16BIT. */
    const val PCM_16BIT = 2

    /** Mirrors AudioFormat.ENCODING_PCM_8BIT (unsigned). */
    const val PCM_8BIT = 3

    /** Mirrors AudioFormat.ENCODING_PCM_FLOAT. */
    const val PCM_FLOAT = 4

    /** Mirrors AudioFormat.ENCODING_PCM_24BIT_PACKED. */
    const val PCM_24BIT_PACKED = 21

    /** Mirrors AudioFormat.ENCODING_PCM_32BIT. */
    const val PCM_32BIT = 22

    /** Bytes per sample per channel for [encoding], or null when this converter doesn't know it. */
    fun bytesPerSample(encoding: Int): Int? = when (encoding) {
        PCM_8BIT -> 1
        PCM_16BIT -> 2
        PCM_24BIT_PACKED -> 3
        PCM_FLOAT, PCM_32BIT -> 4
        else -> null
    }

    /** Whether [toMono]/[toStereo] can read [encoding] at all. */
    fun isSupported(encoding: Int): Boolean = bytesPerSample(encoding) != null

    /**
     * Downmixes the interleaved PCM in `buffer[offset, offset+size)` to mono
     * float, averaging the channels of each frame. A trailing partial frame
     * is dropped, as it was when only 16-bit output existed.
     */
    fun toMono(buffer: ByteBuffer, offset: Int, size: Int, channels: Int, encoding: Int): FloatArray {
        val safeChannels = max(1, channels)
        val (bytes, totalSamples) = view(buffer, offset, size, encoding) ?: return FloatArray(0)
        val frames = totalSamples / safeChannels
        val mono = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until safeChannels) sum += sample(bytes, frame * safeChannels + channel, encoding)
            mono[frame] = sum / safeChannels
        }
        return mono
    }

    /**
     * Splits the interleaved PCM in `buffer[offset, offset+size)` into planar
     * left/right float, appending one chunk to each list. A mono source is
     * widened by sharing its samples; anything past two channels keeps the
     * first two, matching the behaviour this converter replaces.
     */
    fun toStereo(
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        channels: Int,
        encoding: Int,
        left: MutableList<FloatArray>,
        right: MutableList<FloatArray>,
    ) {
        val safeChannels = max(1, channels)
        val (bytes, totalSamples) = view(buffer, offset, size, encoding) ?: return
        val frames = totalSamples / safeChannels
        val leftChunk = FloatArray(frames)
        val rightChunk = FloatArray(frames)
        for (frame in 0 until frames) {
            val base = frame * safeChannels
            leftChunk[frame] = sample(bytes, base, encoding)
            rightChunk[frame] = if (safeChannels > 1) sample(bytes, base + 1, encoding) else leftChunk[frame]
        }
        left += leftChunk
        right += rightChunk
    }

    /**
     * A little-endian view over the region, paired with how many whole
     * samples of [encoding] it holds — or null when the region can't hold
     * even one sample (or the encoding is unknown, which the caller is
     * expected to have ruled out via [isSupported] before decoding).
     */
    private fun view(buffer: ByteBuffer, offset: Int, size: Int, encoding: Int): Pair<ByteBuffer, Int>? {
        val perSample = bytesPerSample(encoding) ?: return null
        if (size < perSample || offset < 0 || offset + size > buffer.capacity()) return null
        val bytes = buffer.duplicate().apply {
            position(offset)
            limit(offset + size)
        }.slice().apply { order(ByteOrder.LITTLE_ENDIAN) }
        return bytes to size / perSample
    }

    /** One sample out of [bytes] (a [view] result) at [index], by [encoding]. */
    private fun sample(bytes: ByteBuffer, index: Int, encoding: Int): Float =
        when (encoding) {
            // 8-bit PCM is unsigned: silence is 128, not 0.
            PCM_8BIT -> ((bytes.get(index).toInt() and 0xFF) - 128) / 128f
            PCM_16BIT -> bytes.getShort(index * 2) / 32768f
            PCM_24BIT_PACKED -> {
                val base = index * 3
                val value = (bytes.get(base).toInt() and 0xFF) or
                    ((bytes.get(base + 1).toInt() and 0xFF) shl 8) or
                    (bytes.get(base + 2).toInt() shl 16)
                value / 8388608f
            }
            PCM_32BIT -> (bytes.getInt(index * 4) / 2147483648.0).toFloat()
            PCM_FLOAT -> bytes.getFloat(index * 4)
            else -> 0f
        }
}
