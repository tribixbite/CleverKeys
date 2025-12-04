package tribixbite.cleverkeys

import android.annotation.TargetApi
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import tribixbite.cleverkeys.prefs.LayoutsPreference

/**
 * Manages IME subtypes, locale layouts, and extra keys.
 *
 * This class centralizes logic for:
 * - Getting enabled IME subtypes for this keyboard
 * - Extracting extra keys (accents) from subtypes
 * - Determining default subtype based on system settings
 * - Refreshing locale layout based on current subtype
 * - Managing extra keys configuration
 *
 * Responsibilities:
 * - Query InputMethodManager for enabled subtypes
 * - Parse subtype extra values (default_layout, extra_keys, script)
 * - Update Config with merged extra keys from all enabled subtypes
 * - Determine locale-specific default layout
 * - Handle Android version differences (API 12+, 24+)
 *
 * NOT included (remains in CleverKeysService):
 * - InputMethodService lifecycle methods
 * - LayoutManager updates (caller updates after getting layout)
 * - Configuration persistence (SubtypeManager reads/writes to Config)
 *
 * This class is extracted from CleverKeysService.java for better separation of concerns
 * and testability (v1.32.365).
 */
class SubtypeManager(private val context: Context) {

    @JvmField
    val inputMethodManager: InputMethodManager =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    @Deprecated("Use inputMethodManager instead", ReplaceWith("inputMethodManager"))
    private val imm: InputMethodManager get() = inputMethodManager

    /**
     * Gets list of enabled subtypes for this keyboard.
     *
     * @return List of enabled subtypes, or empty list if none found
     */
    fun getEnabledSubtypes(): List<InputMethodSubtype> {
        val pkg = context.packageName
        for (imi in imm.enabledInputMethodList) {
            if (imi.packageName == pkg) {
                return imm.getEnabledInputMethodSubtypeList(imi, true)
            }
        }
        return emptyList()
    }

    /**
     * Extracts extra keys from a subtype.
     *
     * @param subtype Input method subtype
     * @return ExtraKeys parsed from subtype, or EMPTY if none
     */
    @TargetApi(12)
    fun extra_keys_of_subtype(subtype: InputMethodSubtype): ExtraKeys {
        val extraKeys = subtype.getExtraValueOf("extra_keys")
        val script = subtype.getExtraValueOf("script")
        return if (extraKeys != null) {
            ExtraKeys.parse(script, extraKeys)
        } else {
            ExtraKeys.EMPTY
        }
    }

    /**
     * Refreshes accent options by merging extra keys from all enabled subtypes.
     *
     * @param enabled_subtypes List of enabled subtypes
     * @return Merged ExtraKeys from all subtypes
     */
    fun refreshAccentsOption(enabled_subtypes: List<InputMethodSubtype>): ExtraKeys {
        val extraKeys = enabled_subtypes.map { extra_keys_of_subtype(it) }
        return ExtraKeys.merge(extraKeys)
    }

    /**
     * Gets the default subtype based on current system settings.
     * On Android 7.0+ (API 24), matches by language tag to avoid random selection.
     *
     * @param enabled_subtypes List of enabled subtypes
     * @return Default subtype, or null if none found
     */
    @TargetApi(12)
    fun defaultSubtypes(enabled_subtypes: List<InputMethodSubtype>): InputMethodSubtype? {
        if (Build.VERSION.SDK_INT < 24) {
            return imm.currentInputMethodSubtype
        }

        // Android might return a random subtype, for example, the first in the
        // list alphabetically.
        val currentSubtype = imm.currentInputMethodSubtype ?: return null

        for (s in enabled_subtypes) {
            if (s.languageTag == currentSubtype.languageTag) {
                return s
            }
        }
        return null
    }

    /**
     * Refreshes subtype settings and returns the appropriate default layout.
     * Updates config with voice typing availability and extra keys.
     *
     * @param config Config to update with extra keys
     * @param resources Resources for loading layouts
     * @return Default layout for current subtype, or null to use fallback
     */
    fun refreshSubtype(config: Config, resources: Resources): KeyboardData? {
        config.shouldOfferVoiceTyping = true
        var defaultLayout: KeyboardData? = null
        config.extra_keys_subtype = null

        if (Build.VERSION.SDK_INT >= 12) {
            val enabledSubtypes = getEnabledSubtypes()
            val subtype = defaultSubtypes(enabledSubtypes)

            if (subtype != null) {
                val s = subtype.getExtraValueOf("default_layout")
                if (s != null) {
                    defaultLayout = LayoutsPreference.layoutOfString(resources, s)
                }
                config.extra_keys_subtype = refreshAccentsOption(enabledSubtypes)
            }
        }

        return defaultLayout
    }

    /** @deprecated Use inputMethodManager field instead */
    @Deprecated("Use inputMethodManager field instead", ReplaceWith("inputMethodManager"))
    fun getInputMethodManager(): InputMethodManager = inputMethodManager

    companion object {
        private const val TAG = "SubtypeManager"
    }
}
