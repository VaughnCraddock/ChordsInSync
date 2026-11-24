package com.music.bitchord.data.bpm

import java.util.Locale

/**
 * Naming and candidacy for the Opus→AAC conversion, kept pure so the
 * rules are unit-testable: the Android converter ([OpusAacConverter])
 * decides what a file *is* by its codec, but menus and batch planning
 * need a cheap answer from the name alone.
 */
object OpusFiles {

    /**
     * Extensions whose files *may* hold Opus audio: `.opus` always does;
     * `.ogg`/`.oga` may hold Vorbis instead; `.webm` may hold video.
     * Candidacy is only ever a shortlist — conversion verifies the audio
     * track's codec before it touches anything, and a candidate that
     * turns out not to be Opus is simply analyzed as-is.
     */
    private val CANDIDATE_EXTENSIONS = setOf("opus", "oga", "ogg", "webm", "mka")

    /** Whether [name] (a display name or a path) could be an Opus file worth offering conversion for. */
    fun isCandidate(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in CANDIDATE_EXTENSIONS
    }

    /** The sibling AAC name for [name]: same base, `.m4a` extension. */
    fun outputNameFor(name: String): String {
        val base = name.substringBeforeLast('.', name)
        return "$base.m4a"
    }
}
