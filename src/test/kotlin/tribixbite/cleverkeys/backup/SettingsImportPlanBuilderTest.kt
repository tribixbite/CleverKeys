package tribixbite.cleverkeys.backup

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.junit.Test

class SettingsImportPlanBuilderTest {

    private val screen = ScreenMetrics(width = 1080, height = 2400, density = 3.0f)

    @Test
    fun emptyDelta_whenJsonMatchesCurrentSnapshot() {
        val json = """{"metadata":{"app_version":"1.4.0","screen_width":1080,"screen_height":2400},"preferences":{"key_height":50}}"""
        val current = mapOf<String, Any?>("key_height" to 50)

        val plan = SettingsImportPlanBuilder.fromJson(json, current, screen)

        assertThat(plan.changes).isEmpty()
        assertThat(plan.parseSkippedKeys).isEmpty()
        assertThat(plan.internalRemoves).isEmpty()
        assertThat(plan.sourceVersion).isEqualTo("1.4.0")
    }

    @Test
    fun modifiedKey_intValueChanged() {
        val json = """{"preferences":{"key_height":60}}"""

        val plan = SettingsImportPlanBuilder.fromJson(json, mapOf("key_height" to 50), screen)

        assertThat(plan.changes).hasSize(1)
        val c = plan.changes.single()
        assertThat(c.key).isEqualTo("key_height")
        assertThat(c.type).isEqualTo(ChangeType.MODIFIED)
        assertThat(c.current).isEqualTo(PrefValue.IntV(50))
        assertThat(c.proposed).isEqualTo(PrefValue.IntV(60))
    }

    @Test
    fun addedKey_absentInSnapshot() {
        val json = """{"preferences":{"new_key":"hello"}}"""

        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        assertThat(plan.changes).hasSize(1)
        val c = plan.changes.single()
        assertThat(c.type).isEqualTo(ChangeType.ADDED)
        assertThat(c.current).isEqualTo(PrefValue.Unset)
        assertThat(c.proposed).isEqualTo(PrefValue.Str("hello"))
    }

    @Test
    fun prefValueVariants_eachJsonTypeMaterializesCorrectly() {
        val json = """{
            "preferences":{
                "b":true, "i":42, "f":3.14, "s":"hi"
            }
        }""".trimIndent()

        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)
        val byKey = plan.changes.associateBy { it.key }

