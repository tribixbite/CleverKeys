package tribixbite.cleverkeys

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import android.widget.Toast
import tribixbite.cleverkeys.clipboard.PrivateCopyIntentParser
import tribixbite.cleverkeys.clipboard.PrivateCopyRateLimiter

/**
 * #156 Private copy — entry point B: the exported `ACTION_PROCESS_TEXT` selection-toolbar target.
 *
 * Lets the user copy selected text from ANY app whose selection toolbar honors PROCESS_TEXT
 * (API 23+) straight into CleverKeys' private clipboard, WITHOUT the text ever reaching the
 * Android OS clipboard. CleverKeys need not be the active keyboard.
 *
 * SECURITY POSTURE (design §6): exported BY DESIGN but `android:enabled="false"` in the manifest
 * until the user opts in via Settings → Clipboard → "Private copy in other apps". Dataflow is
 * strictly INBOUND: text in → local DB row + toast → `finish()` with `RESULT_CANCELED`. We NEVER
 *   - return a result (no `setResult(RESULT_OK)`) → the host app's text/selection is never mutated,
 *     for both PROCESS_TEXT (editable) and PROCESS_TEXT_READONLY (read-only) launches;
 *   - read `intent.clipData`, `EXTRA_STREAM`, a URI, or any extra other than `EXTRA_PROCESS_TEXT`
 *     (all intent reading is delegated to the pure-JVM [PrivateCopyIntentParser]) → the classic
 *     "exported activity coerced into opening attacker URIs" class is structurally closed.
 *
 * Provenance ([getCallingPackage]) is kernel-attested and recorded as `source_package`; a launch
 * without `forResult` (which the real toolbar never does) is recorded as `direct-launch`.
 *
 * `Theme.NoDisplay` requires `finish()` before `onResume()` — we finish inside [onCreate].
 */
class PrivateCopyProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            handle()
        } catch (e: Exception) {
            // Defensive: a hostile or malformed launch must never crash-loop the process.
            Log.w(TAG, "Private copy PROCESS_TEXT failed: ${e.message}")
        } finally {
            // ALWAYS finish before onResume (Theme.NoDisplay contract) and NEVER setResult(RESULT_OK):
            // default RESULT_CANCELED + null data leaves the host app's text untouched.
            finish()
        }
    }

    private fun handle() {
        // Config-init trap: Config.globalConfig() THROWS on a cold start. This activity is a valid
        // cold-start entry point of the process, so initialize the global config first, mirroring
        // the standalone-activity precedent (SwipeCalibrationActivity:124-126).
        if (Config.globalConfigOrNull() == null) {
            val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
            Config.initGlobalConfig(prefs, resources, null, false)
        }

        // Guard 1: device unlocked — ClipboardDatabase lives in Credential-Encrypted storage, which
        // is inaccessible before first unlock. `directBootAware="false"` should already prevent a
        // pre-unlock launch; this is defense in depth against a stale resolver cache.
        if (!isUserUnlocked()) {
            Log.w(TAG, "Private copy ignored: device not unlocked")
            return
        }

        // Guard 2: feature pref enabled. The component-disable is the real gate (design §6.6); this
        // defensive double-check ensures a stale resolver cache can't bypass the user's opt-out.
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        if (!prefs.getBoolean(PREF_TOOLBAR_ENABLED, false)) {
            Log.w(TAG, "Private copy ignored: feature disabled by pref")
            return
        }

        // Parse + validate via the pure-JVM parser (the ONLY intent-reading code). READONLY is
        // irrelevant to behavior — we only ever read the text extra, never transform/return it.
        val maxBytes = Config.globalConfig().clipboard_max_item_size_kb * 1024
        val processText = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        val result = PrivateCopyIntentParser.parse(intent?.action, processText, maxBytes)
        if (result !is PrivateCopyIntentParser.Result.Accept) {
            Log.w(TAG, "Private copy rejected: ${(result as PrivateCopyIntentParser.Result.Reject).reason}")
            return
        }

        // Rate limit per calling package (anti-annoyance; not a security boundary — design §6.5).
        // getCallingPackage() is non-null and kernel-attested only for startActivityForResult
        // launches (which the real selection toolbar uses); "direct-launch" otherwise.
        val callerPkg = callingPackage ?: DIRECT_LAUNCH
        if (!rateLimiter.tryAcquire(callerPkg)) {
            Log.w(TAG, "Private copy rate-limited for caller: $callerPkg")
            return  // Drop silently — no toast, don't give a flooding app a UI channel.
        }

        // Store privately. Uses applicationContext so the singleton service outlives this activity.
        // NEVER touches the OS clipboard (privateCopy → addPrivateClip → rewriteOsClipboard=false).
        val stored = ClipboardHistoryService.privateCopy(applicationContext, result.text, callerPkg)
        if (stored) {
            // Activities may toast (the Android-13 IME toast suppression does not apply here).
            Toast.makeText(applicationContext, R.string.private_copy_toast, Toast.LENGTH_SHORT).show()
        } else {
            Log.w(TAG, "Private copy: service unavailable, entry not stored")
        }
    }

    private fun isUserUnlocked(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val userManager = getSystemService(Context.USER_SERVICE) as? UserManager
            userManager?.isUserUnlocked ?: true
        } else {
            true // Pre-N devices have no Direct Boot.
        }
    }

    companion object {
        private const val TAG = "PrivateCopyPT"

        /** Pref that gates + reflects the component-enabled state (default false — opt-in). */
        const val PREF_TOOLBAR_ENABLED = "clipboard_private_copy_toolbar_enabled"

        /** Recorded as `source_package` when launched without `forResult` (a strong injection tell). */
        private const val DIRECT_LAUNCH = "direct-launch"

        /** Process-lifetime rate limiter shared across all launches (design §6.5). */
        private val rateLimiter = PrivateCopyRateLimiter()
    }
}
