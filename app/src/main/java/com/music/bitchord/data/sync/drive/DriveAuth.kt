package com.music.bitchord.data.sync.drive

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.sync.SyncNeedsSignInException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume

/**
 * The Drive account's sign-in: Google's Authorization API, and nothing else.
 *
 * This is a separate sign-in from the YouTube Music one, and the difference
 * is the whole point: that one is a WebView cookie harvested for Innertube;
 * this one is a real OAuth grant, minted by Play services against the
 * Android OAuth client registered in Vaughn's own Google Cloud project for
 * this build's package + signing SHA-1 (the Sync page's setup card shows
 * both, computed at runtime, for copying into the console). No client id
 * and no `google-services.json` exist in the app — Play services makes the
 * match, and hands back a roughly one-hour access token. There is no
 * refresh token in this flow, so nothing durable is stored: the account
 * identity lives in [AuthStore], tokens live in memory for the length of
 * a run.
 *
 * The scope is the FULL Drive scope, and that was Vaughn's explicit call:
 * the safe `drive.file` scope was built first and he declined it, because
 * pairs must be able to point at folders that already exist in his Drive,
 * which `drive.file` can never see. Full Drive is a restricted scope, so
 * on his own (Testing-status, unverified) project the first connect shows
 * Google's "unverified app" warning; he proceeds via Advanced, which the
 * setup card says out loud so the warning is expected, not alarming. The
 * breadth is in the grant only — the provider still only ever walks the
 * folder each pair points at, down from there.
 */
object DriveAuth {

    /** The one scope this engine asks for: full Drive, per Vaughn's explicit choice over `drive.file`. */
    const val SCOPE_DRIVE_FULL = "https://www.googleapis.com/auth/drive"

    private const val TAG = "DriveAuth"

