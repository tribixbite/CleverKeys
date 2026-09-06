package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * MockK-based JVM tests for DictionaryManager.
 *
 * Tests user word operations (add, remove, isUserWord, clear) and language switching.
 *
 * NOTE: the constructor still reaches android.jar stubs — `DirectBootAwarePreferences` and the
 * legacy-custom-words migration. (Until ARC-079 it also built a `WordPredictor` per language,
 * whose `AsyncDictionaryLoader` → `Handler(Looper.getMainLooper())` chain was the hard blocker;
 * `setLanguage` is now pure bookkeeping and is exercised directly below.)
 *
 * Strategy: Create DictionaryManager via Objenesis (without calling constructor),
 * then set private fields via reflection. This tests the user word CRUD methods
 * and language switching logic without triggering the android stub chain.
 *
 * Constructor/migration tests call migrateLegacyCustomWords() directly via reflection.
 */
class DictionaryManagerTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var mockLegacyPrefs: SharedPreferences
    private lateinit var mockLegacyEditor: SharedPreferences.Editor

    // Capture what's written to prefs so we can verify JSON content
    private val savedStrings = mutableMapOf<String, String?>()

    @Before
    fun setup() {
        // Mock android.util.Log
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        // Mock DirectBootAwarePreferences @JvmStatic method
        mockkStatic(DirectBootAwarePreferences::get_shared_preferences)

        // Mock Locale.getDefault()
        mockkStatic(Locale::class)
        every { Locale.getDefault() } returns Locale.ENGLISH

        // Create mock SharedPreferences + Editor (DirectBootAware prefs).
        // The store is READ-YOUR-WRITES: getString serves whatever putString last saved
        // (via [savedStrings]). C-4 made membership merge against a FRESH pref read on
        // every mutation, so a store whose reads ignore writes would misrepresent the
        // production contract — and is exactly how the stale-membership stomp hid.
        mockPrefs = mockk(relaxed = true)
        mockEditor = mockk(relaxed = true)
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } answers {
            savedStrings[firstArg()] = secondArg()
            mockEditor
        }
        every { mockEditor.remove(any()) } returns mockEditor
        every { mockPrefs.getString(any(), any()) } answers {
            savedStrings[firstArg()] ?: secondArg()
        }

        // Create mock legacy prefs + editor
        mockLegacyPrefs = mockk(relaxed = true)
        mockLegacyEditor = mockk(relaxed = true)
        every { mockLegacyPrefs.edit() } returns mockLegacyEditor
        every { mockLegacyEditor.remove(any()) } returns mockLegacyEditor

        // Wire up context
        mockContext = mockk(relaxed = true)
        every { DirectBootAwarePreferences.get_shared_preferences(any()) } returns mockPrefs
        every { mockContext.getSharedPreferences("user_dictionary", Context.MODE_PRIVATE) } returns mockLegacyPrefs
    }

    @After
    fun teardown() {
        savedStrings.clear()
        unmockkStatic(DirectBootAwarePreferences::get_shared_preferences)
        unmockkStatic(Log::class)
        unmockkStatic(Locale::class)
    }

    /**
     * Create a DictionaryManager without calling the constructor (avoids Handler Stub!).
     * Uses Objenesis (available via MockK dependency) to create the instance, then
     * sets internal fields via reflection.
     */
    private fun buildManager(
        language: String = "en",
        existingWords: String? = null
    ): DictionaryManager {
        // Seed the read-your-writes store rather than stubbing getString directly, so
        // subsequent production writes to the same key stay visible to production reads.
        if (existingWords != null) savedStrings["custom_words_$language"] = existingWords

        // Create instance via Objenesis (no constructor called)
        val objenesis = org.objenesis.ObjenesisStd()
        val manager = objenesis.newInstance(DictionaryManager::class.java)

        // Set private fields via reflection
        val clazz = DictionaryManager::class.java
        setField(manager, clazz, "context", mockContext)
        setField(manager, clazz, "prefs", mockPrefs)
        setField(manager, clazz, "gson", Gson())
        setField(manager, clazz, "userWords", mutableSetOf<String>())
        setField(manager, clazz, "currentLanguage", language)

        // Load user words from prefs (mimics what loadUserWords() does)
        invokeLoadUserWords(manager)

        return manager
    }

    private fun setField(obj: Any, clazz: Class<*>, fieldName: String, value: Any?) {
        val field = clazz.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(obj, value)
    }

    private fun getField(obj: Any, clazz: Class<*>, fieldName: String): Any? {
        val field = clazz.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(obj)
    }

    /** Invoke private loadUserWords() via reflection */
    private fun invokeLoadUserWords(manager: DictionaryManager) {
        val method = DictionaryManager::class.java.getDeclaredMethod("loadUserWords")
        method.isAccessible = true
        method.invoke(manager)
    }

    // =========================================================================
    // Constructor / Migration tests (via reflection)
    // =========================================================================

    @Test
    fun `migration with no legacy words does not modify prefs`() {
        every { mockLegacyPrefs.getStringSet("user_words", null) } returns null
        every { mockPrefs.getString("custom_words_en", null) } returns null

        // Create instance and invoke migration directly
        val manager = buildManager()
        val migrateMethod = DictionaryManager::class.java.getDeclaredMethod("migrateLegacyCustomWords")
        migrateMethod.isAccessible = true
        migrateMethod.invoke(manager)

        // Legacy editor should never have remove() called since there's nothing to migrate
        verify(exactly = 0) { mockLegacyEditor.remove(any()) }
    }

    @Test
    fun `migration with legacy words saves to new format`() {
        val legacyWords = setOf("hello", "world", "test")
        every { mockLegacyPrefs.getStringSet("user_words", null) } returns legacyWords
        every { mockPrefs.getString("custom_words_en", null) } returns null

        val manager = buildManager()
        val migrateMethod = DictionaryManager::class.java.getDeclaredMethod("migrateLegacyCustomWords")
        migrateMethod.isAccessible = true
        migrateMethod.invoke(manager)

        // Verify migration saved JSON to custom_words_en
        verify { mockEditor.putString("custom_words_en", any()) }
        // Verify legacy data was cleared
        verify { mockLegacyEditor.remove("user_words") }
        verify { mockLegacyEditor.apply() }

        // Verify migrated JSON contains all words at the wave-U2 default (255 — top of
        // the 1..255 stored user scale; the legacy set carried no frequencies)
        val savedJson = savedStrings["custom_words_en"]
        assertThat(savedJson).isNotNull()
        assertThat(savedJson).contains("\"hello\"")
        assertThat(savedJson).contains("\"world\"")
        assertThat(savedJson).contains("\"test\"")
        assertThat(savedJson).contains("255")
    }

    @Test
    fun `migration merges legacy with existing new-format words`() {
        val legacyWords = setOf("newword")
        every { mockLegacyPrefs.getStringSet("user_words", null) } returns legacyWords

        // Build with existing words — then set the mock AFTER buildManager (which overwrites it)
        val manager = buildManager(existingWords = """{"existing":100}""")

        // Re-set the mock for migration to read existing words
        every { mockPrefs.getString("custom_words_en", null) } returns """{"existing":100}"""

        val migrateMethod = DictionaryManager::class.java.getDeclaredMethod("migrateLegacyCustomWords")
        migrateMethod.isAccessible = true
        savedStrings.clear()
        migrateMethod.invoke(manager)

        // Verify merged JSON contains both existing and new words
        val savedJson = savedStrings["custom_words_en"]
        assertThat(savedJson).isNotNull()
        assertThat(savedJson).contains("\"existing\"")
        assertThat(savedJson).contains("\"newword\"")
    }

    // =========================================================================
    // addUserWord tests
    // =========================================================================

    @Test
    fun `addUserWord adds word and saves`() {
        val manager = buildManager()
        manager.addUserWord("kotlin")

        assertThat(manager.isUserWord("kotlin")).isTrue()
        verify(atLeast = 1) { mockEditor.putString("custom_words_en", any()) }
    }

    @Test
    fun `addUserWord with null is no-op`() {
        val manager = buildManager()
        savedStrings.clear()
        manager.addUserWord(null)

        // No additional save should occur
        assertThat(savedStrings).isEmpty()
    }

    @Test
    fun `addUserWord with empty string is no-op`() {
        val manager = buildManager()
        savedStrings.clear()
        manager.addUserWord("")

        assertThat(savedStrings).isEmpty()
    }

    // =========================================================================
    // removeUserWord tests
    // =========================================================================

    @Test
    fun `removeUserWord removes previously added word`() {
        val manager = buildManager()
        manager.addUserWord("remove_me")
        assertThat(manager.isUserWord("remove_me")).isTrue()

        manager.removeUserWord("remove_me")
        assertThat(manager.isUserWord("remove_me")).isFalse()
    }

    // =========================================================================
    // C-4 (2026-09-06 audit): cross-writer stomp. `custom_words_<lang>` has THREE
    // writers — this class (IME add paths), CustomDictionarySource (the Dictionary
    // Manager UI's Custom-Words tab), and backup import. Every whole-store rewrite
    // here must merge membership against a FRESH pref read, or another writer's
    // words are silently destroyed / resurrected by the next IME-side mutation.
    // =========================================================================

    /** The store as production reads it back — parsed from the last JSON write. */
    private fun storedWords(key: String = "custom_words_en"): Map<String, Int> {
        val json = savedStrings[key] ?: return emptyMap()
        val type = object : com.google.gson.reflect.TypeToken<Map<String, Int>>() {}.type
        return Gson().fromJson(json, type)
    }

    @Test
    fun `addUserWord preserves a word another writer added since load`() {
        // IME comes up with an empty custom dictionary...
        val manager = buildManager()

        // ...then the user adds a word in the Dictionary Manager UI, which writes through
        // CustomDictionarySource — a DIFFERENT writer this instance never observes.
        kotlinx.coroutines.runBlocking {
            CustomDictionarySource(mockPrefs, "en").addWord("flurble", 255)
        }
        assertThat(storedWords()).containsEntry("flurble", 255)

        // The next IME-side add ("Add to dictionary?" prompt) must not stomp it.
        manager.addUserWord("zeb")

        val stored = storedWords()
        assertThat(stored).containsEntry("flurble", 255)
        assertThat(stored).containsEntry("zeb", 255)
        // And the in-RAM membership adopts the foreign add, so the contraction
        // guard (isUserWordIgnoringCase) protects it without a language switch.
        assertThat(manager.isUserWord("flurble")).isTrue()
    }

    @Test
    fun `addUserWord does not resurrect a word another writer deleted`() {
        val manager = buildManager(existingWords = """{"flurble":255}""")
        assertThat(manager.isUserWord("flurble")).isTrue()

        // Deleted in the Dictionary Manager UI while this instance still holds it in RAM.
        kotlinx.coroutines.runBlocking {
            CustomDictionarySource(mockPrefs, "en").deleteWord("flurble")
        }
        assertThat(storedWords()).doesNotContainKey("flurble")

        manager.addUserWord("zeb")

        val stored = storedWords()
        assertThat(stored).containsEntry("zeb", 255)
        assertThat(stored).doesNotContainKey("flurble")
        assertThat(manager.isUserWord("flurble")).isFalse()
    }

    @Test
    fun `removeUserWord removes only its word and preserves foreign adds`() {
        val manager = buildManager(existingWords = """{"mine":40}""")
        kotlinx.coroutines.runBlocking {
            CustomDictionarySource(mockPrefs, "en").addWord("theirs", 200)
        }

        manager.removeUserWord("mine")

        val stored = storedWords()
        assertThat(stored).doesNotContainKey("mine")
        assertThat(stored).containsEntry("theirs", 200)
    }

    // =========================================================================
    // isUserWord tests
    // =========================================================================

    @Test
    fun `isUserWord returns false for unknown word`() {
        val manager = buildManager()
        assertThat(manager.isUserWord("nonexistent")).isFalse()
    }

    @Test
    fun `isUserWord returns true for added word`() {
        val manager = buildManager()
        manager.addUserWord("present")
        assertThat(manager.isUserWord("present")).isTrue()
    }

    // =========================================================================
    // clearUserDictionary tests
    // =========================================================================

    @Test
    fun `clearUserDictionary removes all words`() {
        val manager = buildManager()
        manager.addUserWord("word1")
        manager.addUserWord("word2")
        manager.addUserWord("word3")
        assertThat(manager.isUserWord("word1")).isTrue()

        manager.clearUserDictionary()

        assertThat(manager.isUserWord("word1")).isFalse()
        assertThat(manager.isUserWord("word2")).isFalse()
        assertThat(manager.isUserWord("word3")).isFalse()
    }

    // =========================================================================
    // Language tests
    // =========================================================================

    @Test
    fun `getCurrentLanguage returns current language`() {
        val manager = buildManager(language = "en")
        assertThat(manager.getCurrentLanguage()).isEqualTo("en")
    }

    @Test
    fun `buildManager with French loads French words`() {
        val manager = buildManager(language = "fr", existingWords = """{"bonjour":100}""")
        assertThat(manager.getCurrentLanguage()).isEqualTo("fr")
        assertThat(manager.isUserWord("bonjour")).isTrue()
    }

    /**
     * ARC-079 — `setLanguage` is pure bookkeeping: current language + the user-word set for
     * that language. It must NOT build a predictor.
     *
     * Before ARC-079 this test could not exist: `setLanguage` constructed a `WordPredictor`,
     * whose `AsyncDictionaryLoader` reaches `Handler(Looper.getMainLooper())` — an android.jar
     * stub that throws on the JVM (the reason this whole file goes through Objenesis in the
     * first place, see the class KDoc). That the call now completes here IS the assertion that
     * the duplicate full-dictionary residency is gone; the source-level pin lives in
     * [LearningWiringDriftTest].
     */
    @Test
    fun `setLanguage swaps the user-word set without constructing a predictor`() {
        val manager = buildManager(language = "en", existingWords = """{"hello":100}""")
        assertThat(manager.isUserWord("hello")).isTrue()

        every { mockPrefs.getString("custom_words_fr", null) } returns """{"bonjour":100}"""

        manager.setLanguage("fr")

        assertThat(manager.getCurrentLanguage()).isEqualTo("fr")
        assertThat(manager.isUserWord("bonjour")).isTrue()
        // The previous language's custom words are not carried over — a French field must not
        // treat an English-only custom word as user-owned.
        assertThat(manager.isUserWord("hello")).isFalse()
    }

    @Test
    fun `setLanguage with null falls back to English`() {
        val manager = buildManager(language = "fr")
        every { mockPrefs.getString("custom_words_en", null) } returns """{"hello":100}"""

        manager.setLanguage(null)

        assertThat(manager.getCurrentLanguage()).isEqualTo("en")
        assertThat(manager.isUserWord("hello")).isTrue()
    }

    // =========================================================================
    // JSON persistence format
    // =========================================================================

    @Test
    fun `user words saved as JSON map with the default frequency 255`() {
        val manager = buildManager()
        savedStrings.clear()

        manager.addUserWord("testword")

        val json = savedStrings["custom_words_en"]
        assertThat(json).isNotNull()
        // Gson serializes Map<String, Int> — expect {"testword":255} (wave U2:
        // a user-added word defaults to the top of the 1..255 stored scale)
        assertThat(json).contains("\"testword\"")
        assertThat(json).contains("255")
    }

    @Test
    fun `saveUserWords preserves a stored frequency the user set`() {
        // Wave U2 regression guard: the old saveUserWords rewrote EVERY word to 100 on
        // any add/remove, destroying dialog-set frequencies. Adding a second word must
        // keep the first word's stored value.
        val manager = buildManager(existingWords = """{"boosted":40}""")
        every { mockPrefs.getString("custom_words_en", null) } returns """{"boosted":40}"""
        savedStrings.clear()

        manager.addUserWord("testword")

        val json = savedStrings["custom_words_en"]
        assertThat(json).isNotNull()
        assertThat(json).contains("\"boosted\":40")
        assertThat(json).contains("\"testword\":255")
    }
}
