# CleverKeys Technical Specifications

Technical documentation for CleverKeys, an Android keyboard with on-device swipe typing. These specs are designed for LLM coding agents to understand the codebase architecture and implementation details.

## Specification Index

### Core System

| Spec | Description |
|------|-------------|
| [Core Keyboard System](./core-keyboard-system.md) | InputMethodService, view hierarchy, key events, layout management |
| [Suggestion Bar & Content Pane](./suggestion-bar-content-pane.md) | Word predictions; emoji/clipboard/GIF panes swapped in a `topPane` FrameLayout (`SuggestionBarPane`; the old ViewFlipper was replaced in v1.2.5) |
| [Gesture System](./gesture-system.md) | Swipe detection, multi-touch, gesture classification |
| [Layout System](./layout-system.md) | XML layouts (`src/main/layouts` → `R.raw`), ExtraKeys, per-layout language/theme bindings, modified-layout cache |
| [Foldable Keyboard Height](./foldable-keyboard-height.md) | Inner/outer-screen keyboard height on foldables (implemented 2026-10-20) |

### Swipe Prediction

| Spec | Description |
|------|-------------|
| [Cursor-Aware Predictions](./cursor-aware-predictions.md) | Cursor sync (single `SuggestionHandler` pipeline since 2026-07-20), exact_add, contraction prefix guard |
| [Context Learning & Next-Word](./context-learning-and-next-word.md) | Persistent learned n-gram LM, master privacy gate, next-word prediction (on by default), suggestion provenance, learned-data manager |
| [Geometric Swipe Engine](./geometric-swipe-engine.md) | Layout-agnostic geometric swipe decoder (`swipe/geometric/`) |
| [CTC Swipe Engine](./ctc-swipe-engine.md) | CTC trie-beam swipe engine — the DEFAULT `ctc` mode, CleverKeys-trained ONNX encoder |
| [CTC Architecture & Multi-Script Guide](./ctc-architecture-and-multiscript-guide.md) | Reference: CTC graph contract, routing rule, multi-script recipe |
| [CTC Context Rescoring & Tunables](./ctc-context-rescoring-and-tunables.md) | Bigram/trigram swipe rescoring (built, CLOSED default-OFF 2026-08-26) and the decode-time tunables inventory |

> The ONNX transformer engine's specs (`neural-prediction`, `neural-multilanguage-architecture`,
> `kv-cache-optimization`, `nn-inference-optimization`, `memory-pool-optimization`) moved to
> `docs/history/neural-engine/` when that engine was removed — see ADR-011.

### Dictionary & Language

| Spec | Description |
|------|-------------|
| [Dictionary System](./dictionary-and-language-system.md) | Word lookup, frequency ranking, user dictionary |
| [Secondary Language](./secondary-language-integration.md) | Multi-language typing, language detection |
| [Language-Specific Dictionary](./language-specific-dictionary-manager.md) | Per-language dictionary management |
| [English Dictionary Pipeline](./english-dictionary-pipeline.md) | Dictionary generation and quality pipeline (evidence classifier) |
| [Autocorrect Ranking](./autocorrect-ranking.md) | Tap autocorrect: noisy-channel rule and weights (`AutocorrectTuning`), gates, κ/δ, prefix length, Persian half-space offer rule; evidence pointers |

### Customization

| Spec | Description |
|------|-------------|
| [Short Swipe Customization](./short-swipe-customization.md) | Per-key gesture customization, CommandRegistry |
| [Per-Layout Subkey](./per-layout-subkey-customization.md) | Proposal only — NOT BUILT (mappings are global) |
| [Subkey Customization](./subkey-customization-current.md) | Current subkey implementation details |
| [Subkey Popover](./subkey-popover.md) | Hold-then-select 3×3 subkey grid, neutral zone, assign/edit screens |
| [Profile System](./profile_system_restoration.md) | Settings backup/restore, layout import/export |

### Settings & Modes

| Spec | Description |
|------|-------------|
| [Settings System](./settings-system.md) | SharedPreferences, settings UI, configuration |
| [Settings-Layout Integration](./settings-layout-integration.md) | GUI mapping to layout system |
| [Cursor Navigation](./cursor-navigation-system.md) | Spacebar slider, arrow key navigation |
| [TrackPoint Mode](./trackpoint-navigation-mode.md) | Joystick-style cursor control |
| [Selection-Delete Mode](./selection-delete-mode.md) | Text selection via backspace gesture |
| [Password Field Mode](./password-field-mode.md) | Secure password input handling |

### Features

