package tribixbite.cleverkeys.customization

import android.util.Log
import tribixbite.cleverkeys.KeyValue

/**
 * Export XML: bakes the custom short-swipe mappings into a layout file
 * (docs/specs/profile_system_restoration.md "Export XML values").
 *
 * Each mapped slot is written with [XmlAttributeMapper.layoutValue] — the same serializer the
 * per-key dialog uses for a modified layout copy — through [LayoutSubkeyXml]'s text-level edit,
 * so the file imports back with the same actions and everything else in it (comments, the theme
 * element, characters outside the BMP) stays byte for byte. The DOM round trip used before wrote
 * those characters as numeric references, which the kxml2 parser Android uses reads back
 * truncated to 16 bits (`😀` became `함`, `XmlLayoutExporterRoundTripTest`).
 */
object XmlLayoutExporter {
    private const val TAG = "XmlLayoutExporter"

    /**
     * The exported file and the mappings it could not hold.
     *
     * @property xml the layout XML with the mappings written in
     * @property notWritten mappings on a key of this layout whose action a layout cannot hold
     *   (`XmlAttributeMapper.layoutValue` is null, e.g. a pinned-clip paste); the slot keeps the
     *   layout's own subkey. When the source XML cannot be read at all, every mapping.
     */
    data class Injection(val xml: String, val notWritten: List<ShortSwipeMapping>)

    /**
     * Write [mappings] into every `<key>` of [xmlContent] whose main value is the mapping's key
     * (matched by runtime key code, `ShortSwipeKeyCodes`: Enter's mapping is stored under its
     * glyph, which `key0="enter"` resolves to). A `removed` mapping removes the slot's
     * attributes (both spellings, `nw` and `key1`).
     */
    fun injectMappings(xmlContent: String, mappings: List<ShortSwipeMapping>): Injection {
        if (mappings.isEmpty()) return Injection(xmlContent, emptyList())
        val mains = try {
            LayoutSubkeyXml.mainValues(xmlContent)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Layout XML could not be read; exported without short swipes", e)
            return Injection(xmlContent, mappings)
        }
        val mappingsByKey = mappings.groupBy { ShortSwipeKeyCodes.canonical(it.keyCode) }
        var xml = xmlContent
        val notWritten = LinkedHashSet<ShortSwipeMapping>()
        mains.forEachIndexed { r, row ->
            row.forEachIndexed { c, main ->
                if (main == null) return@forEachIndexed
                val keyMappings = runtimeCode(main)?.let { mappingsByKey[it] }
                    ?: mappingsByKey[main.lowercase()] ?: return@forEachIndexed
                for (mapping in keyMappings) {
                    val value = if (mapping.isRemoval) null else XmlAttributeMapper.layoutValue(mapping)
                    if (value == null && !mapping.isRemoval) {
                        notWritten += mapping
                        continue
                    }
                    xml = LayoutSubkeyXml.setSubkey(xml, r, c, mapping.direction, value)
                }
            }
        }
        return Injection(xml, notWritten.toList())
    }

    /** The runtime short-swipe code of an XML key description (`enter` → Enter's glyph), or null. */
    private fun runtimeCode(description: String): String? = try {
        ShortSwipeKeyCodes.ofKey(KeyValue.getKeyByName(description.removePrefix("loc ").trim()))
    } catch (e: Exception) {
        null
    }
}
