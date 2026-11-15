package tribixbite.cleverkeys

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dialog-truth tests: verify that confirm/dismiss callbacks fire correctly
 * and that the OPPOSITE callback never fires for the same interaction.
 *
 * These exercise the dialogs in isolation via `createComposeRule().setContent`,
 * which lets us inject mock callbacks and capture exactly which fired. This
 * catches a class of bug that simple "is the dialog displayed?" tests cannot:
 *
 *   - Cancel that secretly applies state
 *   - Apply that fails to deliver the value
 *   - Create button enabled with empty/invalid input
 *
 * Each test wraps the composable in MaterialTheme + Surface to mirror the
 * real activity context (some Material3 widgets crash without a theme).
 */
@RunWith(AndroidJUnit4::class)
class DialogTruthComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // =========================================================================
    // ColorPickerDialog (#93)
    // =========================================================================

    @Test
    fun colorPicker_cancel_doesNotInvokeOnColorSelected() {
        var selectedColor: Color? = null
        var dismissed = false

        composeTestRule.setContent {
            MaterialTheme {
                Surface {
                    ColorPickerDialog(
                        initialColor = Color.Red,
                        attributeName = "Test",
                        onDismiss = { dismissed = true },
                        onColorSelected = { selectedColor = it }
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        assertNull("Cancel must NOT call onColorSelected", selectedColor)
        assertTrue("Cancel must call onDismiss", dismissed)
    }

    @Test
    fun colorPicker_apply_invokesOnColorSelectedWithInitialColor() {
        var selectedColor: Color? = null

        composeTestRule.setContent {
            MaterialTheme {
                Surface {
                    ColorPickerDialog(
                        initialColor = Color.Red,
                        attributeName = "Test",
                        onDismiss = {},
                        onColorSelected = { selectedColor = it }
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Apply").performClick()
        composeTestRule.waitForIdle()

        assertNotNull("Apply must call onColorSelected", selectedColor)
        // Don't compare to Color.Red exactly — Color.hsl() round-trips with FP
        // noise. Verify the picker delivered SOMETHING resembling red:
        // R channel high, G/B low.
        val c = selectedColor!!
        assertTrue(
            "Apply should deliver red-ish color (R=${c.red}, G=${c.green}, B=${c.blue})",
            c.red > 0.8f && c.green < 0.2f && c.blue < 0.2f
        )
    }

    @Test
    fun colorPicker_apply_doesNotImplicitlyDismiss() {
        // ColorPickerDialog calls onColorSelected on Apply but does NOT call
        // onDismiss — the caller is responsible for dismissing. This is
        // deliberate so callers can run code (save preview, etc.) before
        // closing. Lock the contract here so a future "convenience" change
        // doesn't silently break callers.
        var dismissed = false
        var colorSelected = false

        composeTestRule.setContent {
            MaterialTheme {
                Surface {
                    ColorPickerDialog(
                        initialColor = Color.Blue,
                        attributeName = "Test",
                        onDismiss = { dismissed = true },
                        onColorSelected = { colorSelected = true }
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Apply").performClick()
        composeTestRule.waitForIdle()

        assertTrue("Apply fires onColorSelected", colorSelected)
        assertFalse("Apply must NOT auto-fire onDismiss", dismissed)
    }

    @Test
    fun colorPicker_rendersAttributeNameInTitle() {
        composeTestRule.setContent {
            MaterialTheme {
                Surface {
                    ColorPickerDialog(
                        initialColor = Color.Green,
                        attributeName = "Border Color",
                        onDismiss = {},
                        onColorSelected = {}
                    )
                }
            }
        }

        // Title is "Select <attributeName>"
        composeTestRule.onNodeWithText("Select Border Color", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun colorPicker_hueSliderPresent() {
        composeTestRule.setContent {
            MaterialTheme {
                Surface {
                    ColorPickerDialog(
                        initialColor = Color.Yellow,
                        attributeName = "Test",
                        onDismiss = {},
                        onColorSelected = {}
                    )
                }
            }
        }

        // The dialog renders Hue, Saturation, Lightness, Opacity labels.
        composeTestRule.onNodeWithText("Hue").assertIsDisplayed()
        composeTestRule.onNodeWithText("Saturation").assertIsDisplayed()
        composeTestRule.onNodeWithText("Lightness").assertIsDisplayed()
    }
}
