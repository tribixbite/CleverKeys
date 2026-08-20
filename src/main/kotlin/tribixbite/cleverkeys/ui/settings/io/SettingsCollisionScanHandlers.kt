package tribixbite.cleverkeys.ui.settings.io

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tribixbite.cleverkeys.ContractionCollisionScanner
import tribixbite.cleverkeys.SettingsActivity

/**
 * Re-scans for cross-language contraction collisions whenever the active language set changes.
 *
 * ## Why this hangs off language selection
 *
 * A REPLACE-mode contraction key has no reading of its own only in ITS OWN language. With two
 * languages active the merged map can rewrite a real word of the other one — fr+en typing French
 * `dont` used to produce `don't`. Bundled languages are covered by shipped
 * `contraction_collisions_<lang>.json` sidecars; an IMPORTED pack cannot have one, because its
 * contraction file and dictionary arrive on the device long after the build.
 *
 * Language selection is the right moment to close that gap: it is the event that decides which
 * languages are active, it happens in Settings where reading a lexicon is affordable, and it is
 * the one point where the user is present to be told what changed. See
 * [ContractionCollisionScanner]'s KDoc for why import-time and per-keystroke were both rejected.
 *
 * All four selectors call this — primary, secondary, and both quick-toggle alternates. The
 * alternates matter as much as the main pair: a toggle key swaps the active language at runtime
 * with no trip through Settings, so a combination that is only ever reached by toggling would
 * otherwise never be scanned.
 */
internal fun SettingsActivity.rescanContractionCollisions() {
    val _self = this
    // Snapshot the selections on the main thread — they are Compose state and must not be read
    // from the IO dispatcher.
    val languages = setOf(
        primaryLanguage, secondaryLanguage, primaryLanguageAlt, secondaryLanguageAlt,
    )
    lifecycleScope.launch {
        val report = withContext(Dispatchers.IO) {
            runCatching { ContractionCollisionScanner.scan(_self, languages) }.getOrNull()
        } ?: return@launch // a failed scan degrades to "no extra protection", never to a crash

        withContext(Dispatchers.IO) { ContractionCollisionScanner.cache(_self, report) }

        // Only an imported pack's collisions are news. The bundled ones are already handled by
        // the shipped sidecars and were handled before the user touched anything, so announcing
        // them would be noise that trains people to dismiss the dialog unread.
        if (report.hasPackCollisions) {
            collisionWarningKeyCount = report.packCollisions.size
            collisionWarningExamples = report.examples
            collisionWarningLanguages = report.scannedLanguages.sorted().joinToString(", ")
            showCollisionWarningDialog = true
        }
    }
}
