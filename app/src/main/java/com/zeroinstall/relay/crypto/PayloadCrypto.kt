package com.zeroinstall.relay.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end payload encryption.
 *
 * - Key: PBKDF2-HMAC-SHA256(sharedSecret, random 16-byte salt, 120k iterations) -> 256-bit AES key.
 * - Cipher: AES-256-GCM, random 12-byte IV, 128-bit tag.
 * - AAD: the recipient id, so ciphertext cannot be silently re-addressed to another recipient.
 *
 * The output is a compact JSON envelope (all binary fields Base64) that the recipient decrypts
 * with the same shared secret. The Helper and the cloud endpoint only ever see this envelope.
 */
class PayloadCrypto(
    private val random: SecureRandom = SecureRandom(),
    private val iterations: Int = DEFAULT_ITERATIONS,
) {

    @Serializable
    data class Envelope(
        val v: Int,
        val alg: String,
        val kdf: String,
        val iter: Int,
        val salt: String,
        val iv: String,
        val ct: String,
    )

    fun encrypt(plaintext: String, sharedSecret: CharArray, recipientId: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(sharedSecret, salt, iterations)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(recipientId.toByteArray(Charsets.UTF_8))
            val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val envelope = Envelope(
                v = VERSION,
                alg = "AES-256-GCM",
                kdf = "PBKDF2-HMAC-SHA256",
                iter = iterations,
                salt = b64(salt),
                iv = b64(iv),
                ct = b64(ct),
            )
            return json.encodeToString(envelope)
        } finally {
            Arrays.fill(key, 0)
        }
    }

    /** Recipient-side decryption. Throws [javax.crypto.AEADBadTagException] on wrong secret/tampering. */
    fun decrypt(envelopeJson: String, sharedSecret: CharArray, recipientId: String): String {
        val envelope = json.decodeFromString<Envelope>(envelopeJson)
        require(envelope.v == VERSION) { "Unsupported envelope version ${envelope.v}" }
        require(envelope.iter in MIN_ITERATIONS..MAX_ITERATIONS) { "Suspicious iteration count ${envelope.iter}" }
        val decoder = Base64.getDecoder()
        val key = deriveKey(sharedSecret, decoder.decode(envelope.salt), envelope.iter)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, decoder.decode(envelope.iv)),
            )
            cipher.updateAAD(recipientId.toByteArray(Charsets.UTF_8))
            return String(cipher.doFinal(decoder.decode(envelope.ct)), Charsets.UTF_8)
        } finally {
            Arrays.fill(key, 0)
        }
    }

    private fun deriveKey(secret: CharArray, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(secret, salt, rounds, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    companion object {
        const val SCHEME = "zir-e2e-v1:aes-256-gcm+pbkdf2-sha256"
        const val MIN_SECRET_LENGTH = 6
        const val DEFAULT_ITERATIONS = 120_000
        private const val MIN_ITERATIONS = 1_000
        private const val MAX_ITERATIONS = 5_000_000
        private const val VERSION = 1
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_BITS = 256
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
