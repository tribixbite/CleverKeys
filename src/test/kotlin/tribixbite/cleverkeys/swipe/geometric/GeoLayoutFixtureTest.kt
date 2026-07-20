package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Phase-1 guard for the test-only fixture parser ([GeoLayoutFixtures]): asserts it
 * reproduces the key-count / letter-classification / alias-table semantics the
 * later phases tune against. In particular it pins the ЙЦУКЕН row shape
 * (11 / 11 / 9 = 31 center letters + ё/ъ corners) — the issue-#9 regression guard.
 */
class GeoLayoutFixtureTest {

    // ── QWERTY (US) ───────────────────────────────────────────────────────────

    @Test
    fun qwertyUs_has26LetterNodes() {
        val geo = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        // 10 + 9 + 7 = 26 center letters across the three letter rows.
        assertWithMessage("QWERTY should have 26 letter nodes")
            .that(geo.letterNodeCount).isEqualTo(26)
        // Every ASCII letter must be projectable via `chars` (tier 1).
        for (c in 'a'..'z') {
            assertWithMessage("QWERTY chars must contain '$c'")
                .that(geo.chars).containsKey(c.code)
        }
    }

    @Test
    fun qwertyUs_hasBottomRowAppended() {
        val withBottom = GeoLayoutFixtures.loadShipped("latn_qwerty_us", appendBottomRow = true)
        val without = GeoLayoutFixtures.loadShipped("latn_qwerty_us", appendBottomRow = false)
        // The standard bottom row adds exactly 5 (non-letter) keys.
        assertThat(withBottom.keys.size - without.keys.size).isEqualTo(5)
        // Bottom-row keys are on a 4th row (index 3) and are NOT letter nodes.
        val bottom = withBottom.keys.filter { it.row == 3 }
        assertThat(bottom).hasSize(5)
        assertThat(bottom.all { !it.isLetterNode }).isTrue()
    }

    @Test
    fun qwertyUs_locCornersAreAliasedButNotLetters() {
        // 's' key hosts `loc ß` corner → ß enters the alias table (ß is a letter),
        // but ß is not a letter NODE (it has no center key of its own on QWERTY).
        val geo = GeoLayoutFixtures.loadShipped("latn_qwerty_us", includeLocCorners = true)
        assertWithMessage("ß should alias to a host key when loc corners are resolved")
            .that(geo.aliases).containsKey('ß'.code)
        // Dropping loc corners removes the ß alias.
        val noLoc = GeoLayoutFixtures.loadShipped("latn_qwerty_us", includeLocCorners = false)
        assertThat(noLoc.aliases).doesNotContainKey('ß'.code)
    }

    // ── ЙЦУКЕН (Русский) — issue #9 row-shape regression guard ─────────────────

    @Test
    fun jcukenRu_has31LetterNodes_rows11_11_9() {
        val geo = GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru")
        assertWithMessage("ЙЦУКЕН should have 31 center letters (11/11/9)")
            .that(geo.letterNodeCount).isEqualTo(31)

        val lettersInRow = IntArray(3)
        for (key in geo.keys) {
            if (key.isLetterNode && key.row in 0..2) lettersInRow[key.row]++
        }
        assertWithMessage("row 0 letters").that(lettersInRow[0]).isEqualTo(11)
        assertWithMessage("row 1 letters").that(lettersInRow[1]).isEqualTo(11)
        assertWithMessage("row 2 letters (shift+backspace are non-letter)").that(lettersInRow[2]).isEqualTo(9)
    }

    @Test
    fun jcukenRu_corner_yo_and_hardSign_alias_to_hosts() {
        // ё is key1 of е; ъ is key1 of ь. Both are corner letters → alias table,
        // NOT their own letter nodes.
        val geo = GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru")
        assertWithMessage("ё must alias to a host key").that(geo.aliases).containsKey('ё'.code)
        assertWithMessage("ъ must alias to a host key").that(geo.aliases).containsKey('ъ'.code)
        // The ё alias-host must be the 'е' key.
        val eKeyId = geo.chars['е'.code]?.single()
        assertThat(geo.aliases['ё'.code]).isEqualTo(eKeyId)
        // The ъ alias-host must be the 'ь' key.
        val softSignId = geo.chars['ь'.code]?.single()
        assertThat(geo.aliases['ъ'.code]).isEqualTo(softSignId)
    }

