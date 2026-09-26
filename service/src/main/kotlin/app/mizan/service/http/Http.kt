package app.mizan.service.http

import app.mizan.service.json.Json
import app.mizan.service.json.JsonValue
import com.sun.net.httpserver.HttpExchange

/**
 * The small HTTP surface the service uses.
 *
 * It is shared by the execution routes and the governance routes so that a
 * response, a bearer token and a query parameter are parsed one way in the
 * whole service. A second parser is how a security header ends up honoured on
 * one route and ignored on another.
 */
object Http {

    /** Largest body the service will act on. A bigger one is refused, not parsed. */
    const val MAX_BODY_BYTES = 256 * 1024

    /**
     * The most the service will read from a body it has already decided to
     * refuse. Draining a bounded amount lets the client finish sending and
     * then read the refusal, instead of seeing a closed connection. Beyond
     * this the connection is dropped, because a peer that ignores a 413 must
     * not be able to spend the service's memory.
     */
    const val MAX_DRAIN_BYTES = 4 * 1024 * 1024

    fun respond(exchange: HttpExchange, status: Int, body: JsonValue, extraHeaders: Map<String, String> = emptyMap()) {
        val bytes = Json.write(body).toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        extraHeaders.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.responseBody.flush()
        exchange.close()
    }

    /** The outcome of reading a request body. */
    data class Body(val value: JsonValue.Obj?, val tooLarge: Boolean) {
        companion object {
            val missing = Body(null, tooLarge = false)
            val oversized = Body(null, tooLarge = true)
        }
    }

    /**
     * Reads a body with a ceiling. A body that is missing, too large, or not
     * an object is a refusal, and none of the three is guessed at.
     */
    fun readBody(exchange: HttpExchange): Body {
        val declared = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declared != null && declared > MAX_DRAIN_BYTES) return Body.oversized
        val bytes = exchange.requestBody.readNBytes(MAX_BODY_BYTES + 1)
        if (bytes.size <= MAX_BODY_BYTES) {
            return Body(
                value = Json.parseOrNull(bytes.toString(Charsets.UTF_8))?.let { it as? JsonValue.Obj },
                tooLarge = false,
            )
        }
        // Too large to act on. Finish reading what the client is still sending
        // so the refusal can be delivered, then answer 413.
        var remaining = MAX_DRAIN_BYTES - bytes.size
        while (remaining > 0) {
            val chunk = exchange.requestBody.readNBytes(remaining.coerceAtMost(64 * 1024))
            if (chunk.isEmpty()) break
            remaining -= chunk.size
        }
        return Body.oversized
    }

    /** The object, or null when the body was missing or malformed. */
    fun readJsonObject(exchange: HttpExchange): JsonValue.Obj? = readBody(exchange).value

    fun bearer(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val trimmed = header.trim()
        if (!trimmed.startsWith("Bearer ", ignoreCase = true)) return null
        return trimmed.substring(7).trim().takeIf { it.isNotEmpty() }
    }

    fun query(exchange: HttpExchange, name: String): String? {
        val raw = exchange.requestURI.rawQuery ?: return null
        return raw.split('&').mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            if (pieces.size == 2) {
                java.net.URLDecoder.decode(pieces[0], Charsets.UTF_8) to
                    java.net.URLDecoder.decode(pieces[1], Charsets.UTF_8)
            } else {
                null
            }
        }.firstOrNull { (key, _) -> key == name }?.second
    }

    /** The path segments after the context root, without empty parts. */
    fun segments(exchange: HttpExchange, contextRoot: String): List<String> {
        val path = exchange.requestURI.path.removePrefix(contextRoot)
        return path.split('/').filter { it.isNotEmpty() }
    }

    /**
     * Wraps a handler so a thrown exception becomes a 500 and the connection
     * is always closed. A handler that hangs is worse than one that fails.
     */
    inline fun serve(exchange: HttpExchange, crossinline block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            try {
                respond(
                    exchange,
                    500,
                    Json.obj(
                        "status" to Json.str("failed"),
                        "messageCode" to Json.str("SERVICE_ERROR"),
                    ),
                )
            } catch (_: Throwable) {
                exchange.close()
            }
        }
    }
}
