package tribixbite.cleverkeys.ui.settings

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.backup.SETTINGS_DEFAULTS
import tribixbite.cleverkeys.backup.SettingsValidation
import java.io.File

/**
 * F-1/F-2 (comprehensive audit 2026-09-06): "Reset Settings" used
 * `editor.clear()` on the MAIN shared prefs file and re-seeded only ~45
 * settings keys — silently destroying `custom_words_<lang>` /
 * `disabled_words_<lang>` (the user's custom dictionary), `layouts`,
 * `extra_keys*`, per-language prefs, custom themes, AND every migration
 * marker (`margin_prefs_version` — whose loss re-runs the dp→percent
 * margin migration over freshly-written percent values and mangles them).
 *
 * The fix: [SettingsResetPolicy] enumerates and removes ONLY classified
 * settings keys, so everything else in the shared file survives a reset.
 * This class pins both the ratchet (no `clear()` on the shared file, ever
 * again) and the policy's behavior.
 */
class SettingsResetPolicyTest {

    private val mainKotlin = File("src/main/kotlin")

    /**
     * The `editor.clear()` ban (cross-cutting ratchet #7). The MAIN prefs file
     * (DirectBootAwarePreferences) also holds dictionaries, layouts, custom
     * themes and migration markers — `clear()`-then-reseed destroys everything
     * unlisted. Files below clear their OWN dedicated prefs file (or restore a
     * full snapshot immediately after) and are the only sanctioned uses.
     */
    @Test
    fun prefsEditorClearIsBannedOutsideWhitelistedFiles() {
        val whitelist = setOf(
            // Dedicated file "swipe_performance_stats" — not the settings file.
            "SwipePerformanceStats.kt",
            // Dedicated file SwipeMLDataStore.PREF_NAME — not the settings file.
            "SwipeMLDataStore.kt",
            // Dedicated file "privacy_settings" — not the settings file.
            "PrivacyManager.kt",
            // restorePrefsSnapshot: clear() followed by a full-snapshot rewrite of
            // EVERY key in one editor — the CK-150-020 rollback. Nothing is lost.
            "BackupRestoreManager.kt",
        )
        val clearPattern = Regex("""(?:\w*[Ee]ditor|\w*[Pp]refs\.edit\(\))\s*\.\s*clear\(\)""")

        var sitesSeen = 0
        val offenders = sortedSetOf<String>()
        mainKotlin.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                clearPattern.findAll(file.readText()).forEach { _ ->
                    sitesSeen++
                    if (file.name !in whitelist) offenders += file.path
                }
            }

        check(sitesSeen > 0) { "Regex matched zero editor.clear() sites — pattern broken, not a real pass." }
        assertThat(offenders).isEmpty()
    }

    // ── Policy behavior (the F-1/F-2 reproduction, at the decision core) ──

    /** F-1: user data sharing the prefs file must survive a settings reset. */
    @Test
    fun customDictionary_layouts_extraKeys_surviveReset() {
        val existing = setOf(
            // Non-settings data the old clear() destroyed:
            "custom_words_en", "custom_words_fr", "disabled_words_en",
            "layouts", "extra_keys", "custom_extra_keys",
            // Settings keys that SHOULD be reset:
            "swipe_trail_color", "longpress_timeout", "keyrepeat_backspace_only",
        )
        val removed = SettingsResetPolicy.keysToRemove(existing)

        assertThat(removed).containsAtLeast(
            "swipe_trail_color", "longpress_timeout", "keyrepeat_backspace_only",
        )
        assertWithMessage("non-settings data must survive a settings reset")
            .that(removed.intersect(setOf(
                "custom_words_en", "custom_words_fr", "disabled_words_en",
                "layouts", "extra_keys", "custom_extra_keys",
            )))
            .isEmpty()
    }

    /** F-2: migration markers must survive (or migrations re-run and mangle values). */
    @Test
    fun migrationMarkers_surviveReset() {
        val markers = SettingsValidation.INTERNAL_KEYS
        assertWithMessage("INTERNAL_KEYS (markers + device-bound state) must never be removed")
            .that(SettingsResetPolicy.keysToRemove(markers)).isEmpty()
        assertWithMessage("a reset must not seed internal keys either")
            .that(SettingsResetPolicy.seedValues().keys.intersect(markers)).isEmpty()
    }

    /** Pre-existing G5 behavior: the engine choice survives a reset. */
    @Test
    fun engineChoiceKeys_surviveReset() {
        val removed = SettingsResetPolicy.keysToRemove(SettingsResetPolicy.PRESERVED_SETTINGS_KEYS)
        assertThat(removed).isEmpty()
    }

    /** Deprecated legacy keys are swept by a reset so the next export omits them. */
    @Test
    fun deprecatedKeys_areRemovedByReset() {
        val removed = SettingsResetPolicy.keysToRemove(setOf("swipe_fuzzy_match_mode", "neural_beam_width"))
        assertThat(removed).containsExactly("swipe_fuzzy_match_mode", "neural_beam_width")
    }

    /**
     * ARC-052 invariant, carried over: the seed list writes only classified
     * settings keys and never resurrects a deprecated key.
     */
    @Test
    fun seedValues_writeOnlyClassifiedSettingsKeys() {
        val seeds = SettingsResetPolicy.seedValues().keys
        assertWithMessage("every seeded key must be in SETTINGS_DEFAULTS (the settings universe)")
            .that(seeds - SETTINGS_DEFAULTS.keys).isEmpty()
        assertWithMessage("a reset must never write a DEPRECATED_KEYS member (ARC-052)")
            .that(seeds.filter { SettingsValidation.isDeprecatedPreference(it) }).isEmpty()
        assertWithMessage("preserved engine keys must not be reseeded either")
            .that(seeds.intersect(SettingsResetPolicy.PRESERVED_SETTINGS_KEYS)).isEmpty()
    }
}
