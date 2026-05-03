package tribixbite.cleverkeys

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.backup.*

@RunWith(AndroidJUnit4::class)
class DictionaryImportPreviewDialogComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun planWith(vararg langs: Pair<String, LangChanges>) = DictImportPlan(
        sourceVersion = "1.4.0",
        perLanguage = langs.toMap(),
        mergedCustomWordsByLang = langs.associate { (l, ch) -> l to ch.newCustomWords },
        mergedDisabledWordsByLang = langs.associate { (l, ch) -> l to ch.newDisabledWords.toSet() },
    )

    @Test
    fun languageSection_collapsedByDefault() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    DictionaryImportPreviewDialog(
                        plan = planWith("en" to LangChanges(mapOf("foo" to 1), emptyList())),
                        onCancel = {},
                        onApply = { _, _ -> },
                    )
                }
            }
        }
        // Header visible, individual word "foo" should NOT be visible (collapsed)
        composeRule.onNodeWithText("EN").assertIsDisplayed()
        composeRule.onNodeWithText("foo").assertDoesNotExist()
    }

    @Test
    fun expand_revealsCustomWords() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    DictionaryImportPreviewDialog(
                        plan = planWith("en" to LangChanges(mapOf("foo" to 1, "bar" to 2), emptyList())),
                        onCancel = {},
                        onApply = { _, _ -> },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Expand").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("foo").assertIsDisplayed()
        composeRule.onNodeWithText("bar").assertIsDisplayed()
    }

    @Test
    fun headerCheckbox_2state_uncheckRemovesAllInLang() {
        var captured: Set<LangWord>? = null
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    DictionaryImportPreviewDialog(
                        plan = planWith("en" to LangChanges(mapOf("foo" to 1, "bar" to 2), emptyList())),
                        onCancel = {},
                        onApply = { excludedC, _ -> captured = excludedC },
                    )
                }
            }
        }
        // Default — all selected. Tap header checkbox to deselect all.
        composeRule.onNodeWithText("EN").performClick()    // toggles header checkbox row
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Apply (0)").performClick()
        assertEquals(setOf(LangWord("en", "foo"), LangWord("en", "bar")), captured)
    }

    @Test
    fun searchFilter_scopedToSubgroup() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    DictionaryImportPreviewDialog(
                        plan = planWith("en" to LangChanges(mapOf("apple" to 1, "banana" to 2), emptyList())),
                        onCancel = {},
                        onApply = { _, _ -> },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Expand").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Search\u2026", substring = true).performTextInput("app")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("apple").assertIsDisplayed()
        composeRule.onNodeWithText("banana").assertDoesNotExist()
    }

    @Test
    fun perLangWordExclusions_passToOnApply() {
        var capturedC: Set<LangWord>? = null
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    DictionaryImportPreviewDialog(
                        plan = planWith("en" to LangChanges(mapOf("foo" to 1, "bar" to 2), emptyList())),
                        onCancel = {},
                        onApply = { excludedC, _ -> capturedC = excludedC },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Expand").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("foo").performClick()    // deselect foo
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Apply (1)").performClick()
        assertEquals(setOf(LangWord("en", "foo")), capturedC)
    }
}
