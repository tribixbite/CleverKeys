package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Phase-2 tests for [LayoutProjection] — the preference-ordered tiers (§ Geometry &
 * Script Abstraction) and the per-codepoint-Unicode-script NFD Mn strip. Verifies the
 * exact cases the spec pins:
 *  - `й` never collapses to `и` (tier 1 fires before the NFD tier 3);
 *  - `ё`→`е`-host, `ъ`→`ь`-host (Cyrillic corner aliases);
 *  - é → `e` on QWERTY *and* AZERTY (tier-3 NFD);
 *  - `ς` projects verbatim (atomic letter, not stripped to `σ`);
 *  - Arabic per actual XML: `أ`→`ا`, `إ`→`غ`, `آ`→`ى`, ذ→`د`, and ء/ؤ/ئ/ة/ى via tier 1;
 *  - Ukrainian ї/є/і/ґ project on a loc-resolved JCUKEN fixture, skipped when dropped;
 *  - a letter-on-punct host, a digraph-label fallback, untypeable-word skipping, and
 *    the DEAD_LAYOUT coverage guard.
 */
class GeoProjectionTest {

    /** Convenience: project a single char and return the resolved key id, or null. */
    private fun proj(c: Char, layout: LayoutGeometry): Int? =
        LayoutProjection.projectCodepoint(LayoutProjection.foldCodepoint(c.code), layout)

    // ── Cyrillic (ЙЦУКЕН) ─────────────────────────────────────────────────────

    @Test
    fun cyrillic_shortI_projectsToItself_neverToI() {
        // THE core regression: й is a real center key. Tier 1 must resolve it to the
        // й key, NOT NFD-strip it to и (which tier 3 would do — but tier 3 never runs
        // because tier 1 already succeeded).
        val geo = GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru")
        val shortI = geo.keys.first { it.label == "й" }
        val plainI = geo.keys.first { it.label == "и" }
        val projected = proj('й', geo)
        assertWithMessage("'й' must project to its OWN key, not to 'и'")
            .that(projected).isEqualTo(shortI.id)
        assertThat(projected).isNotEqualTo(plainI.id)
    }

    @Test
    fun cyrillic_yo_and_hardSign_projectToHostKeys() {
        val geo = GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru")
        val eHost = geo.chars['е'.code]!!.single()
        val softSignHost = geo.chars['ь'.code]!!.single()
        assertWithMessage("ё → е-host").that(proj('ё', geo)).isEqualTo(eHost)
        assertWithMessage("ъ → ь-host").that(proj('ъ', geo)).isEqualTo(softSignHost)
    }

    @Test
    fun ukrainian_locCorners_projectWhenResolved_skippedWhenDropped() {
        val resolved = GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru", includeLocCorners = true)
        for (c in listOf('ї', 'є', 'і', 'ґ', 'ў')) {
            assertWithMessage("Ukrainian '$c' must project when loc corners are resolved")
                .that(proj(c, resolved)).isNotNull()
        }
        val dropped = GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru", includeLocCorners = false)
        // ї/є/і/ґ have NO NFD base on the JCUKEN centers, so dropping their loc corners
        // makes them untypeable. (ў is DELIBERATELY excluded: it NFD-decomposes to у —
        // a real center — so it stays typeable via tier 3 even without the loc corner,
        // which is correct behaviour, not a regression.)
        for (c in listOf('ї', 'є', 'і', 'ґ')) {
            assertWithMessage("Ukrainian '$c' must be untypeable when loc corners are dropped")
                .that(proj(c, dropped)).isNull()
        }
        assertWithMessage("ў stays typeable via tier-3 NFD (у) even without its loc corner")
            .that(proj('ў', dropped)).isNotNull()
        // A whole Ukrainian word projects only when the loc corners are resolved.
        assertWithMessage("'їжа' must project on the loc-resolved fixture")
            .that(LayoutProjection.project("їжа", resolved)).isNotNull()
        assertWithMessage("'їжа' must be skipped when loc corners are dropped")
            .that(LayoutProjection.project("їжа", dropped)).isNull()
    }

    // ── Latin accents (tier-3 NFD) ────────────────────────────────────────────

    @Test
    fun latinAccent_e_projectsToE_onQwertyAndAzerty() {
        val qwerty = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val azerty = GeoLayoutFixtures.loadShipped("latn_azerty_fr")
        for (geo in listOf(qwerty, azerty)) {
            val eKey = geo.chars['e'.code]!!.single()
            assertWithMessage("é must project to the 'e' key (tier-3 NFD)")
                .that(proj('é', geo)).isEqualTo(eKey)
            assertWithMessage("è must project to the 'e' key (tier-3 NFD)")
                .that(proj('è', geo)).isEqualTo(eKey)
        }
        // A whole accented word projects: "école" passes over e/c/o/l/e (all typeable).
        assertWithMessage("'école' must project on QWERTY")
            .that(LayoutProjection.project("école", qwerty)).isNotNull()
    }

