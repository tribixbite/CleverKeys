package tribixbite.cleverkeys.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for `computeShortSwipeDiff`. Exercises the
 * `<keyCode>:<direction>` flat-map diff over the nested short-swipe
 * customization JSON shape.
 */
class ShortSwipeDiffTest {

    private fun blob(vararg entries: Pair<String, String>): String {
        // entries: "<keyCode>:<direction>" -> "<actionValue>" — minimal shape.
        val grouped = entries.groupBy { it.first.substringBefore(':') }
        val sb = StringBuilder("""{"version":2,"mappings":{""")
        var firstKey = true
        for ((keyCode, kvs) in grouped) {
            if (!firstKey) sb.append(",")
            firstKey = false
            sb.append("\"").append(keyCode).append("\":{")
            var firstDir = true
            for ((id, value) in kvs) {
                if (!firstDir) sb.append(",")
                firstDir = false
                val dir = id.substringAfter(':')
                sb.append("\"").append(dir).append("\":{")
                sb.append("\"displayText\":\"$value\",")
                sb.append("\"actionType\":\"TEXT\",")
                sb.append("\"actionValue\":\"$value\",")
                sb.append("\"useKeyFont\":false")
                sb.append("}")
            }
            sb.append("}")
        }
        sb.append("}}")
        return sb.toString()
    }

    @Test
    fun added_removed_unchanged_classification() {
        val cur = blob("a:NE" to "1", "a:SW" to "old", "b:E" to "x")
        val prop = blob("a:NE" to "1", "c:N" to "new")     // SW removed, a:NE unchanged, c:N added, b:E removed

        val d = computeShortSwipeDiff(cur, prop)!!
        assertThat(d.added).containsExactly("c:N")
        assertThat(d.removed).containsExactly("a:SW", "b:E").inOrder()
        assertThat(d.changed).isEmpty()
        assertThat(d.unchanged).isEqualTo(1)
    }

    @Test
    fun changed_when_actionValue_differs() {
        val cur = blob("a:NE" to "old_text")
        val prop = blob("a:NE" to "new_text")

        val d = computeShortSwipeDiff(cur, prop)!!
        assertThat(d.added).isEmpty()
        assertThat(d.removed).isEmpty()
        assertThat(d.changed).containsExactly("a:NE")
        assertThat(d.unchanged).isEqualTo(0)
    }

    @Test
    fun emptyDiff_whenIdentical() {
        val same = blob("a:NE" to "1", "b:SW" to "2")
        val d = computeShortSwipeDiff(same, same)!!
        assertThat(d.isEmpty).isTrue()
        assertThat(d.unchanged).isEqualTo(2)
    }

    @Test
    fun nullInput_returnsNull() {
        assertThat(computeShortSwipeDiff(null, blob())).isNull()
        assertThat(computeShortSwipeDiff(blob(), null)).isNull()
        assertThat(computeShortSwipeDiff(null, null)).isNull()
    }

    @Test
    fun malformedInput_returnsNull() {
        assertThat(computeShortSwipeDiff("not json", blob())).isNull()
        assertThat(computeShortSwipeDiff(blob(), """{"foo":1}""")).isNull()  // missing "mappings"
    }

    @Test
    fun missingMappingsKey_returnsNull() {
        val empty = """{"version":2}"""
        assertThat(computeShortSwipeDiff(empty, blob("a:NE" to "1"))).isNull()
    }

    @Test
    fun bulkDiff_handlesManyMappings() {
        // 10 current, 12 proposed → 5 unchanged, 5 changed, 7 added, 5 removed.
        val cur = blob(
            "a:N" to "1", "a:S" to "2", "a:E" to "3", "a:W" to "4", "a:NE" to "5",
            "b:N" to "x", "b:S" to "y", "b:E" to "z", "b:W" to "w", "b:NE" to "v",
        )
        val prop = blob(
            "a:N" to "1", "a:S" to "2", "a:E" to "3", "a:W" to "4", "a:NE" to "5",
            "b:N" to "X", "b:S" to "Y", "b:E" to "Z", "b:W" to "W", "b:NE" to "V",
            "c:N" to "a", "c:S" to "b", "c:E" to "c", "c:W" to "d", "c:NE" to "e",
            "d:N" to "1", "d:S" to "2",
        )
        val d = computeShortSwipeDiff(cur, prop)!!
        assertThat(d.added).hasSize(7)         // c:* (5) + d:N, d:S (2)
        assertThat(d.removed).isEmpty()        // nothing in cur but not in prop
        assertThat(d.changed).hasSize(5)       // b:* all changed values
        assertThat(d.unchanged).isEqualTo(5)   // a:*
    }
}
