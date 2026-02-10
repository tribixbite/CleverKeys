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
 * NOTE: DictionaryManager's constructor calls setLanguage() which creates WordPredictor →
 * AsyncDictionaryLoader → Handler(Looper.getMainLooper()) — all android.jar stubs.
 * mockkConstructor can't intercept Handler from android.jar on JVM.
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

        // Create mock SharedPreferences + Editor (DirectBootAware prefs)
        mockPrefs = mockk(relaxed = true)
        mockEditor = mockk(relaxed = true)
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } answers {
            savedStrings[firstArg()] = secondArg()
            mockEditor
        }
        every { mockEditor.remove(any()) } returns mockEditor

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
        every { mockPrefs.getString("custom_words_$language", null) } returns existingWords

        // Create instance via Objenesis (no constructor called)
        val objenesis = org.objenesis.ObjenesisStd()
        val manager = objenesis.newInstance(DictionaryManager::class.java)

        // Set private fields via reflection
        val clazz = DictionaryManager::class.java
        setField(manager, clazz, "context", mockContext)
        setField(manager, clazz, "prefs", mockPrefs)
        setField(manager, clazz, "gson", Gson())
        setField(manager, clazz, "predictors", mutableMapOf<String, Any>())
        setField(manager, clazz, "userWords", mutableSetOf<String>())
        setField(manager, clazz, "currentLanguage", language)
        setField(manager, clazz, "currentPredictor", null)

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

    /** Invoke private saveUserWords() via reflection */
    private fun invokeSaveUserWords(manager: DictionaryManager) {
        val method = DictionaryManager::class.java.getDeclaredMethod("saveUserWords")
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

        // Verify migrated JSON contains all words with frequency 100
        val savedJson = savedStrings["custom_words_en"]
        assertThat(savedJson).isNotNull()
        assertThat(savedJson).contains("\"hello\"")
        assertThat(savedJson).contains("\"world\"")
        assertThat(savedJson).contains("\"test\"")
        assertThat(savedJson).contains("100")
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

    // =========================================================================
    // JSON persistence format
    // =========================================================================

    @Test
    fun `user words saved as JSON map with frequency 100`() {
        val manager = buildManager()
        savedStrings.clear()

        manager.addUserWord("testword")

        val json = savedStrings["custom_words_en"]
        assertThat(json).isNotNull()
        // Gson serializes Map<String, Int> — expect {"testword":100}
        assertThat(json).contains("\"testword\"")
        assertThat(json).contains("100")
    }
}
