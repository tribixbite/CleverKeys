package tribixbite.cleverkeys

import android.content.SharedPreferences
import android.util.Log
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import java.io.File

/**
 * The user-word guard on REPLACE-mode contractions, end to end and **case-total**.
 *
 * `SuggestionHandler.replaceModeContractionFor` is guard #4 of the four in
 * `.claude/skills/contraction-system.md` §5: a REPLACE mapping takes the typed word's slot, so
 * it must never fire on a word the user added by hand. The shipped French table alone holds
 * ~18k `d'X` aliases — `dangle`, `dalliance`, `dorange` — that are ordinary strings someone may
 * have added as a name or a term of art.
 *
 * ## What was wrong, and why a case-INSENSITIVE stored set was the wrong fix
 *
 * The guard used to probe three casings (the word, its lowercase, its capitalised form) against
 * an exact-match `Set`. A user who stored `DONT` and typed `dont` matched none of them and had
 * their own word rewritten to `don't`. The obvious repair — make the persisted set itself
 * case-insensitive — changes add/remove/dedup semantics for a **user-owned, persisted** set:
 * `Foo` and `foo` would collapse into one entry, and removing either would remove both, for
 * every user, retroactively, to fix a lookup.
 *
 * The fix is therefore READ-SIDE only: `DictionaryManager.isUserWordIgnoringCase` answers over a
 * case-folded shadow of the set while the set itself keeps exact membership.
 * [storedUserWordsStayCaseSensitive] is the no-regression proof for that, and it passes both
 * before and after the change — it is the test that says the deferral's stated risk was not
 * incurred.
 *
 * ## Why the objects are allocated without their constructors
 *
 * Same reason as [ImeTeardownExecutorShutdownTest]: `SuggestionHandler` initialises
 * `Handler(Looper.getMainLooper())` in a field initialiser and `DictionaryManager`'s constructor
 * runs a SharedPreferences migration — both android.jar stubs that throw under `runMockTests`.
 * Objenesis allocates without the constructor and only the fields these paths touch are seeded,
 * so the REAL guard body and the REAL `DictionaryManager` lookup execute. Every other field is
 * left null: a future guard that reaches for one fails loudly instead of drifting out of cover.
 */
class ContractionUserWordGuardTest {

    private val objenesis = ObjenesisStd()

    /** What the production code wrote to prefs, so persistence can be asserted directly. */
    private val savedPrefs = mutableMapOf<String, String?>()

