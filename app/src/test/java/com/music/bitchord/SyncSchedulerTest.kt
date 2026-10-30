package com.music.bitchord

import com.music.bitchord.data.sync.SyncDirection
import com.music.bitchord.data.sync.SyncEngines
import com.music.bitchord.data.sync.SyncPair
import com.music.bitchord.data.sync.SyncScheduler
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scheduler's guest list: a run may only touch pairs that are switched
 * on, on an engine this build wires, with that engine's master switch on.
 * An engine switched off is dormant — this selection is where that promise
 * is kept for the automatic run.
 */
class SyncSchedulerTest {

    private fun pair(
        id: String,
        engineId: String = SyncEngines.DEVICE_FOLDERS,
        enabled: Boolean = true,
    ) = SyncPair(
        id = id,
        engineId = engineId,
        name = id,
        remoteLocator = "content://tree/$id",
        remoteLabel = id,
        localFolder = id,
        direction = SyncDirection.BIDIRECTIONAL,
        enabled = enabled,
    )

    @Test
    fun `an enabled pair on an enabled engine runs`() {
        val pairs = listOf(pair("a"))
        val selected = SyncScheduler.selectRunnablePairs(pairs) { true }
        assertEquals(listOf("a"), selected.map { it.id })
    }

    @Test
    fun `a paused pair does not run`() {
        val pairs = listOf(pair("a", enabled = false), pair("b"))
        val selected = SyncScheduler.selectRunnablePairs(pairs) { true }
        assertEquals(listOf("b"), selected.map { it.id })
    }

    @Test
    fun `an engine switched off holds all of its pairs`() {
        val pairs = listOf(pair("a"), pair("b"))
        val selected = SyncScheduler.selectRunnablePairs(pairs) { false }
        assertEquals(emptyList<String>(), selected.map { it.id })
    }

    @Test
    fun `an unwired engine never runs, whatever its switch says`() {
        val pairs = listOf(pair("a", engineId = SyncEngines.GOOGLE_DRIVE))
        val selected = SyncScheduler.selectRunnablePairs(pairs) { true }
        assertEquals(emptyList<String>(), selected.map { it.id })
    }

    @Test
    fun `selection is per engine, not global`() {
        val pairs = listOf(
            pair("a", engineId = SyncEngines.DEVICE_FOLDERS),
            pair("b", engineId = SyncEngines.WEBDAV),
        )
        val selected = SyncScheduler.selectRunnablePairs(pairs) { it == SyncEngines.DEVICE_FOLDERS }
        assertEquals(listOf("a"), selected.map { it.id })
    }
}
