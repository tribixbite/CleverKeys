package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.backup.ScreenMetrics
import tribixbite.cleverkeys.backup.SettingsImportPlan

/**
 * Activity-level Compose tests for the new SAF preview flow:
 *
 * 1. `import_emptyDeltas_skipsPreviewAndShowsResultDirectly` — when the
 *    plan returned by [BackupRestoreManager.buildSettingsImportPlan] has
 *    no changes, no parse-skipped keys, and no short-swipe import, the
 *    Activity must NOT surface the preview dialog. It should jump
 *    straight to the result dialog with the "No changes" copy.
 *
 * 2. `export_resultDialogShowsCount` — verifies the new
 *    [BackupRestoreManager.exportConfig] `Int` return is rendered into
 *    the success dialog body so the user sees a real number.
 *
 * Both tests inject a hand-rolled [FakeBackupRestoreManager] via
 * [BackupRestoreActivity.testManagerOverride] — NO MockK, NO Robolectric,
 * just a subclass of `open class BackupRestoreManager`. This keeps the
 * androidTest classpath minimal and matches the round-3 review decision.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreActivityImportPreviewTest {

    private val sampleScreen = ScreenMetrics(1080, 2400, 3.0f)

    init {
        // Install the fake BEFORE the @get:Rule fires onCreate. Field
        // initialisers (and therefore `init` blocks) run as part of the
        // test instance construction — earlier than the rule's `apply()`,
        // which is when Compose's createAndroidComposeRule launches the
        // Activity. JUnit4 builds a fresh test instance per test method,
        // so each method sees a fresh override + fresh activity.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        BackupRestoreActivity.testManagerOverride =
            FakeBackupRestoreManager(ctx, sampleScreen, exportCount = 42)
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<BackupRestoreActivity>()

    /**
     * Hand-rolled fake — overrides ONLY the methods this test exercises.
     * BackupRestoreManager must be `open` (Step 5.6.0) for this to compile.
     */
    private class FakeBackupRestoreManager(
        context: Context,
        private val sampleScreen: ScreenMetrics,
        private val exportCount: Int,
    ) : BackupRestoreManager(context) {
        override fun buildSettingsImportPlan(uri: Uri, prefs: SharedPreferences): SettingsImportPlan =
            SettingsImportPlan(
                sourceVersion = "1.4.0",
                sourceScreen = sampleScreen,
                currentScreen = sampleScreen,
                changes = emptyList(),
                parseSkippedKeys = emptyList(),
                internalRemoves = emptyList(),
                shortSwipeImportSize = 0,
                shortSwipeImportRawJson = null,
            )

        override fun exportConfig(uri: Uri, prefs: SharedPreferences): Int = exportCount
    }

    @After
    fun tearDown() {
        BackupRestoreActivity.testManagerOverride = null
    }

    @Test
    fun import_emptyDeltas_skipsPreviewAndShowsResultDirectly() {
        // Drive the import path via reflection so we don't expose performImport
        // publicly. Methods are private but accessible via getDeclaredMethod.
        val activity = composeRule.activity
        val method = activity.javaClass.getDeclaredMethod("performImport", Uri::class.java)
        method.isAccessible = true
        composeRule.runOnUiThread {
            method.invoke(activity, Uri.parse("content://test/empty"))
        }
        composeRule.waitForIdle()
        // Brief wait for the IO coroutine to settle; idling resources would
        // be cleaner but the project doesn't currently configure them.
        Thread.sleep(500)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("No changes").assertIsDisplayed()
        composeRule.onNodeWithText("Backup file matches current settings.", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun export_resultDialogShowsCount() {
        val activity = composeRule.activity
        val method = activity.javaClass.getDeclaredMethod("performExport", Uri::class.java)
        method.isAccessible = true
        composeRule.runOnUiThread {
            method.invoke(activity, Uri.parse("content://test/dest.json"))
        }
        composeRule.waitForIdle()
        Thread.sleep(500)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Settings exported: 42", substring = true).assertIsDisplayed()
    }
}
