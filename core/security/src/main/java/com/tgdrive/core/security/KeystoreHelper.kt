package com.tgdrive.core.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tgdrive.core.common.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

data class ApiCredentials(
    val apiId: String,
    val apiHash: String,
)

/**
 * Helpers for Android Keystore + EncryptedSharedPreferences with Samsung Knox recovery.
 *
 * Exposes typed safe methods ([getApiCreds], [saveApiCreds], [getString], [putString])
 * routed through [safeRead] / [safeWrite], performs eager canary verification inside
 * [openWithRecovery], prevents duplicate concurrent recovery wipes via a [generation]
 * counter, and separates [regenerateTdlibSessionKey] from full [clear].
 */
@Singleton
class KeystoreHelper @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    private val masterKeyAlias = MasterKey.DEFAULT_MASTER_KEY_ALIAS
    private val PREFS_NAME = "tgdrive_secure_prefs"
    private val FALLBACK_PREFS_NAME = "tgdrive_secure_prefs_fallback"

    @Volatile private var _prefs: SharedPreferences? = null
    @Volatile private var _encryptionAvailable: Boolean = true
    private var generation: Int = 0

    @Deprecated(
        message = "Use typed safe methods (getApiCreds/saveApiCreds/getString/putString)",
        replaceWith = ReplaceWith("getString(key, default)"),
    )
    val encryptedPrefs: SharedPreferences
        get() = openEncryptedPrefs(force = false)

    private fun openEncryptedPrefs(force: Boolean, expectedGeneration: Int = -1): SharedPreferences {
        if (!force) {
            _prefs?.let { return it }
        }
        synchronized(this) {
            if (!force) {
                _prefs?.let { return it }
            } else if (expectedGeneration != -1 && expectedGeneration != generation) {
                // Another thread already recovered this generation; reuse recovered instance.
                _prefs?.let { return it }
            }
            val opened = openWithRecovery(force)
            _prefs = opened
            return opened
        }
    }

    private fun openWithRecovery(force: Boolean): SharedPreferences {
        if (force) {
            resetSecureStorageInternal()
        }
        // Attempt 1: create + canary read so lazy AEADBadTagException surfaces here.
        try {
            val prefs = createEncryptedPrefs()
            prefs.getString(KEY_CANARY, null)
            _encryptionAvailable = true
            return prefs
        } catch (t: Throwable) {
            Log.w("KeystoreHelper: EncryptedSharedPreferences open/canary failed — attempting recovery", t)
        }
        // Attempt 2: wipe corrupted master key + prefs and retry cleanly.
        resetSecureStorageInternal()
        try {
            val prefs = createEncryptedPrefs()
            prefs.getString(KEY_CANARY, null)
            _encryptionAvailable = true
            return prefs
        } catch (t: Throwable) {
            Log.e("KeystoreHelper: recovery failed — falling back to PLAIN SharedPreferences (encryption disabled)", t)
            _encryptionAvailable = false
        }
        return ctx.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun currentGeneration(): Int = synchronized(this) { generation }

    private fun <T> safeRead(block: (SharedPreferences) -> T?): T? {
        val genBefore = currentGeneration()
        return try {
            block(openEncryptedPrefs(force = false))
        } catch (t: Throwable) {
            Log.w("KeystoreHelper: read failed — attempting recovery", t)
            try {
                val recovered = openEncryptedPrefs(force = true, expectedGeneration = genBefore)
                block(recovered)
            } catch (t2: Throwable) {
                Log.e("KeystoreHelper: read failed even after recovery", t2)
                null
            }
        }
    }

    private fun safeWrite(block: (SharedPreferences.Editor) -> Unit): Boolean {
        val genBefore = currentGeneration()
        return try {
            val editor = openEncryptedPrefs(force = false).edit()
            block(editor)
            editor.commit()
            true
        } catch (t: Throwable) {
            Log.w("KeystoreHelper: write failed — attempting recovery", t)
            try {
                val recovered = openEncryptedPrefs(force = true, expectedGeneration = genBefore)
                val editor = recovered.edit()
                block(editor)
                editor.commit()
                true
            } catch (t2: Throwable) {
                Log.e("KeystoreHelper: write failed even after recovery", t2)
                false
            }
        }
    }

    fun getString(key: String, default: String? = null): String? =
        safeRead { it.getString(key, default) } ?: default

    fun putString(key: String, value: String): Boolean =
        safeWrite { it.putString(key, value) }

    fun remove(key: String): Boolean =
        safeWrite { it.remove(key) }

    fun getApiCreds(): ApiCredentials? =
        safeRead { prefs ->
            val id = prefs.getString(KEY_API_ID, null)?.trim()
            val hash = prefs.getString(KEY_API_HASH, null)?.trim()
            if (!id.isNullOrEmpty() && !hash.isNullOrEmpty()) {
                ApiCredentials(apiId = id, apiHash = hash)
            } else {
                null
            }
        }

    fun saveApiCreds(apiId: String, apiHash: String): Boolean {
        val id = apiId.trim()
        val hash = apiHash.trim()
        return safeWrite { editor ->
            editor.putString(KEY_API_ID, id)
            editor.putString(KEY_API_HASH, hash)
        }
    }

    @Synchronized
    fun tdlibSessionKey(): ByteArray {
        val existing = safeRead { it.getString(KEY_TDLIB_SESSION_KEY, null) }
        if (existing != null) {
            try {
                val decoded = Base64.decode(existing, Base64.NO_WRAP)
                if (decoded.size == 32) {
                    return decoded
                }
                Log.w("KeystoreHelper: stored TDLib key invalid length (${decoded.size} bytes) — regenerating")
            } catch (t: Throwable) {
                Log.w("KeystoreHelper: stored TDLib key corrupt — regenerating", t)
            }
        }
        return generateAndStore()
    }

    private fun generateAndStore(): ByteArray {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val ok = safeWrite { it.putString(KEY_TDLIB_SESSION_KEY, Base64.encodeToString(key, Base64.NO_WRAP)) }
        if (!ok) {
            Log.e("KeystoreHelper: failed to persist TDLib session key — key is in-memory only, will be lost on process death")
        }
        return key
    }

    /**
     * Regenerate ONLY the TDLib session key, preserving stored API credentials.
     */
    @Synchronized
    fun regenerateTdlibSessionKey(): ByteArray {
        Log.i("KeystoreHelper: regenerating TDLib session key (preserving API creds)")
        safeWrite { it.remove(KEY_TDLIB_SESSION_KEY) }
        return generateAndStore()
    }

    @Synchronized
    fun resetSecureStorage() {
        resetSecureStorageInternal()
        _prefs = null
    }

    private fun resetSecureStorageInternal() {
        generation++
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(masterKeyAlias)) {
                keyStore.deleteEntry(masterKeyAlias)
                Log.i("KeystoreHelper: deleted master key alias '$masterKeyAlias'")
            }
        } catch (t: Throwable) {
            Log.w("KeystoreHelper: failed to delete master key", t)
        }
        try {
            ctx.deleteSharedPreferences(PREFS_NAME)
            Log.i("KeystoreHelper: deleted prefs '$PREFS_NAME'")
        } catch (t: Throwable) {
            Log.w("KeystoreHelper: deleteSharedPreferences failed", t)
        }
        try {
            ctx.deleteSharedPreferences(FALLBACK_PREFS_NAME)
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun clear() {
        resetSecureStorage()
    }

    val isEncryptionAvailable: Boolean get() = _encryptionAvailable

    companion object {
        const val KEY_API_ID = "telegram_api_id"
        const val KEY_API_HASH = "telegram_api_hash"
        private const val KEY_CANARY = "_keystore_canary"
        private const val KEY_TDLIB_SESSION_KEY = "tdlib_session_key_b64"
    }
}
