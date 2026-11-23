/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.link

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import app.selfwatch.db.dao.LinkDao
import app.selfwatch.db.dao.LinkPermission
import app.selfwatch.link.client.LinkStatus
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Clock
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The answer of [LinkPermissions] for one call. */
public sealed interface LinkDecision {
    /** [packageName] may talk to the watch app. */
    public data class Allowed(val packageName: String) : LinkDecision

    /** Nobody decided yet; the approval notification is showing. */
    public data object Pending : LinkDecision

    /** Refused; [reason] is for the log only. */
    public data class Denied(val reason: String) : LinkDecision

    /** The `LinkStatus` code for this decision (SUCCESS, PENDING_APPROVAL or DENIED). */
    public val status: Int
        get() = when (this) {
            is Allowed -> LinkStatus.SUCCESS
            Pending -> LinkStatus.PENDING_APPROVAL
            is Denied -> LinkStatus.DENIED
        }
}

/** One approval question: may [packageName] (null = an unknown app) message watch app [appUuid]? */
public data class ApprovalRequest(
    /** The asking package, or null for a Gadgetbridge Intent whose sender Android does not tell. */
    val packageName: String?,
    /** The watch app. */
    val appUuid: UUID,
    /** The asking package's signing certificate digest (empty for an unknown app). */
    val certSha256: String,
)

/** Shows (and withdraws) approval questions. The real one is [NotificationApprovalPrompt]. */
public interface ApprovalPrompt {
    /** Asks the user about [request]; the answer comes back through [LinkPermissions.decide]. */
    public suspend fun ask(request: ApprovalRequest)

    /** Withdraws the question for [packageName] (null = unknown app) and [appUuid], if shown. */
    public fun cancel(packageName: String?, appUuid: UUID)
}

/** Reads a package's signing certificates as one SHA-256 text. */
public fun interface SignerReader {
    /** The certificate digest of [packageName], or null when it is not installed or unsigned. */
    public fun certSha256(packageName: String): String?
}

/**
 * [SignerReader] on [PackageManager]: `GET_SIGNING_CERTIFICATES` (the APK contents signers) on API 28+,
 * `GET_SIGNATURES` below. Each certificate is hashed with SHA-256 (lower-case hex); several signers are
 * sorted and joined with commas, so the text is stable.
 */
public class PackageSignerReader(private val pm: PackageManager) : SignerReader {
    override fun certSha256(packageName: String): String? {
        val sigs = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) newSigners(packageName) else oldSigners(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            SwLog.d(TAG) { "package not visible: $e" }
            null
        }
        if (sigs.isNullOrEmpty()) return null
        return digest(sigs.map { it.toByteArray() })
    }

    private fun newSigners(pkg: String): Array<Signature>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val info = packageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        return info.signingInfo?.apkContentsSigners
    }

    @Suppress("DEPRECATION") // API 26-27 have only GET_SIGNATURES
    private fun oldSigners(pkg: String): Array<Signature>? = packageInfo(pkg, PackageManager.GET_SIGNATURES).signatures

    private fun packageInfo(pkg: String, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, flags)
        }

    /** Helpers. */
    public companion object {
        private const val TAG = "LinkSigners"

        /** The digest text for certificates [certs] (each DER bytes). */
        public fun digest(certs: List<ByteArray>): String = certs.map { sha256Hex(it) }.sorted().joinToString(",")

        private fun sha256Hex(b: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }
    }
}

/**
 * Who may use the link (F05). On each call: calling uid → package name(s) → signing certificate
 * ([SignerReader]) → the `link_permission` row for (package, watch app):
 * - row allowed and same certificate → [LinkDecision.Allowed] (the use is recorded);
 * - row with another certificate → always [LinkDecision.Denied] (a look-alike app);
 * - row denied → [LinkDecision.Denied];
 * - no row → the approval question is shown ([ApprovalPrompt], at most once per [askAgainSec]) →
 *   [LinkDecision.Pending].
 *
 * Gadgetbridge Intents whose sender is unknown use the pseudo package [UNKNOWN_PACKAGE] with an empty
 * certificate ([checkUnknownSender]). Revoking (the Linked phone apps screen) deletes the row, so the
 * app is asked again next time.
 */
