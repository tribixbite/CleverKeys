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

    /**
     * One scan of the device's dictionaries. [packs] are installed packs with a parseable
     * manifest (what the pickers list); [packsWithDictionary] are those whose dictionary file
     * exists (what [isAvailable] has always required).
     */
    private data class Snapshot(
        val bundled: Set<String>,
        val packs: Set<String>,
        val packsWithDictionary: Set<String>,
    )

    /**
     * Per-process cache (2026-10-08 audit). A scan is `assets.list` plus a directory walk and
     * a manifest parse per pack — callers run on the main thread (Layout Manager's first
     * composition, the keyboard's message on every bound-layout switch), so it is done once
     * and reused. Bundled dictionaries cannot change while the process lives; installed packs
     * change only through [LanguagePackManager.importLanguagePack] / [LanguagePackManager.deletePack],
     * which call [invalidate]. Settings and the keyboard share one process, so one cache serves
     * both. A failed scan is not cached.
     */
    @Volatile
    private var cached: Snapshot? = null

    /** Drop the cached scan; the next query rescans. Called after every pack import/delete. */
    fun invalidate() {
        cached = null
    }

    private fun snapshot(context: Context): Snapshot {
        cached?.let { return it }
        val manager = LanguagePackManager.getInstance(context)
        val packs = manager.getInstalledPacks().map { it.code }.toSet()
        return Snapshot(
            bundled = bundledLanguages(context),
            packs = packs,
            packsWithDictionary = packs.filterTo(mutableSetOf()) { manager.isInstalled(it) },
        ).also { cached = it }
    }

    /** Sorted language codes with a bundled dictionary or an installed pack. */
    fun availableLanguages(context: Context): List<String> = try {
        snapshot(context).let { (it.bundled + it.packs).sorted() }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to list available dictionaries", e)
        emptyList()
    }

    /** True when [code] has a bundled dictionary or an installed language pack. */
    fun isAvailable(context: Context, code: String): Boolean = try {
        snapshot(context).let { code in it.bundled || code in it.packsWithDictionary }
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
