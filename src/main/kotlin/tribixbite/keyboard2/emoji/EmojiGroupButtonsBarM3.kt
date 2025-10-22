package tribixbite.keyboard2.emoji

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Material 3 emoji group selector with ScrollableTabRow
 *
 * Fixes ALL 3 bugs from original EmojiGroupButtonsBar.kt:
 * 1. ✅ Bug #252: Nullable AttributeSet → Compose doesn't need AttributeSet
 * 2. ✅ No Material 3 components → ScrollableTabRow with Material 3 styling
 * 3. ✅ Hardcoded button weights → Flexible ScrollableTabRow layout
 *
 * Features:
 * - ScrollableTabRow for horizontal scrolling
 * - Material 3 tab indicator with animation
 * - Emoji icons for each category
 * - "Recent" tab with clock emoji
 * - Reactive state management with ViewModel
 * - Accessibility support (content descriptions)
 * - Theme integration
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiGroupButtonsBarM3(
    viewModel: EmojiViewModel,
    modifier: Modifier = Modifier
) {
    val selectedGroupIndex by viewModel.selectedGroupIndex.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val scope = rememberCoroutineScope()

    // Build list of tabs: Recent + all emoji groups
    val tabs = buildList {
        // "Recent" tab at index -1
        add(EmojiGroupTab(index = -1, label = "Recent", emoji = "🕙"))

        // Add all emoji groups
        groups.forEachIndexed { index, groupName ->
            val firstEmoji = viewModel.getFirstEmojiOfGroup(index)
            add(
                EmojiGroupTab(
                    index = index,
                    label = groupName.replaceFirstChar { it.uppercase() },
                    emoji = firstEmoji?.emoji ?: "😀"
                )
            )
        }
    }

    // Convert selected group index to tab index (offset by 1 for "Recent" tab)
    val selectedTabIndex = selectedGroupIndex + 1 // -1 becomes 0, 0 becomes 1, etc.

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        ScrollableTabRow(
            selectedTabIndex = selectedTabIndex.coerceIn(0, tabs.size - 1),
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            edgePadding = 8.dp,
            indicator = @Composable { tabPositions ->
                if (selectedTabIndex in tabPositions.indices) {
                    TabRowDefaults.Indicator(
                        Modifier,
                        height = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            divider = {
                Divider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
            }
        ) {
            tabs.forEachIndexed { tabIndex, tab ->
                Tab(
                    selected = selectedTabIndex == tabIndex,
                    onClick = {
                        scope.launch {
                            viewModel.selectGroup(tab.index)
                        }
                    },
                    modifier = Modifier.semantics {
                        contentDescription = tab.label
                    }
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // Emoji icon
                        Text(
                            text = tab.emoji,
                            fontSize = 24.sp,
                            textAlign = TextAlign.Center,
                            color = if (selectedTabIndex == tabIndex) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            }
                        )

                        // Label (optional, can be hidden for compact display)
                        if (tabIndex == 0) { // Show label for "Recent" tab
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                color = if (selectedTabIndex == tabIndex) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Data class for emoji group tab
 */
private data class EmojiGroupTab(
    val index: Int,        // -1 for "Recent", 0+ for emoji groups
    val label: String,     // "Recent", "Smileys", "Animals", etc.
    val emoji: String      // Representative emoji for the group
)

/**
 * Preview composable
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiGroupButtonsBarM3Preview() {
    MaterialTheme {
        Surface {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Text("Emoji Group Buttons Preview")
            }
        }
    }
}
