package tribixbite.cleverkeys

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.preference.PreferenceManager
import tribixbite.cleverkeys.theme.*
import kotlin.math.*

/**
 * Theme Settings Activity for creating and managing keyboard themes.
 *
 * Features:
 * - Browse built-in XML themes (the actual working themes from themes.xml)
 * - Preview themes with mini keyboard display
 * - Create custom themes with colorwheel color picker (future: connect to keyboard)
 */
class ThemeSettingsActivity : ComponentActivity() {

    companion object {
        /**
         * Built-in themes that actually work (defined in res/values/themes.xml)
         * These are the theme IDs that Config.kt's getThemeId() recognizes
         */
        val BUILTIN_THEMES = listOf(
            BuiltinTheme("cleverkeysdark", "CleverKeys Dark", "Deep purple with silver accents (default)", 0xFF1E1030, 0xFF2A1845, 0xFFC0C0C0, 0xFF9B59B6),
            BuiltinTheme("cleverkeyslight", "CleverKeys Light", "Silver keys with purple accents", 0xFFC0C0C0, 0xFFD8D8D8, 0xFF2C3E50, 0xFF9B59B6),
            BuiltinTheme("dark", "Dark", "Classic dark theme with blue accents", 0xFF1B1B1B, 0xFF333333, 0xFFFFFFFF, 0xFF33B5E5),
            BuiltinTheme("light", "Light", "Classic light theme", 0xFFE3E3E3, 0xFFCCCCCC, 0xFF000000, 0xFF33B5E5),
            BuiltinTheme("black", "Black", "Pure black AMOLED theme", 0xFF000000, 0xFF000000, 0xFFEEEEEE, 0xFF009DFF),
            BuiltinTheme("altblack", "Alt Black", "Black with visible borders", 0xFF000000, 0xFF000000, 0xFFEEEEEE, 0xFF009DFF),
            BuiltinTheme("white", "White", "Pure white theme", 0xFFFFFFFF, 0xFFFFFFFF, 0xFF000000, 0xFF0066CC),
            BuiltinTheme("rosepine", "Rosé Pine", "Elegant dark rose theme", 0xFF191724, 0xFF26233A, 0xFFE0DEF4, 0xFFC4A7E7),
            BuiltinTheme("cobalt", "Cobalt", "Deep blue theme", 0xFF000000, 0xFF000000, 0xFF95C9FF, 0xFF78BFFF),
            BuiltinTheme("pine", "Pine", "Forest green theme", 0xFF000000, 0xFF000000, 0xFF91D7A6, 0xFF74CC8A),
            BuiltinTheme("desert", "Desert", "Warm sand colors", 0xFFFFE0B2, 0xFFFFF3E0, 0xFF000000, 0xFFE65100),
            BuiltinTheme("jungle", "Jungle", "Tropical teal theme", 0xFF4DB6AC, 0xFFE0F2F1, 0xFF000000, 0xFF00695C),
            BuiltinTheme("epaper", "ePaper", "High contrast e-ink style", 0xFFFFFFFF, 0xFFFFFFFF, 0xFF000000, 0xFF000000),
            BuiltinTheme("epaperblack", "ePaper Black", "Inverted e-ink style", 0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF),
            BuiltinTheme("everforestlight", "Everforest Light", "Soft green theme", 0xFFF8F5E4, 0xFFE5E2D1, 0xFF5C6A72, 0xFF828812),
            BuiltinTheme("monet", "Monet (Auto)", "Material You colors (follows system)", 0xFF1B1B1B, 0xFF333333, 0xFFFFFFFF, 0xFF9B59B6),
            BuiltinTheme("monetlight", "Monet Light", "Material You light variant", 0xFFE3E3E3, 0xFFCCCCCC, 0xFF000000, 0xFF9B59B6),
            BuiltinTheme("monetdark", "Monet Dark", "Material You dark variant", 0xFF1B1B1B, 0xFF333333, 0xFFFFFFFF, 0xFF9B59B6),
        )
    }

    /**
     * Represents a built-in XML theme with preview colors
     */
    data class BuiltinTheme(
        val id: String,
        val name: String,
        val description: String,
        val backgroundColor: Long,  // For preview
        val keyColor: Long,         // For preview
        val labelColor: Long,       // For preview
        val swipeTrailColor: Long   // For prefs
    )

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Allow activity to show over lock screen for testing/accessibility
        // NOTE: These flags were causing touch events to not reach Compose clickables
        // Commented out - the theme settings don't need to show over lock screen anyway
        /*
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        */

