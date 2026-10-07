package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import tribixbite.cleverkeys.langpack.LanguagePackManager

/**
 * Which languages have a dictionary on this device: the bundled `assets/dictionaries/
 * <code>_enhanced.bin` dictionaries plus every installed language pack.
 *
 * One implementation for the three places that ask (GH #186/#61): the Multi-Language
 * selectors, Layout Manager's per-layout language picker (which warns about a binding with no
 * dictionary) and the keyboard's layout-switch message.
 */
object LanguageAvailability {
    private const val TAG = "LanguageAvailability"
    private const val DICTIONARY_ASSET_DIR = "dictionaries"
    private const val DICTIONARY_SUFFIX = "_enhanced.bin"

    /** Sorted language codes with a bundled dictionary or an installed pack. */
    fun availableLanguages(context: Context): List<String> {
        val languages = mutableSetOf<String>()
        try {
            languages += bundledLanguages(context)
            LanguagePackManager.getInstance(context).getInstalledPacks().forEach { languages += it.code }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list available dictionaries", e)
        }
        return languages.sorted()
    }

    /** True when [code] has a bundled dictionary or an installed language pack. */
    fun isAvailable(context: Context, code: String): Boolean = try {
        code in bundledLanguages(context) || LanguagePackManager.getInstance(context).isInstalled(code)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to check dictionary for '$code'", e)
        // Unknown is reported as available: a false "not installed" warning would be worse
        // than a missing one, and the dictionary loader reports real failures itself.
        true
    }

    private fun bundledLanguages(context: Context): Set<String> =
        (context.assets.list(DICTIONARY_ASSET_DIR) ?: emptyArray())
            .filter { it.endsWith(DICTIONARY_SUFFIX) }
            .map { it.removeSuffix(DICTIONARY_SUFFIX) }
            // Bundled codes are plain 2-3 letter codes (variant assets are not languages).
            .filter { it.length in 2..3 }
            .toSet()
}
