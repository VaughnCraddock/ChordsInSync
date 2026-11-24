package com.music.bitchord.data.bpm

import kotlin.math.roundToInt

/**
 * Planar float PCM → interleaved 16-bit PCM bytes, the shape an AAC
 * encoder's input buffer wants. Pure and unit-tested: the converter's
 * codec pump is the only Android-bound part of transcoding, and every
 * sample it moves passes through here.
 *
 * Float samples outside [-1, 1] are clamped, not wrapped: a hot master's
 * overshoot (the decoder's float output goes past 1.0 on mastered audio)
 * must land on the rail, not on the opposite sign.
 */
object PcmInterleave {

    /** Bytes one frame of [channels] 16-bit samples takes. */
    fun frameBytes(channels: Int): Int = channels * 2

    /**
     * Interleaves [left] with [right] (or copies [left] alone when [right]
     * is null, for a mono source) into little-endian 16-bit PCM. The two
     * channels are read to the shorter one's length.
     */
    fun toPcm16Bytes(left: FloatArray, right: FloatArray?): ByteArray {
        val frames = if (right == null) left.size else minOf(left.size, right.size)
        val out = ByteArray(frames * (if (right == null) 1 else 2) * 2)
        var offset = 0
        for (frame in 0 until frames) {
            offset = putSample(out, offset, left[frame])
            if (right != null) offset = putSample(out, offset, right[frame])
        }
        return out
    }

    /** The presentation time of the frame that starts [samplesPerChannel] frames in, in microseconds. */
    fun presentationTimeUs(samplesPerChannel: Long, sampleRate: Int): Long =
        if (sampleRate <= 0) 0L else samplesPerChannel * 1_000_000L / sampleRate

    private fun putSample(out: ByteArray, offset: Int, sample: Float): Int {
        val value = (sample.coerceIn(-1f, 1f) * 32767f).roundToInt()
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value shr 8) and 0xFF).toByte()
        return offset + 2
    }
}
