package tribixbite.cleverkeys.theme

import androidx.compose.ui.graphics.Color

/**
 * Predefined keyboard themes for CleverKeys.
 *
 * Provides 18 professionally designed themes across 6 categories:
 * - Gemstone (3): Ruby, Sapphire, Emerald
 * - Neon (3): Electric Blue, Hot Pink, Lime Green
 * - Pastel (3): Soft Pink, Sky Blue, Mint Green
 * - Nature (3): Forest, Ocean, Desert
 * - Utilitarian (3): Charcoal, Slate, Concrete
 * - Modern (3): Midnight, Sunrise, Aurora
 *
 * Each theme includes complete color definitions for all keyboard components.
 */

/**
 * Theme category enumeration.
 */
enum class ThemeCategory(val displayName: String) {
    GEMSTONE("Gemstone"),
    NEON("Neon"),
    PASTEL("Pastel"),
    NATURE("Nature"),
    UTILITARIAN("Utilitarian"),
    MODERN("Modern"),
    CUSTOM("Custom")
}

/**
 * Complete theme information including metadata and colors.
 */
data class ThemeInfo(
    val id: String,
    val name: String,
    val category: ThemeCategory,
    val colorScheme: KeyboardColorScheme,
    val description: String,
    val isDeletable: Boolean = false,
    val isExportable: Boolean = true
)

// =============================================================================
// GEMSTONE THEMES - Precious stone inspired colors
// =============================================================================

/**
 * Ruby - Deep red gemstone theme with warm tones.
 */
fun themeGemstoneRuby(): ThemeInfo = ThemeInfo(
    id = "gemstone_ruby",
    name = "Ruby",
    category = ThemeCategory.GEMSTONE,
    description = "Deep crimson inspired by precious rubies",
    colorScheme = KeyboardColorScheme(
        keyDefault = Color(0xFF4A0E0E),              // Deep burgundy
        keyActivated = Color(0xFF6B1515),            // Brighter burgundy
        keyLocked = Color(0xFF8B2323),               // Ruby red
        keyModifier = Color(0xFF7A1A1A),             // Dark ruby
        keySpecial = Color(0xFFAA3030),              // Bright ruby

        keyLabel = Color(0xFFFFD6D6),                // Soft pink text
        keySubLabel = Color(0xFFFFB3B3),             // Pink text
        keySecondaryLabel = Color(0xFFFF9999),       // Light pink

        keyBorder = Color(0xFF8B2323),               // Ruby border
        keyBorderActivated = Color(0xFFCC4444),      // Bright ruby

        swipeTrail = Color(0xFFFF6B6B),              // Vibrant ruby trail
        ripple = Color(0xFFFF6B6B).copy(alpha = 0.4f),

        suggestionText = Color(0xFFFFFFFF),
        suggestionBackground = Color(0xFF4A0E0E),
        suggestionHighConfidence = Color(0xFFFF6B6B),

        keyboardBackground = Color(0xFF2A0505),      // Very dark red
        keyboardSurface = Color(0xFF3A0A0A)          // Dark red
    )
)

/**
 * Sapphire - Deep blue gemstone theme with cool tones.
 */
fun themeGemstoneSapphire(): ThemeInfo = ThemeInfo(
    id = "gemstone_sapphire",
    name = "Sapphire",
    category = ThemeCategory.GEMSTONE,
    description = "Rich blue inspired by precious sapphires",
    colorScheme = KeyboardColorScheme(
        keyDefault = Color(0xFF0A1E4A),              // Deep navy
        keyActivated = Color(0xFF15306B),            // Brighter navy
        keyLocked = Color(0xFF1E4A8B),               // Sapphire blue
        keyModifier = Color(0xFF173A7A),             // Dark sapphire
        keySpecial = Color(0xFF2B5CAA),              // Bright sapphire

        keyLabel = Color(0xFFD6E4FF),                // Soft blue text
        keySubLabel = Color(0xFFB3CEFF),             // Blue text
        keySecondaryLabel = Color(0xFF99BFFF),       // Light blue

        keyBorder = Color(0xFF1E4A8B),               // Sapphire border
        keyBorderActivated = Color(0xFF4472CC),      // Bright sapphire

        swipeTrail = Color(0xFF6B9FFF),              // Vibrant sapphire trail
        ripple = Color(0xFF6B9FFF).copy(alpha = 0.4f),

        suggestionText = Color(0xFFFFFFFF),
        suggestionBackground = Color(0xFF0A1E4A),
        suggestionHighConfidence = Color(0xFF6B9FFF),

        keyboardBackground = Color(0xFF050F2A),      // Very dark blue
        keyboardSurface = Color(0xFF0A193A)          // Dark blue
    )
)

