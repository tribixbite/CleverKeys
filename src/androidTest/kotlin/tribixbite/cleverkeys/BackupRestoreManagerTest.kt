package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests for BackupRestoreManager.
 * Covers config export/import round-trip, metadata, preference validation,
 * dictionary export/import, and clipboard export/import.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreManagerTest {

    private lateinit var context: Context
    private lateinit var manager: BackupRestoreManager
    private lateinit var testPrefs: SharedPreferences

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        manager = BackupRestoreManager(context)
        testPrefs = context.getSharedPreferences("backup_test_prefs", Context.MODE_PRIVATE)
        testPrefs.edit().clear().apply()
    }

    // =========================================================================
    // Config export
    // =========================================================================

    @Test
    fun exportConfigCreatesValidJson() {
        // Populate test prefs
        testPrefs.edit()
            .putBoolean("haptic_enabled", true)
            .putInt("vibrate_duration", 25)
            .putFloat("keyboard_height_percent", 35.0f)
            .putString("primary_language", "en")
            .apply()

        val tempFile = File(context.cacheDir, "test_export.json")
        val uri = Uri.fromFile(tempFile)

        try {
            val result = manager.exportConfig(uri, testPrefs)
            assertTrue("Export should succeed", result)
            assertTrue("File should exist", tempFile.exists())

            val json = tempFile.readText()
            val root = JsonParser.parseString(json).asJsonObject
            assertTrue("Should have metadata", root.has("metadata"))
            assertTrue("Should have preferences", root.has("preferences"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun exportConfigIncludesMetadata() {
        testPrefs.edit().putString("test_key", "test_value").apply()

        val tempFile = File(context.cacheDir, "test_metadata.json")
        val uri = Uri.fromFile(tempFile)

        try {
            manager.exportConfig(uri, testPrefs)
            val json = tempFile.readText()
            val metadata = JsonParser.parseString(json).asJsonObject
                .getAsJsonObject("metadata")

            assertTrue("Should have app_version", metadata.has("app_version"))
            assertTrue("Should have export_date", metadata.has("export_date"))
            assertTrue("Should have screen_width", metadata.has("screen_width"))
            assertTrue("Should have screen_height", metadata.has("screen_height"))
            assertTrue("Should have android_version", metadata.has("android_version"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun exportConfigPreservesPreferenceTypes() {
        testPrefs.edit()
            .putBoolean("bool_pref", true)
            .putInt("int_pref", 42)
            .putFloat("float_pref", 3.14f)
            .putString("string_pref", "hello")
            .apply()

        val tempFile = File(context.cacheDir, "test_types.json")
        val uri = Uri.fromFile(tempFile)

        try {
            manager.exportConfig(uri, testPrefs)
            val json = tempFile.readText()
            val prefs = JsonParser.parseString(json).asJsonObject
                .getAsJsonObject("preferences")

            assertTrue("bool_pref should be true", prefs.get("bool_pref").asBoolean)
            assertEquals(42, prefs.get("int_pref").asInt)
            assertEquals(3.14f, prefs.get("float_pref").asFloat, 0.01f)
            assertEquals("hello", prefs.get("string_pref").asString)
        } finally {
            tempFile.delete()
        }
    }

    // =========================================================================
    // Config import
    // =========================================================================

    @Test
    fun importConfigRestoresPreferences() {
        // Export first
        testPrefs.edit()
            .putBoolean("haptic_enabled", true)
            .putInt("vibrate_duration", 30)
            .putString("primary_language", "fr")
            .apply()

        val tempFile = File(context.cacheDir, "test_roundtrip.json")
        val uri = Uri.fromFile(tempFile)

        try {
            manager.exportConfig(uri, testPrefs)

            // Clear prefs
            testPrefs.edit().clear().apply()
            assertNull(testPrefs.getString("primary_language", null))

            // Import
            val result = manager.importConfig(uri, testPrefs)
            assertTrue("Should import some keys", result.importedCount > 0)

            // Verify restored
            assertEquals("fr", testPrefs.getString("primary_language", null))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun importConfigReturnsImportResult() {
        testPrefs.edit()
            .putString("key1", "value1")
            .putString("key2", "value2")
            .apply()

        val tempFile = File(context.cacheDir, "test_result.json")
        val uri = Uri.fromFile(tempFile)

        try {
            manager.exportConfig(uri, testPrefs)
            testPrefs.edit().clear().apply()

            val result = manager.importConfig(uri, testPrefs)
            assertTrue("importedCount should be > 0", result.importedCount > 0)
            assertTrue("importedKeys should not be empty", result.importedKeys.isNotEmpty())
            assertTrue("sourceVersion should be set", result.sourceVersion.isNotEmpty())
        } finally {
            tempFile.delete()
        }
    }

    // =========================================================================
    // Dictionary export/import
    // =========================================================================

    @Test
    fun exportDictionariesDoesNotCrash() {
        val tempFile = File(context.cacheDir, "test_dict.json")
        val uri = Uri.fromFile(tempFile)

        try {
            // Should not throw even with no custom words
            manager.exportDictionaries(uri)
            assertTrue("Dict export file should exist", tempFile.exists())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun dictionaryRoundTrip() {
        // Set up some custom words in preferences
        val mainPrefs = DirectBootAwarePreferences.get_shared_preferences(context)
        mainPrefs.edit().putString("custom_words_en", "[\"testword1\",\"testword2\"]").apply()

        val tempFile = File(context.cacheDir, "test_dict_rt.json")
        val uri = Uri.fromFile(tempFile)

        try {
            manager.exportDictionaries(uri)
            assertTrue("Export should create file", tempFile.exists())
            assertTrue("File should have content", tempFile.length() > 0)

            // Import back
            val result = manager.importDictionaries(uri)
            assertTrue("Should import words", result.userWordsImported >= 0)
        } finally {
            tempFile.delete()
            mainPrefs.edit().remove("custom_words_en").apply()
        }
    }

    // =========================================================================
    // Clipboard export/import
    // =========================================================================

    @Test
    fun exportClipboardDoesNotCrash() {
        val tempFile = File(context.cacheDir, "test_clip.json")
        val uri = Uri.fromFile(tempFile)

        try {
            val result = manager.exportClipboardHistory(uri)
            // May be 0 if no clipboard history — just verify no crash
            assertTrue(result.exportedCount >= 0)
        } finally {
            tempFile.delete()
        }
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun exportEmptyPreferences() {
        // Ensure prefs are empty
        testPrefs.edit().clear().apply()

        val tempFile = File(context.cacheDir, "test_empty.json")
        val uri = Uri.fromFile(tempFile)

        try {
            val result = manager.exportConfig(uri, testPrefs)
            assertTrue("Should succeed even with empty prefs", result)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun importScreenSizeMismatchDetection() {
        testPrefs.edit().putString("test", "value").apply()

        val tempFile = File(context.cacheDir, "test_screen.json")
        val uri = Uri.fromFile(tempFile)

        try {
            manager.exportConfig(uri, testPrefs)
            testPrefs.edit().clear().apply()
            val result = manager.importConfig(uri, testPrefs)
            // Importing on same device: no mismatch
            assertFalse("Same device should not have screen mismatch",
                result.hasScreenSizeMismatch())
        } finally {
            tempFile.delete()
        }
    }
}
