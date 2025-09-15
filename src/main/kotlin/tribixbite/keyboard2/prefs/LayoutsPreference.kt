package tribixbite.keyboard2.prefs

import android.content.SharedPreferences
import android.content.res.Resources
import tribixbite.keyboard2.KeyboardData

/**
 * Layouts preference management
 */
object LayoutsPreference {
    
    /**
     * Layout interface
     */
    interface Layout {
        fun getKeyboardData(resources: Resources): KeyboardData?
    }
    
    /**
     * System layout
     */
    class SystemLayout : Layout {
        override fun getKeyboardData(resources: Resources): KeyboardData? {
            return KeyboardData.createDefaultQwerty()
        }
    }
    
    /**
     * Named layout
     */
    class NamedLayout(private val name: String) : Layout {
        override fun getKeyboardData(resources: Resources): KeyboardData? {
            return KeyboardData.createDefaultQwerty()
        }
    }
    
    /**
     * Custom layout
     */
    class CustomLayout : Layout {
        override fun getKeyboardData(resources: Resources): KeyboardData? {
            return KeyboardData.createDefaultQwerty()
        }
        
        companion object {
            fun parse(customLayout: String): CustomLayout {
                return CustomLayout()
            }
        }
    }
    
    /**
     * Load layouts from preferences
     */
    fun load_from_preferences(resources: Resources, prefs: SharedPreferences): List<KeyboardData> {
        return listOf(KeyboardData.createDefaultQwerty())
    }
    
    /**
     * Save layouts to preferences
     */
    fun save_to_preferences(editor: SharedPreferences.Editor, layouts: List<Layout>) {
        // Save layouts
    }
}