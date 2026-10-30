package com.music.bitchord.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress

/**
 * What a run of one pair may do once every gate has had its say. Narrower
 * than the configured [SyncDirection] whenever the phone is away from home,
 * the remote cannot be written to, or the pair or its engine is off.
 */
enum class EffectiveDirection {
    /** Nothing at all: the pair is paused, its engine is off, or an upload-only pair cannot upload. */
    PAUSED,

    /** The remote may be read and copied from, never written to. */
    DOWNLOAD_ONLY,

    /** The remote may be written to; nothing is copied down. */
    UPLOAD_ONLY,

    /** Both halves of a bidirectional pair are allowed. */
    BIDIRECTIONAL,
}

/** Why a direction came out narrower than configured — the status line's raw material. */
enum class DirectionBlock {
    NONE,
    PAIR_DISABLED,
    ENGINE_DISABLED,

    /** Uploads wait for the home network. */
    NOT_HOME,

    /** The remote cannot be written to at all. */
    READ_ONLY_REMOTE,
}

/** An [EffectiveDirection] plus the reason it is what it is. */
data class DirectionDecision(
    val effective: EffectiveDirection,
    val block: DirectionBlock,
)

/**
 * The home gate: upload-capable directions run only on the home network.
 *
 * The rule is Vaughn's, stated about his own server — writing to the library
 * is something that happens at home, never over a public path — and the
 * engine applies it to every pair alike so there is one rule to reason
 * about. Away from home a bidirectional pair degrades to its download half;
 * an upload-only pair has no download half, so it pauses whole.
 *
 * The decision itself is a pure function, [decide]: feed it the facts and it
 * answers. Whether the phone *is* home is [HomeProbe]'s separate question.
 */
object HomeGate {

    fun decide(
        configured: SyncDirection,
        pairEnabled: Boolean,
        engineEnabled: Boolean,
        atHome: Boolean,
        remoteWritable: Boolean,
    ): DirectionDecision {
        if (!pairEnabled) return DirectionDecision(EffectiveDirection.PAUSED, DirectionBlock.PAIR_DISABLED)
        if (!engineEnabled) return DirectionDecision(EffectiveDirection.PAUSED, DirectionBlock.ENGINE_DISABLED)

        // Downloads are legal anywhere; only the upload half is gated.
        if (configured == SyncDirection.DOWNLOAD_ONLY) {
            return DirectionDecision(EffectiveDirection.DOWNLOAD_ONLY, DirectionBlock.NONE)
        }

        val uploadsAllowed = atHome && remoteWritable
        if (uploadsAllowed) {
            return DirectionDecision(
                if (configured == SyncDirection.UPLOAD_ONLY) {
                    EffectiveDirection.UPLOAD_ONLY
                } else {
                    EffectiveDirection.BIDIRECTIONAL
                },
                DirectionBlock.NONE,
            )
        }

        // Not home is the reason worth naming first: a read-only remote at
        // home can be fixed by pointing at another folder, while being away
        // fixes itself by going home.
        val block = if (!atHome) DirectionBlock.NOT_HOME else DirectionBlock.READ_ONLY_REMOTE
        return when (configured) {
            SyncDirection.UPLOAD_ONLY -> DirectionDecision(EffectiveDirection.PAUSED, block)
            SyncDirection.BIDIRECTIONAL -> DirectionDecision(EffectiveDirection.DOWNLOAD_ONLY, block)
            SyncDirection.DOWNLOAD_ONLY -> DirectionDecision(EffectiveDirection.DOWNLOAD_ONLY, DirectionBlock.NONE)
        }
    }
}

/** Answers the one question the gate asks: is this phone on the home network right now? */
fun interface HomeProbe {
    suspend fun isHome(): Boolean
}

/**
 * The home test, by reachability rather than by name.
 *
 * An SSID can be read only with a location permission this feature has no
 * other need for, and a name can be copied by any neighbour's router anyway.
 * What the gate actually cares about — *can this phone reach the home
 * server the LAN way?* — is directly measurable: the active network must be
 * Wi-Fi (or ethernet), and a short TCP connect to the configured home host
 * must succeed on it. Every failure reads as not home, which is the safe
 * direction to fail: it costs a delayed upload, never an unwanted one.
 */
class AndroidHomeProbe(
    context: Context,
    private val host: () -> String,
    private val port: () -> Int,
) : HomeProbe {

    private val appContext = context.applicationContext

    override suspend fun isHome(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
            val network = manager.activeNetwork ?: return@runCatching false
            val capabilities = manager.getNetworkCapabilities(network) ?: return@runCatching false
            val onLanTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!onLanTransport) return@runCatching false
            // Bound to the Wi-Fi network itself, so a route that only exists
            // through a VPN tunnel does not count as being home.
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(host(), port()), PROBE_TIMEOUT_MS)
            }
            true
        }.getOrDefault(false)
    }

    private companion object {
        /** Long enough for a loaded NAS to answer, short enough to ask before every upload. */
        const val PROBE_TIMEOUT_MS = 1_500
    }
}
