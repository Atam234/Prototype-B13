package com.zeroinstall.relay.server

import com.zeroinstall.relay.data.AckOutcome
import com.zeroinstall.relay.data.MessageRepository
import com.zeroinstall.relay.service.RelayStateHolder
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Embedded Ktor (CIO) server bound to 0.0.0.0 so any device on the hotspot can reach it.
 *
 * Routes:
 *  GET  /                       relay portal (assets/relay.html) with a per-session token injected
 *  GET  /api/health             liveness + undelivered count
 *  GET  /api/config             cloud endpoint
 *  GET  /api/pending            undelivered messages (alias: /api/pending-messages); marks them RELAYED_VIA_HELPER
 *  POST /api/ack/{id}           helper's cloud upload succeeded -> DELIVERED
 *  POST /api/ack-message        same, id in JSON body
 *  POST /api/relay-failed/{id}  helper's cloud upload failed -> back to PENDING with reason
 *
 * API calls must carry the `X-Relay-Token` header. The token is only obtainable by loading the
 * portal page from this server, so other web origins the Helper visits cannot read or ack messages.
 */
class LocalRelayServer(
    private val repository: MessageRepository,
    /** Supplies the portal HTML template (on Android: assets/relay.html). */
    portalLoader: () -> String,
    /** Current cloud endpoint, read on every request so UI changes apply immediately. */
    private val cloudEndpoint: () -> String,
) {
    @Volatile
    private var engine: ApplicationEngine? = null

    @Volatile
    var sessionToken: String = newToken()
        private set

    val isRunning: Boolean get() = engine != null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val portalTemplate: String by lazy(portalLoader)

    /** Blocking: binds the port. Call from a background dispatcher. */
    @Synchronized
    fun start(port: Int) {
        if (engine != null) return
        sessionToken = newToken()
        val server = embeddedServer(CIO, port = port, host = "0.0.0.0") { relayModule() }
        server.start(wait = false)
        engine = server
    }

    /** Blocking: waits up to ~1.5s for in-flight requests. */
    @Synchronized
    fun stop() {
        engine?.stop(gracePeriodMillis = 300, timeoutMillis = 1_500)
        engine = null
    }

    private fun Application.relayModule() {
        install(ContentNegotiation) { json(this@LocalRelayServer.json) }

        install(CORS) {
            anyHost()
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Options)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(TOKEN_HEADER)
            allowNonSimpleContentTypes = true
            maxAgeInSeconds = 600
        }

        install(StatusPages) {
            exception<Throwable> { call, cause ->
                RelayStateHolder.log("Server error on ${call.request.local.uri}: ${cause.message}")
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiError("internal_error", cause.message ?: cause.javaClass.simpleName),
                )
            }
        }

        routing {
            get("/") { call.respondPortal() }
            get("/index.html") { call.respondPortal() }
            get("/relay.html") { call.respondPortal() }

            get("/api/health") {
                call.noStore()
                call.respond(
                    HealthResponse("ok", "ZeroInstallRelay", repository.countUndelivered(), System.currentTimeMillis())
                )
            }

            get("/api/config") {
                if (!call.authorized()) return@get
                call.noStore()
                call.respond(ConfigResponse(cloudEndpoint(), System.currentTimeMillis()))
            }

            get("/api/pending") { call.handlePending() }
            get("/api/pending-messages") { call.handlePending() }

            post("/api/ack/{id}") {
                if (!call.authorized()) return@post
                val body = call.receiveJsonOrNull<AckRequest>()
                val id = call.parameters["id"].orEmpty()
                call.handleAck(id, body)
            }

            post("/api/ack-message") {
                if (!call.authorized()) return@post
                val body = call.receiveJsonOrNull<AckRequest>()
                call.handleAck(body?.id.orEmpty(), body)
            }

            post("/api/relay-failed/{id}") {
                if (!call.authorized()) return@post
                val id = call.parameters["id"].orEmpty()
                val reason = call.receiveJsonOrNull<FailureReport>()?.reason?.take(200) ?: "unspecified"
                val updated = repository.markRelayFailed(id, "Helper upload failed: $reason")
                RelayStateHolder.log("Helper ${call.helperAddress()} could not upload ${id.take(8)}: $reason")
                call.noStore()
                call.respond(
                    if (updated) HttpStatusCode.OK else HttpStatusCode.NotFound,
                    mapOf("id" to id, "status" to if (updated) "PENDING" else "UNKNOWN"),
                )
            }

            // Explicit OPTIONS fallback for clients that preflight without an Origin header.
            options("/{path...}") { call.respond(HttpStatusCode.NoContent) }
        }
    }

    private suspend fun ApplicationCall.respondPortal() {
        noStore()
        response.header("X-Content-Type-Options", "nosniff")
        response.header("Referrer-Policy", "no-referrer")
        response.header(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
                "img-src 'self' data:; connect-src *; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
        )
        RelayStateHolder.log("Helper ${helperAddress()} opened the relay portal")
        respondText(portalTemplate.replace(TOKEN_PLACEHOLDER, sessionToken), ContentType.Text.Html)
    }

    private suspend fun ApplicationCall.handlePending() {
        if (!authorized()) return
        noStore()
        val messages = repository.undelivered()
        repository.markHandedToHelper(messages.map { it.id })
        if (messages.isNotEmpty()) {
            RelayStateHolder.log("Handed ${messages.size} encrypted message(s) to helper ${helperAddress()}")
        }
        respond(
            PendingResponse(
                cloudEndpoint = cloudEndpoint(),
                serverTime = System.currentTimeMillis(),
                count = messages.size,
                messages = messages.map(RelayMessageDto::from),
            )
        )
    }

    private suspend fun ApplicationCall.handleAck(id: String, body: AckRequest?) {
        noStore()
        if (id.isBlank() || id.length > 64) {
            respond(HttpStatusCode.BadRequest, ApiError("bad_request", "Missing or invalid message id"))
            return
        }
        val helper = helperAddress()
        val receipt = buildString {
            append("helper=").append(helper)
            body?.cloudStatus?.let { append(" cloudStatus=").append(it) }
            body?.cloudReceipt?.let { append(" receipt=").append(it.take(200)) }
        }
        when (repository.markDelivered(id, "HELPER $helper", receipt)) {
            AckOutcome.DELIVERED -> {
                RelayStateHolder.log("Message ${id.take(8)} DELIVERED via helper $helper")
                respond(AckResponse(id, "DELIVERED", alreadyDelivered = false))
            }
            AckOutcome.ALREADY_DELIVERED -> respond(AckResponse(id, "DELIVERED", alreadyDelivered = true))
            AckOutcome.NOT_FOUND -> respond(HttpStatusCode.NotFound, ApiError("not_found", "Unknown message $id"))
        }
    }

    private suspend fun ApplicationCall.authorized(): Boolean {
        val provided = request.headers[TOKEN_HEADER]
        if (provided != null &&
            MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), sessionToken.toByteArray(Charsets.UTF_8))
        ) {
            return true
        }
        noStore()
        respond(
            HttpStatusCode.Unauthorized,
            ApiError("invalid_token", "Relay session token missing or expired. Reload the relay page."),
        )
        return false
    }

    private suspend inline fun <reified T> ApplicationCall.receiveJsonOrNull(): T? {
        val text = runCatching { receiveText() }.getOrNull()
        if (text.isNullOrBlank() || text.length > MAX_BODY_CHARS) return null
        return runCatching { json.decodeFromString<T>(text) }.getOrNull()
    }

    private fun ApplicationCall.noStore() {
        response.header(HttpHeaders.CacheControl, "no-store")
    }

    private fun ApplicationCall.helperAddress(): String = request.origin.remoteAddress

    companion object {
        const val PORTAL_ASSET = "relay.html"
        private const val TOKEN_PLACEHOLDER = "__RELAY_TOKEN__"
        private const val TOKEN_HEADER = "X-Relay-Token"
        private const val MAX_BODY_CHARS = 8_192

        private fun newToken(): String {
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
