package juloo.keyboard2

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.TypedValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.*

/**
 * Complete theme management with Android system integration
 * Properly integrates with Android themes, dark mode, and dynamic theming
 */
class Theme private constructor(private val context: Context) {

    companion object {
        private var instance: Theme? = null

        fun initialize(context: Context): Theme {
            return instance ?: Theme(context).also { instance = it }
        }

        fun get_current(): ThemeData {
            return instance?.getCurrentTheme() ?: createFallbackTheme()
        }

        private fun createFallbackTheme(): ThemeData {
            return ThemeData(
                keyColor = 0xFF2B2B2B.toInt(),
                keyBorderColor = 0xFF1A1A1A.toInt(),
                labelColor = Color.WHITE,
                backgroundColor = Color.BLACK,
                labelTextSize = 16f,
                isDarkMode = true
            )
        }
    }

    /**
     * Complete theme data with Android integration
     */
    data class ThemeData(
        val keyColor: Int,
        val keyBorderColor: Int,
        val labelColor: Int,
        val backgroundColor: Int,
        val labelTextSize: Float,
        val isDarkMode: Boolean,
        // Extended theme properties from Android system
        val keyActivatedColor: Int = keyColor,
        val suggestionTextColor: Int = labelColor,
        val suggestionBackgroundColor: Int = backgroundColor,
        val swipeTrailColor: Int = 0xFF00D4FF.toInt(),
        val errorColor: Int = 0xFFFF5722.toInt(),
        val successColor: Int = 0xFF4CAF50.toInt(),
        // Typography
        val keyTextSize: Float = labelTextSize,
        val suggestionTextSize: Float = labelTextSize * 0.9f,
        val hintTextSize: Float = labelTextSize * 0.7f,
        // Dimensions from Android theme
        val keyCornerRadius: Float = 8f,
        val keyElevation: Float = 2f,
        val suggestionBarHeight: Float = 48f
    )

    private val _currentTheme = MutableStateFlow(createFallbackTheme())
    val currentThemeFlow: StateFlow<ThemeData> = _currentTheme.asStateFlow()

    /**
     * Get current theme with Android system integration
     */
    fun getCurrentTheme(): ThemeData {
        return if (isSystemDarkMode()) {
            createSystemDarkTheme()
        } else {
            createSystemLightTheme()
        }
    }

    /**
     * Check if system is in dark mode
     */
    private fun isSystemDarkMode(): Boolean {
        val uiMode = context.resources.configuration.uiMode
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Create theme from Android system dark theme
     */
    private fun createSystemDarkTheme(): ThemeData {
        val typedValue = TypedValue()
        val theme = context.theme

        // Get colors from Android theme
        val keyColor = getThemeColor(android.R.attr.colorBackground, 0xFF2B2B2B.toInt())
        val labelColor = getThemeColor(android.R.attr.textColorPrimary, Color.WHITE)
        val backgroundColor = getThemeColor(android.R.attr.colorBackgroundFloating, Color.BLACK)

        return ThemeData(
            keyColor = keyColor,
            keyBorderColor = adjustColorBrightness(keyColor, 0.8f),
            labelColor = labelColor,
            backgroundColor = backgroundColor,
            labelTextSize = getThemeTextSize(android.R.attr.textSize, 16f),
            isDarkMode = true,
            keyActivatedColor = adjustColorBrightness(keyColor, 1.2f),
            suggestionTextColor = labelColor,
            suggestionBackgroundColor = adjustColorBrightness(backgroundColor, 1.1f),
            swipeTrailColor = getThemeColor(android.R.attr.colorAccent, 0xFF00D4FF.toInt()),
            keyCornerRadius = getThemeDimension(android.R.attr.cornerRadius, 8f),
            keyElevation = getThemeDimension(android.R.attr.elevation, 2f)
        )
    }

    /**
     * Create theme from Android system light theme
     */
    private fun createSystemLightTheme(): ThemeData {
        val keyColor = getThemeColor(android.R.attr.colorBackground, Color.LTGRAY)
        val labelColor = getThemeColor(android.R.attr.textColorPrimary, Color.BLACK)
        val backgroundColor = getThemeColor(android.R.attr.colorBackgroundFloating, Color.WHITE)

        return ThemeData(
            keyColor = keyColor,
            keyBorderColor = adjustColorBrightness(keyColor, 0.8f),
            labelColor = labelColor,
            backgroundColor = backgroundColor,
            labelTextSize = getThemeTextSize(android.R.attr.textSize, 16f),
            isDarkMode = false,
            keyActivatedColor = adjustColorBrightness(keyColor, 0.9f),
            suggestionTextColor = labelColor,
            suggestionBackgroundColor = adjustColorBrightness(backgroundColor, 0.95f),
            swipeTrailColor = getThemeColor(android.R.attr.colorAccent, 0xFF1976D2.toInt()),
            keyCornerRadius = getThemeDimension(android.R.attr.cornerRadius, 8f),
            keyElevation = getThemeDimension(android.R.attr.elevation, 2f)
        )
    }

    /**
     * Get color from Android theme system
     */
    private fun getThemeColor(attrId: Int, fallback: Int): Int {
        val typedValue = TypedValue()
        return if (context.theme.resolveAttribute(attrId, typedValue, true)) {
            if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
                typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                typedValue.data
            } else {
                ContextCompat.getColor(context, typedValue.resourceId)
            }
        } else {
            fallback
        }
    }