/**
 * Emerald - Deep green gemstone theme with vibrant tones.
 */
fun themeGemstoneEmerald(): ThemeInfo = ThemeInfo(
    id = "gemstone_emerald",
    name = "Emerald",
    category = ThemeCategory.GEMSTONE,
    description = "Lush green inspired by precious emeralds",
    colorScheme = KeyboardColorScheme(
        keyDefault = Color(0xFF0E4A1E),              // Deep green
        keyActivated = Color(0xFF156B30),            // Brighter green
        keyLocked = Color(0xFF238B3E),               // Emerald green
        keyModifier = Color(0xFF1A7A34),             // Dark emerald
        keySpecial = Color(0xFF30AA54),              // Bright emerald

        keyLabel = Color(0xFFD6FFE4),                // Soft green text
        keySubLabel = Color(0xFFB3FFCE),             // Green text
        keySecondaryLabel = Color(0xFF99FFBF),       // Light green

        keyBorder = Color(0xFF238B3E),               // Emerald border
        keyBorderActivated = Color(0xFF44CC72),      // Bright emerald

        swipeTrail = Color(0xFF6BFF9F),              // Vibrant emerald trail
        ripple = Color(0xFF6BFF9F).copy(alpha = 0.4f),

        suggestionText = Color(0xFFFFFFFF),
        suggestionBackground = Color(0xFF0E4A1E),
        suggestionHighConfidence = Color(0xFF6BFF9F),

        keyboardBackground = Color(0xFF052A0F),      // Very dark green
        keyboardSurface = Color(0xFF0A3A19)          // Dark green
    )
)

// =============================================================================
// NEON THEMES - Vibrant glowing colors
// =============================================================================

/**
 * Neon Electric Blue - Vibrant electric blue with high contrast.
 */
fun themeNeonElectricBlue(): ThemeInfo = ThemeInfo(
    id = "neon_electric_blue",
    name = "Electric Blue",
    category = ThemeCategory.NEON,
    description = "Vibrant electric blue with neon glow",
    colorScheme = KeyboardColorScheme(
        keyDefault = Color(0xFF001A33),              // Very dark blue
        keyActivated = Color(0xFF002B52),            // Dark blue
        keyLocked = Color(0xFF0055AA),               // Electric blue
        keyModifier = Color(0xFF003D7A),             // Medium blue
        keySpecial = Color(0xFF0077FF),              // Bright electric

        keyLabel = Color(0xFF00D4FF),                // Cyan neon text
        keySubLabel = Color(0xFF00A8CC),             // Bright cyan
        keySecondaryLabel = Color(0xFF0088AA),       // Medium cyan

        keyBorder = Color(0xFF0055AA),               // Electric border
        keyBorderActivated = Color(0xFF00BBFF),      // Bright neon

        swipeTrail = Color(0xFF00F0FF),              // Neon cyan trail
        ripple = Color(0xFF00F0FF).copy(alpha = 0.6f),

        suggestionText = Color(0xFF00D4FF),
        suggestionBackground = Color(0xFF001A33),
        suggestionHighConfidence = Color(0xFF00F0FF),

        keyboardBackground = Color(0xFF000D1A),      // Almost black
        keyboardSurface = Color(0xFF00141A)          // Very dark blue
    )
)

/**
 * Neon Hot Pink - Vibrant hot pink with neon glow.
 */
