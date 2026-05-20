package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.backup.ChangeType
import tribixbite.cleverkeys.backup.PrefValue
import tribixbite.cleverkeys.backup.SettingsChange

/**
 * Pure JVM tests for the import-preview render helpers in
 * `BackupRestorePreviewDialogs.kt`. The helpers are `internal` (changed
 * from `private` to enable testing) so direct unit tests can verify
 * the JSON-blob diff output without spinning up a Compose runtime.
 *
 * Covers:
 *   - Single-side `renderJsonBlobSummary` for layouts, generic arrays,
 *     objects, malformed input.
 *   - Two-side `renderJsonBlobDelta` for layouts (added / removed /
 *     unchanged) + extra_keys-style objects + non-matching shapes.
 *   - `renderDelta` integration when both sides are JsonBlob.
 *   - Truncation of long name lists.
 */
class BackupRestorePreviewRenderTest {

    // ── renderJsonBlobSummary (single-side, ADDED rows) ──────────────────

    @Test
    fun layoutsSummary_listsLayoutNames() {
        val raw = """[{"name":"qwerty"},{"name":"dvorak"}]"""
        assertThat(renderJsonBlobSummary(raw))
            .isEqualTo("[2 layouts: qwerty, dvorak]")
    }

    @Test
    fun layoutsSummary_singularGrammar() {
        val raw = """[{"name":"qwerty"}]"""
        assertThat(renderJsonBlobSummary(raw))
            .isEqualTo("[1 layout: qwerty]")
    }

    @Test
    fun layoutsSummary_truncatesLongNameList() {
        val raw = (1..20).joinToString(",", "[", "]") {
            """{"name":"layout_with_long_name_$it"}"""
        }
        val out = renderJsonBlobSummary(raw)
        // Should mention the count and have a "+N more" suffix when truncated.
        assertThat(out).startsWith("[20 layouts:")
        assertThat(out).contains("+")
        assertThat(out).contains("more")
    }

    @Test
    fun genericArraySummary_falsBackToItemCount() {
        // Array of strings — no `name` field — falls to generic count.
        val raw = """["a","b","c"]"""
        assertThat(renderJsonBlobSummary(raw)).isEqualTo("[3 items]")
    }

    @Test
    fun emptyArraySummary() {
        assertThat(renderJsonBlobSummary("[]")).isEqualTo("[empty]")
    }

    @Test
    fun jsonObjectSummary_showsKeyCount() {
        val raw = """{"key_dollar":[5,2],"key_euro":[5,1]}"""
        assertThat(renderJsonBlobSummary(raw)).isEqualTo("{2 keys}")
    }

    @Test
    fun malformedJsonSummary_fallsBackToGenericLabel() {
        assertThat(renderJsonBlobSummary("not json at all"))
            .isEqualTo("(JSON change)")
    }

    // ── renderJsonBlobDelta (both sides, MODIFIED rows) ──────────────────

    @Test
    fun layoutsDelta_addedAndRemoved() {
        val cur = """[{"name":"qwerty"},{"name":"dvorak"}]"""
        val prop = """[{"name":"qwerty"},{"name":"azerty"}]"""
        val out = renderJsonBlobDelta(cur, prop)
        assertThat(out).contains("+ azerty")
        assertThat(out).contains("\u2212 dvorak")     // minus sign
        assertThat(out).contains("(1 unchanged)")
    }

    @Test
    fun layoutsDelta_onlyAdditions() {
        val cur = """[{"name":"qwerty"}]"""
        val prop = """[{"name":"qwerty"},{"name":"dvorak"},{"name":"azerty"}]"""
        val out = renderJsonBlobDelta(cur, prop)
        assertThat(out).contains("+ ")
        assertThat(out).doesNotContain("\u2212")     // no removed
        assertThat(out).contains("(1 unchanged)")
    }

    @Test
    fun layoutsDelta_onlyRemovals() {
        val cur = """[{"name":"qwerty"},{"name":"dvorak"},{"name":"azerty"}]"""
        val prop = """[{"name":"qwerty"}]"""
        val out = renderJsonBlobDelta(cur, prop)
        assertThat(out).contains("\u2212")            // removed marker
        assertThat(out).doesNotContain("+ ")          // no added
    }

