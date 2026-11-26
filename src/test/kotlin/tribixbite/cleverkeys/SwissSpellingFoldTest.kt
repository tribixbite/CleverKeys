package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.util.Locale
import org.junit.Test
import tribixbite.cleverkeys.swipe.LexiconContentVersion
import tribixbite.cleverkeys.swipe.ctc.CtcAzProjection
import tribixbite.cleverkeys.swipe.ctc.CtcCkdtLexicon
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader

/**
 * D2: Swiss German spelling (`ss` for `ß`), the pure half — [SwissSpelling] and every load-time
 * fold built on it: the tap dictionary and its prefix index, the secondary index, the CTC base
 * pairs with their memo key, the geometric word array, the next-word model's lookup aliases and
 * the first-use seed from the system languages.
 *
 * The shipped German list spells Germany's way (`GermanSharpSDictionaryDriftTest`); these tests
 * are the Swiss reading of the same assets. The typing behaviour on top is
 * `WordPredictorSharpSTest`, the swipe decode `CtcAccentKeyLayoutReplayTest`.
 */
class SwissSpellingFoldTest {

    // ── The fold itself ─────────────────────────────────────────────────────────────────────

    @Test
    fun `sharp s is written ss, and a word without one is returned as it is`() {
        assertThat(SwissSpelling.fold("straße")).isEqualTo("strasse")
        assertThat(SwissSpelling.fold("Maßstab")).isEqualTo("Massstab")
        assertThat(SwissSpelling.fold("GROẞ")).isEqualTo("GROSS")
        val plain = "dass"
        assertWithMessage("same instance").that(SwissSpelling.fold(plain)).isSameInstanceAs(plain)
    }

    @Test
    fun `the fold is idempotent`() {
        for (word in listOf("straße", "schloßstraße", "weiß", "dass", "")) {
            val once = SwissSpelling.fold(word)
            assertWithMessage(word).that(SwissSpelling.fold(once)).isEqualTo(once)
        }
    }

    @Test
    fun `only a German lexicon is ever respelled`() {
        for (code in listOf("de", "DE", "de_CH", "de-AT")) {
            assertWithMessage(code).that(SwissSpelling.appliesTo(true, code)).isTrue()
            assertWithMessage("$code, switch off").that(SwissSpelling.appliesTo(false, code)).isFalse()
        }
        for (code in listOf("en", "nl", "da", "", null)) {
            assertWithMessage("$code").that(SwissSpelling.appliesTo(true, code)).isFalse()
        }
    }

    // ── Tap dictionary ──────────────────────────────────────────────────────────────────────

    private fun indexOf(dict: Map<String, Int>): MutableMap<String, MutableSet<String>> {
        val index = HashMap<String, MutableSet<String>>()
        for (w in dict.keys) for (len in 1..minOf(3, w.length)) index.getOrPut(w.substring(0, len)) { HashSet() }.add(w)
        return index
    }

    @Test
    fun `dictionary keys move to their ss spelling and twins keep the higher frequency`() {
        val dict = hashMapOf(
            "straße" to 690, "groß" to 700, "gros" to 650, "dass" to 720,
            "maße" to 560, "masse" to 540, // twins: Maße is the more frequent here
            "buße" to 300, "busse" to 410, // … and here Busse is
        )
        val index = indexOf(dict)

        val rewritten = SwissSpelling.foldDictionaryKeys(dict, index, 3)

        assertThat(rewritten).isEqualTo(4)
        assertThat(dict).containsExactly(
            "strasse", 690, "gross", 700, "gros", 650, "dass", 720, "masse", 560, "busse", 410,
        )
        assertWithMessage("no ß key left").that(dict.keys.filter { 'ß' in it }).isEmpty()
    }

    @Test
    fun `the prefix index is re-keyed with the dictionary`() {
        val dict = hashMapOf("maße" to 560, "masse" to 540, "maß" to 500, "fuß" to 480, "groß" to 700)
        val index = indexOf(dict)
        assertThat(index).containsKey("maß")

        SwissSpelling.foldDictionaryKeys(dict, index, 3)

        // Exactly the index a fresh build over the folded keys gives: no stale bucket, no ß.
        assertThat(index).isEqualTo(indexOf(dict))
        assertWithMessage("the bucket maß is gone").that(index).doesNotContainKey("maß")
        assertThat(index["mas"]).containsExactly("masse", "mass")
        assertThat(index["fus"]).containsExactly("fuss")
        assertThat(index["gro"]).containsExactly("gross")
    }

