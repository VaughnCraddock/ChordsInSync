package com.music.bitchord.sync

import com.music.bitchord.data.sync.drive.DriveErrors
import com.music.bitchord.data.sync.drive.DriveFailure
import com.music.bitchord.data.sync.drive.DriveFile
import com.music.bitchord.data.sync.drive.DriveJson
import com.music.bitchord.data.sync.drive.DriveSetup
import com.music.bitchord.data.sync.drive.DriveTime
import com.music.bitchord.data.sync.drive.DriveTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Drive provider's brains are pure on purpose: file JSON to entries,
 * stamps, and the failure taxonomy a run reports. These tests pin that
 * mapping against the real [DriveFile] shape, including the two exclusions
 * that matter — trashed items never come back, and Google-native documents
 * never enter the planner (they are not byte streams; Drive would hand
 * back an export, not the file). Folders are structure, not entries: the
 * planner diffs files, and [DriveTree.resolve] is where folder shape is
 * pinned.
 */
class DriveLogicTest {

    private fun file(
        id: String,
        name: String,
        parent: String,
        folder: Boolean = false,
        size: Long = 0,
        md5: String? = null,
        trashed: Boolean = false,
        mime: String = if (folder) "application/vnd.google-apps.folder" else "audio/mpeg",
        mtime: Long = 1_000_000,
    ) = DriveFile(
        id = id,
        name = name,
        mimeType = mime,
        size = size,
        modifiedTimeMs = mtime,
        md5Checksum = md5,
        parents = listOf(parent),
        trashed = trashed,
    )

    @Test
    fun `file json maps size string md5 and parents`() {
        val parsed = DriveJson.parseFile(
            """
            {
              "id": "abc123",
              "name": "Song.mp3",
              "mimeType": "audio/mpeg",
              "size": "1234567",
              "modifiedTime": "2026-10-30T12:34:56.789Z",
              "md5Checksum": "d41d8cd98f00b204e9800998ecf8427e",
              "parents": ["root1"],
              "trashed": false
            }
            """.trimIndent(),
        )!!
        assertEquals("abc123", parsed.id)
        assertEquals("Song.mp3", parsed.name)
        assertFalse(parsed.isFolder)
        assertEquals(1_234_567L, parsed.size)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", parsed.md5Checksum)
        assertEquals(listOf("root1"), parsed.parents)
        assertEquals(DriveTime.parseMs("2026-10-30T12:34:56.789Z"), parsed.modifiedTimeMs)
    }

    @Test
    fun `folder json has no size and no md5`() {
        val parsed = DriveJson.parseFile(
            """{"id":"f1","name":"Sub","mimeType":"application/vnd.google-apps.folder","parents":["r"]}""",
        )!!
        assertTrue(parsed.isFolder)
        assertEquals(0L, parsed.size)
        assertNull(parsed.md5Checksum)
    }

    @Test
    fun `list page keeps the next page token and skips unparseable entries`() {
        val (files, token) = DriveJson.parseListPage(
            """
            {
              "files": [
                {"id": "a", "name": "A.mp3", "mimeType": "audio/mpeg"},
                {"name": "broken"},
                {"id": "b", "name": "B.mp3", "mimeType": "audio/mpeg", "trashed": true}
              ],
              "nextPageToken": "tok-2"
            }
            """.trimIndent(),
        )
        assertEquals(listOf("a", "b"), files.map { it.id })
        assertTrue(files.last().trashed)
        assertEquals("tok-2", token)
    }

    @Test
    fun `error body reason comes out of the nested error object`() {
        assertEquals(
            "rateLimitExceeded",
            DriveJson.errorReason("""{"error": {"errors": [{"reason": "rateLimitExceeded"}]}}"""),
        )
        assertNull(DriveJson.errorReason("""{"error": {"message": "nope"}}"""))
        assertNull(DriveJson.errorReason("not json"))
    }

