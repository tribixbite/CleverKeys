package tribixbite.cleverkeys.ui

import android.content.Context
import android.util.AttributeSet
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Emoji Picker View - Material 3 emoji selection interface
 *
 * Features:
 * - Category-based emoji organization
 * - Search functionality with keyword matching
 * - Recently used emojis tracking
 * - Smooth animations and transitions
 * - Material 3 design language
 *
 * Usage:
 * ```kotlin
 * val emojiPicker = EmojiPickerView(context)
 * emojiPicker.onEmojiSelected = { emoji ->
 *     // Handle emoji selection
 * }
 * ```
 *
 * @since v2.1.0
 */
class EmojiPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr),
    LifecycleOwner, SavedStateRegistryOwner {

    // Lifecycle management (required for Compose in IME)
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    /**
     * Emoji selection callback
     */
    var onEmojiSelected: ((String) -> Unit)? = null

    /**
     * Dismiss callback (swipe back or close button)
     */
    var onDismiss: (() -> Unit)? = null

    // State management
    private val _selectedCategory = MutableStateFlow(EmojiData.Category.SMILEYS)
    private val _searchQuery = MutableStateFlow("")
    private val _recentEmojis = MutableStateFlow<List<String>>(emptyList())

    init {
        // Setup lifecycle and saved state
        setViewTreeLifecycleOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDetachedFromWindow() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDetachedFromWindow()
    }

    @Composable
    override fun Content() {
        MaterialTheme {
            EmojiPickerContent(
                selectedCategory = _selectedCategory.collectAsState().value,
                searchQuery = _searchQuery.collectAsState().value,
                recentEmojis = _recentEmojis.collectAsState().value,
                onCategorySelected = { _selectedCategory.value = it },
                onSearchQueryChanged = { _searchQuery.value = it },
                onEmojiClick = { emoji ->
                    handleEmojiSelected(emoji)
                },
                onDismissClick = {
                    onDismiss?.invoke()
                }
            )
        }
    }

    /**
     * Handle emoji selection with recents tracking
     */
    private fun handleEmojiSelected(emoji: String) {
        // Add to recents (max 30, most recent first)
        val currentRecents = _recentEmojis.value.toMutableList()
        currentRecents.remove(emoji) // Remove if already present
        currentRecents.add(0, emoji) // Add to front
        if (currentRecents.size > 30) {
            currentRecents.removeLast()
        }
        _recentEmojis.value = currentRecents

        // Notify callback
        onEmojiSelected?.invoke(emoji)
    }

    /**
     * Load recent emojis from preferences
     */
    fun loadRecents(recents: List<String>) {
        _recentEmojis.value = recents.take(30)
    }

    /**
     * Get current recent emojis for saving to preferences
     */
    fun getRecents(): List<String> = _recentEmojis.value
}

/**
 * Main emoji picker composable content
 */
@Composable
private fun EmojiPickerContent(
    selectedCategory: EmojiData.Category,
    searchQuery: String,
    recentEmojis: List<String>,
    onCategorySelected: (EmojiData.Category) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onEmojiClick: (String) -> Unit,
    onDismissClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Header with search and close button
        EmojiPickerHeader(
            searchQuery = searchQuery,
            onSearchQueryChanged = onSearchQueryChanged,
            onDismissClick = onDismissClick
        )

        // Category tabs
        if (searchQuery.isEmpty()) {
            CategoryTabs(
                selectedCategory = selectedCategory,
                onCategorySelected = onCategorySelected,
                hasRecents = recentEmojis.isNotEmpty()
            )
        }

        // Emoji grid
        EmojiGrid(
            selectedCategory = selectedCategory,
            searchQuery = searchQuery,
            recentEmojis = recentEmojis,
            onEmojiClick = onEmojiClick
        )
    }
}

/**
 * Header with search bar and close button
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiPickerHeader(
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    onDismissClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Search field
            TextField(
                value = searchQuery,
                onValueChange = onSearchQueryChanged,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                placeholder = { Text("Search emojis...") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(24.dp)
            )

            // Close button
            IconButton(
                onClick = onDismissClick,
                modifier = Modifier.size(48.dp)
            ) {
                Text(
                    text = "✕",
                    fontSize = 24.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * Category tabs with horizontal scroll
 */
@Composable
private fun CategoryTabs(
    selectedCategory: EmojiData.Category,
    onCategorySelected: (EmojiData.Category) -> Unit,
    hasRecents: Boolean
) {
    ScrollableTabRow(
        selectedTabIndex = selectedCategory.ordinal,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        edgePadding = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        EmojiData.Category.values().forEach { category ->
            // Skip recent if empty
            if (category == EmojiData.Category.RECENT && !hasRecents) {
                return@forEach
            }

            Tab(
                selected = selectedCategory == category,
                onClick = { onCategorySelected(category) },
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(8.dp)
                ) {
                    // Category icon
                    Text(
                        text = category.icon,
                        fontSize = 24.sp
                    )
                    // Category name (small text)
                    Text(
                        text = category.displayName.split(" ")[0], // First word only
                        fontSize = 10.sp,
                        fontWeight = if (selectedCategory == category) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Emoji grid with lazy loading
 */
@Composable
private fun EmojiGrid(
    selectedCategory: EmojiData.Category,
    searchQuery: String,
    recentEmojis: List<String>,
    onEmojiClick: (String) -> Unit
) {
    val emojis = remember(selectedCategory, searchQuery, recentEmojis) {
        when {
            // Search mode
            searchQuery.isNotEmpty() -> {
                EmojiData.searchEmojis(searchQuery)
            }
            // Recent category
            selectedCategory == EmojiData.Category.RECENT -> {
                recentEmojis.map { emoji ->
                    EmojiData.Emoji(emoji, "", emptyList())
                }
            }
            // Normal category
            else -> {
                EmojiData.getEmojisForCategory(selectedCategory)
            }
        }
    }

    if (emojis.isEmpty()) {
        // Empty state
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (searchQuery.isNotEmpty()) "🔍" else "😢",
                    fontSize = 48.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (searchQuery.isNotEmpty())
                        "No emojis found for \"$searchQuery\""
                    else
                        "No recent emojis yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        // Emoji grid (8 columns)
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp)
        ) {
            items(emojis.size) { index ->
                val emoji = emojis[index]
                EmojiItem(
                    emoji = emoji.char,
                    onClick = { onEmojiClick(emoji.char) }
                )
            }
        }
    }
}

/**
 * Individual emoji item in the grid
 */
@Composable
private fun EmojiItem(
    emoji: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(4.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            fontSize = 28.sp,
            textAlign = TextAlign.Center
        )
    }
}
