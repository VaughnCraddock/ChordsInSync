package com.music.bitchord

import com.music.bitchord.data.sync.EffectiveDirection
import com.music.bitchord.data.sync.SyncAction
import com.music.bitchord.data.sync.SyncDirection
import com.music.bitchord.data.sync.SyncEntry
import com.music.bitchord.data.sync.SyncFileStamp
import com.music.bitchord.data.sync.SyncIndexEntry
import com.music.bitchord.data.sync.SyncNote
import com.music.bitchord.data.sync.SyncPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync planner's rules, case by case. The planner is pure, so each rule
 * Vaughn settled — no deletes by default, conflicts keep both, one-way pairs
 * never reach across — is a test here rather than a behaviour to discover on
 * a real library.
 */
class SyncPlannerTest {

    private val date = "2026-10-30"

    private fun entry(path: String, size: Long = 100L, mtime: Long = 1_000_000L, tag: String? = null) =
        SyncEntry(path, SyncFileStamp(size, mtime, tag))

    private fun agreed(path: String, size: Long = 100L, mtime: Long = 1_000_000L, tag: String? = null) =
        SyncIndexEntry(path, size, mtime, size, mtime, tag)

    private fun plan(
        configured: SyncDirection = SyncDirection.BIDIRECTIONAL,
        effective: EffectiveDirection = EffectiveDirection.BIDIRECTIONAL,
        mirrorDeletes: Boolean = false,
        local: List<SyncEntry> = emptyList(),
        remote: List<SyncEntry> = emptyList(),
        index: Map<String, SyncIndexEntry> = emptyMap(),
    ) = SyncPlanner.plan(configured, effective, mirrorDeletes, local, remote, index, date)

    private fun kinds(plan: com.music.bitchord.data.sync.SyncPlan) = plan.actions.map { it.kind to it.path }

    // ── New files ────────────────────────────────────────────────────────

    @Test
    fun `new local file uploads in a two-way pair`() {
        val result = plan(local = listOf(entry("a.mp3")))
        assertEquals(listOf(SyncAction.Kind.UPLOAD to "a.mp3"), kinds(result))
    }

    @Test
    fun `new remote file downloads in a two-way pair`() {
        val result = plan(remote = listOf(entry("a.mp3")))
        assertEquals(listOf(SyncAction.Kind.DOWNLOAD to "a.mp3"), kinds(result))
    }

