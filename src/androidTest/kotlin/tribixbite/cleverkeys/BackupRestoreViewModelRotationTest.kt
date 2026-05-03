package tribixbite.cleverkeys

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.backup.*

/**
 * Validates the ViewModel's purpose: state survives [ActivityScenario.recreate]
 * (the rotation simulation). Without [BackupRestoreViewModel] the import-preview
 * plan would be discarded on rotate, forcing the user to re-pick the file.
 *
 * Reflection seam: `viewModels()` generates a private `viewModel$delegate`
 * `Lazy<VM>` field on the activity. We unwrap it to access the resolved VM
 * instance. Brittle by nature — if the Kotlin codegen changes the field name
 * we expose a `@VisibleForTesting` accessor on the activity instead.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreViewModelRotationTest {

    private fun extractViewModel(activity: BackupRestoreActivity): BackupRestoreViewModel {
        val vmField = activity.javaClass.getDeclaredField("viewModel\$delegate")
        vmField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val lazyVm = vmField.get(activity) as Lazy<BackupRestoreViewModel>
        return lazyVm.value
    }

    @Test
    fun previewPlan_survivesActivityRecreation() {
        val scenario = ActivityScenario.launch(BackupRestoreActivity::class.java)
        try {
            val testPlan = SettingsImportPlan(
                sourceVersion = "1.4.0",
                sourceScreen = ScreenMetrics(1080, 2400, 3.0f),
                currentScreen = ScreenMetrics(1080, 2400, 3.0f),
                changes = emptyList(),
                parseSkippedKeys = emptyList(),
                internalRemoves = emptyList(),
                shortSwipeImportSize = 0,
                shortSwipeImportRawJson = null,
            )
            scenario.onActivity { activity ->
                extractViewModel(activity).settingsPreviewPlan = testPlan
            }

            // Trigger recreation (rotation simulation)
            scenario.recreate()

            scenario.onActivity { activity ->
                val plan = extractViewModel(activity).settingsPreviewPlan
                assertNotNull("Plan must survive rotation", plan)
                assertEquals("1.4.0", plan?.sourceVersion)
            }
        } finally {
            scenario.close()
        }
    }
}
