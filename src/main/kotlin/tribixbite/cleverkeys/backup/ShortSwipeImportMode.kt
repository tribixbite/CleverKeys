package tribixbite.cleverkeys.backup

/**
 * UI radio choice for short-swipe import:
 * - SKIP: ignore the file's short-swipe section.
 * - MERGE: file entries are added on top of the existing set — on a key+direction
 *   collision the IMPORTED mapping wins; non-colliding existing mappings survive
 *   (G-3, maintainer decision 2026-09-08). Collisions are not silent: the preview
 *   dialog renders them as `changed` rows of [ShortSwipeDiff] before the user
 *   applies. Default in the SAF preview UI.
 * - REPLACE: destructive — wipe existing mappings, install the file's set.
 */
enum class ShortSwipeImportMode { SKIP, MERGE, REPLACE }
