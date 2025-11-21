package tribixbite.keyboard2.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tribixbite.keyboard2.theme.keyboardColors

/**
 * Word Info Dialog - Material 3 dialog showing suggestion details.
 *
 * v2.1 Priority 2 Feature #4: Shows word information when user long-presses
 * a suggestion in the suggestion bar.
 *
 * Features:
 * - Material 3 AlertDialog with proper styling
 * - Word display with large typography
 * - Confidence score (if available)
 * - Source indicator (neural prediction, dictionary, etc.)
 * - Quick actions (insert, dismiss, copy)
 * - Accessibility support
 *
 * Usage:
 * ```kotlin
 * if (showDialog) {
 *     WordInfoDialog(
 *         word = "hello",
 *         confidence = 0.85f,
 *         onDismiss = { showDialog = false },
 *         onInsertWord = { word ->
 *             // Insert word into text field
 *             showDialog = false
 *         }
 *     )
 * }
 * ```
 *
 * @param word The suggestion word to display info for
 * @param confidence Optional confidence score (0.0-1.0), null if unavailable
 * @param source Optional source string (e.g., "Neural Prediction", "Dictionary")
 * @param onDismiss Callback when dialog is dismissed
 * @param onInsertWord Callback when user chooses to insert the word
 *
 * @since v2.1.0
 */
@Composable
fun WordInfoDialog(
    word: String,
    confidence: Float? = null,
    source: String? = null,
    onDismiss: () -> Unit,
    onInsertWord: (String) -> Unit
) {
    val colors = keyboardColors()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Word information",
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Word Information",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Word display with large typography
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = colors.suggestionBackground
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = word,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Medium,
                            color = colors.suggestionText
                        )
                    }
                }

                // Confidence score (if available)
                confidence?.let { conf ->
                    InfoRow(
                        label = "Confidence",
                        value = "${(conf * 100).toInt()}%"
                    )
                }

                // Source indicator
                InfoRow(
                    label = "Source",
                    value = source ?: "Neural Prediction"
                )

                // Word length
                InfoRow(
                    label = "Length",
                    value = "${word.length} characters"
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onInsertWord(word) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Insert Word")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    )
}

/**
 * Info row component for displaying labeled values.
 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}
