package com.example.connectors.odoo

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * High-level client and factory for Odoo JSON-RPC and XML-RPC communication.
 */
class OdooApiClient(
    val baseUrl: String,
    val service: OdooApiService
) {
    var currentSession: OdooAuthResponseResult? = null
        private set

    companion object {
        fun buildMoshi(): Moshi {
            return Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()
        }

        fun buildOkHttpClient(loggingEnabled: Boolean = false): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)

            if (loggingEnabled) {
                val logging = HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                }
                builder.addInterceptor(logging)
            }
            return builder.build()
        }

        fun create(
            baseUrl: String,
            okHttpClient: OkHttpClient = buildOkHttpClient(),
            moshi: Moshi = buildMoshi()
        ): OdooApiClient {
            val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val retrofit = Retrofit.Builder()
                .baseUrl(normalizedUrl)
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()

            val service = retrofit.create(OdooApiService::class.java)
            return OdooApiClient(normalizedUrl, service)
        }
    }

    /**
     * Authenticates with Odoo using the Web Session endpoint (`/web/session/authenticate`).
     */
    suspend fun authenticateSession(
        db: String,
        login: String,
        password: String
    ): Result<OdooAuthResponseResult> {
        return try {
            val request = OdooJsonRpcRequest(
                params = OdooAuthRequestParams(db = db, login = login, password = password)
            )
            val response = service.authenticateSession(request)
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.error != null) {
                    Result.failure(Exception("Odoo Error [${body.error.code}]: ${body.error.message} - ${body.error.data?.message}"))
                } else if (body?.result != null) {
                    currentSession = body.result
                    Result.success(body.result)
                } else {
                    Result.failure(Exception("Empty authentication response from Odoo server"))
                }
            } else {
                Result.failure(Exception("HTTP error ${response.code()}: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Authenticates with Odoo using the universal `/jsonrpc` common service.
     */
    suspend fun authenticateCommon(
        db: String,
        login: String,
        password: String
    ): Result<Int> {
        return try {
            val request = OdooJsonRpcRequest(
                params = OdooCommonAuthRequestParams(
                    args = listOf(db, login, password)
                )
            )
            val response = service.authenticateCommon(request)
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.error != null) {
                    Result.failure(Exception("Odoo Error [${body.error.code}]: ${body.error.message}"))
                } else if (body?.result != null && body.result > 0) {
                    Result.success(body.result)
                } else {
                    Result.failure(Exception("Invalid authentication credentials for Odoo"))
                }
            } else {
                Result.failure(Exception("HTTP error ${response.code()}: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches records using `/web/dataset/search_read`.
     */
    suspend fun searchRead(
        model: String,
        domain: List<List<String>> = emptyList(),
        fields: List<String> = emptyList(),
        limit: Int = 25
    ): Result<List<Map<String, Any>>> {
        return try {
            val request = OdooJsonRpcRequest(
                params = OdooSearchReadRequestParams(
                    model = model,
                    domain = domain,
                    fields = fields,
                    limit = limit
                )
            )
            val response = service.searchRead(request)
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.error != null) {
                    Result.failure(Exception("Odoo Search Error: ${body.error.message}"))
                } else {
                    Result.success(body?.result?.records ?: emptyList())
                }
            } else {
                Result.failure(Exception("HTTP error ${response.code()}: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Invokes an Odoo model method using `/web/dataset/call_kw/{model}/{method}`.
     */
    suspend fun callModelMethod(
        model: String,
        method: String,
        args: List<Any> = emptyList(),
        kwargs: Map<String, Any> = emptyMap()
    ): Result<Any?> {
        return try {
            val requestPayload: Map<String, Any?> = mapOf(
                "model" to model,
                "method" to method,
                "args" to args,
                "kwargs" to kwargs
            )
            val request = OdooJsonRpcRequest(params = requestPayload)
            val response = service.callKw(model, method, request)
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.error != null) {
                    Result.failure(Exception("Odoo RPC Error: ${body.error.message} - ${body.error.data?.message}"))
                } else {
                    Result.success(body?.result)
                }
            } else {
                Result.failure(Exception("HTTP error ${response.code()}: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Executes an XML-RPC authentication call via `/xmlrpc/2/common`.
     */
    suspend fun authenticateXmlRpc(
        db: String,
        login: String,
        password: String
    ): Result<String> {
        return try {
            val xmlPayload = OdooXmlRpcHelper.buildAuthXml(db, login, password)
            val requestBody = xmlPayload.toRequestBody("text/xml".toMediaType())
            val response = service.callXmlRpcCommon(requestBody)
            if (response.isSuccessful) {
                val responseXml = response.body()?.string() ?: ""
                Result.success(responseXml)
            } else {
                Result.failure(Exception("XML-RPC HTTP error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