    @Test
    fun jcukenRu_ukrainian_locCorners_present_only_when_resolved() {
        // ї/ў/є/ґ/і exist ONLY as loc corners. With loc resolution they are aliased;
        // without it they are absent (this is what decides whether Ukrainian works).
        val resolved = GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru", includeLocCorners = true)
        for (cp in listOf('ї', 'ў', 'є', 'ґ', 'і')) {
            assertWithMessage("Ukrainian '$cp' must alias when loc corners resolved")
                .that(resolved.aliases).containsKey(cp.code)
        }
        val dropped = GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru", includeLocCorners = false)
        for (cp in listOf('ї', 'ў', 'є', 'ґ', 'і')) {
            assertWithMessage("Ukrainian '$cp' must be absent when loc corners dropped")
                .that(dropped.aliases).doesNotContainKey(cp.code)
        }
    }

    // ── Other shipped Latin layouts ───────────────────────────────────────────

    @Test
    fun azertyFr_has26LetterNodes_and_e_projects() {
        val geo = GeoLayoutFixtures.loadShipped("latn_azerty_fr")
        assertThat(geo.letterNodeCount).isEqualTo(26)
        assertThat(geo.chars).containsKey('e'.code)
    }

    @Test
    fun qwertzDe_has29LetterNodes_including_umlauts() {
        // QWERTZ (Deutsch) letter rows: row0 = 11 (q..ü), row1 = 11 (a..ä), row2 = 7
        // (y,x,c,v,b,n,m — shift/backspace are special keys, excluded) → 29 letters,
        // three of which are the umlaut CENTER keys ü/ö/ä.
        val geo = GeoLayoutFixtures.loadShipped("latn_qwertz_de")
        assertThat(geo.letterNodeCount).isEqualTo(29)
        for (c in listOf('ü', 'ö', 'ä')) {
            assertWithMessage("QWERTZ center letter '$c' must project")
                .that(geo.chars).containsKey(c.code)
        }
    }

    @Test
    fun dvorak_has26LetterNodes() {
        val geo = GeoLayoutFixtures.loadShipped("latn_dvorak")
        assertThat(geo.letterNodeCount).isEqualTo(26)
    }

    // ── Committed fixtures ────────────────────────────────────────────────────

    @Test
    fun weirdCustom_has_four_letter_rows_and_nonUniform_widths() {
        // 4 authored rows (bottom_row="false" so the parser does NOT append one when
        // asked not to) with letters a..g / h..n / o..u / v,w,y = 7+7+6+3 = 23 letters.
        val geo = GeoLayoutFixtures.loadFixture("weird_custom", appendBottomRow = false)
        assertThat(geo.letterNodeCount).isEqualTo(23)
        // Non-uniform widths: the wide 'a' key (1.5) is wider than 'e' (0.8).
        val a = geo.keys.first { it.label == "a" }
        val e = geo.keys.first { it.label == "e" }
        assertWithMessage("'a' hit box (width 1.5) must be wider than 'e' (width 0.8)")
            .that(a.w).isGreaterThan(e.w)
        // q / x / z are intentionally absent → per-layout vocabulary filtering.
        assertThat(geo.chars).doesNotContainKey('q'.code)
        assertThat(geo.chars).doesNotContainKey('x'.code)
        assertThat(geo.chars).doesNotContainKey('z'.code)
    }

    @Test
    fun weirdCustom_rowShift_offsets_second_row() {
        // Row 1 has shift="0.25"; its keys' centroid y must sit below row 0's by the
        // shift, and the first key of row 1 has its own key shift="0.5".
        val geo = GeoLayoutFixtures.loadFixture("weird_custom", appendBottomRow = false)
        val row0y = geo.keys.first { it.row == 0 }.cy
        val row1y = geo.keys.first { it.row == 1 }.cy
        assertThat(row1y).isGreaterThan(row0y)
    }

    @Test
    fun letterOnPunct_z_is_aliasHostOnly_not_a_letterNode() {
        // '.' key hosts a 'z' corner. z must be reachable via the alias table, and
        // the '.' key must be an alias-host-only node (non-letter center).
        val geo = GeoLayoutFixtures.loadFixture("letter_on_punct", appendBottomRow = false)
        assertWithMessage("'z' must alias to the punctuation host key")
            .that(geo.aliases).containsKey('z'.code)
        // '.' is not a letter, so it is NOT in chars and is not a letter node.
        assertThat(geo.chars).doesNotContainKey('.'.code)
        val punctKey = geo.keys[geo.aliases['z'.code]!!]
        assertWithMessage("host key of 'z' alias must be an alias-host-only node")
            .that(punctKey.isLetterNode).isFalse()
        assertThat(punctKey.label).isEmpty()
    }
}
