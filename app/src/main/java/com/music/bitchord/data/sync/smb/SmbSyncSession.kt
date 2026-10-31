package com.music.bitchord.data.sync.smb

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.music.bitchord.data.smb.SmbConnection
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One pair's own SMBJ session — the per-pair counterpart of the library
 * view's [SmbConnection] singleton. Sync pairs each name their own server
 * and sign in with their own credential (SYNC-RESEARCH §1.1: the sync
 * engine instantiates its own sessions from pair config rather than
 * fighting the library view's single-server holder), so a session here is
 * built from a [SmbPaths.Target] plus a username/password, and nothing
 * about it is global.
 *
 * Sessions are shared per target+username through [SmbSyncSessions]: a
 * run creates its provider, the provider asks for the session, and the
 * connection outlives the run the way the library pool does — a fresh
 * SMB sign-in every four hours per pair would be its own kind of noise
 * (and, against a lockout counter, its own risk). A pair whose password
 * changed gets a fresh session: the registry compares and replaces.
 *
 * Failure posture mirrors [SmbConnection]: a transport failure
 * ([IOException]) reconnects once; a server *status* answer
 * (SMBApiException — bad password, no such share) is not an IOException
 * and fails fast.
 */
class SmbSyncSession(
    private val target: SmbPaths.Target,
    private val username: String,
    private val password: String,
) {

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var share: DiskShare? = null

    /** Whether [password] is the credential this session was built with. */
    fun matchesPassword(candidate: String): Boolean = candidate == password

    @Synchronized
    fun close() {
        runCatching { share?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        share = null
        connection = null
        client = null
    }

    fun <T> withShare(block: (DiskShare) -> T): T {
        return try {
            block(open())
        } catch (failure: IOException) {
            close()
            block(open())
        }
    }

    @Synchronized
    private fun open(): DiskShare {
        val live = share
        if (live != null && connection?.isConnected == true) return live
        close()
        val config = com.hierynomus.smbj.SmbConfig.builder()
            .withTimeout(20, TimeUnit.SECONDS)
            .withReadTimeout(30, TimeUnit.SECONDS)
            .withWriteTimeout(30, TimeUnit.SECONDS)
            .withTransactTimeout(30, TimeUnit.SECONDS)
            .build()
        val newClient = SMBClient(config)
        try {
            val newConnection = newClient.connect(target.host, target.port)
            val session = newConnection.authenticate(
                SmbConnection.authFor(username, password),
            )
            val diskShare = session.connectShare(target.share) as? DiskShare
                ?: throw SmbConnection.SmbException("\"${target.share}\" is not a file share")
            client = newClient
            connection = newConnection
            share = diskShare
            return diskShare
        } catch (failure: Throwable) {
            runCatching { newClient.close() }
            throw failure
        }
    }
}

/** The standing sessions, one per (server, share, username) — see [SmbSyncSession]. */
object SmbSyncSessions {

    private val sessions = mutableMapOf<String, SmbSyncSession>()

    @Synchronized
    fun forTarget(target: SmbPaths.Target, username: String, password: String): SmbSyncSession {
        val key = "${target.host}:${target.port}/${target.share}|$username"
        val existing = sessions[key]
        if (existing != null && existing.matchesPassword(password)) return existing
        existing?.close()
        return SmbSyncSession(target, username, password).also { sessions[key] = it }
    }
}
