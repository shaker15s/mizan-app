package com.example

import com.example.connectors.odoo.OdooApiService
import com.example.connectors.odoo.OdooJsonRpcRequest
import com.example.connectors.odoo.OdooJsonRpcResponse
import com.example.connectors.odoo.OdooSearchReadRequestParams
import com.example.connectors.odoo.OdooSession
import com.example.connectors.odoo.OdooSessionState
import com.example.connectors.odoo.OdooXmlRpcFaultException
import com.example.connectors.odoo.OdooXmlRpcParser
import com.example.connectors.odoo.OdooXmlRpcRepository
import com.example.connectors.odoo.OdooXmlRpcSerializer
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class OdooXmlRpcRepositoryTest {

    @Test
    fun testSerializerPrimitivesAndEscaping() {
        val s = OdooXmlRpcSerializer.serializeValue("Al-Amal & Sons <Trading> \"Co.\" 'Ltd'")
        assertTrue(s.contains("&amp;"))
        assertTrue(s.contains("&lt;Trading&gt;"))
        assertTrue(s.contains("&quot;Co.&quot;"))
        assertTrue(s.contains("&apos;Ltd&apos;"))

        val i = OdooXmlRpcSerializer.serializeValue(42)
        assertEquals("<value><int>42</int></value>", i)

        val bTrue = OdooXmlRpcSerializer.serializeValue(true)
        assertEquals("<value><boolean>1</boolean></value>", bTrue)

        val bFalse = OdooXmlRpcSerializer.serializeValue(false)
        assertEquals("<value><boolean>0</boolean></value>", bFalse)

        val d = OdooXmlRpcSerializer.serializeValue(123.45)
        assertEquals("<value><double>123.45</double></value>", d)
    }

    @Test
    fun testSerializerNestedStructures() {
        val list = listOf("draft", 100)
        val serializedList = OdooXmlRpcSerializer.serializeValue(list)
        assertTrue(serializedList.contains("<array><data>"))
        assertTrue(serializedList.contains("<string>draft</string>"))
        assertTrue(serializedList.contains("<int>100</int>"))

        val map = mapOf("state" to "sale", "amount" to 500)
        val serializedMap = OdooXmlRpcSerializer.serializeValue(map)
        assertTrue(serializedMap.contains("<struct>"))
        assertTrue(serializedMap.contains("<member><name>state</name><value><string>sale</string></value></member>"))
    }

    @Test
    fun testMethodCallXmlGeneration() {
        val callXml = OdooXmlRpcSerializer.buildMethodCall(
            methodName = "execute_kw",
            params = listOf("my_db", 2, "secret_key", "sale.order", "search", listOf<Any>())
        )
        assertTrue(callXml.startsWith("<?xml version=\"1.0\""))
        assertTrue(callXml.contains("<methodName>execute_kw</methodName>"))
        assertTrue(callXml.contains("<value><string>my_db</string></value>"))
        assertTrue(callXml.contains("<value><int>2</int></value>"))
    }

    @Test
    fun testParserSuccessPrimitives() {
        val intXml = """
            <?xml version="1.0"?>
            <methodResponse>
                <params>
                    <param><value><int>77</int></value></param>
                </params>
            </methodResponse>
        """.trimIndent()
        val intResult = OdooXmlRpcParser.parseResponse(intXml)
        assertEquals(77, intResult)

        val strXml = """
            <?xml version="1.0"?>
            <methodResponse>
                <params>
                    <param><value><string>SO-2026-999</string></value></param>
                </params>
            </methodResponse>
        """.trimIndent()
        val strResult = OdooXmlRpcParser.parseResponse(strXml)
        assertEquals("SO-2026-999", strResult)

        val boolXml = """
            <?xml version="1.0"?>
            <methodResponse>
                <params>
                    <param><value><boolean>1</boolean></value></param>
                </params>
            </methodResponse>
        """.trimIndent()
        val boolResult = OdooXmlRpcParser.parseResponse(boolXml)
        assertEquals(true, boolResult)
    }

    @Test
    fun testParserNestedStructAndArray() {
        val complexXml = """
            <?xml version="1.0"?>
            <methodResponse>
                <params>
                    <param>
                        <value>
                            <array>
                                <data>
                                    <value>
                                        <struct>
                                            <member>
                                                <name>id</name>
                                                <value><int>101</int></value>
                                            </member>
                                            <member>
                                                <name>name</name>
                                                <value><string>SO001</string></value>
                                            </member>
                                            <member>
                                                <name>amount_total</name>
                                                <value><double>1850.50</double></value>
                                            </member>
                                        </struct>
                                    </value>
                                </data>
                            </array>
                        </value>
                    </param>
                </params>
            </methodResponse>
        """.trimIndent()

        val parsed = OdooXmlRpcParser.parseResponse(complexXml)
        assertTrue(parsed is List<*>)
        val records = parsed as List<*>
        assertEquals(1, records.size)
        val firstRecord = records[0] as Map<*, *>
        assertEquals(101, firstRecord["id"])
        assertEquals("SO001", firstRecord["name"])
        assertEquals(1850.50, firstRecord["amount_total"])
    }

    @Test
    fun testParserFaultHandling() {
        val faultXml = """
            <?xml version="1.0"?>
            <methodResponse>
                <fault>
                    <value>
                        <struct>
                            <member>
                                <name>faultCode</name>
                                <value><int>2</int></value>
                            </member>
                            <member>
                                <name>faultString</name>
                                <value><string>Access Denied: Invalid password</string></value>
                            </member>
                        </struct>
                    </value>
                </fault>
            </methodResponse>
        """.trimIndent()

        try {
            OdooXmlRpcParser.parseResponse(faultXml)
            org.junit.Assert.fail("Expected OdooXmlRpcFaultException")
        } catch (e: OdooXmlRpcFaultException) {
            assertEquals(2, e.faultCode)
            assertEquals("Access Denied: Invalid password", e.faultString)
        }
    }

    @Test
    fun testRepositorySessionManagementAndState() = runBlocking {
        val fakeService = FakeOdooApiService()
        val repository = OdooXmlRpcRepository(fakeService, defaultDatabase = "test_db")

        // Initial state
        assertEquals(OdooSessionState.Unauthenticated, repository.sessionState.value)
        assertFalse(repository.isAuthenticated)
        assertNull(repository.currentSession)

        // ExecuteKw should fail when unauthenticated
        val unauthCall = repository.executeKw("sale.order", "search")
        assertTrue(unauthCall.isFailure)
        assertTrue(unauthCall.exceptionOrNull() is IllegalStateException)

        // Set fake auth response to succeed with UID 5
        fakeService.nextCommonResponse = """
            <?xml version="1.0"?>
            <methodResponse>
                <params><param><value><int>5</int></value></param></params>
            </methodResponse>
        """.trimIndent()

        val authResult = repository.authenticate("test_db", "admin", "secret_pass")
        assertTrue(authResult.isSuccess)
        val session = authResult.getOrThrow()
        assertEquals(5, session.uid)
        assertEquals("test_db", session.database)
        assertEquals("admin", session.username)

        assertTrue(repository.isAuthenticated)
        assertNotNull(repository.currentSession)
        assertEquals(5, repository.currentSession?.uid)
        assertTrue(repository.sessionState.value is OdooSessionState.Authenticated)

        // ExecuteKw should succeed now that session is active
        fakeService.nextObjectResponse = """
            <?xml version="1.0"?>
            <methodResponse>
                <params>
                    <param>
                        <value>
                            <array>
                                <data>
                                    <value><int>101</int></value>
                                    <value><int>102</int></value>
                                </data>
                            </array>
                        </value>
                    </param>
                </params>
            </methodResponse>
        """.trimIndent()

        val searchResult = repository.search("sale.order")
        assertTrue(searchResult.isSuccess)
        assertEquals(listOf(101L, 102L), searchResult.getOrThrow())

        // Test create
        fakeService.nextObjectResponse = """
            <?xml version="1.0"?>
            <methodResponse>
                <params><param><value><int>201</int></value></param></params>
            </methodResponse>
        """.trimIndent()

        val createResult = repository.create("sale.order", mapOf("partner_id" to 3))
        assertTrue(createResult.isSuccess)
        assertEquals(201L, createResult.getOrThrow())

        // Test write
        fakeService.nextObjectResponse = """
            <?xml version="1.0"?>
            <methodResponse>
                <params><param><value><boolean>1</boolean></value></param></params>
            </methodResponse>
        """.trimIndent()

        val writeResult = repository.write("sale.order", listOf(201L), mapOf("note" to "Updated"))
        assertTrue(writeResult.isSuccess)
        assertTrue(writeResult.getOrThrow())

        // Test logout
        repository.logout()
        assertFalse(repository.isAuthenticated)
        assertEquals(OdooSessionState.Unauthenticated, repository.sessionState.value)
    }

    @Test
    fun testAuthenticationFailureUpdatesState() = runBlocking {
        val fakeService = FakeOdooApiService()
        val repository = OdooXmlRpcRepository(fakeService, defaultDatabase = "test_db")

        // Server returns boolean 0 for invalid login
        fakeService.nextCommonResponse = """
            <?xml version="1.0"?>
            <methodResponse>
                <params><param><value><boolean>0</boolean></value></param></params>
            </methodResponse>
        """.trimIndent()

        val result = repository.authenticate("test_db", "wrong_user", "wrong_pass")
        assertTrue(result.isFailure)
        assertFalse(repository.isAuthenticated)
        assertTrue(repository.sessionState.value is OdooSessionState.Error)
    }

    @Test
    fun testRestoreSession() {
        val fakeService = FakeOdooApiService()
        val repository = OdooXmlRpcRepository(fakeService, defaultDatabase = "test_db")

        val existingSession = OdooSession(
            database = "alamal_db",
            username = "restored_user",
            apiKeyOrPassword = "key",
            uid = 99
        )
        repository.restoreSession(existingSession)

        assertTrue(repository.isAuthenticated)
        assertEquals(99, repository.currentSession?.uid)
        assertEquals("alamal_db", repository.currentSession?.database)
    }

    // Fake API service implementation for testing
    private class FakeOdooApiService : OdooApiService {
        var nextCommonResponse: String = """<?xml version="1.0"?><methodResponse><params><param><value><int>1</int></value></param></params></methodResponse>"""
        var nextObjectResponse: String = """<?xml version="1.0"?><methodResponse><params><param><value><boolean>1</boolean></value></param></params></methodResponse>"""

        override suspend fun authenticateSession(request: OdooJsonRpcRequest<com.example.connectors.odoo.OdooAuthRequestParams>): Response<OdooJsonRpcResponse<com.example.connectors.odoo.OdooAuthResponseResult>> {
            throw UnsupportedOperationException()
        }

        override suspend fun authenticateCommon(request: OdooJsonRpcRequest<com.example.connectors.odoo.OdooCommonAuthRequestParams>): Response<OdooJsonRpcResponse<Int>> {
            throw UnsupportedOperationException()
        }

        override suspend fun searchRead(request: OdooJsonRpcRequest<OdooSearchReadRequestParams>): Response<OdooJsonRpcResponse<com.example.connectors.odoo.OdooSearchReadResult>> {
            throw UnsupportedOperationException()
        }

        override suspend fun callKw(model: String, method: String, request: OdooJsonRpcRequest<Map<String, Any?>>): Response<OdooJsonRpcResponse<Any>> {
            throw UnsupportedOperationException()
        }

        override suspend fun executeJsonRpc(request: OdooJsonRpcRequest<Map<String, Any?>>): Response<OdooJsonRpcResponse<Any>> {
            throw UnsupportedOperationException()
        }

        override suspend fun callXmlRpcCommon(xmlBody: RequestBody): Response<ResponseBody> {
            val responseBody = nextCommonResponse.toResponseBody("text/xml".toMediaType())
            return Response.success(responseBody)
        }

        override suspend fun callXmlRpcObject(xmlBody: RequestBody): Response<ResponseBody> {
            val responseBody = nextObjectResponse.toResponseBody("text/xml".toMediaType())
            return Response.success(responseBody)
        }
    }
}
