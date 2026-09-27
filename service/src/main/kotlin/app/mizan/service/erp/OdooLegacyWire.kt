package app.mizan.service.erp

import app.mizan.domain.security.SecretMaterial

/**
 * The XML-RPC wire format, for one purpose only: reading a pre-19 installation
 * while it is being migrated.
 *
 * Odoo 19 speaks JSON-2 and this service's primary connector speaks JSON-2.
 * Older installations expose `/xmlrpc/2/common` and `/xmlrpc/2/object`, and a
 * migration cannot always move the customer's ERP on the day the app ships.
 * This file is the smallest possible bridge across that gap: encode a call,
 * decode an answer, and refuse to guess about anything else.
 *
 * Two facts shape it. First, XML-RPC has no typed error channel -- a business
 * refusal and a bug both arrive as a `<fault>` -- so the fault's code is
 * carried as the reason code and never interpreted as success. Second, the
 * protocol is untyped enough that a response can be well-formed XML and still
 * mean nothing, so an unexpected shape is a schema-drift failure, not a
 * default value.
 *
 * Nothing here decides anything, and no code above the connector sees it.
 */
object OdooLegacyWire {

    const val PATH_COMMON = "/xmlrpc/2/common"
    const val PATH_OBJECT = "/xmlrpc/2/object"

    /** Odoo's own fault codes for the cases a migration actually hits. */
    const val FAULT_AUTHENTICATION = 100
    const val FAULT_OBJECT_NOT_FOUND = 1
    const val FAULT_MISSING_ATTRIBUTE = 2

    fun url(baseUrl: String, path: String): String = baseUrl.trimEnd('/') + path

    /** XML-RPC is form-shaped and carries no bearer token: credentials travel in the call. */
    fun headers(): Map<String, String> = linkedMapOf(
        "Content-Type" to "text/xml",
        "Accept" to "text/xml",
        "User-Agent" to "Wakeel/2.0",
    )

    // ------------------------------------------------------------------ calls

    /**
     * `authenticate(db, login, password, {})` on the common endpoint.
     *
     * The login is the API key's identity and the password is the secret, so
     * the credential is a pair the deployment configures, not one opaque key.
     */
    fun authenticateCall(database: String, login: String, apiKey: SecretMaterial): String = methodCall(
        "authenticate",
        listOf(
            Xml.Str(database),
            Xml.Str(login),
            Xml.Str(apiKey.reveal()),
            Xml.Struct(emptyMap()),
        ),
    )

    /**
     * `execute_kw(db, uid, password, model, method, args, kwargs)`.
     *
     * The password is repeated on every call because XML-RPC is stateless:
     * there is no session to bind to, which is one of the reasons this path is
     * migration-only and never the target.
     */
    fun executeCall(
        database: String,
        uid: Long,
        apiKey: SecretMaterial,
        model: String,
        method: String,
        args: List<Xml> = emptyList(),
        kwargs: Map<String, Xml> = emptyMap(),
        /** Refuses a model or method name that is not a plain identifier path. */
        guarded: Boolean = true,
    ): String {
        if (guarded) {
            require(model.matches(MODEL_NAME)) { "refusing an unexpected model name" }
            require(method.matches(METHOD_NAME)) { "refusing an unexpected method name" }
        }
        return methodCall(
            "execute_kw",
            listOf(
                Xml.Str(database),
                Xml.Int(uid),
                Xml.Str(apiKey.reveal()),
                Xml.Str(model),
                Xml.Str(method),
                Xml.Array(args),
                Xml.Struct(kwargs),
            ),
        )
    }

    private val MODEL_NAME = Regex("[A-Za-z0-9_.]+")
    private val METHOD_NAME = Regex("[A-Za-z0-9_]+")

    fun methodCall(name: String, params: List<Xml>): String = buildString {
        append("""<?xml version="1.0"?>""")
        append("<methodCall><methodName>").append(escape(name)).append("</methodName><params>")
        for (param in params) append("<param>").append(param.render()).append("</param>")
        append("</params></methodCall>")
    }

    // --------------------------------------------------------------- decoding

    /** What came back. A fault is an answer too, and it is not a value. */
    sealed interface Response {
        data class Value(val value: Xml) : Response
        data class Fault(val code: kotlin.Int, val message: String) : Response

        /** Well-formed enough to receive, not well-formed enough to read. */
        data class Unreadable(val reasonCode: String) : Response
    }

