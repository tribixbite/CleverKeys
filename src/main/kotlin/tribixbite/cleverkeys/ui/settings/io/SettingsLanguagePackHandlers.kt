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
 * Get display name for language code.
 */
internal fun SettingsActivity.getLanguageDisplayName(code: String): String {
    return when (code) {
        "none" -> "None"
        "en" -> "English"
        "es" -> "Spanish (Español)"
        "fr" -> "French (Français)"
        "de" -> "German (Deutsch)"
        "pt" -> "Portuguese (Português)"
        "it" -> "Italian (Italiano)"
        "ru" -> "Russian (Русский)"
        "nl" -> "Dutch (Nederlands)"
        "pl" -> "Polish (Polski)"
        "sv" -> "Swedish (Svenska)"
        "da" -> "Danish (Dansk)"
        "no" -> "Norwegian (Norsk)"
        "fi" -> "Finnish (Suomi)"
        "cs" -> "Czech (Čeština)"
        "hu" -> "Hungarian (Magyar)"
        "tr" -> "Turkish (Türkçe)"
        "el" -> "Greek (Ελληνικά)"
        "ro" -> "Romanian (Română)"
        "uk" -> "Ukrainian (Українська)"
        "hr" -> "Croatian (Hrvatski)"
        "sk" -> "Slovak (Slovenčina)"
        "sl" -> "Slovenian (Slovenščina)"
        "bg" -> "Bulgarian (Български)"
        "mk" -> "Macedonian (Македонски)"
        "he" -> "Hebrew (עברית)"
        "ca" -> "Catalan (Català)"
        "eu" -> "Basque (Euskara)"
        "gl" -> "Galician (Galego)"
        // Downloadable-only language packs (the full downloadable set also covers
        // nl/ru/uk/el/bg/mk/he/tr above plus the bundled-language and en-variant zips —
        // see the GitHub `langpacks` release / scripts/dictionaries/).
        "id" -> "Indonesian (Bahasa Indonesia)"
        "ms" -> "Malay (Bahasa Melayu)"
        "sw" -> "Swahili (Kiswahili)"
        "tl" -> "Tagalog (Filipino)"
        else -> code.uppercase()
    }
}

internal fun SettingsActivity.importLanguagePack() {
    languagePackImportStatus = null
    try {
        languagePackImportLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
    } catch (e: Exception) {
        Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

internal fun SettingsActivity.performLanguagePackImport(uri: Uri) {
    val _self = this
    lifecycleScope.launch {
        try {
            val manager = LanguagePackManager.getInstance(_self)
            when (val result = manager.importLanguagePack(uri)) {
                is ImportResult.Success -> {
                    languagePackImportStatus = "Imported: ${result.manifest.name} (${result.manifest.wordCount} words)"
                    refreshInstalledLanguagePacks()
                    refreshAvailableSecondaryLanguages()
                    Toast.makeText(
                        _self,
                        "Language pack imported: ${result.manifest.name}",
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
                    languagePackImportStatus = "Error: ${result.message}"
                    Toast.makeText(
                        _self,
                        "Import failed: ${result.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            languagePackImportStatus = "Error: ${e.message}"
            Toast.makeText(_self, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(_self, "Language pack deleted", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(_self, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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
