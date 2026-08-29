package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.JsonParser
import org.junit.BeforeClass
import org.junit.Test
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader
import java.io.File
import java.util.zip.ZipFile

/**
 * The 32-frame emission budget, swept over every lexicon the app SHIPS.
 *
 * ## What is being measured, and why it cannot be reasoned about
 *
 * The exported encoder emits a fixed `[1, 32, 65]`, so a word needs
 * `length + (adjacent duplicate pairs)` frames ([CtcDecodableLength]) and one needing more than 32
 * has **no valid alignment at all**: the beam cannot produce it from any trace. The failure is
 * completely silent — the word sits in the trie, occupies nodes, and is simply never offered.
 * Nothing distinguishes it from a badly-swiped gesture, which is why the question has to be
 * answered by a sweep and not by intuition about how long words get.
 *
 * [CtcImportedPackSupportTest] closed this for the IMPORTED Latin packs (nl/id/ms/sw/tl, zero over
 * budget). It was still open for the lexicons that ship in the APK — the set that matters most,
 * because those are the words every user has without importing anything. This class closes it for
 * all eight bundled languages and for the injected contraction alias keys.
 *
 * ## The sweep is over the EMISSION SURFACE, not the dictionary form
 *
 * The budget applies to what the beam spells, so each language is swept through the exact
 * projection `CtcEngineAdapter.buildTrie` applies to it. Sweeping canonical forms would measure
 * strings the decoder never emits, in both directions:
 *
 *  * **en** ([CtcLanguageSupport.LexiconSource.EN_JSON]) — `dictionaries/en_enhanced.json` through
 *    the STRIP policy (`CtcLexiconTrie.loadStrippingNonAlphabet`): lowercase, keep a–z, drop
 *    everything else. `aaron's` is swept as `aarons`, one frame SHORTER than its dictionary form.
 *    No accent folding — that asymmetry is the shipped en branch and must not be "unified".
 *  * **fr/de/es/it/pt/sv** ([CtcLanguageSupport.LexiconSource.CKDT_BIN]) — the bundled
 *    `<lang>_enhanced.bin` through [CtcAzProjection.project]. `ß→ss` and `æ→ae` EXPAND, so the
 *    projection can make a word LONGER than its canonical form. That is the direction that could
 *    push a German compound over the line, and it is the reason the surface is what gets swept.
 *  * **ru** ([CtcLanguageSupport.LexiconSource.CKDT_LANGPACK]) — the `langpack-ru` pack through
 *    [CtcScriptProjection]'s per-script rules (ё→е, ъ→ь folds, NO NFD). The Cyrillic folds are
 *    one-to-one so they cannot lengthen a word, but the projection DROPS words with no
 *    Cyrillic-key spelling, and sweeping the unprojected list would measure words the trie never
 *    holds.
 *
 * Alias keys are swept as their own group: [CtcContractionKeys.inject] inserts them into the same
 * trie as ordinary paths, so they are under the same budget, and the French/Italian productive
 * elisions (`d'abaissement` → `dabaissement`) are the longest pseudo-words the engine ever spells.
 *
 * ## Result (measured 2026-08-29, ARC-057)
 *
 * **Zero surfaces over budget, in every bundled lexicon and every alias table.** Frames of 32:
 *
 * | source      | surfaces | max frames | worst surface               |
 * |-------------|---------:|-----------:|-----------------------------|
 * | en          |   98,122 |         23 | `pricewaterhousecoopers`    |
 * | fr          |   37,958 |         22 | `professionnellement`       |
 * | de          |   39,594 |         26 | `wirtschaftswissenschaften` |
 * | es          |   47,955 |         20 | `inconstitucionalidad`      |
 * | it          |   39,657 |         23 | `internazionalizzazione`    |
 * | pt          |   38,996 |         21 | `inconstitucionalidade`     |
 * | sv          |   39,183 |         25 | `decemberoverenskommelsen`  |
 * | ru          |   49,704 |         24 | `высококвалифицированных`   |
 * | en aliases  |    1,848 |         15 | `administrations`           |
 * | fr aliases  |   18,126 |         23 | `dinstitutionnalisation`    |
 * | de aliases  |       21 |          8 | `destaing`                  |
 * | it aliases  |   21,357 |     **28** | `dellelettroencefalogramma` |
 *
 * es/pt/sv ship no contraction table at all, so they contribute no alias keys — see
 * `BundledContractionDataTest` for why that is correct and not a gap.
 *
 * The **tightest headroom in the whole app is 4 frames**, on the Italian alias key
 * `dellelettroencefalogramma`; the tightest real word is German's `wirtschaftswissenschaften` at
 * 6. So the budget is not comfortable-by-a-mile, and [MAX_FRAMES_EARLY_WARNING] turns the next two
 * frames of erosion into a test failure rather than a silent disappearance.
 *
 * The en count is JSON ENTRIES, not distinct surfaces: the strip policy maps `dont` and `don't`
 * onto one path and the trie dedupes on insert. Every entry is measured anyway, because every
 * entry is something the loader tries to insert.
 *
 * The worst words are recorded above but deliberately NOT asserted. Pinning them would make this a
 * dictionary-regeneration tripwire — a job `CtcCkdtLexiconTest` already does, exactly — instead of
 * a budget check. What is asserted is the invariant (nothing over budget), the sweep's own
 * non-vacuity, and the early-warning band.
 *
 * ## Falsifiability
 *
 * A discovery sweep whose expected result is green proves nothing unless it can be shown to go
 * red. Two negative controls do that through the SAME [sweep] function the real lexicons use:
 * synthetic words of both failing shapes ([aSyntheticOverBudgetWordIsFlaggedByTheSameSweep]), and
 * one over-long word hidden among French's 37,958 real ones
 * ([oneOverBudgetWordAmongARealLexiconStillFailsTheSweep]).
 *
 * Pure JVM; the pure-test CWD is the project root, so the shipped assets and the langpack zips are
 * reachable by relative `File` paths (same convention as [CtcCkdtLexiconTest]).
 */
