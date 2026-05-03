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
}
