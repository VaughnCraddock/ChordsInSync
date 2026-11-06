package com.music.bitchord

import com.music.bitchord.data.bpm.TempoEstimator
import com.music.bitchord.playback.smart.PcmConvert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * [PcmConvert] against hand-computed sample values, and end to end into
 * [TempoEstimator].
 *
 * The end-to-end cases are the 2026-11-05 field failure rebuilt as
 * fixtures: a 122 BPM track whose decoder hands over **float** PCM (and,
 * for the other non-16-bit family, 24-bit PCM), where the pre-fix decoder
 * read every buffer as 16-bit and produced structureless garbage that the
 * estimator — correctly, for garbage — reported as "no tempo detected" on
 * a steady house track.
 */
class PcmConvertTest {

    private fun bufferOf(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes)

    private fun mono(bytes: ByteArray, channels: Int, encoding: Int, offset: Int = 0): FloatArray =
        PcmConvert.toMono(bufferOf(bytes), offset, bytes.size - offset, channels, encoding)

    @Test
    fun pcm16_exactValues() {
        val bytes = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0); putShort(16384); putShort(-16384); putShort(32767); putShort(-32768)
        }.array()
        val out = mono(bytes, channels = 1, encoding = PcmConvert.PCM_16BIT)
        assertEquals(5, out.size)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-6f)
        assertEquals(-0.5f, out[2], 1e-6f)
        assertEquals(32767 / 32768f, out[3], 1e-6f)
        assertEquals(-1f, out[4], 1e-6f)
    }

    @Test
    fun pcmFloat_passesThroughIncludingOvershoot() {
        val bytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(0.25f); putFloat(-0.5f); putFloat(1.5f)
        }.array()
        val out = mono(bytes, channels = 1, encoding = PcmConvert.PCM_FLOAT)
        assertEquals(0.25f, out[0], 1e-6f)
        assertEquals(-0.5f, out[1], 1e-6f)
        // Mastered audio overshoots 1.0 in float; the value must survive,
        // not wrap or clip — the envelope only needs relative shape.
        assertEquals(1.5f, out[2], 1e-6f)
    }

    @Test
    fun pcm8_isUnsigned() {
        val out = mono(byteArrayOf(0, 64, 128.toByte(), 255.toByte()), channels = 1, encoding = PcmConvert.PCM_8BIT)
        assertEquals(-1f, out[0], 1e-6f)
        assertEquals(-0.5f, out[1], 1e-6f)
        assertEquals(0f, out[2], 1e-6f)
        assertEquals(127 / 128f, out[3], 1e-6f)
    }

    @Test
    fun pcm24_signExtends() {
        // 0x000000 -> 0, 0x7FFFFF -> ~1, 0x800000 -> -1, 0xFF0000 -> -65536 (sign extension, not 16.7M)
        val bytes = byteArrayOf(
            0, 0, 0,
            0xFF.toByte(), 0xFF.toByte(), 0x7F,
            0, 0, 0x80.toByte(),
            0, 0, 0xFF.toByte(),
        )
        val out = mono(bytes, channels = 1, encoding = PcmConvert.PCM_24BIT_PACKED)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(8388607 / 8388608f, out[1], 1e-6f)
        assertEquals(-1f, out[2], 1e-6f)
        assertEquals(-65536 / 8388608f, out[3], 1e-6f)
    }

    @Test
    fun pcm32_exactValues() {
        val bytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0); putInt(Int.MAX_VALUE); putInt(Int.MIN_VALUE)
        }.array()
        val out = mono(bytes, channels = 1, encoding = PcmConvert.PCM_32BIT)
        assertEquals(0f, out[0], 1e-5f)
        assertEquals(1f, out[1], 1e-5f)
        assertEquals(-1f, out[2], 1e-5f)
    }

    @Test
    fun stereoAveragesAndHonoursOffset() {
        // Two junk bytes in front (a codec buffer's offset), then one stereo
        // float frame L=0.6 R=0.2 -> mono 0.4.
        val bytes = ByteBuffer.allocate(2 + 8).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0x55); put(0x55); putFloat(0.6f); putFloat(0.2f)
        }.array()
        val out = mono(bytes, channels = 2, encoding = PcmConvert.PCM_FLOAT, offset = 2)
        assertEquals(1, out.size)
        assertEquals(0.4f, out[0], 1e-6f)
    }

    @Test
    fun stereoSplitKeepsChannelsApart() {
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(16384); putShort(-16384)
        }.array()
        val left = ArrayList<FloatArray>()
        val right = ArrayList<FloatArray>()
        PcmConvert.toStereo(bufferOf(bytes), 0, bytes.size, 2, PcmConvert.PCM_16BIT, left, right)
        assertEquals(0.5f, left[0][0], 1e-6f)
        assertEquals(-0.5f, right[0][0], 1e-6f)
    }

    @Test
    fun unknownEncodingIsUnsupportedNotGuessed() {
        assertFalse(PcmConvert.isSupported(999))
        assertEquals(null, PcmConvert.bytesPerSample(999))
        assertEquals(0, mono(byteArrayOf(1, 2, 3, 4), channels = 1, encoding = 999).size)
    }

    @Test
    fun floatDecodedClickTrack_122bpm_estimates() {
        val estimate = TempoEstimator.estimate(convertedClickTrack(PcmConvert.PCM_FLOAT, gain = 1.0f), SAMPLE_RATE)
        assertTempo122(estimate)
    }

    @Test
    fun floatDecodedHotClickTrack_122bpm_estimates() {
        // Full-scale bursts with float overshoot past 1.0: a loud master the
        // way a float decoder hands it over.
        val estimate = TempoEstimator.estimate(convertedClickTrack(PcmConvert.PCM_FLOAT, gain = 1.3f), SAMPLE_RATE)
        assertTempo122(estimate)
    }

    @Test
    fun pcm24DecodedClickTrack_122bpm_estimates() {
        val estimate = TempoEstimator.estimate(convertedClickTrack(PcmConvert.PCM_24BIT_PACKED, gain = 1.0f), SAMPLE_RATE)
        assertTempo122(estimate)
    }

    private fun assertTempo122(estimate: TempoEstimator.Estimate?) {
        assertNotNull("no tempo found for a 122 BPM click track", estimate)
        val error = kotlin.math.abs(estimate!!.bpm - 122.0) / 122.0
        assertTrue("expected within 2% of 122 BPM, got ${estimate.bpm}", error <= 0.02)
    }

    /**
     * A 122 BPM click track at 44.1 kHz, rendered as interleaved stereo in
     * [encoding] and converted back to mono through [PcmConvert] — the exact
     * shape of the on-device path (decoder bytes in, estimator samples out).
     */
    private fun convertedClickTrack(encoding: Int, gain: Float): FloatArray {
        val seconds = 20
        val mono = FloatArray(seconds * SAMPLE_RATE)
        val period = 60.0 / 122.0
        val burstSamples = (0.03 * SAMPLE_RATE).toInt()
        var seed = 0x12345678
        fun noise(): Float {
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            return (seed / 0x40000000f) - 1f
        }
        var beat = 0
        while (true) {
            val start = (beat * period * SAMPLE_RATE).toInt()
            if (start >= mono.size) break
            for (i in 0 until burstSamples) {
                val index = start + i
                if (index >= mono.size) break
                val decay = exp(-i.toDouble() / (0.006 * SAMPLE_RATE)).toFloat()
                mono[index] += noise() * decay * 0.9f * gain
            }
            beat++
        }
        val perSample = PcmConvert.bytesPerSample(encoding)!!
        val bytes = ByteBuffer.allocate(mono.size * 2 * perSample).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in mono) {
            repeat(2) { putSample(bytes, sample, encoding) }
        }
        return PcmConvert.toMono(bytes, 0, bytes.capacity(), channels = 2, encoding = encoding)
    }

    private fun putSample(bytes: ByteBuffer, sample: Float, encoding: Int) {
        when (encoding) {
            PcmConvert.PCM_FLOAT -> bytes.putFloat(sample)
            PcmConvert.PCM_24BIT_PACKED -> {
                val value = (sample.coerceIn(-1f, 1f) * 8388608f).toInt()
                bytes.put((value and 0xFF).toByte())
                bytes.put(((value shr 8) and 0xFF).toByte())
                bytes.put(((value shr 16) and 0xFF).toByte())
            }
            else -> error("fixture only renders float and 24-bit")
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