class CtcBundledLexiconEmitBudgetTest {

    /**
     * One lexicon's emission surfaces measured against the budget.
     *
     * @property source the lexicon's name, as it appears in a failure message.
     * @property swept how many emission surfaces were measured. Asserted non-trivial, because a
     *   sweep that silently loaded nothing satisfies every budget assertion vacuously.
     * @property overBudget the surfaces needing more than [CtcDecodableLength.EMISSION_FRAMES]
     *   frames — THE result, and it must be empty.
     * @property maxFrames the largest frame requirement seen.
     * @property worst the surface that needed [maxFrames].
     */
    class Sweep(
        val source: String,
        val swept: Int,
        val overBudget: List<String>,
        val maxFrames: Int,
        val worst: String,
    ) {
        /**
         * The deliverable if this ever fires: which words, how many frames each needs, and what
         * the maintainer must NOT do about it. Built eagerly (Truth takes a message value, not a
         * lambda), which is cheap because only the ten worst offenders are formatted.
         */
        fun failureMessage(): String = buildString {
            append("$source: ${overBudget.size} of $swept emission surfaces need more than ")
            append("${CtcDecodableLength.EMISSION_FRAMES} frames and are therefore UNDECODABLE — ")
            append("they occupy the trie and can never be swiped, with no error and no log. ")
            append("Worst offenders: ")
            append(
                overBudget.sortedByDescending { CtcDecodableLength.framesRequired(it) }
                    .take(10)
                    .joinToString(", ") { "$it=${CtcDecodableLength.framesRequired(it)}f" }
            )
            append(". Longest surface within budget: $worst=${maxFrames}f. ")
            append("Do NOT resolve this by editing a decoder constant: the budget IS the exported ")
            append("model's `log_emissions [1, 32, 65]` and moving it requires a re-export.")
        }
    }

