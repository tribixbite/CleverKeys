# Architecture — Verification & Remediation

_Adversarial re-verification of the prior architecture audit against HEAD (`main`).
All counts re-derived from source, not copied from the prior audit._

## Verification Results

| # | Finding (prior claim) | Verdict | Fresh evidence (file:line) |
|---|---|---|---|
| 1 | CLAUDE.md documents `tribixbite/keyboard2/` with `core/neural/data/config/utils/testing` subdirs that don't exist; real package is `tribixbite/cleverkeys/` with a different subdir set | **CONFIRMED** | `CLAUDE.md:91-99` shows the fictional tree. `ls src/main/kotlin/tribixbite/keyboard2` → *No such file or directory*. Real subdirs: `autocorrect, autofill, backup, clipboard, contextaware, customization, gif, langpack, ml, onnx, personalization, prefs, theme, ui`. NONE of `core/neural/data/config/utils/testing` exist as dirs. |
| 2 | 57% flat root: 158 of 277 files directly in `tribixbite/cleverkeys/`, incl. 9 `Clipboard*.kt` despite a `clipboard/` package, 15 Activities | **CONFIRMED (numbers slightly higher)** | Total `.kt` under `cleverkeys/` = **277**. Directly in the root dir = **158** (57.0%). `Clipboard*.kt` in root = **11** (not 9): `ClipboardDatabase, ClipboardEntry, ClipboardHistoryCheckBox, ClipboardHistoryService, ClipboardHistoryView, ClipboardManager, ClipboardMediaManager, ClipboardPinView, ClipboardSearchUtils, ClipboardSettingsActivity, ClipboardTagDialog`. `*Activity.kt` in root = **15** (matches). Note: `clipboard/` package holds **4** files, but all are `clipboard/sanitize/*` (`UrlSanitizer, SanitizationConfig, RulesetParser, Ruleset`) — the prior "clipboard/ (4 files)" is real but they are URL-sanitization, unrelated to the 11 root Clipboard files. |
| 3 | Config is a 1,615-line global mutable bag; ~150 `@JvmField var`; `globalConfig()=_globalConfig!!` force-unwrap at Config.kt:1183; 21+ files read statically | **CONFIRMED (all numbers verified, consumers higher)** | `wc -l Config.kt` = **1615**. `@JvmField var` = **157** mutable public fields (`@JvmField val` = 5). `Config.kt:1165` `_globalConfig: Config? = null`; `Config.kt:1183` `fun globalConfig(): Config = _globalConfig!!`; `Config.kt:1186` `globalPrefs() = _globalConfig!!._prefs` (second force-unwrap). Static consumers = **28 files** call `Config.globalConfig()` (prior said "21+"; confirmed and undercounted). |
| 4 | Service-as-singleton escape hatch: `getInstance()` + static `findKeyByChar()` + `_customizationMode` global at CleverKeysService.kt:157-173 | **CONFIRMED** | `CleverKeysService.kt:155` `private var _customizationMode: Boolean = false`; `:164` `_customizationMode = enabled`; `:169` `isCustomizationMode()`; `:173` `fun getInstance(): CleverKeysService? = _instance`; `:184` `fun findKeyByChar(char: String): KeyboardData.Key?` (static companion). Line numbers shifted ~+16 from the prior audit but all constructs present. |
| 5 | 10 glue files (4 Bridges + 6 Initializers), no DI framework, hand-wired in onCreate | **CONFIRMED** | All 10 files exist in root. Wired in `CleverKeysService` onCreate via factory `.create(...)`: `KeyEventReceiverBridge.create` (`:339`), `ManagerInitializer.create(...).initialize()` (`:397`), `SuggestionBridge.create` (`:411`), `NeuralLayoutBridge.create` (`:422`), `PredictionInitializer.create` (`:426`), `PropagatorInitializer.create` (`:443`), `SubtypeLayoutInitializer.create` (`:525`), `LayoutBridge` assigned from `SubtypeLayoutInitializer` result (`:532`). No Hilt/Koin/Dagger in the build. Combined size of the 10 files = **1,458 lines**. |
| 6 | 19 files ≥1000 lines; WordPredictor 2335, OptimizedVocabulary 2045, Pointers 1870, Keyboard2View 1790, all interface-free | **PARTIAL** | Files ≥1000 lines across `src/main` = **18** (not 19). Line counts confirmed exactly: WordPredictor **2335**, OptimizedVocabulary **2045**, Pointers **1870**, Keyboard2View **1790**. "Interface-free" is **imprecise**: `Keyboard2View` implements `View.OnTouchListener, Pointers.IPointerEventHandler` (`Keyboard2View.kt:69`) and `Pointers` defines/uses `IPointerEventHandler`. `WordPredictor` (`:26`) and `OptimizedVocabulary` (`:32`) implement no interface. The valid core of the claim: none of the four expose an **extracted abstraction for their own public API** (no `WordPredictor`/`Vocabulary` interface a caller/test can substitute). |
| 7 | snake_case Java residue coexists with camelCase | **CONFIRMED** | Config.kt has 136 `@JvmField` snake_case fields (`swipe_dist_px`, `haptic_key_press`, `margin_bottom`…). **147** `fun x_y(` snake_case method definitions across the package (`handle_event_key`, `set_shift_state`, `on_change`, `load_from_preferences`…). Mixed with camelCase throughout — direct port residue from the Java `Unexpected-Keyboard` original. |

