package tribixbite.cleverkeys

import android.content.SharedPreferences
import android.os.Bundle
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
 * - Browse predefined themes (18 themes across 6 categories)
 * - Create custom themes with colorwheel color picker
 * - Edit/delete custom themes
 * - Live preview of keyboard appearance
 * - Special swipe trail color picker
 * - Export/import themes as JSON
 */
class ThemeSettingsActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                ThemeSettingsScreen(
                    onBack = { finish() },
                    onThemeSelected = { themeId ->
                        // Save selected theme and update Config
                        prefs.edit().putString("selected_theme_id", themeId).apply()
                        Toast.makeText(this, "Theme applied!", Toast.LENGTH_SHORT).show()
                    }
                )
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
            // Custom Themes Section
            if (customThemes.isNotEmpty()) {
                item {
                    Text(
                        "Custom Themes",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                items(customThemes) { theme ->
                    ThemeCard(
                        name = theme.name,
                        colorScheme = theme.colors,
                        isCustom = true,
                        onSelect = { onThemeSelected(theme.id) },
                        onEdit = { editingTheme = theme },
                        onDelete = { showDeleteConfirm = theme.id }
                    )
                }
            }

            // Predefined Themes by Category
            val predefinedThemes = getAllPredefinedThemes()
            ThemeCategory.entries.filter { it != ThemeCategory.CUSTOM }.forEach { category ->
                val themesInCategory = predefinedThemes[category] ?: emptyList()
                if (themesInCategory.isNotEmpty()) {
                    item {
                        Text(
                            category.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(themesInCategory) { themeInfo ->
                        ThemeCard(
                            name = themeInfo.name,
                            colorScheme = themeInfo.colorScheme,
                            description = themeInfo.description,
                            isCustom = false,
                            onSelect = { onThemeSelected(themeInfo.id) }
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    // Create Theme Dialog
    if (showCreateDialog) {
        ThemeCreatorDialog(
            initialTheme = null,
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

@Composable
fun ThemeCard(
    name: String,
    colorScheme: KeyboardColorScheme,
    description: String? = null,
    isCustom: Boolean,
    onSelect: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = Color(colorScheme.keyboardBackground.toArgb())
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeCreatorDialog(
    initialTheme: CustomTheme?,
    onDismiss: () -> Unit,
    onSave: (CustomTheme) -> Unit
) {
    var themeName by remember { mutableStateOf(initialTheme?.name ?: "My Theme") }
    var colors by remember { mutableStateOf(initialTheme?.colors ?: darkKeyboardColorScheme()) }
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
