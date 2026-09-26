// Test-only stand-in for the slice of OkHttp that :integration uses.
//
// The module builds requests, executes them, and reads the response body. It
// never touches connection pools, interceptors, or TLS, so this stand-in is
// small -- and honest: a request built here carries the real headers, the real
// URL and the real body, which is what the contract tests assert on.
//
// Nothing here performs I/O. `Call.execute()` answers 503 (and a body that
// says so) rather than pretending a network exists: a test that needs a server
// uses the real `:service`, as `UseCaseMatrixTest` does.
//
// Not in any Gradle source set -- see tools/jvm_stubs/okio_stub.kt.
package okhttp3

import okio.Buffer
import java.io.Closeable
import java.util.concurrent.TimeUnit

class MediaType private constructor(private val raw: String) {
    override fun toString(): String = raw

    companion object {
        fun String.toMediaType(): MediaType = MediaType(this)
    }
}

class RequestBody private constructor(private val content: String, val type: MediaType? = null) {
    fun contentLength(): Long = content.length.toLong()

    fun contentType(): MediaType? = type

    fun writeTo(buffer: Buffer) {
        buffer.writeUtf8(content)
    }

    companion object {
        fun String.toRequestBody(type: MediaType? = null): RequestBody = RequestBody(this, type)

        fun ByteArray.toRequestBody(type: MediaType? = null, offset: Int = 0, byteCount: Int = size): RequestBody =
            RequestBody(String(this, offset, byteCount))
    }
}

class ResponseBody internal constructor(private val content: String) : Closeable {
    fun string(): String = content

    override fun close() = Unit
}

/** Same shape as the real artifact, which declares it in the companion. */
fun String.toResponseBody(type: MediaType? = null): ResponseBody = ResponseBody(this)

class Request internal constructor(
    private val target: String,
    private val headers: Map<String, String>,
    val body: RequestBody?,
    private val verb: String,
) {
    val url: String
        get() = target

    val method: String
        get() = verb

    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun headers(): Map<String, String> = headers

    class Builder {
        private var target: String = ""
        private var body: RequestBody? = null
        private var verb: String = "GET"
        private val headers = LinkedHashMap<String, String>()

        fun url(value: String): Builder = apply { target = value }

        fun header(name: String, value: String): Builder = apply { headers[name] = value }

        fun addHeader(name: String, value: String): Builder = apply { headers[name] = value }

        fun removeHeader(name: String): Builder = apply { headers.remove(name) }

        fun get(): Builder = apply { verb = "GET" }

        fun delete(): Builder = apply { verb = "DELETE"; body = null }

        fun post(body: RequestBody): Builder = apply { verb = "POST"; this.body = body }

        fun put(body: RequestBody): Builder = apply { verb = "PUT"; this.body = body }

        fun patch(body: RequestBody): Builder = apply { verb = "PATCH"; this.body = body }

        fun method(verb: String, body: RequestBody?): Builder = apply {
            this.verb = verb.uppercase()
            this.body = body
        }

        fun build(): Request = Request(target, headers.toMap(), body, verb)
    }
}

class Response internal constructor(
    val code: Int,
    val body: ResponseBody?,
    private val headers: Map<String, String> = emptyMap(),
) : Closeable {
    val isSuccessful: Boolean
        get() = code in 200..299

    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    override fun close() = Unit

    class Builder {
        private var code: Int = 200
        private var body: ResponseBody? = null
        private val headers = LinkedHashMap<String, String>()

        fun code(value: Int): Builder = apply { code = value }

        fun body(value: ResponseBody?): Builder = apply { body = value }

        fun header(name: String, value: String): Builder = apply { headers[name] = value }

        fun build(): Response = Response(code, body, headers.toMap())
    }
}

interface Call {
    fun execute(): Response

    fun cancel() = Unit
}

class OkHttpClient private constructor() {
    fun newCall(request: Request): Call = NoNetworkCall(request)

    private class NoNetworkCall(private val request: Request) : Call {
        override fun execute(): Response = Response.Builder()
            .code(503)
            .body("{\"messageCode\":\"NO_TRANSPORT\"}".toResponseBody())
            .build()
    }

    class Builder {
        private val timeouts = LinkedHashMap<String, Pair<Long, TimeUnit>>()

        fun connectTimeout(value: Long, unit: TimeUnit): Builder = apply { timeouts["connect"] = value to unit }

        fun readTimeout(value: Long, unit: TimeUnit): Builder = apply { timeouts["read"] = value to unit }

        fun writeTimeout(value: Long, unit: TimeUnit): Builder = apply { timeouts["write"] = value to unit }

        fun callTimeout(value: Long, unit: TimeUnit): Builder = apply { timeouts["call"] = value to unit }

        fun build(): OkHttpClient = OkHttpClient()
    }

    companion object
}
