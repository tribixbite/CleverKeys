package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Review 2026-11-14 M22: Layout Manager, Theme Manager and the Theme Creator's look editor held
 * open XML edits, in-progress theme edits and pending exports in plain `remember`, and none of
 * the activities declares `configChanges`: a rotation threw the edit away, and an export whose
 * SAF picker returned after a rotation found no pending content and left a 0-byte file.
 *
 * Source scan (Compose state cannot be recreated on the JVM): every listed piece of state must be
 * declared with `rememberSaveable`. The Savers it needs are tested for round trips in
 * `theme.ThemeEditorSaversTest` and `LayoutRowIdsTest`. Not covered: that the Bundle actually
 * survives a real recreation, or the size of a very large XML in it — that needs a device.
 */
class RotationSaveableStateDriftTest {

    private val layoutManager = "src/main/kotlin/tribixbite/cleverkeys/activities/LayoutManagerActivity.kt"
    private val themeSettings = "src/main/kotlin/tribixbite/cleverkeys/activities/ThemeSettingsActivity.kt"
    private val lookEditor = "src/main/kotlin/tribixbite/cleverkeys/activities/ThemeLookEditor.kt"

    private fun assertSaveable(path: String, names: List<String>) {
        val src = SourceScan.read(path)
        val missing = names.filterNot { SourceScan.declaresSaveable(src, it) }
        assertWithMessage("$path: state lost on rotation (declare with rememberSaveable): $missing")
            .that(missing).isEmpty()
    }

    @Test
    fun layoutManager_keepsRowsDialogsXmlAndPendingExportAcrossRotation() = assertSaveable(
        layoutManager,
        listOf(
            "layoutsWithIds", "showAddDialog", "showLanguagePickerFor", "showThemePickerFor",
            "showKeyGeometryFor", "showCustomLayoutDialog", "showDeleteConfirmDialog",
            "showRevertConfirmFor", "xmlText", "pendingExportContent", "pendingExportSkipped",
            "includeTheme",
        ),
    )

    /**
     * Low (review 2026-11-14): the dialogs held a list INDEX, so a list changed under an open
     * dialog (the keyboard creating a "(modified)" copy) made Delete remove another row. They
     * hold the row id now and resolve it when they act.
     */
    @Test
    fun layoutManager_dialogsHoldRowIdsNotIndices() {
        val src = SourceScan.read(layoutManager)
        assertWithMessage("$layoutManager: a dialog selector still holds a list index")
            .that(src.contains("mutableStateOf<Int?>(null)")).isFalse()
        assertWithMessage("$layoutManager: the editor target still holds an index")
            .that(src.contains("Pair<Int, String>")).isFalse()
    }

    @Test
    fun themeManager_keepsDialogsDraftAndPendingExportAcrossRotation() = assertSaveable(
        themeSettings,
        listOf(
            "showCreateDialog", "editingTheme", "pendingThemeXml", "showDeleteConfirm",
            "themeName", "colors", "extension", "selectedColorAttribute",
        ),
    )

    @Test
    fun lookEditor_keepsItsOpenRoleAndModeAcrossRotation() = assertSaveable(lookEditor, listOf("editing", "variant"))
}
