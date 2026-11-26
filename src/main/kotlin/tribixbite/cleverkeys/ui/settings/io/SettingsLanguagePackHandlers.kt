package tribixbite.cleverkeys.ui.settings.io

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.Defaults
import tribixbite.cleverkeys.DirectBootAwarePreferences
import tribixbite.cleverkeys.LanguageAvailability
import tribixbite.cleverkeys.LanguageDisplayNames
import tribixbite.cleverkeys.LanguagePrefs
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.ResourcesResultText
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.langpack.ImportResult
import tribixbite.cleverkeys.langpack.LanguagePackManager
import tribixbite.cleverkeys.swipe.CtcInstalledPacks
import tribixbite.cleverkeys.ui.settings.updateConfigFromSettings

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
            .languages.filterNotNull().distinct()
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

/**
 * Import a language-pack ZIP. The import runs in [SettingsImports.languagePack], not this
 * activity's scope: a rotation cancelled it, and the installed pack was then reported as a
 * failure, without the list refresh (pre-tag review 2026-11-25 S2). The result is shown by
 * whichever Settings activity is in front when it ends.
 */
internal fun SettingsActivity.performLanguagePackImport(uri: Uri) {
    val appContext = applicationContext
    val started = SettingsImports.languagePack.start(null) {
        val followUp = catchingFailures({
            val result = LanguagePackManager.getInstance(appContext).importLanguagePack(uri)
            followUp { showLanguagePackImportResult(result) }
        }) { e ->
            android.util.Log.e(SettingsActivity.TAG, "Language pack import failed", e)
            followUp {
                val detail = ioFailureText(e)
                languagePackImportStatus = LanguagePackImportStatus.Failed(detail)
                Toast.makeText(this, getString(R.string.common_import_failed_detail, detail), Toast.LENGTH_SHORT).show()
            }
        }
        ImportTracker.Outcome(null, followUp)
    }
    if (!started) {
        // A second pick (or a shared file) while a pack import or removal runs: say so rather
        // than drop it silently (todo 2026-11-26; it was a log line only).
        android.util.Log.w(SettingsActivity.TAG, "Language pack operation already running; $uri not imported")
        showLanguagePackBusy()
    }
}

/** Another language-pack import or removal is still running; this one was not started. */
private fun SettingsActivity.showLanguagePackBusy() {
    Toast.makeText(this, getString(R.string.multilang_pack_busy), Toast.LENGTH_SHORT).show()
}

/** The finished import's [result] on this (the current) Settings activity. */
private fun SettingsActivity.showLanguagePackImportResult(result: ImportResult) {
    val _self = this
    when (result) {
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
            // verdict, so a rotation cancelling this measurement costs only its time.
            // See CtcImportedPackSupport for what is being measured and why.
            val code = result.manifest.code
            lifecycleScope.launch { withContext(Dispatchers.IO) { CtcInstalledPacks.evaluateNow(_self, code) } }
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
}

/**
 * Remove the installed pack [code]. The removal runs in [SettingsImports.languagePack] like the
 * import, not this activity's scope: a rotation cancelled it, leaving the files deleted while the
 * list, the secondary-language choices and the toast were skipped (todo 2026-11-26, the S2
 * leftover). One pack operation at a time: a removal asked for while an import runs is refused
 * with a message.
 */
internal fun SettingsActivity.deleteLanguagePack(code: String) {
    val appContext = applicationContext
    // The starting activity's preferences: the work resets the language choices there itself.
    val languagePrefs = prefs
    val started = SettingsImports.languagePack.start(null) {
        val followUp = catchingFailures({
            val deleted = LanguagePackManager.getInstance(appContext).deletePack(code)
            var resets = emptyMap<String, String>()
            if (deleted) {
                // Drop the CTC eligibility verdict with the pack. A later reimport of the same
                // language could otherwise restore identical bytes whose mtime happens to match
                // the stored fingerprint and inherit a stale measurement.
                CtcInstalledPacks.invalidate(appContext, code)
                // A secondary choice naming the removed language goes back to "none", here and
                // not in the follow-up, so the keyboard drops it even when Settings was left: it
                // kept the language as its active secondary otherwise (Saga 2026-11-26).
                resets = RemovedPackLanguagePrefs.resets(
                    code,
                    stillAvailable = LanguageAvailability.availableLanguages(appContext),
                    secondary = Config.safeGetString(languagePrefs, LanguagePrefs.SECONDARY, Defaults.SECONDARY_LANGUAGE),
                    secondaryAlt = Config.safeGetString(
                        languagePrefs, LanguagePrefs.SECONDARY_ALT, Defaults.SECONDARY_LANGUAGE_ALT
                    ),
                )
                if (resets.isNotEmpty()) {
                    // commit: the keyboard's preference listener reloads its languages from it.
                    languagePrefs.edit().apply { resets.forEach { (key, value) -> putString(key, value) } }.commit()
                    if (LanguagePrefs.SECONDARY in resets) {
                        // As saveSetting does for a serving key: other screens refresh their lists.
                        LocalBroadcastManager.getInstance(appContext)
                            .sendBroadcast(Intent("tribixbite.cleverkeys.LANGUAGE_CHANGED"))
                    }
                }
            }
            followUp {
                if (deleted) {
                    // The shown choices follow the stored ones at once (the block under the
                    // secondary dropdown hides with "None").
                    resets[LanguagePrefs.SECONDARY]?.let { secondaryLanguage = it }
                    resets[LanguagePrefs.SECONDARY_ALT]?.let { secondaryLanguageAlt = it }
                    if (resets.isNotEmpty()) updateConfigFromSettings()
                    refreshInstalledLanguagePacks()
                    refreshAvailableSecondaryLanguages()
                    Toast.makeText(this, getString(R.string.multilang_pack_deleted), Toast.LENGTH_SHORT).show()
                }
            }
        }) { e ->
            android.util.Log.e(SettingsActivity.TAG, "Language pack delete failed", e)
            followUp {
                Toast.makeText(
                    this,
                    getString(R.string.common_delete_failed_detail, ioFailureText(e)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        ImportTracker.Outcome(null, followUp)
    }
    if (!started) {
        android.util.Log.w(SettingsActivity.TAG, "Language pack operation already running; $code not removed")
        showLanguagePackBusy()
    }
}

/**
 * The language choices a pack removal must reset (pure, `RemovedPackLanguagePrefsTest`): the
 * secondary and the quick-toggle secondary, back to "none", when they name the removed language
 * [code] and it is not in [stillAvailable] (a bundled dictionary can still serve it).
 *
 * # TODO: the primary and its quick-toggle alternate have no "none"; a removed primary's pack
 * still leaves that choice stored (its dropdown then shows the first option).
 */
internal object RemovedPackLanguagePrefs {
    fun resets(
        code: String,
        stillAvailable: Collection<String>,
        secondary: String?,
        secondaryAlt: String?,
    ): Map<String, String> {
        if (code in stillAvailable) return emptyMap()
        return buildMap {
            if (secondary == code) put(LanguagePrefs.SECONDARY, LanguageDisplayNames.NONE)
            if (secondaryAlt == code) put(LanguagePrefs.SECONDARY_ALT, LanguageDisplayNames.NONE)
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
