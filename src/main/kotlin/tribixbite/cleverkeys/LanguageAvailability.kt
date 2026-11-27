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

    /**
     * Bumped by every [invalidate] (audit B5, 2026-11-26): a scan that an import or delete
     * overtook (it invalidated while the scan ran) must not store its stale result.
     */
    private val generation = java.util.concurrent.atomic.AtomicLong()

    /** Drop the cached scan; the next query rescans. Called after every pack import/delete. */
    fun invalidate() {
        generation.incrementAndGet()
        cached = null
    }

    private fun snapshot(context: Context): Snapshot {
        cached?.let { return it }
        val startedAt = generation.get()
        val manager = LanguagePackManager.getInstance(context)
        // Asked BEFORE the scan: asked after it, an unlock landing in between would cache the
        // empty pre-unlock scan as readable (follow-up review 2026-11-15).
        val readable = manager.isPackStorageReadable()
        val packs = manager.getInstalledPacks().map { it.code }.toSet()
        val snapshot = Snapshot(
            bundled = bundledLanguages(context),
            packs = packs,
            packsWithDictionary = packs.filterTo(mutableSetOf()) { manager.isInstalled(it) },
        )
        // Before the first unlock the packs directory cannot be listed and the scan sees none.
        // Caching that kept the empty list for the whole process (review 2026-11-14): a false
        // "no dictionary" warning and pickers without the installed packs until a restart.
        if (readable) synchronized(this) { if (generation.get() == startedAt) cached = snapshot }
        return snapshot
    }

    /** Sorted language codes with a bundled dictionary or an installed pack. */
    fun availableLanguages(context: Context): List<String> = try {
        snapshot(context).let { (it.bundled + it.packs).sorted() }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to list available dictionaries", e)
        emptyList()
    }

    /**
     * True when [code] has a bundled dictionary or an installed pack's DICTIONARY file (audit B5,
     * 2026-11-26: whether the manifest parses does not matter here: a pack with a dictionary and
     * a broken manifest serves words, a manifest without its dictionary serves none).
     */
    fun isAvailable(context: Context, code: String): Boolean = try {
        // An empty bundled list is a failed asset scan (a bundled dictionary always exists): unknown.
        val bundled = snapshot(context).bundled
        bundled.isEmpty() || code in bundled || LanguagePackManager.getInstance(context).isInstalled(code)
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
