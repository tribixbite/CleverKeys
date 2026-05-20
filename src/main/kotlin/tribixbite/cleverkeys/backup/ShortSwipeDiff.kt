package tribixbite.cleverkeys.backup

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Structured diff between two short-swipe customization JSON blobs.
 *
 * Each `MappingId` is `<keyCode>:<direction>` (e.g. `"a:NE"`), matching
 * the storage-key format used by `ShortSwipeCustomizationManager`.
 *
 *   - `added`     — mapping ids present in proposed but not in current
 *   - `removed`   — mapping ids present in current but not in proposed
 *   - `changed`   — same id in both, but the mapping body differs
 *                   (any of displayText / actionType / actionValue /
 *                   useKeyFont changed)
 *   - `unchanged` — count of mapping ids that are byte-identical
 *
 * Empty diff (all four zero/empty) means nothing changes — the dialog
 * can hide the short-swipe section entirely in that case.
 */
data class ShortSwipeDiff(
    val added: List<String>,
    val removed: List<String>,
    val changed: List<String>,
    val unchanged: Int,
) {
    val isEmpty: Boolean
        get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}

/**
 * Compute a [ShortSwipeDiff] between a current and a proposed
 * short-swipe customization JSON blob. The JSON shape is
 * `{ "version": N, "mappings": { "<keyCode>": { "<direction>": { ... } } } }`.
 *
 * Returns `null` if either input is `null`, missing the `mappings`
 * object, or malformed — the dialog falls back to just the count + radio
 * when null.
 */
fun computeShortSwipeDiff(currentJson: String?, proposedJson: String?): ShortSwipeDiff? {
    if (currentJson == null || proposedJson == null) return null
    return try {
        val cur = flattenShortSwipe(JsonParser.parseString(currentJson))
        val prop = flattenShortSwipe(JsonParser.parseString(proposedJson))
        if (cur == null || prop == null) return null

        val added = (prop.keys - cur.keys).sorted()
        val removed = (cur.keys - prop.keys).sorted()
        val both = cur.keys intersect prop.keys
        val changed = both.filter { cur[it] != prop[it] }.sorted()
        val unchanged = both.size - changed.size

        ShortSwipeDiff(added, removed, changed, unchanged)
    } catch (_: Exception) {
        null
    }
}

/**
 * Flatten the nested `mappings` object into a flat
 * `Map<"<keyCode>:<direction>", DirectionMappingBody>` for byte-comparison.
 * Returns `null` if the input isn't the expected shape — caller falls
 * through to the no-diff path.
 */
private fun flattenShortSwipe(root: com.google.gson.JsonElement): Map<String, JsonObject>? {
    if (!root.isJsonObject) return null
    val mappings = root.asJsonObject.getAsJsonObject("mappings") ?: return null
    val flat = mutableMapOf<String, JsonObject>()
    for ((keyCode, byDirection) in mappings.entrySet()) {
        if (!byDirection.isJsonObject) continue
        for ((direction, body) in byDirection.asJsonObject.entrySet()) {
            if (body.isJsonObject) {
                flat["$keyCode:$direction"] = body.asJsonObject
            }
        }
    }
    return flat
}
