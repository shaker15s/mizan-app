package com.example.connectors.odoo

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Standard JSON-RPC 2.0 Request wrapper for Odoo RPC endpoints.
 */
@JsonClass(generateAdapter = true)
data class OdooJsonRpcRequest<T>(
    @Json(name = "jsonrpc")
    val jsonrpc: String = "2.0",
    @Json(name = "method")
    val method: String = "call",
    @Json(name = "params")
    val params: T,
    @Json(name = "id")
    val id: Long = System.currentTimeMillis()
)

/**
 * Standard JSON-RPC 2.0 Response wrapper from Odoo RPC endpoints.
 */
@JsonClass(generateAdapter = true)
data class OdooJsonRpcResponse<T>(
    @Json(name = "jsonrpc")
    val jsonrpc: String = "2.0",
    @Json(name = "id")
    val id: Long? = null,
    @Json(name = "result")
    val result: T? = null,
    @Json(name = "error")
    val error: OdooJsonRpcError? = null
)

/**
 * Odoo JSON-RPC Error representation.
 */
@JsonClass(generateAdapter = true)
data class OdooJsonRpcError(
    @Json(name = "code")
    val code: Int,
    @Json(name = "message")
    val message: String,
    @Json(name = "data")
    val data: OdooErrorData? = null
)

@JsonClass(generateAdapter = true)
data class OdooErrorData(
    @Json(name = "name")
    val name: String? = null,
    @Json(name = "debug")
    val debug: String? = null,
    @Json(name = "message")
    val message: String? = null,
    @Json(name = "arguments")
    val arguments: List<String>? = null,
    @Json(name = "exception_type")
    val exceptionType: String? = null
)

/**
 * Authentication payload for Odoo Web Session (`/web/session/authenticate`).
 */
@JsonClass(generateAdapter = true)
data class OdooAuthRequestParams(
    @Json(name = "db")
    val db: String,
    @Json(name = "login")
    val login: String,
    @Json(name = "password")
    val password: String
)

/**
 * Authentication result returned by Odoo Web Session.
 */
@JsonClass(generateAdapter = true)
data class OdooAuthResponseResult(
    @Json(name = "uid")
    val uid: Int,
    @Json(name = "name")
    val name: String? = null,
    @Json(name = "username")
    val username: String? = null,
    @Json(name = "session_id")
    val sessionId: String? = null,
    @Json(name = "db")
    val db: String? = null,
    @Json(name = "partner_id")
    val partnerId: Long? = null,
    @Json(name = "company_id")
    val companyId: Long? = null,
    @Json(name = "server_version")
    val serverVersion: String? = null,
    @Json(name = "is_admin")
    val isAdmin: Boolean? = false,
    @Json(name = "is_system")
    val isSystem: Boolean? = false
)

/**
 * Common service authentication parameters for universal `/jsonrpc` endpoint.
 * Corresponds to: `service = "common"`, `method = "authenticate"`, `args = [db, login, password, user_agent_env]`.
 */
@JsonClass(generateAdapter = true)
data class OdooCommonAuthRequestParams(
    @Json(name = "service")
    val service: String = "common",
    @Json(name = "method")
    val method: String = "authenticate",
    @Json(name = "args")
    val args: List<String>
)

/**
 * Universal JSON-RPC execute_kw parameter wrapper for `/jsonrpc`.
 * Corresponds to Odoo's object service:
 * `args` = [db, uid, password, model, method, methodArgs, methodKwargs]
 */
data class OdooExecuteKwRequestParams(
    val service: String = "object",
    val method: String = "execute_kw",
    val args: List<Any>
)

/**
 * Dataset call_kw payload for `/web/dataset/call_kw/{model}/{method}`.
 */
data class OdooCallKwRequestParams(
    val model: String,
    val method: String,
    val args: List<Any> = emptyList(),
    val kwargs: Map<String, Any> = emptyMap()
)

/**
 * Dataset search_read payload for `/web/dataset/search_read`.
 */
@JsonClass(generateAdapter = true)
data class OdooSearchReadRequestParams(
    @Json(name = "model")
    val model: String,
    @Json(name = "domain")
    val domain: List<List<String>> = emptyList(),
    @Json(name = "fields")
    val fields: List<String> = emptyList(),
    @Json(name = "offset")
    val offset: Int = 0,
    @Json(name = "limit")
    val limit: Int = 20,
    @Json(name = "sort")
    val sort: String? = null
)

/**
 * Result payload from `/web/dataset/search_read`.
 */
@JsonClass(generateAdapter = true)
data class OdooSearchReadResult(
    @Json(name = "length")
    val length: Int = 0,
    @Json(name = "records")
    val records: List<Map<String, Any>> = emptyList()
)

/**
 * Structured DTO for Sale Order creation in Odoo ERP.
 */
@JsonClass(generateAdapter = true)
data class OdooSaleOrderCreateDto(
    @Json(name = "partner_id")
    val partnerId: Long,
    @Json(name = "client_order_ref")
    val clientOrderRef: String? = null,
    @Json(name = "state")
    val state: String = "draft",
    @Json(name = "note")
    val note: String? = null
)

/**
 * XML-RPC Helper for building Odoo XML-RPC 2.0 payloads.
 */
object OdooXmlRpcHelper {
    /**
     * Builds an XML-RPC payload to authenticate via `/xmlrpc/2/common`.
     */
    fun buildAuthXml(db: String, login: String, password: String): String {
        return """
            <?xml version="1.0"?>
            <methodCall>
                <methodName>authenticate</methodName>
                <params>
                    <param><value><string>$db</string></value></param>
                    <param><value><string>$login</string></value></param>
                    <param><value><string>$password</string></value></param>
                    <param><value><struct></struct></value></param>
                </params>
            </methodCall>
        """.trimIndent()
    }

    /**
     * Builds an XML-RPC payload for execute_kw via `/xmlrpc/2/object`.
     */
    fun buildExecuteKwXml(
        db: String,
        uid: Int,
        password: String,
        model: String,
        method: String,
        argsXml: String = "<array><data></data></array>",
        kwargsXml: String = "<struct></struct>"
    ): String {
        return """
            <?xml version="1.0"?>
            <methodCall>
                <methodName>execute_kw</methodName>
                <params>
                    <param><value><string>$db</string></value></param>
                    <param><value><int>$uid</int></value></param>
                    <param><value><string>$password</string></value></param>
                    <param><value><string>$model</string></value></param>
                    <param><value><string>$method</string></value></param>
                    <param><value>$argsXml</value></param>
                    <param><value>$kwargsXml</value></param>
                </params>
            </methodCall>
        """.trimIndent()
    }
}
