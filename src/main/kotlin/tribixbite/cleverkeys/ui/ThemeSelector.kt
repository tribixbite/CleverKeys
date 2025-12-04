package tribixbite.cleverkeys.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tribixbite.cleverkeys.theme.*

/**
 * Main theme selector UI with categories and theme previews.
 *
 * Features:
 * - Categorized theme browsing (Gemstone, Neon, Pastel, Nature, Utilitarian, Modern, Custom)
 * - Theme preview cards showing color palette
 * - Selected theme highlight
 * - Custom theme creation
 * - Theme delete/export for custom themes
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSelector(
    modifier: Modifier = Modifier,
    onThemeSelected: (String) -> Unit = {},
    onCreateCustom: () -> Unit = {},
    onDeleteTheme: (String) -> Unit = {},
    onExportTheme: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val themeManager = remember { MaterialThemeManager(context) }
    val customThemeManager = remember { themeManager.getCustomThemeManager() }

    val selectedThemeId by themeManager.selectedThemeId.collectAsState()
    val allThemes = remember { themeManager.getAllThemes() }

    var selectedCategory by remember { mutableStateOf(ThemeCategory.GEMSTONE) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header with title and create button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Keyboard Themes",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            // Create custom theme button
            FilledTonalButton(
                onClick = onCreateCustom,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create")
                Spacer(Modifier.width(8.dp))
                Text("Custom")
            }
        }

        // Category tabs
        CategoryTabs(
            categories = allThemes.keys.toList(),
            selectedCategory = selectedCategory,
            onCategorySelected = { selectedCategory = it }
        )

        // Theme grid for selected category
        val themesInCategory = allThemes[selectedCategory] ?: emptyList()

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(themesInCategory.chunked(2)) { rowThemes ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowThemes.forEach { theme ->
                        ThemePreviewCard(
                            theme = theme,
                            isSelected = theme.id == selectedThemeId,
                            modifier = Modifier.weight(1f),
                            onClick = { onThemeSelected(theme.id) },
                            onDelete = if (theme.isDeletable) {
                                { onDeleteTheme(theme.id) }
                            } else null,
                            onExport = if (theme.isExportable) {
                                { onExportTheme(theme.id) }
                            } else null
                        )
                    }

                    // Fill empty space if odd number of themes
                    if (rowThemes.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            // Bottom padding
            item {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Category tabs for theme browsing.
 */
@Composable
fun CategoryTabs(
    categories: List<ThemeCategory>,
    selectedCategory: ThemeCategory,
    onCategorySelected: (ThemeCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(categories) { category ->
            CategoryChip(
                category = category,
                isSelected = category == selectedCategory,
                onClick = { onCategorySelected(category) }
            )
        }
    }
}

/**
 * Individual category chip.
 */
@Composable
fun CategoryChip(
    category: ThemeCategory,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    val textColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = modifier
            .clickable(onClick = onClick)
            .height(36.dp),
        shape = RoundedCornerShape(18.dp),
        color = backgroundColor,
        tonalElevation = if (isSelected) 0.dp else 1.dp
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = category.displayName,
                style = MaterialTheme.typography.labelLarge,
                color = textColor,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

/**
 * Theme preview card showing colors and name.
 */
@Composable
fun ThemePreviewCard(
    theme: ThemeInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }

    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .height(140.dp)
            .border(2.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // Header with name and menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = theme.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = theme.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                // Menu button for custom themes
                if (onDelete != null || onExport != null) {
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "More options",
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            onExport?.let { export ->
                                DropdownMenuItem(
                                    text = { Text("Export") },
                                    onClick = {
                                        export()
                                        showMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Share, contentDescription = null)
                                    }
                                )
                            }

                            onDelete?.let { delete ->
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = {
                                        delete()
                                        showMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Delete, contentDescription = null)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Color palette preview
            ColorPalettePreview(
                colors = theme.colorScheme,
                modifier = Modifier.fillMaxWidth()
            )

            // Selection indicator
            if (isSelected) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Color palette preview showing key colors.
 */
@Composable
fun ColorPalettePreview(
    colors: KeyboardColorScheme,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Show key representative colors
        ColorCircle(colors.keyDefault)
        ColorCircle(colors.keyActivated)
        ColorCircle(colors.keyLabel)
        ColorCircle(colors.swipeTrail)
        ColorCircle(colors.keyboardBackground)
    }
}

/**
 * Small color circle for palette preview.
 */
@Composable
fun ColorCircle(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
    )
}
