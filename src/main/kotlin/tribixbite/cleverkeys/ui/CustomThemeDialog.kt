package tribixbite.cleverkeys.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import tribixbite.cleverkeys.theme.KeyboardColorScheme

/**
 * Dialog for creating a custom keyboard theme.
 *
 * Simplified color picker with predefined palettes.
 * Users select from curated color combinations for easy custom theme creation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomThemeDialog(
    onDismiss: () -> Unit,
    onCreateTheme: (String, KeyboardColorScheme) -> Unit,
    modifier: Modifier = Modifier
) {
    var themeName by remember { mutableStateOf("") }
    var selectedPalette by remember { mutableStateOf(PredefinedPalette.DARK_BLUE) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Header
                Text(
                    text = "Create Custom Theme",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                // Theme name input
                OutlinedTextField(
                    value = themeName,
                    onValueChange = { themeName = it },
                    label = { Text("Theme Name") },
                    placeholder = { Text("My Theme") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Palette selection
                Text(
                    text = "Color Palette",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                PaletteGrid(
                    selectedPalette = selectedPalette,
                    onPaletteSelected = { selectedPalette = it }
                )

                // Preview
                Text(
                    text = "Preview",
                    style = MaterialTheme.typography.titleSmall
                )

                PalettePreviewRow(palette = selectedPalette)

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            if (themeName.isNotBlank()) {
                                val colors = selectedPalette.toKeyboardColorScheme()
                                onCreateTheme(themeName, colors)
                                onDismiss()
                            }
                        },
                        enabled = themeName.isNotBlank()
                    ) {
                        Text("Create")
                    }
                }
            }
        }
    }
}

/**
 * Grid of predefined color palettes.
 */
@Composable
fun PaletteGrid(
    selectedPalette: PredefinedPalette,
    onPaletteSelected: (PredefinedPalette) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val palettes = PredefinedPalette.values().toList()
        palettes.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { palette ->
                    PaletteOption(
                        palette = palette,
                        isSelected = palette == selectedPalette,
                        onClick = { onPaletteSelected(palette) },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Fill empty slots
                repeat(3 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Individual palette option.
 */
@Composable
fun PaletteOption(
    palette: PredefinedPalette,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PalettePreviewRow(palette = palette)
        Text(
            text = palette.displayName,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/**
 * Preview row showing palette colors.
 */
@Composable
fun PalettePreviewRow(
    palette: PredefinedPalette,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        palette.colors.take(5).forEach { color ->
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
            )
        }
    }
}

/**
 * Predefined color palettes for easy custom theme creation.
 */
enum class PredefinedPalette(
    val displayName: String,
    val colors: List<Color>
) {
    DARK_BLUE(
        "Dark Blue",
        listOf(
            Color(0xFF1A2332),
            Color(0xFF2B3A52),
            Color(0xFFB3C4E0),
            Color(0xFF6B8FCC),
            Color(0xFF0F1820)
        )
    ),
    PURPLE(
        "Purple",
        listOf(
            Color(0xFF2A1A3E),
            Color(0xFF4A2B6B),
            Color(0xFFD4AAE6),
            Color(0xFF8A5AAA),
            Color(0xFF1A0F26)
        )
    ),
    TEAL(
        "Teal",
        listOf(
            Color(0xFF1A3E3E),
            Color(0xFF2B6B6B),
            Color(0xFFAAE6E6),
            Color(0xFF5AAAAA),
            Color(0xFF0F2626)
        )
    ),
    BURGUNDY(
        "Burgundy",
            listOf(
            Color(0xFF3E1A1A),
            Color(0xFF6B2B2B),
            Color(0xFFE6AAAA),
            Color(0xFFAA5A5A),
            Color(0xFF260F0F)
        )
    ),
    OLIVE(
        "Olive",
        listOf(
            Color(0xFF3E3E1A),
            Color(0xFF6B6B2B),
            Color(0xFFE6E6AA),
            Color(0xFFAAAA5A),
            Color(0xFF26260F)
        )
    ),
    NAVY(
        "Navy",
        listOf(
            Color(0xFF1A1A3E),
            Color(0xFF2B2B6B),
            Color(0xFFAAAAE6),
            Color(0xFF5A5AAA),
            Color(0xFF0F0F26)
        )
    ),
    EMERALD(
        "Emerald",
        listOf(
            Color(0xFF1A3E1A),
            Color(0xFF2B6B2B),
            Color(0xFFAAE6AA),
            Color(0xFF5AAA5A),
            Color(0xFF0F260F)
        )
    ),
    CHARCOAL(
        "Charcoal",
        listOf(
            Color(0xFF2A2A2A),
            Color(0xFF3A3A3A),
            Color(0xFFEEEEEE),
            Color(0xFF888888),
            Color(0xFF1A1A1A)
        )
    ),
    SLATE(
        "Slate",
        listOf(
            Color(0xFF2A333A),
            Color(0xFF3A4A52),
            Color(0xFFE6F0F5),
            Color(0xFF7799BB),
            Color(0xFF1A2228)
        )
    );

    /**
     * Convert palette to full KeyboardColorScheme.
     */
    fun toKeyboardColorScheme(): KeyboardColorScheme {
        return KeyboardColorScheme(
            keyDefault = colors[0],
            keyActivated = colors[1],
            keyLocked = colors[1].copy(alpha = 0.8f),
            keyModifier = colors[1].copy(alpha = 0.7f),
            keySpecial = colors[1].copy(alpha = 0.9f),

            keyLabel = colors[2],
            keySubLabel = colors[2].copy(alpha = 0.8f),
            keySecondaryLabel = colors[2].copy(alpha = 0.6f),

            keyBorder = colors[1].copy(alpha = 0.5f),
            keyBorderActivated = colors[3],

            swipeTrail = colors[3],
            ripple = colors[3].copy(alpha = 0.4f),

            suggestionText = colors[2],
            suggestionBackground = colors[0],
            suggestionHighConfidence = colors[3],

            keyboardBackground = colors[4],
            keyboardSurface = colors[0]
        )
    }
}
