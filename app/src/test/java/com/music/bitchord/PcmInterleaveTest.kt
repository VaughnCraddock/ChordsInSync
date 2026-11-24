package com.music.bitchord

import com.music.bitchord.data.bpm.PcmInterleave
import org.junit.Assert.assertEquals
import org.junit.Test

/** The converter's sample plumbing: exact bytes, clamping, and the frame-count timestamps. */
class PcmInterleaveTest {

    @Test
    fun stereo_interleavesLittleEndian() {
        val bytes = PcmInterleave.toPcm16Bytes(
            floatArrayOf(0f, 1f, -1f),
            floatArrayOf(0.5f, -0.5f, 0f),
        )
        // Frames: (0, 16384) (32767, -16383) (-32767, 0), little-endian.
        // (The half-scale values differ by one LSB between signs: rounding
        // ties go toward positive infinity, as Math.round does.)
        assertEquals(12, bytes.size)
        assertEquals(0, bytes.shortAt(0))
        assertEquals(16384, bytes.shortAt(1))
        assertEquals(32767, bytes.shortAt(2))
        assertEquals(-16383, bytes.shortAt(3))
        assertEquals(-32767, bytes.shortAt(4))
        assertEquals(0, bytes.shortAt(5))
    }

    @Test
    fun mono_copiesTheSingleChannel() {
        val bytes = PcmInterleave.toPcm16Bytes(floatArrayOf(1f, -1f), null)
        assertEquals(4, bytes.size)
        assertEquals(32767, bytes.shortAt(0))
        assertEquals(-32767, bytes.shortAt(1))
    }

    @Test
    fun overshoot_clampsToTheRail_neverWraps() {
        // A hot master's float overshoot must land on the rail; a wrap
        // would put a loud positive peak at full negative scale.
        val bytes = PcmInterleave.toPcm16Bytes(floatArrayOf(1.9f, -2.4f), null)
        assertEquals(32767, bytes.shortAt(0))
        assertEquals(-32767, bytes.shortAt(1))
    }

    @Test
    fun unevenChannels_readToTheShorter() {
        val bytes = PcmInterleave.toPcm16Bytes(FloatArray(5), FloatArray(3))
        assertEquals(3 * 4, bytes.size)
    }

    @Test
    fun presentationTimes_countFrames() {
        assertEquals(0L, PcmInterleave.presentationTimeUs(0, 48_000))
        assertEquals(1_000_000L, PcmInterleave.presentationTimeUs(48_000, 48_000))
        assertEquals(500_000L, PcmInterleave.presentationTimeUs(22_050, 44_100))
        assertEquals(0L, PcmInterleave.presentationTimeUs(100, 0))
    }

    @Test
    fun frameBytes_followChannels() {
        assertEquals(2, PcmInterleave.frameBytes(1))
        assertEquals(4, PcmInterleave.frameBytes(2))
    }

    private fun ByteArray.shortAt(sampleIndex: Int): Int {
        val offset = sampleIndex * 2
        return (this[offset].toInt() and 0xFF) or (this[offset + 1].toInt() shl 8)
    }
}
