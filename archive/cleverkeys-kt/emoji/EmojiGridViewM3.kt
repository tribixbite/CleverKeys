package tribixbite.cleverkeys.emoji

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tribixbite.cleverkeys.Emoji
import tribixbite.cleverkeys.R

/**
 * Material 3 emoji grid with LazyVerticalGrid
 *
 * Fixes ALL 8 bugs from original EmojiGridView.kt:
 * 1. ✅ Bug #244: Wrong base class GridLayout → LazyVerticalGrid (Compose)
 * 2. ✅ Bug #245: No adapter pattern → Reactive state management
 * 3. ✅ Hardcoded colors → Material 3 theme integration
 * 4. ✅ No Material 3 → Full Material 3 Surface/ripples
 * 5. ✅ No animations → animateItemPlacement with spring physics
 * 6. ✅ No accessibility → Content descriptions for all emojis
 * 7. ✅ Poor touch feedback → Material ripple effects
 * 8. ✅ Inefficient rendering → LazyVerticalGrid lazy loading
 *
 * Features:
 * - Efficient lazy loading with LazyVerticalGrid
 * - Material 3 theming with semantic colors
 * - Spring-based item animations
 * - Accessibility support (content descriptions)
 * - Loading/error/empty states
 * - Material ripple effects
 * - 48dp minimum touch targets
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EmojiGridViewM3(
    viewModel: EmojiViewModel,
    onEmojiSelected: (Emoji.EmojiData) -> Unit,
    modifier: Modifier = Modifier
) {
    val emojis by viewModel.emojis.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when {
            // Loading state
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.emoji_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Error state
            errorMessage != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "⚠️",
                            fontSize = 48.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = errorMessage ?: stringResource(R.string.emoji_error_unknown),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        TextButton(
                            onClick = { viewModel.clearError() }
                        ) {
                            Text(stringResource(R.string.emoji_error_dismiss))
                        }
                    }
                }
            }

            // Empty state
            emojis.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "🔍",
                            fontSize = 48.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Text(
                            text = stringResource(R.string.emoji_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Emoji grid
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(8), // 8 columns for emoji grid
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(
                        items = emojis,
                        key = { emoji -> emoji.emoji }
                    ) { emoji ->
                        EmojiCell(
                            emoji = emoji,
                            onClick = {
                                viewModel.onEmojiSelected(emoji)
                                onEmojiSelected(emoji)
                            },
                            modifier = Modifier.animateItemPlacement(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual emoji cell with Material 3 styling
 */
@Composable
private fun EmojiCell(
    emoji: Emoji.EmojiData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .size(48.dp) // Minimum 48dp touch target (accessibility)
            .semantics {
                contentDescription = "${emoji.emoji} - ${emoji.description}"
            },
        color = Color.Transparent,
        onClick = onClick
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emoji.emoji,
                fontSize = 32.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Emoji grid preview composables
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiGridViewM3Preview() {
    MaterialTheme {
        Surface {
            // Preview would require Context for ViewModel
            // This is a placeholder structure
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Text("Emoji Grid Preview")
            }
        }
    }
}
