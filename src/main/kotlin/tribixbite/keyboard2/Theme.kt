package tribixbite.keyboard2

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface

/**
 * Minimal Theme stub to resolve dependencies
 * TODO: Implement full functionality from Theme.kt.bak
 */
data class ThemeData(
    val keyColor: Int = Color.WHITE,
    val keyBorderColor: Int = Color.GRAY,
    val labelColor: Int = Color.BLACK,
    val backgroundColor: Int = Color.LTGRAY,
    val labelTextSize: Float = 16f,
    val isDarkMode: Boolean = false,
    val keyActivatedColor: Int = Color.BLUE,
    val suggestionTextColor: Int = Color.BLACK,
    val suggestionBackgroundColor: Int = Color.WHITE,
    val swipeTrailColor: Int = Color.CYAN
)

object Theme {
    fun getSystemThemeData(context: Context): ThemeData {
        return ThemeData()
    }

    fun getKeyFont(context: Context): Typeface {
        return Typeface.DEFAULT
    }
}