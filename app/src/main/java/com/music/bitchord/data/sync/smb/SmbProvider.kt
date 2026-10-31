package com.music.bitchord.data.sync.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mserref.NtStatus
import com.hierynomus.smbj.share.DiskShare
import com.music.bitchord.data.smb.SmbClient
import com.music.bitchord.data.sync.SyncEntry
import com.music.bitchord.data.sync.SyncFileStamp
import com.music.bitchord.data.sync.SyncProvider
import com.music.bitchord.data.sync.SyncUnreachableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumSet

/**
 * The far end of an SMB pair: one folder in one share on one server,
 * through SMBJ directly.
 *
 * The library view's SMB layer is a read-only browser whose entries carry
 * no times; sync cannot live on that (its change detection IS size plus
 * last-write time — SMB offers no content stamp), so this provider drives
 * its own per-pair session ([SmbSyncSession]) and asks the wire for
 * `lastWriteTime` on every row. Listing rows become sync entries through
 * [SmbSyncFiles], where the decisions are unit-tested; this class is the
 * wire-shaped shell around them.
 *
 * Audio only in listings, matching the local end's MediaStore blind spot.
 *
 * SMB is LAN-only by nature: a server the phone cannot route to is not an
 * error, it is Tuesday. Transport failures become
 * [SyncUnreachableException], the engine records the pair as unreachable,
 * and scheduled runs skip it quietly. A server that answers — including
 * with "access denied" — keeps its answer's identity and fails properly.
 *
 * No live test of this provider exists from the build VM (its LAN is
 * proxy-blocked); the wire paths are exercised by phone testing. The pure
 * layers under it — locator, row mapping, planner behaviour on SMB-shaped
 * stamps — are unit-tested here.
 */