    /**
     * Get text size from Android theme
     */
    private fun getThemeTextSize(attrId: Int, fallback: Float): Float {
        val typedValue = TypedValue()
        return if (context.theme.resolveAttribute(attrId, typedValue, true)) {
            TypedValue.complexToDimensionPixelSize(typedValue.data, context.resources.displayMetrics).toFloat()
        } else {
            fallback
        }
    }

    /**
     * Get dimension from Android theme
     */
    private fun getThemeDimension(attrId: Int, fallback: Float): Float {
        val typedValue = TypedValue()
        return if (context.theme.resolveAttribute(attrId, typedValue, true)) {
            TypedValue.complexToDimension(typedValue.data, context.resources.displayMetrics)
        } else {
            fallback
        }
    }

    /**
     * Adjust color brightness
     */
    private fun adjustColorBrightness(color: Int, factor: Float): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val a = Color.alpha(color)

        return Color.argb(
            a,
            (r * factor).toInt().coerceIn(0, 255),
            (g * factor).toInt().coerceIn(0, 255),
            (b * factor).toInt().coerceIn(0, 255)
        )
    }

    /**
     * Update theme based on system changes
     */
    fun onConfigurationChanged(newConfig: Configuration) {
        val newTheme = getCurrentTheme()
        _currentTheme.value = newTheme

        // Notify all registered theme listeners
        notifyThemeListeners(newTheme)
    }

    private val themeListeners = mutableListOf<(ThemeData) -> Unit>()

    /**
     * Register theme change listener
     */
    fun addThemeListener(listener: (ThemeData) -> Unit) {
        themeListeners.add(listener)
    }

    /**
     * Remove theme change listener
     */
    fun removeThemeListener(listener: (ThemeData) -> Unit) {
        themeListeners.remove(listener)
    }

    /**
     * Notify all theme listeners of changes
     */
    private fun notifyThemeListeners(theme: ThemeData) {
        themeListeners.forEach { listener ->
            try {
                listener(theme)
            } catch (e: Exception) {
                android.util.Log.e("Theme", "Theme listener error", e)
            }
        }
    }

    /**
     * Apply theme to specific view
     */
    fun applyThemeToView(view: android.view.View, theme: ThemeData = getCurrentTheme()) {
        when (view) {
            is android.widget.TextView -> {
                view.setTextColor(theme.labelColor)
                view.textSize = theme.labelTextSize
            }
            is android.widget.Button -> {
                view.setTextColor(theme.labelColor)
                view.setBackgroundColor(theme.keyColor)
            }
            else -> {
                view.setBackgroundColor(theme.backgroundColor)
            }
        }
    }
}