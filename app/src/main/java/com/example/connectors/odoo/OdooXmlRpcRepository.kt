package com.example.connectors.odoo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Encapsulates an active, authenticated Odoo ERP session.
 */
data class OdooSession(
    val database: String,
    val username: String,
    val apiKeyOrPassword: String,
    val uid: Int,
    val serverVersion: String? = null,
    val authenticatedAt: Long = System.currentTimeMillis()
)

/**
 * Observable session state transitions for Odoo ERP integration.
 */
sealed class OdooSessionState {
    object Unauthenticated : OdooSessionState()

    data class Authenticating(
        val database: String,
        val username: String
    ) : OdooSessionState()

    data class Authenticated(
        val session: OdooSession
    ) : OdooSessionState()

    data class Error(
        val message: String,
        val faultCode: Int? = null,
        val cause: Throwable? = null
    ) : OdooSessionState()
}

/**
 * Repository class that leverages [OdooApiService] to manage Odoo session state
 * and execute authenticated XML-RPC calls to Odoo ERP endpoints (`/xmlrpc/2/common` and `/xmlrpc/2/object`).
 */
class OdooXmlRpcRepository(
    private val apiService: OdooApiService,
    val defaultDatabase: String? = null
) {
    private val xmlMediaType = "text/xml; charset=utf-8".toMediaType()

    private val _sessionState = MutableStateFlow<OdooSessionState>(OdooSessionState.Unauthenticated)
    val sessionState: StateFlow<OdooSessionState> = _sessionState.asStateFlow()

    /**
     * The currently active session, or null if unauthenticated.
     */
    val currentSession: OdooSession?
        get() = (_sessionState.value as? OdooSessionState.Authenticated)?.session

    /**
     * Whether an authenticated session is currently held and ready for calls.
     */
    val isAuthenticated: Boolean
        get() = currentSession != null

    companion object {
        /**
         * Factory function to create an [OdooXmlRpcRepository] from a base URL.
         */
        fun create(baseUrl: String, defaultDatabase: String? = null): OdooXmlRpcRepository {
            val apiClient = OdooApiClient.create(baseUrl)
            return OdooXmlRpcRepository(apiClient.service, defaultDatabase)
        }
    }

    /**
     * Authenticates with Odoo ERP via XML-RPC common endpoint (`/xmlrpc/2/common`).
     * On success, updates [sessionState] to [OdooSessionState.Authenticated] and returns the [OdooSession].
     */
    suspend fun authenticate(
        database: String = defaultDatabase.orEmpty(),
        login: String,
        passwordOrApiKey: String
    ): Result<OdooSession> {
        if (database.isBlank()) {
            val error = "Database name cannot be blank for Odoo authentication."
            _sessionState.value = OdooSessionState.Error(error)
            return Result.failure(IllegalArgumentException(error))
        }

        _sessionState.value = OdooSessionState.Authenticating(database, login)

        return try {
            val authXml = OdooXmlRpcSerializer.buildMethodCall(
                methodName = "authenticate",
                params = listOf(database, login, passwordOrApiKey, emptyMap<String, Any>())
            )
            val requestBody = authXml.toRequestBody(xmlMediaType)
            val response = apiService.callXmlRpcCommon(requestBody)

            if (!response.isSuccessful) {
                val errorMsg = "HTTP error ${response.code()} during XML-RPC authentication: ${response.message()}"
                _sessionState.value = OdooSessionState.Error(errorMsg)
                return Result.failure(Exception(errorMsg))
            }

            val responseBody = response.body()?.string().orEmpty()
            val parsedResult = OdooXmlRpcParser.parseResponse(responseBody)

            val uid = when (parsedResult) {
                is Number -> parsedResult.toInt()
                is Boolean -> if (parsedResult) 1 else 0
                else -> 0
            }

            if (uid > 0) {
                // Fetch server version optionally
                val versionInfo = fetchServerVersionInternal().getOrNull()
                val serverVersion = (versionInfo?.get("server_version") as? String)
                    ?: (versionInfo?.get("server_serie") as? String)

                val session = OdooSession(
                    database = database,
                    username = login,
                    apiKeyOrPassword = passwordOrApiKey,
                    uid = uid,
                    serverVersion = serverVersion
                )
                _sessionState.value = OdooSessionState.Authenticated(session)
                Result.success(session)
            } else {
                val errorMsg = "Odoo XML-RPC authentication failed: Invalid credentials or database '$database'."
                _sessionState.value = OdooSessionState.Error(errorMsg)
                Result.failure(SecurityException(errorMsg))
            }
        } catch (fault: OdooXmlRpcFaultException) {
            _sessionState.value = OdooSessionState.Error(fault.faultString, fault.faultCode, fault)
            Result.failure(fault)
        } catch (e: Exception) {
            _sessionState.value = OdooSessionState.Error(e.message ?: "Authentication failed", cause = e)
            Result.failure(e)
        }
    }

    /**
     * Restores a previously saved session.
     */
    fun restoreSession(session: OdooSession) {
        _sessionState.value = OdooSessionState.Authenticated(session)
    }

    /**
     * Clears current session and sets state to [OdooSessionState.Unauthenticated].
     */
    fun logout() {
        _sessionState.value = OdooSessionState.Unauthenticated
    }

    /**
     * Executes an authenticated `execute_kw` call against `/xmlrpc/2/object`.
     * Fails immediately if not authenticated.
     */
    suspend fun executeKw(
        model: String,
        method: String,
        args: List<Any?> = emptyList(),
        kwargs: Map<String, Any?> = emptyMap()
    ): Result<Any?> {
        val session = currentSession
            ?: return Result.failure(IllegalStateException("Cannot execute XML-RPC call: Odoo session is not authenticated. Call authenticate() first."))

        return try {
            val params = listOf(
                session.database,
                session.uid,
                session.apiKeyOrPassword,
                model,
                method,
                args,
                kwargs
            )
            val callXml = OdooXmlRpcSerializer.buildMethodCall("execute_kw", params)
            val requestBody = callXml.toRequestBody(xmlMediaType)
            val response = apiService.callXmlRpcObject(requestBody)

            if (!response.isSuccessful) {
                return Result.failure(Exception("HTTP error ${response.code()} executing $model.$method: ${response.message()}"))
            }

            val responseBody = response.body()?.string().orEmpty()
            val result = OdooXmlRpcParser.parseResponse(responseBody)
            Result.success(result)
        } catch (fault: OdooXmlRpcFaultException) {
            Result.failure(fault)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Queries server version and metadata from `/xmlrpc/2/common` endpoint.
     */
    suspend fun getServerVersion(): Result<Map<String, Any?>> {
        return fetchServerVersionInternal()
    }

    private suspend fun fetchServerVersionInternal(): Result<Map<String, Any?>> {
        return try {
            val versionXml = OdooXmlRpcSerializer.buildMethodCall("version", emptyList())
            val requestBody = versionXml.toRequestBody(xmlMediaType)
            val response = apiService.callXmlRpcCommon(requestBody)

            if (!response.isSuccessful) {
                return Result.failure(Exception("HTTP error ${response.code()} fetching version"))
            }

            val body = response.body()?.string().orEmpty()
            val parsed = OdooXmlRpcParser.parseResponse(body)
            @Suppress("UNCHECKED_CAST")
            val map = parsed as? Map<String, Any?> ?: emptyMap()
            Result.success(map)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Performs a record search via XML-RPC.
     * Returns a list of record IDs.
     */
    suspend fun search(
        model: String,
        domain: List<List<Any?>> = emptyList(),
        offset: Int = 0,
        limit: Int = 80
    ): Result<List<Long>> {
        val kwargs = mutableMapOf<String, Any?>()
        if (offset > 0) kwargs["offset"] = offset
        if (limit > 0) kwargs["limit"] = limit

        return executeKw(
            model = model,
            method = "search",
            args = listOf(domain),
            kwargs = kwargs
        ).map { result ->
            (result as? List<*>)?.mapNotNull { (it as? Number)?.toLong() } ?: emptyList()
        }
    }

    /**
     * Reads fields for the specified record IDs via XML-RPC.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun read(
        model: String,
        ids: List<Long>,
        fields: List<String> = emptyList()
    ): Result<List<Map<String, Any?>>> {
        val kwargs = if (fields.isNotEmpty()) mapOf("fields" to fields) else emptyMap()
        return executeKw(
            model = model,
            method = "read",
            args = listOf(ids),
            kwargs = kwargs
        ).map { result ->
            (result as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()
        }
    }

    /**
     * Combines search and read in a single round-trip via XML-RPC.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun searchRead(
        model: String,
        domain: List<List<Any?>> = emptyList(),
        fields: List<String> = emptyList(),
        offset: Int = 0,
        limit: Int = 80
    ): Result<List<Map<String, Any?>>> {
        val kwargs = mutableMapOf<String, Any?>()
        if (fields.isNotEmpty()) kwargs["fields"] = fields
        if (offset > 0) kwargs["offset"] = offset
        if (limit > 0) kwargs["limit"] = limit

        return executeKw(
            model = model,
            method = "search_read",
            args = listOf(domain),
            kwargs = kwargs
        ).map { result ->
            (result as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()
        }
    }

    /**
     * Creates a new record in Odoo via XML-RPC `create`.
     * Returns the newly generated record ID.
     */
    suspend fun create(
        model: String,
        values: Map<String, Any?>
    ): Result<Long> {
        return executeKw(
            model = model,
            method = "create",
            args = listOf(values)
        ).map { result ->
            (result as? Number)?.toLong()
                ?: throw IllegalStateException("Expected record ID from create(), but received: $result")
        }
    }

    /**
     * Updates an existing record in Odoo via XML-RPC `write`.
     */
    suspend fun write(
        model: String,
        ids: List<Long>,
        values: Map<String, Any?>
    ): Result<Boolean> {
        return executeKw(
            model = model,
            method = "write",
            args = listOf(ids, values)
        ).map { result ->
            result == true || (result as? Number)?.toInt() == 1
        }
    }

    /**
     * Deletes records in Odoo via XML-RPC `unlink`.
     */
    suspend fun unlink(
        model: String,
        ids: List<Long>
    ): Result<Boolean> {
        return executeKw(
            model = model,
            method = "unlink",
            args = listOf(ids)
        ).map { result ->
            result == true || (result as? Number)?.toInt() == 1
        }
    }

    /**
     * Executes a workflow button method (e.g., `action_confirm`, `action_cancel`) on the given record IDs.
     */
    suspend fun callWorkflow(
        model: String,
        method: String,
        ids: List<Long>
    ): Result<Any?> {
        return executeKw(
            model = model,
            method = method,
            args = listOf(ids)
        )
    }
}
