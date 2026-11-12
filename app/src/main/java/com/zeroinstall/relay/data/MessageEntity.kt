package com.zeroinstall.relay.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Lifecycle of an outgoing message: PENDING -> RELAYED_VIA_HELPER -> DELIVERED. */
enum class MessageStatus(val label: String) {
    PENDING("PENDING"),
    RELAYED_VIA_HELPER("RELAYED VIA HELPER"),
    DELIVERED("DELIVERED"),
}

/**
 * An outgoing message queued on the offline Sender.
 *
 * Only ciphertext is persisted: [encryptedPayload] is an AES-256-GCM envelope produced by
 * [com.zeroinstall.relay.crypto.PayloadCrypto]. Neither the Helper nor the cloud relay can read it.
 */
@Entity(
    tableName = "outgoing_messages",
    indices = [Index(value = ["status"]), Index(value = ["createdAt"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val recipientId: String,
    val encryptedPayload: String,
    val encryptionScheme: String,
    val plaintextLength: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val status: MessageStatus,
    val relayAttempts: Int = 0,
    /** Who handed the message to the cloud, e.g. "HELPER 192.168.49.23" or "DIRECT_SYNC". */
    val deliveredVia: String? = null,
    val deliveredAt: Long? = null,
    val cloudReceipt: String? = null,
    val lastError: String? = null,
)
