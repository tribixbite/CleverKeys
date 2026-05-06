package tribixbite.cleverkeys.clipboard.sanitize

import android.content.Context
import android.net.Uri
import android.util.Log
import tribixbite.cleverkeys.Config
import java.io.File

/**
 * Resolves the active sanitization ruleset from the three Config toggles
 * + the bundled assets + the user-supplied custom file.
 *
 * Stateless apart from a private cache to avoid re-parsing on every clipboard
 * insert. Cache invalidates on `rebuild()` which the settings UI calls when
 * any toggle or the custom-rules URI changes.
 */
class SanitizationConfig(private val context: Context) {

    @Volatile private var cached: UrlSanitizer? = null

    /**
     * Returns the current sanitizer. If all toggles are off, returns a
     * no-op sanitizer that returns input unchanged in O(text-length).
     */
    fun sanitizer(): UrlSanitizer {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val s = build()
            cached = s
            return s
        }
    }

    /** Force the next call to sanitizer() to rebuild from current Config + custom file. */
    fun rebuild() { cached = null }

    private fun build(): UrlSanitizer {
        val cfg = try { Config.globalConfig() } catch (_: Exception) {
            // Config not yet initialised (e.g. during early service start). No-op.
            return RulesetUrlSanitizer(Ruleset(emptyMap()))
        }

        val sanitize = cfg.clipboard_sanitize_links_enabled
        val embed = cfg.clipboard_embed_enrich_enabled
        val custom = cfg.clipboard_custom_rules_enabled
        val customUri = cfg.clipboard_custom_rules_uri

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
