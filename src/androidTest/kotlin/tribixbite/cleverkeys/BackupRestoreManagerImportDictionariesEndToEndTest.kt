package tribixbite.cleverkeys

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.personalization.UserVocabulary
import java.io.File

/**
 * End-to-end coverage of the public `BackupRestoreManager.importDictionaries(uri)`
 * delegator chain. Symmetric to [BackupRestoreManagerImportConfigEndToEndTest],
 * but for the dictionary path: build plan -> apply with empty exclusion sets
 * (legacy headless contract).
 *
 * Verifies all three input shapes the format supports today:
 *  - v2 `custom_words_by_language` map
 *  - legacy `custom_words` (routed to English)
 *  - legacy `user_words` array
 *
 * The first-writer-wins precedence (v2 > legacy custom_words > legacy user_words)
 * is exercised by the third test.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreManagerImportDictionariesEndToEndTest {

    private lateinit var context: Context
    private lateinit var manager: BackupRestoreManager
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = BackupRestoreManager(context)
        tmpDir = context.cacheDir.resolve("dict-test").apply { deleteRecursively(); mkdirs() }
        // Clear language-keyed dict prefs so each test starts empty
        DirectBootAwarePreferences.get_shared_preferences(context).edit()
            .remove(LanguagePreferenceKeys.customWordsKey("en"))
            .remove(LanguagePreferenceKeys.disabledWordsKey("en"))
            .commit()
    }

    private fun writeImportFile(json: String): android.net.Uri {
        val f = File(tmpDir, "dict.json")
        f.writeText(json)
        return android.net.Uri.fromFile(f)
    }

    @Test
    fun importDictionaries_v2Format_addsCustomWordsToEnglish() {
        val json = """{
            "custom_words_by_language":{"en":{"foo":50,"bar":100}}
        }"""

        val result = manager.importDictionaries(writeImportFile(json))

        assertEquals(2, result.userWordsImported)
        // Read back via prefs to verify on-disk state
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        val type = object : TypeToken<Map<String, Int>>() {}.type
        val storedEn: Map<String, Int> =
            Gson().fromJson(prefs.getString(LanguagePreferenceKeys.customWordsKey("en"), "{}"), type)
        assertEquals(50, storedEn["foo"])
        assertEquals(100, storedEn["bar"])
    }

    @Test
    fun importDictionaries_legacyFormat_routedToEnglish() {
        val json = """{"custom_words":{"legacy":42}}"""
        val result = manager.importDictionaries(writeImportFile(json))

        assertEquals(1, result.userWordsImported)
    }

    @Test
    fun importDictionaries_threeSourcesFirstWriterWins() {
        // Same word "foo" in all three sources with different frequencies.
        // v2 (10) wins; legacy custom_words (20) and user_words (30) skipped.
        val json = """{
            "custom_words_by_language":{"en":{"foo":10}},
            "custom_words":{"foo":20},
            "user_words":[{"word":"foo","frequency":30}]
        }"""

        val result = manager.importDictionaries(writeImportFile(json))

        assertEquals(1, result.userWordsImported)
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        val type = object : TypeToken<Map<String, Int>>() {}.type
        val storedEn: Map<String, Int> =
            Gson().fromJson(prefs.getString(LanguagePreferenceKeys.customWordsKey("en"), "{}"), type)
        assertEquals(10, storedEn["foo"])
    }

    /**
     * Note10+ 2026-11-24: the maintainer's settings export and then his dictionary export,
     * restored on a phone where CleverKeys was not the running keyboard, kept 5,000 of his 7,165
     * learned words, though the settings had just set the vocabulary cap
     * (`personalization_max_words`) to 10,000. The vocabulary read its cap from the in-memory
     * Config, and only the keyboard service refreshes Config when a preference changes. Here
     * Config holds the old cap and the preference the restored one, as after that settings import.
     */
    @Test
    fun importDictionaries_learnedVocabularyKeepsTheStoredCapNotAStaleConfig() {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        val vocabulary = UserVocabulary.getInstance(context)
        val savedVocabulary = vocabulary.exportToJson()
        val hadCap = prefs.contains(CAP_KEY)
        val savedCap = Config.safeGetInt(prefs, CAP_KEY, Defaults.PERSONALIZATION_MAX_WORDS)
        val config = Config.globalConfigOrNull()
        val savedConfigCap = config?.personalization_max_words
        try {
            prefs.edit().putInt(CAP_KEY, RESTORED_CAP).commit()
            config?.personalization_max_words = Defaults.PERSONALIZATION_MAX_WORDS
            val now = System.currentTimeMillis()
            val rows = (1..RESTORED_WORDS).map {
                mapOf("word" to "ckvocab$it", "usageCount" to 3, "lastUsed" to now, "firstUsed" to now - 1_000L)
            }
            val result = manager.importDictionaries(writeImportFile(Gson().toJson(mapOf("user_vocabulary" to rows))))

            assertEquals("words the import reports", RESTORED_WORDS, result.learnedVocabularyImported)
            assertEquals("every restored word fits the stored cap", RESTORED_WORDS, vocabulary.size())
        } finally {
            prefs.edit().apply { if (hadCap) putInt(CAP_KEY, savedCap) else remove(CAP_KEY) }.commit()
            if (config != null && savedConfigCap != null) config.personalization_max_words = savedConfigCap
            vocabulary.clearAll()
            vocabulary.importFromJson(savedVocabulary)
            vocabulary.flush()
        }
    }

    private companion object {
        const val CAP_KEY = "personalization_max_words"
        /** Above the default cap (5,000) and under the restored one, like the 7,165 on the S25U. */
        const val RESTORED_WORDS = 7_000
        const val RESTORED_CAP = 10_000
    }
}