fun themeNeonHotPink(): ThemeInfo = ThemeInfo(
    id = "neon_hot_pink",
    name = "Hot Pink",
    category = ThemeCategory.NEON,
    description = "Vibrant hot pink with neon glow",
    colorScheme = KeyboardColorScheme(
        keyDefault = Color(0xFF33001A),              // Very dark pink
        keyActivated = Color(0xFF52002B),            // Dark pink
        keyLocked = Color(0xFFAA0055),               // Hot pink
        keyModifier = Color(0xFF7A003D),             // Medium pink
        keySpecial = Color(0xFFFF0077),              // Bright hot pink

        keyLabel = Color(0xFFFF00D4),                // Magenta neon text
        keySubLabel = Color(0xFFCC00A8),             // Bright magenta
        keySecondaryLabel = Color(0xFFAA0088),       // Medium magenta

        keyBorder = Color(0xFFAA0055),               // Hot pink border
        keyBorderActivated = Color(0xFFFF00BB),      // Bright neon

        swipeTrail = Color(0xFFFF00F0),              // Neon magenta trail
        ripple = Color(0xFFFF00F0).copy(alpha = 0.6f),

        suggestionText = Color(0xFFFF00D4),
        suggestionBackground = Color(0xFF33001A),
        suggestionHighConfidence = Color(0xFFFF00F0),

        keyboardBackground = Color(0xFF1A000D),      // Almost black
        keyboardSurface = Color(0xFF1A0014)          // Very dark pink
    )
)

/**
 * Neon Lime Green - Vibrant lime green with neon glow.
 */
fun themeNeonLimeGreen(): ThemeInfo = ThemeInfo(
    id = "neon_lime_green",
    name = "Lime Green",
    category = ThemeCategory.NEON,
    description = "Vibrant lime green with neon glow",
    colorScheme = KeyboardColorScheme(
        keyDefault = Color(0xFF1A3300),              // Very dark green
        keyActivated = Color(0xFF2B5200),            // Dark green
        keyLocked = Color(0xFF55AA00),               // Lime green
        keyModifier = Color(0xFF3D7A00),             // Medium green
        keySpecial = Color(0xFF77FF00),              // Bright lime

        keyLabel = Color(0xFFD4FF00),                // Yellow-green neon text
        keySubLabel = Color(0xFFA8CC00),             // Bright lime
        keySecondaryLabel = Color(0xFF88AA00),       // Medium lime

        keyBorder = Color(0xFF55AA00),               // Lime border
        keyBorderActivated = Color(0xFFBBFF00),      // Bright neon

        swipeTrail = Color(0xFFF0FF00),              // Neon lime trail
        ripple = Color(0xFFF0FF00).copy(alpha = 0.6f),

        suggestionText = Color(0xFFD4FF00),
        suggestionBackground = Color(0xFF1A3300),
        suggestionHighConfidence = Color(0xFFF0FF00),

        keyboardBackground = Color(0xFF0D1A00),      // Almost black
        keyboardSurface = Color(0xFF141A00)          // Very dark green
    )
)

// =============================================================================
// PASTEL THEMES - Soft, gentle colors
// =============================================================================

/**
 * Pastel Soft Pink - Gentle pink with soft tones.
 */
fun themePastelSoftPink(): ThemeInfo = ThemeInfo(
    id = "pastel_soft_pink",
    name = "Soft Pink",
    category = ThemeCategory.PASTEL,
    description = "Gentle pink with soft, calming tones",
    colorScheme = KeyboardColorScheme(
        keyDefault = Color(0xFFFFE6F0),              // Very soft pink
        keyActivated = Color(0xFFFFCCE0),            // Soft pink
        keyLocked = Color(0xFFFFB3D9).copy(alpha = 0.8f), // Pastel pink
        keyModifier = Color(0xFFFFB3D9).copy(alpha = 0.6f),
        keySpecial = Color(0xFFFF99CC).copy(alpha = 0.7f),

        keyLabel = Color(0xFF663344),                // Dark pink text
        keySubLabel = Color(0xFF884466),             // Medium pink text
        keySecondaryLabel = Color(0xFFAA6688),       // Light pink text

        keyBorder = Color(0xFFFFCCE0),               // Soft pink border
        keyBorderActivated = Color(0xFFFF99CC),      // Bright pastel

        swipeTrail = Color(0xFFFFAADD),              // Soft pink trail
        ripple = Color(0xFFFFAADD).copy(alpha = 0.3f),

        suggestionText = Color(0xFF663344),
        suggestionBackground = Color(0xFFFFF0F5),
        suggestionHighConfidence = Color(0xFFFF99CC),

        keyboardBackground = Color(0xFFFFF5FA),      // Almost white pink
        keyboardSurface = Color(0xFFFFEBF0)          // Very soft pink
    )
)

