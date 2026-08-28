package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.JsonParser
import org.junit.Test
import java.io.File

/**
 * ARC-010: the shipped `assets/bigrams/<lang>_bigrams.json` tables are the source of truth
 * for the next-word cold-start seed, so their SCHEMA and the merge policy that
 * consumes them are pinned here — against the real files, not a fixture.
 *
 * Context for why this test exists at all: these six assets shipped in
 * 2025-11 and were read by NOTHING until 2026-08-28. The only loader that
 * referenced them (`BigramModel.loadFromFile`) had zero callers AND parsed a
 * different format (whitespace-delimited plain text), so "the assets load"
 * was never true and could not have been noticed. Both halves — schema and
 * wiring — need a standing guard.
 *
 * Project root as CWD (same convention as [TestRunnerListDriftTest]).
 */
class StaticBigramSeedTest {

    private companion object {
        const val ASSET_DIR = "src/main/assets/bigrams"

        /** Every shipped static bigram asset. */
        val ASSET_LANGUAGES = listOf("de", "en", "es", "fr", "it", "pt")

        /** Languages with a hardcoded fallback table in `BigramModel.kt`. */
        val HARDCODED_LANGUAGES = mapOf(
            "en" to "English",
            "es" to "Spanish",
            "fr" to "French",
            "de" to "German",
        )

        /**
         * Hardcoded-only pairs per language: present in `BigramModel`'s table
         * but NOT in the shipped asset. These are the entries the merge policy
         * keeps as gap fillers — pinned by count so that "the asset supersedes
         * the table" can never quietly become "the asset DELETES curated pairs".
         */
        val EXPECTED_GAP_FILLERS = mapOf("en" to 16, "es" to 6, "fr" to 2, "de" to 1)

        /**
         * Usable pair count and DROPPED entry count per shipped asset.
         *
         * The dropped entries are real and deliberate: `fr` files a unigram
         * (`"c'est"`) and two phrases (`"il y a"`, `"s'il vous plaît"`), and
         * `it` files five unigrams (`"c'è"`, `"nel"`, `"nella"`, `"del"`,
         * `"della"`). Neither shape has a single previous word to key on, so
         * the seed cannot use them. Pinned so a later asset edit that adds a
         * phrase entry surfaces here instead of vanishing silently.
         */
        val EXPECTED_USABLE = mapOf(
            "de" to 96, "en" to 319, "es" to 119, "fr" to 96, "it" to 77, "pt" to 79
        )
        val EXPECTED_DROPPED = mapOf(
            "de" to 0, "en" to 0, "es" to 0, "fr" to 3, "it" to 5, "pt" to 0
        )
    }

    // ------------------------------------------------------------- fixtures

    private fun assetFile(language: String): File {
        val file = File(ASSET_DIR, "${language}_bigrams.json")
        check(file.isFile) {
            "expected shipped asset at ${file.absolutePath} (run from project root)"
        }
        return file
    }

    /** Raw asset entries, straight from the file with no normalization. */
    private fun rawAsset(language: String): Map<String, String> {
        val root = JsonParser.parseString(assetFile(language).readText()).asJsonObject
        return root.entrySet().associate { (k, v) -> k to v.toString() }
    }

    /**
     * `BigramModel`'s hardcoded pairs for a language, read out of the SOURCE —
     * the class is Android-bound (`android.util.Log`) and cannot be loaded by
     * the pure runner, and a source scan additionally pins the table against
     * silent edits.
     */
    private fun hardcodedPairs(language: String): Map<String, Float> {
        val source = File("src/main/kotlin/tribixbite/cleverkeys/BigramModel.kt").readText()
        val modelFn = HARDCODED_LANGUAGES.getValue(language)
        val start = source.indexOf("private fun initialize${modelFn}Model")
        check(start > 0) { "initialize${modelFn}Model not found in BigramModel.kt" }
        val end = source.indexOf("Unigrams = mutableMapOf", start)
        check(end > start) { "$modelFn unigram block not found after the bigram block" }

        return Regex(""""([^"]+\|[^"]+)" to ([0-9.]+)f""")
            .findAll(source.substring(start, end))
            .associate { it.groupValues[1] to it.groupValues[2].toFloat() }
    }

