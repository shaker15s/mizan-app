package com.example

import com.example.connectors.odoo.OdooApiClient
import com.example.connectors.odoo.OdooAuthRequestParams
import com.example.connectors.odoo.OdooAuthResponseResult
import com.example.connectors.odoo.OdooCommonAuthRequestParams
import com.example.connectors.odoo.OdooJsonRpcError
import com.example.connectors.odoo.OdooJsonRpcRequest
import com.example.connectors.odoo.OdooJsonRpcResponse
import com.example.connectors.odoo.OdooSearchReadRequestParams
import com.example.connectors.odoo.OdooXmlRpcHelper
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OdooRetrofitServiceTest {

    private lateinit var moshi: Moshi

    @Before
    fun setup() {
        moshi = OdooApiClient.buildMoshi()
    }

    @Test
    fun testAuthRequestSerialization() {
        val authParams = OdooAuthRequestParams(
            db = "odoo_enterprise_2026",
            login = "admin@alamal.com",
            password = "secure_token_key"
        )
        val request = OdooJsonRpcRequest(params = authParams, id = 42L)
        val type = Types.newParameterizedType(OdooJsonRpcRequest::class.java, OdooAuthRequestParams::class.java)
        val adapter: JsonAdapter<OdooJsonRpcRequest<OdooAuthRequestParams>> = moshi.adapter(type)
        val json = adapter.toJson(request)

        assertTrue(json.contains("\"jsonrpc\":\"2.0\""))
        assertTrue(json.contains("\"method\":\"call\""))
        assertTrue(json.contains("\"db\":\"odoo_enterprise_2026\""))
        assertTrue(json.contains("\"login\":\"admin@alamal.com\""))
        assertTrue(json.contains("\"id\":42"))
    }

    @Test
    fun testAuthResponseDeserialization() {
        val sampleResponseJson = """
            {
                "jsonrpc": "2.0",
                "id": 42,
                "result": {
                    "uid": 2,
                    "name": "Administrator",
                    "username": "admin",
                    "session_id": "sess_odoo_9812401824",
                    "db": "odoo_enterprise_2026",
                    "partner_id": 3,
                    "company_id": 1,
                    "server_version": "19.0+e",
                    "is_admin": true,
                    "is_system": true
                }
            }
        """.trimIndent()

        val type = Types.newParameterizedType(OdooJsonRpcResponse::class.java, OdooAuthResponseResult::class.java)
        val adapter: JsonAdapter<OdooJsonRpcResponse<OdooAuthResponseResult>> = moshi.adapter(type)
        val response = adapter.fromJson(sampleResponseJson)

        assertNotNull(response)
        assertEquals("2.0", response?.jsonrpc)
        assertEquals(42L, response?.id)
        assertNotNull(response?.result)
        assertEquals(2, response?.result?.uid)
        assertEquals("Administrator", response?.result?.name)
        assertEquals("sess_odoo_9812401824", response?.result?.sessionId)
        assertEquals(true, response?.result?.isAdmin)
    }

    @Test
    fun testOdooErrorResponseParsing() {
        val errorJson = """
            {
                "jsonrpc": "2.0",
                "id": 99,
                "error": {
                    "code": 200,
                    "message": "Odoo Server Error",
                    "data": {
                        "name": "odoo.exceptions.AccessDenied",
                        "debug": "Traceback (most recent call last)...",
                        "message": "Access Denied",
                        "arguments": ["Access Denied"]
                    }
                }
            }
        """.trimIndent()

        val type = Types.newParameterizedType(OdooJsonRpcResponse::class.java, OdooAuthResponseResult::class.java)
        val adapter: JsonAdapter<OdooJsonRpcResponse<OdooAuthResponseResult>> = moshi.adapter(type)
        val response = adapter.fromJson(errorJson)

        assertNotNull(response)
        assertNotNull(response?.error)
        assertEquals(200, response?.error?.code)
        assertEquals("Odoo Server Error", response?.error?.message)
        assertEquals("odoo.exceptions.AccessDenied", response?.error?.data?.name)
        assertEquals("Access Denied", response?.error?.data?.message)
    }

    @Test
    fun testCommonAuthRequestSerialization() {
        val commonParams = OdooCommonAuthRequestParams(
            args = listOf("demo_db", "admin", "admin_pass")
        )
        val request = OdooJsonRpcRequest(params = commonParams, id = 101L)
        val type = Types.newParameterizedType(OdooJsonRpcRequest::class.java, OdooCommonAuthRequestParams::class.java)
        val adapter: JsonAdapter<OdooJsonRpcRequest<OdooCommonAuthRequestParams>> = moshi.adapter(type)
        val json = adapter.toJson(request)

        assertTrue(json.contains("\"service\":\"common\""))
        assertTrue(json.contains("\"method\":\"authenticate\""))
        assertTrue(json.contains("\"demo_db\""))
    }

    @Test
    fun testSearchReadRequestSerialization() {
        val searchParams = OdooSearchReadRequestParams(
            model = "sale.order",
            domain = listOf(listOf("state", "=", "sale")),
            fields = listOf("name", "partner_id", "amount_total"),
            limit = 10
        )
        val request = OdooJsonRpcRequest(params = searchParams)
        val type = Types.newParameterizedType(OdooJsonRpcRequest::class.java, OdooSearchReadRequestParams::class.java)
        val adapter: JsonAdapter<OdooJsonRpcRequest<OdooSearchReadRequestParams>> = moshi.adapter(type)
        val json = adapter.toJson(request)

        assertTrue(json.contains("\"model\":\"sale.order\""))
        assertTrue(json.contains("\"limit\":10"))
        assertTrue(json.contains("\"state\""))
    }

    @Test
    fun testXmlRpcPayloadBuilder() {
        val authXml = OdooXmlRpcHelper.buildAuthXml("my_db", "user1", "pass123")
        assertTrue(authXml.contains("<methodName>authenticate</methodName>"))
        assertTrue(authXml.contains("<string>my_db</string>"))
        assertTrue(authXml.contains("<string>user1</string>"))
        assertTrue(authXml.contains("<string>pass123</string>"))

        val executeXml = OdooXmlRpcHelper.buildExecuteKwXml(
            db = "my_db",
            uid = 2,
            password = "key",
            model = "sale.order",
            method = "search_read"
        )
        assertTrue(executeXml.contains("<methodName>execute_kw</methodName>"))
        assertTrue(executeXml.contains("<int>2</int>"))
        assertTrue(executeXml.contains("<string>sale.order</string>"))
        assertTrue(executeXml.contains("<string>search_read</string>"))
    }

    @Test
    fun testApiClientCreation() {
        val client = OdooApiClient.create("https://my-odoo-instance.com")
        assertNotNull(client)
        assertEquals("https://my-odoo-instance.com/", client.baseUrl)
        assertNotNull(client.service)
    }
}
