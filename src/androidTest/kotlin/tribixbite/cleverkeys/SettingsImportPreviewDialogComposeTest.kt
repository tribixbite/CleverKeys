package tribixbite.cleverkeys

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.backup.*

@RunWith(AndroidJUnit4::class)
class SettingsImportPreviewDialogComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleScreen = ScreenMetrics(1080, 2400, 3.0f)

    private fun planWith(vararg changes: SettingsChange, ssSize: Int = 0, ssRaw: String? = null) =
        SettingsImportPlan(
            sourceVersion = "1.4.0",
            sourceScreen = sampleScreen,
            currentScreen = sampleScreen,
            changes = changes.toList(),
            parseSkippedKeys = emptyList(),
            internalRemoves = emptyList(),
            shortSwipeImportSize = ssSize,
            shortSwipeImportRawJson = ssRaw,
        )

    @Test
    fun cancel_doesNotInvokeOnApply() {
        var applyCalled = false
        var cancelCalled = false
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    SettingsImportPreviewDialog(
                        plan = planWith(SettingsChange("k", PrefValue.Unset, PrefValue.IntV(1), ChangeType.ADDED)),
                        onCancel = { cancelCalled = true },
                        onApply = { _, _ -> applyCalled = true },
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("Cancel preview").performClick()
        assertTrue(cancelCalled)
        assertFalse(applyCalled)
    }

    @Test
    fun apply_passesEmptyExclusionSet_whenNothingDeselected() {
        var captured: Set<String>? = null
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    SettingsImportPreviewDialog(
                        plan = planWith(
                            SettingsChange("a", PrefValue.Unset, PrefValue.IntV(1), ChangeType.ADDED),
                            SettingsChange("b", PrefValue.Unset, PrefValue.IntV(2), ChangeType.ADDED),
                        ),
                        onCancel = {},
                        onApply = { excluded, _ -> captured = excluded },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Apply (2)").performClick()
        assertEquals(emptySet<String>(), captured)
    }

    @Test
    fun applyButton_countDecrementsWhenRowDeselected() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    SettingsImportPreviewDialog(
                        plan = planWith(
                            SettingsChange("a", PrefValue.Unset, PrefValue.IntV(1), ChangeType.ADDED),
                            SettingsChange("b", PrefValue.Unset, PrefValue.IntV(2), ChangeType.ADDED),
                        ),
                        onCancel = {},
                        onApply = { _, _ -> },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Apply (2)").assertIsDisplayed()
        // Click row "a" checkbox to deselect — node strategy: find by row's
        // key text, then locate the sibling Checkbox via testTag if added.
        // Conservative path: onNodeWithText("a").performClick() — Material's
        // checkbox is hit-tested through the row.
        composeRule.onNodeWithText("a").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Apply (1)").assertIsDisplayed()
    }

    @Test
    fun shortSwipeRadio_defaultsToMerge() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    SettingsImportPreviewDialog(
                        plan = planWith(ssSize = 5, ssRaw = """{"q":{"up":"DEL"}}"""),
                        onCancel = {},
                        onApply = { _, _ -> },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Merge — fill gaps, preserve existing (recommended)")
            .assertIsDisplayed()
        // Default is Merge -> red warning is NOT shown
        composeRule.onNodeWithText("This will REPLACE your", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun shortSwipeReplace_showsRedWarning() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    SettingsImportPreviewDialog(
                        plan = planWith(ssSize = 5, ssRaw = """{"q":{"up":"DEL"}}"""),
                        onCancel = {},
                        onApply = { _, _ -> },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Replace — wipe existing, install file's set").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("This will REPLACE your", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun applyButton_alwaysEnabledEvenAtZeroSelection() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    SettingsImportPreviewDialog(
                        plan = planWith(SettingsChange("a", PrefValue.Unset, PrefValue.IntV(1), ChangeType.ADDED)),
                        onCancel = {},
                        onApply = { _, _ -> },
                    )
                }
            }
        }
        // Deselect the only row
        composeRule.onNodeWithText("a").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Apply (0)").assertIsEnabled()
    }
}
