package tribixbite.cleverkeys

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * v1.1.95: "Android user dictionary filtered by locale (prevents cross-language contamination)".
 *
 * Release-record row: `UserDictionaryObserver.kt#UserDictionaryObserver`, PRESENT-UNTESTED
 * until now. The user-visible failure it fixed is concrete: with a French keyboard, the words
 * a user had added to the SYSTEM dictionary under `en` were being pulled into the French
 * prediction set, so English words surfaced while typing French.
 *
 * What is pinned here is the whole mechanism, through the REAL production methods:
 *
 *  1. the provider query carries a locale predicate at all, with the exact selection SQL and
 *     the exact bound arguments (`fr`, `fr%`) — a locale-prefixed row like `fr_FR` must match,
 *     and a row with no locale (a global word) must still be included;
 *  2. only rows the provider returned under that filter enter the cache;
 *  3. switching language RE-QUERIES with the new arguments and drops the previous language's
 *     words, which is the contamination fix itself;
 *  4. the incremental diff that feeds the predictor is computed against that filtered set.
 *
 * ## Why the objects are allocated without their constructors
 *
 * `UserDictionaryObserver` extends `ContentObserver` and passes
 * `Handler(Looper.getMainLooper())` to its super-constructor — an android.jar stub that throws
 * "Stub!" under `runMockTests`. Objenesis allocates without running any constructor; only the
 * fields the exercised paths read are seeded, so the REAL method bodies run. Same pattern and
 * same reason as [ContractionUserWordGuardTest].
 */
class UserDictionaryLocaleFilterTest {

    /** The exact selection the observer must bind. `locale`/`word`/`frequency` are the platform
     *  column names (`UserDictionary.Words.*`); spelled out so a silent change to the predicate
     *  — e.g. dropping the `IS NULL` arm and losing every global word — fails here. */
    private val expectedSelection = "locale = ? OR locale LIKE ? OR locale IS NULL"

    private val objenesis = ObjenesisStd()

    private lateinit var context: Context
    private lateinit var resolver: ContentResolver
    private lateinit var prefs: SharedPreferences

    /** Every query the production code issued, in order. */
    private val projections = mutableListOf<List<String>>()
    private val selections = mutableListOf<String?>()
    private val boundArgs = mutableListOf<List<String>>()

    /** Rows the fake provider hands back for the NEXT query. */
    private var providerRows: List<Triple<String, Int, String?>> = emptyList()

    /** When false the fake cursor reports no FREQUENCY column (index -1). */
    private var frequencyColumnPresent = true

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        mockkStatic(DirectBootAwarePreferences::get_shared_preferences)
        prefs = mockk(relaxed = true)
        every { prefs.getString(any(), any()) } returns "{}"
        every { DirectBootAwarePreferences.get_shared_preferences(any()) } returns prefs

