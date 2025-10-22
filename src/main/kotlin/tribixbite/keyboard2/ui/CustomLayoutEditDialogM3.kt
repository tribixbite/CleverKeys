package tribixbite.keyboard2.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tribixbite.keyboard2.R

/**
 * Material 3 Custom Layout Edit Dialog
 *
 * Features:
 * - Full Material 3 design (Dialog, TextField, Buttons)
 * - Real-time validation with throttled updates
 * - Monospace font for code editing
 * - Material You theming support
 * - Error feedback with Material 3 error states
 * - Accessibility support
 */

/**
 * Show the custom layout edit dialog using Material 3 Compose
 *
 * @param initialText Initial layout description when modifying
 * @param allowRemove Whether to show remove button for existing layouts
 * @param onDismiss Callback when dialog is dismissed
 * @param onValidate Validation function returning error message or null
 * @param onConfirm Callback when user confirms (text parameter)
 * @param onRemove Callback when user removes layout (optional, only if allowRemove=true)
 */
@Composable
fun CustomLayoutEditDialogM3(
    initialText: String = "",
    allowRemove: Boolean = false,
    onDismiss: () -> Unit,
    onValidate: (String) -> String? = { null },
    onConfirm: (String) -> Unit,
    onRemove: (() -> Unit)? = null
) {
    var text by remember { mutableStateOf(initialText) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Throttled validation
    LaunchedEffect(text) {
        scope.launch {
            delay(500) // 500ms throttle
            errorMessage = onValidate(text)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.8f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                // Title
                Text(
                    text = stringResource(R.string.custom_layout_editor_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Editor Field
                LayoutEditorField(
                    text = text,
                    onTextChange = { text = it },
                    errorMessage = errorMessage,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Error display
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    // Remove button (if allowed)
                    if (allowRemove && onRemove != null) {
                        TextButton(
                            onClick = {
                                onRemove()
                                onDismiss()
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.pref_layouts_remove_custom))
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    // Cancel button
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.cancel))
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // OK button
                    FilledTonalButton(
                        onClick = {
                            if (errorMessage == null) {
                                onConfirm(text)
                                onDismiss()
                            }
                        },
                        enabled = errorMessage == null && text.isNotBlank()
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            }
        }
    }
}

/**
 * Layout Editor Text Field with Material 3 styling
 */
@Composable
private fun LayoutEditorField(
    text: String,
    onTextChange: (String) -> Unit,
    @Suppress("UNUSED_PARAMETER") errorMessage: String?, // Used by parent for error display
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // Hint text
            if (text.isEmpty()) {
                Text(
                    text = stringResource(R.string.custom_layout_editor_hint),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(4.dp)
                )
            }

            // Text editor
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxSize(),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text
                )
            )
        }
    }
}

/**
 * Validation functions for layout text
 *
 * Note: These functions return hardcoded error messages for use outside of Compose context.
 * For Compose context with string resources, use extension functions below.
 */
object LayoutValidatorsM3 {

    /**
     * Basic validation for layout format
     */
    fun validateBasicFormat(context: android.content.Context, text: String): String? {
        if (text.isBlank()) {
            return context.getString(R.string.custom_layout_error_empty)
        }

        val lines = text.trim().split('\n')
        if (lines.isEmpty()) {
            return context.getString(R.string.custom_layout_error_no_rows)
        }

        // Check for extremely long lines
        lines.forEach { line ->
            if (line.length > 100) {
                return context.getString(R.string.custom_layout_error_line_too_long)
            }
        }

        return null
    }

    /**
     * Advanced validation for keyboard layout structure
     */
    fun validateKeyboardStructure(context: android.content.Context, text: String): String? {
        val basicError = validateBasicFormat(context, text)
        if (basicError != null) return basicError

        val lines = text.trim().split('\n')

        // Check for reasonable number of rows
        if (lines.size > 10) {
            return context.getString(R.string.custom_layout_error_too_many_rows)
        }

        // Check each line for valid key format
        lines.forEachIndexed { index, line ->
            val keys = line.trim().split(Regex("\\s+"))

            if (keys.size > 20) {
                return context.getString(R.string.custom_layout_error_too_many_keys, index + 1)
            }

            keys.forEach { key ->
                if (key.isNotEmpty() && key.any { it.isWhitespace() }) {
                    return context.getString(R.string.custom_layout_error_invalid_key, index + 1, key)
                }
            }
        }

        return null
    }

    /**
     * Validation with character restrictions
     */
    fun validateWithCharacterRestrictions(context: android.content.Context, text: String): String? {
        val structureError = validateKeyboardStructure(context, text)
        if (structureError != null) return structureError

        // Check for invalid characters
        val invalidChars = text.filter {
            !it.isLetterOrDigit() &&
            !it.isWhitespace() &&
            it !in ".,;:!?\"'()[]{}+-*/=<>@#$%^&|~`"
        }

        if (invalidChars.isNotEmpty()) {
            val charList = invalidChars.toSet().joinToString(", ")
            return context.getString(R.string.custom_layout_error_invalid_chars, charList)
        }

        return null
    }
}

/**
 * Composable helper for validation with automatic context
 */
@Composable
fun rememberLayoutValidator(): (String) -> String? {
    val context = LocalContext.current
    return remember(context) {
        { text: String -> LayoutValidatorsM3.validateKeyboardStructure(context, text) }
    }
}

/**
 * Preview function for development
 */
@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun PreviewCustomLayoutEditDialog() {
    val context = LocalContext.current
    MaterialTheme {
        CustomLayoutEditDialogM3(
            initialText = "q w e r t y\na s d f g h\nz x c v b n",
            allowRemove = true,
            onDismiss = {},
            onValidate = { text -> LayoutValidatorsM3.validateKeyboardStructure(context, text) },
            onConfirm = {},
            onRemove = {}
        )
    }
}
