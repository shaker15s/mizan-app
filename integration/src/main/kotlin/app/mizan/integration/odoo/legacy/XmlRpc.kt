package app.mizan.integration.odoo.legacy

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Legacy Odoo XML-RPC adapter. Isolated from business rules.
 * Scheduled for removal with Odoo's legacy RPC (Odoo 22). Prefer JSON-2.
 */
class XmlRpcFaultException(
    val faultCode: Int,
    val faultString: String,
) : Exception("Odoo XML-RPC fault [$faultCode]")

object XmlRpcSerializer {
    fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun serializeValue(value: Any?): String = when (value) {
        null -> "<value><boolean>0</boolean></value>"
        is Boolean -> "<value><boolean>${if (value) "1" else "0"}</boolean></value>"
        is Byte, is Short, is Int -> "<value><int>$value</int></value>"
        is Long -> "<value><i8>$value</i8></value>"
        is Float, is Double -> "<value><double>$value</double></value>"
        is String -> "<value><string>${escape(value)}</string></value>"
        is Collection<*> -> "<value><array><data>${value.joinToString("") { serializeValue(it) }}</data></array></value>"
        is Array<*> -> "<value><array><data>${value.joinToString("") { serializeValue(it) }}</data></array></value>"
        is Map<*, *> -> {
            val members = value.entries.joinToString("") { (key, child) ->
                "<member><name>${escape(key.toString())}</name>${serializeValue(child)}</member>"
            }
            "<value><struct>$members</struct></value>"
        }
        else -> "<value><string>${escape(value.toString())}</string></value>"
    }

    fun methodCall(methodName: String, params: List<Any?> = emptyList()): String {
        require(methodName.matches(Regex("""[A-Za-z0-9_]+"""))) { "unexpected method name" }
        val paramsXml = params.joinToString("") { "<param>${serializeValue(it)}</param>" }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<methodCall><methodName>$methodName</methodName><params>$paramsXml</params></methodCall>"
    }
}

object XmlRpcParser {
    fun parse(xml: String): Any? {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.isExpandEntityReferences = false
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val root = document.documentElement
        val fault = root.firstElement("fault")
        if (fault != null) {
            val value = fault.firstElement("value")
            val map = value?.let { parseValue(it) } as? Map<*, *>
            val code = (map?.get("faultCode") as? Number)?.toInt() ?: -1
            val message = map?.get("faultString")?.toString() ?: "Unknown fault"
            throw XmlRpcFaultException(code, message)
        }
        val params = root.firstElement("params") ?: return null
        val param = params.firstElement("param") ?: return null
        val value = param.firstElement("value") ?: return null
        return parseValue(value)
    }

    private fun parseValue(valueElement: Element): Any? {
        val children = valueElement.childElements()
        if (children.isEmpty()) return valueElement.textContent
        return when (children[0].tagName.lowercase()) {
            "string" -> children[0].textContent
            "int", "i4" -> children[0].textContent.trim().toIntOrNull() ?: 0
            "i8" -> children[0].textContent.trim().toLongOrNull() ?: 0L
            "boolean" -> {
                val text = children[0].textContent.trim()
                text == "1" || text.equals("true", ignoreCase = true)
            }
            "double" -> children[0].textContent.trim().toDoubleOrNull() ?: 0.0
            "array" -> {
                val data = children[0].firstElement("data") ?: children[0]
                data.childElements("value").map { parseValue(it) }
            }
            "struct" -> {
                val map = linkedMapOf<String, Any?>()
                children[0].childElements("member").forEach { member ->
                    val name = member.firstElement("name")?.textContent?.trim()
                    val value = member.firstElement("value")
                    if (name != null && value != null) map[name] = parseValue(value)
                }
                map
            }
            else -> children[0].textContent
        }
    }
}

private fun Element.childElements(tagName: String? = null): List<Element> {
    val list = mutableListOf<Element>()
    val nodes = childNodes
    for (index in 0 until nodes.length) {
        val node = nodes.item(index)
        if (node.nodeType == Node.ELEMENT_NODE) {
            val element = node as Element
            if (tagName == null || element.tagName.equals(tagName, ignoreCase = true)) list += element
        }
    }
    return list
}

private fun Element.firstElement(tagName: String): Element? {
    val nodes = childNodes
    for (index in 0 until nodes.length) {
        val node = nodes.item(index)
        if (node.nodeType == Node.ELEMENT_NODE) {
            val element = node as Element
            if (element.tagName.equals(tagName, ignoreCase = true)) return element
        }
    }
    return null
}
