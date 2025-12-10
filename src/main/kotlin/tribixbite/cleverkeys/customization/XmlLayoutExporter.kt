package tribixbite.cleverkeys.customization

import android.util.Log
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Handles exporting keyboard layouts to XML, optionally injecting custom short swipe mappings.
 */
object XmlLayoutExporter {
    private const val TAG = "XmlLayoutExporter"

    /**
     * Inject custom short swipe mappings into an existing XML layout string.
     *
     * @param xmlContent The source XML string (e.g., from raw resource or existing custom layout).
     * @param mappings The list of custom mappings to apply.
     * @return The modified XML string with short swipes baked in.
     */
    fun injectMappings(xmlContent: String, mappings: List<ShortSwipeMapping>): String {
        try {
            if (mappings.isEmpty()) return xmlContent

            // Parse XML to DOM
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(InputSource(StringReader(xmlContent)))

            // Group mappings by key code for faster lookup
            val mappingsByKey = mappings.groupBy { it.keyCode.lowercase() }

            // Iterate through all <key> tags
            val keyNodes = document.getElementsByTagName("key")
            for (i in 0 until keyNodes.length) {
                val keyElement = keyNodes.item(i) as? Element ?: continue
                
                // Determine the key's identity (c="a" or key0="a")
                val keyId = getKeyIdentifier(keyElement) ?: continue
                
                // Check if we have mappings for this key
                val keyMappings = mappingsByKey[keyId.lowercase()] ?: continue

                // Apply mappings
                for (mapping in keyMappings) {
                    val attrName = dirToAttribute(mapping.direction)
                    val attrValue = XmlAttributeMapper.toXmlValue(mapping)
                    
                    // Set the attribute (overwriting existing if present)
                    keyElement.setAttribute(attrName, attrValue)
                    
                    // Also set the "indication" if provided? 
                    // UK XML uses 'indication' for bottom label. 
                    // Short swipes usually have their own corner labels automatically derived from the action symbol,
                    // but if we want to force a label, UK format is tricky.
                    // The standard UK parser derives the label from the action value.
                    // We'll trust the parser's default behavior for now to avoid clutter.
                }
            }

            // Serialize back to String
            return serializeDocument(document)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject mappings into XML", e)
            // Fallback: return original content if injection fails
            return xmlContent
        }
    }

    private fun getKeyIdentifier(element: Element): String? {
        // Check 'c' attribute first (most common)
        if (element.hasAttribute("c")) {
            return element.getAttribute("c")
        }
        // Check 'key0' attribute
        if (element.hasAttribute("key0")) {
            return element.getAttribute("key0")
        }
        return null
    }

    private fun dirToAttribute(dir: SwipeDirection): String {
        return when (dir) {
            SwipeDirection.NW -> "nw"
            SwipeDirection.N -> "n"
            SwipeDirection.NE -> "ne"
            SwipeDirection.W -> "w"
            SwipeDirection.E -> "e"
            SwipeDirection.SW -> "sw"
            SwipeDirection.S -> "s"
            SwipeDirection.SE -> "se"
        }
    }

    private fun serializeDocument(doc: Document): String {
        val transformerFactory = TransformerFactory.newInstance()
        val transformer = transformerFactory.newTransformer()
        
        // Pretty print configuration
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")

        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString()
    }
}