    // ---------------------------------------------------------- asset schema

    @Test
    fun `every shipped asset parses to its pinned usable and dropped counts`() {
        for (language in ASSET_LANGUAGES) {
            val raw = rawAsset(language)
            val parsed = StaticBigramSeed.parseAsset(assetFile(language).readText())
            assertWithMessage("$language usable pairs").that(parsed)
                .hasSize(EXPECTED_USABLE.getValue(language))
            assertWithMessage(
                "$language: entries parseAsset could not use. See EXPECTED_DROPPED — a CHANGE " +
                    "means the asset gained or lost a non-bigram entry, which is worth reading " +
                    "before this number is updated."
            ).that(raw.size - parsed.size).isEqualTo(EXPECTED_DROPPED.getValue(language))
        }
    }

    @Test
    fun `asset keys are two whitespace-separated tokens, normalized to lowercase`() {
        for (language in ASSET_LANGUAGES) {
            for (key in rawAsset(language).keys) {
                assertWithMessage("$language key '$key'").that(key).isEqualTo(key.trim())
                assertWithMessage("$language key '$key': pipe is the TABLE separator, not the asset's")
                    .that(key).doesNotContain("|")

                val split = StaticBigramSeed.splitKey(key) ?: continue // pinned drops above
                // de/pt key real orthographic capitals ("guten Tag", "vielen Dank")
                // while lookups always arrive lowercase from the committed-word
                // tracker, so the split — not the file — owns normalization.
                assertWithMessage("$language key '$key'").that(split.first)
                    .isEqualTo(split.first.lowercase())
                assertWithMessage("$language key '$key'").that(split.second)
                    .isEqualTo(split.second.lowercase())
            }
        }
    }

    @Test
    fun `case-only asset keys never collide after normalization`() {
        // Normalizing "guten Tag" → "guten tag" would silently drop one of two
        // entries if the file also carried the lowercase form.
        for (language in ASSET_LANGUAGES) {
            val normalized = rawAsset(language).keys.mapNotNull { StaticBigramSeed.splitKey(it) }
            assertWithMessage("$language: duplicate pairs after case normalization")
                .that(normalized.toSet()).hasSize(normalized.size)
        }
    }

    @Test
    fun `asset values are curated rank scores, not probabilities`() {
        // The distinction that decides where this data may be used: the values
        // are per-previous-word ORDERING scores in a narrow band, and they sum
        // well past 1.0 inside a group. Feeding them to
        // BigramModel.getContextualProbability — whose interpolation needs a
        // real P(w|prev) on the unigram scale — would pin getContextMultiplier
        // at its 10x clamp for every listed pair. This test is the standing
        // record of WHY the multiplier keeps the hardcoded table.
        for (language in ASSET_LANGUAGES) {
            val parsed = StaticBigramSeed.parseAsset(assetFile(language).readText())
            for ((key, rank) in parsed) {
                assertWithMessage("$language '$key'").that(rank).isGreaterThan(0f)
                assertWithMessage("$language '$key'").that(rank).isAtMost(1f)
            }

            val groupSums = parsed.entries
                .groupBy { StaticBigramSeed.splitKey(it.key)!!.first }
                .mapValues { (_, entries) -> entries.sumOf { it.value.toDouble() } }
            assertWithMessage(
                "$language: no previous-word group sums past 1.0 — if that ever becomes " +
                    "true these ARE probabilities and the multiplier decision must be revisited"
            ).that(groupSums.values.maxOrNull()!!).isGreaterThan(1.0)
        }
    }

