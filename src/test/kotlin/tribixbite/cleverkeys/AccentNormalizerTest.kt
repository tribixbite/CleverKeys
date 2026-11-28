package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.swipe.ctc.CtcAzProjection
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader
import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * Pure JVM tests for AccentNormalizer.
 *
 * These tests run without Robolectric or Android dependencies,
 * making them compatible with ARM64/Termux environments.
 */
class AccentNormalizerTest {

    // =========================================================================
    // normalize() tests
    // =========================================================================

    @Test
    fun `normalize removes French accents`() {
        assertThat(AccentNormalizer.normalize("café")).isEqualTo("cafe")
        assertThat(AccentNormalizer.normalize("naïve")).isEqualTo("naive")
        assertThat(AccentNormalizer.normalize("résumé")).isEqualTo("resume")
        assertThat(AccentNormalizer.normalize("français")).isEqualTo("francais")
        assertThat(AccentNormalizer.normalize("garçon")).isEqualTo("garcon")
    }

    @Test
    fun `normalize removes German umlauts`() {
        assertThat(AccentNormalizer.normalize("München")).isEqualTo("munchen")
        assertThat(AccentNormalizer.normalize("schön")).isEqualTo("schon")
        assertThat(AccentNormalizer.normalize("größe")).isEqualTo("grosse")
        assertThat(AccentNormalizer.normalize("über")).isEqualTo("uber")
        assertThat(AccentNormalizer.normalize("ähnlich")).isEqualTo("ahnlich")
    }

    @Test
    fun `normalize handles German sharp s`() {
        assertThat(AccentNormalizer.normalize("straße")).isEqualTo("strasse")
        assertThat(AccentNormalizer.normalize("größe")).isEqualTo("grosse")
        assertThat(AccentNormalizer.normalize("weißen")).isEqualTo("weissen")
    }

    @Test
    fun `normalize removes Spanish accents`() {
        assertThat(AccentNormalizer.normalize("señor")).isEqualTo("senor")
        assertThat(AccentNormalizer.normalize("niño")).isEqualTo("nino")
        assertThat(AccentNormalizer.normalize("España")).isEqualTo("espana")
        assertThat(AccentNormalizer.normalize("jalapeño")).isEqualTo("jalapeno")
    }

    @Test
    fun `normalize removes Nordic characters`() {
        assertThat(AccentNormalizer.normalize("København")).isEqualTo("kobenhavn")
        assertThat(AccentNormalizer.normalize("fjørd")).isEqualTo("fjord")
        assertThat(AccentNormalizer.normalize("Ångström")).isEqualTo("angstrom")
    }

    @Test
    fun `normalize handles Icelandic characters`() {
        assertThat(AccentNormalizer.normalize("Þórr")).isEqualTo("thorr")
        assertThat(AccentNormalizer.normalize("Reykjavík")).isEqualTo("reykjavik")
    }

    @Test
    fun `normalize handles ligatures`() {
        assertThat(AccentNormalizer.normalize("Ærodynamic")).isEqualTo("aerodynamic")
        assertThat(AccentNormalizer.normalize("œuvre")).isEqualTo("oeuvre")
        assertThat(AccentNormalizer.normalize("Cœur")).isEqualTo("coeur")
    }

    @Test
    fun `normalize handles Polish characters`() {
        assertThat(AccentNormalizer.normalize("Łódź")).isEqualTo("lodz")
        assertThat(AccentNormalizer.normalize("żółty")).isEqualTo("zolty")
    }

    @Test
    fun `normalize handles Turkish dotless i`() {
        assertThat(AccentNormalizer.normalize("Istanbulı")).isEqualTo("istanbuli")
    }

    @Test
    fun `normalize handles empty string`() {
        assertThat(AccentNormalizer.normalize("")).isEqualTo("")
    }

    @Test
    fun `normalize leaves ASCII unchanged`() {
        assertThat(AccentNormalizer.normalize("hello")).isEqualTo("hello")
        assertThat(AccentNormalizer.normalize("WORLD")).isEqualTo("world")
        assertThat(AccentNormalizer.normalize("Test123")).isEqualTo("test123")
    }

