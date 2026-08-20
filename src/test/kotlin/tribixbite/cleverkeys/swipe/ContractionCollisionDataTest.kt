package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.JsonParser
import org.junit.BeforeClass
import org.junit.Test
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader
import java.io.File

/**
 * DATA-quality guards for the shipped cross-language collision sidecars
 * (`assets/dictionaries/contraction_collisions_<lang>.json`).
 *
 * ## What the sidecars are for
 *
 * A `contractions_<lang>.json` entry is REPLACE mode — the key is an alias with no reading of its
 * own, so the display form takes its slot. That is a PER-LANGUAGE judgement, and
 * `ContractionManager.loadTypingMappings` merges several languages' mappings into one map with no
 * provenance. Before the fix that meant, measured on these very assets:
 *
 *  - fr+en: typing French `dont` (a very common relative pronoun) produced `don't`
 *  - de+en: typing German `im` (in dem) produced `I'm`
 *  - de+en: typing English `hats` produced `hat's`, from de's curated clitic table
 *
 * The sidecar names, per REPLACE key, the other bundled languages whose lexicon contains it.
 * `ContractionCollisionDemotion` intersects that against the ACTIVE languages and moves any hit
 * into the PAIRED bucket, where both spellings stay reachable.
 *
 * ## Why this test recomputes instead of reading
 *
 * A generation-time artefact's characteristic failure is going STALE: someone regenerates a
 * lexicon or adds a contraction, the sidecar is not rebuilt, and the guard silently stops
 * covering the new collisions. So the exhaustiveness test below does not check the file for
 * self-consistency — it rebuilds the whole table from the shipped lexicons and asserts equality.
 * That makes `scripts/build_contraction_collisions.py` mandatory after any such change, and the
 * failure message says so.
 *
 * Pure JVM; the pure-test CWD is the project root, so shipped assets resolve by relative `File`
 * path (same convention as [BundledContractionDataTest]).
 */
class ContractionCollisionDataTest {

    private companion object {
        const val DICT_DIR = "src/main/assets/dictionaries"

        /** Every language bundling a lexicon — any of them can be the user's other language. */
        val BUNDLED = listOf("en", "de", "es", "fr", "it", "pt", "sv")

        /** language → its bundled lexicon surfaces, lowercased. */
        lateinit var lexicons: Map<String, Set<String>>

        /** language → shipped sidecar table, or absent when the language ships no REPLACE file. */
        lateinit var sidecars: Map<String, Map<String, Set<String>>>

        @BeforeClass
        @JvmStatic
        fun load() {
            lexicons = BUNDLED.associateWith { lexiconOf(it) }
            sidecars = BUNDLED.mapNotNull { lang ->
                val f = File("$DICT_DIR/contraction_collisions_$lang.json")
                if (f.isFile) lang to readSidecar(f) else null
            }.toMap()
        }

        private fun lexiconOf(lang: String): Set<String> {
            val bin = File("$DICT_DIR/${lang}_enhanced.bin")
            if (bin.isFile) {
                val entries = bin.inputStream().use { CkdtDictionaryReader.readEntries(it) }
                return entries.mapTo(HashSet()) { it.word.lowercase() }
            }
            val json = File("$DICT_DIR/${lang}_enhanced.json")
            check(json.isFile) { "no bundled lexicon for $lang (run from project root)" }
            return JsonParser.parseString(json.readText()).asJsonObject
                .entrySet().mapTo(HashSet()) { it.key.lowercase() }
        }

        private fun readSidecar(f: File): Map<String, Set<String>> =
            JsonParser.parseString(f.readText()).asJsonObject.entrySet().associate { (key, value) ->
                key.lowercase() to value.asJsonArray.mapTo(LinkedHashSet()) { it.asString }
            }

        /**
         * The EFFECTIVE REPLACE keys for `lang`, as `ContractionManager` ends up holding them.
         *
         * English must be modelled rather than read raw: its base ships as
         * `contractions_non_paired.json`, and `loadEnglishBase` then removes every key that is
         * also a base in `contraction_pairings.json` — the 2026-07-23 reclassification that
         * stopped `well` → `we'll` destroying the word "well". Reading the raw file would credit
         * English with keys it never applies and demand sidecar entries the runtime can never hit.
         */
        fun replaceKeysOf(lang: String): Set<String> {
            if (lang == "en") {
                val raw = keysOf(File("$DICT_DIR/contractions_non_paired.json"))
                val paired = keysOf(File("$DICT_DIR/contraction_pairings.json"))
                return raw - paired
            }
            val f = File("$DICT_DIR/contractions_$lang.json")
            return if (f.isFile) keysOf(f) else emptySet()
        }

        private fun keysOf(f: File): Set<String> =
            JsonParser.parseString(f.readText()).asJsonObject
                .entrySet().mapTo(HashSet()) { it.key.lowercase() }

        /** Rebuild `lang`'s whole collision table from the shipped lexicons. */
        fun recompute(lang: String): Map<String, Set<String>> =
            replaceKeysOf(lang).mapNotNull { key ->
                val hits = BUNDLED.filter { it != lang && key in lexicons.getValue(it) }
                if (hits.isEmpty()) null else key to hits.toSet()
            }.toMap()
    }