    @Test
    fun `a second fold changes nothing`() {
        val dict = hashMapOf("straße" to 690, "maße" to 560, "masse" to 540)
        val index = indexOf(dict)
        SwissSpelling.foldDictionaryKeys(dict, index, 3)
        val dictOnce = HashMap(dict)
        val indexOnce = index.mapValues { HashSet(it.value) }

        assertThat(SwissSpelling.foldDictionaryKeys(dict, index, 3)).isEqualTo(0)
        assertThat(dict).isEqualTo(dictOnce)
        assertThat(index).isEqualTo(indexOnce)
    }

    @Test
    fun `a word disabled in its shipped spelling is disabled in the Swiss one too`() {
        // Dictionary Manager lists the shipped list, so it stores `straße`.
        val stored = setOf("straße", "gros")
        assertThat(SwissSpelling.foldDisabled(true, stored)).containsExactly("straße", "strasse", "gros")
        assertWithMessage("switch off").that(SwissSpelling.foldDisabled(false, stored)).isSameInstanceAs(stored)
        val plain = setOf("gros", "dass")
        assertWithMessage("nothing to add").that(SwissSpelling.foldDisabled(true, plain)).isSameInstanceAs(plain)
    }

    // ── Secondary index ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the secondary index shows ss and merges twins to the better rank`() {
        val index = NormalizedPrefixIndex("de")
        index.addWord("straße", 40)
        index.addWord("masse", 90)
        index.addWord("maße", 70)
        index.addWord("dass", 3)

        val rewritten = index.respellCanonicals(SwissSpelling::fold)