    @Test
    fun `normalize converts to lowercase`() {
        assertThat(AccentNormalizer.normalize("HELLO")).isEqualTo("hello")
        assertThat(AccentNormalizer.normalize("CAFÉ")).isEqualTo("cafe")
    }

    // =========================================================================
    // hasAccents() tests
    // =========================================================================

    @Test
    fun `hasAccents returns true for accented words`() {
        assertThat(AccentNormalizer.hasAccents("café")).isTrue()
        assertThat(AccentNormalizer.hasAccents("naïve")).isTrue()
        assertThat(AccentNormalizer.hasAccents("München")).isTrue()
        assertThat(AccentNormalizer.hasAccents("straße")).isTrue()
    }

    @Test
    fun `hasAccents returns false for ASCII words`() {
        assertThat(AccentNormalizer.hasAccents("hello")).isFalse()
        assertThat(AccentNormalizer.hasAccents("world")).isFalse()
        assertThat(AccentNormalizer.hasAccents("test123")).isFalse()
    }

    @Test
    fun `hasAccents returns false for empty string`() {
        assertThat(AccentNormalizer.hasAccents("")).isFalse()
    }

    // =========================================================================
    // normalizePreservingCase() tests
    // =========================================================================

    @Test
    fun `normalizePreservingCase preserves lowercase`() {
        assertThat(AccentNormalizer.normalizePreservingCase("café")).isEqualTo("cafe")
        assertThat(AccentNormalizer.normalizePreservingCase("naïve")).isEqualTo("naive")
    }

    @Test
    fun `normalizePreservingCase preserves title case`() {
        assertThat(AccentNormalizer.normalizePreservingCase("Café")).isEqualTo("Cafe")
        assertThat(AccentNormalizer.normalizePreservingCase("München")).isEqualTo("Munchen")
    }

    @Test
    fun `normalizePreservingCase preserves all caps`() {
        assertThat(AccentNormalizer.normalizePreservingCase("CAFÉ")).isEqualTo("CAFE")
        assertThat(AccentNormalizer.normalizePreservingCase("MÜNCHEN")).isEqualTo("MUNCHEN")
    }

    @Test
    fun `normalizePreservingCase handles empty string`() {
        assertThat(AccentNormalizer.normalizePreservingCase("")).isEqualTo("")
    }

    // =========================================================================
    // buildAccentMap() tests
    // =========================================================================

    @Test
    fun `buildAccentMap groups words by normalized form`() {
        val words = listOf(
            "café" to 1000,
            "cafe" to 500,
            "schön" to 800,
            "schon" to 600
        )

        val accentMap = AccentNormalizer.buildAccentMap(words)

        assertThat(accentMap).hasSize(2)
        assertThat(accentMap["cafe"]).hasSize(2)
        assertThat(accentMap["schon"]).hasSize(2)
    }

    @Test
    fun `buildAccentMap sorts by frequency descending`() {
        val words = listOf(
            "cafe" to 500,
            "café" to 1000
        )

        val accentMap = AccentNormalizer.buildAccentMap(words)
        val cafeForms = accentMap["cafe"]!!

        // Higher frequency first
        assertThat(cafeForms[0].first).isEqualTo("café")
        assertThat(cafeForms[0].second).isEqualTo(1000)
        assertThat(cafeForms[1].first).isEqualTo("cafe")
        assertThat(cafeForms[1].second).isEqualTo(500)
    }

    @Test
    fun `buildAccentMap handles empty list`() {
        val accentMap = AccentNormalizer.buildAccentMap(emptyList())
        assertThat(accentMap).isEmpty()
    }

    // =========================================================================
    // getBestCanonical() tests
    // =========================================================================

    @Test
    fun `getBestCanonical returns highest frequency form`() {
        val words = listOf(
            "cafe" to 500,
            "café" to 1000
        )
        val accentMap = AccentNormalizer.buildAccentMap(words)

        val best = AccentNormalizer.getBestCanonical("cafe", accentMap)

        assertThat(best).isEqualTo("café")
    }

    @Test
    fun `getBestCanonical returns null for unknown word`() {
        val accentMap = AccentNormalizer.buildAccentMap(emptyList())

        val result = AccentNormalizer.getBestCanonical("unknown", accentMap)

        assertThat(result).isNull()
    }

    // =========================================================================
    // getAllCanonicals() tests
    // =========================================================================