    @Test
    fun `new local file is not a download-only pair's business`() {
        val result = plan(
            configured = SyncDirection.DOWNLOAD_ONLY,
            effective = EffectiveDirection.DOWNLOAD_ONLY,
            local = listOf(entry("a.mp3")),
        )
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun `new remote file is not an upload-only pair's business`() {
        val result = plan(
            configured = SyncDirection.UPLOAD_ONLY,
            effective = EffectiveDirection.UPLOAD_ONLY,
            remote = listOf(entry("a.mp3")),
        )
        assertTrue(result.actions.isEmpty())
    }

    // ── Changes against the agreed state ────────────────────────────────

    @Test
    fun `remote change downloads and local change uploads`() {
        val index = mapOf("a.mp3" to agreed("a.mp3"), "b.mp3" to agreed("b.mp3"))
        val result = plan(
            local = listOf(entry("a.mp3"), entry("b.mp3", size = 200L)),
            remote = listOf(entry("a.mp3", size = 300L), entry("b.mp3")),
            index = index,
        )
        assertEquals(
            listOf(SyncAction.Kind.DOWNLOAD to "a.mp3", SyncAction.Kind.UPLOAD to "b.mp3"),
            kinds(result),
        )
    }

    @Test
    fun `unchanged pair settles with no actions`() {
        val result = plan(
            local = listOf(entry("a.mp3")),
            remote = listOf(entry("a.mp3")),
            index = mapOf("a.mp3" to agreed("a.mp3")),
        )
        assertTrue(result.actions.isEmpty())
        assertTrue("a.mp3" in result.settled)
    }

    @Test
    fun `a change blocked by the direction is not settled`() {
        // Away from home a two-way pair runs download-only: the local edit
        // must stay un-agreed, or the upload would never be planned later.
        val result = plan(
            effective = EffectiveDirection.DOWNLOAD_ONLY,
            local = listOf(entry("a.mp3", size = 200L)),
            remote = listOf(entry("a.mp3")),
            index = mapOf("a.mp3" to agreed("a.mp3")),
        )
        assertTrue(result.actions.isEmpty())
        assertFalse("a.mp3" in result.settled)
    }

    @Test
    fun `a tag change with the same size and time still counts as a change`() {
        val result = plan(
            local = listOf(entry("a.mp3", tag = "v1")),
            remote = listOf(entry("a.mp3", tag = "v2")),
            index = mapOf("a.mp3" to agreed("a.mp3", tag = "v1")),
        )
        assertEquals(listOf(SyncAction.Kind.DOWNLOAD to "a.mp3"), kinds(result))
    }

    @Test
    fun `mtime wobble inside the tolerance is not a change`() {
        val result = plan(
            local = listOf(entry("a.mp3")),
            remote = listOf(entry("a.mp3", mtime = 1_000_000L + 1_500L)),
            index = mapOf("a.mp3" to agreed("a.mp3")),
        )
        assertTrue(result.actions.isEmpty())
        assertTrue("a.mp3" in result.settled)
    }

    // ── Conflicts ────────────────────────────────────────────────────────

    @Test
    fun `both sides changed keeps both, remote copy under a dated name`() {
        val result = plan(
            local = listOf(entry("Song.flac", size = 200L)),
            remote = listOf(entry("Song.flac", size = 300L)),
            index = mapOf("Song.flac" to agreed("Song.flac")),
        )
        assertEquals(1, result.actions.size)
        val action = result.actions.single()
        assertEquals(SyncAction.Kind.DOWNLOAD_CONFLICT_COPY, action.kind)
        assertEquals("Song (conflict 2026-10-30).flac", action.conflictName)
    }

    @Test
    fun `conflict name numbers itself when the dated name is taken`() {
        val name = SyncPlanner.conflictNameFor(
            "Song.flac",
            date,
            taken = setOf("Song (conflict 2026-10-30).flac"),
        )
        assertEquals("Song (conflict 2026-10-30) (1).flac", name)
    }

    @Test
    fun `conflict in a folder keeps the copy in the same folder`() {
        val name = SyncPlanner.conflictNameFor("Album/Song.flac", date, taken = emptySet())
        assertEquals("Album/Song (conflict 2026-10-30).flac", name)
    }

    @Test
    fun `both sides changed on an upload-only run keeps both and touches nothing`() {
        val result = plan(
            configured = SyncDirection.UPLOAD_ONLY,
            effective = EffectiveDirection.UPLOAD_ONLY,
            local = listOf(entry("a.mp3", size = 200L)),
            remote = listOf(entry("a.mp3", size = 300L)),
            index = mapOf("a.mp3" to agreed("a.mp3")),
        )
        assertEquals(
            listOf(SyncAction(SyncAction.Kind.NOTE, "a.mp3", note = SyncNote.CONFLICT_KEPT)),
            result.actions,
        )
    }

    @Test
    fun `files already on both sides with no history agree silently when identical`() {
        val result = plan(
            local = listOf(entry("a.mp3")),
            remote = listOf(entry("a.mp3")),
        )
        assertTrue(result.actions.isEmpty())
        assertTrue("a.mp3" in result.settled)
    }

    @Test
    fun `files already on both sides with no history conflict when different`() {
        val result = plan(
            local = listOf(entry("a.mp3", size = 200L)),
            remote = listOf(entry("a.mp3", size = 300L)),
        )
        assertEquals(listOf(SyncAction.Kind.DOWNLOAD_CONFLICT_COPY), result.actions.map { it.kind })
    }

    @Test
    fun `preserved conflict copies on the phone are never planned`() {
        val result = plan(
            local = listOf(entry("Song (conflict 2026-10-29).flac")),
            remote = emptyList(),
        )
        assertTrue(result.actions.isEmpty())
    }

    // ── Deletions ────────────────────────────────────────────────────────

    @Test
    fun `a phone deletion is kept and reported by default`() {
        val result = plan(
            remote = listOf(entry("a.mp3")),
            index = mapOf("a.mp3" to agreed("a.mp3")),
        )
        assertEquals(
            listOf(SyncAction(SyncAction.Kind.NOTE, "a.mp3", note = SyncNote.DELETED_ON_PHONE_KEPT)),
            result.actions,
        )
    }

    @Test
    fun `a remote deletion is kept and reported by default`() {
        val result = plan(
            local = listOf(entry("a.mp3")),
            index = mapOf("a.mp3" to agreed("a.mp3")),
        )
        assertEquals(
            listOf(SyncAction(SyncAction.Kind.NOTE, "a.mp3", note = SyncNote.DELETED_ON_REMOTE_KEPT)),
            result.actions,
        )
    }

    @Test
    fun `mirroring propagates a phone deletion to the remote`() {
        val result = plan(
            mirrorDeletes = true,
            remote = listOf(entry("a.mp3")),
            index = mapOf("a.mp3" to agreed("a.mp3")),
        )
        assertEquals(listOf(SyncAction.Kind.DELETE_REMOTE to "a.mp3"), kinds(result))
    }

    @Test
    fun `mirroring propagates a remote deletion to the phone`() {
        val result = plan(
            mirrorDeletes = true,
            local = listOf(entry("a.mp3")),
            index = mapOf("a.mp3" to agreed("a.mp3")),
        )
        assertEquals(listOf(SyncAction.Kind.DELETE_LOCAL to "a.mp3"), kinds(result))
    }

    @Test
    fun `mirroring never makes an upload-only pair delete on the phone`() {
        // The remote lost the file; an upload-only mirror restores it by
        // uploading rather than deleting the phone's copy.
        val result = plan(
            configured = SyncDirection.UPLOAD_ONLY,
            effective = EffectiveDirection.UPLOAD_ONLY,
            mirrorDeletes = true,
            local = listOf(entry("a.mp3")),
            index = mapOf("a.mp3" to agreed("a.mp3")),
        )
        assertEquals(listOf(SyncAction.Kind.UPLOAD to "a.mp3"), kinds(result))
    }

    @Test
    fun `mirroring never makes a download-only pair delete on the remote`() {
        // The phone lost the file; a download-only mirror restores it by
        // downloading rather than writing any deletion to the remote.
        val result = plan(
            configured = SyncDirection.DOWNLOAD_ONLY,
            effective = EffectiveDirection.DOWNLOAD_ONLY,
            mirrorDeletes = true,
            remote = listOf(entry("a.mp3")),
            index = mapOf("a.mp3" to agreed("a.mp3")),
        )
        assertEquals(listOf(SyncAction.Kind.DOWNLOAD to "a.mp3"), kinds(result))
    }

    @Test
    fun `a file gone from both sides needs no action`() {
        val result = plan(index = mapOf("a.mp3" to agreed("a.mp3")))
        assertTrue(result.actions.isEmpty())
        assertTrue(result.settled.isEmpty())
    }

    // ── Paused ───────────────────────────────────────────────────────────

    @Test
    fun `a paused pair plans nothing`() {
        val result = plan(
            effective = EffectiveDirection.PAUSED,
            local = listOf(entry("a.mp3")),
            remote = listOf(entry("b.mp3")),
        )
        assertTrue(result.actions.isEmpty())
        assertTrue(result.settled.isEmpty())
    }
}
