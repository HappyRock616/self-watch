/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.data

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.selfwatch.ProcessRole
import app.selfwatch.interop.api.InputSource
import app.selfwatch.interop.api.OutputSink
import app.selfwatch.interop.backup.BackupManifest
import app.selfwatch.interop.backup.BackupRestore
import app.selfwatch.interop.backup.BackupWriter
import app.selfwatch.interop.backup.ServicePauser
import app.selfwatch.interop.hc.HcAvailability
import app.selfwatch.interop.hc.HcDirection
import app.selfwatch.interop.hc.HcPermissions
import app.selfwatch.interop.hc.HcStatus
import app.selfwatch.interop.hc.HcSync
import app.selfwatch.interop.hc.HcSyncReport
import app.selfwatch.interop.jobs.AutoJobs
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.service.SyncCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Backup and restore as the Data screen uses them (a seam for tests). */
interface BackupOps {
    /** Writes a backup to [out], encrypted when [passphrase] is given. */
    suspend fun write(out: OutputSink, passphrase: CharArray?): Outcome<BackupManifest>

    /** Reads and checks the backup in [src] without changing anything. */
    suspend fun inspect(src: InputSource, passphrase: CharArray?): Outcome<BackupManifest>

    /** Replaces everything with the backup in [src]. */
    suspend fun restore(src: InputSource, passphrase: CharArray?): Outcome<BackupManifest>

    /** Factory. */
    companion object {
        /** The real thing over [writer] and [restorer]. */
        fun of(writer: BackupWriter, restorer: BackupRestore): BackupOps = object : BackupOps {
            override suspend fun write(out: OutputSink, passphrase: CharArray?) = writer.write(out, passphrase)
            override suspend fun inspect(src: InputSource, passphrase: CharArray?) = restorer.inspect(src, passphrase)
            override suspend fun restore(src: InputSource, passphrase: CharArray?) = restorer.restore(src, passphrase)
        }
    }
}

/** Health Connect as the Data screen uses it (a seam for tests). */
interface HcOps {
    /** Availability on this phone. */
    fun status(): HcStatus

    /** The Health Connect permissions held now (empty when unavailable). */
    suspend fun granted(): Set<String>

    /** One foreground sync run (both directions). */
    suspend fun sync(): Outcome<HcSyncReport>
}

/** [HcOps] over the phone's Health Connect. */
class AndroidHcOps(private val ctx: Context) : HcOps {
    private val hcSync: HcSync by lazy { HcSync(ctx) }

    override fun status(): HcStatus = HcAvailability.status(ctx)

    override suspend fun granted(): Set<String> = if (HcAvailability.isAvailable(ctx)) HcPermissions.granted(ctx) else emptySet()

    override suspend fun sync(): Outcome<HcSyncReport> = hcSync.runOnce(HcDirection.BOTH, background = false)
}

/**
 * The backup passphrase kept for automatic encrypted backups. It is stored **only** when the user
 * asks for it, and then only encrypted with a key that never leaves the Android Keystore (see
 * [KeystorePassphraseStore]); manual backups always ask for the passphrase and never store it.
 */
interface PassphraseStore {
    /** True when a passphrase is stored. */
    fun isStored(): Boolean

    /** The stored passphrase, or null. The caller wipes the array after use. */
    suspend fun load(): CharArray?

    /** Stores [passphrase] (the caller keeps ownership of the array). */
    suspend fun save(passphrase: CharArray): Boolean

    /** Forgets the stored passphrase and deletes its key. */
    suspend fun clear()
}

/**
 * [PassphraseStore] backed by an AES-256-GCM key generated inside the Android Keystore (alias
 * [KEY_ALIAS], no user authentication so the backup job can use it in the background). Only the
 * ciphertext is written, to `no_backup/backup-passphrase.bin` (never part of any backup): one
 * version byte, the 12-byte nonce, then the GCM ciphertext of the UTF-8 passphrase. If the key is
 * lost (for example after a factory reset of the Keystore) [load] returns null and automatic
 * encrypted backups skip until the passphrase is entered again.
 */
class KeystorePassphraseStore(ctx: Context) : PassphraseStore {
    private val file = File(ctx.noBackupFilesDir, FILE_NAME)

    override fun isStored(): Boolean = file.isFile

