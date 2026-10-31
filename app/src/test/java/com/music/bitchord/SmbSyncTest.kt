package com.music.bitchord

import com.music.bitchord.data.sync.EffectiveDirection
import com.music.bitchord.data.sync.SyncAction
import com.music.bitchord.data.sync.SyncDirection
import com.music.bitchord.data.sync.SyncEntry
import com.music.bitchord.data.sync.SyncFileStamp
import com.music.bitchord.data.sync.SyncIndexEntry
import com.music.bitchord.data.sync.SyncPlanner
import com.music.bitchord.data.sync.smb.SmbPaths
import com.music.bitchord.data.sync.smb.SmbRawEntry
import com.music.bitchord.data.sync.smb.SmbSyncFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The SMB sync engine's pure layers: the pair locator's round trip, the
 * listing-row mapping (SMB's whole change detection is size + last-write,
 * so the stamp mapping is the feature), and the planner reading SMB-shaped
 * listings. The wire itself needs a LAN server and is phone-tested — see
 * FORK-NOTES.
 */
class SmbSyncTest {

    @Test
    fun locator_roundTrips() {
        val target = SmbPaths.target("192.168.100.3", "MusicLibrary", "Rock/Live")
        assertEquals("smb://192.168.100.3/MusicLibrary/Rock/Live", SmbPaths.locator(target))
        assertEquals(target, SmbPaths.parse(SmbPaths.locator(target)))
    }

    @Test
    fun locator_carriesANonDefaultPort() {
        val target = SmbPaths.target("nas.local:1445", "Music", "")
        assertEquals(1445, target.port)
        assertEquals("smb://nas.local:1445/Music", SmbPaths.locator(target))
        assertEquals(target, SmbPaths.parse("smb://nas.local:1445/Music"))
    }

    @Test
    fun locator_normalizesSlashesAndBackslashes() {
        val target = SmbPaths.target(" smb://NAS/ ", "/Music/", "\\Rock\\Live\\")
        assertEquals("nas", target.host)
        assertEquals("Music", target.share)
        assertEquals("Rock/Live", target.basePath)
        assertEquals("Music/Rock/Live", SmbPaths.label(target))
        assertEquals("Music", SmbPaths.label(SmbPaths.target("nas", "Music", "")))
    }

    @Test
    fun parse_refusesForeignLocators() {
        assertNull(SmbPaths.parse("content://tree/primary:Music"))
        assertNull(SmbPaths.parse("smb://onlyhost"))
        assertNull(SmbPaths.parse(""))
    }

    @Test
    fun underBase_joinsPairRootAndPath() {
        val target = SmbPaths.target("nas", "Music", "Rock")
        assertEquals("Rock/Live/take.flac", SmbPaths.underBase(target, "Live/take.flac"))
        val rootTarget = SmbPaths.target("nas", "Music", "")
        assertEquals("take.flac", SmbPaths.underBase(rootTarget, "take.flac"))
    }

    private fun row(name: String, dir: Boolean = false, size: Long = 0, mtime: Long = 0) =
        SmbRawEntry(name = name, isDirectory = dir, size = size, lastWriteMs = mtime)

    @Test
    fun split_walksFoldersAndKeepsAudioWithItsStamp() {
        val split = SmbSyncFiles.split(
            rows = listOf(
                row(".", dir = true),
                row("..", dir = true),
                row("Live", dir = true),
                row("song.mp3", size = 100, mtime = 5_000),
                row("take.FLAC", size = 200, mtime = 6_000),
                row("notes.txt", size = 10, mtime = 7_000),
                row("cover.jpg", size = 20, mtime = 8_000),
            ),
            prefix = "Rock",
        )
        assertEquals(listOf("Rock/Live"), split.directories)
        assertEquals(listOf("Rock/song.mp3", "Rock/take.FLAC"), split.files.map { it.path })
        // The stamp is size + last-write, unmodified: SMB has no tag to add.
        assertEquals(SyncFileStamp(100, 5_000, null), split.files[0].stamp)
        assertEquals(SyncFileStamp(200, 6_000, null), split.files[1].stamp)
    }

    @Test
    fun split_atThePairRootHasNoPrefix() {
        val split = SmbSyncFiles.split(
            rows = listOf(row("a.mp3", size = 1, mtime = 2), row("Sub", dir = true)),
            prefix = "",
        )
        assertEquals(listOf("Sub"), split.directories)
        assertEquals(listOf("a.mp3"), split.files.map { it.path })
    }

    @Test
    fun `planner follows last-write when there is no tag to consult`() {
        // The SMB case end to end: both sides hold the file at the agreed
        // size; the server's copy was re-ripped later (mtime well past the
        // tolerance). With no etag anywhere, the clock is the only witness.
        val agreed = SyncIndexEntry(
            path = "a.mp3", localSize = 100, localMtimeMs = 1_000,
            remoteSize = 100, remoteMtimeMs = 1_000, remoteTag = null,
        )
        val local = listOf(SyncEntry("a.mp3", SyncFileStamp(100, 1_000)))
        val remote = listOf(SyncEntry("a.mp3", SyncFileStamp(100, 90_000)))
        val plan = SyncPlanner.plan(
            configured = SyncDirection.BIDIRECTIONAL,
            effective = EffectiveDirection.BIDIRECTIONAL,
            mirrorDeletes = false,
            local = local,
            remote = remote,
            index = mapOf("a.mp3" to agreed),
            conflictDate = "2026-10-30",
        )
        assertEquals(listOf(SyncAction(SyncAction.Kind.DOWNLOAD, "a.mp3")), plan.actions)
    }

    @Test
    fun `planner leaves an in-tolerance smb pair settled`() {
        // Servers and MediaStore report whole seconds at different grains;
        // a stamp inside the tolerance must not churn the pair every run.
        val agreed = SyncIndexEntry(
            path = "a.mp3", localSize = 100, localMtimeMs = 1_000,
            remoteSize = 100, remoteMtimeMs = 1_000, remoteTag = null,
        )
        val local = listOf(SyncEntry("a.mp3", SyncFileStamp(100, 1_500)))
        val remote = listOf(SyncEntry("a.mp3", SyncFileStamp(100, 2_000)))
        val plan = SyncPlanner.plan(
            configured = SyncDirection.BIDIRECTIONAL,
            effective = EffectiveDirection.BIDIRECTIONAL,
            mirrorDeletes = false,
            local = local,
            remote = remote,
            index = mapOf("a.mp3" to agreed),
            conflictDate = "2026-10-30",
        )
        assertEquals(emptyList<SyncAction>(), plan.actions)
        assertEquals(setOf("a.mp3"), plan.settled.keys)
    }
}