| Spec | Description |
|------|-------------|
| [Quick Settings Tile](./quick-settings-tile.md) | Shade tile: minimize / restore / show the keyboard, picker fallback (2026-10-20) |
| [Clipboard System](./clipboard-system.md) | 3-tab clipboard (history/pinned/todos), schema V5 (`DATABASE_VERSION = 5`: #156 private copy), media, tags, todos |
| [Clipboard Privacy](./clipboard-privacy.md) | Password manager exclusion + media privacy gating |
| [Keyboard Minimize](./keyboard-minimize.md) | Assignable bar and floating-button minimize commands, lifecycle and touch geometry |
| [Timestamp Keys](./timestamp-keys.md) | Date/time insertion keys |
| [Termux Integration](./termux_integration.md) | Terminal keyboard optimizations |
| [GIF Panel](./gif-panel-spec.md) | Offline GIF panel and pack system |

### Reference

| Spec | Description |
|------|-------------|
| [Architectural Decisions](./architectural-decisions.md) | ADRs for major design choices (ADR-001 … ADR-011; ADR-008 superseded) |
| [Testing Strategy](./testing-strategy.md) | Executed JVM/MockK/native evidence, expanded gap coverage and pinned ew-cli workflow |
| [Performance Optimization](./performance-optimization.md) | Targets and strategies; `CleanupHandler`; no monitoring system exists |
| [Typo-Drop Rescue Pipeline](./typo-drop-rescue-pipeline.md) | Superseded 2026-07-02 by `scripts/build_wordlist.py`; kept for provenance |
| [SPEC_TEMPLATE.md](./SPEC_TEMPLATE.md) | Template for new specifications |

## Key Source Files

| File | Purpose |
|------|---------|
| `CleverKeysService.kt` | Main InputMethodService |
| `Keyboard2View.kt` | Custom view rendering |
| `KeyboardData.kt` | Key layout model (there is no `Keyboard2.kt`) |
| `KeyEventHandler.kt` | Key press processing + text editing (direct `InputConnection` calls; no `InputConnectionManager` exists — see core-keyboard-system.md) |
| `KeyboardReceiver.kt` | `IReceiver` impl: `InputConnection` access, event routing |
| `Pointers.kt` | Touch handling and gestures |
| `Config.kt` | All settings with defaults |
| `activities/SettingsActivity.kt` + `ui/settings/` | Settings UI (Jetpack Compose) |
| `KeyValue.kt` | Key definitions (300+ keys) |
| `swipe/CtcEngineAdapter.kt` | Swipe decoding (CTC) |
| `DictionaryManager.kt` | Dictionary management |

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    CleverKeysService                        │
│                  (InputMethodService)                       │
├─────────────────────────────────────────────────────────────┤
│  ┌────────────────┐  ┌────────────────┐  ┌──────────────┐  │
│  │ Keyboard2View  │  │ KeyEventHandler│  │ Keyboard     │  │
│  │ (Rendering)    │  │ (Processing +  │  │ Receiver     │  │
│  │                │  │  text commit)  │  │ (IReceiver:  │  │
│  │                │  │                │  │  InputConn.) │  │
│  └───────┬────────┘  └───────┬────────┘  └──────┬───────┘  │
│          │                   │                   │          │
│  ┌───────▼────────┐  ┌───────▼────────┐         │          │
│  │  KeyboardData  │  │   Pointers     │         │          │
│  │ (Layout model) │  │ (Touch/Gesture)│         │          │
│  └───────┬────────┘  └───────┬────────┘         │          │
│          │                   │                   │          │
│  ┌───────▼───────────────────▼───────────────────▼───────┐  │
│  │                    Config                              │  │
│  │            (Settings, Preferences)                     │  │
│  └────────────────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────────────┤
│                    Swipe Prediction Pipeline                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │  CtcEngine   │  │  ONNX CTC    │  │  Trie beam   │       │
│  │  Adapter /   │→ │  encoder     │→ │  over merged │       │
│  │  Geometric   │  │  (or SHARK2) │  │   lexicon    │       │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
└─────────────────────────────────────────────────────────────┘
```

## Common Patterns (real entry points, 2026-10-23)

### Settings Access

```kotlin
// Read: Config is a snapshot rebuilt by ConfigurationManager on every pref change
val value = Config.globalConfig().some_field          // or globalConfigOrNull()

// Write: only the settings UI writes prefs, through a SettingsActivity extension
internal fun SettingsActivity.saveSetting(key: String, value: Any)   // ui/settings/SettingsPersistence.kt
// There is no Config.saveSetting; ConfigurationManager.onSharedPreferenceChanged → Config.refresh
```

### Key Event Handling

```kotlin
// KeyEventHandler.kt — the IKeyEventHandler entry points called by Pointers
override fun key_down(key: KeyValue?, isSwipe: Boolean)                                   // :75
override fun key_up(key: KeyValue?, mods: Pointers.Modifiers, isKeyRepeat: Boolean)      // :99
private fun handleEditingKey(ev: KeyValue.Editing)                                        // :861 — the one implementation of editing commands
```

### Gesture Classification

```kotlin
// gesture/GestureClassifier.kt (pure)
class GestureClassifier {
    enum class GestureType { … }
    data class GestureData(…)
    fun classify(gesture: GestureData, config: ConfigSnapshot): GestureType               // :47
}
// Short-swipe vs TrackPoint vs selection-delete vs popover routing itself lives in Pointers.kt
```

## Related Documentation

- [User Guide Wiki](../wiki/TABLE_OF_CONTENTS.md) - End-user documentation
- [CHANGELOG](../../CHANGELOG.md) - Version history
- [TODO](../../memory/todo.md) - Current tasks