/**
 * Pastel Sky Blue - Gentle blue with soft tones.
 */
fun themePastelSkyBlue(): ThemeInfo = ThemeInfo(
    id = "pastel_sky_blue",
    name = "Sky Blue",
    category = ThemeCategory.PASTEL,
    description = "Gentle blue with soft, airy tones",
    colorScheme = KeyboardColorScheme(
        keyDefault = Color(0xFFE6F0FF),              // Very soft blue
        keyActivated = Color(0xFFCCE0FF),            // Soft blue
        keyLocked = Color(0xFFB3D9FF).copy(alpha = 0.8f), // Pastel blue
        keyModifier = Color(0xFFB3D9FF).copy(alpha = 0.6f),
        keySpecial = Color(0xFF99CCFF).copy(alpha = 0.7f),

        keyLabel = Color(0xFF334466),                // Dark blue text
        keySubLabel = Color(0xFF446688),             // Medium blue text
        keySecondaryLabel = Color(0xFF6688AA),       // Light blue text

        keyBorder = Color(0xFFCCE0FF),               // Soft blue border
        keyBorderActivated = Color(0xFF99CCFF),      // Bright pastel

        swipeTrail = Color(0xFFAADDFF),              // Soft blue trail
        ripple = Color(0xFFAADDFF).copy(alpha = 0.3f),

        suggestionText = Color(0xFF334466),
        suggestionBackground = Color(0xFFF0F5FF),
        suggestionHighConfidence = Color(0xFF99CCFF),

        keyboardBackground = Color(0xFFF5FAFF),      // Almost white blue
        keyboardSurface = Color(0xFFEBF0FF)          // Very soft blue
    )
)

/**
 * Pastel Mint Green - Gentle mint with soft tones.
 */
fun themePastelMintGreen(): ThemeInfo = ThemeInfo(
    id = "pastel_mint_green",
    name = "Mint Green",
    category = ThemeCategory.PASTEL,
    description = "Gentle mint with soft, fresh tones",
    colorScheme = KeyboardColorScheme(
        keyDefault = Color(0xFFE6FFF0),              // Very soft mint
        keyActivated = Color(0xFFCCFFE0),            // Soft mint
        keyLocked = Color(0xFFB3FFD9).copy(alpha = 0.8f), // Pastel mint
        keyModifier = Color(0xFFB3FFD9).copy(alpha = 0.6f),
        keySpecial = Color(0xFF99FFCC).copy(alpha = 0.7f),

        keyLabel = Color(0xFF336644),                // Dark green text
        keySubLabel = Color(0xFF448866),             // Medium green text
        keySecondaryLabel = Color(0xFF66AA88),       // Light green text

        keyBorder = Color(0xFFCCFFE0),               // Soft mint border
        keyBorderActivated = Color(0xFF99FFCC),      // Bright pastel

        swipeTrail = Color(0xFFAAFFDD),              // Soft mint trail
        ripple = Color(0xFFAAFFDD).copy(alpha = 0.3f),

        suggestionText = Color(0xFF336644),
        suggestionBackground = Color(0xFFF0FFF5),
        suggestionHighConfidence = Color(0xFF99FFCC),

        keyboardBackground = Color(0xFFF5FFFA),      // Almost white green
        keyboardSurface = Color(0xFFEBFFF0)          // Very soft mint
    )
)

// =============================================================================
// NATURE THEMES - Earthy, organic colors
// =============================================================================

/**
 * Nature Forest - Deep forest greens and browns.
 */
