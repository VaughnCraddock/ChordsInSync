package com.music.bitchord

import com.music.bitchord.data.bpm.BpmTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Tag round-trips on generated fixtures: write a BPM, read it back with
 * the same reader the app verifies with, and prove the tags that were
 * already there survived byte-for-byte.
 */
class BpmTagsTest {

    // -------------------------------------------------------------------- MP3

    @Test
    fun mp3_id3v24_roundTrips_andPreservesFrames() {
        val audio = fakeMpegAudio()
        val fixture = id3Tag(
            major = 4,
            frames = listOf(
                "TIT2" to byteArrayOf(3) + "Fixture Title".toByteArray(Charsets.UTF_8),
                "TPE1" to byteArrayOf(3) + "Fixture Artist".toByteArray(Charsets.UTF_8),
            ),
            audio = audio,
        )
        val written = BpmTags.withBpm(fixture, 128.5, "song.mp3")
        assertNotNull(written)
        assertEquals(128.5, BpmTags.readBpm(written!!, "song.mp3")!!, 0.001)
        // Pre-existing frames survive with their payloads intact.
        assertTrue(written.containsFrameText("TIT2", "Fixture Title"))
        assertTrue(written.containsFrameText("TPE1", "Fixture Artist"))
        // The audio itself is untouched, just (possibly) shifted.
        assertTrue(written.endsWith(audio))
        assertEquals(1, written.countOccurrences("TBPM"))
    }

    @Test
    fun mp3_id3v23_roundTrips() {
        val fixture = id3Tag(
            major = 3,
            frames = listOf("TIT2" to byteArrayOf(0) + "Old Tag".toByteArray(Charsets.ISO_8859_1)),
            audio = fakeMpegAudio(),
        )
        val written = BpmTags.withBpm(fixture, 120.0, "song.mp3")
        assertNotNull(written)
        assertEquals(120.0, BpmTags.readBpm(written!!, "song.mp3")!!, 0.001)
        assertTrue(written.containsFrameText("TIT2", "Old Tag"))
    }

    @Test
    fun mp3_existingTbpm_isReplacedNotDuplicated() {
        val fixture = id3Tag(
            major = 4,
            frames = listOf("TBPM" to byteArrayOf(3) + "100".toByteArray(Charsets.UTF_8)),
            audio = fakeMpegAudio(),
        )
        assertEquals(100.0, BpmTags.readBpm(fixture, "song.mp3")!!, 0.001)
        val written = BpmTags.withBpm(fixture, 128.0, "song.mp3")!!
        assertEquals(128.0, BpmTags.readBpm(written, "song.mp3")!!, 0.001)
        assertEquals(1, written.countOccurrences("TBPM"))
    }

    @Test
    fun mp3_withoutAnyTag_getsOne() {
        val audio = fakeMpegAudio()
        val written = BpmTags.withBpm(audio, 140.0, "bare.mp3")
        assertNotNull(written)
        assertEquals(140.0, BpmTags.readBpm(written!!, "bare.mp3")!!, 0.001)
        assertTrue(written.endsWith(audio))
    }

    // ------------------------------------------------------------------- FLAC

    @Test
    fun flac_roundTrips_andPreservesCommentFields() {
        val frames = fakeFlacFrames()
        val fixture = flacFile(
            comment = VorbisFixture(
                vendor = "fixture/1.0",
                fields = listOf("TITLE=Fixture Song", "GENRE=Test", "ARTIST=Someone"),
            ),
            frames = frames,
        )
        val written = BpmTags.withBpm(fixture, 122.0, "song.flac")!!
        assertEquals(122.0, BpmTags.readBpm(written, "song.flac")!!, 0.001)
        assertTrue("audio frames must survive", written.endsWith(frames))
        // Second write replaces, never duplicates, and keeps the rest.
        val rewritten = BpmTags.withBpm(written, 90.5, "song.flac")!!
        assertEquals(90.5, BpmTags.readBpm(rewritten, "song.flac")!!, 0.001)
        assertEquals(1, rewritten.countOccurrences("BPM="))
        val parsed = parseFlacComment(rewritten)
        assertEquals("fixture/1.0", parsed.vendor)
        assertTrue(parsed.fields.contains("TITLE=Fixture Song"))
        assertTrue(parsed.fields.contains("GENRE=Test"))
        assertTrue(parsed.fields.contains("ARTIST=Someone"))
    }