    private companion object {
        /** The shipped APK assets — en's JSON and the six bundled CKDT dictionaries. */
        const val DICT_DIR = "src/main/assets/dictionaries"

        /** Where the langpack zips live; ru's lexicon is delivered as one. */
        const val LANGPACK_DIR = "scripts/dictionaries"

        /** [CtcLanguageSupport.LexiconSource.CKDT_BIN] languages, in table order. */
        val CKDT_LANGUAGES = listOf("fr", "de", "es", "it", "pt", "sv")

        /** Every bundled language, in [CtcLanguageSupport.SUPPORTED]'s order. */
        val BUNDLED_LANGUAGES = listOf("en") + CKDT_LANGUAGES + "ru"

        /**
         * Languages that ship a contraction table. es/pt/sv deliberately ship none — Spanish
         * amalgams carry no apostrophe, Portuguese apostrophe forms are not swipeable, and Swedish
         * reduced forms are written solid (`BundledContractionDataTest` pins all three).
         */
        val ALIAS_LANGUAGES = listOf("en", "fr", "de", "it")

        /** language → the bundled lexicon's sweep. */
        lateinit var sweeps: Map<String, Sweep>

        /** language → the injected contraction alias keys' sweep. */
        lateinit var aliasSweeps: Map<String, Sweep>

        /**
         * Lower bound on each lexicon's swept surface count. Deliberately far below the real sizes
         * (37,958–98,122): the point is to catch a sweep that loaded nothing, not to pin a
         * vocabulary size — [CtcCkdtLexiconTest] pins the projection counts exactly and
         * duplicating that here would make one dictionary regeneration fail two tests for one
         * reason.
         */
        const val MIN_SURFACES = 20_000

        /**
         * Lower bound on each lexicon's longest surface, in frames. A projection bug that
         * truncated every word would still report "zero over budget"; this is what makes the green
         * mean something. 16 is comfortably under every measured max (20–26) and comfortably over
         * anything a truncation would leave.
         */
        const val MIN_MAX_FRAMES = 16

        /**
         * The EARLY-WARNING band, two frames below the hard budget.
         *
         * The budget itself only fails at 33 frames, by which point words have already gone
         * silently missing for whoever regenerated the dictionary. The observed worst across
         * everything shipped is 28 (`dellelettroencefalogramma`), so a max of 31 means the next
         * lexicon is one compound away from losing words. Failing at 31 converts that into a
         * decision someone makes deliberately.
         *
         * This is a HEADROOM signal, not the budget — a failure here does not mean anything is
         * broken today.
         */
        const val MAX_FRAMES_EARLY_WARNING = 30

        @JvmStatic
        @BeforeClass
        fun sweepEveryBundledLexicon() {
            val lexicons = LinkedHashMap<String, Sweep>()
            lexicons["en"] = sweep("en", enSurfaces())
            for (lang in CKDT_LANGUAGES) lexicons[lang] = sweep(lang, ckdtAzSurfaces(lang))
            lexicons["ru"] = sweep("ru", ruSurfaces())
            sweeps = lexicons
            aliasSweeps = ALIAS_LANGUAGES.associateWith {
                sweep("$it aliases", injectableAliasKeys(it))
            }
        }

        /** Measure [surfaces] against the budget. The ONE sweep implementation. */
        fun sweep(source: String, surfaces: List<String>): Sweep {
            var maxFrames = 0
            var worst = ""
            val over = ArrayList<String>()
            for (surface in surfaces) {
                val frames = CtcDecodableLength.framesRequired(surface)
                if (frames > maxFrames) {
                    maxFrames = frames
                    worst = surface
                }
                if (!CtcDecodableLength.isDecodable(surface)) over.add(surface)
            }
            return Sweep(source, surfaces.size, over, maxFrames, worst)
        }

        /**
         * en's trie surfaces: `en_enhanced.json`'s keys under the STRIP policy
         * (`CtcLexiconTrie.loadStrippingNonAlphabet` — lowercase, keep a–z, drop the rest). Words
         * that strip to nothing are dropped, exactly as the loader drops them.
         */
        fun enSurfaces(): List<String> {
            val file = File("$DICT_DIR/en_enhanced.json")
            check(file.isFile) { "expected shipped lexicon at ${file.path} (run from project root)" }
            val root = JsonParser.parseString(file.readText()).asJsonObject
            val out = ArrayList<String>(root.size())
            for ((word, _) in root.entrySet()) {
                val stripped = buildString {
                    for (ch in word.lowercase()) if (ch in 'a'..'z') append(ch)
                }
                if (stripped.isNotEmpty()) out.add(stripped)
            }
            return out
        }

        /** A bundled CKDT dictionary's a–z surfaces — the [CtcAzProjection] branch. */
        fun ckdtAzSurfaces(language: String): List<String> {
            val bin = File("$DICT_DIR/${language}_enhanced.bin")
            check(bin.isFile) { "expected shipped lexicon at ${bin.path} (run from project root)" }
            return CtcAzProjection.projectLexicon(mergedLexicon(readCkdt(bin))).freqs.keys.toList()
        }

        /**
         * ru's trie surfaces: the `langpack-ru` pack through the per-script projection.
         *
         * The pack is read rather than `scripts/dictionaries/ru/ru_enhanced.bin` because the pack
         * is what the app installs and serves
         * ([CtcLanguageSupport.LexiconSource.CKDT_LANGPACK]). The two files are byte-identical
         * today; reading the served one keeps that an observation rather than an assumption.
         */
        fun ruSurfaces(): List<String> {
            val zip = File("$LANGPACK_DIR/langpack-ru.zip")
            check(zip.isFile) { "expected ${zip.path} (run from project root)" }
            val entries = ZipFile(zip).use { zf ->
                val entry = zf.getEntry("dictionary.bin")
                checkNotNull(entry) { "${zip.name} has no dictionary.bin" }
                zf.getInputStream(entry).use { CkdtDictionaryReader.readEntries(it) }
            }
            val projector =
                CtcScriptProjection.projectorFor("ru", CtcScriptSupport.alphabetFor("ru"))
            return CtcScriptProjection.projectLexicon(mergedLexicon(entries), projector)
                .freqs.keys.toList()
        }

        fun readCkdt(file: File): List<CkdtDictionaryReader.Entry> =
            file.inputStream().use { CkdtDictionaryReader.readEntries(it) }

        /**
         * The adapter's merge step with no user words and nothing disabled: `freq = max(1, 255 −
         * rank)`, then the projection. Surface COLLISIONS are resolved inside the projection, so
         * only the surfaces the trie actually holds come back — a collided-away spelling is not a
         * trie path and sweeping it would measure a word the beam cannot emit for a reason other
         * than length.
         */
        fun mergedLexicon(entries: List<CkdtDictionaryReader.Entry>): Map<String, Double> =
            CtcLexiconMerge.merge(
                CtcCkdtLexicon.frequencyPairs(entries), emptyList(), emptySet()
            )

        /**
         * The alias keys the adapter injects for [language], lowercased: the REPLACE file's keys
         * plus the APPEND file's keys, which is `ContractionManager.getAliasKeys()`'s union, plus
         * the shared pairing table for en.
         *
         * Filtered to the INJECTABLE ones ([CtcContractionKeys.isInjectable]) — a key carrying a
         * hyphen or an accent has no a–z path, is never inserted, and therefore has no budget to
         * exceed.
         */
        fun injectableAliasKeys(language: String): List<String> {
            val az = CharArray(26) { 'a' + it }
            val keys = LinkedHashSet<String>()
            val names = listOfNotNull(
                "contractions_$language.json",
                "contraction_pairs_$language.json",
                if (language == "en") "contraction_pairings.json" else null,
            )
            for (name in names) {
                val file = File("$DICT_DIR/$name")
                if (!file.isFile) continue
                val root = JsonParser.parseString(file.readText()).asJsonObject
                for ((key, _) in root.entrySet()) keys.add(key.lowercase())
            }
            return keys.filter { CtcContractionKeys.isInjectable(it, az) }
        }
    }

