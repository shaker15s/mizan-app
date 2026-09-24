package app.mizan.integration.odoo.json2

import app.mizan.domain.model.CanonicalJson
import app.mizan.domain.model.CanonicalValue

/**
 * Odoo 19 external JSON-2.
 * POST {base}/json/2/{model}/{method}
 * Authorization: bearer <api key>   (added by the caller, never logged)
 * X-Odoo-Database: when the host is shared by more than one database
 *
 * This transport is for a trusted host. The production Android graph does
 * not construct it with a device-held ERP key.
 */
object OdooJson2Request {
    fun url(baseUrl: String, model: String, method: String): String {
        require(model.matches(MODEL)) { "refusing unexpected model name" }
        require(method.matches(METHOD)) { "refusing unexpected method name" }
        val root = baseUrl.trimEnd('/')
        require(root.startsWith("https://")) { "JSON-2 base URL must be https" }
        return "$root/json/2/$model/$method"
    }

    fun headers(database: String?): Map<String, String> {
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "User-Agent" to "MIZAN/1.0",
        )
        if (!database.isNullOrBlank()) headers["X-Odoo-Database"] = database
        return headers
    }

    fun body(arguments: CanonicalValue): String = CanonicalJson.write(arguments)

    private val MODEL = Regex("""[a-zA-Z0-9_.]+""")
    private val METHOD = Regex("""[a-zA-Z0-9_]+""")
}

/**
 * Bearer material that is not a data-class field and is not returned by toString.
 */
class EphemeralSecret(value: String) {
    private val chars = value.toCharArray()

    fun reveal(): String = String(chars)

    fun clear() {
        chars.fill('\u0000')
    }

    override fun toString(): String = "EphemeralSecret(redacted)"
}
