package com.music.bitchord.data.bpm

import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.roundToInt

/** The container families the BPM tag writer knows. */
enum class BpmFormat { MP3, FLAC, MP4, UNSUPPORTED }

/**
 * Reads and writes a file's own BPM tag, byte by byte:
 *
 *  - **MP3** — the ID3v2 `TBPM` frame (v2.3 and v2.4; a file with no ID3v2
 *    tag gets a fresh v2.4 one holding just `TBPM`).
 *  - **FLAC** — the `BPM` field of the Vorbis comment block.
 *  - **M4A/MP4** — the `tmpo` atom, which the format defines as an integer;
 *    the one-decimal value is rounded for this format alone (see
 *    [BpmTagStore] — the read-back after a write reports the rounded value,
 *    so nothing pretends otherwise).
 *  - **Ogg Vorbis/Opus, WAV, AIFF, everything else** — [BpmFormat.UNSUPPORTED]
 *    for writing. Analysis still works; there is simply no writer.
 *
 * Why hand-rolled, when jaudiotagger exists: it was tried first, per the
 * feature brief. Its POM resolves through the proxy without trouble
 * (net.jthink:jaudiotagger:3.0.1), but the jar's own class files reference
 * `javax.imageio` — a package Android does not have — which is the exact
 * failure [com.music.bitchord.download.Mp4Tagger]'s header records other
 * music apps hitting with the unmodified desktop library. This codebase
 * already hand-rolls three taggers for that reason; a fourth, narrow one
 * keeps every guarantee unit-testable on the JVM, which jaudiotagger's
 * Android behaviour here is not.
 *
 * Preservation rule, all three writers: everything already in the tag that
 * isn't the BPM field itself is carried across byte-for-byte — other
 * frames, other comment fields, other `ilst` items, the vendor string.
 * Only the BPM entry is replaced (or added). A file whose layout isn't
 * recognised comes back as a refusal (`null`), never as a guess: a track
 * left untagged is a smaller loss than a track corrupted.
 *
 * Pure byte work throughout — no Android types — so round-trips are
 * unit-tested on generated fixtures.
 */
object BpmTags {

    // ---------------------------------------------------------------- format

