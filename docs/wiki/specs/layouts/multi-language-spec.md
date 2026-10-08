---
title: Multi-Language Input - Technical Specification
description: Primary and secondary dictionaries, auto-detection, and per-layout language binding
user_guide: /wiki/layouts/multi-language/
status: implemented
version: v2.0.0 development
---

# Multi-Language Input Technical Specification

## Overview

CleverKeys serves typing from an **active primary** language (prediction, autocorrect,
learning, n-gram/static LM, swipe lexicon) and an optional **active secondary** dictionary
whose candidates are merged into the tap-typing slate. The user's choices live in the
Multi-Language settings. Since GH #186/#61 (2026-10-07) a layout may carry a **language
binding**: while a bound layout is active, its language is the only active language.
Every change of the active languages goes through one path, `ActiveLanguageSync`.

The internal engineering notes are
[dictionary-and-language-system.md](https://github.com/tribixbite/CleverKeys/blob/main/docs/specs/dictionary-and-language-system.md)
(section "Per-layout language binding") and
[secondary-language-integration.md](https://github.com/tribixbite/CleverKeys/blob/main/docs/specs/secondary-language-integration.md).

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Config | `Config.kt` | User language preferences; the resolved active languages (`primary_language`, `active_secondary_language`, `layout_bound_language`); `layout_languages` per layout |
| LayoutLanguageBinding / ActiveLanguages | `LayoutLanguageBinding.kt` | Pure rules: code validation, entry precedence, `resolve()` |
| ActiveLanguageSync | `ActiveLanguageSync.kt` | The single language-change path: diffs and reloads only what changed |
| LayoutsPreference | `prefs/LayoutsPreference.kt` | Stores the optional `language` field inside each `layouts` entry; `loadLayoutsWithBindings` |
| LayoutManagerActivity | `activities/LayoutManagerActivity.kt` | Per-layout "Language: X" chip and the Layout language dialog; missing-pack warning |
| WordPredictor | `WordPredictor.kt` | Primary + secondary dictionaries; inline secondary merge (`secondary_prediction_weight`); auto-detection call |
| MultiLanguageManager / LanguageDetector | `MultiLanguageManager.kt`, `LanguageDetector.kt` | `detectAndSwitch` from recent words (word and character patterns) |
| AccentNormalizer / NormalizedPrefixIndex | `AccentNormalizer.kt`, `NormalizedPrefixIndex.kt` | Accent-insensitive lookup for 26-letter input |
| LanguagePackManager | `langpack/LanguagePackManager.kt` | Imported language packs |

## Active Language Resolution

`LayoutLanguageBinding.resolve` runs on every Config refresh and on every
`Config.set_current_layout`, using the current layout index (an index past the end
resolves to layout 0, matching `LayoutManager.current_layout_unmodified`):

| Current layout | Active primary | Active secondary |
|----------------|----------------|------------------|
| Unbound | `pref_primary_language` | `pref_secondary_language` when Multi-Language is on and a secondary is chosen |
| Bound to X | X | none (single-language) |

```kotlin
// LayoutLanguageBinding.kt
val index = if (currentIndex >= bindings.size) 0 else currentIndex
val bound = bindings.getOrNull(index)
if (bound != null) {
    // #61: a bound layout is single-language — no words mixed in from a secondary.
    return ActiveLanguages(primary = bound, secondary = null, boundLayoutLanguage = bound)
}
```

The user's preferences are never rewritten by a binding, so leaving a bound layout returns
to them exactly. Settings keep showing the user's own choices.

## Per-Layout Language Binding

**Storage.** Each entry of the `layouts` preference may carry a `language` field. Unbound
entries serialize exactly as before; a bound named layout becomes
`{"kind":"named","name":…,"language":…}`. Reordering, renaming or deleting a layout carries
or removes its binding, and Backup & Restore exports it inside the existing `layouts` value.

**Entry values** (`LayoutLanguageBinding.normalizeEntry`):

| Stored value | Meaning |
|--------------|---------|
| `null` | No user choice; the layout XML's `language` attribute applies if valid |
| a code (`fa`, `pt_br`) | Bound to that language |
| `"none"` | Explicitly unbound; overrides an XML default ("Follow Multi-Language settings") |

Codes are trimmed, lower-cased and must match `^[a-z]{2,3}(?:[_-][a-z0-9]{1,16}){0,4}$`, the
same shape required of a language pack's code. An invalid stored value is dropped (treated
as `null`).

**XML default.** A custom layout may declare `<keyboard language="fa" …>`
(`KeyboardData.declared_language`). Loading is lenient: an invalid value never hides a
stored layout, but the layout editor refuses to save one ("The language attribute must be a
language code such as fa or pt_br"). The user's per-entry choice overrides the XML.
Built-in layouts declare no language.

**UI.** Each Layout Manager row shows a "Language: X" chip; tapping it opens **Layout
language** with **Follow Multi-Language settings**, the installed languages, and the XML
default labelled "(layout default)". Choosing the XML default stores nothing; choosing
Follow Multi-Language over an XML default stores `"none"`.

## One Language-Change Path: ActiveLanguageSync

A Settings language selector, a `primaryLangToggle`/`secondaryLangToggle` swap,
Multi-Language on/off, a layout switch onto or off a bound layout, a portrait/landscape
selection change, editing the layout list and a backup import all end in a Config refresh.
`CleverKeysService.onConfigChanged` passes `Config.activeLanguages()` to
`ActiveLanguageSync.apply`, which reloads only what changed, in this order:

1. primary dictionary (`PredictionCoordinator.reloadWordPredictorDictionary`, which also
   moves the n-gram/static LM, context model and learning);
2. secondary dictionary (load, or unload when single-language);
3. contractions (`ContractionManager.loadTypingMappings(primary, secondary)`);
4. re-warm the serving swipe engine, after the reloads so it warms the new language.

```kotlin
// ActiveLanguageSync.kt
if (primaryChanged) sink.reloadPrimary(target.primary)
if (secondaryChanged) sink.reloadSecondary(target.secondary)
if (primaryChanged || secondaryChanged) {
    sink.reloadContractions(target.primary, target.secondary)
    // ARC-014: after the dictionary reloads (see class KDoc).
    sink.rewarmSwipe()
}
// Only a binding that actually changed the serving language is announced; switching
// between unbound layouts stays silent exactly as before.
if (primaryChanged && target.boundLayoutLanguage != null) sink.announce(target)
```

Switching between unbound layouts reloads nothing. Swipe routing (CTC or geometric per
language) reads the same active values, so it follows the binding.

## Behaviour While a Bound Layout Is Active

| Situation | Behaviour |
|-----------|-----------|
| Switching onto a bound layout | Suggestion bar shows "Language: X" |
| Bound language has no bundled or installed dictionary | Allowed. Layout Manager warns on the row; switching to it shows "Language: X (no dictionary installed)". Typing works; predictions and autocorrect stay empty until the pack is imported |
| `primaryLangToggle` / `secondaryLangToggle` | Change nothing; the bar shows "This layout sets the language: X" |
| Auto language detection | Suspended (`WordPredictor.tryAutoLanguageDetection` returns early when `layout_bound_language != null`) |
| Three or more languages | Bind each layout; `switch_forward` cycles the languages in layout order. No limit on bound layouts |

**Downgrade note.** A build without this feature reads a bound named entry as the System
layout. Unbound entries are unaffected.

## Unbound Layouts: Primary + Secondary

Unchanged from earlier versions. With Multi-Language on and a secondary chosen,
`WordPredictor.predictInternal` merges secondary candidates that are not already in the
primary slate, scored with `secondary_prediction_weight` (default 0.9). Accent-insensitive
lookup lets 26-letter input reach accented words. With **Auto-Detect Language** on,
`MultiLanguageManager.detectAndSwitch` checks recent words against the detection
sensitivity and may switch the language models.

## Configuration

| Setting | Key | Default | Source |
|---------|-----|---------|--------|
| **Enable Multi-Language** | `pref_enable_multilang` | false | `Defaults.ENABLE_MULTILANG` |
| **Primary Language** | `pref_primary_language` | `en` | `Defaults.PRIMARY_LANGUAGE` |
| **Secondary Language** | `pref_secondary_language` | `none` | `Config.kt` |
| **Auto-Detect Language** | `pref_auto_detect_language` | true | `Defaults.AUTO_DETECT_LANGUAGE` |
| **Detection Sensitivity** | `pref_language_detection_sensitivity` | 0.6 | `Defaults.LANGUAGE_DETECTION_SENSITIVITY` |
| **Secondary weight** | `pref_secondary_prediction_weight` | 0.9 | `Defaults.SECONDARY_PREDICTION_WEIGHT` |
| **Layout language** | `language` field in each `layouts` entry | none | `prefs/LayoutsPreference.kt` |

## Test Coverage

| Suite | File |
|-------|------|
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/LayoutLanguageBindingTest.kt` |
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/ActiveLanguageSyncTest.kt` |
| Mock | `src/test/kotlin/tribixbite/cleverkeys/LayoutLanguageBindingConfigTest.kt` |

## Related Specifications

- [Switching Layouts](./switching-layouts-spec.md) - Layout cycling, which drives language changes for bound layouts
- [Language System](https://github.com/tribixbite/CleverKeys/blob/main/docs/specs/dictionary-and-language-system.md) - Full language architecture
- [CTC Swipe Engine](https://github.com/tribixbite/CleverKeys/blob/main/docs/specs/ctc-swipe-engine.md) - The per-language swipe decoder
- [Secondary Language](https://github.com/tribixbite/CleverKeys/blob/main/docs/specs/secondary-language-integration.md) - Secondary dictionary integration