    /**
     * Reads a `<methodResponse>`.
     *
     * A reader, not a validator: it understands exactly the subset Odoo sends
     * (scalars, arrays, structs, and a fault) and reports anything else as
     * unreadable. Refusing to guess is the whole point -- an XML-RPC answer
     * that is silently half-read is a stock count or an amount that is wrong.
     */
    fun parse(body: String): Response {
        if (body.isBlank()) return Response.Unreadable("ODOO_LEGACY_EMPTY_BODY")
        val faultIndex = body.indexOf("<fault>")
        if (faultIndex >= 0) {
            val fault = readValue(body, body.indexOf("<value>", faultIndex))
                ?: return Response.Unreadable("ODOO_LEGACY_FAULT_UNREADABLE")
            val struct = fault.asStruct() ?: return Response.Unreadable("ODOO_LEGACY_FAULT_NOT_STRUCT")
            val code: kotlin.Int = struct["faultCode"]?.asInt() ?: 0
            val message = struct["faultString"]?.asString() ?: ""
            return Response.Fault(code, message)
        }
        val start = body.indexOf("<value>")
        if (start < 0) return Response.Unreadable("ODOO_LEGACY_NO_VALUE")
        val value = readValue(body, start) ?: return Response.Unreadable("ODOO_LEGACY_VALUE_UNREADABLE")
        return Response.Value(value)
    }

    /**
     * Reads one `<value>` starting at [start].
     *
     * The parser is deliberately small: it reads the element after the value
     * tag, consumes its children, and stops. It does not attempt to be a
     * general XML parser, and it returns null rather than a partial reading
     * when the markup does not end where it says it ends.
     */
    private fun readValue(body: String, start: Int): Xml? {
        if (start < 0) return null
        var index = start + "<value>".length
        val open = body.indexOf('<', index)
        if (open < 0) return null

        // `<value>text</value>` is a string with no type element.
        if (body.startsWith("</value>", open)) {
            return Xml.Str(unescape(body.substring(index, open)))
        }
        val close = body.indexOf('>', open)
        if (close < 0) return null
        val tag = body.substring(open + 1, close).removeSuffix("/").trim()
        val selfClosing = body.startsWith("/>", close - 1)

        return when (tag) {
            "string", "dateTime.iso8601", "base64" -> {
                val end = body.indexOf("</$tag>", close)
                if (end < 0) return null
                Xml.Str(unescape(body.substring(close + 1, end)))
            }
            "int", "i4" -> Xml.Int(body.substring(close + 1, body.indexOf("</$tag>", close).takeIf { it > 0 } ?: return null).trim().toLongOrNull() ?: return null)
            "boolean" -> Xml.Bool(body.substring(close + 1, body.indexOf("</boolean>", close).takeIf { it > 0 } ?: return null).trim() == "1")
            "double" -> {
                val end = body.indexOf("</double>", close)
                if (end < 0) return null
                Xml.Str(body.substring(close + 1, end).trim())
            }
            "nil" -> Xml.Nil
            "array" -> readArray(body, close)
            "struct" -> readStruct(body, close)
            else -> if (selfClosing) Xml.Nil else null
        }
    }

    private fun readArray(body: String, afterTag: Int): Xml? {
        val dataStart = body.indexOf("<data>", afterTag)
        if (dataStart < 0) return null
        val dataEnd = body.indexOf("</data>", dataStart)
        if (dataEnd < 0) return null
        val items = ArrayList<Xml>()
        var cursor = dataStart
        while (true) {
            val next = body.indexOf("<value>", cursor)
            if (next < 0 || next > dataEnd) break
            val item = readValue(body, next) ?: return null
            items += item
            cursor = valueEnd(body, next) ?: return null
        }
        return Xml.Array(items)
    }

    private fun readStruct(body: String, afterTag: Int): Xml? {
        val end = body.indexOf("</struct>", afterTag)
        if (end < 0) return null
        val fields = LinkedHashMap<String, Xml>()
        var cursor = afterTag
        while (true) {
            val memberStart = body.indexOf("<member>", cursor)
            if (memberStart < 0 || memberStart > end) break
            val nameStart = body.indexOf("<name>", memberStart)
            val nameEnd = if (nameStart < 0) -1 else body.indexOf("</name>", nameStart)
            if (nameStart < 0 || nameEnd < 0 || nameEnd > end) return null
            val name = unescape(body.substring(nameStart + "<name>".length, nameEnd))
            val valueStart = body.indexOf("<value>", nameEnd)
            if (valueStart < 0 || valueStart > end) return null
            val value = readValue(body, valueStart) ?: return null
            fields[name] = value
            cursor = valueEnd(body, valueStart) ?: return null
        }
        return Xml.Struct(fields)
    }