        assertThat(byKey["b"]!!.proposed).isEqualTo(PrefValue.Bool(true))
        assertThat(byKey["i"]!!.proposed).isEqualTo(PrefValue.IntV(42))
        // Gson treats 3.14 as Double — caller decides Float vs Double.
        // Settings uses Float; verify FloatV with the original literal.
        assertThat(byKey["f"]!!.proposed).isEqualTo(PrefValue.FloatV(3.14f))
        assertThat(byKey["s"]!!.proposed).isEqualTo(PrefValue.Str("hi"))
    }

    @Test
    fun jsonBlobPref_layoutsKey_classifiedAsJsonBlob() {
        val rawLayouts = """[{"name":"qwerty"},{"name":"dvorak"}]"""
        val json = """{"preferences":{"layouts":$rawLayouts}}"""

        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        val c = plan.changes.single { it.key == "layouts" }
        assertThat(c.proposed).isInstanceOf(PrefValue.JsonBlob::class.java)
        // Canonicalized via JsonParser → toString — round-trip equality is the contract.
        val canonical = JsonParser.parseString(rawLayouts).toString()
        assertThat((c.proposed as PrefValue.JsonBlob).raw).isEqualTo(canonical)
    }

    @Test
    fun legacyHorizontalMargin_synthesizesNewKeysAndQueuesRemove() {
        val json = """{"preferences":{"horizontal_margin_portrait":24}}"""
        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        // The legacy key never lands in `changes` (user wouldn't see "old key" rows).
        assertThat(plan.changes.map { it.key }).containsExactly("margin_left_portrait", "margin_right_portrait")
        // Both new keys are ADDED (snapshot is empty).
        assertThat(plan.changes.all { it.type == ChangeType.ADDED }).isTrue()
        // The legacy key's removal is queued so apply explicitly drops it from prefs.
        assertThat(plan.internalRemoves).containsExactly("horizontal_margin_portrait")
        // And it's listed in parseSkippedKeys so the user can audit what was dropped.
        assertThat(plan.parseSkippedKeys.map { it.key }).contains("horizontal_margin_portrait")
    }

    @Test
    fun legacyHorizontalMargins_allFourPairsConverted() {
        // Density 3.0, width 1080: 24dp * 3.0 = 72px ; 72/1080 = 0.0667 → 6%.
        val json = """{"preferences":{
            "horizontal_margin_portrait":24,
            "horizontal_margin_landscape":24,
            "horizontal_margin_portrait_unfolded":24,
            "horizontal_margin_landscape_unfolded":24
        }}""".trimIndent()
        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        // Eight ADDED rows expected (left + right per orientation × 4).
        val addedKeys = plan.changes.filter { it.type == ChangeType.ADDED }.map { it.key }.toSet()
        assertThat(addedKeys).containsAtLeast(
            "margin_left_portrait", "margin_right_portrait",
            "margin_left_landscape", "margin_right_landscape",
            "margin_left_portrait_unfolded", "margin_right_portrait_unfolded",
            "margin_left_landscape_unfolded", "margin_right_landscape_unfolded",
        )
        // All four legacy keys queued for removal.
        assertThat(plan.internalRemoves.toSet()).containsExactly(
            "horizontal_margin_portrait",
            "horizontal_margin_landscape",
            "horizontal_margin_portrait_unfolded",
            "horizontal_margin_landscape_unfolded",
        )
    }

    @Test
    fun legacyBottomMargin_dpAboveThirty_convertedToPercent() {
        // 50dp * 3.0 / 2400 * 100 = 6.25 → 6%.
        val json = """{"preferences":{"margin_bottom_portrait":50}}"""
        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        val change = plan.changes.single { it.key == "margin_bottom_portrait" }
        val proposed = change.proposed as PrefValue.IntV
        assertThat(proposed.v).isLessThan(30)
        assertThat(proposed.v).isAtLeast(1)
    }

    @Test
    fun legacyBottomMargin_valueAtOrBelowThirty_passesThrough() {
        // ≤30 may already be a percent — leave alone.
        val json = """{"preferences":{"margin_bottom_portrait":15}}"""
        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        val change = plan.changes.single { it.key == "margin_bottom_portrait" }
        assertThat((change.proposed as PrefValue.IntV).v).isEqualTo(15)
    }

    @Test
    fun internalPreferenceKeys_landInSkippedNotChanges() {
        // All three internal keys from BackupRestoreManager.isInternalPreference.
        val json = """{"preferences":{
            "version":42,
            "current_layout_portrait":3,
            "current_layout_landscape":2
        }}""".trimIndent()
        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        assertThat(plan.changes).isEmpty()
        assertThat(plan.parseSkippedKeys.map { it.key }).containsExactly(
            "version", "current_layout_portrait", "current_layout_landscape"
        )
        plan.parseSkippedKeys.forEach {
            assertThat(it.reason).isEqualTo("internal preference")
        }
    }

    @Test
    fun floatKey_dispatchesToFloatVNotIntV_evenWhenJsonValueIsIntegerMultiple() {
        // Regression for the round-3 reviewer's correctness finding:
        // SharedPreferences throws ClassCastException if a key historically
        // stored as Float is overwritten with putInt. The dispatch is BY KEY.
        // swipe_trail_width is in isFloatPreference's allowlist; even when
        // the import JSON has 5.0 (no fractional part), it must produce
        // FloatV, NOT IntV.
        val json = """{"preferences":{"swipe_trail_width":5.0}}"""
        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)
        val change = plan.changes.single { it.key == "swipe_trail_width" }
        assertThat(change.proposed).isInstanceOf(PrefValue.FloatV::class.java)
        assertThat((change.proposed as PrefValue.FloatV).v).isEqualTo(5.0f)
    }

    @Test
    fun nonFloatKey_dispatchesToIntVForIntegerJson() {
        val json = """{"preferences":{"key_height":50}}"""
        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)
        val change = plan.changes.single { it.key == "key_height" }
        assertThat(change.proposed).isEqualTo(PrefValue.IntV(50))
    }

    @Test
    fun isFloatPreferenceParityWithLegacy() {
        // Spot-check the ported set covers the categories listed in the
        // legacy method's source comments. Update as the legacy method evolves.
        val expectedFloatKeys = listOf(
            "character_size", "swipe_trail_width", "swipe_trail_glow_radius",
            "swipe_min_distance", "prediction_context_boost",
            "autocorrect_char_match_threshold", "slider_speed_max",
        )
        expectedFloatKeys.forEach {
            assertThat(SettingsValidation.isFloatPreference(it)).isTrue()
        }
        val expectedNonFloatKeys = listOf("key_height", "version", "layouts", "primary_language")
        expectedNonFloatKeys.forEach {
            assertThat(SettingsValidation.isFloatPreference(it)).isFalse()
        }
    }

    @Test
    fun internalKeys_setMatchesLegacyMethod() {
        // Drift guard. INTERNAL_KEYS is the single source of truth for both
        // export-filter and import-filter (BackupRestoreManager.isInternalPreference
        // now delegates here). Hard-code the expected set explicitly so any
        // future addition is reviewed deliberately, not slipped in silently.
        val expected = setOf(
            "version",
            "current_layout_portrait",
            "current_layout_landscape",
            "margin_prefs_version",            // legacy backup pollution
            "need_migration",                  // DirectBoot prefs migration
            "lang_pref_migration_version",     // language-prefs migration
            "vibrate_custom_migration_v1",     // one-time #154 vibrate_custom cleanup
            "voice_ime_known",                 // runtime IME state
            "voice_ime_last_used",             // runtime IME state
            "ime_prompt_shown_this_session",   // per-session UI flag
            // Backup-encryption state (Stage B) — never exported (§8.2).
            "backup_passphrase_ciphertext",    // Keystore-wrapped passphrase
            "backup_passphrase_iv",            // GCM IV for the wrapped passphrase
            "backup_allow_intent_passphrase",  // headless --es passphrase opt-in
            "backup_last_headless_action_ms",  // in-prefs rate-limit timestamp
            // 2026-08-29: derived per-device cache of imported-langpack CTC eligibility,
            // fingerprinted by local file length+mtime — meaningless on any other device.
            "ctc_langpack_verdicts",
        )
        assertThat(SettingsValidation.INTERNAL_KEYS).isEqualTo(expected)
    }

    @Test
    fun outOfRangeIntValue_landsInSkipped() {
        // keyboard_height has range 10..100 in BackupRestoreManager.validateIntPreference.
        val json = """{"preferences":{"keyboard_height":99999}}"""
        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        val rejected = plan.parseSkippedKeys.singleOrNull { it.key == "keyboard_height" }
        assertThat(rejected).isNotNull()
        assertThat(rejected!!.reason).contains("out of range")
        assertThat(plan.changes.any { it.key == "keyboard_height" }).isFalse()
    }

    @Test
    fun typeMismatch_landsInSkipped() {
        // Int-typed key receives a string — must reject.
        val json = """{"preferences":{"keyboard_height":"definitely-not-a-number"}}"""
        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        assertThat(plan.parseSkippedKeys.any { it.key == "keyboard_height" }).isTrue()
    }

    @Test
    fun shortSwipeSection_recordsSizeAndRawJson() {
        val ssJson = """{"q":{"up":"DEL"}}"""
        val json = """{"preferences":{}, "short_swipe_customizations":$ssJson}"""

        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        assertThat(plan.shortSwipeImportSize).isEqualTo(1)
        // Canonicalized — caller hands `shortSwipeImportRawJson` to ShortSwipeManager.importFromJson.
        assertThat(plan.shortSwipeImportRawJson).isEqualTo(JsonParser.parseString(ssJson).toString())
    }

    @Test
    fun perRuleCategoryCoverage_intRangeFloatRangeStringAllowlist() {
        // One key per category:
        //   - keyboard_height (Int range 10..100 — out at 99999)
        //   - prediction_context_boost (Float range 0.5..5.0 — out at 99.0)
        //   - theme (String — empty rejected by isNotEmpty())
        val json = """{
            "preferences": {
                "keyboard_height": 99999,
                "prediction_context_boost": 99.0,
                "theme": ""
            }
        }""".trimIndent()
        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        val rejectedKeys = plan.parseSkippedKeys.map { it.key }.toSet()
        assertThat(rejectedKeys).containsAtLeast(
            "keyboard_height", "prediction_context_boost", "theme"
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Default-aware diff (added 2026-05-14 to fix fresh-install over-report
    // of changes). The user's complaint: importing a 150-key backup into a
    // fresh install showed 150 "changes" — but a row whose proposed value
    // equals the effective default is NOT a change the user can perceive.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun freshInstall_proposedEqualsDefault_isNotEmittedAsChange() {
        // gif_enabled defaults to false. The import file also has false. On a
        // fresh install (current snapshot empty), the user sees NO change.
        val json = """{"preferences":{"gif_enabled":false}}"""
        val defaults = mapOf<String, PrefValue>("gif_enabled" to PrefValue.Bool(false))

        val plan = SettingsImportPlanBuilder.fromJson(
            json,
            currentSnapshot = emptyMap(),
            screen = screen,
            defaultSnapshot = defaults,
        )

        assertThat(plan.changes).isEmpty()
    }

    @Test
    fun freshInstall_proposedDiffersFromDefault_showsDefaultAsCurrent() {
        // gif_enabled defaults to false; import wants true. User WILL see a
        // change: from default false → true. The `current` field is the
        // effective default (Bool(false)) so the preview row is meaningful,
        // not the inert `Unset` sentinel.
        val json = """{"preferences":{"gif_enabled":true}}"""
        val defaults = mapOf<String, PrefValue>("gif_enabled" to PrefValue.Bool(false))

        val plan = SettingsImportPlanBuilder.fromJson(
            json,
            currentSnapshot = emptyMap(),
            screen = screen,
            defaultSnapshot = defaults,
        )

        assertThat(plan.changes).hasSize(1)
        val c = plan.changes.single()
        assertThat(c.key).isEqualTo("gif_enabled")
        assertThat(c.current).isEqualTo(PrefValue.Bool(false))   // not Unset
        assertThat(c.proposed).isEqualTo(PrefValue.Bool(true))
        assertThat(c.type).isEqualTo(ChangeType.ADDED)
    }

    @Test
    fun freshInstall_unknownKey_fallsBackToUnsetSentinel() {
        // For keys not in the defaults map (e.g. future prefs we don't yet
        // track), behavior is unchanged: ADDED row with current=Unset. This
        // preserves backward compatibility for the import side.
        val json = """{"preferences":{"future_key":"x"}}"""

        val plan = SettingsImportPlanBuilder.fromJson(
            json,
            currentSnapshot = emptyMap(),
            screen = screen,
            defaultSnapshot = emptyMap(),
        )

        assertThat(plan.changes).hasSize(1)
        val c = plan.changes.single()
        assertThat(c.current).isEqualTo(PrefValue.Unset)
        assertThat(c.proposed).isEqualTo(PrefValue.Str("x"))
        assertThat(c.type).isEqualTo(ChangeType.ADDED)
    }

    @Test
    fun currentSet_diffsAgainstCurrentNotDefault() {
        // When prefs already have a non-default value, the diff is between
        // current and proposed — the default is irrelevant. This guards
        // against any regression where the defaults map shadows real user
        // values.
        val json = """{"preferences":{"gif_enabled":false}}"""
        val current = mapOf<String, Any?>("gif_enabled" to true)
        val defaults = mapOf<String, PrefValue>("gif_enabled" to PrefValue.Bool(false))

        val plan = SettingsImportPlanBuilder.fromJson(
            json,
            currentSnapshot = current,
            screen = screen,
            defaultSnapshot = defaults,
        )

        assertThat(plan.changes).hasSize(1)
        val c = plan.changes.single()
        assertThat(c.current).isEqualTo(PrefValue.Bool(true))
        assertThat(c.proposed).isEqualTo(PrefValue.Bool(false))
        assertThat(c.type).isEqualTo(ChangeType.MODIFIED)
    }

    @Test
    fun bulkFreshInstall_onlyNonDefaultRowsEmitted() {
        // Simulates the user's actual scenario: import 5 keys into a fresh
        // install, 3 of which equal defaults. Only 2 changes should be
        // surfaced — the over-report this fix targets.
        val json = """{"preferences":{
            "gif_enabled":false,
            "swipe_typing_enabled":true,
            "autocorrect_enabled":true,
            "keyboard_opacity":75,
            "swipe_trail_width":12.0
        }}""".trimIndent()
        val defaults = mapOf<String, PrefValue>(
            "gif_enabled" to PrefValue.Bool(false),
            "swipe_typing_enabled" to PrefValue.Bool(true),
            "autocorrect_enabled" to PrefValue.Bool(true),
            "keyboard_opacity" to PrefValue.IntV(100),
            "swipe_trail_width" to PrefValue.FloatV(8.0f),
        )

        val plan = SettingsImportPlanBuilder.fromJson(
            json,
            currentSnapshot = emptyMap(),
            screen = screen,
            defaultSnapshot = defaults,
        )

        // Three rows (gif_enabled, swipe_typing_enabled, autocorrect_enabled)
        // equal defaults → skipped. Two rows (keyboard_opacity, swipe_trail_width)
        // differ from defaults → emitted with default-as-current.
        assertThat(plan.changes.map { it.key }.toSet())
            .containsExactly("keyboard_opacity", "swipe_trail_width")
        val byKey = plan.changes.associateBy { it.key }
        assertThat(byKey["keyboard_opacity"]!!.current).isEqualTo(PrefValue.IntV(100))
        assertThat(byKey["swipe_trail_width"]!!.current).isEqualTo(PrefValue.FloatV(8.0f))
    }

    @Test
    fun deprecatedNeuralKeys_filteredFromThePreview() {
        // Neural-engine removal (2026-08-18). A v1.5.x backup still carries ~20 `neural_*`
        // keys plus the per-language prefix-boost variants. Nothing reads them now, so
        // surfacing them would be pure preview noise AND would write dead keys into prefs.
        // The per-language forms need PREFIX matching — DEPRECATED_KEYS is exact-match — so
        // they are the case most likely to regress; pin both forms plus a live control key
        // that must still come through.
        //
        // `finger_occlusion_offset` is deliberately in this payload as a SECOND live control.
        // It was deprecated by the 2026-08-18 sweep on the assumption that it was
        // engine-specific, and un-deprecated on 2026-08-19 once the audit established it shifts
        // RAW touch Y before any decoder — a property of the finger and the screen. It must now
        // come THROUGH the filter, so a v1.5.x user's calibrated value survives the upgrade
        // instead of being silently dropped. This assertion is what would catch it being
        // re-deprecated by name-matching in some future sweep.
        val json = """{"preferences":{
            "neural_temperature": 1.5,
            "neural_beam_width": 12,
            "neural_prefix_boost_multiplier_es": 2.5,
            "neural_prefix_boost_max_de": 7.0,
            "swipe_beam_autocorrect_enabled": false,
            "finger_occlusion_offset": 12,
            "keyboard_opacity": 55
        }}""".trimIndent()
        val plan = SettingsImportPlanBuilder.fromJson(
            json,
            currentSnapshot = emptyMap(),
            screen = screen,
            defaultSnapshot = SETTINGS_DEFAULTS,
        )
        assertThat(plan.changes.map { it.key })
            .containsExactly("keyboard_opacity", "finger_occlusion_offset")
    }

    @Test
    fun deprecatedPrefixMatchDoesNotSwallowLiveKeys() {
        // The prefix rule must not become a `startsWith("neural")` catch-all that also eats
        // an unrelated future key. Only the two documented per-language bases match.
        assertThat(SettingsValidation.isDeprecatedPreference("neural_prefix_boost_max_de")).isTrue()
        assertThat(SettingsValidation.isDeprecatedPreference("neural_prefix_boost_multiplier_es"))
            .isTrue()
        assertThat(SettingsValidation.isDeprecatedPreference("keyboard_opacity")).isFalse()
        assertThat(SettingsValidation.isDeprecatedPreference("swipe_engine_mode")).isFalse()
        assertThat(SettingsValidation.isDeprecatedPreference("ctc_beam_width")).isFalse()
        // Un-deprecated 2026-08-19: it shifts RAW touch Y before any decoder runs, so it is an
        // input-path setting, not an engine one. Pinned by name because the 2026-08-18 sweep
        // classified it by filename (it lived in NeuralLayoutHelper) rather than by what it did.
        assertThat(SettingsValidation.isDeprecatedPreference("finger_occlusion_offset")).isFalse()
    }

    @Test
    fun customWords_routedToDictionaryPreview_notSettingsPreview() {
        // `custom_words_fr` and `disabled_words_en` are per-language dictionary
        // entries handled by DictImportPlan. They appearing in the SETTINGS
        // preview as "(unset) → {huge JSON blob}" is a leak — the filter
        // sends them to parseSkippedKeys with a descriptive reason.
        val json = """{"preferences":{
            "custom_words_fr": "{\"oui\":100}",
            "disabled_words_en": "[\"nope\"]",
            "keyboard_opacity": 55
        }}""".trimIndent()
        val plan = SettingsImportPlanBuilder.fromJson(
            json,
            currentSnapshot = emptyMap(),
            screen = screen,
            defaultSnapshot = SETTINGS_DEFAULTS,
        )
        // Only keyboard_opacity in changes; dictionary keys filtered.
        assertThat(plan.changes.map { it.key }).containsExactly("keyboard_opacity")
        val skippedKeys = plan.parseSkippedKeys.map { it.key }.toSet()
        assertThat(skippedKeys).contains("custom_words_fr")
        assertThat(skippedKeys).contains("disabled_words_en")
    }

    @Test
    fun stringlyNumericKey_typeFlipDoesNotSurfaceAsChange() {
        // `clipboard_history_limit` is read via `safeGetString(…).toIntOrNull()`
        // but written via both `putString` (legacy slider) AND `putInt`
        // (Config setter). A backup made via the Int path stores `50`;
        // canonicalization normalizes both sides to Str("50") so the diff
        // sees no change.
        val json = """{"preferences":{"clipboard_history_limit": 50}}"""
        val current = mapOf<String, Any?>("clipboard_history_limit" to "50")  // String stored

        val plan = SettingsImportPlanBuilder.fromJson(
            json,
            currentSnapshot = current,
            screen = screen,
            defaultSnapshot = SETTINGS_DEFAULTS,
        )

        // Same conceptual value, different types → suppressed.
        assertThat(plan.changes).isEmpty()
    }

    @Test
    fun stringlyNumericKey_realValueChangeStillSurfaces() {
        // Same key but proposed value is genuinely different from current.
        // Verify canonicalization didn't break the real-change path.
        val json = """{"preferences":{"clipboard_history_limit": 0}}"""
        val current = mapOf<String, Any?>("clipboard_history_limit" to "50")

        val plan = SettingsImportPlanBuilder.fromJson(
            json,
            currentSnapshot = current,
            screen = screen,
            defaultSnapshot = SETTINGS_DEFAULTS,
        )

        assertThat(plan.changes).hasSize(1)
        val c = plan.changes.single()
        // Both sides canonicalized to Str.
        assertThat(c.current).isEqualTo(PrefValue.Str("50"))
        assertThat(c.proposed).isEqualTo(PrefValue.Str("0"))
        assertThat(c.type).isEqualTo(ChangeType.MODIFIED)
    }

    @Test
    fun circleSensitivity_stringValue_acceptedNotRejectedAsTypeMismatch() {
        // `Defaults.CIRCLE_SENSITIVITY = "2"` — the runtime reads it via
        // `safeGetString` and validates against the regex `[1-5]`. The
        // validator's `isIntKey()` allowlist wrongly contained this key,
        // causing String values to be rejected as "expected Int, got String"
        // and silently dropped from the import. Reported by user 2026-05-15.
        val json = """{"preferences":{"circle_sensitivity": "3"}}"""
        val plan = SettingsImportPlanBuilder.fromJson(
            json,
            currentSnapshot = emptyMap(),
            screen = screen,
            defaultSnapshot = SETTINGS_DEFAULTS,
        )

        // Should land in changes (Str("3")), NOT in parseSkippedKeys.
        assertThat(plan.changes.map { it.key }).contains("circle_sensitivity")
        assertThat(plan.parseSkippedKeys.map { it.key }).doesNotContain("circle_sensitivity")
        val c = plan.changes.single { it.key == "circle_sensitivity" }
        assertThat(c.proposed).isEqualTo(PrefValue.Str("3"))
    }

    @Test
    fun clipboardHistoryLimit_stringValue_acceptedNotRejected() {
        // Same bug class as circle_sensitivity — `Defaults.CLIPBOARD_HISTORY_LIMIT = "50"`
        // is a String pref but `isIntKey()` wrongly listed it. A backup
        // with the value as String "100" would be dropped.
        val json = """{"preferences":{"clipboard_history_limit": "100"}}"""
        val plan = SettingsImportPlanBuilder.fromJson(
            json,
            currentSnapshot = emptyMap(),
            screen = screen,
            defaultSnapshot = SETTINGS_DEFAULTS,
        )

        assertThat(plan.parseSkippedKeys.map { it.key })
            .doesNotContain("clipboard_history_limit")
        // Should appear in changes (Str("100") differs from default "50").
        assertThat(plan.changes.map { it.key }).contains("clipboard_history_limit")
    }

    // ── G5 ctc engine settings round-trip (CTC integration audit, missing-test 3) ─────

    @Test
    fun ctcEngineSettings_modeAndBeamWidth_surviveTheImportPlan() {
        // A backup that switches the engine mode to ctc and carries a non-default
        // beam width must surface BOTH as applyable changes.
        val json = """{"preferences":{"swipe_engine_mode":"ctc","ctc_beam_width":180}}"""
        val plan = SettingsImportPlanBuilder.fromJson(
            json,
            currentSnapshot = mapOf("swipe_engine_mode" to "neural"),
            screen = screen,
            defaultSnapshot = SETTINGS_DEFAULTS,
        )

        val mode = plan.changes.single { it.key == "swipe_engine_mode" }
        assertThat(mode.type).isEqualTo(ChangeType.MODIFIED)
        assertThat(mode.current).isEqualTo(PrefValue.Str("neural"))
        assertThat(mode.proposed).isEqualTo(PrefValue.Str("ctc"))

        val beam = plan.changes.single { it.key == "ctc_beam_width" }
        assertThat(beam.type).isEqualTo(ChangeType.ADDED)
        // Effective current renders as the compile-time default (100), not Unset.
        assertThat(beam.current).isEqualTo(PrefValue.IntV(100))
        assertThat(beam.proposed).isEqualTo(PrefValue.IntV(180))
        assertThat(plan.parseSkippedKeys).isEmpty()
    }

    @Test
    fun ctcMode_uppercaseImport_canonicalizedToLowercase() {
        // Audit L1: an uppercase "CTC" from a hand-edited/foreign backup must apply in
        // the canonical lowercase form every reader (router, dropdown) expects.
        val json = """{"preferences":{"swipe_engine_mode":"CTC"}}"""
        val plan = SettingsImportPlanBuilder.fromJson(
            json,
            currentSnapshot = mapOf("swipe_engine_mode" to "neural"),
            screen = screen,
        )

        val mode = plan.changes.single { it.key == "swipe_engine_mode" }
        assertThat(mode.proposed).isEqualTo(PrefValue.Str("ctc"))
    }

    @Test
    fun ctcMode_uppercaseImportMatchingStoredLowercase_isNoChange() {
        // Case-only difference must not surface as a change row (L1: both sides
        // canonicalize before the diff).
        val json = """{"preferences":{"swipe_engine_mode":"CTC"}}"""
        val plan = SettingsImportPlanBuilder.fromJson(
            json,
            currentSnapshot = mapOf("swipe_engine_mode" to "ctc"),
            screen = screen,
        )

        assertThat(plan.changes).isEmpty()
    }

    @Test
    fun emptyDefaults_behavesAsBeforeFix() {
        // Backward-compat sanity: empty defaults map = pre-fix behavior.
        val json = """{"preferences":{"new_key":"hello"}}"""
        val plan = SettingsImportPlanBuilder.fromJson(
            json,
            currentSnapshot = emptyMap(),
            screen = screen,
            defaultSnapshot = emptyMap(),
        )

        assertThat(plan.changes).hasSize(1)
        assertThat(plan.changes.single().current).isEqualTo(PrefValue.Unset)
        assertThat(plan.changes.single().type).isEqualTo(ChangeType.ADDED)
    }
}