    @Test
    fun flac_withoutComment_getsOne() {
        val frames = fakeFlacFrames()
        val fixture = flacFile(comment = null, frames = frames)
        val written = BpmTags.withBpm(fixture, 87.0, "bare.flac")!!
        assertEquals(87.0, BpmTags.readBpm(written, "bare.flac")!!, 0.001)
        assertTrue(written.endsWith(frames))
    }

    // -------------------------------------------------------------------- MP4

    @Test
    fun mp4_withMeta_roundTrips_andPreservesItems() {
        val fixture = mp4WithMeta()
        val written = BpmTags.withBpm(fixture, 131.4, "song.m4a")!!
        // tmpo is an integer atom by definition: 131.4 lands as 131.
        assertEquals(131.0, BpmTags.readBpm(written, "song.m4a")!!, 0.001)
        assertTrue("the ©nam item must survive", written.containsBytes(NAME_ATOM_TEXT))
    }

    @Test
    fun mp4_withoutMeta_getsAChain_andChunkOffsetsMove() {
        val fixture = mp4Bare()
        val oldEntry = readStcoEntry(fixture.bytes)
        assertEquals(fixture.mdatDataOffset.toLong(), oldEntry)
        val written = BpmTags.withBpm(fixture.bytes, 96.0, "song.m4a")!!
        assertEquals(96.0, BpmTags.readBpm(written, "song.m4a")!!, 0.001)
        // The file grew only by the appended udta chain, and the chunk
        // offset moved by exactly that growth.
        val newEntry = readStcoEntry(written)
        assertEquals(oldEntry + (written.size - fixture.bytes.size), newEntry)
    }

    // -------------------------------------------------------------- refusals

    @Test
    fun ogg_isAnalysisOnly() {
        val ogg = "OggS".toByteArray(Charsets.US_ASCII) + ByteArray(200)
        assertNull(BpmTags.withBpm(ogg, 120.0, "song.ogg"))
        assertNull(BpmTags.readBpm(ogg, "song.ogg"))
    }

    @Test
    fun garbage_isRefusedNotGuessed() {
        val junk = ByteArray(300) { (it * 7 % 251).toByte() }
        assertNull(BpmTags.withBpm(junk, 120.0, "mystery.bin"))
    }

    // --------------------------------------------------------------- fixtures

