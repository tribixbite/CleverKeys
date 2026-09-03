package tribixbite.cleverkeys

import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.ui.settings.io.exportSwipeDataJSON
import tribixbite.cleverkeys.ui.settings.io.exportSwipeDataNDJSON

/**
 * Pins v1.1.71's "SAF file picker for swipe data export (saves anywhere)".
 *
 * The promise has two halves. The **picker** half is that the export goes through the
 * Storage Access Framework — the user chooses the destination, so the file lands wherever
 * they want and no storage permission is involved; that is pinned by
 * [exportRoutesThroughTheStorageAccessFramework], which also pins the manifest's silence on
 * storage permissions (SAF is what makes that possible). The **hand-off** half is that the
 * menu action actually opens that picker, pre-named per format, and cannot take the settings
 * screen down when no document provider answers — [exportJsonLaunchesTheJsonPicker],
 * [exportNdjsonLaunchesTheNdjsonPicker], [exportSurvivesADeviceWithNoDocumentProvider].
 *
 * Mock tier: `SettingsActivity` and `Toast` are Android types. Run with
 * `scripts/gradle-guard.sh runMockTests -PtestClass=SwipeDataExportSafTest`.
 */
class SwipeDataExportSafTest {

    private lateinit var activity: SettingsActivity
    private lateinit var jsonLauncher: ActivityResultLauncher<String>
    private lateinit var ndjsonLauncher: ActivityResultLauncher<String>

    @Before
    fun setUp() {
        mockkStatic(Toast::class)
        every { Toast.makeText(any(), any<CharSequence>(), any()) } returns mockk(relaxed = true)

        jsonLauncher = mockk(relaxed = true)
        ndjsonLauncher = mockk(relaxed = true)
        activity = mockk(relaxed = true)
        every { activity.swipeDataJsonExportLauncher } returns jsonLauncher
        every { activity.swipeDataNdjsonExportLauncher } returns ndjsonLauncher
    }

    @After
    fun tearDown() {
        unmockkStatic(Toast::class)
    }

    @Test
    fun exportJsonLaunchesTheJsonPicker() {
        val name = slot<String>()
        every { jsonLauncher.launch(capture(name)) } returns Unit

        activity.exportSwipeDataJSON()

        verify(exactly = 1) { jsonLauncher.launch(any()) }
        verify(exactly = 0) { ndjsonLauncher.launch(any()) }
        assertWithMessage("the picker must open pre-named 'swipe_data_<yyyyMMdd_HHmmss>.json'")
            .that(name.captured).matches("""swipe_data_\d{8}_\d{6}\.json""")
    }

    @Test
    fun exportNdjsonLaunchesTheNdjsonPicker() {
        val name = slot<String>()
        every { ndjsonLauncher.launch(capture(name)) } returns Unit

        activity.exportSwipeDataNDJSON()

        verify(exactly = 1) { ndjsonLauncher.launch(any()) }
        // Each format has its OWN launcher because CreateDocument fixes the MIME type at
        // registration; crossing them would save NDJSON under an application/json document.
        verify(exactly = 0) { jsonLauncher.launch(any()) }
        assertWithMessage("the picker must open pre-named 'swipe_data_<yyyyMMdd_HHmmss>.ndjson'")
            .that(name.captured).matches("""swipe_data_\d{8}_\d{6}\.ndjson""")
    }

    @Test
    fun exportSurvivesADeviceWithNoDocumentProvider() {
        // The real failure on a provider-less ROM is ActivityNotFoundException, but its
        // android.jar constructor is a stub that throws "Stub!" when instantiated off-device.
        // The handler catches `Exception`, so any RuntimeException exercises the same branch.
        every { jsonLauncher.launch(any()) } throws RuntimeException("no activity found to handle intent")

        // Must not propagate: this runs on a settings-screen click handler.
        activity.exportSwipeDataJSON()

        verify(exactly = 1) { Toast.makeText(any(), any<CharSequence>(), any()) }
    }

    @Test
    fun exportRoutesThroughTheStorageAccessFramework() {
        val settings = File("src/main/kotlin/tribixbite/cleverkeys/activities/SettingsActivity.kt")
        check(settings.isFile) { "${settings.path} not found — run with the project root as CWD." }
        val text = settings.readText()

        // CreateDocument == "the user picks the destination". A hardcoded
        // getExternalFilesDir()/Downloads path would break the "saves anywhere" promise.
        assertWithMessage("the JSON export launcher must be a SAF CreateDocument contract")
            .that(text).contains("ActivityResultContracts.CreateDocument(\"application/json\")")
        assertWithMessage("the NDJSON export launcher must be a SAF CreateDocument contract")
            .that(text).contains("ActivityResultContracts.CreateDocument(\"application/x-ndjson\")")

        // SAF is also what keeps the app permission-free: a document URI carries its own
        // grant. Re-assert here so a regression to direct file IO is caught as the
        // permission request it would have to become.
        val manifest = File("AndroidManifest.xml").readText()
        for (permission in listOf(
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
        )) {
            assertWithMessage("SAF export means $permission must never be requested")
                .that(manifest).doesNotContain("<uses-permission android:name=\"$permission\"")
        }
    }
}