fun themeNatureForest(): ThemeInfo = ThemeInfo(
    id = "nature_forest",
    name = "Forest",
    category = ThemeCategory.NATURE,
    description = "Deep forest greens inspired by woodlands",
    colorScheme = KeyboardColorScheme(
        keyDefault = Color(0xFF1A2E1A),              // Deep forest green
        keyActivated = Color(0xFF2B4A2B),            // Moss green
        keyLocked = Color(0xFF3E6B3E),               // Forest green
        keyModifier = Color(0xFF2D4F2D),             // Dark leaf green
        keySpecial = Color(0xFF4A8A4A),              // Bright forest

        keyLabel = Color(0xFFCCE6CC),                // Light green text
        keySubLabel = Color(0xFFAAD4AA),             // Green text
        keySecondaryLabel = Color(0xFF88BB88),       // Medium green

        keyBorder = Color(0xFF3E6B3E),               // Forest border
        keyBorderActivated = Color(0xFF5AAA5A),      // Bright green

        swipeTrail = Color(0xFF6BCC6B),              // Leaf green trail
        ripple = Color(0xFF6BCC6B).copy(alpha = 0.4f),

        suggestionText = Color(0xFFFFFFFF),
        suggestionBackground = Color(0xFF1A2E1A),
        suggestionHighConfidence = Color(0xFF6BCC6B),

        keyboardBackground = Color(0xFF0F1A0F),      // Very dark green
        keyboardSurface = Color(0xFF1A261A)          // Dark forest
    )
)

/**
 * Nature Ocean - Deep ocean blues and teals.
 */
fun themeNatureOcean(): ThemeInfo = ThemeInfo(
    id = "nature_ocean",
    name = "Ocean",
    category = ThemeCategory.NATURE,
    description = "Deep ocean blues inspired by the sea",
    colorScheme = KeyboardColorScheme(
        keyDefault = Color(0xFF1A2E3E),              // Deep ocean blue
        keyActivated = Color(0xFF2B4A5E),            // Ocean blue
        keyLocked = Color(0xFF3E6B8A),               // Sea blue
        keyModifier = Color(0xFF2D4F72),             // Deep sea
        keySpecial = Color(0xFF4A8AAA),              // Bright ocean

        keyLabel = Color(0xFFCCE6F0),                // Light blue text
        keySubLabel = Color(0xFFAAD4E6),             // Blue text
        keySecondaryLabel = Color(0xFF88BBD4),       // Medium blue

        keyBorder = Color(0xFF3E6B8A),               // Ocean border
        keyBorderActivated = Color(0xFF5AAACC),      // Bright blue

        swipeTrail = Color(0xFF6BCCEE),              // Water blue trail
        ripple = Color(0xFF6BCCEE).copy(alpha = 0.4f),

        suggestionText = Color(0xFFFFFFFF),
        suggestionBackground = Color(0xFF1A2E3E),
        suggestionHighConfidence = Color(0xFF6BCCEE),

        keyboardBackground = Color(0xFF0F1A26),      // Very dark blue
        keyboardSurface = Color(0xFF1A2633)          // Dark ocean
    )
)

/**
 * Nature Desert - Warm sand and earth tones.
 */
fun themeNatureDesert(): ThemeInfo = ThemeInfo(
    id = "nature_desert",
    name = "Desert",
    category = ThemeCategory.NATURE,
    description = "Warm sandy tones inspired by deserts",
    colorScheme = KeyboardColorScheme(
        keyDefault = Color(0xFF3E2E1A),              // Sandy brown
        keyActivated = Color(0xFF5E4A2B),            // Tan
        keyLocked = Color(0xFF8A6B3E),               // Desert sand
        keyModifier = Color(0xFF724F2D),             // Earth brown
        keySpecial = Color(0xFFAA8A4A),              // Bright sand

        keyLabel = Color(0xFFF0E6CC),                // Light sand text
        keySubLabel = Color(0xFFE6D4AA),             // Sand text
        keySecondaryLabel = Color(0xFFD4BB88),       // Medium sand

        keyBorder = Color(0xFF8A6B3E),               // Desert border
        keyBorderActivated = Color(0xFFCCAA5A),      // Bright sand

        swipeTrail = Color(0xFFEECC6B),              // Sand trail
        ripple = Color(0xFFEECC6B).copy(alpha = 0.4f),

        suggestionText = Color(0xFF2E1A0A),
        suggestionBackground = Color(0xFFFFF5E6),
        suggestionHighConfidence = Color(0xFFEECC6B),

        keyboardBackground = Color(0xFF261A0F),      // Dark earth
        keyboardSurface = Color(0xFF33261A)          // Earth brown
    )
)

