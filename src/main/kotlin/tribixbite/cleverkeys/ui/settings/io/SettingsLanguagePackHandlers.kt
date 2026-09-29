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
import tribixbite.cleverkeys.LanguageDisplayNames
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.langpack.ImportResult
import tribixbite.cleverkeys.langpack.LanguagePackManager
import tribixbite.cleverkeys.swipe.CtcInstalledPacks

/**
 * Detect available V2 binary dictionaries for secondary language selection.
 * Scans assets/dictionaries/ for *_enhanced.bin files.
 *
 * @return List of language codes (e.g., ["es", "fr", "de"])
 */
internal fun SettingsActivity.detectAvailableV2Dictionaries(): List<String> {
    val languages = mutableSetOf<String>()
    try {
        // Bundled dictionaries in assets
        val files = assets.list("dictionaries") ?: emptyArray()
        for (file in files) {
            if (file.endsWith("_enhanced.bin")) {
                val langCode = file.removeSuffix("_enhanced.bin")
                // v1.1.93: Include ALL languages including English
                // UI already filters out primary language from secondary options
                if (langCode.length in 2..3) {
                    languages.add(langCode)
                }
            }
        }

        // Installed language packs
        val packManager = LanguagePackManager.getInstance(this)
        packManager.getInstalledPacks().forEach { pack ->
            languages.add(pack.code)
        }

        android.util.Log.i(SettingsActivity.TAG, "Available V2 dictionaries: $languages")
    } catch (e: Exception) {
        android.util.Log.e(SettingsActivity.TAG, "Failed to detect V2 dictionaries", e)
    }
    return languages.sorted()
}

internal fun SettingsActivity.refreshAvailableSecondaryLanguages() {
    availableSecondaryLanguages = detectAvailableV2Dictionaries()
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
        Toast.makeText(this, getString(R.string.common_file_picker_failed, e.message.orEmpty()), Toast.LENGTH_SHORT).show()
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
                    // The manager's reason text is not localized yet (domain layer).
                    // TODO(i18n): typed ImportResult.Error reasons → string resources.
                    languagePackImportStatus = LanguagePackImportStatus.Failed(result.message)
                    Toast.makeText(
                        _self,
                        getString(R.string.common_import_failed_detail, result.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            val detail = e.message ?: getString(R.string.common_unknown_error)
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
            Toast.makeText(
                _self,
                getString(R.string.common_delete_failed_detail, e.message ?: getString(R.string.common_unknown_error)),
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
        installedLanguagePacks = manager.getInstalledPacks()
    } catch (e: Exception) {
        installedLanguagePacks = emptyList()
    }
}