    @Test
    fun germanUmlaut_projectsToCenterKey_onQwertz() {
        // QWERTZ has ü/ö/ä as CENTER keys → tier 1 (verbatim), NOT stripped to u/o/a.
        val geo = GeoLayoutFixtures.loadShipped("latn_qwertz_de")
        for (c in listOf('ü', 'ö', 'ä')) {
            val center = geo.chars[c.code]?.single()
            assertWithMessage("QWERTZ '$c' is a center key → tier 1")
                .that(proj(c, geo)).isEqualTo(center)
        }
    }

    // ── Greek final sigma ─────────────────────────────────────────────────────

    @Test
    fun greek_finalSigma_projectsVerbatim_notStrippedToSigma() {
        val geo = GeoLayoutFixtures.loadShipped("grek_qwerty")
        val finalSigmaKey = geo.chars['ς'.code]?.single()
        val sigmaKey = geo.chars['σ'.code]?.single()
        assertWithMessage("ς is a real center → projects to its own key")
            .that(proj('ς', geo)).isEqualTo(finalSigmaKey)
        assertWithMessage("ς must NOT collapse to σ").that(proj('ς', geo)).isNotEqualTo(sigmaKey)
        // Tonos accents strip via tier 3 (ώ→ω center key).
        val omegaKey = geo.chars['ω'.code]?.single()
        assertWithMessage("ώ must project to the ω key (tonos stripping)")
            .that(proj('ώ', geo)).isEqualTo(omegaKey)
    }

    // ── Arabic PC (per the actual XML) ────────────────────────────────────────

    /**
     * The DEDICATED center key id for a single-codepoint letter. `ا`/`ل` also appear
     * as tier-2 fallback components of the `لا` ligature key, so `chars[cp]` can hold
     * two ids; the dedicated key is the letter NODE whose whole label is that letter.
     */
    private fun centerKey(c: Char, layout: LayoutGeometry): Int =
        layout.keys.first { it.isLetterNode && it.label == c.toString() }.id

    @Test
    fun arabic_hamzaForms_projectToHosts_perActualXml() {
        val geo = GeoLayoutFixtures.loadShipped("arab_pc")
        // Tier-4 corner-host aliases verified against arab_pc.xml.
        assertWithMessage("أ → ا-host").that(proj('أ', geo)).isEqualTo(centerKey('ا', geo))
        assertWithMessage("إ → غ-host").that(proj('إ', geo)).isEqualTo(centerKey('غ', geo))
        assertWithMessage("آ → ى-host").that(proj('آ', geo)).isEqualTo(centerKey('ى', geo))
        assertWithMessage("ذ → د-host").that(proj('ذ', geo)).isEqualTo(centerKey('د', geo))
    }

    @Test
    fun arabic_dedicatedCenterKeys_projectViaTier1() {
        val geo = GeoLayoutFixtures.loadShipped("arab_pc")
        // ء ؤ ئ ة ى all have dedicated CENTER keys → tier 1.
        for (cp in listOf('ء', 'ؤ', 'ئ', 'ة', 'ى')) {
            val center = geo.chars[cp.code]?.single()
            assertWithMessage("Arabic U+%04X must be a tier-1 center".format(cp.code))
                .that(proj(cp, geo)).isEqualTo(center)
        }
    }

    @Test
    fun arabic_harakat_areStrippedByProjection() {
        // Harakat (U+064E fatha etc.) are Mn marks the legacy AccentNormalizer misses;
        // as free-standing combining marks they carry no key of their own, so a bare
        // harakat is untypeable — but a base letter + harakat sequence strips to the
        // base letter and projects. "دَ" (dal + fatha) → the dal key, single anchor.
        val geo = GeoLayoutFixtures.loadShipped("arab_pc")
        val dalHost = centerKey('د', geo)
        val projected = LayoutProjection.project("دَ", geo) // د + fatha
        assertWithMessage("base+harakat must project to a single anchor (the base key)")
            .that(projected).isNotNull()
        assertThat(projected!!.toList()).containsExactly(dalHost)
    }

    // ── Custom-layout rules ───────────────────────────────────────────────────