public class LinkPermissions(
    private val pm: PackageManager,
    private val links: LinkDao,
    private val clock: Clock,
    private val prompt: ApprovalPrompt,
    private val signers: SignerReader = PackageSignerReader(pm),
    private val askAgainSec: Long = ASK_AGAIN_SEC,
) {
    private val pending = ConcurrentHashMap<Pair<String, UUID>, Long>()

    /** The packages of [uid] (several for a shared uid), or empty. */
    public fun packagesForUid(uid: Int): List<String> = pm.getPackagesForUid(uid)?.toList().orEmpty()

    /** The decision for a call from [uid] about watch app [appUuid]. */
    public suspend fun checkUid(uid: Int, appUuid: UUID): LinkDecision {
        val pkgs = packagesForUid(uid)
        if (pkgs.isEmpty()) return LinkDecision.Denied("no package for uid $uid")
        var firstSigned: Pair<String, String>? = null
        for (pkg in pkgs) {
            val cert = signers.certSha256(pkg) ?: continue
            if (firstSigned == null) firstSigned = pkg to cert
            val row = links.get(pkg, appUuid) ?: continue
            return judge(row, cert)
        }
        val (pkg, cert) = firstSigned ?: return LinkDecision.Denied("caller has no signing certificate")
        return askFor(pkg, appUuid, cert)
    }

    /** The decision for [packageName] (a Gadgetbridge Intent whose sender Android named). */
    public suspend fun checkPackage(packageName: String, appUuid: UUID): LinkDecision {
        val cert = signers.certSha256(packageName) ?: return LinkDecision.Denied("$packageName has no signing certificate")
        val row = links.get(packageName, appUuid) ?: return askFor(packageName, appUuid, cert)
        return judge(row, cert)
    }

    /** The decision for a Gadgetbridge Intent from an unknown app about [appUuid]. */
    public suspend fun checkUnknownSender(appUuid: UUID): LinkDecision {
        val row = links.get(UNKNOWN_PACKAGE, appUuid) ?: return askFor(UNKNOWN_PACKAGE, appUuid, "")
        return judge(row, "")
    }

    /** Stores the user's answer (from the approval notification) and withdraws the question. */
    public suspend fun decide(packageName: String, appUuid: UUID, certSha256: String, allowed: Boolean) {
        links.set(LinkPermission(packageName, appUuid, certSha256, allowed, clock.nowSec(), null))
        pending.remove(packageName to appUuid)
        prompt.cancel(packageName.takeIf { it != UNKNOWN_PACKAGE }, appUuid)
        SwLog.i(TAG, "${if (allowed) "allowed" else "denied"} $packageName for watch app $appUuid")
    }

    /** Forgets the decision for [packageName] and [appUuid] (the app is asked again next time). */
    public suspend fun revoke(packageName: String, appUuid: UUID): Boolean = links.delete(packageName, appUuid)

    /** The real packages allowed to talk to [appUuid] (where watch messages are broadcast). */
    public suspend fun approvedPackages(appUuid: UUID): List<String> =
        links.list().filter { it.appUuid == appUuid && it.allowed && it.packageName != UNKNOWN_PACKAGE }.map { it.packageName }

    private suspend fun judge(row: LinkPermission, cert: String): LinkDecision = when {
        row.certSha256 != cert -> {
            SwLog.w(TAG, "${row.packageName}: signing certificate differs from the approved one; denied")
            LinkDecision.Denied("certificate mismatch")
        }
        !row.allowed -> LinkDecision.Denied("denied by the user")
        else -> {
            links.touch(row.packageName, row.appUuid, clock.nowSec())
            LinkDecision.Allowed(row.packageName)
        }
    }

    private suspend fun askFor(pkg: String, appUuid: UUID, cert: String): LinkDecision {
        val now = clock.nowSec()
        val key = pkg to appUuid
        val last = pending[key]
        if (last == null || now - last >= askAgainSec) {
            pending[key] = now
            SwLog.i(TAG, "asking the user about $pkg for watch app $appUuid")
            prompt.ask(ApprovalRequest(pkg.takeIf { it != UNKNOWN_PACKAGE }, appUuid, cert))
        }
        return LinkDecision.Pending
    }

    /** Constants. */
    public companion object {
        private const val TAG = "LinkPermissions"

        /** The `link_permission.package` used for Gadgetbridge Intents from an unknown sender. */
        public const val UNKNOWN_PACKAGE: String = "?"

        /** A question still unanswered after this many seconds is shown again on the next call. */
        public const val ASK_AGAIN_SEC: Long = 10 * 60
    }
}
