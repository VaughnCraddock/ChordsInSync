package com.music.bitchord

import com.music.bitchord.data.sync.DirectionBlock
import com.music.bitchord.data.sync.EffectiveDirection
import com.music.bitchord.data.sync.HomeGate
import com.music.bitchord.data.sync.SyncDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The home gate's arithmetic: a run's effective direction is the least of
 * what the pair asks for, what its switches allow, and where the phone is.
 * The probe that decides "home" is Android's business; this table is the
 * part that must never drift.
 */
class HomeGateTest {

    private fun decide(
        configured: SyncDirection = SyncDirection.BIDIRECTIONAL,
        pairEnabled: Boolean = true,
        engineEnabled: Boolean = true,
        atHome: Boolean = true,
        remoteWritable: Boolean = true,
    ) = HomeGate.decide(configured, pairEnabled, engineEnabled, atHome, remoteWritable)

    @Test
    fun `a switched-off pair does not run`() {
        val decision = decide(pairEnabled = false)
        assertEquals(EffectiveDirection.PAUSED, decision.effective)
        assertEquals(DirectionBlock.PAIR_DISABLED, decision.block)
    }

    @Test
    fun `a switched-off engine parks its pairs`() {
        val decision = decide(engineEnabled = false)
        assertEquals(EffectiveDirection.PAUSED, decision.effective)
        assertEquals(DirectionBlock.ENGINE_DISABLED, decision.block)
    }

    @Test
    fun `two-way at home with a writable remote runs two-way`() {
        val decision = decide()
        assertEquals(EffectiveDirection.BIDIRECTIONAL, decision.effective)
        assertEquals(DirectionBlock.NONE, decision.block)
    }

    @Test
    fun `two-way away from home clamps to downloads`() {
        val decision = decide(atHome = false)
        assertEquals(EffectiveDirection.DOWNLOAD_ONLY, decision.effective)
        assertEquals(DirectionBlock.NOT_HOME, decision.block)
    }

    @Test
    fun `two-way against a read-only remote clamps to downloads`() {
        val decision = decide(remoteWritable = false)
        assertEquals(EffectiveDirection.DOWNLOAD_ONLY, decision.effective)
        assertEquals(DirectionBlock.READ_ONLY_REMOTE, decision.block)
    }

    @Test
    fun `away and read-only reports the home block first`() {
        val decision = decide(atHome = false, remoteWritable = false)
        assertEquals(EffectiveDirection.DOWNLOAD_ONLY, decision.effective)
        assertEquals(DirectionBlock.NOT_HOME, decision.block)
    }

    @Test
    fun `upload-only has nothing to do away from home`() {
        val decision = decide(configured = SyncDirection.UPLOAD_ONLY, atHome = false)
        assertEquals(EffectiveDirection.PAUSED, decision.effective)
        assertEquals(DirectionBlock.NOT_HOME, decision.block)
    }

    @Test
    fun `upload-only against a read-only remote has nothing to do`() {
        val decision = decide(configured = SyncDirection.UPLOAD_ONLY, remoteWritable = false)
        assertEquals(EffectiveDirection.PAUSED, decision.effective)
        assertEquals(DirectionBlock.READ_ONLY_REMOTE, decision.block)
    }

    @Test
    fun `upload-only at home runs as upload-only`() {
        val decision = decide(configured = SyncDirection.UPLOAD_ONLY)
        assertEquals(EffectiveDirection.UPLOAD_ONLY, decision.effective)
        assertEquals(DirectionBlock.NONE, decision.block)
    }

    @Test
    fun `download-only is never gated`() {
        val decision = decide(
            configured = SyncDirection.DOWNLOAD_ONLY,
            atHome = false,
            remoteWritable = false,
        )
        assertEquals(EffectiveDirection.DOWNLOAD_ONLY, decision.effective)
        assertEquals(DirectionBlock.NONE, decision.block)
    }
}