    @Test
    fun `the sidecars exist for exactly the languages that ship REPLACE mappings`() {
        // Guards the guard: if a sidecar silently disappeared, every assertion below would pass
        // vacuously for that language and the demotion would quietly cover nothing.
        for (lang in BUNDLED) {
            val hasCollisions = recompute(lang).isNotEmpty()
            val hasSidecar = lang in sidecars
            assertWithMessage(
                "$lang: has collisions=$hasCollisions but sidecar present=$hasSidecar — run " +
                    "`python3 scripts/build_contraction_collisions.py`"
            ).that(hasSidecar).isEqualTo(hasCollisions)
        }
        assertWithMessage("en/de/fr/it all ship REPLACE keys that collide, so all four must be present")
            .that(sidecars.keys).containsAtLeast("en", "de", "fr", "it")
    }

    @Test
    fun `every sidecar key is a REPLACE key of its own language`() {
        for ((lang, table) in sidecars) {
            val keys = replaceKeysOf(lang)
            for (key in table.keys) {
                assertWithMessage(
                    "$lang: collision key '$key' is not in that language's REPLACE mappings, so " +
                        "the demotion can never fire for it — the sidecar is stale"
                ).that(keys).contains(key)
            }
        }
    }

    @Test
    fun `every listed language really does contain the key in its bundled lexicon`() {
        for ((lang, table) in sidecars) {
            for ((key, languages) in table) {
                assertWithMessage("$lang: '$key' lists its OWN language, which cannot collide with itself")
                    .that(languages).doesNotContain(lang)
                for (other in languages) {
                    assertWithMessage(
                        "$lang: '$key' claims to collide with $other, but $other's bundled " +
                            "lexicon does not contain it — a demotion would fire for nothing"
                    ).that(lexicons.getValue(other)).contains(key)
                }
            }
        }
    }

    /**
     * THE DRIFT PIN. Rebuilds every table from the shipped lexicons and demands equality.
     *
     * This is the assertion that makes the generation-time design safe: a lexicon regeneration,
     * a new curated contraction, or a hand-edit of a sidecar all fail here rather than silently
     * narrowing the guard.
     */
    @Test
    fun `the sidecars match a full recomputation from the shipped lexicons`() {
        for ((lang, shipped) in sidecars) {
            val expected = recompute(lang)
            val missing = expected.keys - shipped.keys
            val extra = shipped.keys - expected.keys
            assertWithMessage(
                "$lang: ${missing.size} collisions are MISSING from the sidecar (e.g. " +
                    "${missing.take(8)}) — a real word of another active language is being " +
                    "destroyed. Run `python3 scripts/build_contraction_collisions.py`."
            ).that(missing).isEmpty()
            assertWithMessage(
                "$lang: ${extra.size} sidecar keys no longer collide (e.g. ${extra.take(8)}) — " +
                    "stale entries demote mappings that are now safe. Regenerate."
            ).that(extra).isEmpty()
            for ((key, languages) in expected) {
                assertWithMessage("$lang: '$key' colliding-language list drifted")
                    .that(shipped[key]).isEqualTo(languages)
            }
        }
    }

    /**
     * The specific regressions this whole mechanism exists to prevent, named so a future change
     * that loses them fails with the word rather than with a count.
     */
    @Test
    fun `the measured cross-language casualties are covered`() {
        val en = sidecars.getValue("en")
        assertWithMessage("`im` is a common German word and was being rewritten to I'm")
            .that(en["im"]).contains("de")
        assertWithMessage("`dont` is a common French relative pronoun and was being rewritten to don't")
            .that(en["dont"]).contains("fr")

        val de = sidecars.getValue("de")
        assertWithMessage("`hats` is an English word and de's curated clitic table rewrote it to hat's")
            .that(de["hats"]).contains("en")

        val fr = sidecars.getValue("fr")
        assertWithMessage(
            "`rendezvous` is why this mechanism was built — it is legitimate French, an English " +
                "lexicon word and a German one, and was held out of the shipped data entirely " +
                "until the demotion existed to protect it"
        ).that(fr["rendezvous"]).containsExactly("de", "en")
    }

    @Test
    fun `demotion stays a rounding error against the size of the replace tables`() {
        // Adversarial check on the design: if a large fraction of REPLACE keys demoted, the fix
        // would itself be the regression — users would stop getting elisions they rely on.
        // Worst real pair is fr+en. Recorded as a ratchet so a lexicon change that suddenly
        // demotes thousands is caught rather than shipped.
        val frKeys = replaceKeysOf("fr").size
        val frCollisions = sidecars.getValue("fr").count { (_, langs) -> "en" in langs }
        assertWithMessage("fr REPLACE keys colliding with en: $frCollisions of $frKeys")
            .that(frCollisions).isLessThan(frKeys / 50) // < 2%
        assertWithMessage("but it must not be zero, or the guard is not doing anything")
            .that(frCollisions).isGreaterThan(50)
    }
}
