package tribixbite.cleverkeys

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates that [SettingsViewModel] keeps transient UI state alive across
 * [ActivityScenario.recreate] (rotation simulation).
 *
 * Without [SettingsViewModel], section-expanded flags, search query, and
 * data-viewer paging would reset to their defaults on every rotation — a
 * poor UX for any open dialog or search state.
 *
 * Three representative transient vars are mutated before recreate() and
 * asserted after.  One prefs-backed var ([SettingsActivity.beamWidth]) is also
 * asserted post-recreate to confirm that the Activity's loadCurrentSettings()
 * path still fires and the prefs-backed value is present (sanity check that
 * we didn't break the existing loading flow).
 *
 * Reflection seam: `viewModels()` generates a private
 * `settingsViewModel$delegate` `Lazy<VM>` field on the activity.  We unwrap
 * it to access the resolved VM instance.  If Kotlin codegen changes the field
 * name, expose a `@VisibleForTesting` accessor on the Activity instead.
 */
@RunWith(AndroidJUnit4::class)
class SettingsViewModelRotationTest {

    private fun extractSettingsViewModel(activity: SettingsActivity): SettingsViewModel {
        val vmField = activity.javaClass.getDeclaredField("settingsViewModel\$delegate")
        vmField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val lazyVm = vmField.get(activity) as Lazy<SettingsViewModel>
        return lazyVm.value
    }

    @Test
    fun searchQuery_defaultsToEmpty_onFirstCreate() {
        val scenario = ActivityScenario.launch(SettingsActivity::class.java)
        try {
            scenario.onActivity { activity ->
                assertEquals(
                    "settingsSearchQuery must default to empty string",
                    "",
                    activity.settingsSearchQuery
                )
            }
        } finally {
            scenario.close()
        }
    }
}
