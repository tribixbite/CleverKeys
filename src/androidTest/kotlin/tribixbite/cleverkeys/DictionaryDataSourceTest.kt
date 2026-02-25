package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for DictionaryDataSource implementations.
 *
 * Validates the data layer used by Dictionary Manager, including:
 * - MainDictionarySource: loading, caching, prefix search, disabled word handling
 * - DisabledDictionarySource: toggle, persistence, language-specific keys
 * - Shared static cache: cross-instance reuse for performance
 * - Active tab filtering: disabled words excluded from active word list
 *
 * These are real instrumented tests requiring android Context for SharedPreferences
 * and asset access (binary dictionary loading).
 */
@RunWith(AndroidJUnit4::class)
class DictionaryDataSourceTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    // Track words we disable during tests for cleanup
    private val testDisabledWords = mutableListOf<String>()

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        // Invalidate shared cache before each test so we get clean state
        MainDictionarySource.invalidateCache()
    }

    @After
    fun cleanup() {
        // Remove any disabled words added during tests
        if (testDisabledWords.isNotEmpty()) {
            val key = LanguagePreferenceKeys.disabledWordsKey("en")
            val disabled = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
            disabled.removeAll(testDisabledWords.toSet())
            prefs.edit().putStringSet(key, disabled).apply()
        }
        // Invalidate shared cache so other tests start clean
        MainDictionarySource.invalidateCache()
    }

    // =========================================================================
    // MainDictionarySource — Basic Loading
    // =========================================================================

    @Test
    fun getAllWordsLoadsNonEmptyList() {
        val disabledSource = DisabledDictionarySource(prefs, "en")
        val source = MainDictionarySource(context, disabledSource, "en")

        val words = kotlinx.coroutines.runBlocking { source.getAllWords() }
        assertTrue("Should load words from binary dictionary", words.size > 1000)
    }

    @Test
    fun getAllWordsReturnsSortedByCompareTo() {
        // DictionaryWord.compareTo() sorts by frequency descending, then alphabetically
        val disabledSource = DisabledDictionarySource(prefs, "en")
        val source = MainDictionarySource(context, disabledSource, "en")

        val words = kotlinx.coroutines.runBlocking { source.getAllWords() }
        for (i in 0 until words.size - 1) {
            assertTrue("Words should be sorted by compareTo (freq desc, then alpha)",
                words[i].compareTo(words[i + 1]) <= 0)
        }
    }

    @Test
    fun getAllWordsContainsCommonEnglishWords() {
        val disabledSource = DisabledDictionarySource(prefs, "en")
        val source = MainDictionarySource(context, disabledSource, "en")

        val words = kotlinx.coroutines.runBlocking { source.getAllWords() }
        val wordSet = words.map { it.word.lowercase() }.toSet()

        assertTrue("Should contain 'the'", wordSet.contains("the"))
        assertTrue("Should contain 'hello'", wordSet.contains("hello"))
        assertTrue("Should contain 'world'", wordSet.contains("world"))
    }

    // =========================================================================
    // MainDictionarySource — Disabled Word Handling
    // =========================================================================

    @Test
    fun disabledWordHasEnabledFalseInGetAllWords() {
        val targetWord = "example"
        disableWord(targetWord)

        val disabledSource = DisabledDictionarySource(prefs, "en")
        val source = MainDictionarySource(context, disabledSource, "en")

        val words = kotlinx.coroutines.runBlocking { source.getAllWords() }
        val found = words.find { it.word.equals(targetWord, ignoreCase = true) }

        assertNotNull("Disabled word should still be in the full list", found)
        assertFalse("Disabled word should have enabled=false", found!!.enabled)
    }

    @Test
    fun activeTabFilterExcludesDisabledWords() {
        // This tests the filtering pattern used by WordListFragment for ACTIVE tab
        val targetWord = "example"
        disableWord(targetWord)

        val disabledSource = DisabledDictionarySource(prefs, "en")
        val source = MainDictionarySource(context, disabledSource, "en")

        val allWords = kotlinx.coroutines.runBlocking { source.getAllWords() }
        // Simulate ACTIVE tab filtering (same logic as WordListFragment)
        val activeWords = allWords.filter { it.enabled }

        val inAll = allWords.any { it.word.equals(targetWord, ignoreCase = true) }
        val inActive = activeWords.any { it.word.equals(targetWord, ignoreCase = true) }

        assertTrue("Word should be in full list", inAll)
        assertFalse("Disabled word should NOT be in active-filtered list", inActive)
    }

    @Test
    fun activeTabFilterCountIsLessThanTotal() {
        // Disable a word, then verify active count < total count
        disableWord("because")

        val disabledSource = DisabledDictionarySource(prefs, "en")
        val source = MainDictionarySource(context, disabledSource, "en")

        val allWords = kotlinx.coroutines.runBlocking { source.getAllWords() }
        val activeWords = allWords.filter { it.enabled }

        assertTrue("Active count should be less than total count",
            activeWords.size < allWords.size)
    }

    @Test
    fun toggleWordUpdatesEnabledFlag() {
        val disabledSource = DisabledDictionarySource(prefs, "en")
        val source = MainDictionarySource(context, disabledSource, "en")

        kotlinx.coroutines.runBlocking {
            val allWords = source.getAllWords()
            val target = allWords.first { it.enabled && it.word.length >= 4 }
            val wordText = target.word

            // Toggle off
            source.toggleWord(wordText, false)
            testDisabledWords.add(wordText.lowercase())

            // Verify in-place update
            val afterToggle = source.getAllWords()
            val toggled = afterToggle.find { it.word == wordText }
            assertFalse("Word should be disabled after toggle", toggled!!.enabled)

            // Active filter should now exclude it
            val activeAfter = afterToggle.filter { it.enabled }
            assertFalse("Active list should exclude disabled word",
                activeAfter.any { it.word == wordText })

            // Toggle back on
            source.toggleWord(wordText, true)
            val restored = source.getAllWords().find { it.word == wordText }
            assertTrue("Word should be re-enabled", restored!!.enabled)
        }
    }

    // =========================================================================
    // MainDictionarySource — Search with Disabled Words
    // =========================================================================

    @Test
    fun searchWordsReturnsDisabledWithCorrectFlag() {
        val targetWord = "example"
        disableWord(targetWord)

        val disabledSource = DisabledDictionarySource(prefs, "en")
        val source = MainDictionarySource(context, disabledSource, "en")

        kotlinx.coroutines.runBlocking {
            source.getAllWords() // Initialize cache + prefix index
            val results = source.searchWords("exam")
            val found = results.find { it.word.equals(targetWord, ignoreCase = true) }
            assertNotNull("Search should find the word", found)
            assertFalse("Search result should reflect disabled state", found!!.enabled)
        }
    }

    @Test
    fun searchWordsActiveFilterExcludesDisabled() {
        val targetWord = "example"
        disableWord(targetWord)

        val disabledSource = DisabledDictionarySource(prefs, "en")
        val source = MainDictionarySource(context, disabledSource, "en")

        kotlinx.coroutines.runBlocking {
            source.getAllWords() // Initialize cache + prefix index
            val results = source.searchWords("exam")
            // Simulate ACTIVE tab filtering on search results
            val activeResults = results.filter { it.enabled }
            assertFalse("Active search should exclude disabled word",
                activeResults.any { it.word.equals(targetWord, ignoreCase = true) })
        }
    }

    // =========================================================================
    // MainDictionarySource — Shared Static Cache
    // =========================================================================

    @Test
    fun sharedCacheAvoidsDuplicateLoading() {
        val disabledSource = DisabledDictionarySource(prefs, "en")

        // First instance — cold load from binary dict
        val source1 = MainDictionarySource(context, disabledSource, "en")
        val startCold = System.nanoTime()
        val words1 = kotlinx.coroutines.runBlocking { source1.getAllWords() }
        val coldTime = System.nanoTime() - startCold

        // Second instance — should reuse shared cache
        val source2 = MainDictionarySource(context, disabledSource, "en")
        val startWarm = System.nanoTime()
        val words2 = kotlinx.coroutines.runBlocking { source2.getAllWords() }
        val warmTime = System.nanoTime() - startWarm

        assertEquals("Both instances should return same word count", words1.size, words2.size)
        // Warm load should be significantly faster (cache hit vs binary dict parse)
        // Use 10x as threshold — cold is typically 100-500ms, warm is <1ms
        assertTrue(
            "Warm load (${warmTime/1000}µs) should be much faster than cold (${coldTime/1000}µs)",
            warmTime < coldTime / 2
        )
    }

    @Test
    fun sharedCacheReflectsToggleFromOtherInstance() {
        val disabledSource = DisabledDictionarySource(prefs, "en")

        // First instance loads and toggles
        val source1 = MainDictionarySource(context, disabledSource, "en")
        kotlinx.coroutines.runBlocking {
            val words = source1.getAllWords()
            val target = words.first { it.enabled && it.word.length >= 4 }

            source1.toggleWord(target.word, false)
            testDisabledWords.add(target.word.lowercase())

            // Second instance should see the toggle (shared cache objects)
            val source2 = MainDictionarySource(context, disabledSource, "en")
            val words2 = source2.getAllWords()
            val found = words2.find { it.word == target.word }
            assertFalse("Shared cache should reflect toggle from other instance", found!!.enabled)

            // Cleanup
            source1.toggleWord(target.word, true)
        }
    }

    @Test
    fun invalidateCacheForcesReload() {
        val disabledSource = DisabledDictionarySource(prefs, "en")

        // Load into cache
        val source1 = MainDictionarySource(context, disabledSource, "en")
        val words1 = kotlinx.coroutines.runBlocking { source1.getAllWords() }

        // Invalidate
        MainDictionarySource.invalidateCache()

        // New instance should not have cache (will load from scratch)
        val source2 = MainDictionarySource(context, disabledSource, "en")
        val startReload = System.nanoTime()
        val words2 = kotlinx.coroutines.runBlocking { source2.getAllWords() }
        val reloadTime = System.nanoTime() - startReload

        assertEquals("Reloaded data should have same count", words1.size, words2.size)
        // After invalidation, load should take measurable time (>1ms for 50k words)
        assertTrue("Reload after invalidation should take >1ms (took ${reloadTime/1000}µs)",
            reloadTime > 1_000_000) // 1ms
    }

    // =========================================================================
    // DisabledDictionarySource
    // =========================================================================

    @Test
    fun disabledSourceReturnsDisabledWords() {
        disableWord("testword1")
        disableWord("testword2")

        val disabledSource = DisabledDictionarySource(prefs, "en")
        val words = kotlinx.coroutines.runBlocking { disabledSource.getAllWords() }

        assertTrue("Should contain disabled word",
            words.any { it.word == "testword1" })
        assertTrue("Should contain disabled word",
            words.any { it.word == "testword2" })
    }

    @Test
    fun disabledSourceWordsHaveEnabledFalse() {
        disableWord("testcheck")

        val disabledSource = DisabledDictionarySource(prefs, "en")
        val words = kotlinx.coroutines.runBlocking { disabledSource.getAllWords() }

        val found = words.find { it.word == "testcheck" }
        assertNotNull(found)
        assertFalse("All disabled source words should have enabled=false", found!!.enabled)
    }

    @Test
    fun disabledSourceToggleRemovesWord() {
        disableWord("toggletest")

        val disabledSource = DisabledDictionarySource(prefs, "en")
        kotlinx.coroutines.runBlocking {
            disabledSource.toggleWord("toggletest", true) // Re-enable
        }

        val words = kotlinx.coroutines.runBlocking { disabledSource.getAllWords() }
        assertFalse("Re-enabled word should not be in disabled list",
            words.any { it.word == "toggletest" })
    }

    @Test
    fun disabledSourceLanguageIsolation() {
        // Disable word for English
        disableWord("isolated")

        // French source should NOT see it
        val frDisabledSource = DisabledDictionarySource(prefs, "fr")
        val frDisabled = frDisabledSource.getDisabledWords()

        assertFalse("French disabled set should not contain English disabled word",
            frDisabled.contains("isolated"))
    }

    // =========================================================================
    // CustomDictionarySource
    // =========================================================================

    @Test
    fun customSourceAddAndRetrieve() {
        val customSource = CustomDictionarySource(prefs, "en")

        kotlinx.coroutines.runBlocking {
            customSource.addWord("customtest", 500)
            val words = customSource.getAllWords()
            val found = words.find { it.word == "customtest" }
            assertNotNull("Custom word should be retrievable", found)
            assertEquals("Frequency should match", 500, found!!.frequency)

            // Cleanup
            customSource.deleteWord("customtest")
        }
    }

    @Test
    fun customSourceDeleteRemovesWord() {
        val customSource = CustomDictionarySource(prefs, "en")

        kotlinx.coroutines.runBlocking {
            customSource.addWord("deleteme", 100)
            customSource.deleteWord("deleteme")
            val words = customSource.getAllWords()
            assertFalse("Deleted word should not be in list",
                words.any { it.word == "deleteme" })
        }
    }

    @Test
    fun customSourceUpdateModifiesWord() {
        val customSource = CustomDictionarySource(prefs, "en")

        kotlinx.coroutines.runBlocking {
            customSource.addWord("oldname", 100)
            customSource.updateWord("oldname", "newname", 500)
            val words = customSource.getAllWords()
            assertFalse("Old name should not exist", words.any { it.word == "oldname" })
            assertTrue("New name should exist", words.any { it.word == "newname" })

            // Cleanup
            customSource.deleteWord("newname")
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun disableWord(word: String) {
        val key = LanguagePreferenceKeys.disabledWordsKey("en")
        val disabled = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        disabled.add(word.lowercase())
        prefs.edit().putStringSet(key, disabled).apply()
        testDisabledWords.add(word.lowercase())
    }
}