    @Test
    fun `getAllCanonicals returns all forms sorted by frequency`() {
        val words = listOf(
            "cafe" to 500,
            "café" to 1000,
            "cafè" to 300
        )
        val accentMap = AccentNormalizer.buildAccentMap(words)

        val allForms = AccentNormalizer.getAllCanonicals("cafe", accentMap)

        assertThat(allForms).containsExactly("café", "cafe", "cafè").inOrder()
    }

    @Test
    fun `getAllCanonicals returns empty list for unknown word`() {
        val accentMap = AccentNormalizer.buildAccentMap(emptyList())

        val result = AccentNormalizer.getAllCanonicals("unknown", accentMap)

        assertThat(result).isEmpty()
    }

    // =========================================================================
    // Edge cases and regression tests
    // =========================================================================

    @Test
    fun `normalize handles combined diacritical marks`() {
        // Precomposed vs decomposed forms
        val precomposed = "é" // U+00E9
        val decomposed = "é"  // e + U+0301 (combining acute)

        assertThat(AccentNormalizer.normalize(precomposed)).isEqualTo("e")
        assertThat(AccentNormalizer.normalize(decomposed)).isEqualTo("e")
    }

    @Test
    fun `normalize handles Vietnamese characters`() {
        assertThat(AccentNormalizer.normalize("Việt")).isEqualTo("viet")
        assertThat(AccentNormalizer.normalize("Đà")).isEqualTo("da")
    }

    @Test
    fun `normalize handles multiple accents on same word`() {
        assertThat(AccentNormalizer.normalize("résumé")).isEqualTo("resume")
        assertThat(AccentNormalizer.normalize("naïveté")).isEqualTo("naivete")
    }

    // =========================================================================
    // Final Fable audit M6 (2026-11-28): no Regex per dictionary word
    // =========================================================================

    /**
     * The implementations as they were before M6 (a Regex per call), kept as the oracle: the char
     * loops that replaced them must give the same output for every word.
     */
    private object RegexReference {
        private val DIACRITICS = Regex("[\\u0300-\\u036F]")
        private val COMBINING = Regex("[\\p{Mn}]")
        private val SPECIAL = mapOf(
            'ß' to "ss", 'ø' to "o", 'Ø' to "o", 'ð' to "d", 'Ð' to "d", 'þ' to "th", 'Þ' to "th",
            'æ' to "ae", 'Æ' to "ae", 'œ' to "oe", 'Œ' to "oe", 'ł' to "l", 'Ł' to "l", 'đ' to "d",
            'Đ' to "d", 'ı' to "i", 'ħ' to "h", 'Ħ' to "h",
        )
        private val AZ_JOINERS = charArrayOf('\'', '’', '-')
        private val AZ_EXPANSIONS = mapOf('ß' to "ss", 'œ' to "oe", 'æ' to "ae", 'ø' to "o")

        fun normalize(word: String): String {
            if (word.isEmpty()) return word
            var result = word.lowercase()
            for ((char, replacement) in SPECIAL) result = result.replace(char.toString(), replacement)
            return DIACRITICS.replace(Normalizer.normalize(result, Normalizer.Form.NFD), "")
        }

        fun stripNonSpacingMarks(text: String): String = COMBINING.replace(text, "")

        fun azProject(word: String): String? {
            if (word.isEmpty()) return null
            val decomposed = Normalizer.normalize(word.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            val sb = StringBuilder(decomposed.length)
            for (ch in COMBINING.replace(decomposed, "")) {
                if (ch in AZ_JOINERS) continue
                val expansion = AZ_EXPANSIONS[ch]
                if (expansion != null) {
                    sb.append(expansion)
                    continue
                }
                if (ch !in 'a'..'z') return null
                sb.append(ch)
            }
            return if (sb.isEmpty()) null else sb.toString()
        }
    }

