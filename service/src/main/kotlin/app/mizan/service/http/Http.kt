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

    /** Largest body the service will read. A bigger one is refused, not buffered. */
    const val MAX_BODY_BYTES = 256 * 1024

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

    /**
     * Reads a body with a ceiling. Returns null when the body is missing, too
     * large, or not an object: all three are refusals, and none of them is
     * guessed at.
     */
    fun readJsonObject(exchange: HttpExchange): JsonValue.Obj? {
        val declared = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declared != null && declared > MAX_BODY_BYTES) return null
        val bytes = exchange.requestBody.readNBytes(MAX_BODY_BYTES + 1)
        if (bytes.size > MAX_BODY_BYTES) return null
        return Json.parseOrNull(bytes.toString(Charsets.UTF_8))?.let { value ->
            (value as? JsonValue.Obj)
        }
    }

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
