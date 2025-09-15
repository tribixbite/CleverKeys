package tribixbite.keyboard2

import android.content.Context
import android.content.res.XmlResourceParser
import kotlinx.coroutines.*
import org.xmlpull.v1.XmlPullParser

/**
 * Keyboard layout loader for XML layout files
 * Kotlin implementation with comprehensive layout parsing
 */
class KeyboardLayoutLoader(private val context: Context) {
    
    companion object {
        private const val TAG = "KeyboardLayoutLoader"
    }
    
    private val loadedLayouts = mutableMapOf<String, KeyboardData>()
    
    /**
     * Load layout from XML resource
     */
    suspend fun loadLayout(layoutName: String): KeyboardData? = withContext(Dispatchers.IO) {
        // Check cache first
        loadedLayouts[layoutName]?.let { return@withContext it }
        
        try {
            val resourceId = getLayoutResourceId(layoutName)
            if (resourceId != 0) {
                val layout = parseLayoutXml(resourceId, layoutName)
                if (layout != null) {
                    loadedLayouts[layoutName] = layout
                }
                layout
            } else {
                logE("Layout resource not found: $layoutName")
                null
            }
        } catch (e: Exception) {
            logE("Failed to load layout: $layoutName", e)
            null
        }
    }
    
    /**
     * Get resource ID for layout name
     */
    private fun getLayoutResourceId(layoutName: String): Int {
        // Map layout names to XML resources
        val layoutResources = mapOf(
            "qwerty" to "latn_qwerty_us",
            "qwertz" to "latn_qwertz", 
            "azerty" to "latn_azerty_fr",
            "dvorak" to "latn_dvorak",
            "colemak" to "latn_colemak"
        )
        
        val resourceName = layoutResources[layoutName] ?: layoutName
        return context.resources.getIdentifier(resourceName, "xml", context.packageName)
    }
    
    /**
     * Parse XML layout file
     */
    private fun parseLayoutXml(resourceId: Int, layoutName: String): KeyboardData? {
        return try {
            val parser = context.resources.getXml(resourceId)
            parseKeyboardXml(parser, layoutName)
        } catch (e: Exception) {
            logE("Failed to parse XML layout: $layoutName", e)
            createFallbackLayout(layoutName)
        }
    }
    
    /**
     * Parse keyboard XML structure
     */
    private fun parseKeyboardXml(parser: XmlResourceParser, layoutName: String): KeyboardData {
        val rows = mutableListOf<List<KeyboardData.Key>>()
        var currentRow = mutableListOf<KeyboardData.Key>()
        
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "row" -> {
                            currentRow = mutableListOf()
                        }
                        "key" -> {
                            val key = parseKeyElement(parser)
                            currentRow.add(key)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "row" && currentRow.isNotEmpty()) {
                        rows.add(currentRow.toList())
                    }
                }
            }
            eventType = parser.next()
        }
        
        return KeyboardData(
            name = layoutName,
            keys = rows,
            width = rows.maxOfOrNull { it.size } ?: 10,
            height = rows.size
        )
    }
    
    /**
     * Parse individual key element
     */
    private fun parseKeyElement(parser: XmlResourceParser): KeyboardData.Key {
        val keyStr = parser.getAttributeValue(null, "key0") ?: "?"
        val width = parser.getAttributeFloatValue(null, "width", 1.0f)
        val shift = parser.getAttributeFloatValue(null, "shift", 0.0f)
        
        // Parse additional key states (shift, fn, etc.)
        val keys = arrayOfNulls<KeyValue>(4)
        keys[0] = parseKeyValue(keyStr)
        
        // Check for shift/fn variants
        parser.getAttributeValue(null, "key1")?.let { keys[1] = parseKeyValue(it) }
        parser.getAttributeValue(null, "key2")?.let { keys[2] = parseKeyValue(it) }
        parser.getAttributeValue(null, "key3")?.let { keys[3] = parseKeyValue(it) }
        
        return KeyboardData.Key(keys, width, shift)
    }
    
    /**
     * Parse key value from string
     */
    private fun parseKeyValue(keyStr: String): KeyValue {
        return when {
            keyStr.length == 1 -> KeyValue.makeCharKey(keyStr[0])
            keyStr.startsWith("f") && keyStr.length > 1 -> {
                // Function key
                val code = keyStr.drop(1).toIntOrNull() ?: 0
                KeyValue.makeEventKey(code)
            }
            keyStr == "shift" -> KeyValue.makeModifierKey(1)
            keyStr == "enter" -> KeyValue.makeEventKey(android.view.KeyEvent.KEYCODE_ENTER)
            keyStr == "backspace" -> KeyValue.makeEventKey(android.view.KeyEvent.KEYCODE_DEL)
            keyStr == "space" -> KeyValue.makeCharKey(' ')
            else -> KeyValue.makeStringKey(keyStr)
        }
    }
    
    /**
     * Create fallback layout if XML parsing fails
     */
    private fun createFallbackLayout(layoutName: String): KeyboardData {
        logW("Creating fallback layout for: $layoutName")
        return KeyboardData.createDefaultQwerty()
    }
    
    /**
     * Get all available layouts
     */
    fun getAvailableLayouts(): List<String> {
        return listOf("qwerty", "qwertz", "azerty", "dvorak", "colemak")
    }
    
    /**
     * Clear layout cache
     */
    fun clearCache() {
        loadedLayouts.clear()
        logD("Layout cache cleared")
    }
}