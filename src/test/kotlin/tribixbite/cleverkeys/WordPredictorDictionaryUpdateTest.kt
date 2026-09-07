package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Mock-tier tests for `WordPredictor`'s dictionary-update paths — the 2026-09-06 audit
 * items C-1 (reload signal never consumed), C-2 (incremental updates skip the wave-U2
 * frequency calibration), C-3 (removing a shadowing custom word deletes the BASE word),
 * and C-9 (proper-noun case mappings leak across full dictionary loads).
 *
 * # Harness
 *
 * Same shape as [tribixbite.cleverkeys.autocorrect.AutoCorrectEndToEndTest]: the
 * `WordPredictor` is allocated via Objenesis (its constructor builds an
 * `AsyncDictionaryLoader` → `Handler(Looper)` android-stub chain) and only the fields the
 * exercised paths touch are reflection-seeded. The private methods under test —
 * `handleIncrementalUpdate`, `checkAndReload`, `loadCustomAndUserWordsIntoMap` — are the
 * REAL production bodies, invoked reflectively; the seams mocked are the ones production
 * itself abstracts (SharedPreferences via `DirectBootAwarePreferences`, the platform
 * provider via `UserDictionaryWords.read`).
 *
 * The dictionary fixture uses the binary-CKDT runtime scale (~5.5K..1M) so the
 * calibration assertions run on the same span as the device (see `baseFrequencySpanOf`).
 */
class WordPredictorDictionaryUpdateTest {

