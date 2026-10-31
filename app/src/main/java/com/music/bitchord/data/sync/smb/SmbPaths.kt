package com.music.bitchord.data.sync.smb

import com.music.bitchord.data.smb.SmbConfig

/**
 * Where an SMB pair points, and the one string that names it: the pair's
 * `remoteLocator` is `smb://host/share/folder/in/share` — the addressing
 * [SmbConfig] already speaks, minus any credential (those live in the
 * encrypted store, keyed by pair, and never ride in a locator that gets
 * logged and exported).
 *
 * Pure string work in both directions, so a locator written at pair
 * creation parses back to exactly the target it was written from.
 */
object SmbPaths {

    /** One server, one share, one folder in it — a pair's far side. */
    data class Target(
        val host: String,
        val port: Int,
        val share: String,
        /** The pair's folder inside the share: display form (`/`-joined), no leading/trailing slash; blank = the share root. */
        val basePath: String,
    )

    /**
     * The target for raw form input. [hostInput] may carry a port
     * (`nas:445`, `smb://nas/…`) — [SmbConfig.splitHostPort] settles it.
     */
    fun target(hostInput: String, share: String, basePath: String): Target {
        val (host, port) = SmbConfig.splitHostPort(hostInput)
        return Target(
            host = host,
            port = port,
            share = share.trim().trim('/'),
            basePath = basePath.trim().replace('\\', '/').trim('/'),
        )
    }

    /** The locator string stored on the pair. The port rides only when it isn't the default. */
    fun locator(target: Target): String {
        val authority = if (target.port == SmbConfig.DEFAULT_PORT) {
            target.host
        } else {
            "${target.host}:${target.port}"
        }
        return buildString {
            append("smb://").append(authority).append('/').append(target.share)
            if (target.basePath.isNotBlank()) append('/').append(target.basePath)
        }
    }

    /** A locator back to its target; null when it isn't one of ours. */
    fun parse(locator: String): Target? {
        val rest = locator.trim().removePrefix("smb://")
        if (rest == locator.trim() && !locator.trim().startsWith("smb:")) return null
        val segments = rest.split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) return null
        val (host, port) = SmbConfig.splitHostPort(segments[0])
        if (host.isBlank()) return null
        return Target(
            host = host,
            port = port,
            share = segments[1],
            basePath = segments.drop(2).joinToString("/"),
        )
    }

    /** What the Sync page calls the remote folder: `share`, or `share/folder`. */
    fun label(target: Target): String =
        if (target.basePath.isBlank()) target.share else "${target.share}/${target.basePath}"

    /** A path under the pair's root joined onto the pair's base folder, display form. */
    fun underBase(target: Target, relativePath: String): String =
        listOf(target.basePath, relativePath).filter { it.isNotBlank() }.joinToString("/")
}