    @Test
    fun layoutsDelta_sameNamesDifferentInternalContent_reportsInternalChanges() {
        // Same layout names, same key count, but a non-`keys` field differs
        // (e.g. `script` tag, key glyphs). Deep diff buckets these as
        // "internal changes" rather than reporting per-layout key counts.
        val cur = """[
            {"name":"qwerty","keys":[1,2,3],"script":"en"},
            {"name":"dvorak","keys":[1,2],"script":"en"}
        ]""".trimIndent()
        val prop = """[
            {"name":"qwerty","keys":[1,2,3],"script":"en-US"},
            {"name":"dvorak","keys":[1,2],"script":"en"}
        ]""".trimIndent()
        val out = renderJsonBlobDelta(cur, prop)
        assertThat(out).contains("internal changes")
        assertThat(out).contains("1 unchanged")  // dvorak unchanged
    }

    @Test
    fun layoutsDelta_keyCountChanged_reportsPerLayoutCountDelta() {
        // When a shared layout's key count changes, report the delta inline.
        val cur = """[{"name":"qwerty","keys":[1,2,3]}]"""
        val prop = """[{"name":"qwerty","keys":[1,2,3,4,5]}]"""
        val out = renderJsonBlobDelta(cur, prop)
        assertThat(out).contains("qwerty: 3\u21925 keys")  // 3→5 keys
    }

    @Test
    fun objectDelta_addedAndRemovedKeys() {
        val cur = """{"key_dollar":[5,2],"key_euro":[5,1]}"""
        val prop = """{"key_dollar":[5,2],"key_pound":[5,3]}"""
        val out = renderJsonBlobDelta(cur, prop)
        assertThat(out).contains("+ key_pound")
        assertThat(out).contains("\u2212 key_euro")
        assertThat(out).contains("(1 unchanged)")
    }

    @Test
    fun objectDelta_valueOnlyChange_reportedAsChanged() {
        val cur = """{"key_dollar":[5,2]}"""
        val prop = """{"key_dollar":[5,3]}"""
        val out = renderJsonBlobDelta(cur, prop)
        assertThat(out).contains("~ key_dollar")
    }

    @Test
    fun mixedShapeDelta_fallsBackToGenericLabel() {
        // current is array, proposed is object → no clean diff path.
        val out = renderJsonBlobDelta("""[1,2,3]""", """{"a":1}""")
        assertThat(out).isEqualTo("(JSON change)")
    }

    @Test
    fun malformedJsonDelta_fallsBackToGenericLabel() {
        val out = renderJsonBlobDelta("not json", """{"a":1}""")
        assertThat(out).isEqualTo("(JSON change)")
    }

    // ── renderDelta integration ──────────────────────────────────────────

    @Test
    fun renderDelta_bothJsonBlob_usesStructuredDiff() {
        val change = SettingsChange(
            key = "layouts",
            current = PrefValue.JsonBlob("""[{"name":"qwerty"}]"""),
            proposed = PrefValue.JsonBlob("""[{"name":"qwerty"},{"name":"dvorak"}]"""),
            type = ChangeType.MODIFIED,
        )
        val out = renderDelta(change)
        // Should NOT contain the "→" separator since the diff is presented
        // as a single combined string for JsonBlob pairs.
        assertThat(out).doesNotContain("\u2192")
        assertThat(out).contains("+ dvorak")
    }

    @Test
    fun renderDelta_unsetToJsonBlob_usesAsymmetricFallthrough() {
        // ADDED row: current is Unset, proposed is JsonBlob. Fall through
        // to the per-side render so the dialog shows "(none) → [N layouts]".
        val change = SettingsChange(
            key = "layouts",
            current = PrefValue.Unset,
            proposed = PrefValue.JsonBlob("""[{"name":"qwerty"}]"""),
            type = ChangeType.ADDED,
        )
        val out = renderDelta(change)
        assertThat(out).contains("(none)")
        assertThat(out).contains("\u2192")
        assertThat(out).contains("[1 layout: qwerty]")
    }

    @Test
    fun renderDelta_nonJsonBlob_preservesOldBehavior() {
        // Regular int change → "current → proposed" verbatim.
        val change = SettingsChange(
            key = "keyboard_height",
            current = PrefValue.IntV(30),
            proposed = PrefValue.IntV(27),
            type = ChangeType.MODIFIED,
        )
        val out = renderDelta(change)
        assertThat(out).isEqualTo("30  \u2192  27")
    }
}