    private lateinit var prefs: SharedPreferences

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        prefs = mockk(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            savedPrefs[firstArg()] = secondArg()
            editor
        }
        every { prefs.getString(any(), any()) } returns null
    }

    @After
    fun teardown() {
        savedPrefs.clear()
        unmockkAll()
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * A real [DictionaryManager] holding [stored], seeded through the production
     * [DictionaryManager.addUserWord] so the write path (and its cache invalidation) is the one
     * under test rather than a reflective back door.
     */
    private fun dictionaryManager(
        language: String = "en",
        stored: Set<String> = emptySet(),
    ): DictionaryManager {
        val manager = objenesis.newInstance(DictionaryManager::class.java)
        manager.setField("prefs", prefs)
        manager.setField("gson", Gson())
        manager.setField("userWords", mutableSetOf<String>())
        manager.setField("currentLanguage", language)
        stored.forEach { manager.addUserWord(it) }
        return manager
    }

    /**
     * Run the real `replaceModeContractionFor` against [typed], with [mapping] as the REPLACE
     * table's answer for it and [dictionaries] as the personal dictionary.
     *
     * @return the display form the guard would substitute, or null when it refuses.
     */
    private fun guard(
        dictionaries: DictionaryManager?,
        typed: String,
        mapping: String?,
    ): String? {
        val contractions = mockk<ContractionManager>()
        // The real manager lowercases its key, so the table answers for any casing of `typed`.
        every { contractions.getNonPairedMapping(any()) } answers {
            if (firstArg<String>().lowercase() == typed.lowercase()) mapping else null
        }
        val coordinator = mockk<PredictionCoordinator>()
        every { coordinator.getDictionaryManager() } returns dictionaries

        val handler = objenesis.newInstance(SuggestionHandler::class.java)
        handler.setField("contractionManager", contractions)
        handler.setField("predictionCoordinator", coordinator)

        val method = SuggestionHandler::class.java
            .getDeclaredMethod("replaceModeContractionFor", String::class.java)
        method.isAccessible = true
        return method.invoke(handler, typed) as String?
    }

    // ------------------------------------------------- the case-total guard (new)

    @Test
    fun aWordStoredUppercaseIsProtectedWhenTypedLowercase() {
        val dictionaries = dictionaryManager(stored = setOf("DONT"))

        assertWithMessage(
            "the user stored `DONT`; typing `dont` must NOT be rewritten to `don't`. The old " +
                "three-casing probe (word / lowercase / capitalised) matched none of " +
                "`dont`, `dont`, `Dont` against a stored `DONT`, so the guard let the " +
                "REPLACE mapping destroy the user's own word in its own slot."
        ).that(guard(dictionaries, "dont", "don't")).isNull()
    }

    @Test
    fun aWordStoredInMixedCaseIsProtectedWhateverCasingIsTyped() {
        val dictionaries = dictionaryManager(stored = setOf("dOnt"))

        assertWithMessage(
            "a mixed-case stored form is reachable by no fixed number of probes — only a fold " +
                "answers it. Stored `dOnt`, typed `Dont`."
        ).that(guard(dictionaries, "Dont", "don't")).isNull()
    }

    /**
     * The real collision shape from the French table: `dangle` is a `contractions_fr.json` key
     * mapping to `d'angle`, and also a perfectly ordinary string to add as a name.
     */
    @Test
    fun theFrenchAliasShapeIsProtectedInEveryCasing() {
        val dictionaries = dictionaryManager(language = "fr", stored = setOf("DAngle"))

        assertWithMessage("stored `DAngle`, typed `dangle` — must not become `d'angle`")
            .that(guard(dictionaries, "dangle", "d'angle")).isNull()
        assertWithMessage("stored `DAngle`, typed `DANGLE` — must not become `d'angle`")
            .that(guard(dictionaries, "DANGLE", "d'angle")).isNull()
    }

    // ------------------------------------- the cases the old probe already covered

    @Test
    fun theCasingsTheOldProbeCoveredStayCovered() {
        assertWithMessage("stored exactly as typed — the common case")
            .that(guard(dictionaryManager(stored = setOf("dont")), "dont", "don't")).isNull()
        assertWithMessage("stored capitalised, typed lowercase")
            .that(guard(dictionaryManager(stored = setOf("Dont")), "dont", "don't")).isNull()
        assertWithMessage("stored lowercase, typed capitalised")
            .that(guard(dictionaryManager(stored = setOf("dont")), "Dont", "don't")).isNull()
    }

    @Test
    fun aContractionNobodyClaimedIsStillReplaced() {
        assertWithMessage(
            "with an empty personal dictionary the guard must pass the mapping through — a " +
                "guard that suppressed every contraction would be worse than none"
        ).that(guard(dictionaryManager(), "dont", "don't")).isEqualTo("don't")

        assertWithMessage(
            "an unrelated custom word must not suppress somebody else's contraction"
        ).that(guard(dictionaryManager(stored = setOf("wurzelbaum")), "dont", "don't"))
            .isEqualTo("don't")
    }

    @Test
    fun aKeyWithNoReplaceMappingIsLeftAloneWithoutConsultingTheDictionary() {
        assertWithMessage("no mapping means nothing to replace, dictionary or not")
            .that(guard(dictionaryManager(stored = setOf("hello")), "hello", null)).isNull()
    }

    @Test
    fun anUninitialisedDictionaryFallsThroughToTheMapping() {
        assertWithMessage(
            "prediction not yet initialised → no personal dictionary exists, so there is no " +
                "user word to protect and the mapping applies"
        ).that(guard(null, "dont", "don't")).isEqualTo("don't")
    }

    // ------------------------------------------------------- the semantics pin (d)

    /**
     * The persisted set keeps EXACT, case-sensitive membership.
     *
     * This is the proof that the read-side fold did not quietly become a stored-set change: the
     * deferral's stated risk was precisely "giving `userWords` case-insensitive membership
     * changes add/remove/dedup semantics for a persisted user-owned set". Green before the fold
     * and green after it means that risk was never taken.
     */
    @Test
    fun storedUserWordsStayCaseSensitive() {
        val manager = dictionaryManager(stored = setOf("foo"))
        savedPrefs.clear()
        manager.addUserWord("Foo")

        assertWithMessage("`foo` and `Foo` are TWO entries — adding one must not dedup the other")
            .that(userWordsOf(manager)).containsExactly("foo", "Foo")
        assertWithMessage("both casings persist verbatim")
            .that(savedPrefs["custom_words_en"]).contains(""""foo":100""")
        assertWithMessage("both casings persist verbatim")
            .that(savedPrefs["custom_words_en"]).contains(""""Foo":100""")

        manager.removeUserWord("foo")

        assertWithMessage("removing `foo` removes ONLY `foo`")
            .that(userWordsOf(manager)).containsExactly("Foo")
        assertWithMessage("exact membership is what `isUserWord` still answers")
            .that(manager.isUserWord("Foo")).isTrue()
        assertWithMessage("exact membership is what `isUserWord` still answers")
            .that(manager.isUserWord("foo")).isFalse()
    }

    // ------------------------------------------------------------ coherence (e)

    @Test
    fun theGuardTracksLaterAdditionsAndRemovals() {
        val dictionaries = dictionaryManager()

        assertWithMessage("nothing stored yet — the mapping applies")
            .that(guard(dictionaries, "dont", "don't")).isEqualTo("don't")

        dictionaries.addUserWord("DONT")
        assertWithMessage(
            "the guard must see a word the instant it is added — the folded view is derived " +
                "from the same set, not a snapshot taken at startup"
        ).that(guard(dictionaries, "dont", "don't")).isNull()

        dictionaries.removeUserWord("DONT")
        assertWithMessage(
            "and must stop protecting it the instant it is removed, or a stale folded view " +
                "would suppress a correct contraction forever"
        ).that(guard(dictionaries, "dont", "don't")).isEqualTo("don't")
    }

    @Test
    fun theGuardFollowsTheActiveLanguagesWordSet() {
        val dictionaries = dictionaryManager(language = "en", stored = setOf("DONT"))
        assertWithMessage("English custom word protects the English key")
            .that(guard(dictionaries, "dont", "don't")).isNull()

        // `setLanguage` reloads the set from the other language's prefs entry (ARC-079: that is
        // now ALL it does). The folded view must be reloaded with it.
        every { prefs.getString("custom_words_fr", any()) } returns """{"BONJOUR":100}"""
        dictionaries.setLanguage("fr")

        assertWithMessage(
            "after the reload the English-only custom word is gone, so the French field must " +
                "get the contraction it asked for"
        ).that(guard(dictionaries, "dont", "don't")).isEqualTo("don't")
        assertWithMessage("and the newly loaded French word must be protected, folded")
            .that(guard(dictionaries, "bonjour", "b'onjour")).isNull()
    }

    @Test
    fun clearingTheDictionaryReleasesTheGuard() {
        val dictionaries = dictionaryManager(stored = setOf("DONT"))
        assertWithMessage("stored → protected").that(guard(dictionaries, "dont", "don't")).isNull()

        dictionaries.clearUserDictionary()

        assertWithMessage("cleared → the folded view must be empty too")
            .that(guard(dictionaries, "dont", "don't")).isEqualTo("don't")
    }

    // ------------------------------------------------------- structural coherence

    /**
     * Every write to `userWords` must go through the one helper that also drops the folded view.
     *
     * This is a source pin because the realistic regression is not the fold breaking — it is a
     * NEW write site (a bulk import, a sync, an undo) touching the set directly and leaving the
     * folded view describing the previous contents. The result would be a guard that either
     * protects a word the user deleted or fails to protect one they just added, with every
     * behavioural test above still green.
     */
    @Test
    fun everyUserWordMutationGoesThroughTheInvalidatingHelper() {
        val source = File("src/main/kotlin/tribixbite/cleverkeys/DictionaryManager.kt")
        assertWithMessage("expected source file to exist: ${source.path} (run from project root)")
            .that(source.isFile).isTrue()
        val text = source.readText()

        val helper = "private fun mutateUserWords("
        assertWithMessage(
            "the mutation helper is gone from DictionaryManager — if it was renamed, update " +
                "this test; if it was removed, the case-folded view can silently go stale"
        ).that(text).contains(helper)

        // The helper's own body calls the mutation through the lambda receiver (`userWords.block()`),
        // so a legitimate write never spells one of these names — the whole file may be scanned.
        val offenders = Regex("""userWords\.(add|addAll|remove|removeAll|clear|retainAll)\(""")
            .findAll(text)
            .map { it.value }
            .toList()

        assertWithMessage(
            "found direct mutation(s) of `userWords` outside `mutateUserWords`: $offenders. " +
                "Route them through the helper — it is what invalidates the case-folded view " +
                "the REPLACE-mode contraction guard reads."
        ).that(offenders).isEmpty()
    }

    // ------------------------------------------------------------------ reflection

    /** The manager's own view of its set, for the case-sensitivity pin. */
    @Suppress("UNCHECKED_CAST")
    private fun userWordsOf(manager: DictionaryManager): Set<String> {
        val field = DictionaryManager::class.java.getDeclaredField("userWords")
        field.isAccessible = true
        return (field.get(manager) as Set<String>).toSet()
    }

    /**
     * Write a private field declared on the receiver's own class. Fails with the declaring
     * class's field list when the name is gone, so a rename produces a diagnosis rather than a
     * bare `NoSuchFieldException`.
     */
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
