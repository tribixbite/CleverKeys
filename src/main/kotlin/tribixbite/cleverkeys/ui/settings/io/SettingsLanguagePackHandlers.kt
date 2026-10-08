package tribixbite.cleverkeys.ui.settings.io

import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.Defaults
import tribixbite.cleverkeys.DirectBootAwarePreferences
import tribixbite.cleverkeys.LanguageAvailability
import tribixbite.cleverkeys.LanguageDisplayNames
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.ResourcesResultText
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.langpack.ImportResult
import tribixbite.cleverkeys.langpack.LanguagePackManager
import tribixbite.cleverkeys.swipe.CtcInstalledPacks

/**
 * Detect available V2 binary dictionaries for language selection: the bundled
 * `<code>_enhanced.bin` dictionary assets plus installed language packs. Shared with Layout
 * Manager's per-layout language picker through [LanguageAvailability] (GH #186/#61).
 *
 * @return List of language codes (e.g., ["es", "fr", "de"])
 */
internal fun SettingsActivity.detectAvailableV2Dictionaries(): List<String> {
    val languages = LanguageAvailability.availableLanguages(this)
    android.util.Log.i(SettingsActivity.TAG, "Available V2 dictionaries: $languages")
    return languages
}

internal fun SettingsActivity.refreshAvailableSecondaryLanguages() {
    availableSecondaryLanguages = detectAvailableV2Dictionaries()
}

/**
 * The distinct languages bound to a layout (Layout Manager, GH #186/#61) — the user's entry
 * choice or the layout XML's `language` default, exactly as the keyboard resolves them — for
 * the Multi-Language note that such a binding replaces these settings while its layout is
 * active (2026-10-08 audit). Parsing the few configured layouts is cheap; on failure the note
 * is simply not shown.
 */
internal fun SettingsActivity.refreshBoundLayoutLanguages() {
    boundLayoutLanguages = try {
        tribixbite.cleverkeys.prefs.LayoutsPreference.loadLayoutsWithBindings(resources, prefs)
            .second.filterNotNull().distinct()
    } catch (e: Exception) {
        android.util.Log.w(SettingsActivity.TAG, "Could not read layout language bindings", e)
        emptyList()
    }
}

/**
 * Display name for a dictionary language code in the app's UI language, e.g. "Spanish (Español)"
 * under English and "Spanyol (Español)" under Hungarian; the "none" sentinel is the translated
 * "None". Replaces a hand-written English table (device finding 2026-09-29, fa/hu) — see
 * [LanguageDisplayNames].
 */
internal fun SettingsActivity.getLanguageDisplayName(code: String): String =
    if (code == LanguageDisplayNames.NONE) getString(R.string.common_none)
    else LanguageDisplayNames.displayName(code, resources.configuration.locales[0])

/**
 * Outcome of the last language-pack import, as shown under the Import button.
 *
 * Typed for the same reason as [GifImportStatus]: the section used to colour the line with
 * `status.startsWith("Error")`, which silently breaks the moment the message is translated.
 * The variant decides the colour; [message] is pure, translatable copy.
 */
sealed interface LanguagePackImportStatus {
    val message: String

    /** A successful import. Rendered in the primary colour. */
    data class Imported(override val message: String) : LanguagePackImportStatus

    /** A failed import; [message] is the reason. Rendered in the error colour. */
    data class Failed(override val message: String) : LanguagePackImportStatus
}

internal fun SettingsActivity.importLanguagePack() {
    languagePackImportStatus = null
    try {
        languagePackImportLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
    } catch (e: Exception) {
        toastFilePickerFailed(e)
    }
}

internal fun SettingsActivity.performLanguagePackImport(uri: Uri) {
    val _self = this
    lifecycleScope.launch {
        try {
            val manager = LanguagePackManager.getInstance(_self)
            when (val result = manager.importLanguagePack(uri)) {
                is ImportResult.Success -> {
                    val words = result.manifest.wordCount
                    languagePackImportStatus = LanguagePackImportStatus.Imported(
                        resources.getQuantityString(
                            R.plurals.multilang_pack_imported_status, words, result.manifest.name, words
                        )
                    )
                    refreshInstalledLanguagePacks()
                    refreshAvailableSecondaryLanguages()
                    Toast.makeText(
                        _self,
                        getString(R.string.multilang_pack_imported_toast, result.manifest.name),
                        Toast.LENGTH_SHORT
                    ).show()
                    // Measure the new pack for CTC eligibility now, off the main thread, rather
                    // than leaving it to the first swipe's background scheduler: the user is
                    // here, the file is hot, and the swipe-engine card can then tell them the
                    // truth immediately. Idempotent — the swipe path reads the same cached
                    // verdict. See CtcImportedPackSupport for what is being measured and why.
                    val code = result.manifest.code
                    withContext(Dispatchers.IO) { CtcInstalledPacks.evaluateNow(_self, code) }
                }
                is ImportResult.Error -> {
                    // Typed reason → localized text; the English log text stays in logcat.
                    android.util.Log.w(SettingsActivity.TAG, "Language pack import refused: ${result.message}")
                    val reason = result.failure.render(ResourcesResultText(resources))
                    languagePackImportStatus = LanguagePackImportStatus.Failed(reason)
                    Toast.makeText(
                        _self,
                        getString(R.string.common_import_failed_detail, reason),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Language pack import failed", e)
            val detail = ioFailureText(e)
            languagePackImportStatus = LanguagePackImportStatus.Failed(detail)
            Toast.makeText(_self, getString(R.string.common_import_failed_detail, detail), Toast.LENGTH_SHORT).show()
        }
    }
}

internal fun SettingsActivity.deleteLanguagePack(code: String) {
    val _self = this
    lifecycleScope.launch {
        try {
            val manager = LanguagePackManager.getInstance(_self)
            if (manager.deletePack(code)) {
                // Drop the CTC eligibility verdict with the pack. A later reimport of the same
                // language could otherwise restore identical bytes whose mtime happens to match
                // the stored fingerprint and inherit a stale measurement.
                CtcInstalledPacks.invalidate(_self, code)
                refreshInstalledLanguagePacks()
                refreshAvailableSecondaryLanguages()
                Toast.makeText(_self, getString(R.string.multilang_pack_deleted), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Language pack delete failed", e)
            Toast.makeText(
                _self,
                getString(R.string.common_delete_failed_detail, ioFailureText(e)),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

/**
 * Read an installed pack's NOTICE.txt off the main thread and hand it to [onLoaded] on the
 * main thread: the text (capped by [LanguagePackManager.readNotice]) or null when the pack kept
 * none — packs built before 2026-09-27, or imported by an app version that dropped the member.
 */
internal fun SettingsActivity.loadLanguagePackNotice(code: String, onLoaded: (String?) -> Unit) {
    val manager = LanguagePackManager.getInstance(this)
    lifecycleScope.launch {
        val text = withContext(Dispatchers.IO) { manager.readNotice(code) }
        onLoaded(text)
    }
}

internal fun SettingsActivity.refreshInstalledLanguagePacks() {
    try {
        val manager = LanguagePackManager.getInstance(this)
        val packs = manager.getInstalledPacks()
        installedLanguagePacks = packs
        // gh #184: an installed pack the loader refuses (e.g. over the word cap) says so.
        languagePackProblems = packs.mapNotNull { pack ->
            manager.installedDictionaryProblem(pack.code)?.let { pack.code to it }
        }.toMap()
    } catch (e: Exception) {
        installedLanguagePacks = emptyList()
        languagePackProblems = emptyMap()
    }
}