    @Test
    fun `resolve walks folders and entries carry files with md5 stamps`() {
        val files = listOf(
            file("song", "Song.mp3", "root", size = 500, md5 = "aa11"),
            file("sub", "Live", "root", folder = true),
            file("inner", "Inner.flac", "sub", size = 900, md5 = null),
        )
        // Folder shape lives in resolve: the folder itself plus both files.
        val resolved = DriveTree.resolve(files, "root")
        assertEquals(setOf("Song.mp3", "Live", "Live/Inner.flac"), resolved.map { it.path }.toSet())

        // Entries are files only; the stamp is size + mtime + md5 tag.
        val entries = DriveTree.entries(files, "root")
        val byPath = entries.associateBy { it.path }
        assertEquals(setOf("Song.mp3", "Live/Inner.flac"), byPath.keys)

        val song = byPath.getValue("Song.mp3")
        assertEquals(500L, song.stamp.size)
        assertEquals("aa11", song.stamp.tag)

        val inner = byPath.getValue("Live/Inner.flac")
        assertEquals(900L, inner.stamp.size)
        assertNull(inner.stamp.tag)
    }

    @Test
    fun `trashed files google docs and unreachable files never list`() {
        val files = listOf(
            file("gone", "Gone.mp3", "root", trashed = true),
            file("doc", "Notes", "root", mime = "application/vnd.google-apps.document"),
            file("sheet", "Sheet", "root", mime = "application/vnd.google-apps.spreadsheet"),
            file("elsewhere", "Other.mp3", "some-other-root"),
            file("kept", "Kept.mp3", "root"),
        )
        assertEquals(listOf("Kept.mp3"), DriveTree.entries(files, "root").map { it.path })
    }

    @Test
    fun `same name in different folders keeps both entries`() {
        val files = listOf(
            file("a", "Set", "root", folder = true),
            file("b", "Set", "root", folder = true),
            file("s1", "Intro.mp3", "a"),
            file("s2", "Intro.mp3", "b"),
        )
        // Both folders are named "Set", so both files resolve to the same
        // path — which is exactly why the provider keys its id map on the
        // resolved entry list, not on names alone.
        val paths = DriveTree.entries(files, "root").map { it.path }
        assertEquals(listOf("Set/Intro.mp3", "Set/Intro.mp3"), paths.sorted())
    }

    @Test
    fun `rfc3339 parses offsets and formats back to utc`() {
        val withMillis = DriveTime.parseMs("2026-10-30T12:34:56.789Z")!!
        assertEquals("2026-10-30T12:34:56.789Z", DriveTime.formatMs(withMillis))
        assertEquals(
            DriveTime.parseMs("2026-10-30T12:34:56Z"),
            DriveTime.parseMs("2026-10-30T14:34:56+02:00"),
        )
        assertNull(DriveTime.parseMs("not a date"))
    }

    @Test
    fun `failure classification separates sign-in retryable and fatal`() {
        assertEquals(DriveFailure.NEEDS_SIGN_IN, DriveErrors.classify(401, null))
        assertEquals(DriveFailure.RETRYABLE, DriveErrors.classify(429, "rateLimitExceeded"))
        assertEquals(DriveFailure.RETRYABLE, DriveErrors.classify(500, null))
        assertEquals(DriveFailure.RETRYABLE, DriveErrors.classify(503, null))
        assertEquals(DriveFailure.RETRYABLE, DriveErrors.classify(403, "userRateLimitExceeded"))
        assertEquals(DriveFailure.FATAL, DriveErrors.classify(403, "insufficientPermissions"))
        assertEquals(DriveFailure.FATAL, DriveErrors.classify(404, "notFound"))
        assertEquals(DriveFailure.FATAL, DriveErrors.classify(400, null))
    }

    @Test
    fun `backoff doubles caps and honours retry-after within its own cap`() {
        assertEquals(1_000L, DriveErrors.backoffMs(0))
        assertEquals(2_000L, DriveErrors.backoffMs(1))
        assertEquals(4_000L, DriveErrors.backoffMs(2))
        assertEquals(30_000L, DriveErrors.backoffMs(10))
        assertEquals(5_000L, DriveErrors.backoffMs(0, retryAfterSeconds = 5))
        assertEquals(60_000L, DriveErrors.backoffMs(0, retryAfterSeconds = 600))
    }

    @Test
    fun `sha1 display format is colon separated uppercase hex`() {
        assertEquals(
            "00:0A:FF:10",
            DriveSetup.formatSha1(byteArrayOf(0x00, 0x0A, 0xFF.toByte(), 0x10)),
        )
        assertEquals("", DriveSetup.formatSha1(ByteArray(0)))
    }
}
