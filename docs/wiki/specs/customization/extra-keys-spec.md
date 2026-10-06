---
title: Extra Keys - Technical Specification
description: Predefined key catalog, preference storage, responsive configuration and layout placement
user_guide: /wiki/customization/extra-keys/
status: implemented
version: v2.0.0
---

# Extra Keys Technical Specification

## Overview

Extra Keys configuration enables predefined actions and characters using per-key Boolean preferences. Layout placement uses `KeyboardData.PreferredPos`; it is not a separate left/right bottom-row list or a fixed spacebar-width calculation.

## Key Components

| Component | Source | Responsibility |
|-----------|--------|----------------|
| Catalog | `src/main/kotlin/tribixbite/cleverkeys/prefs/ExtraKeysPreference.kt:61` | 108 predefined key identifiers |
| Categories | `src/main/kotlin/tribixbite/cleverkeys/prefs/ExtraKeysPreference.kt:177` | Shared category partition consumed by the UI and completeness tests |
| Configuration screen | `src/main/kotlin/tribixbite/cleverkeys/activities/ExtraKeysConfigActivity.kt:55` | Search, saved checkbox state, defaults and category rows |
| Row | `src/main/kotlin/tribixbite/cleverkeys/activities/ExtraKeysConfigActivity.kt:217` | Resource-derived label, optional description, identifier and checkbox |
| Runtime preferences | `src/main/kotlin/tribixbite/cleverkeys/Config.kt:927` | Enabled predefined and custom extra-key maps |
| Placement | `src/main/kotlin/tribixbite/cleverkeys/KeyboardData.kt:48` | Preferred-position insertion and fallback |

## Preference Storage

`ExtraKeysPreference.prefKeyOfKeyName` (`ExtraKeysPreference.kt:486`) returns `extra_key_<identifier>`. Each predefined key stores a Boolean in `DirectBootAwarePreferences`, shared with the keyboard service. Missing values use `defaultChecked` (`ExtraKeysPreference.kt:193`), rather than one universal default.

Toggling a checkbox updates the screen's state map and applies the corresponding preference immediately. Reset writes the catalog's default Boolean for every key. Search and orientation changes do not alter enabled preferences.

`getExtraKeys` (`ExtraKeysPreference.kt:475`) converts enabled names into a map of `KeyValue` to `KeyboardData.PreferredPos`. `Config.extra_keys_param` holds this predefined map; `extra_keys_custom` is a separate custom-key map.

## Search and Viewport Behavior

The query uses `rememberSaveable` (`ExtraKeysConfigActivity.kt:71`). Filtering matches identifiers, localized titles and optional localized descriptions, ignoring case. The activity renders `ExtraKeysPreference.categorizedKeys`; every advertised identifier must appear in exactly one category. Autofill belongs to System; Clear system clipboard belongs to Editing.

A single `LazyColumn` (`ExtraKeysConfigActivity.kt:115`) contains keyed search, summary and reset items followed by category and key items. Headers can scroll away to leave space for rows in landscape, split-screen or large-font configurations. The app bar remains outside the list. Query restoration and list scrolling do not toggle preferences.

Category items use `category:<resource ID>` keys; key items use their identifiers (`ExtraKeysConfigActivity.kt:200`). Row labels derive from the current key/resources rather than unkeyed remembered labels, preventing stale titles after filtering or recycling.

## Layout Placement

`LayoutModifier.kt:45` combines predefined/custom extras with the mandatory configuration key. It suppresses Next/Previous Layout extras with one enabled layout, computes locale extras when the layout permits them, and removes keys already present before calling `KeyboardData.addExtraKeys` (`LayoutModifier.kt:108`).

`KeyboardData.PreferredPos` (`KeyboardData.kt:416`) specifies an optional neighboring key and ordered row/column/direction candidates. A value of `-1` leaves a coordinate unspecified. `addExtraKeys` tries each preferred position; unplaced keys then try `PreferredPos.ANYWHERE`. Placement is limited by available slots, so an enabled preference is not a guarantee of a new visible key on every layout.

There is no `ExtraKeysManager` enum, `extra_keys_left`/`extra_keys_right` storage, six-key hard cap or percentage-based spacebar sizing in this implementation. Extra-key actions use the existing `KeyValue` input pipeline and layout/subkey behavior.

## Test Coverage

| Suite | Source | Protected behavior |
|-------|--------|--------------------|
| Host | `src/test/kotlin/tribixbite/cleverkeys/prefs/AndroidXPreferenceMigrationTest.kt` | Catalog completeness and Autofill classification |
| Compose | `src/androidTest/kotlin/tribixbite/cleverkeys/ExtraKeysConfigActivityComposeTest.kt` | Search, labels, reset reachability, opt-in system-clear row, Autofill visibility, landscape row access and query recreation |
| Seeker | Recorded in `memory/todo.md` | Minified-build identifier/title search, unchanged enabled count, landscape scrolling and rotation retention |

## Related Specifications

- [Extra Keys Guide](../../customization/extra-keys.md) - Configure predefined keys
- [Per-Key Actions](per-key-actions-spec.md) - Custom direction assignments
- [Layout System](../../../specs/layout-system.md) - Keyboard layout transformations
- [Gesture System](../../../specs/gesture-system.md) - Gesture handling