    @Test
    fun `every hardcoded language has a shipped asset, and two languages gain one`() {
        assertThat(ASSET_LANGUAGES).containsAtLeastElementsIn(HARDCODED_LANGUAGES.keys)
        // it/pt have no hardcoded table at all — the asset is the only static
        // data they will ever have, which is the point of loading it.
        assertThat(ASSET_LANGUAGES.toSet() - HARDCODED_LANGUAGES.keys)
            .containsExactly("it", "pt")
    }

    // ------------------------------------------------------------ key split

    @Test
    fun `splitKey accepts both separators and rejects ambiguity`() {
        assertThat(StaticBigramSeed.splitKey("the best")).isEqualTo("the" to "best")
        assertThat(StaticBigramSeed.splitKey("the|best")).isEqualTo("the" to "best")
        assertThat(StaticBigramSeed.splitKey("  The   Best  ")).isEqualTo("the" to "best")
        assertThat(StaticBigramSeed.splitKey("c'est|le")).isEqualTo("c'est" to "le")
        assertThat(StaticBigramSeed.splitKey("guten Tag")).isEqualTo("guten" to "tag")

        // The real shapes the shipped fr/it files carry that the seed cannot use.
        assertThat(StaticBigramSeed.splitKey("il y a")).isNull()
        assertThat(StaticBigramSeed.splitKey("s'il vous plaît")).isNull()
        assertThat(StaticBigramSeed.splitKey("c'est")).isNull()
        assertThat(StaticBigramSeed.splitKey("nel")).isNull()

        assertThat(StaticBigramSeed.splitKey("a|b|c")).isNull()
        assertThat(StaticBigramSeed.splitKey("the ")).isNull()
        assertThat(StaticBigramSeed.splitKey(" |x")).isNull()
        assertThat(StaticBigramSeed.splitKey("")).isNull()
    }

    // --------------------------------------------------------- merge policy

    @Test
    fun `asset wins on conflict and the hardcoded table fills gaps`() {
        val index = StaticBigramSeed.build(
            primary = mapOf("the best" to 0.9f, "the first" to 0.8f),
            fallback = mapOf("the|best" to 0.01f, "the|world" to 0.008f)
        )
        val top = index.top("the", 10)

        // Both asset pairs plus the hardcoded-only one; the conflicting
        // "the best" carries the ASSET rank, which is why it stays first.
        assertThat(top.map { it.word }).containsExactly("best", "first", "world").inOrder()
        assertThat(top[0].rank).isEqualTo(0.9f)
        assertThat(index.pairCount).isEqualTo(3)
        assertThat(index.prevWordCount).isEqualTo(1)
    }

    @Test
    fun `the merged en index keeps every curated hardcoded pair`() {
        // ARC-010 decision, recorded here because it is a judgement call: the
        // shipped en asset carries 319 pairs to the hardcoded table's 68 and
        // covers 52 of them, so the asset is the source of truth — but the
        // remaining 16 are MERGED IN rather than dropped. They sort last inside
        // their group (their scores are on the old, much smaller scale), which
        // is the honest position for a pair the richer table declined to list.
        val hardcoded = hardcodedPairs("en")
        val asset = StaticBigramSeed.parseAsset(assetFile("en").readText())
        val index = StaticBigramSeed.build(asset, hardcoded)

        for (key in hardcoded.keys) {
            val (prev, next) = StaticBigramSeed.splitKey(key)!!
            assertWithMessage("hardcoded pair '$key' lost in the merge")
                .that(index.contains(prev, next)).isTrue()
        }
        for (key in asset.keys) {
            val (prev, next) = StaticBigramSeed.splitKey(key)!!
            assertWithMessage("asset pair '$key' missing from the merge")
                .that(index.contains(prev, next)).isTrue()
        }
    }

