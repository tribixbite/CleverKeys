package tribixbite.cleverkeys

import android.content.SharedPreferences
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.backup.PrefValue
import tribixbite.cleverkeys.backup.SETTINGS_DEFAULTS

/**
 * Device test 2026-11-23 (Pixel 8 Pro, release 4112648a): applying a backup import crashed the
 * app, Settings and the keyboard together (they share one process). The import writes every value
 * with the import table's type ([SETTINGS_DEFAULTS]; `custom_border_line_width` is a Float there,
 * while its slider writes an Int), and the Settings screen's change handler, which runs for each
 * key the import changes while Settings is open, read that key with a raw `getInt`:
 * `ClassCastException: java.lang.Float cannot be cast to java.lang.Integer` at
 * `SettingsActivity.onSharedPreferenceChanged`.
 *
 * This writes every settings key the way an import does, with Settings open, and checks the screen
 * survives and shows the imported value. The preferences are restored exactly afterwards.
 */
@RunWith(AndroidJUnit4::class)
class SettingsImportTypesInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs: SharedPreferences = DirectBootAwarePreferences.get_shared_preferences(context)
    private lateinit var saved: Map<String, Any?>

    @Before
    fun snapshot() {
        saved = HashMap(prefs.all)
    }

    /** Puts back exactly the snapshot: keys added by the test removed, every other value rewritten. */
    @After
    fun restore() {
        val editor = prefs.edit()
        for (key in prefs.all.keys - saved.keys) editor.remove(key)
        for ((key, value) in saved) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
            }
        }
        editor.commit()
    }

    @Test
    fun settingsSurvivesAnImportThatWritesEveryKeyWithItsTableType() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                // Absent first, so the typed write below is a change the screen is told about
                // whatever this device stored before (an import into a fresh or reset install).
                val clearing = prefs.edit()
                for (key in SETTINGS_DEFAULTS.keys) clearing.remove(key)
                clearing.commit()
                val importing = prefs.edit()
                for ((key, value) in SETTINGS_DEFAULTS) {
                    when (value) {
                        is PrefValue.Bool -> importing.putBoolean(key, value.v)
                        is PrefValue.IntV -> importing.putInt(key, value.v)
                        is PrefValue.FloatV -> importing.putFloat(key, value.v)
                        is PrefValue.Str -> importing.putString(key, value.v)
                        is PrefValue.JsonBlob -> importing.putString(key, value.raw)
                        PrefValue.Unset -> Unit
                    }
                }
                // The device case: the S25U backup's border width, 2.0.
                importing.putFloat("custom_border_line_width", 2f)
                importing.commit()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { activity ->
                assertEquals("the slider shows the imported width", 2, activity.customBorderLineWidth)
            }
        }
    }
}