**Summary of confirmed magnitudes:** 277 files, 158 flat-root (57%), Config 1615 lines / 157 mutable public fields / 2 `!!` unwraps / 28 static consumers, 10 glue files (1458 lines), 18 files ≥1000 lines, 147 snake_case methods.

---

## Remediation Steps (leverage-ordered, incremental)

Ordered by **(risk-reduction + effort payoff) ÷ blast-radius**. Every step is a strangler-fig increment that compiles and ships on its own.

### R1 — Fix CLAUDE.md doc drift `[15 min, zero code risk, highest leverage]`
Pure docs, unblocks every future contributor/agent that trusts the map. See **Package Reorg Plan → §Doc edits**. Milestone: `rg 'keyboard2' CLAUDE.md` returns 0 hits; the tree matches `ls cleverkeys/`.

### R2 — Make `globalConfig()` null-safe `[1 hr, low risk]`
Eliminate the two `!!` crash points (`Config.kt:1183`, `:1186`) that fault if any consumer runs before `initGlobalConfig()`. This is the single highest-severity runtime finding.
- Add `fun globalConfigOrNull(): Config? = _globalConfig` and `fun isInitialized(): Boolean = _globalConfig != null`.
- Keep `globalConfig()` but replace `!!` with an explicit throw carrying context: `?: error("Config.globalConfig() called before initGlobalConfig(); caller=${Thread.currentThread().stackTrace...}")`. Same crash surface, but diagnosable instead of a bare NPE.
- Migrate the handful of early/edge consumers (tile service, direct-boot, receivers) to `globalConfigOrNull()?.let{}`.
- **Milestone:** `rg '_globalConfig!!' Config.kt` → 0 hits; a JVM test that calls `globalConfigOrNull()` pre-init returns null instead of throwing.

### R3 — Introduce `ConfigSnapshot` immutable read-model for hot paths `[phased, medium risk]`
The strangler seam for Finding #3. Details in **Config Immutability Migration Plan**. Deliver behind the existing `Config` object so no consumer breaks. **Milestone:** hot-path readers (`Pointers`, `Keyboard2View`, `Gesture`, `GestureClassifier`) read from an immutable snapshot; static `Config.globalConfig()` consumer count drops from 28 toward 0, tracked by CI grep.

### R4 — Package reorg: collapse the flat root `[phased, low risk per Kotlin]`
Move the obvious clusters (11 Clipboard, 6 Emoji, 15 Activities) into packages. Kotlin decouples package from directory, and these are `package tribixbite.cleverkeys` files, so the **safe** first move is directory-only (git mv, no package rename) which touches zero imports. See **Package Reorg Plan**. **Milestone:** flat-root file count 158 → <100, tracked per PR.

