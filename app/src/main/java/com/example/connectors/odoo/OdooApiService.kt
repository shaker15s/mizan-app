package com.example.connectors.odoo

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit Service Interface for communicating with Odoo ERP instances
 * via JSON-RPC 2.0 and XML-RPC protocols.
 */
interface OdooApiService {

    /**
     * Authenticates a user session against `/web/session/authenticate`.
     * Returns the full session context including user ID (UID), session_id token,
     * database name, and user preferences.
     */
    @Headers("Content-Type: application/json")
    @POST("web/session/authenticate")
    suspend fun authenticateSession(
        @Body request: OdooJsonRpcRequest<OdooAuthRequestParams>
    ): Response<OdooJsonRpcResponse<OdooAuthResponseResult>>

    /**
     * Authenticates credentials using Odoo's universal JSON-RPC common service.
     * Equivalent to XML-RPC `common.authenticate(db, login, password, {})`.
     * Returns the integer UID if successful, or false/0 if invalid.
     */
    @Headers("Content-Type: application/json")
    @POST("jsonrpc")
    suspend fun authenticateCommon(
        @Body request: OdooJsonRpcRequest<OdooCommonAuthRequestParams>
    ): Response<OdooJsonRpcResponse<Int>>

    /**
     * Executes queries via Odoo's dataset `search_read` endpoint.
     * Fetches record arrays with field projection, domain filtering, and pagination.
     */
    @Headers("Content-Type: application/json")
    @POST("web/dataset/search_read")
    suspend fun searchRead(
        @Body request: OdooJsonRpcRequest<OdooSearchReadRequestParams>
    ): Response<OdooJsonRpcResponse<OdooSearchReadResult>>

    /**
     * Executes keyword methods on an Odoo model (e.g. `create`, `write`, `action_confirm`).
     * Path params: `{model}` (e.g. "sale.order"), `{method}` (e.g. "action_confirm").
     */
    @Headers("Content-Type: application/json")
    @POST("web/dataset/call_kw/{model}/{method}")
    suspend fun callKw(
        @Path("model") model: String,
        @Path("method") method: String,
        @Body request: OdooJsonRpcRequest<Map<String, Any?>>
    ): Response<OdooJsonRpcResponse<Any>>

    /**
     * Universal JSON-RPC call dispatcher for executing arbitrary Odoo RPC services.
     */
    @Headers("Content-Type: application/json")
    @POST("jsonrpc")
    suspend fun executeJsonRpc(
        @Body request: OdooJsonRpcRequest<Map<String, Any?>>
    ): Response<OdooJsonRpcResponse<Any>>

    /**
     * Odoo XML-RPC 2.0 common endpoint (`/xmlrpc/2/common`).
     * Used for XML-RPC authentication and version queries.
     */
    @Headers("Content-Type: text/xml")
    @POST("xmlrpc/2/common")
    suspend fun callXmlRpcCommon(
        @Body xmlBody: RequestBody
    ): Response<ResponseBody>

    /**
     * Odoo XML-RPC 2.0 object endpoint (`/xmlrpc/2/object`).
     * Used for executing ORM methods via XML-RPC.
     */
    @Headers("Content-Type: text/xml")
    @POST("xmlrpc/2/object")
    suspend fun callXmlRpcObject(
        @Body xmlBody: RequestBody
    ): Response<ResponseBody>
}