class SmbProvider(
    private val target: SmbPaths.Target,
    username: String,
    password: String,
) : SyncProvider {

    private val session = SmbSyncSessions.forTarget(target, username, password)

    override suspend fun list(): List<SyncEntry> = guardUnreachable {
        session.withShare { share ->
            val entries = mutableListOf<SyncEntry>()
            val stack = ArrayDeque<String>()
            stack.add("")
            val visited = HashSet<String>()
            while (stack.isNotEmpty()) {
                val prefix = stack.removeFirst()
                if (!visited.add(prefix.lowercase())) continue
                val rows = share.list(SmbClient.smbPath(SmbPaths.underBase(target, prefix)))
                    .map { info ->
                        SmbRawEntry(
                            name = info.fileName,
                            isDirectory = (info.fileAttributes and
                                FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L,
                            size = info.endOfFile,
                            lastWriteMs = info.lastWriteTime.toEpochMillis(),
                        )
                    }
                val split = SmbSyncFiles.split(rows, prefix)
                entries += split.files
                for (directory in split.directories) {
                    if (!visited.contains(directory.lowercase())) stack.add(directory)
                }
            }
            entries
        }
    }

    override suspend fun read(path: String): InputStream = guardUnreachable {
        val file = session.withShare { share ->
            share.openFile(
                SmbClient.smbPath(SmbPaths.underBase(target, path)),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
        }
        SmbInputStream(file)
    }

    override suspend fun write(path: String, source: InputStream, size: Long, mtimeMs: Long?): SyncEntry =
        guardUnreachable {
            session.withShare { share ->
                val full = SmbPaths.underBase(target, path)
                ensureFolders(share, full.substringBeforeLast('/', missingDelimiterValue = ""))
                val wire = SmbClient.smbPath(full)
                share.openFile(
                    wire,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    null,
                ).use { file ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var offset = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        file.write(buffer, offset, 0, read)
                        offset += read
                    }
                }
                if (mtimeMs != null) setLastWrite(share, wire, mtimeMs)
                val info = share.getFileInformation(wire)
                SyncEntry(
                    path,
                    SyncFileStamp(
                        size = info.standardInformation.endOfFile,
                        mtimeMs = info.basicInformation.lastWriteTime.toEpochMillis(),
                    ),
                )
            }
        }

    override suspend fun delete(path: String) {
        guardUnreachable {
            session.withShare { share ->
                share.rm(SmbClient.smbPath(SmbPaths.underBase(target, path)))
            }
        }
    }

    /** Creates each missing folder on the way to [displayDir], top-down. */
    private fun ensureFolders(share: DiskShare, displayDir: String) {
        if (displayDir.isBlank()) return
        var current = ""
        for (segment in displayDir.split('/')) {
            current = if (current.isEmpty()) segment else "$current/$segment"
            val wire = SmbClient.smbPath(current)
            if (!share.folderExists(wire)) share.mkdir(wire)
        }
    }

    /**
     * Offers the source file's moment to the stored copy. Best-effort by
     * design: a server that refuses time-setting still has the file, and
     * the read-back stamp (not the offered one) is what the index records —
     * the pair simply compares by the server's clock from then on. The
     * file's current attributes ride along so the set changes nothing else.
     */
    private fun setLastWrite(share: DiskShare, wire: String, mtimeMs: Long) {
        runCatching {
            val current = share.getFileInformation(wire, FileBasicInformation::class.java)
            share.setFileInformation(
                wire,
                FileBasicInformation(
                    FileBasicInformation.DONT_SET,
                    FileBasicInformation.DONT_SET,
                    FileTime.ofEpochMillis(mtimeMs),
                    FileBasicInformation.DONT_SET,
                    current.fileAttributes,
                ),
            )
        }
    }

    /**
     * Runs [block], translating "the server cannot be reached at all" into
     * [SyncUnreachableException]. SMBJ surfaces transport trouble in a few
     * wrappers; the cause chain is the reliable place to look. A server
     * status answer (SMBApiException) never carries those causes, so real
     * answers are never mistaken for absence.
     */
    private suspend fun <T> guardUnreachable(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (failure: Throwable) {
                if (failure.isUnreachable()) throw SyncUnreachableException(failure.message)
                throw failure
            }
        }

    private fun Throwable.isUnreachable(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is ConnectException || cause is UnknownHostException ||
                cause is SocketTimeoutException || cause is NoRouteToHostException
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}

/**
 * A download's bytes, read off an open SMB file handle. Bounded by the
 * file's own length, like the cover reader in the library layer: some
 * servers answer a read past EOF with bytes no one should trust.
 */
private class SmbInputStream(
    private val file: com.hierynomus.smbj.share.File,
) : InputStream() {

    private val length: Long = runCatching { file.length }.getOrDefault(-1L)
    private var offset = 0L
    private val chunk = ByteArray(64 * 1024)
    private var chunkStart = 0
    private var chunkEnd = 0

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, bufferOffset: Int, byteCount: Int): Int {
        if (byteCount == 0) return 0
        if (chunkStart >= chunkEnd) {
            if (length >= 0 && offset >= length) return -1
            val wanted = if (length >= 0) {
                minOf(chunk.size.toLong(), length - offset).toInt()
            } else {
                chunk.size
            }
            if (wanted <= 0) return -1
            val read = file.read(chunk, offset, 0, wanted)
            if (read <= 0) return -1
            offset += read
            chunkStart = 0
            chunkEnd = read
        }
        val count = minOf(byteCount, chunkEnd - chunkStart)
        System.arraycopy(chunk, chunkStart, buffer, bufferOffset, count)
        chunkStart += count
        return count
    }

    override fun close() {
        runCatching { file.close() }
    }
}

/**
 * The one-time SMB writability probe, run at pair creation with the user's
 * knowledge (SYNC-RESEARCH §7.3): open a temp file for write in the pair's
 * folder, close it, remove it.
 *
 * True when the write landed; false when the server answered the write
 * with access denied (a read-only share or a read-only user — SMB's way of
 * saying so); null when no verdict was reached.
 */
object SmbWritabilityProbe {

    private const val PROBE_NAME = ".bitchord-sync-probe"

    suspend fun probe(target: SmbPaths.Target, username: String, password: String): Boolean? =
        withContext(Dispatchers.IO) {
            val session = SmbSyncSessions.forTarget(target, username, password)
            val wire = SmbClient.smbPath(
                SmbPaths.underBase(target, PROBE_NAME),
            )
            runCatching {
                session.withShare { share ->
                    share.openFile(
                        wire,
                        EnumSet.of(AccessMask.GENERIC_WRITE),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_CREATE,
                        null,
                    ).close()
                    runCatching { share.rm(wire) }
                }
                true
            }.getOrElse { failure ->
                var cause: Throwable? = failure
                var denied = false
                while (cause != null) {
                    if (cause is SMBApiException && cause.status == NtStatus.STATUS_ACCESS_DENIED) {
                        denied = true
                    }
                    cause = cause.cause
                }
                if (denied) false else null
            }
        }
}