    /** Which container [head] (the file's first bytes) and [fileName] describe. Magic wins over the name. */
    fun formatOf(head: ByteArray, fileName: String? = null): BpmFormat {
        if (head.startsWith(FLAC_MAGIC)) return BpmFormat.FLAC
        if (head.startsWith(ID3_MAGIC)) return BpmFormat.MP3
        if (head.size >= 8 && head.regionMatches(4, FTYP)) return BpmFormat.MP4
        if (head.startsWith(OGG_MAGIC)) return BpmFormat.UNSUPPORTED
        if (head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0) {
            return BpmFormat.MP3 // Bare MPEG audio: no tag yet, but a writable shape.
        }
        return when (fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)) {
            "mp3" -> BpmFormat.MP3
            "flac" -> BpmFormat.FLAC
            "m4a", "mp4", "m4b", "m4p" -> BpmFormat.MP4
            else -> BpmFormat.UNSUPPORTED
        }
    }

    /** The text form a BPM is stored as: "128" when whole, "128.5" when not. */
    fun formatBpmText(bpm: Double): String {
        val rounded = (bpm * 10).roundToInt() / 10.0
        return if (rounded == rounded.roundToInt().toDouble()) {
            rounded.roundToInt().toString()
        } else {
            String.format(Locale.ROOT, "%.1f", rounded)
        }
    }

    /** The first number in a tag text, or null — taggers write all manner of things around the digits. */
    internal fun parseBpmText(text: String): Double? {
        val match = NUMBER_REGEX.find(text) ?: return null
        return match.value.replace(',', '.').toDoubleOrNull()
            ?.takeIf { it in 1.0..999.0 }
    }

    // ------------------------------------------------------------------- read

    /** The BPM already in these bytes' tag, or null when there is none (or none that parses). */
    fun readBpm(bytes: ByteArray, fileName: String? = null): Double? = when (formatOf(bytes, fileName)) {
        BpmFormat.MP3 -> readId3Bpm(bytes)
        BpmFormat.FLAC -> readFlacBpm(bytes)
        BpmFormat.MP4 -> readMp4Bpm(bytes)
        BpmFormat.UNSUPPORTED -> null
    }

    // ------------------------------------------------------------------ write

    /**
     * A copy of [bytes] with [bpm] in its tag, or null when the format is
     * unsupported or the layout unrecognised. FLAC goes through the
     * header-level path so callers with big files can stream the frames;
     * see [flacRewrite].
     */
    fun withBpm(bytes: ByteArray, bpm: Double, fileName: String? = null): ByteArray? =
        when (formatOf(bytes, fileName)) {
            BpmFormat.MP3 -> withId3Bpm(bytes, bpm)
            BpmFormat.FLAC -> flacRewrite(bytes, bpm)?.let { rewrite ->
                rewrite.metadata + bytes.copyOfRange(rewrite.audioStart, bytes.size)
            }
            BpmFormat.MP4 -> withMp4Bpm(bytes, bpm)
            BpmFormat.UNSUPPORTED -> null
        }

    // ------------------------------------------------------------- ID3v2/MP3

    private fun readId3Bpm(bytes: ByteArray): Double? {
        val tag = parseId3(bytes) ?: return null
        val frame = tag.frames.firstOrNull { it.id == "TBPM" } ?: return null
        val content = frame.content(bytes)
        if (content.isEmpty()) return null
        val text = decodeId3Text(content) ?: return null
        return parseBpmText(text)
    }

    private fun withId3Bpm(bytes: ByteArray, bpm: Double): ByteArray? {
        val text = formatBpmText(bpm)
        val existing = parseId3(bytes)
        if (existing == null) {
            // No ID3v2 tag. Only a file that plainly starts with MPEG audio
            // gets a fresh tag prepended — anything else is a shape this
            // doesn't know, and guessing at it is how files get corrupted.
            val startsWithAudio = bytes.size >= 2 &&
                bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0
            if (!startsWithAudio) return null
            val tag = buildId3Tag(major = 4, frames = listOf(id3Frame(4, "TBPM", id3TextContent(4, text))), padding = 512)
            return tag + bytes
        }
        val kept = existing.frames.filter { it.id != "TBPM" }.map { it.raw(bytes) }
        val frames = kept + id3Frame(existing.major, "TBPM", id3TextContent(existing.major, text))
        val bodySize = frames.sumOf { it.size }
        // Reuse the old tag's room when the new frames fit — the audio then
        // doesn't move at all. Otherwise take what is needed plus padding.
        val totalBody = if (bodySize <= existing.bodySize) existing.bodySize else bodySize + 512
        val tag = buildId3Tag(existing.major, frames, padding = totalBody - bodySize)
        return tag + bytes.copyOfRange(existing.audioStart, bytes.size)
    }

    private class Id3Tag(
        val major: Int,
        val bodySize: Int,
        /** Offset where the audio begins: header + body (+ a v2.4 footer when present). */
        val audioStart: Int,
        val frames: List<Id3Frame>,
    )

    private class Id3Frame(val id: String, val start: Int, val size: Int, val contentStart: Int, val contentSize: Int) {
        fun raw(bytes: ByteArray): ByteArray = bytes.copyOfRange(start, start + size)
        fun content(bytes: ByteArray): ByteArray = bytes.copyOfRange(contentStart, contentStart + contentSize)
    }

    private fun parseId3(bytes: ByteArray): Id3Tag? = runCatching {
        if (bytes.size < 10 || !bytes.startsWith(ID3_MAGIC)) return null
        val major = bytes[3].toInt() and 0xFF
        if (major != 3 && major != 4) return null
        val flags = bytes[5].toInt() and 0xFF
        // Unsynchronisation rewrites bytes inside frames; rather than
        // implement it for the vanishingly rare tagged-with-unsync file,
        // refuse — the caller reports the file as unwritable, honestly.
        if (flags and 0x80 != 0) return null
        val bodySize = syncsafe(bytes, 6) ?: return null
        val bodyEnd = 10 + bodySize
        if (bodyEnd > bytes.size) return null
        var offset = 10
        // Extended header, when flagged: skipped, not preserved (its content
        // describes the tag being replaced) — frames are what carry across.
        if (flags and 0x40 != 0) {
            offset = if (major == 3) {
                if (offset + 4 > bodyEnd) return null
                offset + 4 + readU32(bytes, offset).toInt()
            } else {
                val size = syncsafe(bytes, offset) ?: return null
                offset + size
            }
            if (offset > bodyEnd) return null
        }
        val frames = mutableListOf<Id3Frame>()
        while (offset + 10 <= bodyEnd) {
            val id = String(bytes, offset, 4, Charsets.ISO_8859_1)
            if (id.all { it == '\u0000' }) break // Padding.
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val size = if (major == 4) {
                syncsafe(bytes, offset + 4) ?: return null
            } else {
                val value = readU32(bytes, offset + 4)
                if (value > Int.MAX_VALUE) return null
                value.toInt()
            }
            if (size < 0 || offset + 10 + size > bodyEnd) return null
            frames += Id3Frame(id, offset, 10 + size, offset + 10, size)
            offset += 10 + size
        }
        val footer = if (major == 4 && flags and 0x10 != 0) 10 else 0
        Id3Tag(major, bodySize, bodyEnd + footer, frames)
    }.getOrNull()

    private fun buildId3Tag(major: Int, frames: List<ByteArray>, padding: Int): ByteArray {
        val bodySize = frames.sumOf { it.size } + padding
        val out = ByteArrayOutputStream(10 + bodySize)
        out.write(ID3_MAGIC)
        out.write(major)
        out.write(0) // Revision.
        out.write(0) // Flags: no unsynchronisation, no extended header, no footer.
        out.writeSyncsafe(bodySize)
        frames.forEach { out.write(it) }
        repeat(padding) { out.write(0) }
        return out.toByteArray()
    }

    private fun id3Frame(major: Int, id: String, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(10 + content.size)
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        if (major == 4) out.writeSyncsafe(content.size) else out.writeU32(content.size)
        out.write(0)
        out.write(0) // Frame flags.
        out.write(content)
        return out.toByteArray()
    }

    /** A text frame's content: encoding byte + text. Digits are safe in every encoding; v2.4 takes UTF-8 on principle. */
    private fun id3TextContent(major: Int, text: String): ByteArray =
        if (major == 4) {
            byteArrayOf(3) + text.toByteArray(Charsets.UTF_8)
        } else {
            byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
        }

    private fun decodeId3Text(content: ByteArray): String? = runCatching {
        val body = content.copyOfRange(1, content.size)
        when (content[0].toInt() and 0xFF) {
            0 -> String(body, Charsets.ISO_8859_1)
            1 -> String(body, Charsets.UTF_16) // BOM-aware.
            2 -> String(body, Charsets.UTF_16BE)
            3 -> String(body, Charsets.UTF_8)
            else -> null
        }?.trim('\u0000', ' ', '\t', '\n', '\r')
    }.getOrNull()

    // ------------------------------------------------------------------- FLAC

    /** A rebuilt FLAC metadata region, and where the frames begin in the original bytes. */
    class FlacRewrite(val metadata: ByteArray, val audioStart: Int)

    private class FlacBlock(val type: Int, val start: Int, val length: Int) {
        val end get() = start + length
    }

    private class FlacLayout(val blocks: List<FlacBlock>, val audioStart: Int)

    private fun parseFlac(head: ByteArray): FlacLayout? = runCatching {
        if (!head.startsWith(FLAC_MAGIC)) return null
        val blocks = mutableListOf<FlacBlock>()
        var offset = FLAC_MAGIC.size
        while (true) {
            if (offset + 4 > head.size) return null
            val flags = head[offset].toInt() and 0xFF
            val length = ((head[offset + 1].toInt() and 0xFF) shl 16) or
                ((head[offset + 2].toInt() and 0xFF) shl 8) or
                (head[offset + 3].toInt() and 0xFF)
            val start = offset + 4
            if (start + length > head.size) return null
            blocks += FlacBlock(flags and 0x7F, start, length)
            offset = start + length
            if (flags and 0x80 != 0) break
        }
        if (blocks.firstOrNull()?.type != FLAC_STREAMINFO) return null
        FlacLayout(blocks, offset)
    }.getOrNull()

    /** The parsed Vorbis comment: its vendor string and its raw "KEY=value" fields, in order. */
    private class VorbisComment(val vendor: String, val fields: List<String>)

    private fun parseVorbisComment(head: ByteArray, block: FlacBlock): VorbisComment? = runCatching {
        var pos = block.start
        val end = block.end
        fun readLe(): Int {
            if (pos + 4 > end) throw IllegalArgumentException("short comment")
            val value = (head[pos].toInt() and 0xFF) or
                ((head[pos + 1].toInt() and 0xFF) shl 8) or
                ((head[pos + 2].toInt() and 0xFF) shl 16) or
                ((head[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return value
        }
        val vendorLength = readLe()
        if (vendorLength < 0 || pos + vendorLength > end) return null
        val vendor = String(head, pos, vendorLength, Charsets.UTF_8)
        pos += vendorLength
        val count = readLe()
        if (count < 0 || count > 100_000) return null
        val fields = ArrayList<String>(count)
        repeat(count) {
            val length = readLe()
            if (length < 0 || pos + length > end) return null
            fields += String(head, pos, length, Charsets.UTF_8)
            pos += length
        }
        VorbisComment(vendor, fields)
    }.getOrNull()

    private fun vorbisCommentPayload(vendor: String, fields: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeLe(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        writeLe(vendorBytes.size)
        out.write(vendorBytes)
        writeLe(fields.size)
        for (field in fields) {
            val encoded = field.toByteArray(Charsets.UTF_8)
            writeLe(encoded.size)
            out.write(encoded)
        }
        return out.toByteArray()
    }

    private fun commentKey(field: String): String =
        field.substringBefore('=', "").uppercase(Locale.ROOT)

    fun readFlacBpm(head: ByteArray): Double? {
        val layout = parseFlac(head) ?: return null
        val block = layout.blocks.firstOrNull { it.type == FLAC_VORBIS_COMMENT } ?: return null
        val comment = parseVorbisComment(head, block) ?: return null
        val field = comment.fields.firstOrNull { commentKey(it) == "BPM" } ?: return null
        return parseBpmText(field.substringAfter('=', ""))
    }

    /**
     * The metadata region for this FLAC with [bpm] in its comment, or null
     * when the head doesn't hold the whole block chain or the shape isn't
     * recognised. Mirrors [com.music.bitchord.download.FlacTagger.header]'s
     * contract so the caller can stream the frames through.
     */
    fun flacRewrite(head: ByteArray, bpm: Double): FlacRewrite? {
        val layout = parseFlac(head) ?: return null
        val commentBlock = layout.blocks.firstOrNull { it.type == FLAC_VORBIS_COMMENT }
        val existing = commentBlock?.let { parseVorbisComment(head, it) }
        if (commentBlock != null && existing == null) return null // A comment we can't parse is not ours to replace.
        val vendor = existing?.vendor ?: "BitChord"
        val fields = (existing?.fields ?: emptyList()).filter { commentKey(it) != "BPM" } +
            "BPM=${formatBpmText(bpm)}"
        val payload = vorbisCommentPayload(vendor, fields)
        if (payload.size > FLAC_MAX_BLOCK) return null

        // The chain, in order: every block the file had except PADDING (it
        // exists to be spent) and the old comment, with the new comment
        // where the old one stood — or after STREAMINFO when the file had
        // no comment at all. The insert-after-STREAMINFO must only fire in
        // that second case; firing it in both duplicated the comment block.
        val hasComment = commentBlock != null
        val chain = mutableListOf<Pair<Int, ByteArray>>()
        var inserted = false
        for (block in layout.blocks) {
            when (block.type) {
                FLAC_PADDING -> Unit
                FLAC_VORBIS_COMMENT -> {
                    chain += FLAC_VORBIS_COMMENT to payload
                    inserted = true
                }
                else -> {
                    chain += block.type to head.copyOfRange(block.start, block.end)
                    if (!inserted && !hasComment && block.type == FLAC_STREAMINFO) {
                        chain += FLAC_VORBIS_COMMENT to payload
                        inserted = true
                    }
                }
            }
        }
        if (!inserted) return null
        val out = ByteArrayOutputStream()
        out.write(FLAC_MAGIC)
        chain.forEachIndexed { index, (type, blockPayload) ->
            out.write(if (index == chain.lastIndex) type or 0x80 else type)
            out.write((blockPayload.size ushr 16) and 0xFF)
            out.write((blockPayload.size ushr 8) and 0xFF)
            out.write(blockPayload.size and 0xFF)
            out.write(blockPayload)
        }
        return FlacRewrite(out.toByteArray(), layout.audioStart)
    }

    // -------------------------------------------------------------------- MP4

    private class Mp4Box(
        val offset: Int,
        val headerLen: Int,
        val size: Int,
        val rawSize32: Long,
        val type: String,
    ) {
        val contentOffset get() = offset + headerLen
        val end get() = offset + size
    }

    private fun parseMp4Boxes(bytes: ByteArray, start: Int, end: Int): List<Mp4Box> {
        val out = mutableListOf<Mp4Box>()
        var pos = start
        while (pos + 8 <= end) {
            val size32 = readU32(bytes, pos)
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            var headerLen = 8
            var size = size32
            if (size32 == 1L) {
                if (pos + 16 > end) break
                size = readU64(bytes, pos + 8)
                headerLen = 16
            } else if (size32 == 0L) {
                size = (end - pos).toLong()
            }
            if (size < headerLen || pos + size > end || size > Int.MAX_VALUE) break
            out += Mp4Box(pos, headerLen, size.toInt(), size32, type)
            pos += size.toInt()
        }
        return out
    }

    /** moov's `meta` box, whether it sits under `udta` or directly under `moov`. */
    private fun findMeta(bytes: ByteArray, moov: Mp4Box): Mp4Box? {
        val children = parseMp4Boxes(bytes, moov.contentOffset, moov.end)
        val udta = children.firstOrNull { it.type == "udta" }
        if (udta != null) {
            parseMp4Boxes(bytes, udta.contentOffset, udta.end)
                .firstOrNull { it.type == "meta" }?.let { return it }
        }
        return children.firstOrNull { it.type == "meta" }
    }

    /** Children of a `meta` box start after its four version/flag bytes. */
    private fun metaChildren(bytes: ByteArray, meta: Mp4Box): List<Mp4Box> =
        parseMp4Boxes(bytes, meta.contentOffset + 4, meta.end)

    fun readMp4Bpm(bytes: ByteArray): Double? = runCatching {
        val moov = parseMp4Boxes(bytes, 0, bytes.size).firstOrNull { it.type == "moov" } ?: return null
        val meta = findMeta(bytes, moov) ?: return null
        val ilst = metaChildren(bytes, meta).firstOrNull { it.type == "ilst" } ?: return null
        val tmpo = parseMp4Boxes(bytes, ilst.contentOffset, ilst.end)
            .firstOrNull { it.type == "tmpo" } ?: return null
        val data = parseMp4Boxes(bytes, tmpo.contentOffset, tmpo.end)
            .firstOrNull { it.type == "data" } ?: return null
        // data content: 4 bytes of version/type + 4 of locale, then the int.
        val payloadStart = data.contentOffset + 8
        val payloadSize = data.end - payloadStart
        if (payloadSize <= 0) return null
        var value = 0L
        for (i in 0 until minOf(payloadSize, 8)) {
            value = (value shl 8) or (bytes[payloadStart + i].toLong() and 0xFF)
        }
        value.toDouble().takeIf { it in 1.0..999.0 }
    }.getOrNull()

    private fun withMp4Bpm(bytes: ByteArray, bpm: Double): ByteArray? = runCatching {
        val moov = parseMp4Boxes(bytes, 0, bytes.size).firstOrNull { it.type == "moov" } ?: return null
        val tmpoItem = mp4TmpoItem(bpm)
        // Locate meta and remember whether it sits inside udta: ancestors
        // that wrap the edit must grow, and "is meta inside udta" cannot be
        // re-derived after the splice by comparing offsets — meta is often
        // udta's FIRST child, starting exactly at udta's content offset.
        val moovChildren = parseMp4Boxes(bytes, moov.contentOffset, moov.end)
        val udta = moovChildren.firstOrNull { it.type == "udta" }
        val nestedMeta = udta?.let { u ->
            parseMp4Boxes(bytes, u.contentOffset, u.end).firstOrNull { it.type == "meta" }
        }
        val meta = nestedMeta ?: moovChildren.firstOrNull { it.type == "meta" }
        val ilst = meta?.let { m -> metaChildren(bytes, m).firstOrNull { it.type == "ilst" } }
        if (meta == null || ilst == null) {
            // No metadata chain to edit: append a whole one as moov's last
            // child — the insertion Mp4Tagger proved, offsets patched.
            val udta = mp4Box("udta", mp4MetaAtom(mp4IlstAtom(listOf(tmpoItem))))
            return insertIntoMoov(bytes, moov, udta)
        }
        // Rebuild ilst with every existing item except tmpo, plus the new
        // tmpo — then grow the four ancestors by the same delta and shift
        // every chunk offset that pointed past the old ilst.
        val kept = parseMp4Boxes(bytes, ilst.contentOffset, ilst.end)
            .filter { it.type != "tmpo" }
            .map { bytes.copyOfRange(it.offset, it.end) }
        val newIlst = mp4IlstAtom(kept + tmpoItem)
        val delta = newIlst.size - ilst.size
        val working = bytes.copyOf()
        patchChunkOffsets(working, moov, threshold = ilst.end.toLong(), delta = delta)
        val out = working.copyOfRange(0, ilst.offset) + newIlst + working.copyOfRange(ilst.end, working.size)
        growBoxSize(out, ilst.offset, newIlst.size.toLong(), ilst.headerLen)
        // meta and (when meta is nested in it) udta wrap ilst; their headers
        // precede it, so their offsets are the same in the spliced array and
        // the original parses still say where they live.
        if (nestedMeta != null && udta != null) {
            growBoxSize(out, udta.offset, udta.size + delta.toLong(), udta.headerLen, udta.rawSize32)
        }
        growBoxSize(out, meta.offset, meta.size + delta.toLong(), meta.headerLen, meta.rawSize32)
        growBoxSize(out, moov.offset, moov.size + delta.toLong(), moov.headerLen, moov.rawSize32)
        out
    }.getOrNull()

    /** Appends [atom] as [moov]'s last child, shifting chunk offsets past the insertion point. */
    private fun insertIntoMoov(bytes: ByteArray, moov: Mp4Box, atom: ByteArray): ByteArray {
        val insertAt = moov.end
        val delta = atom.size
        val working = bytes.copyOf()
        patchChunkOffsets(working, moov, threshold = insertAt.toLong(), delta = delta)
        val prefix = working.copyOf(insertAt)
        if (moov.rawSize32 != 0L) {
            growBoxSize(prefix, moov.offset, moov.size + delta.toLong(), moov.headerLen, moov.rawSize32)
        }
        return prefix + atom + working.copyOfRange(insertAt, working.size)
    }

    /** Adds [delta] to every `stco`/`co64` entry at or past [threshold], anywhere under [moov]. */
    private fun patchChunkOffsets(bytes: ByteArray, moov: Mp4Box, threshold: Long, delta: Int) {
        if (delta == 0) return
        fun walk(box: Mp4Box) {
            when (box.type) {
                "stco" -> {
                    val base = box.contentOffset + 4
                    val count = readU32(bytes, base).toInt()
                    var p = base + 4
                    repeat(count) {
                        val off = readU32(bytes, p)
                        if (off >= threshold) writeU32(bytes, p, off + delta)
                        p += 4
                    }
                }
                "co64" -> {
                    val base = box.contentOffset + 4
                    val count = readU32(bytes, base).toInt()
                    var p = base + 4
                    repeat(count) {
                        val off = readU64(bytes, p)
                        if (off >= threshold) writeU64(bytes, p, off + delta)
                        p += 8
                    }
                }
                "moov", "trak", "mdia", "minf", "stbl" ->
                    parseMp4Boxes(bytes, box.contentOffset, box.end).forEach(::walk)
            }
        }
        walk(moov)
    }

    /** Rewrites a box's size field in place; a raw size of 0 ("to end of parent") is left to keep meaning it. */
    private fun growBoxSize(bytes: ByteArray, offset: Int, newSize: Long, headerLen: Int, rawSize32: Long = 1L) {
        if (rawSize32 == 0L) return
        if (headerLen == 16) writeU64(bytes, offset + 8, newSize) else writeU32(bytes, offset, newSize)
    }

    private fun mp4Box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        writeU32(out, 0, (8 + payload.size).toLong())
        type.toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    /** iTunes `data` atom body: version/type word + locale word, then the value. 21 = unsigned integer. */
    private fun mp4TmpoItem(bpm: Double): ByteArray {
        val value = bpm.roundToInt().coerceIn(1, 999)
        val body = ByteArray(8 + 2)
        writeU32(body, 0, 21)
        body[8] = ((value ushr 8) and 0xFF).toByte()
        body[9] = (value and 0xFF).toByte()
        return mp4Box("tmpo", mp4Box("data", body))
    }

    private fun mp4IlstAtom(items: List<ByteArray>): ByteArray {
        val payload = ByteArrayOutputStream()
        items.forEach { payload.write(it) }
        return mp4Box("ilst", payload.toByteArray())
    }

    private fun mp4MetaAtom(ilst: ByteArray): ByteArray {
        // version/flags(4) + hdlr + ilst; the handler declares iTunes-style
        // metadata, as Mp4Tagger writes it.
        val hdlrBody = ByteArray(25)
        "mdir".toByteArray(Charsets.ISO_8859_1).copyInto(hdlrBody, 8)
        val hdlr = mp4Box("hdlr", hdlrBody)
        val payload = ByteArray(4 + hdlr.size + ilst.size)
        hdlr.copyInto(payload, 4)
        ilst.copyInto(payload, 4 + hdlr.size)
        return mp4Box("meta", payload)
    }

    // ----------------------------------------------------------------- helpers

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && regionMatches(0, prefix)

    private fun ByteArray.regionMatches(offset: Int, other: ByteArray): Boolean {
        if (offset < 0 || offset + other.size > size) return false
        for (i in other.indices) if (this[offset + i] != other[i]) return false
        return true
    }

    private fun syncsafe(bytes: ByteArray, offset: Int): Int? {
        if (offset + 4 > bytes.size) return null
        var value = 0
        for (i in 0 until 4) {
            val b = bytes[offset + i].toInt() and 0xFF
            if (b and 0x80 != 0) return null
            value = (value shl 7) or b
        }
        return value
    }

    private fun ByteArrayOutputStream.writeSyncsafe(value: Int) {
        write((value ushr 21) and 0x7F)
        write((value ushr 14) and 0x7F)
        write((value ushr 7) and 0x7F)
        write(value and 0x7F)
    }

    private fun ByteArrayOutputStream.writeU32(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun readU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun writeU32(b: ByteArray, off: Int, value: Long) {
        b[off] = ((value shr 24) and 0xFF).toByte()
        b[off + 1] = ((value shr 16) and 0xFF).toByte()
        b[off + 2] = ((value shr 8) and 0xFF).toByte()
        b[off + 3] = (value and 0xFF).toByte()
    }

    private fun writeU64(b: ByteArray, off: Int, value: Long) {
        for (i in 0 until 8) b[off + i] = ((value shr (8 * (7 - i))) and 0xFF).toByte()
    }

    private val ID3_MAGIC = "ID3".toByteArray(Charsets.US_ASCII)
    private val FLAC_MAGIC = "fLaC".toByteArray(Charsets.US_ASCII)
    private val OGG_MAGIC = "OggS".toByteArray(Charsets.US_ASCII)
    private val FTYP = "ftyp".toByteArray(Charsets.US_ASCII)

    private const val FLAC_STREAMINFO = 0
    private const val FLAC_PADDING = 1
    private const val FLAC_VORBIS_COMMENT = 4
    private const val FLAC_MAX_BLOCK = (1 shl 24) - 1

    private val NUMBER_REGEX = Regex("""\d+(?:[.,]\d+)?""")
}
