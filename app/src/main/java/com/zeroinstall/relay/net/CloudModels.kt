package com.zeroinstall.relay.net

import com.zeroinstall.relay.data.MessageEntity
import kotlinx.serialization.Serializable

/**
 * Body POSTed to the cloud endpoint. The Helper's browser (assets/relay.html) builds exactly the
 * same shape, so a cloud backend handles both delivery channels identically and can de-duplicate
 * on [idempotencyKey] (a message may legitimately arrive through both channels).
 */
@Serializable
data class CloudEnvelope(
    val relay: String = "ZeroInstallRelay",
    val version: Int = 1,
    val channel: String,
    val idempotencyKey: String,
    val relayedAt: Long,
    val message: CloudMessage,
) {
    companion object {
        fun from(entity: MessageEntity, channel: String, now: Long = System.currentTimeMillis()) = CloudEnvelope(
            channel = channel,
            idempotencyKey = entity.id,
            relayedAt = now,
            message = CloudMessage(
                id = entity.id,
                recipientId = entity.recipientId,
                encryptedPayload = entity.encryptedPayload,
                encryptionScheme = entity.encryptionScheme,
                createdAt = entity.createdAt,
            ),
        )
    }
}

@Serializable
data class CloudMessage(
    val id: String,
    val recipientId: String,
    val encryptedPayload: String,
    val encryptionScheme: String,
    val createdAt: Long,
)
