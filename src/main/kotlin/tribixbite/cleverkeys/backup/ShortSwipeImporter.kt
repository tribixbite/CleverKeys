package tribixbite.cleverkeys.backup

import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager

/**
 * Injection seam for short-swipe importing. Production uses the real
 * `ShortSwipeCustomizationManager`; MockK tests inject a stub.
 *
 * Returns the number of mappings successfully imported.
 */
interface ShortSwipeImporter {
    suspend fun importFromJson(json: String, merge: Boolean): Int
}

class RealShortSwipeImporter(
    private val manager: ShortSwipeCustomizationManager,
) : ShortSwipeImporter {
    override suspend fun importFromJson(json: String, merge: Boolean): Int =
        manager.importFromJson(json, merge = merge)
}