        resolver = mockk()
        context = mockk()
        every { context.contentResolver } returns resolver

        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            projections += secondArg<Array<String>?>()?.toList().orEmpty()
            selections += thirdArg<String?>()
            boundArgs += arg<Array<String>?>(3)?.toList().orEmpty()
            cursorOver(providerRows)
        }
    }

    @After
    fun teardown() {
        projections.clear()
        selections.clear()
        boundArgs.clear()
        unmockkAll()
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * A cursor over `(word, frequency, locale)` rows. The locale is carried only so the fixture
     * reads like the provider table; the production code never selects it — that is exactly why
     * the SQL predicate has to be right, and why this test asserts on the predicate.
     */
    private fun cursorOver(rows: List<Triple<String, Int, String?>>): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        var index = -1
        every { cursor.getColumnIndex("word") } returns 0
        every { cursor.getColumnIndex("frequency") } returns if (frequencyColumnPresent) 1 else -1
        every { cursor.moveToNext() } answers { ++index < rows.size }
        every { cursor.getString(0) } answers { rows[index].first }
        every { cursor.getInt(1) } answers { rows[index].second }
        return cursor
    }

    /** An observer with its caches seeded and its language set, without running a constructor. */
    private fun observer(language: String = "en"): UserDictionaryObserver {
        val obs = objenesis.newInstance(UserDictionaryObserver::class.java)
        obs.setField("context", context)
        obs.setField("currentLanguage", language)
        obs.setField("cachedUserWords", mutableMapOf<String, Int>())
        obs.setField("cachedCustomWords", mutableMapOf<String, Int>())
        obs.setField("changeListener", null)
        return obs
    }

    private fun UserDictionaryObserver.loadCache() =
        UserDictionaryObserver::class.java.getDeclaredMethod("loadUserDictionaryCache")
            .apply { isAccessible = true }
            .invoke(this)

    private fun UserDictionaryObserver.checkChanges() =
        UserDictionaryObserver::class.java.getDeclaredMethod("checkUserDictionaryChanges")
            .apply { isAccessible = true }
            .invoke(this)

    private fun UserDictionaryObserver.loadCustomWords() =
        UserDictionaryObserver::class.java.getDeclaredMethod("loadCustomWordsCache")
            .apply { isAccessible = true }
            .invoke(this)

    private fun UserDictionaryObserver.checkCustomChanges() =
        UserDictionaryObserver::class.java.getDeclaredMethod("checkCustomWordsChanges")
            .apply { isAccessible = true }
            .invoke(this)

    /** What the language-specific custom-words pref hands back on the next read. */
    private fun customWordsJson(language: String, json: String) {
        every { prefs.getString("custom_words_$language", any()) } returns json
    }

    /** A listener that records only the custom-words callbacks. */
    private class CustomWordsRecorder : UserDictionaryObserver.ChangeListener {
        val delivered = mutableListOf<Map<String, Int>>()
        val retracted = mutableListOf<Set<String>>()
        override fun onUserDictionaryChanged(addedWords: Map<String, Int>, removedWords: Set<String>) = Unit
        override fun onCustomWordsChanged(addedOrModified: Map<String, Int>, removed: Set<String>) {
            delivered += addedOrModified
            retracted += removed
        }
    }

    // ------------------------------------------------------- (1) the locale predicate

    @Test
    fun theProviderQueryBindsTheActiveLanguageAsAnExactAndAPrefixMatch() {
        providerRows = listOf(Triple("bonjour", 250, "fr"))

        observer(language = "fr").loadCache()

        assertWithMessage("exactly one provider query per cache load").that(selections).hasSize(1)
        assertWithMessage(
            "the UserDictionary query must be locale-filtered. Without a predicate every " +
                "language's system words land in the active language's prediction set — the " +
                "cross-language contamination v1.1.95 fixed."
        ).that(selections.single()).isEqualTo(expectedSelection)

        assertWithMessage(
            "args must be the language and its LIKE prefix, in that order: `fr` matches a row " +
                "stored as plain `fr`, `fr%` matches `fr_FR` / `fr_CA`."
        ).that(boundArgs.single()).containsExactly("fr", "fr%").inOrder()
    }

    @Test
    fun theQueryProjectsOnlyTheWordAndFrequencyColumns() {
        observer().loadCache()

        assertWithMessage(
            "widening the projection would pull the whole UserDictionary row (including the " +
                "app-id and shortcut columns) across the provider boundary for every word"
        ).that(projections.single()).containsExactly("word", "frequency").inOrder()
    }

    @Test
    fun aGlobalRowWithNoLocaleIsStillIncludedByThePredicate() {
        assertWithMessage(
            "`OR locale IS NULL` is the arm that keeps words a user added with no locale " +
                "(the Android UI's default) visible in EVERY language. Dropping it would make " +
                "the fix over-filter and silently lose those words."
        ).that(expectedSelection).contains("locale IS NULL")

        providerRows = listOf(Triple("kotlin", 400, null))
        val obs = observer(language = "de")
        obs.loadCache()

        assertThat(obs.getCachedUserWords()).containsExactly("kotlin", 400)
    }

    // ------------------------------------------------------- (2) what enters the cache

    @Test
    fun returnedRowsAreCachedLowercasedWithTheirFrequencies() {
        providerRows = listOf(
            Triple("Bonjour", 250, "fr"),
            Triple("ÉCOLE", 30, "fr_FR"),
        )

        val obs = observer(language = "fr")
        obs.loadCache()

        assertWithMessage("lookups downstream are lowercase, so the cache stores lowercase")
            .that(obs.getCachedUserWords())
            .containsExactly("bonjour", 250, "école", 30)
    }

    @Test
    fun aRowWithoutAFrequencyColumnFallsBackToTheDocumentedDefault() {
        frequencyColumnPresent = false
        providerRows = listOf(Triple("mot", 999, "fr"))

        val obs = observer(language = "fr")
        obs.loadCache()

        assertWithMessage("no FREQUENCY column (index -1) → 1000, not 0 and not a crash")
            .that(obs.getCachedUserWords()).containsExactly("mot", 1000)
    }

    @Test
    fun aProviderThatReturnsNoCursorLeavesAnEmptyCacheInsteadOfThrowing() {
        every { resolver.query(any(), any(), any(), any(), any()) } returns null

        val obs = observer(language = "fr")
        obs.loadCache()

        assertThat(obs.getCachedUserWords()).isEmpty()
    }

    // ------------------------------------- (3) the contamination fix: switching language

    @Test
    fun switchingLanguageRequeriesWithTheNewLocaleAndDropsTheOldWords() {
        providerRows = listOf(Triple("thoroughfare", 300, "en"))
        val obs = observer(language = "en")
        obs.loadCache()
        assertThat(obs.getCachedUserWords().keys).containsExactly("thoroughfare")

        // The French provider view contains only French rows.
        providerRows = listOf(Triple("boulangerie", 300, "fr"))
        obs.setLanguage("fr")

        assertThat(selections).hasSize(2)
        assertWithMessage("the second query must bind the NEW language, not the old one")
            .that(boundArgs.last()).containsExactly("fr", "fr%").inOrder()
        assertWithMessage(
            "this IS the contamination fix: after switching to French the English system word " +
                "must be gone from the cache, not merged with the French ones"
        ).that(obs.getCachedUserWords()).containsExactly("boulangerie", 300)
    }

    @Test
    fun settingTheSameLanguageAgainDoesNotRequery() {
        providerRows = listOf(Triple("word", 1, "en"))
        val obs = observer(language = "en")
        obs.loadCache()
        assertThat(selections).hasSize(1)

        obs.setLanguage("en")

        assertWithMessage("a no-op language set must not hit the content provider again")
            .that(selections).hasSize(1)
    }

    // ------------------------------------------------- (4) the incremental diff notified

    @Test
    fun theChangeDiffIsComputedOverTheLocaleFilteredSetOnly() {
        providerRows = listOf(Triple("alpha", 100, "fr"), Triple("beta", 200, "fr"))
        val obs = observer(language = "fr")
        obs.loadCache()

        val added = mutableListOf<Map<String, Int>>()
        val removed = mutableListOf<Set<String>>()
        obs.setChangeListener(object : UserDictionaryObserver.ChangeListener {
            override fun onUserDictionaryChanged(addedWords: Map<String, Int>, removedWords: Set<String>) {
                added += addedWords
                removed += removedWords
            }

            override fun onCustomWordsChanged(addedOrModified: Map<String, Int>, removed2: Set<String>) = Unit
        })

        // The user removed `beta` and added `gamma` in the system dictionary.
        providerRows = listOf(Triple("alpha", 100, "fr"), Triple("gamma", 300, "fr"))
        obs.checkChanges()

        assertThat(added).hasSize(1)
        assertWithMessage("only the genuinely new word is pushed to the predictor")
            .that(added.single()).containsExactly("gamma", 300)
        assertWithMessage("and only the genuinely removed one is retracted")
            .that(removed.single()).containsExactly("beta")
        assertThat(obs.getCachedUserWords()).containsExactly("alpha", 100, "gamma", 300)
    }

    @Test
    fun aCasedProviderWordIsDeliveredWithItsStoredSpellingButCachedFolded() {
        providerRows = emptyList()
        val obs = observer(language = "en")
        obs.loadCache()

        val added = mutableListOf<Map<String, Int>>()
        obs.setChangeListener(object : UserDictionaryObserver.ChangeListener {
            override fun onUserDictionaryChanged(addedWords: Map<String, Int>, removedWords: Set<String>) {
                added += addedWords
            }

            override fun onCustomWordsChanged(addedOrModified: Map<String, Int>, removed: Set<String>) = Unit
        })

        // The user adds a proper noun to the SYSTEM dictionary while the keyboard is up.
        providerRows = listOf(Triple("Boston", 300, "en"))
        obs.checkChanges()

        assertThat(added).hasSize(1)
        assertWithMessage(
            "the provider row's spelling must survive to the delivery (Issue #72: the " +
                "predictor records userWordOriginalCase from it) — folding here would make " +
                "an incrementally added proper noun serve lowercase until the next full load"
        ).that(added.single()).containsExactly("Boston", 300)
        assertWithMessage("the diff cache itself stays lowercase-folded")
            .that(obs.getCachedUserWords()).containsExactly("boston", 300)
    }

    @Test
    fun anUnchangedProviderDoesNotNotifyAtAll() {
        providerRows = listOf(Triple("alpha", 100, "fr"))
        val obs = observer(language = "fr")
        obs.loadCache()

        var notifications = 0
        obs.setChangeListener(object : UserDictionaryObserver.ChangeListener {
            override fun onUserDictionaryChanged(addedWords: Map<String, Int>, removedWords: Set<String>) {
                notifications++
            }

            override fun onCustomWordsChanged(addedOrModified: Map<String, Int>, removed: Set<String>) = Unit
        })

        obs.checkChanges()

        assertWithMessage(
            "an unchanged dictionary must not trigger a predictor rebuild — the observer exists " +
                "precisely to avoid periodic reload work"
        ).that(notifications).isEqualTo(0)
    }

    // ------------------------ (5) custom words: cased keys keep their STORED frequency
    //
    // The custom_words_<lang> pref stores keys AS TYPED — exact-case membership is a pinned
    // invariant (ContractionUserWordGuardTest.storedUserWordsStayCaseSensitive) — while every
    // serving map is lowercase-keyed. The observer must therefore read the frequency with the
    // key AS STORED and only then fold the map key: lowercasing the key BEFORE the JSON lookup
    // misses every cased entry ({"LaTeX":200} probed as "latex") and silently substitutes the
    // 1000 default for any word containing an uppercase letter.
    //
    // Delivery contract (mirrors the full-load reference semantics): the added/modified maps
    // carry the spelling AS STORED so the predictor can record userWordOriginalCase
    // incrementally; the caches and the removal sets use the lowercase-folded serving keys.

    @Test
    fun aCaseCarryingCustomWordKeepsItsStoredFrequencyOnInitialLoad() {
        customWordsJson("en", """{"LaTeX":200,"hello":150}""")

        val obs = observer(language = "en")
        obs.loadCustomWords()

        assertWithMessage(
            "the frequency must be read with the key exactly as stored — folding the key " +
                "before the optInt lookup turns a cased word's stored frequency into the 1000 " +
                "default. The lowercase word pins the already-working path unchanged."
        ).that(obs.getCachedCustomWords()).containsExactly("latex", 200, "hello", 150)
    }

    @Test
    fun aCaseCarryingCustomWordAddedIncrementallyDeliversItsStoredFrequency() {
        val obs = observer(language = "en")
        val listener = CustomWordsRecorder()
        obs.setChangeListener(listener)

        customWordsJson("en", """{"LaTeX":200,"hello":150}""")
        obs.checkCustomChanges()

        assertThat(listener.delivered).hasSize(1)
        assertWithMessage(
            "the incremental delivery feeds WordPredictor's calibration (C-2) and its case " +
                "recording (Issue #72): the STORED 1..255 pref value must arrive under the " +
                "spelling AS STORED — the consumer folds for its serving maps and keeps the " +
                "casing for userWordOriginalCase"
        ).that(listener.delivered.single()).containsExactly("LaTeX", 200, "hello", 150)
        assertWithMessage("the diff cache itself stays lowercase-folded")
            .that(obs.getCachedCustomWords()).containsExactly("latex", 200, "hello", 150)
    }

    @Test
    fun changingACasedWordsStoredFrequencyIsDeliveredAsAModification() {
        customWordsJson("en", """{"LaTeX":200}""")
        val obs = observer(language = "en")
        obs.loadCustomWords()

        val listener = CustomWordsRecorder()
        obs.setChangeListener(listener)

        customWordsJson("en", """{"LaTeX":90}""")
        obs.checkCustomChanges()

        assertWithMessage(
            "a frequency edit to a cased word must be delivered — with the folded-key lookup " +
                "both the cached and the current read collapse to the 1000 default and the " +
                "edit is swallowed without any notification"
        ).that(listener.delivered).hasSize(1)
        assertThat(listener.delivered.single()).containsExactly("LaTeX", 90)
    }

    @Test
    fun aCaseOnlyRespellingWithTheSameFrequencyDoesNotNotify() {
        customWordsJson("en", """{"latex":200}""")
        val obs = observer(language = "en")
        obs.loadCustomWords()

        val listener = CustomWordsRecorder()
        obs.setChangeListener(listener)

        // The user re-added the word cased; the folded serving key and the frequency are
        // unchanged, so the predictor has nothing to do.
        customWordsJson("en", """{"LaTeX":200}""")
        obs.checkCustomChanges()

        assertWithMessage(
            "a case-only respelling must be a no-op for the lowercase serving maps — the " +
                "folded-key lookup instead reads the respelt entry as the 1000 default and " +
                "pushes a spurious, WRONG modification"
        ).that(listener.delivered).isEmpty()
        assertThat(listener.retracted).isEmpty()
    }

    @Test
    fun removingACasedCustomWordIsRetractedUnderItsFoldedKey() {
        customWordsJson("en", """{"LaTeX":200}""")
        val obs = observer(language = "en")
        obs.loadCustomWords()

        val listener = CustomWordsRecorder()
        obs.setChangeListener(listener)

        customWordsJson("en", "{}")
        obs.checkCustomChanges()

        assertThat(listener.retracted).hasSize(1)
        assertWithMessage("retraction reaches the predictor under the lowercase serving key")
            .that(listener.retracted.single()).containsExactly("latex")
        assertThat(obs.getCachedCustomWords()).isEmpty()
    }

    // ------------------------------------------------------------------ reflection

    private fun Any.setField(name: String, value: Any?) {
        val field = javaClass.declaredFields.firstOrNull { it.name == name }
        assertWithMessage(
            "field '$name' not found on ${javaClass.simpleName} — it was renamed or removed; " +
                "declared: ${javaClass.declaredFields.map { it.name }}"
        ).that(field).isNotNull()
        field!!.isAccessible = true
        field.set(this, value)
    }
}
