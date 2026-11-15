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

    /**
     * The current mappings as JSON, for a later [restore] when a full restore fails after the
     * short swipes were already replaced (review 2026-11-14 M6). Null = nothing to restore with.
     */
    suspend fun snapshot(): String? = null

    /** Make the mappings exactly [snapshot] again — an empty snapshot clears them. */
    suspend fun restore(snapshot: String) {}
}

class RealShortSwipeImporter(
    private val manager: ShortSwipeCustomizationManager,
) : ShortSwipeImporter {
    override suspend fun importFromJson(json: String, merge: Boolean): Int =
        manager.importFromJson(json, merge = merge)

    // Load first (follow-up review 2026-11-15): exportToJson reads the in-memory cache, which is
    // empty in a process that has not loaded the mappings yet (Settings → Restore straight after a
    // cold start). That empty snapshot made a failed restore ERASE the user's short swipes.
    override suspend fun snapshot(): String {
        manager.loadMappings()
        return manager.exportToJson()
    }

    override suspend fun restore(snapshot: String) {
        manager.restoreFromJson(snapshot)
    }
}