    @Test
    fun `gap-filler counts are pinned per language`() {
        for ((language, expected) in EXPECTED_GAP_FILLERS) {
            val assetKeys = StaticBigramSeed.parseAsset(assetFile(language).readText()).keys
            val hardcodedKeys = hardcodedPairs(language).keys
                .mapNotNull { StaticBigramSeed.splitKey(it) }
                .map { "${it.first} ${it.second}" }
                .toSet()
            assertWithMessage(
                "$language: pairs only in BigramModel's hardcoded table. A CHANGE here means " +
                    "the asset's coverage of the curated table moved — re-read the merge policy " +
                    "in StaticBigramSeed before updating this number."
            ).that((hardcodedKeys - assetKeys).size).isEqualTo(expected)
        }
    }

    // ------------------------------------------------------------- fallback

    @Test
    fun `the hardcoded table alone is a working index - the pre-load and failure state`() {
        // BigramModel's pre-load state, and what it permanently keeps when a
        // language's asset is missing or malformed.
        val index = StaticBigramSeed.build(primary = emptyMap(), fallback = hardcodedPairs("en"))
        assertThat(index.pairCount).isEqualTo(hardcodedPairs("en").size)
        assertThat(index.top("i", 3).map { it.word }).containsExactly("am", "have", "will").inOrder()
        assertThat(index.top("to", 2).map { it.word }).containsExactly("be", "have").inOrder()
    }

    @Test
    fun `an empty build is the empty index`() {
        val index = StaticBigramSeed.build(emptyMap(), emptyMap())
        assertThat(index.pairCount).isEqualTo(0)
        assertThat(index.prevWordCount).isEqualTo(0)
        assertThat(index.top("the", 3)).isEmpty()
    }

    // ---------------------------------------------------------- index rules

    @Test
    fun `continuations are ranked best-first with the word as a deterministic tie-break`() {
        val index = StaticBigramSeed.build(
            mapOf(
                "x low" to 0.10f,
                "x zebra" to 0.90f,
                "x apple" to 0.90f, // ties with zebra → alphabetical
                "x mid" to 0.50f
            )
        )
        assertThat(index.top("x", 10).map { it.word })
            .containsExactly("apple", "zebra", "mid", "low").inOrder()
    }

    @Test
    fun `top caps results, is case-insensitive, and misses cleanly`() {
        val index = StaticBigramSeed.build(mapOf("the best" to 0.9f, "the first" to 0.8f))
        assertThat(index.top("THE", 1).map { it.word }).containsExactly("best")
        assertThat(index.top("the", 0)).isEmpty()
        assertThat(index.top("the", -1)).isEmpty()
        assertThat(index.top("nosuchword", 3)).isEmpty()
    }

    @Test
    fun `real en asset answers the cold-start example from the audit`() {
        // audit 2026-08-06 §4.2-2: "day-one users should get the → best/first/most…"
        val index = StaticBigramSeed.build(
            StaticBigramSeed.parseAsset(assetFile("en").readText()),
            hardcodedPairs("en")
        )
        assertThat(index.top("the", 3).map { it.word })
            .containsExactly("same", "best", "first").inOrder()
        assertThat(index.top("i", 3).map { it.word })
            .containsExactly("am", "have", "will").inOrder()
        // it/pt gain data they never had.
        val italian = StaticBigramSeed.build(
            StaticBigramSeed.parseAsset(assetFile("it").readText())
        )
        assertThat(italian.top("io", 1).map { it.word }).containsExactly("sono")
    }

    // ------------------------------------------------- malformed-input guard

    @Test
    fun `a malformed asset degrades entry-by-entry, never fatally`() {
        val parsed = StaticBigramSeed.parseAsset(
            """
            {
              "the best": 0.9,
              "three word key": 0.8,
              "": 0.7,
              "the worst": "not a number",
              "the never": 0,
              "the huge": 42,
              "the fine": 0.4
            }
            """.trimIndent()
        )
        assertThat(parsed.keys).containsExactly("the best", "the fine")
    }
}
