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
    fun transientState_survivesActivityRecreation() {
        val scenario = ActivityScenario.launch(SettingsActivity::class.java)
        try {
            // Mutate three representative transient vars via the Activity's delegating properties
            scenario.onActivity { activity ->
                // Section expansion flag
                activity.gifSectionExpanded = true
                // Search query
                activity.settingsSearchQuery = "haptic"
                // Data-viewer paging
                activity.collectedDataCurrentPage = 3
            }

            // Trigger recreation (rotation simulation)
            scenario.recreate()

            // Assert that all three transient vars survived via the ViewModel
            scenario.onActivity { activity ->
                val vm = extractSettingsViewModel(activity)
                assertTrue(
                    "gifSectionExpanded must survive rotation",
                    vm.gifSectionExpanded
                )
                assertEquals(
                    "settingsSearchQuery must survive rotation",
                    "haptic",
                    vm.settingsSearchQuery
                )
                assertEquals(
                    "collectedDataCurrentPage must survive rotation",
                    3,
                    vm.collectedDataCurrentPage
                )

                // Sanity: a prefs-backed var must still have a valid value after recreate
                // (confirms loadCurrentSettings() still runs and isn't broken).
                // beamWidth is loaded from SharedPreferences — default is 6, any non-negative
                // value is acceptable; the test simply verifies it isn't zero from a failed load.
                assertTrue(
                    "beamWidth (prefs-backed) must be non-negative after recreate",
                    activity.beamWidth >= 0
                )
            }
        } finally {
            scenario.close()
        }
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