        // CRITICAL: Use DirectBootAwarePreferences to get the SAME SharedPreferences
        // that CleverKeysService is listening to. This ensures when we write "theme",
        // the service's OnSharedPreferenceChangeListener fires immediately.
        prefs = DirectBootAwarePreferences.get_shared_preferences(this)

        setContent {
            // Use a custom dark color scheme with visible surfaces
            val colorScheme = darkColorScheme(
                primary = Color(0xFF9B59B6),       // Purple accent
                onPrimary = Color.White,
                primaryContainer = Color(0xFF4A235A),
                onPrimaryContainer = Color(0xFFE8DAEF),
                secondary = Color(0xFF64B5F6),     // Blue accent
                onSecondary = Color.Black,
                surface = Color(0xFF1E1E1E),       // Dark but visible surface
                onSurface = Color(0xFFE0E0E0),     // Light text
                background = Color(0xFF121212),    // Material dark background
                onBackground = Color(0xFFE0E0E0),
                surfaceVariant = Color(0xFF2C2C2C),
                onSurfaceVariant = Color(0xFFB0B0B0),
                outline = Color(0xFF555555)
            )
            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ThemeSettingsScreen(
                        onBack = { finish() },
                        onThemeSelected = { themeId ->
                            // Find swipe trail color
                            var swipeTrailColor = 0xFF9B59B6.toInt() // Default purple
                            
                            // 1. Check built-in themes
                            val builtin = BUILTIN_THEMES.find { it.id == themeId }
                            if (builtin != null) {
                                swipeTrailColor = builtin.swipeTrailColor.toInt()
                            } else {
                                // 2. Check decorative/custom themes
                                // ThemeProvider isn't easily accessible here as a singleton without context, 
                                // but we can check if it's custom/decorative
                                if (themeId.startsWith("custom_") || themeId.startsWith("decorative_")) {
                                    val themeProvider = ThemeProvider.getInstance(this)
                                    val colorScheme = themeProvider.getColorScheme(themeId)
                                    if (colorScheme != null) {
                                        swipeTrailColor = colorScheme.swipeTrail.toArgb()
                                    }
                                }
                            }

                            // Save selected theme to device protected storage (same prefs the keyboard listens to)
                            // This will immediately trigger the keyboard's OnSharedPreferenceChangeListener
                            prefs.edit()
                                .putString("theme", themeId)
                                .putInt("swipe_trail_color", swipeTrailColor)
                                .commit() // Use commit() for synchronous write

                            // Also save to default preferences for backup/consistency
                            PreferenceManager.getDefaultSharedPreferences(this)
                                .edit()
                                .putString("theme", themeId)
                                .putInt("swipe_trail_color", swipeTrailColor)
                                .commit()

                            // Send broadcast to notify service immediately
                            val intent = Intent(CleverKeysService.ACTION_THEME_CHANGED)
                            intent.setPackage(packageName) // Restrict to our own package
                            sendBroadcast(intent)
                            
                            Toast.makeText(this, "Theme applied: $themeId", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(
    onBack: () -> Unit,
    onThemeSelected: (String) -> Unit
) {
    val context = LocalContext.current
    // Use DirectBootAwarePreferences to ensure we read the same prefs as the service
    val prefs = remember { DirectBootAwarePreferences.get_shared_preferences(context) }
    // Use mutableStringStateOf instead of mutableStateOf to avoid type inference issues
    var currentThemeId by remember { mutableStateOf(prefs.getString("theme", "cleverkeysdark") ?: "cleverkeysdark") }
    val themeManager = remember { CustomThemeManager(context) }
    val customThemes by themeManager.customThemes.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var editingTheme by remember { mutableStateOf<CustomTheme?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Keyboard Themes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Create Theme")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Built-in XML Themes Section (these ACTUALLY work with the keyboard)
            item {
                Text(
                    "Built-in Themes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                Text(
                    "These themes apply directly to the keyboard",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(ThemeSettingsActivity.BUILTIN_THEMES) { builtinTheme ->
                BuiltinThemeCard(
                    theme = builtinTheme,
                    isSelected = currentThemeId == builtinTheme.id,
                    onSelect = {
                        currentThemeId = builtinTheme.id
                        onThemeSelected(builtinTheme.id)
                    }
                )
            }

            // Custom Themes Section (user-created)
            if (customThemes.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Custom Themes",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                items(customThemes) { theme ->
                    val customId = "custom_${theme.id}"
                    ThemeCard(
                        name = theme.name,
                        colorScheme = theme.colors,
                        isCustom = true,
                        isSelected = currentThemeId == customId,
                        onSelect = {
                            currentThemeId = customId
                            onThemeSelected(customId)
                        },
                        onEdit = { editingTheme = theme },
                        onDelete = { showDeleteConfirm = theme.id }
                    )
                }
            }

            // Decorative Themes Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Decorative Themes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                Text(
                    "Designer color schemes for your keyboard",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Predefined Decorative Themes by Category (from PredefinedThemes.kt)
            val predefinedThemes = getAllPredefinedThemes()
            ThemeCategory.entries.filter { it != ThemeCategory.CUSTOM }.forEach { category ->
                val themesInCategory = predefinedThemes[category] ?: emptyList()
                if (themesInCategory.isNotEmpty()) {
                    item {
                        Text(
                            category.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    items(themesInCategory) { themeInfo ->
                        val decorativeId = "decorative_${themeInfo.id}"
                        ThemeCard(
                            name = themeInfo.name,
                            colorScheme = themeInfo.colorScheme,
                            description = themeInfo.description,
                            isCustom = false,
                            isSelected = currentThemeId == decorativeId,
                            onSelect = {
                                currentThemeId = decorativeId
                                onThemeSelected(decorativeId)
                            }
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    // Create Theme Dialog - use current theme's colors as defaults
    if (showCreateDialog) {
        // Get the current theme's color scheme to use as defaults
        val currentColors = remember(currentThemeId) {
            val themeProvider = ThemeProvider.getInstance(context)
            themeProvider.getColorScheme(currentThemeId)
        }

        ThemeCreatorDialog(
            initialTheme = null,
            defaultColors = currentColors,
            onDismiss = { showCreateDialog = false },
            onSave = { theme ->
                themeManager.saveCustomTheme(theme)
                showCreateDialog = false
            }
        )
    }

    // Edit Theme Dialog
    editingTheme?.let { theme ->
        ThemeCreatorDialog(
            initialTheme = theme,
            onDismiss = { editingTheme = null },
            onSave = { updatedTheme ->
                themeManager.saveCustomTheme(updatedTheme)
                editingTheme = null
            }
        )
    }

    // Delete Confirmation
    showDeleteConfirm?.let { themeId ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete Theme") },
            text = { Text("Are you sure you want to delete this custom theme?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        themeManager.deleteCustomTheme(themeId)
                        showDeleteConfirm = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeCard(
    name: String,
    colorScheme: KeyboardColorScheme,
    description: String? = null,
    isCustom: Boolean,
    isSelected: Boolean = false,
    onSelect: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "border"
    )

    Card(
        onClick = { onSelect() },
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, borderColor, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = Color(colorScheme.keyboardBackground.toArgb())
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(colorScheme.keyLabel.toArgb())
                    )
                    description?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(colorScheme.keySubLabel.toArgb())
                        )
                    }
                }

                if (isCustom) {
                    Row {
                        onEdit?.let {
                            IconButton(onClick = it, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Edit",
                                    tint = Color(colorScheme.keyLabel.toArgb()),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        onDelete?.let {
                            IconButton(onClick = it, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = Color(colorScheme.keyLabel.toArgb()),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Theme preview - mini keyboard
            ThemePreview(colorScheme)
        }
    }
}

@Composable
fun ThemePreview(colorScheme: KeyboardColorScheme) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Sample keys
        listOf("Q", "W", "E", "R").forEach { letter ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(colorScheme.keyDefault.toArgb()))
                    .border(
                        1.dp,
                        Color(colorScheme.keyBorder.toArgb()),
                        RoundedCornerShape(4.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    letter,
                    color = Color(colorScheme.keyLabel.toArgb()),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Swipe trail indicator
        Box(
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(colorScheme.swipeTrail.toArgb()).copy(alpha = 0.3f),
                            Color(colorScheme.swipeTrail.toArgb())
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Trail",
                color = Color.White,
                fontSize = 10.sp
            )
        }
    }
}

/**
 * Card for displaying a built-in XML theme with preview colors.
 * These themes directly integrate with Config.kt and the keyboard renderer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuiltinThemeCard(
    theme: ThemeSettingsActivity.BuiltinTheme,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "border"
    )

    Card(
        onClick = { onSelect() },
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, borderColor, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = Color(theme.backgroundColor)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            theme.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(theme.labelColor)
                        )
                        if (isSelected) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Text(
                        theme.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(theme.labelColor).copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Preview mini keyboard with theme colors
            BuiltinThemePreview(theme)
        }
    }
}

/**
 * Mini keyboard preview for built-in XML themes.
 */
@Composable
fun BuiltinThemePreview(theme: ThemeSettingsActivity.BuiltinTheme) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Sample keys
        listOf("Q", "W", "E", "R", "T").forEach { letter ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(theme.keyColor))
                    .border(
                        1.dp,
                        Color(theme.labelColor).copy(alpha = 0.3f),
                        RoundedCornerShape(4.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    letter,
                    color = Color(theme.labelColor),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeCreatorDialog(
    initialTheme: CustomTheme?,
    defaultColors: KeyboardColorScheme? = null,
    onDismiss: () -> Unit,
    onSave: (CustomTheme) -> Unit
) {
    var themeName by remember { mutableStateOf(initialTheme?.name ?: "My Theme") }
    // Use priority: initialTheme colors > defaultColors > darkKeyboardColorScheme
    var colors by remember { mutableStateOf(initialTheme?.colors ?: defaultColors ?: darkKeyboardColorScheme()) }
    var selectedColorAttribute by remember { mutableStateOf<ColorAttribute?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                TopAppBar(
                    title = { Text(if (initialTheme == null) "Create Theme" else "Edit Theme") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                val theme = CustomTheme(
                                    id = initialTheme?.id ?: java.util.UUID.randomUUID().toString(),
                                    name = themeName,
                                    colors = colors
                                )
                                onSave(theme)
                            }
                        ) {
                            Text("Save")
                        }
                    }
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    // Theme Name Input
                    OutlinedTextField(
                        value = themeName,
                        onValueChange = { themeName = it },
                        label = { Text("Theme Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Live Preview
                    Text(
                        "Preview",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ThemePreview(colors)

                    Spacer(modifier = Modifier.height(16.dp))

                    // Color Attributes
                    Text(
                        "Key Colors",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ColorAttributeRow("Key Default", colors.keyDefault, ColorAttribute.KEY_DEFAULT) { selectedColorAttribute = it }
                    ColorAttributeRow("Key Activated", colors.keyActivated, ColorAttribute.KEY_ACTIVATED) { selectedColorAttribute = it }
                    ColorAttributeRow("Key Locked", colors.keyLocked, ColorAttribute.KEY_LOCKED) { selectedColorAttribute = it }
                    ColorAttributeRow("Key Modifier", colors.keyModifier, ColorAttribute.KEY_MODIFIER) { selectedColorAttribute = it }
                    ColorAttributeRow("Key Special", colors.keySpecial, ColorAttribute.KEY_SPECIAL) { selectedColorAttribute = it }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        "Label Colors",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ColorAttributeRow("Key Label", colors.keyLabel, ColorAttribute.KEY_LABEL) { selectedColorAttribute = it }
                    ColorAttributeRow("Sub Label", colors.keySubLabel, ColorAttribute.KEY_SUB_LABEL) { selectedColorAttribute = it }
                    ColorAttributeRow("Secondary Label", colors.keySecondaryLabel, ColorAttribute.KEY_SECONDARY_LABEL) { selectedColorAttribute = it }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        "Border Colors",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ColorAttributeRow("Key Border", colors.keyBorder, ColorAttribute.KEY_BORDER) { selectedColorAttribute = it }
                    ColorAttributeRow("Border Activated", colors.keyBorderActivated, ColorAttribute.KEY_BORDER_ACTIVATED) { selectedColorAttribute = it }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Swipe Trail - prominent section
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "✨ Swipe Trail",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "The color shown while swiping to type",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            ColorAttributeRow("Swipe Trail Color", colors.swipeTrail, ColorAttribute.SWIPE_TRAIL) { selectedColorAttribute = it }
                            ColorAttributeRow("Ripple Effect", colors.ripple, ColorAttribute.RIPPLE) { selectedColorAttribute = it }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        "Suggestion Bar",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ColorAttributeRow("Suggestion Text", colors.suggestionText, ColorAttribute.SUGGESTION_TEXT) { selectedColorAttribute = it }
                    ColorAttributeRow("Suggestion Background", colors.suggestionBackground, ColorAttribute.SUGGESTION_BACKGROUND) { selectedColorAttribute = it }
                    ColorAttributeRow("High Confidence", colors.suggestionHighConfidence, ColorAttribute.SUGGESTION_HIGH_CONFIDENCE) { selectedColorAttribute = it }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        "Background Colors",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ColorAttributeRow("Keyboard Background", colors.keyboardBackground, ColorAttribute.KEYBOARD_BACKGROUND) { selectedColorAttribute = it }
                    ColorAttributeRow("Keyboard Surface", colors.keyboardSurface, ColorAttribute.KEYBOARD_SURFACE) { selectedColorAttribute = it }
                }
            }
        }
    }

    // Color Picker Dialog
    selectedColorAttribute?.let { attr ->
        ColorPickerDialog(
            initialColor = getColorForAttribute(colors, attr),
            attributeName = attr.displayName,
            onDismiss = { selectedColorAttribute = null },
            onColorSelected = { newColor ->
                colors = setColorForAttribute(colors, attr, newColor)
                selectedColorAttribute = null
            }
        )
    }
}

@Composable
fun ColorAttributeRow(
    name: String,
    color: Color,
    attribute: ColorAttribute,
    onEdit: (ColorAttribute) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit(attribute) }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                String.format("#%06X", 0xFFFFFF and color.toArgb()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
        }
    }
}

@Composable
fun ColorPickerDialog(
    initialColor: Color,
    attributeName: String,
    onDismiss: () -> Unit,
    onColorSelected: (Color) -> Unit
) {
    var hue by remember { mutableFloatStateOf(initialColor.toHsl()[0]) }
    var saturation by remember { mutableFloatStateOf(initialColor.toHsl()[1]) }
    var lightness by remember { mutableFloatStateOf(initialColor.toHsl()[2]) }
    var alpha by remember { mutableFloatStateOf(initialColor.alpha) }

    val currentColor by remember(hue, saturation, lightness, alpha) {
        derivedStateOf {
            Color.hsl(hue, saturation, lightness, alpha)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Select $attributeName",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Color Preview
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(currentColor)
                        .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Hue Slider (Rainbow)
                Text("Hue", style = MaterialTheme.typography.labelSmall)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = (0..360 step 30).map { Color.hsl(it.toFloat(), 1f, 0.5f) }
                            )
                        )
                )
                Slider(
                    value = hue,
                    onValueChange = { hue = it },
                    valueRange = 0f..360f,
                    modifier = Modifier.fillMaxWidth()
                )

                // Saturation Slider
                Text("Saturation", style = MaterialTheme.typography.labelSmall)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.hsl(hue, 0f, lightness),
                                    Color.hsl(hue, 1f, lightness)
                                )
                            )
                        )
                )
                Slider(
                    value = saturation,
                    onValueChange = { saturation = it },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )

                // Lightness Slider
                Text("Lightness", style = MaterialTheme.typography.labelSmall)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.hsl(hue, saturation, 0f),
                                    Color.hsl(hue, saturation, 0.5f),
                                    Color.hsl(hue, saturation, 1f)
                                )
                            )
                        )
                )
                Slider(
                    value = lightness,
                    onValueChange = { lightness = it },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )

                // Alpha Slider
                Text("Opacity", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = alpha,
                    onValueChange = { alpha = it },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )

                // Hex value display
                Text(
                    String.format("#%08X", currentColor.toArgb()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Quick color presets
                Text("Quick Colors", style = MaterialTheme.typography.labelSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf(
                        Color.Red, Color.Green, Color.Blue, Color.Yellow,
                        Color.Cyan, Color.Magenta, Color.White, Color.Black
                    ).forEach { preset ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(preset)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable {
                                    val hsl = preset.toHsl()
                                    hue = hsl[0]
                                    saturation = hsl[1]
                                    lightness = hsl[2]
                                    alpha = preset.alpha
                                }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    TextButton(onClick = { onColorSelected(currentColor) }) {
                        Text("Apply")
                    }
                }
            }
        }
    }
}

// Color attribute enumeration
enum class ColorAttribute(val displayName: String) {
    KEY_DEFAULT("Key Default"),
    KEY_ACTIVATED("Key Activated"),
    KEY_LOCKED("Key Locked"),
    KEY_MODIFIER("Key Modifier"),
    KEY_SPECIAL("Key Special"),
    KEY_LABEL("Key Label"),
    KEY_SUB_LABEL("Sub Label"),
    KEY_SECONDARY_LABEL("Secondary Label"),
    KEY_BORDER("Key Border"),
    KEY_BORDER_ACTIVATED("Border Activated"),
    SWIPE_TRAIL("Swipe Trail"),
    RIPPLE("Ripple"),
    SUGGESTION_TEXT("Suggestion Text"),
    SUGGESTION_BACKGROUND("Suggestion Background"),
    SUGGESTION_HIGH_CONFIDENCE("High Confidence"),
    KEYBOARD_BACKGROUND("Keyboard Background"),
    KEYBOARD_SURFACE("Keyboard Surface")
}

// Helper functions
fun getColorForAttribute(scheme: KeyboardColorScheme, attr: ColorAttribute): Color {
    return when (attr) {
        ColorAttribute.KEY_DEFAULT -> scheme.keyDefault
        ColorAttribute.KEY_ACTIVATED -> scheme.keyActivated
        ColorAttribute.KEY_LOCKED -> scheme.keyLocked
        ColorAttribute.KEY_MODIFIER -> scheme.keyModifier
        ColorAttribute.KEY_SPECIAL -> scheme.keySpecial
        ColorAttribute.KEY_LABEL -> scheme.keyLabel
        ColorAttribute.KEY_SUB_LABEL -> scheme.keySubLabel
        ColorAttribute.KEY_SECONDARY_LABEL -> scheme.keySecondaryLabel
        ColorAttribute.KEY_BORDER -> scheme.keyBorder
        ColorAttribute.KEY_BORDER_ACTIVATED -> scheme.keyBorderActivated
        ColorAttribute.SWIPE_TRAIL -> scheme.swipeTrail
        ColorAttribute.RIPPLE -> scheme.ripple
        ColorAttribute.SUGGESTION_TEXT -> scheme.suggestionText
        ColorAttribute.SUGGESTION_BACKGROUND -> scheme.suggestionBackground
        ColorAttribute.SUGGESTION_HIGH_CONFIDENCE -> scheme.suggestionHighConfidence
        ColorAttribute.KEYBOARD_BACKGROUND -> scheme.keyboardBackground
        ColorAttribute.KEYBOARD_SURFACE -> scheme.keyboardSurface
    }
}

fun setColorForAttribute(scheme: KeyboardColorScheme, attr: ColorAttribute, color: Color): KeyboardColorScheme {
    return when (attr) {
        ColorAttribute.KEY_DEFAULT -> scheme.copy(keyDefault = color)
        ColorAttribute.KEY_ACTIVATED -> scheme.copy(keyActivated = color)
        ColorAttribute.KEY_LOCKED -> scheme.copy(keyLocked = color)
        ColorAttribute.KEY_MODIFIER -> scheme.copy(keyModifier = color)
        ColorAttribute.KEY_SPECIAL -> scheme.copy(keySpecial = color)
        ColorAttribute.KEY_LABEL -> scheme.copy(keyLabel = color)
        ColorAttribute.KEY_SUB_LABEL -> scheme.copy(keySubLabel = color)
        ColorAttribute.KEY_SECONDARY_LABEL -> scheme.copy(keySecondaryLabel = color)
        ColorAttribute.KEY_BORDER -> scheme.copy(keyBorder = color)
        ColorAttribute.KEY_BORDER_ACTIVATED -> scheme.copy(keyBorderActivated = color)
        ColorAttribute.SWIPE_TRAIL -> scheme.copy(swipeTrail = color)
        ColorAttribute.RIPPLE -> scheme.copy(ripple = color)
        ColorAttribute.SUGGESTION_TEXT -> scheme.copy(suggestionText = color)
        ColorAttribute.SUGGESTION_BACKGROUND -> scheme.copy(suggestionBackground = color)
        ColorAttribute.SUGGESTION_HIGH_CONFIDENCE -> scheme.copy(suggestionHighConfidence = color)
        ColorAttribute.KEYBOARD_BACKGROUND -> scheme.copy(keyboardBackground = color)
        ColorAttribute.KEYBOARD_SURFACE -> scheme.copy(keyboardSurface = color)
    }
}

// Convert Color to HSL
fun Color.toHsl(): FloatArray {
    val r = red
    val g = green
    val b = blue

    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f

    val h: Float
    val s: Float

    if (max == min) {
        h = 0f
        s = 0f
    } else {
        val d = max - min
        s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f)) * 60f
            g -> ((b - r) / d + 2f) * 60f
            else -> ((r - g) / d + 4f) * 60f
        }
    }

    return floatArrayOf(h, s, l)
}
