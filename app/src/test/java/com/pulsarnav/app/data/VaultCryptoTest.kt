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

    @Test
    fun qrMigrationCodec_stripsSensitivePiiFromPlaintextAndSupportsEncryptedQr() {
        val fullFields = JSONObject().apply {
            put("r", "Aarav Rider")
            put("p", "+919999900000")
            put("bg", "O+")
            put("in", "Mom")
            put("ip", "+918888800000")
            put("mn", "Allergic to penicillin")
            put("mac", "AA:BB:CC:DD:EE:FF")
            put("reg", "MH12AB1234")
            put("ha", "221B Baker St")
            put("wa", "Tech Park")
            put("bn", "Pulsar NS400Z")
            put("mid", "ns400z")
            put("cc", "IN")
            put("odo", 4200.0)
        }

        val safeQr = QrMigrationCodec.buildPayload(fullFields, includeSensitiveProfile = false, passphrase = null)
        val safeObj = QrMigrationCodec.decodePayload(safeQr).getOrThrow()
        assertEquals(QrMigrationCodec.QR_MAGIC_HEADER, safeObj.getString("m"))
        assertEquals("Pulsar NS400Z", safeObj.getString("bn"))
        assertEquals("ns400z", safeObj.getString("mid"))
        assertFalse(safeObj.has("r"))
        assertFalse(safeObj.has("p"))
        assertFalse(safeObj.has("bg"))
        assertFalse(safeObj.has("in"))
        assertFalse(safeObj.has("ip"))
        assertFalse(safeObj.has("mn"))
        assertFalse(safeObj.has("ha"))
        assertFalse(safeObj.has("wa"))
        assertFalse(safeObj.has("reg"))
        assertFalse(safeObj.has("mac"))

        val encryptedQr = QrMigrationCodec.buildPayload(fullFields, includeSensitiveProfile = true, passphrase = "RideSafe#42")
        assertTrue(JSONObject(encryptedQr).getString("m") == QrMigrationCodec.QR_ENCRYPTED_HEADER)

        val noPassResult = QrMigrationCodec.decodePayload(encryptedQr, passphrase = null)
        assertTrue(noPassResult.isFailure)
        assertTrue(noPassResult.exceptionOrNull() is VaultPassphraseRequiredException)

        val wrongPassResult = QrMigrationCodec.decodePayload(encryptedQr, passphrase = "wrong")
        assertTrue(wrongPassResult.isFailure)
        assertTrue(wrongPassResult.exceptionOrNull() is SecurityException)

        val unlocked = QrMigrationCodec.decodePayload(encryptedQr, passphrase = "RideSafe#42").getOrThrow()
        assertEquals("Aarav Rider", unlocked.getString("r"))
        assertEquals("+918888800000", unlocked.getString("ip"))
        assertEquals("Allergic to penicillin", unlocked.getString("mn"))
        assertEquals("Pulsar NS400Z", unlocked.getString("bn"))
    }
}
