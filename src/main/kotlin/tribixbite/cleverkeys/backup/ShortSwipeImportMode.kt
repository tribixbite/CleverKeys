package tribixbite.cleverkeys.backup

/**
 * UI radio choice for short-swipe import:
 * - SKIP: ignore the file's short-swipe section.
 * - MERGE: additive — file entries fill gaps; existing mappings preserved.
 *   Default in the SAF preview UI.
 * - REPLACE: destructive — wipe existing mappings, install the file's set.
 */
enum class ShortSwipeImportMode { SKIP, MERGE, REPLACE }