    private fun request(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE_DRIVE_FULL)))
            .build()

    /** How an interactive authorization attempt ended. */
    sealed interface InteractiveOutcome {
        /** A token, no detour needed. [result] carries the account Play services acted for. */
        data class Token(val accessToken: String, val result: AuthorizationResult) : InteractiveOutcome

        /** Play services needs its own screen first — account picker or consent. Launch [pendingIntent]. */
        data class Resolution(val pendingIntent: android.app.PendingIntent) : InteractiveOutcome

        /** Authorization failed outright; [message] is for the log and the status line, not a dialog. */
        data class Failure(val message: String?) : InteractiveOutcome
    }

    /**
     * Asks for a token with the user present. Never throws: the three ways
     * this can end are the outcome type, because the caller (a settings
     * page) has a different next step for each.
     */
    suspend fun authorizeInteractive(context: Context): InteractiveOutcome =
        suspendCancellableCoroutine { continuation ->
            Identity.getAuthorizationClient(context.applicationContext)
                .authorize(request())
                .addOnSuccessListener { result -> continuation.resume(result.toOutcome()) }
                .addOnFailureListener { failure ->
                    Log.w(TAG, "interactive authorize failed: ${failure.message}")
                    continuation.resume(InteractiveOutcome.Failure(failure.message))
                }
        }

    /** The answer to a launched [InteractiveOutcome.Resolution] intent, read back after the activity returns. */
    fun outcomeFromIntent(context: Context, data: Intent): InteractiveOutcome = try {
        Identity.getAuthorizationClient(context.applicationContext)
            .getAuthorizationResultFromIntent(data)
            .toOutcome()
    } catch (failure: ApiException) {
        Log.w(TAG, "authorize resolution failed: ${failure.message}")
        InteractiveOutcome.Failure(failure.message)
    }

    private fun AuthorizationResult.toOutcome(): InteractiveOutcome {
        val pending = pendingIntent
        val token = accessToken
        return when {
            hasResolution() && pending != null -> InteractiveOutcome.Resolution(pending)
            token != null -> InteractiveOutcome.Token(token, this)
            else -> InteractiveOutcome.Failure("Authorization returned neither a token nor a resolution")
        }
    }

    /**
     * A token with nobody present — the background run's path.
     *
     * Play services can usually re-mint silently once consent exists. When
     * it cannot — consent never given, revoked, or a picker is required —
     * the honest answer is [SyncNeedsSignInException]: the run records
     * needs-sign-in and the Sync page offers the interactive fix, rather
     * than the run failing as if something were broken.
     */
    suspend fun accessToken(context: Context): String = withContext(Dispatchers.IO) {
        val task = Identity.getAuthorizationClient(context.applicationContext).authorize(request())
        val result = try {
            Tasks.await(task)
        } catch (failure: ExecutionException) {
            val cause = failure.cause
            if (cause is ApiException && cause.statusCode == CommonStatusCodes.DEVELOPER_ERROR) {
                // Package/SHA-1 not registered (or the Drive API not enabled
                // in the project): retrying will not help, and calling it a
                // sign-in problem would send the owner to the wrong fix.
                throw DriveApiException(0, "developer_error", "Drive authorization is not set up for this build: ${cause.message}")
            }
            throw SyncNeedsSignInException(cause?.message)
        }
        if (result.hasResolution()) throw SyncNeedsSignInException("Drive authorization needs interaction")
        val granted = result.grantedScopes.orEmpty()
        if (granted.isNotEmpty() && SCOPE_DRIVE_FULL !in granted) {
            throw SyncNeedsSignInException("Drive scope was not granted")
        }
        result.accessToken ?: throw SyncNeedsSignInException("Drive authorization returned no token")
    }

    /**
     * The last step of connecting, once a token exists: learn whose account
     * this is and remember it in [AuthStore]. Returns the account email for
     * the page to show. There is no app-owned root to prepare — with the
     * full scope, pairs point at whatever folders the browser picks.
     */
    suspend fun finishConnect(context: Context, outcome: InteractiveOutcome.Token): String {
        val store = AuthStore(context.applicationContext)
        val api = DriveApi { outcome.accessToken }

        val account = runCatching { outcome.result.toGoogleSignInAccount() }.getOrNull()
        var email = account?.email
        var name = account?.displayName
        if (email.isNullOrBlank()) {
            val user = api.aboutUser()
            email = user.email
            name = name ?: user.displayName
        }
        val resolvedEmail = email?.takeIf { it.isNotBlank() }
            ?: throw DriveApiException(0, null, "Google did not say which account connected")
        store.driveAccountEmail = resolvedEmail
        store.driveAccountName = name
        return resolvedEmail
    }

    /**
     * Disconnects: revokes the grant with Play services where it can, and
     * forgets the account locally regardless. The pairs keep their folder
     * ids — they simply cannot run until a connect happens again.
     */
    suspend fun disconnect(context: Context) {
        val store = AuthStore(context.applicationContext)
        val email = store.driveAccountEmail
        if (email != null) {
            runCatching {
                val revoke = RevokeAccessRequest.builder()
                    .setScopes(listOf(Scope(SCOPE_DRIVE_FULL)))
                    .setAccount(android.accounts.Account(email, "com.google"))
                    .build()
                withContext(Dispatchers.IO) {
                    Tasks.await(Identity.getAuthorizationClient(context.applicationContext).revokeAccess(revoke))
                }
            }.onFailure { Log.w(TAG, "revokeAccess failed (local state still cleared): ${it.message}") }
        }
        store.clearDriveAccount()
    }
}

/**
 * What the Google Cloud console needs to know about this build, computed
 * from the installed package at runtime — never hardcoded, because the dev
 * build and any future release build differ in both values and a stale
 * constant would register the wrong app with complete confidence.
 */
object DriveSetup {

    fun packageName(context: Context): String = context.packageName

    /**
     * The SHA-1 of the certificate this installed build was signed with,
     * colon-separated uppercase hex — the exact format the console's
     * Android OAuth client form takes. Null only if the platform cannot
     * say, which on a normally installed build it always can.
     */
    fun sha1Fingerprint(context: Context): String? = runCatching {
        val manager = context.packageManager
        val packageName = context.packageName
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            val info = manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val certificate = signatures?.firstOrNull()?.toByteArray() ?: return null
        formatSha1(MessageDigest.getInstance("SHA-1").digest(certificate))
    }.getOrNull()

    /** Twenty digest bytes to colon-separated uppercase hex. Pure, and the part of the setup card a typo would hurt most — so it is tested. */
    fun formatSha1(digest: ByteArray): String =
        digest.joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
}