    /**
     * Every word of the shipped dictionaries (the APK's assets, and the language packs' when the
     * pack sources are on this machine: they are not in git), plus cases those lists may lack:
     * capitals of the special letters, Turkish dotted I, Greek, Hebrew points, Arabic harakat,
     * Devanagari, a supplementary-plane mark, a lone surrogate and mixed scripts.
     */
    private val oracleCorpus: List<String> by lazy {
        val words = LinkedHashSet<String>()
        fun dictionariesIn(dir: File): List<File> =
            dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith("_enhanced.bin") }
        val bins = dictionariesIn(File("src/main/assets/dictionaries")) +
            File("scripts/dictionaries").listFiles().orEmpty().filter { it.isDirectory }.flatMap { dictionariesIn(it) }
        check(bins.size >= 7) { "expected the shipped dictionaries (run from project root): $bins" }
        for (bin in bins) bin.inputStream().use { input -> CkdtDictionaryReader.readEntries(input).mapTo(words) { it.word } }
        Regex("\"([^\"]+)\"\\s*:").findAll(File("src/main/assets/dictionaries/en_enhanced.json").readText())
            .mapTo(words) { it.groupValues[1] }
        words += listOf(
            "", "a", "ÆSIR", "STRAßE", "ØRESUND", "ŁÓDŹ", "ĐORĐE", "ĦAMRUN", "ÞÓR", "İSTANBUL", "ıslak",
            "λόγος", "ΆΛΦΑ", "ϊ", "שָׁלוֹם", "مُدَرِّسَة", "नमस्ते", "a\uD834\uDD67b", "x\uD834", "café-au-lait",
            "l’été", "don't", "naïveté", "ǅemal", "ﬁne", "Å", "A\u030A", "e\u0301\u0302", "12ab", "Ⅻ",
        )
        words.toList()
    }

    @Test
    fun `normalize matches the regex implementation on every shipped word`() {
        var checked = 0
        for (word in oracleCorpus) {
            assertWithMessage("normalize('$word')").that(AccentNormalizer.normalize(word))
                .isEqualTo(RegexReference.normalize(word))
            checked++
        }
        assertWithMessage("words checked").that(checked).isGreaterThan(100_000)
    }

    @Test
    fun `the CTC a-z projection and mark stripping match the regex implementation`() {
        for (word in oracleCorpus) {
            assertWithMessage("project('$word')").that(CtcAzProjection.project(word))
                .isEqualTo(RegexReference.azProject(word))
            val decomposed = Normalizer.normalize(word.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            assertWithMessage("stripNonSpacingMarks('$word')").that(CtcAzProjection.stripNonSpacingMarks(decomposed))
                .isEqualTo(RegexReference.stripNonSpacingMarks(decomposed))
        }
    }

    /**
     * No Regex on the dictionary and typing paths. The per-word folds (AccentNormalizer and the
     * two CTC projections, run for every dictionary word at load) use no Regex at all, hoisted or
     * not; PredictionContextTracker and LanguageDetector, which match per typed word, build theirs
     * only as hoisted constants (`private val`). Before M6 the folds ran a Regex per word, and the
     * tracker and the detector compiled a new one per call.
     */
    @Test
    fun `no Regex is compiled per call on the dictionary and typing paths`() {
        fun code(path: String) = File(path).readLines().withIndex().filter { (_, line) ->
            val t = line.trimStart()
            !t.startsWith("*") && !t.startsWith("//") && !t.startsWith("/*")
        }
        val regexFree = listOf(
            "src/main/kotlin/tribixbite/cleverkeys/AccentNormalizer.kt",
            "src/main/kotlin/tribixbite/cleverkeys/swipe/ctc/CtcAzProjection.kt",
            "src/main/kotlin/tribixbite/cleverkeys/swipe/ctc/CtcScriptProjection.kt",
        )
        val hoistedOnly = listOf(
            "src/main/kotlin/tribixbite/cleverkeys/PredictionContextTracker.kt",
            "src/main/kotlin/tribixbite/cleverkeys/LanguageDetector.kt",
        )
        val offenders = regexFree.flatMap { path ->
            code(path).filter { (_, line) -> "Regex" in line || ".toRegex()" in line }
                .map { (i, line) -> "$path:${i + 1}: ${line.trim()}" }
        } + hoistedOnly.flatMap { path ->
            code(path).filter { (_, line) ->
                ("Regex(" in line || ".toRegex()" in line) && !line.trimStart().startsWith("private val ")
            }.map { (i, line) -> "$path:${i + 1}: ${line.trim()}" }
        }
        assertWithMessage("Regex on a per-word or per-call path").that(offenders).isEmpty()
    }
}
