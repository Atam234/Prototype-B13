package com.zeroinstall.relay.net

import com.zeroinstall.relay.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UploadResult(val httpCode: Int, val body: String?, val error: String? = null) {
    val isSuccess: Boolean get() = httpCode in 200..299
    fun describe(): String = when {
        isSuccess -> "HTTP $httpCode"
        httpCode > 0 -> "HTTP $httpCode ${body.orEmpty().take(120)}".trim()
        else -> "network error: ${error ?: "unknown"}"
    }
}

/** Direct Sender -> cloud upload, used by the auto-sync worker once the Sender has internet. */
class CloudUploader(private val json: Json) {

    suspend fun upload(endpoint: String, message: MessageEntity, channel: String): UploadResult =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(CloudEnvelope.from(message, channel)).toByteArray(Charsets.UTF_8)
            val connection = try {
                URL(endpoint).openConnection() as HttpURLConnection
            } catch (e: Exception) {
                return@withContext UploadResult(-1, null, e.message ?: e.javaClass.simpleName)
            }
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.doOutput = true
                connection.instanceFollowRedirects = false
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Idempotency-Key", message.id)
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val buffer = CharArray(MAX_BODY_CHARS)
                    val read = reader.read(buffer)
                    if (read > 0) String(buffer, 0, read) else ""
                }
                UploadResult(code, text)
            } catch (e: IOException) {
                UploadResult(-1, null, e.message ?: e.javaClass.simpleName)
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_BODY_CHARS = 2_048
    }
}
