package app.mizan.service.erp

import app.mizan.domain.security.SecretMaterial
import app.mizan.service.json.Json
import app.mizan.service.json.JsonValue
import app.mizan.service.json.asArray
import app.mizan.service.json.asObject
import app.mizan.service.json.asString
import app.mizan.service.json.field
import app.mizan.service.json.text
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The Odoo 19 JSON-2 wire format.
 *
 * Odoo 19 exposes `POST {base}/json/2/{model}/{method}` with a bearer API key
 * and the method's keyword arguments as the JSON body. Two consequences shape
 * this file:
 *
 *  1. **Every call is its own transaction.** A sequence of calls is not a
 *     transaction, so a multi-step business operation must be a single method
 *     the ERP runs atomically, not three round trips that can half-apply.
 *  2. **The database name matters** on a shared host, so it travels in
 *     `X-Odoo-Database` and not inside the body.
 *
 * Nothing here decides anything: this is request shaping and response parsing,
 * with a strict rule that an unexpected shape is a schema drift failure rather
 * than a silently defaulted value.
 */
object OdooWire {

    const val HEADER_AUTHORIZATION = "Authorization"
    const val HEADER_DATABASE = "X-Odoo-Database"
    const val CODE_INVALID_KEY = 401
    const val CODE_ACCESS_DENIED = 403
    const val CODE_NOT_FOUND = 404
    const val CODE_RATE_LIMITED = 429

    /** The atomically-applied operation the connector asks Odoo to run. */
    const val METHOD_CREATE_DRAFT_ORDER = "wakeel_create_draft_order"

    fun url(baseUrl: String, model: String, method: String): String {
        require(model.matches(MODEL)) { "refusing an unexpected model name" }
        require(method.matches(METHOD)) { "refusing an unexpected method name" }
        val root = baseUrl.trimEnd('/')
        return "$root/json/2/$model/$method"
    }

    private val MODEL = Regex("[A-Za-z0-9_.]+")
    private val METHOD = Regex("[A-Za-z0-9_]+")

    /** `Bearer` is spelled the way the JSON-2 documentation spells it. */
    fun authorizationHeader(apiKey: SecretMaterial): String = "bearer " + apiKey.reveal()

    fun searchReadBody(domain: JsonValue, fields: List<String>, limit: Int): String = Json.write(
        Json.obj(
            "domain" to domain,
            "fields" to Json.arr(fields.map { Json.str(it) }),
            "limit" to Json.num(limit),
        ),
    )

    fun createBody(values: Map<String, JsonValue>): String =
        Json.write(Json.obj("vals" to Json.obj(*values.map { it.key to it.value }.toTypedArray())))

    fun readBody(ids: List<Long>, fields: List<String>): String = Json.write(
        Json.obj(
            "ids" to Json.arr(ids.map { Json.num(it) }),
            "fields" to Json.arr(fields.map { Json.str(it) }),
        ),
    )

    fun methodBody(arguments: Map<String, JsonValue>): String =
        Json.write(Json.obj(*arguments.map { it.key to it.value }.toTypedArray()))

    /**
     * Odoo answers `search_read` with an array of objects. Anything else is
     * drift: the caller gets [ErpResult.Malformed] rather than an empty list
     * that would read as "no such customer".
     */
    fun rows(responseBody: String, what: String): ErpResult<List<JsonValue.Obj>> {
        val parsed = Json.parseOrNull(responseBody)
            ?: return ErpResult.Malformed("ODOO_NOT_JSON", what)
        val array = parsed.asArray() ?: return ErpResult.Malformed("ODOO_EXPECTED_ARRAY", what)
        val rows = ArrayList<JsonValue.Obj>(array.items.size)
        for (item in array.items) {
            val row = item.asObject() ?: return ErpResult.Malformed("ODOO_EXPECTED_OBJECT", what)
            rows += row
        }
        return ErpResult.Ok(rows)
    }

    /**
     * `create` returns the new id. Odoo's JSON-2 returns either an object with
     * `id`, or a single-element array holding it; both are accepted, and the
     * absence of an id is drift, not a zero.
     */
    fun createdId(responseBody: String, what: String): ErpResult<Long> {
        val parsed = Json.parseOrNull(responseBody)
            ?: return ErpResult.Malformed("ODOO_NOT_JSON", what)
        val candidate = when (parsed) {
            is JsonValue.Arr -> parsed.items.firstOrNull() ?: JsonValue.Null
            else -> parsed
        }
        val id = when (candidate) {
            is JsonValue.Obj -> candidate.field("id")?.let { numberOrNull(it) }
            else -> numberOrNull(candidate)
        }
        return if (id == null) ErpResult.Malformed("ODOO_CREATE_WITHOUT_ID", what) else ErpResult.Ok(id)
    }

    /** Reads a numeric field that Odoo may send as a number or as a string. */
    fun numberOrNull(value: JsonValue?): Long? = when (value) {
        null -> null
        is JsonValue.Num -> value.raw.toBigDecimalOrNull()?.setScale(0, RoundingMode.DOWN)?.longValueExact()
        is JsonValue.Str -> value.value.toBigDecimalOrNull()?.setScale(0, RoundingMode.DOWN)?.longValueExact()
        else -> null
    }

    /** Odoo money is a float. Minor units are the only safe representation. */
    fun minorUnitsOrNull(value: JsonValue?): Long? {
        val decimal = when (value) {
            null -> return null
            is JsonValue.Num -> value.raw.toBigDecimalOrNull()
            is JsonValue.Str -> value.value.replace(",", "").toBigDecimalOrNull()
            else -> null
        } ?: return null
        return decimal.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    fun minorUnitsOf(value: BigDecimal): Long = value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()

    /** A many2one field arrives as `[id, "display name"]`. */
    fun many2One(value: JsonValue?): Pair<Long, String>? {
        val array = value?.asArray() ?: return null
        if (array.items.size < 2) return null
        val id = numberOrNull(array.items[0]) ?: return null
        val label = array.items[1].asString() ?: return null
        return id to label
    }

    fun textOrNull(value: JsonValue?): String? = when (value) {
        null -> null
        is JsonValue.Str -> value.value
        is JsonValue.Bool -> value.value.toString()
        is JsonValue.Num -> value.raw
        else -> null
    }

    /** A domain is JSON-2's `domain` argument: a list of leaves and operators. */
    fun domain(vararg leaves: JsonValue): JsonValue.Arr = JsonValue.Arr(leaves.toList())

    fun leaf(field: String, operator: String, value: JsonValue): JsonValue.Arr = JsonValue.Arr(
        listOf(Json.str(field), Json.str(operator), value),
    )

    fun or(): JsonValue = Json.str("|")

    fun and(): JsonValue = Json.str("&")

    /** True when the body looks like Odoo's own error envelope. */
    fun isErrorEnvelope(body: String): Boolean {
        val parsed = Json.parseOrNull(body)?.asObject() ?: return false
        return parsed.field("error") != null ||
            (parsed.field("message") != null && parsed.field("code") != null)
    }

    fun errorName(body: String): String? {
        val parsed = Json.parseOrNull(body)?.asObject() ?: return null
        parsed.text("name")?.let { return it }
        parsed.field("error")?.asObject()?.let { error ->
            error.field("data")?.asObject()?.let { data ->
                data.text("name")?.let { return it }
            }
            error.text("message")?.let { return it }
        }
        return parsed.text("message")
    }
}
