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
 * End-to-end coverage of the public `BackupRestoreManager.importConfig(uri, prefs)`
 * delegator chain: build plan -> apply with REPLACE for short-swipe (the
 * Termux automation contract — see docstring on `importConfig`). Uses a real
 * Context, a real `SharedPreferences` instance per-test, and a real `file://`
 * URI to a tmp JSON file. No mocking — exercises the actual SAF-less IO path.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreManagerImportConfigEndToEndTest {

    private lateinit var context: Context
    private lateinit var manager: BackupRestoreManager
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = BackupRestoreManager(context)
        tmpDir = context.cacheDir.resolve("backup-test").apply { deleteRecursively(); mkdirs() }
    }

    private fun writeImportFile(json: String): android.net.Uri {
        val f = File(tmpDir, "import.json")
        f.writeText(json)
        return android.net.Uri.fromFile(f)
    }

    @Test
    fun importConfig_appliesPreferences_andReturnsCount() {
        val prefs = context.getSharedPreferences("import_test_prefs_1", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        // 2026-05-20: must use values DIFFERENT from the compile-time defaults
        // — `SettingsImportPlanBuilder` suppresses no-op rows where proposed
        // equals default. Defaults: keyboard_height=27, swipe_typing_enabled=true.
        val json = """{
            "metadata":{"app_version":"1.4.0"},
            "preferences":{"keyboard_height":50,"swipe_typing_enabled":false}
        }"""
        val uri = writeImportFile(json)

        val result = manager.importConfig(uri, prefs)

        // Both keys imported; nothing skipped.
        assertEquals(2, result.importedCount)
        assertEquals(0, result.skippedCount)
        assertEquals(0, result.excludedByUserCount)
        // Values land in prefs.
        assertEquals(50, prefs.getInt("keyboard_height", 0))
        assertFalse(prefs.getBoolean("swipe_typing_enabled", true))
    }

    @Test
    fun importConfig_invalidValue_landsInSkippedCount_validValuesStillApplied() {
        val prefs = context.getSharedPreferences("import_test_prefs_2", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        // 2026-05-20: swipe_typing_enabled=false differs from default (true)
        // so it isn't suppressed by the default-aware filter. keyboard_height
        // 999999 is out of range (10..100) and lands in skipped.
        val json = """{
            "preferences":{"keyboard_height":999999,"swipe_typing_enabled":false}
        }"""
        val uri = writeImportFile(json)

        val result = manager.importConfig(uri, prefs)

        assertEquals(1, result.importedCount)
        assertEquals(1, result.skippedCount)
        assertTrue(result.skippedKeys.contains("keyboard_height"))
        assertFalse(prefs.getBoolean("swipe_typing_enabled", true))
        // Invalid pref didn't land
        assertEquals(0, prefs.getInt("keyboard_height", 0))
    }

    @Test
    fun importConfig_emptyDeltas_returnsZeroCounts_noOp() {
        val prefs = context.getSharedPreferences("import_test_prefs_3", Context.MODE_PRIVATE)
        prefs.edit().clear().putInt("keyboard_height", 50).commit()
        // Same value as already in prefs -> no delta.
        val json = """{"preferences":{"keyboard_height":50}}"""
        val uri = writeImportFile(json)

        val result = manager.importConfig(uri, prefs)

        assertEquals(0, result.importedCount)
        assertEquals(0, result.skippedCount)
        // Value preserved.
        assertEquals(50, prefs.getInt("keyboard_height", 0))
    }
}
