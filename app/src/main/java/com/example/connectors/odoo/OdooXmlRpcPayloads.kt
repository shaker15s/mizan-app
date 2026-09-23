package com.example.connectors.odoo

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Exception representing an XML-RPC Fault returned by an Odoo server.
 */
class OdooXmlRpcFaultException(
    val faultCode: Int,
    val faultString: String
) : Exception("Odoo XML-RPC Fault [$faultCode]: $faultString")

/**
 * Serializer for constructing standards-compliant XML-RPC 2.0 payloads for Odoo ERP.
 */
object OdooXmlRpcSerializer {

    fun escapeXml(value: String): String {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    /**
     * Serializes any supported Kotlin primitive, list, array, or map to XML-RPC `<value>...</value>`.
     */
    fun serializeValue(value: Any?): String {
        return when (value) {
            null -> "<value><boolean>0</boolean></value>"
            is Boolean -> "<value><boolean>${if (value) "1" else "0"}</boolean></value>"
            is Byte, is Short, is Int -> "<value><int>$value</int></value>"
            is Long -> "<value><int>$value</int></value>"
            is Float, is Double -> "<value><double>$value</double></value>"
            is String -> "<value><string>${escapeXml(value)}</string></value>"
            is Collection<*> -> {
                val items = value.joinToString("") { serializeValue(it) }
                "<value><array><data>$items</data></array></value>"
            }
            is Array<*> -> {
                val items = value.joinToString("") { serializeValue(it) }
                "<value><array><data>$items</data></array></value>"
            }
            is Map<*, *> -> {
                val members = value.entries.joinToString("") { (k, v) ->
                    "<member><name>${escapeXml(k.toString())}</name>${serializeValue(v)}</member>"
                }
                "<value><struct>$members</struct></value>"
            }
            else -> "<value><string>${escapeXml(value.toString())}</string></value>"
        }
    }

    /**
     * Builds a `<methodCall>` XML payload with the specified method and parameter values.
     */
    fun buildMethodCall(methodName: String, params: List<Any?> = emptyList()): String {
        val paramsXml = if (params.isNotEmpty()) {
            val joined = params.joinToString("") { param ->
                "<param>${serializeValue(param)}</param>"
            }
            "<params>$joined</params>"
        } else {
            "<params></params>"
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><methodCall><methodName>$methodName</methodName>$paramsXml</methodCall>"
    }
}

/**
 * Parser for decoding XML-RPC 2.0 `<methodResponse>` documents returned by Odoo ERP.
 */
object OdooXmlRpcParser {

    /**
     * Parses an XML-RPC response document and returns the decoded Kotlin value,
     * or throws [OdooXmlRpcFaultException] if a `<fault>` element is present.
     */
    fun parseResponse(xmlString: String): Any? {
        val factory = DocumentBuilderFactory.newInstance()
        try {
            // Prevent XML external entity (XXE) attacks
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.isExpandEntityReferences = false
        } catch (_: Exception) {
            // Fallback for parser configurations where features are unsupported
        }

        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(InputSource(StringReader(xmlString)))
        val root = doc.documentElement

        // 1. Check for XML-RPC <fault>
        val faultElement = root.firstChildElement("fault")
        if (faultElement != null) {
            val valElement = faultElement.firstChildElement("value")
            val faultMap = if (valElement != null) parseValue(valElement) as? Map<*, *> else null
            val faultCode = (faultMap?.get("faultCode") as? Number)?.toInt() ?: -1
            val faultString = faultMap?.get("faultString")?.toString() ?: "Unknown Odoo XML-RPC fault"
            throw OdooXmlRpcFaultException(faultCode, faultString)
        }

        // 2. Check for XML-RPC <params> -> <param> -> <value>
        val paramsElement = root.firstChildElement("params")
        if (paramsElement != null) {
            val paramElement = paramsElement.firstChildElement("param")
            val valueElement = paramElement?.firstChildElement("value")
            return if (valueElement != null) parseValue(valueElement) else null
        }

        return null
    }

    private fun parseValue(valueElement: Element): Any? {
        val children = valueElement.childElements()
        if (children.isEmpty()) {
            return valueElement.textContent
        }

        val typeElement = children[0]
        return when (typeElement.tagName.lowercase()) {
            "string" -> typeElement.textContent
            "int", "i4" -> typeElement.textContent.trim().toIntOrNull() ?: 0
            "i8" -> typeElement.textContent.trim().toLongOrNull() ?: 0L
            "boolean" -> {
                val text = typeElement.textContent.trim()
                text == "1" || text.equals("true", ignoreCase = true)
            }
            "double" -> typeElement.textContent.trim().toDoubleOrNull() ?: 0.0
            "array" -> {
                val dataElement = typeElement.firstChildElement("data") ?: typeElement
                val values = dataElement.childElements("value")
                values.map { parseValue(it) }
            }
            "struct" -> {
                val members = typeElement.childElements("member")
                val map = mutableMapOf<String, Any?>()
                for (member in members) {
                    val nameEl = member.firstChildElement("name")
                    val valEl = member.firstChildElement("value")
                    if (nameEl != null && valEl != null) {
                        map[nameEl.textContent.trim()] = parseValue(valEl)
                    }
                }
                map
            }
            else -> typeElement.textContent
        }
    }

    private fun Element.childElements(tagName: String? = null): List<Element> {
        val list = mutableListOf<Element>()
        val nodes = this.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val el = node as Element
                if (tagName == null || el.tagName.equals(tagName, ignoreCase = true)) {
                    list.add(el)
                }
            }
        }
        return list
    }

    private fun Element.firstChildElement(tagName: String? = null): Element? {
        val nodes = this.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val el = node as Element
                if (tagName == null || el.tagName.equals(tagName, ignoreCase = true)) {
                    return el
                }
            }
        }
        return null
    }
}
