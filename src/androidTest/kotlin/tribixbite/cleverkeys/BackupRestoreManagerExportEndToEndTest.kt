package tribixbite.cleverkeys

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end coverage of the export side of `BackupRestoreManager`:
 *  - `exportConfig` returns the count of non-internal preferences written
 *    to the JSON file (Gap 5)
 *  - `exportDictionaries` returns a populated [BackupRestoreManager.DictionaryExportSummary]
 *    with real customWords/disabledWords/languageCount tallies (Gap 6)
 *
 * Both run against a real Context + real `SharedPreferences` instance + real
 * `file://` URI in `cacheDir`. This is the only path that exercises the
 * filtering of internal-only keys (e.g. "version") from the count.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreManagerExportEndToEndTest {

    private lateinit var context: Context
    private lateinit var manager: BackupRestoreManager
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = BackupRestoreManager(context)
        tmpDir = context.cacheDir.resolve("export-test").apply { deleteRecursively(); mkdirs() }
    }

    @Test
    fun exportConfig_returnsCountReflectingNonInternalKeys() {
        val prefs = context.getSharedPreferences("export_test_prefs_1", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putInt("keyboard_height", 50)
            .putBoolean("swipe_typing_enabled", true)
            .putString("primary_language", "en")
            .putInt("version", 99)  // internal — must NOT count toward returned tally
            .commit()
        val outFile = File(tmpDir, "out.json")

        val count = manager.exportConfig(android.net.Uri.fromFile(outFile), prefs)

        // The returned count is the number of entries in the `preferences` JSON
        // object — defaults + non-internal stored keys. The "version" key is
        // filtered out by `isInternalPreference`, so it must not contribute.
        assertTrue("Count must include user-set keys: was $count", count >= 3)
        // File should exist + contain expected user keys.
        assertTrue(outFile.exists())
        val text = outFile.readText()
        assertTrue(text.contains("keyboard_height"))
        assertTrue(text.contains("swipe_typing_enabled"))
        assertTrue(text.contains("primary_language"))
        // The internal "version" key must not appear inside the `preferences`
        // block. Metadata is allowed to have its own keys (export_date etc.).
        // Walk the JSON to confirm `preferences` does not include "version".
        val root = com.google.gson.JsonParser.parseString(text).asJsonObject
        val preferences = root.getAsJsonObject("preferences")
        assertFalse(
            "internal 'version' key must be filtered from preferences block",
            preferences.has("version")
        )
    }

    @Test
    fun exportDictionaries_returnsSummaryWithRealCounts() {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        // Ensure deterministic state: clear and seed.
        prefs.edit()
            .remove(LanguagePreferenceKeys.customWordsKey("en"))
            .remove(LanguagePreferenceKeys.disabledWordsKey("en"))
            .commit()
        prefs.edit()
            .putString(LanguagePreferenceKeys.customWordsKey("en"), """{"foo":1,"bar":2}""")
            .putStringSet(LanguagePreferenceKeys.disabledWordsKey("en"), setOf("baddie"))
            .commit()
        val outFile = File(tmpDir, "dict-out.json")

        val summary = manager.exportDictionaries(android.net.Uri.fromFile(outFile))

        assertEquals(2, summary.customWordsCount)
        assertEquals(1, summary.disabledWordsCount)
        assertTrue(
            "languageCount must include at least 'en'",
            summary.languageCount >= 1
        )
        // Cleanup so other tests aren't affected.
        prefs.edit()
            .remove(LanguagePreferenceKeys.customWordsKey("en"))
            .remove(LanguagePreferenceKeys.disabledWordsKey("en"))
            .commit()
    }
}