// =============================================================================
// UTILITARIAN THEMES - Professional, focused colors
// =============================================================================

/**
 * Utilitarian Charcoal - Deep charcoal with high contrast.
 */
fun themeUtilitarianCharcoal(): ThemeInfo = ThemeInfo(
    id = "utilitarian_charcoal",
    name = "Charcoal",
    category = ThemeCategory.UTILITARIAN,
    description = "Professional charcoal with high contrast",
    colorScheme = KeyboardColorScheme(
        keyDefault = Color(0xFF2A2A2A),              // Charcoal
        keyActivated = Color(0xFF3A3A3A),            // Light charcoal
        keyLocked = Color(0xFF4A4A4A),               // Medium grey
        keyModifier = Color(0xFF3E3E3E),             // Dark grey
        keySpecial = Color(0xFF5A5A5A),              // Light grey

        keyLabel = Color(0xFFEEEEEE),                // White text
        keySubLabel = Color(0xFFCCCCCC),             // Light grey text
        keySecondaryLabel = Color(0xFFAAAAAA),       // Grey text

        keyBorder = Color(0xFF1A1A1A),               // Black border
        keyBorderActivated = Color(0xFF666666),      // Grey border

        swipeTrail = Color(0xFF888888),              // Grey trail
        ripple = Color(0xFF888888).copy(alpha = 0.4f),

        suggestionText = Color(0xFFEEEEEE),
        suggestionBackground = Color(0xFF2A2A2A),
        suggestionHighConfidence = Color(0xFFAAAAAA),

        keyboardBackground = Color(0xFF1A1A1A),      // Dark charcoal
        keyboardSurface = Color(0xFF242424)          // Charcoal
    )
)

/**
 * Utilitarian Slate - Cool grey slate tones.
 */
fun themeUtilitarianSlate(): ThemeInfo = ThemeInfo(
    id = "utilitarian_slate",
    name = "Slate",
    category = ThemeCategory.UTILITARIAN,
    description = "Cool slate grey for professional use",
    colorScheme = KeyboardColorScheme(
        keyDefault = Color(0xFF2A333A),              // Slate grey
        keyActivated = Color(0xFF3A4A52),            // Light slate
        keyLocked = Color(0xFF4A5E6B),               // Blue-grey
        keyModifier = Color(0xFF3E515E),             // Dark slate
        keySpecial = Color(0xFF5A738A),              // Bright slate

        keyLabel = Color(0xFFE6F0F5),                // Light grey-blue text
        keySubLabel = Color(0xFFC4D4E0),             // Grey-blue text
        keySecondaryLabel = Color(0xFFA8BBC8),       // Medium grey

        keyBorder = Color(0xFF1A262E),               // Dark slate border
        keyBorderActivated = Color(0xFF6688AA),      // Blue-grey border

        swipeTrail = Color(0xFF7799BB),              // Slate blue trail
        ripple = Color(0xFF7799BB).copy(alpha = 0.4f),

        suggestionText = Color(0xFFE6F0F5),
        suggestionBackground = Color(0xFF2A333A),
        suggestionHighConfidence = Color(0xFF88AACC),

        keyboardBackground = Color(0xFF1A2228),      // Very dark slate
        keyboardSurface = Color(0xFF242C33)          // Dark slate
    )
)

/**
 * Utilitarian Concrete - Neutral concrete grey.
 */