    private lateinit var predictor: WordPredictor

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        predictor = ObjenesisStd().newInstance(WordPredictor::class.java)
        setField("dictionary", AtomicReference(HashMap<String, Int>()))
        setField("prefixIndex", AtomicReference(HashMap<String, MutableSet<String>>()))
        setField("customAndUserWords", emptySet<String>())
        setField("disabledWords", mutableSetOf<String>())
        setField("userWordOriginalCase", ConcurrentHashMap<String, String>())
        setField("currentLanguage", "en")
        // Present only after the C-3 fix; the red run predates the field.
        setFieldIfPresent("shadowedBaseFrequencies", ConcurrentHashMap<String, Int>())
    }

    @After
    fun teardown() {
        // C-1's signal is a STATIC flag — never leave it set for other test classes.
        val flag = WordPredictor::class.java.getDeclaredField("needsReload")
        flag.isAccessible = true
        flag.setBoolean(null, false)
        unmockkAll()
    }

    // ------------------------------------------------------------------ fixtures

    /** The device-realistic base span: ceiling 1M, floor 5.5K (binary CKDT scale). */
    private fun seedBaseDictionary(vararg extra: Pair<String, Int>) {
        dict().putAll(mapOf("the" to 1_000_000, "zyzzyva" to 5_500))
        dict().putAll(extra)
    }

    @Suppress("UNCHECKED_CAST")
    private fun dict(): MutableMap<String, Int> =
        (getField("dictionary") as AtomicReference<MutableMap<String, Int>>).get()

    @Suppress("UNCHECKED_CAST")
    private fun prefixIndex(): MutableMap<String, MutableSet<String>> =
        (getField("prefixIndex") as AtomicReference<MutableMap<String, MutableSet<String>>>).get()

    @Suppress("UNCHECKED_CAST")
    private fun customAndUserWords(): Set<String> = getField("customAndUserWords") as Set<String>

    private fun incrementalUpdate(added: Map<String, Int>, removed: Set<String>) {
        val method = WordPredictor::class.java.getDeclaredMethod(
            "handleIncrementalUpdate", Map::class.java, Set::class.java
        )
        method.isAccessible = true
        method.invoke(predictor, added, removed)
    }

    private fun checkAndReload() {
        val method = WordPredictor::class.java.getDeclaredMethod("checkAndReload")
        method.isAccessible = true
        method.invoke(predictor)
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadCustomAndUserWordsIntoMap(
        context: Context,
        targetMap: MutableMap<String, Int>,
        language: String,
    ): Set<String> {
        val method = WordPredictor::class.java.getDeclaredMethod(
            "loadCustomAndUserWordsIntoMap", Context::class.java, Map::class.java, String::class.java
        )
        method.isAccessible = true
        return method.invoke(predictor, context, targetMap, language) as Set<String>
    }

    /** Mock the two load seams: DirectBootAware prefs (custom words) + the provider read. */
    private fun mockLoadSeams(customWordsJsonByKey: Map<String, String> = emptyMap()): Context {
        mockkStatic(DirectBootAwarePreferences::get_shared_preferences)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { DirectBootAwarePreferences.get_shared_preferences(any()) } returns prefs
        every { prefs.getStringSet(any(), any()) } returns emptySet()
        every { prefs.getString(any(), any()) } answers {
            customWordsJsonByKey[firstArg()] ?: "{}"
        }
        mockkObject(UserDictionaryWords)
        every { UserDictionaryWords.read(any(), any()) } returns emptyList()
        return mockk<Context>(relaxed = true)
    }

    // ------------------------------------------------------ C-2: calibration parity

    @Test
    fun observerDeliveredWordIsCalibratedOntoTheBaseScale() {
        seedBaseDictionary()

        // The observer delivers stored 1..255 values verbatim (UserDictionaryObserver
        // reads FREQUENCY / the pref value raw). 255 is the add-word default.
        incrementalUpdate(mapOf("flurble" to 255), emptySet())

        assertWithMessage(
            "stored 255 (the add-word default) must land at the base CEILING via " +
                "UserWordFrequency.scaleOnto — raw 255 sits below the entire base " +
                "dictionary (floor 5.5K) and buries the user's own word, the exact " +
                "defect wave U2 fixed on the three full-load paths"
        ).that(dict()["flurble"]).isEqualTo(1_000_000)
    }

    @Test
    fun observerDeliveredMinimumFrequencyTiesTheBaseFloor() {
        seedBaseDictionary()

        incrementalUpdate(mapOf("rareling" to 1), emptySet())

        assertWithMessage("stored 1 maps to the base floor — never below the whole dictionary")
            .that(dict()["rareling"]).isEqualTo(5_500)
    }

    @Test
    fun observerDeliveredWordJoinsCustomAndUserWords() {
        seedBaseDictionary()
        setField("disabledWords", mutableSetOf("flurble"))

        incrementalUpdate(mapOf("flurble" to 255), emptySet())

        assertWithMessage(
            "an observer-added word must join customAndUserWords, or it misses the " +
                "disabled-word override (issue #72 semantics) and the autocorrect " +
                "frequency-floor exemption until the next full reload"
        ).that(predictor.isWordDisabled("flurble")).isFalse()
    }

    // ------------------------------------------------ C-3: shadowed base restoration

    @Test
    fun removingAShadowingCustomWordRestoresTheBaseEntry() {
        seedBaseDictionary("hello" to 800_000)

        // The user boosts a bundled word by adding it as a custom word...
        incrementalUpdate(mapOf("hello" to 255), emptySet())
        assertThat(dict()["hello"]).isEqualTo(1_000_000)

        // ...then deletes the custom word in the Dictionary Manager.
        incrementalUpdate(emptyMap(), setOf("hello"))

        assertWithMessage(
            "deleting the CUSTOM word must restore the BASE dictionary entry — the " +
                "bundled word must not vanish from tap predictions until a language " +
                "switch or restart"
        ).that(dict()["hello"]).isEqualTo(800_000)
        assertWithMessage("the restored base word must remain prefix-reachable")
            .that(prefixIndex()["hel"] ?: emptySet<String>()).contains("hello")
    }

    @Test
    fun aStartupLoadedShadowingCustomWordRestoresBaseOnRemoval() {
        // Same defect via the async full-load path: the shadow is recorded at LOAD time.
        val context = mockLoadSeams(mapOf("custom_words_en" to """{"hello":255}"""))
        val loadedMap = hashMapOf("the" to 1_000_000, "hello" to 800_000, "zyzzyva" to 5_500)

        val loadedWords = loadCustomAndUserWordsIntoMap(context, loadedMap, "en")
        // Mirror onLoadCustomWords/onLoadComplete: the map becomes the serving dictionary.
        setField("dictionary", AtomicReference(loadedMap))
        setField("customAndUserWords", loadedWords)

        incrementalUpdate(emptyMap(), setOf("hello"))

        assertWithMessage("the base entry shadowed at startup must be restored on removal")
            .that(dict()["hello"]).isEqualTo(800_000)
    }

    @Test
    fun removingANonShadowingCustomWordRemovesItOutright() {
        seedBaseDictionary()

        incrementalUpdate(mapOf("flurble" to 255), emptySet())
        incrementalUpdate(emptyMap(), setOf("flurble"))

        assertThat(dict()).doesNotContainKey("flurble")
        assertWithMessage("a genuinely custom word leaves the prefix index on removal")
            .that(prefixIndex()["flu"] ?: emptySet<String>()).doesNotContain("flurble")
        assertWithMessage("and leaves customAndUserWords")
            .that(customAndUserWords()).doesNotContain("flurble")
    }

    // ------------------------------------------------------- C-1: one signal, one reload

    @Test
    fun reloadSignalIsConsumedByTheFirstReload() {
        val context = mockLoadSeams()
        setField("context", context)
        seedBaseDictionary()

        WordPredictor.signalReloadNeeded()
        checkAndReload()
        checkAndReload()

        // checkAndReload runs at the top of EVERY prediction; the signal must cost ONE
        // reload (prefs JSON parse + provider binder query + full prefix-index rebuild),
        // not one per keystroke for the rest of the process lifetime.
        verify(exactly = 1) { UserDictionaryWords.read(any(), any()) }
    }

    @Test
    fun aNewSignalTriggersANewReload() {
        val context = mockLoadSeams()
        setField("context", context)
        seedBaseDictionary()

        WordPredictor.signalReloadNeeded()
        checkAndReload()
        WordPredictor.signalReloadNeeded()
        checkAndReload()

        verify(exactly = 2) { UserDictionaryWords.read(any(), any()) }
    }

    // ------------------------------------------------- C-9: case map cleared on full load

    @Test
    fun caseMappingsDoNotLeakAcrossFullDictionaryLoads() {
        val context = mockLoadSeams(
            mapOf(
                "custom_words_en" to """{"LaTeX":255}""",
                "custom_words_fr" to "{}",
            )
        )

        // Language A: custom proper noun "LaTeX" → the case map serves it.
        loadCustomAndUserWordsIntoMap(
            context, hashMapOf("the" to 1_000_000, "zyzzyva" to 5_500), "en"
        )
        assertThat(predictor.applyUserWordCase("latex")).isEqualTo("LaTeX")

        // Language B full load: no custom words, but "latex" IS an ordinary fr
        // dictionary word. The en case mapping must not restyle it.
        loadCustomAndUserWordsIntoMap(
            context, hashMapOf("latex" to 700_000, "base" to 1_000_000), "fr"
        )
        assertWithMessage(
            "the full-load path must clear userWordOriginalCase before repopulating — " +
                "language A's proper-noun casing must not rewrite language B's predictions"
        ).that(predictor.applyUserWordCase("latex")).isEqualTo("latex")
    }

    // ------------------------------------------------------------------ reflection

    private fun setField(name: String, value: Any?) {
        val field = WordPredictor::class.java.declaredFields.firstOrNull { it.name == name }
        assertWithMessage(
            "field '$name' not found on WordPredictor — renamed or removed; declared: " +
                WordPredictor::class.java.declaredFields.map { it.name }
        ).that(field).isNotNull()
        field!!.isAccessible = true
        field.set(predictor, value)
    }

    /** For fields that only exist after a fix under test lands (fail-first red runs). */
    private fun setFieldIfPresent(name: String, value: Any?) {
        val field = WordPredictor::class.java.declaredFields.firstOrNull { it.name == name } ?: return
        field.isAccessible = true
        field.set(predictor, value)
    }

    private fun getField(name: String): Any? {
        val field = WordPredictor::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(predictor)
    }
}
