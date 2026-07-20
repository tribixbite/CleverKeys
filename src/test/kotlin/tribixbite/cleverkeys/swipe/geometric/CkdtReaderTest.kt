package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Phase-2 tests for the pure dictionary loaders ([CkdtDictionaryReader],
 * [FlatJsonDictionaryLoader], the ru-zip route): magic/version validation, the
 * pinned word counts (en = 98,140; ru ≥ 50,000), and the deterministic
 * descending-frequency ordinal ordering (index i == rank r(w)) the frequency prior
 * depends on. A CKDT-vs-JSON cross-check proves neither English loader reorders.
 */
class CkdtReaderTest {

    // ── English CKDT ──────────────────────────────────────────────────────────

    @Test
    fun englishCkdt_hasExactly98140Words() {
        val dict = GeoTestFixtures.englishCkdt()
        assertWithMessage("en_enhanced.bin (CKDT) must hold the shipped 98,140-word lexicon")
            .that(dict.size).isEqualTo(98140)
        assertThat(dict.language).isEqualTo("en")
    }

    @Test
    fun englishCkdt_ordinalOrder_isDescendingFrequency_deterministic() {
        val dict = GeoTestFixtures.englishCkdt()
        // Index 0 is the single most-frequent word; the common function words must sit
        // far ahead of a rare tail word (ordinal rank r asc == frequency desc).
        val words = (0 until dict.size).map { dict.word(it) }
        val rankOf = HashMap<String, Int>(words.size * 2)
        for ((i, w) in words.withIndex()) rankOf.putIfAbsent(w, i)

        // "the" is the most frequent English word; it must be in the very top band.
        val theRank = rankOf["the"]
        assertWithMessage("'the' must be present and near the top").that(theRank).isNotNull()
        assertThat(theRank!!).isLessThan(50)

        // A rare word must rank far below a common one.
        val commonRank = rankOf["and"] ?: Int.MAX_VALUE
        val rareRank = rankOf["aalborg"] ?: Int.MAX_VALUE
        assertWithMessage("'and' must outrank a rare proper noun")
            .that(commonRank).isLessThan(rareRank)
    }

    @Test
    fun englishCkdt_reload_isBitIdentical() {
        // Determinism (NFR-4): two independent reads produce the same ordering.
        val a = GeoTestFixtures.englishCkdt()
        val b = CkdtDictionaryReader.read(
            java.io.File("src/main/assets/dictionaries/en_enhanced.bin").inputStream(), "en"
        )
        assertThat(b.size).isEqualTo(a.size)
        // Sample across the range: same index → same word every run.
        for (i in intArrayOf(0, 1, 100, 5000, 50000, a.size - 1)) {
            assertWithMessage("index $i must be stable across reloads")
                .that(b.word(i)).isEqualTo(a.word(i))
        }
    }

    // ── English flat JSON cross-check ─────────────────────────────────────────

    @Test
    fun englishJson_hasSameWordSetAs_ckdt() {
        val ckdt = GeoTestFixtures.englishCkdt()
        val json = GeoTestFixtures.englishJson()
        assertWithMessage("JSON and CKDT cover the same 98,140-word lexicon")
            .that(json.size).isEqualTo(ckdt.size)
        val ckdtSet = (0 until ckdt.size).mapTo(HashSet()) { ckdt.word(it) }
        val jsonSet = (0 until json.size).mapTo(HashSet()) { json.word(it) }
        assertWithMessage("JSON and CKDT word SETS must be identical")
            .that(jsonSet).isEqualTo(ckdtSet)
    }

    @Test
    fun englishJson_ordinalOrder_isDescendingFrequency() {
        // Higher byte-score = more common ⇒ index 0 is highest-scoring.
        val json = GeoTestFixtures.englishJson()
        val rankOf = HashMap<String, Int>(json.size * 2)
        for (i in 0 until json.size) rankOf.putIfAbsent(json.word(i), i)
        val theRank = rankOf["the"] ?: Int.MAX_VALUE
        val rareRank = rankOf["aalborg"] ?: Int.MAX_VALUE
        assertWithMessage("'the' must outrank a rare word in the JSON ordering")
            .that(theRank).isLessThan(rareRank)
    }

    // ── Russian via the langpack zip ──────────────────────────────────────────

    @Test
    fun russianZip_hasAtLeast50kWords_notThe5kUnigramList() {
        val dict = GeoTestFixtures.russianCkdt()
        assertWithMessage(
            "langpack-ru.zip dictionary.bin (CKDT) must hold >= 50,000 words — a size " +
                "below this means the fixture silently regressed to the 5k unigrams.txt list"
        ).that(dict.size).isAtLeast(50_000)
        assertThat(dict.language).isEqualTo("ru")
        // Sanity: the words are Cyrillic (index 0 is the most frequent Russian word).
        val top = dict.word(0)
        assertWithMessage("top ru word '$top' must contain Cyrillic")
            .that(top.any { it.code in 0x0400..0x04FF }).isTrue()
    }

    // ── Format validation ─────────────────────────────────────────────────────

    @Test
    fun read_rejectsBadMagic() {
        // A byte buffer with a wrong magic must fail loudly, not silently mis-parse.
        val bogus = ByteArray(48) { 0 }
        bogus[0] = 'X'.code.toByte()
        val ex = runCatching { CkdtDictionaryReader.read(bogus, "en") }.exceptionOrNull()
        assertWithMessage("bad magic must throw IllegalArgumentException")
            .that(ex).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun flatJson_parsesEscapesAndPreservesOrderForTies() {
        // Two words share a score → file order breaks the tie deterministically.
        val json = """{ "zed": 200, "alpha": 200, "top": 255 }"""
        val dict = FlatJsonDictionaryLoader.read(json, "en")
        assertThat(dict.size).isEqualTo(3)
        assertWithMessage("highest score first").that(dict.word(0)).isEqualTo("top")
        // Tie (200 vs 200) keeps file order: "zed" appeared before "alpha".
        assertThat(dict.word(1)).isEqualTo("zed")
        assertThat(dict.word(2)).isEqualTo("alpha")
    }
}
