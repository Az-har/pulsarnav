package com.pulsarnav.app.data

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-JVM authenticated encryption (PBKDF2-HMAC-SHA256 + AES-256-GCM) for `.pnav` Vault exports.
 * Requires Android API 26+ (`java.util.Base64` and `AES/GCM/NoPadding`).
 */
object VaultCrypto {

    const val FORMAT_V1 = "PNAV_ENC_V1"
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
    private const val DEFAULT_ITERATIONS = 120_000
    private const val MIN_ITERATIONS = 10_000
    private const val MAX_ITERATIONS = 1_000_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_SIZE_BYTES = 16
    private const val IV_SIZE_BYTES = 12
    private const val GCM_TAG_BITS = 128

    fun isEncryptedPayload(raw: String): Boolean {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return false
        return try {
            JSONObject(trimmed).optString("format") == FORMAT_V1
        } catch (_: Exception) {
            false
        }
    }

    fun encrypt(plaintext: String, passphrase: String, iterations: Int = DEFAULT_ITERATIONS): String {
        require(passphrase.isNotEmpty()) { "Passphrase cannot be empty." }
        val safeIterations = iterations.coerceIn(MIN_ITERATIONS, MAX_ITERATIONS)

        val rng = SecureRandom()
        val salt = ByteArray(SALT_SIZE_BYTES).also { rng.nextBytes(it) }
        val iv = ByteArray(IV_SIZE_BYTES).also { rng.nextBytes(it) }
        val key = deriveKey(passphrase, salt, safeIterations)

        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

        val encoder = Base64.getEncoder()
        return JSONObject().apply {
            put("format", FORMAT_V1)
            put("kdf", "PBKDF2WithHmacSHA256")
            put("iterations", safeIterations)
            put("salt", encoder.encodeToString(salt))
            put("iv", encoder.encodeToString(iv))
            put("ciphertext", encoder.encodeToString(ciphertext))
        }.toString(2)
    }

    fun decrypt(envelopeJson: String, passphrase: String): Result<String> {
        if (passphrase.isEmpty()) {
            return Result.failure(IllegalArgumentException("Passphrase is required to decrypt this vault backup."))
        }
        return try {
            val obj = JSONObject(envelopeJson)
            if (obj.optString("format") != FORMAT_V1) {
                return Result.failure(IllegalArgumentException("Unsupported encrypted vault format."))
            }
            val iterations = obj.optInt("iterations", DEFAULT_ITERATIONS)
            if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
                return Result.failure(IllegalArgumentException("Invalid vault key derivation iteration count."))
            }
            val decoder = Base64.getDecoder()
            val salt = decoder.decode(obj.getString("salt"))
            val iv = decoder.decode(obj.getString("iv"))
            val ciphertext = decoder.decode(obj.getString("ciphertext"))

            val key = deriveKey(passphrase, salt, iterations)
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plainBytes = cipher.doFinal(ciphertext)
            Result.success(String(plainBytes, StandardCharsets.UTF_8))
        } catch (_: AEADBadTagException) {
            Result.failure(SecurityException("Incorrect vault passphrase or damaged backup file."))
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Failed to decrypt vault: ${e.message}"))
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val chars = passphrase.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, KEY_LENGTH_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
            val encoded = factory.generateSecret(spec).encoded
            SecretKeySpec(encoded, "AES")
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }
}