    // ── The sweep ─────────────────────────────────────────────────────────────────────

    @Test
    fun `every bundled Latin lexicon fits the 32-frame emission budget`() {
        for (lang in listOf("en") + CKDT_LANGUAGES) {
            val sweep = sweeps.getValue(lang)
            assertWithMessage(sweep.failureMessage()).that(sweep.overBudget).isEmpty()
        }
    }

    /**
     * ru separately, because its surface comes from a DIFFERENT projection and is the one language
     * whose swept form is not a–z. Folding it into the Latin loop would hide which of the two
     * projections regressed.
     */
    @Test
    fun `the Russian lexicon fits the budget in its projected Cyrillic form`() {
        val sweep = sweeps.getValue("ru")
        assertWithMessage(sweep.failureMessage()).that(sweep.overBudget).isEmpty()
        val cyrillic = CtcScriptSupport.alphabetFor("ru")
        assertWithMessage(
            "the ru sweep must measure Cyrillic surfaces — a Latin-alphabet fallback would mean " +
                "the projection silently produced nothing and the sweep measured the wrong thing"
        ).that(sweep.worst.all { it in cyrillic }).isTrue()
    }

    /**
     * The injected pseudo-words. They are trie paths like any other, so an over-budget alias key
     * would be an entry unreachable for a second, independent reason — invisible behind the
     * reachability accounting `BundledContractionDataTest` does, which asks whether the beam WOULD
     * emit a key, never whether it CAN.
     */
    @Test
    fun `every injected contraction alias key fits the budget`() {
        for (sweep in aliasSweeps.values) {
            assertWithMessage(sweep.failureMessage()).that(sweep.overBudget).isEmpty()
        }
        // …and the alias sweep is not vacuous. fr and it are the productive-elision tables and are
        // the reason this group is swept at all; en and de are small by nature.
        assertThat(aliasSweeps.getValue("fr").swept).isAtLeast(10_000)
        assertThat(aliasSweeps.getValue("it").swept).isAtLeast(10_000)
        assertThat(aliasSweeps.getValue("en").swept).isAtLeast(1_000)
        assertThat(aliasSweeps.getValue("de").swept).isAtLeast(10)
    }