    @Test
    fun letterOnPunct_z_projectsViaAliasHost() {
        val geo = GeoLayoutFixtures.loadFixture("letter_on_punct", appendBottomRow = false)
        val host = geo.aliases['z'.code]!!
        assertWithMessage("z must project to the punctuation-key centroid via the alias table")
            .that(proj('z', geo)).isEqualTo(host)
        // The host key is an alias-host-only node (non-letter center).
        assertThat(geo.keys[host].isLetterNode).isFalse()
    }

    @Test
    fun untypeableWord_isSkipped() {
        // The weird_custom fixture intentionally omits q/x/z → any word with them is
        // silently skipped (FR-4 per-layout vocabulary filtering).
        val geo = GeoLayoutFixtures.loadFixture("weird_custom", appendBottomRow = false)
        assertWithMessage("'quiz' contains q/z (absent) → untypeable")
            .that(LayoutProjection.project("quiz", geo)).isNull()
        assertWithMessage("'bad' is all-present → typeable")
            .that(LayoutProjection.project("bad", geo)).isNotNull()
    }

    @Test
    fun digraphCenterLabel_componentCodepoints_fallBackViaTier2() {
        // A synthetic layout whose only key hosting 'c'/'h' is a "ch" digraph center:
        // both component codepoints must project to that key via tier 2.
        val builder = LayoutGeometry.Builder(aspect = 2.0f)
        builder.addRow(
            LayoutGeometry.Builder.RawRow(
                keys = listOf(
                    LayoutGeometry.Builder.RawKey(
                        centerLabel = "ch", widthUnits = 1f, shiftUnits = 0f,
                        isLetterNode = true,
                        charCodepoints = intArrayOf('c'.code, 'h'.code),
                        aliasCodepoints = IntArray(0),
                    ),
                    LayoutGeometry.Builder.RawKey(
                        centerLabel = "a", widthUnits = 1f, shiftUnits = 0f,
                        isLetterNode = true,
                        charCodepoints = intArrayOf('a'.code),
                        aliasCodepoints = IntArray(0),
                    ),
                ),
                heightUnits = 1f, shiftUnits = 0f, scaleUnits = 0f,
            )
        )
        val geo = builder.build()
        val digraphKey = geo.keys.first { it.label == "ch" }.id
        assertWithMessage("'c' falls back to the 'ch' digraph key (tier 2)")
            .that(proj('c', geo)).isEqualTo(digraphKey)
        assertWithMessage("'h' falls back to the 'ch' digraph key (tier 2)")
            .that(proj('h', geo)).isEqualTo(digraphKey)
    }

    // ── Coverage guard (DEAD_LAYOUT) ──────────────────────────────────────────

    @Test
    fun coverageGuard_measuresTypeableFraction_andFlagsDeadLayout() {
        // The typeable fraction of an English lexicon on a near-empty layout is far
        // below the deadLayoutCoverageThreshold (0.20). We measure it directly here
        // (the WarmUp path lands in Phase 3/4, but the coverage math is pure and
        // testable now via projection over a slice of the real dictionary).
        val en = GeoTestFixtures.englishCkdt()
        // A layout with only the letters "a e i o u" typeable — almost no English word
        // is all-vowels, so coverage collapses.
        val vowelOnly = vowelLayout()
        val sample = 2000
        var typeable = 0
        for (i in 0 until sample) {
            if (LayoutProjection.project(en.word(i), vowelOnly) != null) typeable++
        }
        val fraction = typeable.toFloat() / sample
        assertWithMessage("vowel-only layout coverage must be far below the dead threshold")
            .that(fraction).isLessThan(GeometricEngineConfig().deadLayoutCoverageThreshold)

        // The full QWERTY layout, by contrast, types the vast majority of English.
        val qwerty = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        var q = 0
        for (i in 0 until sample) if (LayoutProjection.project(en.word(i), qwerty) != null) q++
        assertWithMessage("QWERTY must type well above the dead threshold")
            .that(q.toFloat() / sample).isGreaterThan(0.90f)
    }

    /** A minimal 5-key layout typing only the vowels a/e/i/o/u. */
    private fun vowelLayout(): LayoutGeometry {
        val builder = LayoutGeometry.Builder(aspect = 2.0f)
        builder.addRow(
            LayoutGeometry.Builder.RawRow(
                keys = "aeiou".map { c ->
                    LayoutGeometry.Builder.RawKey(
                        centerLabel = c.toString(), widthUnits = 1f, shiftUnits = 0f,
                        isLetterNode = true, charCodepoints = intArrayOf(c.code),
                        aliasCodepoints = IntArray(0),
                    )
                },
                heightUnits = 1f, shiftUnits = 0f, scaleUnits = 0f,
            )
        )
        return builder.build()
    }
}