    private fun fakeMpegAudio(): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(30) {
            out.write(0xFF)
            out.write(0xFB)
            out.write(0x90)
            out.write(0x00)
            out.write(ByteArray(100) { i -> (i % 13).toByte() })
        }
        return out.toByteArray()
    }

    private fun id3Tag(major: Int, frames: List<Pair<String, ByteArray>>, audio: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        for ((id, content) in frames) {
            body.write(id.toByteArray(Charsets.ISO_8859_1))
            if (major == 4) {
                body.writeSyncsafe(content.size)
            } else {
                body.writeU32(content.size.toLong())
            }
            body.write(0)
            body.write(0)
            body.write(content)
        }
        val bodyBytes = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.US_ASCII))
        out.write(major)
        out.write(0)
        out.write(0)
        out.writeSyncsafe(bodyBytes.size)
        out.write(bodyBytes)
        out.write(audio)
        return out.toByteArray()
    }

    private data class VorbisFixture(val vendor: String, val fields: List<String>)

    private fun flacFile(comment: VorbisFixture?, frames: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        val blocks = mutableListOf<Pair<Int, ByteArray>>()
        blocks += 0 to ByteArray(34) { 0x11 } // STREAMINFO.
        if (comment != null) {
            val payload = ByteArrayOutputStream()
            fun writeLe(value: Int) {
                payload.write(value and 0xFF)
                payload.write((value ushr 8) and 0xFF)
                payload.write((value ushr 16) and 0xFF)
                payload.write((value ushr 24) and 0xFF)
            }
            val vendorBytes = comment.vendor.toByteArray(Charsets.UTF_8)
            writeLe(vendorBytes.size)
            payload.write(vendorBytes)
            writeLe(comment.fields.size)
            for (field in comment.fields) {
                val encoded = field.toByteArray(Charsets.UTF_8)
                writeLe(encoded.size)
                payload.write(encoded)
            }
            blocks += 4 to payload.toByteArray()
        }
        blocks += 1 to ByteArray(16) // PADDING — the rewrite spends it.
        blocks.forEachIndexed { index, (type, payload) ->
            out.write(if (index == blocks.lastIndex) type or 0x80 else type)
            out.write((payload.size ushr 16) and 0xFF)
            out.write((payload.size ushr 8) and 0xFF)
            out.write(payload.size and 0xFF)
            out.write(payload)
        }
        out.write(frames)
        return out.toByteArray()
    }

    private fun fakeFlacFrames(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x69, 0x18) + ByteArray(96) { (it % 7).toByte() }

    private fun parseFlacComment(bytes: ByteArray): VorbisFixture {
        var offset = 4
        while (true) {
            val header = bytes[offset].toInt() and 0xFF
            val length = ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
            val start = offset + 4
            if (header and 0x7F == 4) {
                var pos = start
                fun readLe(): Int {
                    val value = (bytes[pos].toInt() and 0xFF) or
                        ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                        ((bytes[pos + 3].toInt() and 0xFF) shl 24)
                    pos += 4
                    return value
                }
                val vendorLength = readLe()
                val vendor = String(bytes, pos, vendorLength, Charsets.UTF_8)
                pos += vendorLength
                val count = readLe()
                val fields = mutableListOf<String>()
                repeat(count) {
                    val fieldLength = readLe()
                    fields += String(bytes, pos, fieldLength, Charsets.UTF_8)
                    pos += fieldLength
                }
                return VorbisFixture(vendor, fields)
            }
            offset = start + length
            if (header and 0x80 != 0) break
        }
        throw AssertionError("no Vorbis comment in the rewritten FLAC")
    }

    private class Mp4Bare(val bytes: ByteArray, val mdatDataOffset: Int)

    /** ftyp + moov (with a real one-entry stco) + mdat: the append-a-chain case, with offsets to verify. */
    private fun mp4Bare(): Mp4Bare {
        val ftyp = mp4Box("ftyp", "M4A ".toByteArray(Charsets.ISO_8859_1) + ByteArray(12))
        val mdatPayloadSize = 64
        // Sizes first: stco lives inside moov, its entry is the mdat payload's
        // absolute offset — moov's size is known before mdat is placed.
        val stbl = { entry: Long ->
            mp4Box(
                "stbl",
                mp4Box("stco", u32(0) + u32(1) + u32(entry)) +
                    mp4Box("stsz", ByteArray(12)) +
                    mp4Box("stts", ByteArray(8)),
            )
        }
        val trak = { entry: Long -> mp4Box("trak", mp4Box("mdia", mp4Box("minf", stbl(entry)))) }
        val moovSize = 8 + trak(0).size
        val mdatDataOffset = ftyp.size + moovSize + 8
        val moov = mp4Box("moov", trak(mdatDataOffset.toLong()))
        val mdat = mp4Box("mdat", ByteArray(mdatPayloadSize) { (it % 5).toByte() })
        return Mp4Bare(ftyp + moov + mdat, mdatDataOffset)
    }

    /** ftyp + mdat + moov/udta/meta/ilst with a ©nam item: the edit-in-place case. mdat first, so no offsets move. */
    private fun mp4WithMeta(): ByteArray {
        val ftyp = mp4Box("ftyp", "M4A ".toByteArray(Charsets.ISO_8859_1) + ByteArray(12))
        val mdat = mp4Box("mdat", ByteArray(32) { (it % 3).toByte() })
        val nameData = mp4Box("data", u32(1) + ByteArray(4) + NAME_ATOM_TEXT)
        val ilst = mp4Box("ilst", mp4Box("©nam", nameData))
        val hdlrBody = ByteArray(25)
        "mdir".toByteArray(Charsets.ISO_8859_1).copyInto(hdlrBody, 8)
        val meta = mp4Box("meta", ByteArray(4) + mp4Box("hdlr", hdlrBody) + ilst)
        val moov = mp4Box("moov", mp4Box("udta", meta))
        return ftyp + mdat + moov
    }

    private fun readStcoEntry(bytes: ByteArray): Long {
        val index = bytes.indexOf("stco".toByteArray(Charsets.ISO_8859_1))
        require(index >= 0) { "no stco in fixture" }
        // stco content: version/flags(4) + count(4) + entry(4).
        return readU32(bytes, index + 4 + 8)
    }

    // ---------------------------------------------------------------- helpers

    private fun mp4Box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        writeU32(out, 0, (8 + payload.size).toLong())
        type.toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    private fun u32(value: Long): ByteArray {
        val out = ByteArray(4)
        writeU32(out, 0, value)
        return out
    }

    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun writeU32(b: ByteArray, off: Int, value: Long) {
        b[off] = ((value shr 24) and 0xFF).toByte()
        b[off + 1] = ((value shr 16) and 0xFF).toByte()
        b[off + 2] = ((value shr 8) and 0xFF).toByte()
        b[off + 3] = (value and 0xFF).toByte()
    }

    private fun ByteArrayOutputStream.writeU32(value: Long) {
        write(((value shr 24) and 0xFF).toInt())
        write(((value shr 16) and 0xFF).toInt())
        write(((value shr 8) and 0xFF).toInt())
        write((value and 0xFF).toInt())
    }

    private fun ByteArrayOutputStream.writeSyncsafe(value: Int) {
        write((value ushr 21) and 0x7F)
        write((value ushr 14) and 0x7F)
        write((value ushr 7) and 0x7F)
        write(value and 0x7F)
    }

    private fun ByteArray.endsWith(suffix: ByteArray): Boolean =
        size >= suffix.size && copyOfRange(size - suffix.size, size).contentEquals(suffix)

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean = indexOf(needle) >= 0

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        if (needle.isEmpty() || size < needle.size) return -1
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun ByteArray.countOccurrences(text: String): Int {
        val needle = text.toByteArray(Charsets.ISO_8859_1)
        var count = 0
        var from = 0
        while (true) {
            val index = copyOfRange(from, size).indexOf(needle)
            if (index < 0) return count
            count++
            from += index + needle.size
        }
    }

    /** True when [bytes] hold an ID3 frame [id] whose decoded text payload contains [text]. */
    private fun ByteArray.containsFrameText(id: String, text: String): Boolean {
        val idBytes = id.toByteArray(Charsets.ISO_8859_1)
        val at = indexOf(idBytes)
        if (at < 0) return false
        val major = this[3].toInt() and 0xFF
        val size = if (major == 4) {
            var value = 0
            for (i in 0 until 4) value = (value shl 7) or (this[at + 4 + i].toInt() and 0x7F)
            value
        } else {
            readU32(this, at + 4).toInt()
        }
        if (size <= 1 || at + 10 + size > this.size) return false
        val content = copyOfRange(at + 10, at + 10 + size)
        val decoded = when (content[0].toInt() and 0xFF) {
            3 -> String(content, 1, content.size - 1, Charsets.UTF_8)
            0 -> String(content, 1, content.size - 1, Charsets.ISO_8859_1)
            else -> return false
        }
        return decoded.contains(text)
    }

    private companion object {
        val NAME_ATOM_TEXT = "Fixture Song".toByteArray(Charsets.UTF_8)
    }
}