    /**
     * The languages with no alias table contribute nothing, and that must be a STATED fact rather
     * than an accident: sweeping an empty list passes the budget assertion vacuously, so if
     * es/pt/sv ever gain a contraction file, [ALIAS_LANGUAGES] has to grow with it or those keys
     * go unswept.
     */
    @Test
    fun `the languages with no contraction table contribute no alias keys`() {
        assertThat(aliasSweeps.keys).containsExactlyElementsIn(ALIAS_LANGUAGES)
        for (lang in listOf("es", "pt", "sv")) {
            assertWithMessage(
                "$lang gained a contraction table — add it to ALIAS_LANGUAGES so its injected " +
                    "keys are swept against the emission budget"
            ).that(injectableAliasKeys(lang)).isEmpty()
        }
    }

    // ── The sweep's own credibility ───────────────────────────────────────────────────

    /**
     * A budget sweep that loaded an empty list, or one fed by a truncating projection, would
     * report "zero over budget" and be believed. Both are excluded here: every lexicon must have
     * contributed tens of thousands of surfaces AND contain a word long enough that the budget is
     * a real constraint on it.
     */
    @Test
    fun `the sweep is not vacuous`() {
        assertThat(sweeps.keys).containsExactlyElementsIn(BUNDLED_LANGUAGES).inOrder()
        for ((lang, sweep) in sweeps) {
            assertWithMessage("$lang swept only ${sweep.swept} surfaces — the lexicon did not load")
                .that(sweep.swept).isAtLeast(MIN_SURFACES)
            assertWithMessage(
                "$lang's longest surface is only ${sweep.maxFrames} frames ('${sweep.worst}') — " +
                    "a projection that truncated every word would still report zero over budget"
            ).that(sweep.maxFrames).isAtLeast(MIN_MAX_FRAMES)
        }
        // The constant the whole sweep is against, pinned where the sweep can see it: a re-export
        // with a different T' must break this class rather than silently widen its claim.
        assertThat(CtcDecodableLength.EMISSION_FRAMES).isEqualTo(32)
    }