### R5 — Composition-root object to retire the 6 Initializers `[medium risk]`
Replace the 6 `*Initializer` factories (612 lines) with one hand-written `KeyboardComponentGraph` object. Keeps the 4 Bridges (they are genuine adapter/delegation seams, not pure wiring). No Hilt/Koin needed. See **Bridge Consolidation** below. **Milestone:** 6 initializer files → 1 graph file; onCreate wiring reads top-to-bottom in one place.

### R6 — Extract interfaces for the 2 pure-logic monsters `[per-class, medium]`
`WordPredictor` and `OptimizedVocabulary` implement nothing and are impossible to fake in tests. Extract `interface Predictor` / `interface Vocabulary` (their existing public surface), have the classes implement them, and change callers to depend on the interface. Enables test doubles and unblocks later size reduction. Do NOT attempt to split the 2000-line bodies yet — interface-first is the low-risk beachhead. **Milestone:** at least one test uses a fake `Predictor`.

### R7 — snake_case → camelCase mechanical rename `[low value, defer]`
147 methods + 136 fields. Cosmetic; each rename ripples across call sites and the `@JvmField` snake names are load-bearing for `refresh()`/SharedPreferences string keys in some spots. Lowest leverage — do opportunistically during R3/R6 file touches, never as a standalone churn PR.

---

## Config Immutability Migration Plan (concrete first steps)

**Goal:** a read-only `ConfigSnapshot` consumed by hot paths, with mutable `Config` remaining the write/init side. Snapshot is rebuilt whenever `Config.refresh()` runs; readers get a stable, thread-safe value.

**Design:**
```
Config (mutable, existing)          ConfigSnapshot (new, immutable data class)
  - initGlobalConfig()   ──emit──▶    val swipeDistPx: Float
  - refresh(res, fold)                val slideStepPx: Float
  - 157 @JvmField var                 val hapticKeyPress: Boolean
                                       ... (only fields hot paths actually read)
```

