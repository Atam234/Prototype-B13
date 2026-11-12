package com.zeroinstall.relay.server

import com.zeroinstall.relay.data.MessageEntity
import kotlinx.serialization.Serializable

@Serializable
data class RelayMessageDto(
    val id: String,
    val recipientId: String,
    val encryptedPayload: String,
    val encryptionScheme: String,
    val createdAt: Long,
    val status: String,
    val relayAttempts: Int,
) {
    companion object {
        fun from(e: MessageEntity) = RelayMessageDto(
            id = e.id,
            recipientId = e.recipientId,
            encryptedPayload = e.encryptedPayload,
            encryptionScheme = e.encryptionScheme,
            createdAt = e.createdAt,
            status = e.status.name,
            relayAttempts = e.relayAttempts,
        )
    }
}

@Serializable
data class PendingResponse(
    val cloudEndpoint: String,
    val serverTime: Long,
    val count: Int,
    val messages: List<RelayMessageDto>,
)

@Serializable
data class AckRequest(
    val id: String? = null,
    val cloudStatus: Int? = null,
    val cloudReceipt: String? = null,
)

@Serializable
data class AckResponse(val id: String, val status: String, val alreadyDelivered: Boolean)

@Serializable
data class FailureReport(val reason: String? = null)

@Serializable
data class HealthResponse(val status: String, val app: String, val undelivered: Int, val serverTime: Long)

@Serializable
data class ConfigResponse(val cloudEndpoint: String, val serverTime: Long)

@Serializable
data class ApiError(val error: String, val message: String)