    /**
     * The headroom signal. Nothing is broken when this fires — it means a lexicon or alias table
     * has crept to within two frames of the budget, and the next regeneration is liable to cross
     * it and lose words silently. Read the offending source's max, decide deliberately, then move
     * the band or the data. Do not move [CtcDecodableLength.EMISSION_FRAMES].
     */
    @Test
    fun `no shipped source is within two frames of the budget`() {
        for (sweep in sweeps.values + aliasSweeps.values) {
            // The tail differs by which side of the budget the source has landed on. Saying
            // "nothing is undecodable yet" while `overBudget` is non-empty would send the reader
            // looking for a headroom problem when words have already gone missing.
            val verdict = if (sweep.overBudget.isEmpty()) {
                "Nothing is undecodable yet — this is the early warning that the next long " +
                    "compound will be."
            } else {
                "${sweep.overBudget.size} surfaces are ALREADY over budget — read the budget " +
                    "test's failure, not this one."
            }
            assertWithMessage(
                "${sweep.source} now needs ${sweep.maxFrames} frames for '${sweep.worst}', " +
                    "leaving ${CtcDecodableLength.headroom(sweep.worst)} of " +
                    "${CtcDecodableLength.EMISSION_FRAMES}. $verdict"
            ).that(sweep.maxFrames).isAtMost(MAX_FRAMES_EARLY_WARNING)
        }
    }

    /**
     * The NEGATIVE CONTROL, and the reason the green above is evidence rather than a tautology.
     *
     * Two synthetic offenders go through [sweep] — the same function every real lexicon goes
     * through — and must be flagged, counted, and named in the failure message. Both shapes are
     * covered because they fail for different reasons: a plain 40-letter word exceeds the budget
     * on LENGTH, while `aaa…a` (17 letters) exceeds it on the CTC collapse rule alone, needing 33
     * frames because every adjacent pair costs a separating blank. A sweep that measured `length`
     * instead of [CtcDecodableLength.framesRequired] would catch the first and miss the second.
     */
    @Test
    fun `a synthetic over-budget word is flagged by the same sweep`() {
        val longWord = "abcdefghijklmnopqrstuvwxyzabcdefghijklmn" // 40 chars → 40 frames
        val repeated = "a".repeat(17)                             // 17 chars → 33 frames
        val fine = "gemeenteraadsverkiezingen"                    // 25 chars → 27 frames; the
                                                                  // imported-pack worst case
        assertThat(CtcDecodableLength.framesRequired(longWord)).isEqualTo(40)
        assertThat(CtcDecodableLength.framesRequired(repeated)).isEqualTo(33)
        assertThat(CtcDecodableLength.framesRequired(fine)).isEqualTo(27)

        val control = sweep("synthetic", listOf(fine, longWord, "cat", repeated))
        assertThat(control.overBudget).containsExactly(longWord, repeated).inOrder()
        assertThat(control.maxFrames).isEqualTo(40)
        assertThat(control.worst).isEqualTo(longWord)

        val message = control.failureMessage()
        assertThat(message).contains("synthetic: 2 of 4 emission surfaces")
        assertThat(message).contains("$longWord=40f")
        assertThat(message).contains("$repeated=33f")
        assertWithMessage("a word within budget must never be named as an offender")
            .that(message).doesNotContain("$fine=27f")
    }

    /**
     * The control against a REAL lexicon: one synthetic word added to French's 37,958 surfaces is
     * still found. This is the exact scenario the sweep exists for — a single over-long entry
     * slipping into a regenerated dictionary — and it shows the real sweep would fail rather than
     * dilute it away in the volume.
     */
    @Test
    fun `one over-budget word among a real lexicon still fails the sweep`() {
        val synthetic = "z".repeat(33)
        assertWithMessage("the unpoisoned French lexicon is the baseline")
            .that(sweeps.getValue("fr").overBudget).isEmpty()

        val poisoned = sweep("fr+synthetic", ckdtAzSurfaces("fr") + synthetic)
        assertThat(poisoned.swept).isEqualTo(sweeps.getValue("fr").swept + 1)
        assertThat(poisoned.overBudget).containsExactly(synthetic)
        assertThat(poisoned.failureMessage()).contains("fr+synthetic: 1 of ${poisoned.swept}")
    }
}
