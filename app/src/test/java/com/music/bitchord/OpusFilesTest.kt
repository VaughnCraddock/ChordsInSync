package com.music.bitchord

import com.music.bitchord.data.bpm.OpusFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The conversion's naming and candidacy rules: what gets offered conversion, and what its sibling is called. */
class OpusFilesTest {

    @Test
    fun candidateExtensions() {
        assertTrue(OpusFiles.isCandidate("track.opus"))
        assertTrue(OpusFiles.isCandidate("track.oga"))
        assertTrue(OpusFiles.isCandidate("track.ogg"))
        assertTrue(OpusFiles.isCandidate("track.webm"))
        assertTrue(OpusFiles.isCandidate("track.mka"))
        assertTrue(OpusFiles.isCandidate("TRACK.OPUS"))
        assertTrue(OpusFiles.isCandidate("/storage/emulated/0/Music/BitChord/song.opus"))
    }

    @Test
    fun nonCandidates() {
        assertFalse(OpusFiles.isCandidate("track.mp3"))
        assertFalse(OpusFiles.isCandidate("track.m4a"))
        assertFalse(OpusFiles.isCandidate("track.flac"))
        assertFalse(OpusFiles.isCandidate("track.wav"))
        assertFalse(OpusFiles.isCandidate("noextension"))
        assertFalse(OpusFiles.isCandidate("opus"))
        assertFalse(OpusFiles.isCandidate(""))
        assertFalse(OpusFiles.isCandidate(null))
    }

    @Test
    fun outputNames() {
        assertEquals("track.m4a", OpusFiles.outputNameFor("track.opus"))
        assertEquals("track.m4a", OpusFiles.outputNameFor("track.webm"))
        assertEquals("my.song.m4a", OpusFiles.outputNameFor("my.song.ogg"))
        assertEquals("track.m4a", OpusFiles.outputNameFor("track"))
    }
}
