package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Test
import tribixbite.cleverkeys.CustomDictionarySource
import tribixbite.cleverkeys.LanguagePreferenceKeys
import tribixbite.cleverkeys.MockSharedPreferences
import tribixbite.cleverkeys.SwipePriority
import tribixbite.cleverkeys.UserWordFrequency
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge

/**
 * Device report (Seeker, 2026-10-10): the custom word `adb`, stored at the legacy frequency 100,
 * lost to `an` at Highest; after the Dictionary Manager Edit raised it to 255 it won — "but only
 * from the SECOND swipe onward". This pins the chain from the Edit dialog's Save to the CTC
 * lexicon the next decode builds, with the production writer ([CustomDictionarySource.updateWord],
 * the dialog's call) and the production reader ([CtcEngineAdapter.userLexiconInputs] /
 * [CtcEngineAdapter.customWordPairs], the only reads `lexiconFor` makes):
 *
 *  - the write is visible to the very next read (no async hop between them);
 *  - a FREQUENCY-only edit changes the memo version, so the next decode cannot reuse the old trie;
 *  - the rebuilt lexicon carries the new calibrated frequency and the unchanged priority.
 *
 * Result (2026-10-10): every step holds — the CTC memo is keyed on the raw `custom_words_<lang>`
 * JSON, so it does not lag a frequency edit. The first-swipe observation is examined in
 * `docs/eval/2026-10-08-user-swipe-priority.md` §9.
 */
class CustomWordEditLexiconInputTest {

    private val lang = "en"

    /** The en CTC base floor and ceiling (`en_enhanced.json` spans 134..255). */
    private val base = listOf("an" to 255.0, "and" to 250.0, "ab" to 180.0, "rare" to 134.0)

    private fun version(inputs: CtcEngineAdapter.Companion.UserLexiconInputs): Long =
        LexiconContentVersion.of(
            "asset:dictionaries/en_enhanced.json", inputs.customJson, inputs.disabled,
            UserDictionarySnapshot.EMPTY_FINGERPRINT, inputs.priorityJson,
        )

    private fun seeded(): MockSharedPreferences = MockSharedPreferences().apply {
        putString(LanguagePreferenceKeys.customWordsKey(lang), """{"adb":100,"wet":255}""")
        putString(LanguagePreferenceKeys.swipePriorityKey(lang), """{"adb":2,"wet":1}""")
    }

    @Test
    fun frequencyOnlyEditReachesTheNextLexiconBuild(): Unit = runBlocking {
        val prefs = seeded()
        val before = CtcEngineAdapter.userLexiconInputs(prefs, lang)
        val mergedBefore = CtcLexiconMerge.merge(base, CtcEngineAdapter.customWordPairs(before.customJson, lang), before.disabled)
        assertThat(mergedBefore["adb"]).isWithin(1e-9).of(UserWordFrequency.scaleOnto(100, 134.0, 255.0))

        // The Edit dialog's Save: same word, frequency 100 → 255, priority unchanged (Highest).
        CustomDictionarySource(prefs, lang).updateWord("adb", "adb", 255, SwipePriority.HIGHEST)

        val after = CtcEngineAdapter.userLexiconInputs(prefs, lang)
        assertWithMessage("a frequency-only edit must change the lexicon memo version")
            .that(version(after)).isNotEqualTo(version(before))
        val pairs = CtcEngineAdapter.customWordPairs(after.customJson, lang).toMap()
        assertThat(pairs).containsEntry("adb", 255)
        assertThat(pairs).containsEntry("wet", 255)
        val merged = CtcLexiconMerge.merge(base, pairs.toList(), after.disabled)
        assertThat(merged["adb"]).isWithin(1e-9).of(255.0)
        assertThat(SwipePriority.parseMap(after.priorityJson))
            .containsExactly("adb", SwipePriority.HIGHEST, "wet", SwipePriority.HIGH)
    }

    @Test
    fun unchangedStoreKeepsTheSameVersion() {
        val prefs = seeded()
        val a = CtcEngineAdapter.userLexiconInputs(prefs, lang)
        val b = CtcEngineAdapter.userLexiconInputs(prefs, lang)
        assertThat(version(b)).isEqualTo(version(a))
    }

    @Test
    fun legacyRaiseThroughTheSourceIsVisibleToTheNextBuild(): Unit = runBlocking {
        val prefs = seeded()
        val before = version(CtcEngineAdapter.userLexiconInputs(prefs, lang))
        assertThat(CustomDictionarySource(prefs, lang).raiseLegacyFrequencies(listOf("adb"))).isEqualTo(1)
        val after = CtcEngineAdapter.userLexiconInputs(prefs, lang)
        assertThat(version(after)).isNotEqualTo(before)
        assertThat(CtcEngineAdapter.customWordPairs(after.customJson, lang).toMap())
            .containsExactly("adb", 255, "wet", 255)
        // The raise leaves swipe priorities untouched.
        assertThat(after.priorityJson).isEqualTo("""{"adb":2,"wet":1}""")
    }
}
