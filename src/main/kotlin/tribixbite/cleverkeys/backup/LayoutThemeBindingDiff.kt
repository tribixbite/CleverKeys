package tribixbite.cleverkeys.backup

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import tribixbite.cleverkeys.KeyGeometryField
import tribixbite.cleverkeys.LayoutKeyGeometry
import tribixbite.cleverkeys.LayoutThemeBinding
import tribixbite.cleverkeys.PredefinedLayoutThemes

/**
 * Per-layout theme binding changes between two `layouts` preference blobs, for the Backup &
 * Restore import preview. Pure JVM (Gson only) so the preview render is unit-testable.
 *
 * The stored `layouts` blob is an array whose entries are a plain predefined-layout name
 * (`"latn_qwerty_us"`) or an object with a `kind` (`named` / `custom` / `system`) and optional
 * `language` / `theme` binding fields (see `LayoutsPreference.Serializer`). The binding adds no
 * preference key of its own: it travels inside this already-classified JSON blob, so the
 * settings classification buckets are unchanged and only the preview needs to look inside.
 */
object LayoutThemeBindingDiff {

    /** Which kind of layout an entry is, for the preview label. */
    enum class Kind { NAMED, CUSTOM, SYSTEM }

    /**
     * One entry whose EFFECTIVE theme binding differs between the two blobs.
     *
     * @property name the predefined layout name, or the custom layout XML's `name`; null for the
     *   System entry and for a custom layout without a name.
     * @property from the current effective theme id (null = follows the global theme).
     * @property to the imported effective theme id (null = follows the global theme).
     */
    data class Change(val kind: Kind, val name: String?, val from: String?, val to: String?)

    /**
     * One entry whose EFFECTIVE key-geometry overrides ([LayoutKeyGeometry.effective]: the entry's
     * choices over its XML defaults) differ; an empty map = follows the look / global settings.
     */
    data class GeometryChange(
        val kind: Kind,
        val name: String?,
        val from: Map<KeyGeometryField, Float>,
        val to: Map<KeyGeometryField, Float>,
    )

    private data class Entry(
        val kind: Kind,
        val name: String?,
        val identity: String,
        val theme: String?,
        val geometry: Map<KeyGeometryField, Float> = emptyMap(),
    )

    private val ROOT_TAG = Regex("<keyboard(\\s[^>]*)?>")
    private val NAME_ATTRIBUTE = Regex("\\sname\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")

    /** True when [array] has the stored `layouts` shape (names and `{"kind":…}` objects). */
    fun isLayoutEntries(array: JsonArray): Boolean = array.size() > 0 && array.all { element ->
        (element.isJsonPrimitive && element.asJsonPrimitive.isString) ||
            (element.isJsonObject && element.asJsonObject.has("kind"))
    }

    /**
     * Entries present in both blobs (matched by layout identity, in order of occurrence) whose
     * effective theme binding changes. Added/removed layouts are not theme changes and are left
     * to the generic count. An unparseable entry is skipped, never thrown.
     */
    fun themeChanges(current: JsonArray, proposed: JsonArray): List<Change> =
        matched(current, proposed).mapNotNull { (c, p) ->
            if (c.theme != p.theme) Change(p.kind, p.name, c.theme, p.theme) else null
        }

    /**
     * Entries present in both blobs whose effective key-geometry overrides change (the same
     * matching as [themeChanges]). Values in the stored blob are sanitised exactly as loading
     * sanitises them ([LayoutKeyGeometry.normalizeEntry]): an out-of-range value from a file
     * is dropped there and therefore never previewed as a change.
     */
    fun geometryChanges(current: JsonArray, proposed: JsonArray): List<GeometryChange> =
        matched(current, proposed).mapNotNull { (c, p) ->
            if (c.geometry != p.geometry) GeometryChange(p.kind, p.name, c.geometry, p.geometry) else null
        }

    /** Pairs (current, proposed) of entries matched by identity, in order of occurrence. */
    private fun matched(current: JsonArray, proposed: JsonArray): List<Pair<Entry, Entry>> {
        val unmatched = entriesOf(current).groupBy { it.identity }.mapValues { it.value.toMutableList() }
        return entriesOf(proposed).mapNotNull { p -> unmatched[p.identity]?.removeFirstOrNull()?.let { it to p } }
    }

    /** The entry's stored `key_geometry` object as JSON-like values, or null. */
    private fun geometryEntryOf(obj: com.google.gson.JsonObject): Map<String, Any?>? {
        val g = obj.get(LayoutKeyGeometry.JSON_FIELD)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return g.entrySet().associate { (k, v) ->
            k to when {
                v.isJsonNull || !v.isJsonPrimitive -> null
                v.asJsonPrimitive.isBoolean -> v.asBoolean
                v.asJsonPrimitive.isNumber -> v.asDouble
                else -> v.asString
            }
        }
    }

    private fun entriesOf(array: JsonArray): List<Entry> = array.mapNotNull(::entryOf)

    private fun entryOf(element: JsonElement): Entry? = try {
        if (element.isJsonPrimitive) {
            val name = element.asString
            if (name == "system") Entry(Kind.SYSTEM, null, "system", null)
            // A predefined layout's XML default (a familiar-style layout's look) is its binding.
            else Entry(Kind.NAMED, name, "named:$name", PredefinedLayoutThemes.declaredThemeOf(name))
        } else {
            val obj = element.asJsonObject
            val entryTheme = obj.get("theme")?.takeIf { !it.isJsonNull }?.asString
            val entryGeometry = LayoutKeyGeometry.normalizeEntry(geometryEntryOf(obj))
            when (obj.get("kind")?.asString) {
                "named" -> {
                    val name = obj.get("name").asString
                    Entry(
                        Kind.NAMED, name, "named:$name",
                        LayoutThemeBinding.effective(entryTheme, PredefinedLayoutThemes.declaredThemeOf(name)),
                        // Predefined layouts declare no key geometry.
                        LayoutKeyGeometry.effective(entryGeometry, emptyMap()),
                    )
                }
                "custom" -> {
                    val xml = obj.get("xml").asString
                    val name = xmlName(xml)
                    // A named custom layout keeps its identity across edits; an unnamed one is
                    // identified by its content.
                    val identity = "custom:" + (name ?: "#" + xml.hashCode())
                    Entry(
                        Kind.CUSTOM, name, identity,
                        LayoutThemeBinding.effective(entryTheme, LayoutThemeBinding.declaredThemeOfXml(xml)),
                        LayoutKeyGeometry.effective(
                            entryGeometry, LayoutKeyGeometry.declaredValues(LayoutKeyGeometry.declaredOfXml(xml)),
                        ),
                    )
                }
                else -> Entry(
                    Kind.SYSTEM, null, "system", LayoutThemeBinding.effective(entryTheme, null),
                    LayoutKeyGeometry.effective(entryGeometry, emptyMap()),
                )
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun xmlName(xml: String): String? {
        val attributes = ROOT_TAG.find(xml)?.groupValues?.get(1) ?: return null
        val match = NAME_ATTRIBUTE.find(attributes) ?: return null
        return (match.groups[2]?.value ?: match.groups[3]?.value)?.takeIf { it.isNotBlank() }
    }
}