    override suspend fun load(): CharArray? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        try {
            val all = file.readBytes()
            if (all.size <= 1 + NONCE_BYTES || all[0].toInt() != VERSION) return@withContext null
            val key = existingKey() ?: return@withContext null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, all, 1, NONCE_BYTES))
            val plain = cipher.doFinal(all, 1 + NONCE_BYTES, all.size - 1 - NONCE_BYTES)
            val chars = Charsets.UTF_8.decode(ByteBuffer.wrap(plain))
            plain.fill(0)
            CharArray(chars.remaining()).also { chars.get(it) }
        } catch (e: GeneralSecurityException) {
            SwLog.w(TAG, "stored passphrase cannot be decrypted", e)
            null
        } catch (e: IOException) {
            SwLog.w(TAG, "stored passphrase cannot be read", e)
            null
        }
    }

    override suspend fun save(passphrase: CharArray): Boolean = withContext(Dispatchers.IO) {
        var plain: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: newKey())
            val buf = Charsets.UTF_8.encode(CharBuffer.wrap(passphrase))
            plain = ByteArray(buf.remaining()).also { buf.get(it) }
            val ct = cipher.doFinal(plain)
            val iv = cipher.iv
            if (iv.size != NONCE_BYTES) return@withContext false
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeBytes(byteArrayOf(VERSION.toByte()) + iv + ct)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return@withContext false
            }
            true
        } catch (e: GeneralSecurityException) {
            SwLog.w(TAG, "passphrase not stored", e)
            false
        } catch (e: IOException) {
            SwLog.w(TAG, "passphrase not stored", e)
            false
        } finally {
            plain?.fill(0)
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            file.delete()
            try {
                keyStore().deleteEntry(KEY_ALIAS)
            } catch (e: GeneralSecurityException) {
                SwLog.w(TAG, "passphrase key not deleted", e)
            } catch (e: IOException) {
                SwLog.w(TAG, "passphrase key not deleted", e)
            }
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun existingKey(): SecretKey? = (keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun newKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    /** Constants. */
    companion object {
        /** Keystore alias of the wrapping key. */
        const val KEY_ALIAS: String = "selfwatch.backup.passphrase"
        private const val FILE_NAME = "backup-passphrase.bin"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION = 1
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_BITS = 256
        private const val TAG = "PassphraseStore"
    }
}

/**
 * The real [ServicePauser]: the `:watch` process holds its own database connection, so a restore
 * stops the service ([SyncCommands.stop], which disconnects the watch and marks it off), waits for it
 * to wind down, then ends the `:watch` process (same app, same user id) so no connection to the old
 * file survives. [resume] starts the service again if it was running.
 */
class WatchServicePauser(private val ctx: Context) : ServicePauser {
    @Volatile
    private var wasRunning = false

    override suspend fun pause(): Boolean {
        val pid = watchPid()
        wasRunning = pid != null
        if (pid == null) return true
        SyncCommands.stop(ctx)
        delay(STOP_WAIT_MS)
        watchPid()?.let { Process.killProcess(it) }
        repeat(GONE_POLLS) {
            if (watchPid() == null) return true
            delay(POLL_MS)
        }
        SwLog.w(TAG, "the :watch process did not end")
        return false
    }

    override suspend fun resume() {
        if (wasRunning && !SyncCommands.start(ctx)) SwLog.i(TAG, "service not restarted after restore")
    }

    private fun watchPid(): Int? {
        val name = ctx.packageName + ProcessRole.WATCH_SUFFIX
        return ctx.getSystemService(ActivityManager::class.java)?.runningAppProcesses
            ?.firstOrNull { it.processName == name }?.pid
    }

    private companion object {
        const val TAG = "WatchServicePauser"
        const val STOP_WAIT_MS = 2_500L
        const val POLL_MS = 100L
        const val GONE_POLLS = 30
    }
}

/**
 * Receives the service's "workout added" broadcast ([SyncCommands.BROADCAST_WORKOUT_ADDED], sent only
 * to this package) and schedules the automatic GPX export job ([AutoJobs.onWorkoutAdded]; the job
 * itself does nothing unless automatic GPX export is on).
 */
class WorkoutAddedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SyncCommands.BROADCAST_WORKOUT_ADDED) return
        val id = intent.getLongExtra(SyncCommands.EXTRA_WORKOUT_ID, 0L)
        if (id <= 0L) return
        if (!AutoJobs.onWorkoutAdded(context.applicationContext, id)) SwLog.w(TAG, "GPX export job not scheduled")
    }

    private companion object {
        const val TAG = "WorkoutAddedReceiver"
    }
}

/** Failure text for the user from an [Outcome.Fail]. */
internal fun SwError.shortText(): String = when (this) {
    is SwError.BadInput -> detail
    is SwError.Io -> detail
    is SwError.Timeout -> what
    is SwError.Blocked -> feature.name.lowercase()
    is SwError.PermissionMissing -> permission
    SwError.Offline -> "offline"
    SwError.WaitingForWifi -> "waiting for Wi-Fi"
    is SwError.RateLimited -> "rate limited"
    else -> toString()
}