        assertThat(rewritten).isEqualTo(2)
        assertThat(index.lookup("strasse")!!.canonicals).containsExactly("strasse")
        val masse = index.lookup("masse")!!
        assertThat(masse.canonicals).containsExactly("masse")
        assertWithMessage("the better (lower) rank of the twins").that(masse.bestFrequencyRank).isEqualTo(70)
        assertThat(index.size()).isEqualTo(3)
        assertWithMessage("found by prefix as before").that(index.getWordsWithPrefix("stra").map { it.bestCanonical })
            .containsExactly("strasse")
    }

    // ── CTC swipe lexicon ───────────────────────────────────────────────────────────────────

    @Test
    fun `frequency pairs are respelled in place and twins merge to the higher frequency`() {
        val pairs = listOf("dass" to 250.0, "maße" to 180.0, "straße" to 170.0, "masse" to 200.0, "weiß" to 90.0)

        val folded = SwissSpelling.foldFrequencyPairs(pairs)

        assertThat(folded).containsExactly(
            "dass" to 250.0, "masse" to 200.0, "strasse" to 170.0, "weiss" to 90.0,
        ).inOrder()
        val plain = listOf("dass" to 250.0, "gros" to 10.0)
        assertWithMessage("a list without ß is returned as it is")
            .that(SwissSpelling.foldFrequencyPairs(plain)).isSameInstanceAs(plain)
        assertWithMessage("switch off").that(SwissSpelling.foldBaseLexicon(false, pairs)).isSameInstanceAs(pairs)
        assertWithMessage("switch on").that(SwissSpelling.foldBaseLexicon(true, pairs)).isEqualTo(folded)
    }

    private val germanEntries by lazy {
        File("src/main/assets/dictionaries/de_enhanced.bin").inputStream().use { CkdtDictionaryReader.readEntries(it) }
    }

    /** `CtcEngineAdapter.lexiconFor`'s CKDT branch without user words, with or without the fold. */
    private fun projected(swiss: Boolean) = CtcAzProjection.projectLexicon(
        CtcLexiconMerge.merge(
            SwissSpelling.foldBaseLexicon(swiss, CtcCkdtLexicon.frequencyPairs(germanEntries)), emptyList(), emptySet(),
        )
    )

    @Test
    fun `the shipped German swipe lexicon shows no sharp s under Swiss spelling`() {
        val off = projected(swiss = false)
        val on = projected(swiss = true)

        // Off: today's lexicon, the shape CtcCkdtLexiconTest pins.
        assertThat(off.freqs.size).isEqualTo(39_588)
        assertThat(off.collisions).isEqualTo(412)
        assertThat(off.display["strasse"]).isEqualTo("straße")

        // On: the same a–z surfaces (ß already projected to ss), none of them shown with ß.
        assertWithMessage("the trie's surfaces do not change").that(on.freqs.keys).isEqualTo(off.freqs.keys)
        assertWithMessage("display forms with ß").that(on.display.values.filter { 'ß' in it }).isEmpty()
        assertWithMessage("alternates with ß")
            .that(on.alternates.values.flatMap { forms -> forms.map { it.word } }.filter { 'ß' in it }).isEmpty()
        for (surface in listOf("strasse", "gross", "weiss", "heisst")) {
            assertWithMessage("swipe '$surface' shows").that(on.display[surface] ?: surface).isEqualTo(surface)
        }
        // A twin is one word now: its surface keeps the more frequent twin's frequency.
        assertThat(on.freqs.getValue("masse")).isEqualTo(off.freqs.getValue("masse"))
        // große/größe share the surface `grosse`: with ß gone the forms are grosse and grösse.
        val grosse = listOfNotNull(on.display["grosse"] ?: "grosse") + on.alternates["grosse"].orEmpty().map { it.word }
        assertThat(grosse).containsExactly("grosse", "grösse")
    }

    @Test
    fun `the lexicon memo key follows the switch, for German only`() {
        fun version(variant: String) = LexiconContentVersion.of("asset:dictionaries/de_enhanced.bin", "{}", emptySet(), "", "", variant)
        val germany = version(SwissSpelling.lexiconVariant(enabled = false, language = "de"))
        val swiss = version(SwissSpelling.lexiconVariant(enabled = true, language = "de"))

        assertWithMessage("toggling the switch must rebuild the memoised trie").that(swiss).isNotEqualTo(germany)
        assertWithMessage("off = the key every install already had")
            .that(germany).isEqualTo(LexiconContentVersion.of("asset:dictionaries/de_enhanced.bin", "{}", emptySet(), ""))
        assertWithMessage("another language's key ignores the switch")
            .that(SwissSpelling.lexiconVariant(enabled = true, language = "fr")).isEmpty()
    }

    // ── Geometric swipe dictionary ──────────────────────────────────────────────────────────

    @Test
    fun `the geometric word list is respelled, a twin kept once at its better position`() {
        assertThat(SwissSpelling.foldWords(listOf("dass", "maße", "straße", "masse", "weiß")))
            .containsExactly("dass", "masse", "strasse", "weiss").inOrder()

        val bytes = File("src/main/assets/dictionaries/de_enhanced.bin").readBytes()
        val germany = CkdtDictionaryReader.read(bytes, "de", 1L)
        val swiss = CkdtDictionaryReader.read(bytes, "de", 2L, SwissSpelling::foldWords)
        val germanyWords = (0 until germany.size).map { germany.word(it) }
        val swissWords = (0 until swiss.size).map { swiss.word(it) }

        assertWithMessage("Germany's list is untouched").that(germanyWords).contains("straße")
        assertWithMessage("no ß in the Swiss list").that(swissWords.filter { 'ß' in it }).isEmpty()
        assertThat(swissWords).containsAtLeast("strasse", "gross", "weiss", "masse")
        assertWithMessage("no word twice").that(swissWords.toSet().size).isEqualTo(swissWords.size)
        val germanySet = germanyWords.toSet()
        assertWithMessage("exactly the twins are gone")
            .that(germanyWords.size - swissWords.size)
            .isEqualTo(germanyWords.count { 'ß' in it && SwissSpelling.fold(it) in germanySet })
    }

    // ── Next-word model ─────────────────────────────────────────────────────────────────────

    private val germanLm = StaticContextLm.parse(
        StaticContextLmFixtures.encode(
            unigrams = mapOf(
                "die" to 0.03, "straße" to 0.002, "ist" to 0.02, "weiß" to 0.004, "maße" to 0.0005,
                "masse" to 0.0006, "dass" to 0.01, "lang" to 0.001, "nicht" to 0.01,
            ),
            table = mapOf(
                "die" to mapOf("straße" to 0.05, "maße" to 0.01, "masse" to 0.008),
                "straße" to mapOf("ist" to 0.2, "lang" to 0.05),
                "weiß" to mapOf("nicht" to 0.3, "dass" to 0.1),
            ),
            language = "de",
        )
    )

    @Test
    fun `a Swiss spelling is looked up as the model's sharp s word, a real ss word as itself`() {
        val model = BigramModel()
        val aliases = model.lookupAliases(germanLm, "de", mapOf("gehts" to "geht's"))

        assertThat(aliases).containsEntry("strasse", "straße")
        assertThat(aliases).containsEntry("weiss", "weiß")
        assertWithMessage("masse is a word of its own beside maße").that(aliases).doesNotContainKey("masse")
        assertWithMessage("the contraction aliases are kept").that(aliases).containsEntry("gehts", "geht's")
        assertWithMessage("no other language gets them")
            .that(model.lookupAliases(germanLm, "en", emptyMap())).isEmpty()

        model.installStaticLm("de", germanLm.withReplaceAliases(aliases))
        // The previous word typed the Swiss way reaches straße's continuations …
        assertThat(model.seedFor("de", "strasse", 5).map { it.word }).containsExactly("ist", "lang").inOrder()
        assertThat(model.seedFor("de", "weiss", 5).map { it.word }).containsExactly("nicht", "dass").inOrder()
        // … and a Swiss tap candidate scores as the word the model names.
        model.applyLanguage("de")
        assertThat(model.getContextMultiplier("strasse", listOf("die")))
            .isEqualTo(model.getContextMultiplier("straße", listOf("die")))
        assertThat(model.getContextMultiplier("strasse", listOf("die"))).isGreaterThan(1f)
    }

    @Test
    fun `the shipped German model answers for Swiss spellings`() {
        val lm = StaticContextLm.parse(StaticLmLanguageData.asset("de").readBytes())
        val aliases = BigramModel().lookupAliases(lm, "de", emptyMap())

        for ((swiss, germany) in listOf("strasse" to "straße", "weiss" to "weiß", "gross" to "groß", "heisst" to "heißt")) {
            assertWithMessage(swiss).that(aliases[swiss]).isEqualTo(germany)
        }
        for (own in listOf("dass", "muss", "masse")) {
            if (lm.contains(own)) assertWithMessage("$own is the model's own word").that(aliases).doesNotContainKey(own)
        }
        val swissLm = lm.withReplaceAliases(aliases)
        assertWithMessage("without the aliases strasse is unknown").that(lm.contains("strasse")).isFalse()
        assertThat(swissLm.contains("strasse")).isTrue()
        assertThat(swissLm.top("strasse", 5).map { it.word }).isEqualTo(lm.top("straße", 5).map { it.word })
    }

    @Test
    fun `continuations are shown respelled, a twin once at its better rank`() {
        data class C(val word: String, val rank: Float)
        val seed = listOf(C("straße", 0.5f), C("masse", 0.3f), C("maße", 0.2f), C("strasse", 0.1f), C("ist", 0.05f))

        val shown = SwissSpelling.foldDistinct(seed, { it.word }) { c, word -> c.copy(word = word) }

        assertThat(shown).containsExactly(C("strasse", 0.5f), C("masse", 0.3f), C("ist", 0.05f)).inOrder()
    }

    // ── First value, from the system languages ──────────────────────────────────────────────

    @Test
    fun `Switzerland and Liechtenstein start with ss, every other German with sharp s`() {
        fun locales(vararg tags: String) = tags.map { Locale.forLanguageTag(it) }
        assertThat(SwissSpelling.seedDefault(locales("de-CH"))).isTrue()
        assertThat(SwissSpelling.seedDefault(locales("de-LI"))).isTrue()
        assertWithMessage("any of the system languages").that(SwissSpelling.seedDefault(locales("en-US", "de-CH"))).isTrue()
        for (tag in listOf("de-DE", "de-AT", "de", "de-LU", "de-BE")) {
            assertWithMessage(tag).that(SwissSpelling.seedDefault(locales(tag))).isFalse()
        }
        assertWithMessage("French or Italian Switzerland is not German")
            .that(SwissSpelling.seedDefault(locales("fr-CH", "it-CH", "en-CH"))).isFalse()
        assertThat(SwissSpelling.seedDefault(emptyList())).isFalse()
    }

    @Test
    fun `the static default is Germany's spelling`() {
        assertThat(Defaults.GERMAN_SWISS_SPELLING).isFalse()
    }
}
