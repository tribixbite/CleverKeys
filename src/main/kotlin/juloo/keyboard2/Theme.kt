package juloo.keyboard2

import android.graphics.Color

/**
 * Theme management for keyboard appearance
 * Kotlin object with property-based theme access
 */
object Theme {
    
    /**
     * Current theme data
     */
    data class ThemeData(
        val keyColor: Int = Color.GRAY,
        val keyBorderColor: Int = 0xFF444444.toInt(),
        val labelColor: Int = Color.WHITE,
        val backgroundColor: Int = Color.BLACK,
        val labelTextSize: Float = 16f
    )
    
    private var currentTheme = ThemeData()
    
    /**
     * Get current theme
     */
    fun get_current(): ThemeData = currentTheme
    
    /**
     * Set theme
     */
    fun setTheme(theme: ThemeData) {
        currentTheme = theme
    }
    
    /**
     * Default dark theme
     */
    val darkTheme = ThemeData(
        keyColor = 0xFF2B2B2B.toInt(),
        keyBorderColor = 0xFF1A1A1A.toInt(),
        labelColor = Color.WHITE,
        backgroundColor = Color.BLACK,
        labelTextSize = 16f
    )
    
    /**
     * Light theme
     */
    val lightTheme = ThemeData(
        keyColor = Color.LTGRAY,
        keyBorderColor = Color.GRAY,
        labelColor = Color.BLACK,
        backgroundColor = Color.WHITE,
        labelTextSize = 16f
    )
}