**Step 1 — Create the snapshot type + builder (no consumer changes).**
- New file `prefs/ConfigSnapshot.kt`: `data class ConfigSnapshot(...)` containing ONLY the fields the four hot-path classes read (derive the set with `rg 'Config\.globalConfig\(\)\.\w+' Pointers.kt Keyboard2View.kt Gesture.kt GestureClassifier.kt`). Start with ~15-25 fields, not all 157.
- In `Config`, add `@Volatile var snapshot: ConfigSnapshot` and rebuild it at the END of `refresh()` (`Config.kt:661`) and in the constructor. This is additive; nothing reads it yet.
- **Measure:** snapshot builds without allocation regressions (it's one small object per refresh, ~O(1)/config change, not per-keystroke).

**Step 2 — Migrate ONE hot-path file (`Gesture.kt` — smallest reader).**
- Replace `Config.globalConfig().swipe_dist_px` with a passed-in `ConfigSnapshot` (constructor param or method arg). Gesture is leaf-ish, so blast radius is small.
- Add a JVM test constructing `Gesture` with a hand-built `ConfigSnapshot` — proving the class is now testable without the global.
- **Measure:** static-consumer count 28 → 27 (`rg -l 'Config\.globalConfig\(\)' src/main/kotlin | wc -l` in CI).

**Step 3 — Migrate `Pointers` + `Keyboard2View` (the touch hot loop).**
- Thread the snapshot in via the existing `IPointerEventHandler` seam / constructor rather than static reads. `Keyboard2View` already holds a `Config` reference in practice, so add a `currentSnapshot()` accessor and switch its per-frame reads to it.
- **Measure:** 27 → ~24; add a CI check `test -eq 0 count-of-globalConfig-in-hotpath-files` gating the four files specifically.

**Progress metric (single number):**
```
BASELINE=28   # rg -l 'Config\.globalConfig\(\)' src/main/kotlin --glob '!**/Config.kt' | wc -l
```
Track this per PR; target ≤10 (leave rarely-run Activities on the static accessor — they're not hot and migrating them is pure churn). Immutability is "done enough" when the four hot-path classes hold zero static `Config.globalConfig()` reads.

**Guardrails:** do NOT try to freeze `Config` itself or delete the 157 `var`s — `refresh()` mutates them and 28 files + tests depend on them. The snapshot is a *read model layered on top*, migrated file-by-file. Big-bang immutability of `Config` would touch every consumer at once and is explicitly out of scope.

---

## Package Reorg Plan (cluster → destination map)

Kotlin does not enforce package==directory, and all root files are `package tribixbite.cleverkeys`. So the **safe increment is directory-only moves that keep the package statement unchanged** → zero import edits, zero risk, just a cleaner tree. A later optional pass can align `package` names with dirs (touches imports; do per-cluster).

| Cluster (files) | Destination dir | Notes |
|---|---|---|
| 11 `Clipboard*.kt` (`ClipboardDatabase, ClipboardEntry, ClipboardHistoryCheckBox, ClipboardHistoryService, ClipboardHistoryView, ClipboardManager, ClipboardMediaManager, ClipboardPinView, ClipboardSearchUtils, ClipboardTagDialog`) + `PinnedEntry, TodoEntry` | `clipboard/` | Existing `clipboard/` only holds `sanitize/`; these belong there. Leave `ClipboardSettingsActivity` for the Activities cluster. |
| 6 `Emoji*.kt` (`Emoji, EmojiGridView, EmojiGroupButtonsBar, EmojiKeywordIndex, EmojiSearchManager, EmojiTooltipManager`) + `ComposeKey*, Modmap` | `emoji/` (new) | Or `ui/emoji/`. Self-contained. |
| 15 `*Activity.kt` | `ui/settings/` (settings screens) + `activities/` (launcher/manager) | `ui/settings/` already exists with `sections/`. Move `SettingsActivity, NeuralSettingsActivity, AutoCorrectionSettingsActivity, ThemeSettingsActivity, ClipboardSettingsActivity, ExtraKeysConfigActivity` there; `LauncherActivity, DictionaryManagerActivity, LayoutManagerActivity, TemplateBrowserActivity, SwipeDebugActivity, Swipe/ShortSwipeCalibrationActivity, ShortSwipeCustomizationActivity, BackupRestoreActivity` → `activities/`. |
| Bridges/Initializers (10) | `di/` or `wiring/` (new) | Co-locate the composition root (R5) here. |
| Gesture/swipe cluster (`Gesture, GestureClassifier, GestureRecognizerTypes, ContinuousGestureRecognizer, EnhancedSwipeGestureRecognizer, ImprovedSwipeGestureRecognizer, SwipeInput, SwipePruner, SwipeResampler, SwipeTokenizer, SwipeTrajectoryProcessor, Trajectory*`) | `gesture/` (new) | Large but cohesive; move last, after Clipboard/Emoji prove the workflow. |

**Move recipe (per cluster):** `git mv src/.../cleverkeys/Foo.kt src/.../cleverkeys/<dir>/Foo.kt`, keep `package tribixbite.cleverkeys` unchanged → compiles with no import churn. Verify with `./gradlew compileDebugKotlin`. Do one cluster per PR.

**Doc edits (R1) — replace `CLAUDE.md:88-100` block with the real tree:**
```
src/main/kotlin/tribixbite/cleverkeys/
├── (root)                          # Core keyboard, prediction, config (being decomposed)
├── autocorrect/  autofill/         # Correction + suggestion-autofill
├── backup/                         # Settings import/export + diff engine
├── clipboard/                      # Clipboard DB, media, sanitize/
├── contextaware/  personalization/ # Context + user adaptation
├── customization/                  # Short swipes, profiles, command palette
├── gif/  langpack/                 # GIF packs, language packs
├── ml/  onnx/                      # ONNX neural prediction (NO CGR)
├── prefs/                          # Config snapshot + preference helpers
├── theme/  ui/                     # Theming + Compose/View UIs (ui/settings, ui/customization)
```
Also change the header `tribixbite/keyboard2/` → `tribixbite/cleverkeys/` and delete the fictional `core/ neural/ data/ config/ utils/ testing/` rows.

---

## Bridge Consolidation (R5 detail)

**Keep the 4 Bridges** (`KeyEventReceiverBridge, SuggestionBridge, NeuralLayoutBridge, LayoutBridge`, 657 lines). They are genuine *delegation adapters* — `CleverKeysService` forwards `getCurrentLayout()`, `setTextLayout()`, etc. through `LayoutBridge` (`CleverKeysService.kt:238-322`). Removing them would re-inflate the 1029-line service. They earn their keep.

**Retire the 6 Initializers** (`ManagerInitializer, PredictionInitializer, PropagatorInitializer, ReceiverInitializer, SubtypeLayoutInitializer, SuggestionBarInitializer`, 801 lines). These are pure *construction* — each is a `create()` + `initialize()` that news up objects and returns a result data class. That is exactly what a **manual composition root** does, spread across 6 files.

**Proposal:** a single `wiring/KeyboardComponentGraph.kt` object:
```kotlin
class KeyboardComponentGraph(private val service: CleverKeysService, private val config: Config) {
    // lazy-built singletons, constructed in dependency order — the whole graph readable in one file
    val keyboardView by lazy { ... }
    val keyEventHandler by lazy { KeyEventHandler(receiverBridge) }
    val managers by lazy { /* was ManagerInitializer */ }
    val predictionCoordinator by lazy { /* was PredictionInitializer */ }
    ...
}
```
`onCreate` becomes `graph = KeyboardComponentGraph(this, config)` + a few `graph.x` reads. **No DI library** — `by lazy` gives ordering + single-instance semantics that a hand-written graph needs, and it stays debuggable/steppable (a real concern given the device's ADB/testing constraints). This is a lateral move (6 files → 1) that improves readability without new dependencies; schedule it AFTER R2/R3 since it touches the service hot path.

---

## Refutations / Corrections

- **#2 clipboard count:** prior "9 `Clipboard*.kt`" is actually **11**. And "clipboard/ package (4 files)" is technically true but misleading — those 4 are `clipboard/sanitize/*` (URL sanitization), not the clipboard-history feature; the 11 history files are NOT in the package.
- **#3 static consumers:** "21+" is an **undercount** — the real number is **28** files.
- **#3 force-unwrap:** there are **two** `!!` sites, not one — `globalConfig()` (`:1183`) AND `globalPrefs()` (`:1186`).
- **#6 file count:** "19 files ≥1000 lines" is **18**.
- **#6 "interface-free":** **imprecise** — `Keyboard2View` implements `View.OnTouchListener, Pointers.IPointerEventHandler` and `Pointers` uses `IPointerEventHandler`. The defensible claim is narrower: these classes expose no *substitutable abstraction of their own public API*, so they can't be faked in tests. WordPredictor and OptimizedVocabulary do implement nothing.
- Everything else (#1, #4, #5, #7 and the core magnitudes of #2/#3/#6) is **CONFIRMED** with the numbers meeting or exceeding the prior claims.

---

## Effort Estimate to Reach A Grade

| Step | Effort | Risk | Grade impact |
|---|---|---|---|
| R1 doc fix | 0.25 hr | none | removes the single most-misleading artifact |
| R2 null-safe globalConfig | 1 hr | low | closes the one P1 crash vector |
| R3 ConfigSnapshot (steps 1-3, 4 hot files) | 1.5-2 days | medium | the core P1 fix; testable hot paths |
| R4 package reorg (Clipboard+Emoji+Activities, dir-only) | 1 day | low | 158→<100 flat root |
| R5 composition-root object | 1 day | medium | 6 initializers → 1 graph |
| R6 Predictor/Vocabulary interfaces | 0.5 day each | medium | unblocks unit-testing the monsters |
| R7 snake_case rename | opportunistic | low | cosmetic |

**Total to A-grade architecture: ~5-6 focused days**, all as independent shippable increments. R1+R2 (≈1.25 hr) alone clear both P1-severity runtime/doc risks and are worth doing immediately. The remaining structural work (R3-R6) is strangler-fig — no big-bang rewrite, app ships after every PR.
