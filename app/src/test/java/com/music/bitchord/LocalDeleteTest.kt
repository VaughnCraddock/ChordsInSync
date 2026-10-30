package com.music.bitchord

import com.music.bitchord.data.LocalDelete
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selection half of local delete: which files a delete covers. The
 * rules that matter are the boring ones — duplicates collapse, songs with
 * no file on the phone contribute nothing, and a folder's delete covers
 * exactly the folder's own songs, never a neighbour's.
 */
class LocalDeleteTest {

    private fun song(
        title: String,
        localUri: String? = null,
        localPath: String? = null,
    ) = Song(
        videoId = "id-$title",
        title = title,
        artist = "Artist",
        thumbnailUrl = null,
        localUri = localUri,
        localPath = localPath,
    )

    @Test
    fun `uri set collects each file once, in order`() {
        val songs = listOf(
            song("a", localUri = "content://media/1"),
            song("b", localUri = "content://media/2"),
            song("a again", localUri = "content://media/1"),
        )
        assertEquals(
            listOf("content://media/1", "content://media/2"),
            LocalDelete.uriStringsFor(songs),
        )
    }

    @Test
    fun `songs with no file on the phone contribute no uri`() {
        val songs = listOf(
            song("streamed"),
            song("blank", localUri = ""),
            song("local", localUri = "file:///sdcard/Music/x.mp3"),
        )
        assertEquals(
            listOf("file:///sdcard/Music/x.mp3"),
            LocalDelete.uriStringsFor(songs),
        )
    }

    @Test
    fun `an empty selection deletes nothing`() {
        assertTrue(LocalDelete.uriStringsFor(emptyList()).isEmpty())
        assertTrue(LocalDelete.folderSongs(emptyList(), "/sdcard/Music").isEmpty())
    }

    @Test
    fun `a folder's songs are exactly the ones whose files sit in it`() {
        val songs = listOf(
            song("in", localPath = "/sdcard/Music/Album/a.mp3"),
            song("also in", localPath = "/sdcard/Music/Album/b.mp3"),
            song("nested", localPath = "/sdcard/Music/Album/Live/c.mp3"),
            song("neighbour", localPath = "/sdcard/Music/Album Two/d.mp3"),
            song("pathless"),
        )
        assertEquals(
            listOf("in", "also in"),
            LocalDelete.folderSongs(songs, "/sdcard/Music/Album").map { it.title },
        )
    }

    @Test
    fun `a folder with no songs in it selects none`() {
        val songs = listOf(song("elsewhere", localPath = "/sdcard/Music/Other/a.mp3"))
        assertTrue(LocalDelete.folderSongs(songs, "/sdcard/Music/Empty").isEmpty())
    }
}
