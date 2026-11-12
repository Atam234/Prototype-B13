package com.zeroinstall.relay.data

import com.zeroinstall.relay.crypto.PayloadCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

enum class AckOutcome { DELIVERED, ALREADY_DELIVERED, NOT_FOUND }

class MessageRepository(
    private val dao: MessageDao,
    private val crypto: PayloadCrypto,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun observeMessages(): Flow<List<MessageEntity>> = dao.observeAll()

    /** Encrypts [plaintext] for [recipientId] with [sharedSecret] and queues it as PENDING. */
    suspend fun queueMessage(recipientId: String, plaintext: String, sharedSecret: CharArray): MessageEntity {
        val normalizedRecipient = recipientId.trim()
        require(normalizedRecipient.isNotEmpty()) { "Recipient is required" }
        require(plaintext.isNotBlank()) { "Message is empty" }
        require(sharedSecret.size >= PayloadCrypto.MIN_SECRET_LENGTH) {
            "Shared secret must be at least ${PayloadCrypto.MIN_SECRET_LENGTH} characters"
        }
        val envelope = withContext(Dispatchers.Default) {
            crypto.encrypt(plaintext, sharedSecret, normalizedRecipient)
        }
        val now = clock()
        val entity = MessageEntity(
            id = UUID.randomUUID().toString(),
            recipientId = normalizedRecipient,
            encryptedPayload = envelope,
            encryptionScheme = PayloadCrypto.SCHEME,
            plaintextLength = plaintext.length,
            createdAt = now,
            updatedAt = now,
            status = MessageStatus.PENDING,
        )
        dao.insert(entity)
        return entity
    }

    suspend fun undelivered(): List<MessageEntity> = dao.getUndelivered()

    suspend fun countUndelivered(): Int = dao.countUndelivered()

    /** Called when a Helper's browser has downloaded the messages for relaying. */
    suspend fun markHandedToHelper(ids: List<String>) {
        if (ids.isNotEmpty()) dao.markRelayed(ids, clock())
    }

    suspend fun markDelivered(id: String, via: String, receipt: String?): AckOutcome {
        val existing = dao.getById(id) ?: return AckOutcome.NOT_FOUND
        if (existing.status == MessageStatus.DELIVERED) return AckOutcome.ALREADY_DELIVERED
        val updated = dao.markDelivered(id, via, receipt?.take(MAX_RECEIPT_LENGTH), clock())
        return if (updated > 0) AckOutcome.DELIVERED else AckOutcome.ALREADY_DELIVERED
    }

    /** Helper reported its upload failed: put the message back to PENDING with the reason. */
    suspend fun markRelayFailed(id: String, reason: String): Boolean =
        dao.markRelayFailed(id, reason.take(MAX_ERROR_LENGTH), clock()) > 0

    suspend fun recordError(id: String, reason: String) {
        dao.recordError(id, reason.take(MAX_ERROR_LENGTH), clock())
    }

    suspend fun clearDelivered(): Int = dao.deleteDelivered()

    private companion object {
        const val MAX_RECEIPT_LENGTH = 300
        const val MAX_ERROR_LENGTH = 300
    }
}
