package com.music.bitchord

import com.music.bitchord.data.LocalGroups
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.DownloadedCollection
import com.music.bitchord.download.Downloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGroupsTest {

    private fun song(
        id: String,
        title: String,
        artist: String = "Artist",
        album: String? = "Album",
        track: Int? = null,
        path: String? = null,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        albumName = album,
        trackNumber = track,
        localPath = path,
    )

    @Test
    fun pageIds_roundTripNamesWithColons() {
        val id = LocalGroups.albumPageId("local:downloads", "Live: At The Hall")
        assertEquals("local:downloads", LocalGroups.folderOf(id))
        assertEquals(BrowseType.ALBUM, LocalGroups.typeOf(id))
        assertFalse(LocalGroups.isArtist(id))

        val artist = LocalGroups.artistPageId("local:all", "AC/DC")
        assertEquals("local:all", LocalGroups.folderOf(artist))
        assertEquals(BrowseType.OTHER, LocalGroups.typeOf(artist))
        assertTrue(LocalGroups.isArtist(artist))
    }

    @Test
    fun folderOf_ignoresEverythingElse() {
        assertNull(LocalGroups.folderOf("local:downloads"))
        assertNull(LocalGroups.folderOf(Downloads.pageIdFor("MPREb_x")))
        assertNull(LocalGroups.folderOf("local:group:bogus:all:x"))
        assertNull(LocalGroups.folderOf(null))
    }

    @Test
    fun songsOf_rebuildsTheGroupInReleaseOrder() {
        val folder = listOf(
            song("b", "B", album = "Live: At The Hall", track = 2),
            song("x", "X", album = "Other"),
            song("a", "A", album = "Live: At The Hall", track = 1),
        )
        val id = LocalGroups.albumPageId("local:all", "Live: At The Hall")
        assertEquals(listOf("a", "b"), LocalGroups.songsOf(id, folder).map { it.videoId })
    }

    @Test
    fun foldersOf_groupsByContainingFolder() {
        val folder = listOf(
            song("a", "A", path = "/music/rock/a.mp3"),
            song("b", "B", path = "/music/rock/b.mp3"),
            song("c", "C", path = "/music/pop/c.mp3"),
            // Nested one level down: its own group, not rolled up into rock.
            song("d", "D", path = "/music/rock/live/d.mp3"),
            // No path on record: groups under no folder.
            song("e", "E"),
        )
        val groups = LocalGroups.foldersOf("local:all", folder)
        assertEquals(listOf("live", "pop", "rock"), groups.map { it.title })

        val rock = groups.first { it.title == "rock" }
        assertEquals(listOf("a", "b"), rock.songs.map { it.videoId })
        assertEquals("/music/rock", rock.subtitle)
        assertEquals(BrowseType.OTHER, rock.type)
        assertEquals(LocalGroups.folderPageId("local:all", "/music/rock"), rock.browseId)
    }

    @Test
    fun folderPageId_roundTripsThroughSongsOf() {
        val folder = listOf(
            song("a", "A", path = "/music/Rock: The Loud Years/a.mp3"),
            song("b", "B", path = "/music/other/b.mp3"),
            song("c", "C", path = "/music/Rock: The Loud Years/c.mp3"),
        )
        // The path itself holds a colon and spaces; the page id must still parse.
        val id = LocalGroups.folderPageId("local:all", "/music/Rock: The Loud Years")
        assertEquals("local:all", LocalGroups.folderOf(id))
        assertEquals(BrowseType.OTHER, LocalGroups.typeOf(id))
        assertFalse(LocalGroups.isArtist(id))
        assertEquals(listOf("a", "c"), LocalGroups.songsOf(id, folder).map { it.videoId })
    }

    /** Folder groups over one song per directory, named so title order and path order agree. */
    private fun folderGroups(vararg paths: String) =
        LocalGroups.foldersOf(
            "local:all",
            paths.mapIndexed { index, path -> song("s$index", "S$index", path = "$path/track.mp3") },
        )

    @Test
    fun orderFolders_favoritesComeFirst() {
        val groups = folderGroups("/music/beta", "/music/alpha", "/music/gamma")
        val ordered = LocalGroups.orderFolders(groups, setOf("/music/gamma"))
        assertEquals(listOf("/music/gamma", "/music/alpha", "/music/beta"), ordered.map { it.subtitle })
    }

    @Test
    fun orderFolders_keepsTitleOrderWithinEachTier() {
        val groups = folderGroups("/music/beta", "/music/alpha", "/music/gamma", "/music/delta")
        val ordered = LocalGroups.orderFolders(groups, setOf("/music/gamma", "/music/beta"))
        assertEquals(
            listOf("/music/beta", "/music/gamma", "/music/alpha", "/music/delta"),
            ordered.map { it.subtitle },
        )
    }

    @Test
    fun orderFolders_emptyFavoritesLeavesOrderUntouched() {
        val groups = folderGroups("/music/beta", "/music/alpha", "/music/gamma")
        assertEquals(groups, LocalGroups.orderFolders(groups, emptySet()))
    }

    @Test
    fun orderFolders_ignoresFavoritesWithNoMatchingFolder() {
        val groups = folderGroups("/music/beta", "/music/alpha")
        val ordered = LocalGroups.orderFolders(groups, setOf("/music/not-scanned"))
        assertEquals(groups.map { it.subtitle }, ordered.map { it.subtitle })
    }

    @Test
    fun folderPathOf_readsThePathBackOffAFolderGroupOnly() {
        val groups = folderGroups("/music/rock")
        assertEquals("/music/rock", LocalGroups.folderPathOf(groups.single()))
        val album = LocalGroups.albumsOf("local:all", listOf(song("a", "A", album = "Record")), emptyList()).single()
        assertNull(LocalGroups.folderPathOf(album))
    }

    @Test
    fun albumsOf_namedReleaseWinsOverItsOwnTags() {
        val tracks = listOf(song("a", "A", album = "Record"), song("b", "B", album = "Tagged"))
        val downloaded = DownloadedCollection(
            id = "MPREb_1",
            title = "record",
            subtitle = "",
            thumbnailUrl = "art",
            playlist = false,
            songs = listOf(tracks[0]),
        )
        val albums = LocalGroups.albumsOf("local:downloads", tracks, listOf(downloaded))
        assertEquals(listOf("record", "Tagged"), albums.map { it.title })
        assertEquals(Downloads.pageIdFor("MPREb_1"), albums[0].browseId)
        assertEquals("MPREb_1", albums[0].downloadId)
        assertEquals("Artist", albums[0].subtitle)
        assertNull(albums[1].downloadId)
    }
}