fun themeUtilitarianConcrete(): ThemeInfo = ThemeInfo(
    id = "utilitarian_concrete",
    name = "Concrete",
    category = ThemeCategory.UTILITARIAN,
    description = "Neutral concrete grey for minimal distraction",
    colorScheme = KeyboardColorScheme(
        keyDefault = Color(0xFF3A3A3A),              // Concrete grey
        keyActivated = Color(0xFF4A4A4A),            // Light concrete
        keyLocked = Color(0xFF5A5A5A),               // Medium concrete
        keyModifier = Color(0xFF4E4E4E),             // Dark concrete
        keySpecial = Color(0xFF6A6A6A),              // Bright concrete

        keyLabel = Color(0xFFF5F5F5),                // Off-white text
        keySubLabel = Color(0xFFD4D4D4),             // Light grey text
        keySecondaryLabel = Color(0xFFB8B8B8),       // Grey text

        keyBorder = Color(0xFF2A2A2A),               // Dark border
        keyBorderActivated = Color(0xFF777777),      // Grey border

        swipeTrail = Color(0xFF999999),              // Concrete trail
        ripple = Color(0xFF999999).copy(alpha = 0.4f),

        suggestionText = Color(0xFFF5F5F5),
        suggestionBackground = Color(0xFF3A3A3A),
        suggestionHighConfidence = Color(0xFFB8B8B8),

        keyboardBackground = Color(0xFF2A2A2A),      // Dark concrete
        keyboardSurface = Color(0xFF333333)          // Concrete
    )
)

// =============================================================================
// MODERN THEMES - Contemporary, stylish colors
// =============================================================================

/**
 * Modern Midnight - Deep purple-blue midnight theme.
 */
fun themeModernMidnight(): ThemeInfo = ThemeInfo(
    id = "modern_midnight",
    name = "Midnight",
    category = ThemeCategory.MODERN,
    description = "Deep purple-blue inspired by midnight sky",
    colorScheme = KeyboardColorScheme(
        keyDefault = Color(0xFF1A1A2E),              // Midnight blue
        keyActivated = Color(0xFF2B2B4A),            // Deep purple-blue
        keyLocked = Color(0xFF3E3E6B),               // Purple-blue
        keyModifier = Color(0xFF2D2D4F),             // Dark purple
        keySpecial = Color(0xFF4A4A8A),              // Bright purple-blue

        keyLabel = Color(0xFFCCCCE6),                // Light purple text
        keySubLabel = Color(0xFFAAAAD4),             // Purple text
        keySecondaryLabel = Color(0xFF8888BB),       // Medium purple

        keyBorder = Color(0xFF3E3E6B),               // Purple-blue border
        keyBorderActivated = Color(0xFF5A5AAA),      // Bright purple

        swipeTrail = Color(0xFF6B6BCC),              // Purple trail
        ripple = Color(0xFF6B6BCC).copy(alpha = 0.4f),

        suggestionText = Color(0xFFE6E6FF),
        suggestionBackground = Color(0xFF1A1A2E),
        suggestionHighConfidence = Color(0xFF8888DD),

        keyboardBackground = Color(0xFF0F0F1A),      // Very dark blue
        keyboardSurface = Color(0xFF1A1A26)          // Dark midnight
    )
)

/**
 * Modern Sunrise - Warm gradient oranges and pinks.
 */
fun themeModernSunrise(): ThemeInfo = ThemeInfo(
    id = "modern_sunrise",
    name = "Sunrise",
    category = ThemeCategory.MODERN,
    description = "Warm oranges and pinks inspired by sunrise",
    colorScheme = KeyboardColorScheme(
        keyDefault = Color(0xFF2E1A1A),              // Dark warm red
        keyActivated = Color(0xFF4A2B2B),            // Warm red-brown
        keyLocked = Color(0xFF6B3E3E),               // Rose
        keyModifier = Color(0xFF4F2D2D),             // Dark rose
        keySpecial = Color(0xFF8A4A4A),              // Bright rose

        keyLabel = Color(0xFFFFCCCC),                // Soft pink text
        keySubLabel = Color(0xFFFFAAAA),             // Pink text
        keySecondaryLabel = Color(0xFFFF8888),       // Medium pink

        keyBorder = Color(0xFF6B3E3E),               // Rose border
        keyBorderActivated = Color(0xFFCC5A5A),      // Bright rose

        swipeTrail = Color(0xFFFF6B6B),              // Warm pink trail
        ripple = Color(0xFFFF6B6B).copy(alpha = 0.4f),

        suggestionText = Color(0xFFFFEEEE),
        suggestionBackground = Color(0xFF2E1A1A),
        suggestionHighConfidence = Color(0xFFFF8888),

        keyboardBackground = Color(0xFF1A0F0F),      // Very dark red
        keyboardSurface = Color(0xFF261A1A)          // Dark warm
    )
)