    /** The offset just past the `</value>` that closes the value at [start]. */
    private fun valueEnd(body: String, start: Int): Int? {
        var depth = 0
        var cursor = start
        while (cursor < body.length) {
            val open = body.indexOf('<', cursor)
            if (open < 0) return null
            val close = body.indexOf('>', open)
            if (close < 0) return null
            val tag = body.substring(open + 1, close)
            when {
                tag == "value" -> depth++
                tag == "/value" -> {
                    depth--
                    if (depth <= 0) return close + 1
                }
            }
            cursor = close + 1
        }
        return null
    }

    fun escape(raw: String): String = buildString(raw.length) {
        for (character in raw) {
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(character)
            }
        }
    }

    fun unescape(raw: String): String {
        val builder = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val character = raw[index]
            if (character != '&') {
                builder.append(character)
                index++
                continue
            }
            val end = raw.indexOf(';', index)
            if (end < 0) {
                builder.append(character)
                index++
                continue
            }
            when (val entity = raw.substring(index + 1, end)) {
                "amp" -> builder.append('&')
                "lt" -> builder.append('<')
                "gt" -> builder.append('>')
                "quot" -> builder.append('"')
                "apos" -> builder.append('\'')
                else -> if (entity.startsWith("#")) {
                    val code = entity.removePrefix("#").removePrefix("x").toIntOrNull(16)
                        ?: entity.removePrefix("#").toIntOrNull()
                    if (code != null) builder.append(code.toChar()) else builder.append(raw.substring(index, end + 1))
                } else {
                    builder.append(raw.substring(index, end + 1))
                }
            }
            index = end + 1
        }
        return builder.toString()
    }

    /** Why a fault happened, in the vocabulary this service already speaks. */
    fun reasonForFault(code: kotlin.Int, message: String): String = when (code) {
        FAULT_AUTHENTICATION -> "ERP_AUTH_REJECTED"
        FAULT_OBJECT_NOT_FOUND -> "ERP_MODEL_OR_METHOD_MISSING"
        FAULT_MISSING_ATTRIBUTE -> "ERP_ACCESS_DENIED"
        else -> if (message.contains("Access", ignoreCase = true)) "ERP_ACCESS_DENIED" else "ERP_REJECTED"
    }

    /** The XML-RPC value model, which is all this file needs to represent. */
    sealed interface Xml {

        data class Str(val value: String) : Xml
        data class Int(val value: Long) : Xml
        data class Bool(val value: Boolean) : Xml
        data object Nil : Xml
        data class Array(val items: List<Xml>) : Xml
        data class Struct(val fields: Map<String, Xml>) : Xml

        fun render(): String = when (this) {
            is Str -> "<value><string>${escape(value)}</string></value>"
            is Int -> "<value><int>$value</int></value>"
            is Bool -> "<value><boolean>${if (value) 1 else 0}</boolean></value>"
            Nil -> "<value><nil/></value>"
            is Array -> "<value><array><data>" + items.joinToString("") { it.render() } + "</data></array></value>"
            is Struct -> "<value><struct>" + fields.entries.joinToString("") { (name, value) ->
                "<member><name>${escape(name)}</name>${value.render()}</member>"
            } + "</struct></value>"
        }

        fun asString(): String? = (this as? Str)?.value

        // `kotlin.Int` is spelled out because `Int` inside this interface is
        // the XML-RPC value type below, and a signature that silently means a
        // different type than it reads is how a fault code becomes a struct.
        fun asInt(): kotlin.Int? = when (this) {
            is Int -> value.toInt()
            is Str -> value.trim().toIntOrNull()
            else -> null
        }

        fun asLong(): kotlin.Long? = when (this) {
            is Int -> value
            is Str -> value.trim().toLongOrNull()
            else -> null
        }

        fun asDouble(): kotlin.Double? = when (this) {
            is Str -> value.trim().toDoubleOrNull()
            is Int -> value.toDouble()
            else -> null
        }

        fun asBool(): kotlin.Boolean? = when (this) {
            is Bool -> value
            is Int -> value != 0L
            is Str -> when (value.trim().lowercase()) {
                "1", "true" -> true
                "0", "false" -> false
                else -> null
            }
            else -> null
        }

        fun asStruct(): Map<String, Xml>? = (this as? Struct)?.fields

        fun asArray(): List<Xml>? = when (this) {
            is Array -> items
            Nil -> emptyList()
            else -> null
        }

        /** Odoo sends a many2one as `[id, "Name"]`; anything else is not one. */
        fun many2One(): Pair<Long, String>? {
            val items = asArray() ?: return null
            if (items.size < 2) return null
            val id = items[0].asLong() ?: return null
            val name = items[1].asString() ?: return null
            return id to name
        }
    }
}
