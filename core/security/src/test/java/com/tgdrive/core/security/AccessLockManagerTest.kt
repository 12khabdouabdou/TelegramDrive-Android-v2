package com.tgdrive.core.security

import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AccessLockManagerTest {

    private fun fakeKeystore(): KeystoreHelper {
        val store = mutableMapOf<String, String>()
        return object : KeystoreHelper(object : ContextWrapper(null) {}) {
            override fun getString(key: String, default: String?): String? = store[key] ?: default
            override fun putString(key: String, value: String): Boolean {
                store[key] = value
                return true
            }
            override fun remove(key: String): Boolean {
                store.remove(key)
                return true
            }
        }
    }

    @Test
    fun `setPassword validates minimum length verifies hash clears and overwrites`() {
        val manager = AccessLockManager(fakeKeystore())
        assertThat(manager.hasPassword()).isFalse()
        assertThat(manager.verifyPassword("1234")).isFalse()

        // Short / blank passwords rejected
        assertThat(manager.setPassword("123")).isFalse()
        assertThat(manager.setPassword("   ")).isFalse()
        assertThat(manager.hasPassword()).isFalse()

        // Valid password hashes with random salt and verifies properly (trimmed + unicode safe)
        assertThat(manager.setPassword("  s3cr3t-🔒  ")).isTrue()
        assertThat(manager.hasPassword()).isTrue()
        assertThat(manager.verifyPassword("s3cr3t-🔒")).isTrue()
        assertThat(manager.verifyPassword("wrong-pass")).isFalse()
        assertThat(manager.verifyPassword("")).isFalse()

        // Overwrite replaces old hash
        assertThat(manager.setPassword("9876")).isTrue()
        assertThat(manager.verifyPassword("s3cr3t-🔒")).isFalse()
        assertThat(manager.verifyPassword("9876")).isTrue()

        // Clear removes stored hash
        manager.clearPassword()
        assertThat(manager.hasPassword()).isFalse()
        assertThat(manager.verifyPassword("9876")).isFalse()
    }
}