/**
 * Modern Aurora - Cool gradient blues and greens.
 */
fun themeModernAurora(): ThemeInfo = ThemeInfo(
    id = "modern_aurora",
    name = "Aurora",
    category = ThemeCategory.MODERN,
    description = "Cool blues and greens inspired by aurora borealis",
    colorScheme = KeyboardColorScheme(
        keyDefault = Color(0xFF1A2E2E),              // Dark teal
        keyActivated = Color(0xFF2B4A4A),            // Teal
        keyLocked = Color(0xFF3E6B6B),               // Aqua teal
        keyModifier = Color(0xFF2D4F4F),             // Dark aqua
        keySpecial = Color(0xFF4A8A8A),              // Bright teal

        keyLabel = Color(0xFFCCF0F0),                // Light cyan text
        keySubLabel = Color(0xFFAAE6E6),             // Cyan text
        keySecondaryLabel = Color(0xFF88D4D4),       // Medium cyan

        keyBorder = Color(0xFF3E6B6B),               // Teal border
        keyBorderActivated = Color(0xFF5AAAAA),      // Bright aqua

        swipeTrail = Color(0xFF6BCCCC),              // Aqua trail
        ripple = Color(0xFF6BCCCC).copy(alpha = 0.4f),

        suggestionText = Color(0xFFEEFFFF),
        suggestionBackground = Color(0xFF1A2E2E),
        suggestionHighConfidence = Color(0xFF88DDDD),

        keyboardBackground = Color(0xFF0F1A1A),      // Very dark teal
        keyboardSurface = Color(0xFF1A2626)          // Dark teal
    )
)

// =============================================================================
// THEME REGISTRY - All predefined themes
// =============================================================================

/**
 * Get all predefined themes organized by category.
 */
fun getAllPredefinedThemes(): Map<ThemeCategory, List<ThemeInfo>> {
    return mapOf(
        ThemeCategory.GEMSTONE to listOf(
            themeGemstoneRuby(),
            themeGemstoneSapphire(),
            themeGemstoneEmerald()
        ),
        ThemeCategory.NEON to listOf(
            themeNeonElectricBlue(),
            themeNeonHotPink(),
            themeNeonLimeGreen()
        ),
        ThemeCategory.PASTEL to listOf(
            themePastelSoftPink(),
            themePastelSkyBlue(),
            themePastelMintGreen()
        ),
        ThemeCategory.NATURE to listOf(
            themeNatureForest(),
            themeNatureOcean(),
            themeNatureDesert()
        ),
        ThemeCategory.UTILITARIAN to listOf(
            themeUtilitarianCharcoal(),
            themeUtilitarianSlate(),
            themeUtilitarianConcrete()
        ),
        ThemeCategory.MODERN to listOf(
            themeModernMidnight(),
            themeModernSunrise(),
            themeModernAurora()
        )
    )
}

/**
 * Get a flat list of all predefined themes.
 */
fun getAllPredefinedThemesList(): List<ThemeInfo> {
    return getAllPredefinedThemes().values.flatten()
}

/**
 * Get a theme by ID.
 */
fun getThemeById(id: String): ThemeInfo? {
    return getAllPredefinedThemesList().find { it.id == id }
}

/**
 * Get all themes in a specific category.
 */
fun getThemesByCategory(category: ThemeCategory): List<ThemeInfo> {
    return getAllPredefinedThemes()[category] ?: emptyList()
}
