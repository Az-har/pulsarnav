package com.pulsarnav.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultCryptoTest {

    @Test
    fun encryptAndDecrypt_roundTripSucceeds() {
        val secretJson = """{"header":{"schemaVersion":2},"settings":{"rider_name":"Aarav"}}"""
        val encrypted = VaultCrypto.encrypt(secretJson, "MyStrongPass#2026", iterations = 10_000)

        assertTrue(VaultCrypto.isEncryptedPayload(encrypted))
        val decrypted = VaultCrypto.decrypt(encrypted, "MyStrongPass#2026")
        assertTrue(decrypted.isSuccess)
        assertEquals(secretJson, decrypted.getOrNull())
    }

    @Test
    fun decrypt_wrongPassphraseReturnsSecurityFailure() {
        val encrypted = VaultCrypto.encrypt("{\"ok\":true}", "correct-horse", iterations = 10_000)
        val result = VaultCrypto.decrypt(encrypted, "wrong-horse")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun decrypt_tamperedCiphertextFails() {
        val encrypted = VaultCrypto.encrypt("{\"ok\":true}", "secret", iterations = 10_000)
        val obj = JSONObject(encrypted)
        val raw = obj.getString("ciphertext")
        val flippedChar = if (raw[0] == 'A') 'B' else 'A'
        obj.put("ciphertext", flippedChar + raw.substring(1))

        val result = VaultCrypto.decrypt(obj.toString(), "secret")
        assertTrue(result.isFailure)
    }

    @Test
    fun decrypt_rejectsOutOfRangeIterations() {
        val encrypted = VaultCrypto.encrypt("{\"ok\":true}", "secret", iterations = 10_000)
        val obj = JSONObject(encrypted).apply { put("iterations", 1) }

        val result = VaultCrypto.decrypt(obj.toString(), "secret")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun isEncryptedPayload_handlesPlaintextJson() {
        assertFalse(VaultCrypto.isEncryptedPayload("""{"header":{"schemaVersion":1}}"""))
        assertFalse(VaultCrypto.isEncryptedPayload("not json"))
    }
}
