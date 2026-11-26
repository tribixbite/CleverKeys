package tribixbite.cleverkeys.clipboard.sanitize

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import tribixbite.cleverkeys.ClipboardSanitizeSettings
import tribixbite.cleverkeys.DirectBootAwarePreferences
import java.io.File

/**
 * Resolves the active sanitization ruleset from the three STORED toggles
 * ([ClipboardSanitizeSettings]) + the bundled assets + the user-supplied custom file.
 *
 * The toggles are read from the settings store on every call, never from the in-memory Config
 * (Fable review 2026-11-26): only the keyboard service refreshes Config, so after a restore in a
 * process without the keyboard it built the ruleset from the old toggles. The parsed ruleset is
 * cached per toggle set ([ClipboardSanitizeSettings.rulesetInputs]), so a changed toggle rebuilds
 * it with no broadcast; [rebuild] remains for the custom FILE, whose content the key cannot see.
 *
 * @param storedSettings the settings store; the device-protected app store by default.
 */
class SanitizationConfig(
    private val context: Context,
    private val storedSettings: () -> SharedPreferences = { DirectBootAwarePreferences.get_shared_preferences(context) },
) {

    /** A built sanitizer and the toggle set it was built from. */
    private class Built(val inputs: ClipboardSanitizeSettings, val sanitizer: UrlSanitizer)

    @Volatile private var cached: Built? = null

    /** The sanitize switches as stored now (an in-memory map lookup). */
    fun storedSwitches(): ClipboardSanitizeSettings = ClipboardSanitizeSettings.fromSettings(storedSettings())

    /**
     * Returns the sanitizer for the stored toggles. If all are off, it is a no-op sanitizer that
     * returns input unchanged in O(text-length).
     */
    internal fun sanitizer(): UrlSanitizer = sanitizer(storedSwitches())

    /** The sanitizer for [settings] (a caller that already read them, like `storeClip`). */
    internal fun sanitizer(settings: ClipboardSanitizeSettings): UrlSanitizer {
        val inputs = settings.rulesetInputs
        cached?.takeIf { it.inputs == inputs }?.let { return it.sanitizer }
        synchronized(this) {
            cached?.takeIf { it.inputs == inputs }?.let { return it.sanitizer }
            val built = Built(inputs, build(inputs))
            cached = built
            return built.sanitizer
        }
    }

    /** Force the next call to sanitizer() to rebuild (the custom rules file changed). */
    fun rebuild() { cached = null }

    private fun build(settings: ClipboardSanitizeSettings): UrlSanitizer {
        val sanitize = settings.sanitizeLinks
        val embed = settings.embedEnrich
        val custom = settings.customRules
        val customUri = settings.customRulesUri

        if (!sanitize && !embed && !custom) {
            // Fast path: all toggles off → never touch context.assets at all.
            return RulesetUrlSanitizer(Ruleset(emptyMap()))
        }

        var merged = Ruleset(emptyMap())
        if (sanitize) merged = RulesetParser.merge(merged, loadAsset("url_rules/clearurls.json"))
        if (embed)    merged = RulesetParser.merge(merged, loadAsset("url_rules/embed_enrich.json"))
        if (custom && !customUri.isNullOrEmpty()) {
            loadCustom(customUri)?.let { merged = RulesetParser.merge(merged, it) }
        }

        return RulesetUrlSanitizer(merged)
    }

    private fun loadAsset(path: String): Ruleset = try {
        val json = context.assets.open(path).bufferedReader().use { it.readText() }
        RulesetParser.fromJson(json)
    } catch (e: Exception) {
        // Don't crash the import path — degrade to empty ruleset for this layer.
        Log.e(TAG, "Failed to load bundled asset $path", e)
        Ruleset(emptyMap())
    }

    private fun loadCustom(uriString: String): Ruleset? = try {
        // Two storage modes:
        //  (a) Copied file in app private dir — settings UI copies on import for resilience
        //  (b) SAF content URI grants — read directly from URI (requires persisted permission)
        val customFile = customFile(context)
        if (customFile.exists()) {
            RulesetParser.fromJson(customFile.readText())
        } else {
            val uri = Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                RulesetParser.fromJson(it.readText())
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to load custom rules at $uriString — falling back", e)
        null
    }

    companion object {
        private const val TAG = "SanitizationConfig"

        /**
         * Canonical path for the user's imported custom rules — copied here at
         * import time so we don't depend on the SAF grant remaining valid.
         */
        fun customFile(context: Context): File =
            File(context.filesDir, "url_rules/custom.substitutions.json")
    }
}
