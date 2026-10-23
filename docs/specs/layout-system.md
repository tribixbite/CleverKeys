# Layout System

## Overview

The layout system manages dynamic key injection through ExtraKeys, position hints, and script-aware customization. It enables users to add keys (F-keys, accents, symbols) to any layout with intelligent placement based on script compatibility and position preferences.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/ExtraKeys.kt` | `ExtraKeys`, `ExtraKey` | Extra key definitions and computation |
| `src/main/kotlin/tribixbite/cleverkeys/KeyboardData.kt` | `PreferredPos`, `addExtraKeys()` | Layout data, position hints |
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `extra_keys_param` | User preferences for extra keys |
| `src/main/layouts/*.xml` | Layout definitions | The 89 built-in layouts (+ `LICENSE`). The build copies them into `build/generated/layouts/res` (`build.gradle:385`), so code loads them as `R.raw.<name>`; `res/xml/` holds only `bottom_row.xml` and the manifest rule files |
| `src/main/kotlin/tribixbite/cleverkeys/LayoutModifier.kt` | `LayoutModifier`, `LayoutCacheKey` | Applies extra keys / Enter / switch keys to a layout and caches the result |

## Architecture

```
User Preferences (strings)
       ↓ (parse)
ExtraKeys.parse("f1|accent@e")
       ↓
ExtraKeys(List<ExtraKey>)
       ↓ (compute with Query)
Map<KeyValue, PreferredPos>
       ↓
KeyboardData.addExtraKeys()
       ↓
Final Layout with injected keys
```

## Configuration

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `extra_key_<name>` | Boolean each | — | one pref per catalogued extra key (`ExtraKeysPreference`); `Config.extra_keys_param = ExtraKeysPreference.get_extra_keys(prefs) ?: emptyMap()` (`Config.kt:1216`) is the resulting `Map<KeyValue, PreferredPos>` |
| custom extra keys (`CustomExtraKeysPreference`) | list | empty | user-typed keys; `Config.extra_keys_custom = CustomExtraKeysPreference.get(prefs) ?: emptyMap()` (`Config.kt:1217`), also a `Map<KeyValue, PreferredPos>` — not a pipe-separated string |

## Implementation Details

### ExtraKey Data Structure

```kotlin
// ExtraKeys.kt:15 — nested, internal; constructed by ExtraKey.parse(str, script)
internal class ExtraKey(
    val kv: KeyValue,              // Key to add
    val script: String?,           // Script constraint (null = any)
    val alts: List<KeyValue>,      // Alternatives: don't add if these are present
    val nextTo: KeyValue?          // Position hint
)
```

### String Parsing Format

```
Format: "key:alt1:alt2@next_to|key2|key3:alt@pos"

Examples:
  "f11_placeholder"           → Simple key, no constraints
  "accent_aigu:´@e"          → Accent near 'e', alternative is ´
  "f1|f2|f3"                 → Multiple keys (pipe-separated)
```

### Parsing (as built, `ExtraKeys.kt`)

```kotlin
// ExtraKeys.kt:78 — one entry: "key name" or "key name:alt1:alt2@next_to"
ExtraKey.parse(str: String, script: String): ExtraKey      // split "@" (limit 2) then ":"; KeyValue.getKeyByName on each part
// ExtraKeys.kt:105 — a whole preference string
ExtraKeys.parse(script: String, str: String): ExtraKeys    // splits on "|" and maps ExtraKey.parse
ExtraKeys.merge(kss: List<ExtraKeys>): ExtraKeys           // ExtraKeys.kt:119; same-key entries combine via ExtraKey.mergeWith
```

There is no `getSystemExtraKeys()` and no `prefs.getString("extra_keys_custom")`: the two
sources are the per-key booleans read by `ExtraKeysPreference.get_extra_keys(prefs)` and the
custom list read by `CustomExtraKeysPreference.get(prefs)`, both already turned into
`Map<KeyValue, PreferredPos>` by the time `Config.refresh` stores them (`Config.kt:1216-1217`).
`LayoutModifier` adds those maps (plus the subtype's extra keys) to the layout.

### Compute Algorithm

The compute algorithm determines which keys to add based on query context:

```kotlin
data class Query(
    val script: String?,           // Current layout script
    val present: Set<KeyValue>     // Keys already on layout
)

fun ExtraKey.compute(dst: MutableMap<KeyValue, PreferredPos>, query: Query) {
    // Step 1: Alternative substitution
    // If only 1 alternative and main key not present → use alternative
    val useAlternative = (alternatives.size == 1 && !dst.containsKey(kv))

    // Step 2: Script compatibility
    // null script matches any; otherwise exact match required
    val scriptMatches = (query.script == null || script == null || query.script == script)

    // Step 3: Presence check
    // Add if no alternatives OR some alternative missing from layout
    val shouldAdd = (alternatives.isEmpty() || !query.present.containsAll(alternatives))

    if (scriptMatches && shouldAdd) {
        val keyToAdd = if (useAlternative) alternatives[0] else kv
        val pos = nextTo?.let { PreferredPos(it) } ?: PreferredPos.DEFAULT
        dst[keyToAdd] = pos
    }
}
```

### Compute Example

```
Given: ExtraKey(kv=accent_aigu, alts=[´], script=latin, nextTo=e)
Query: script=latin, present={a,b,c,e,f}  (note: ´ NOT present)

Step 1: Alternative substitution?
  alternatives.size == 1 && !dst.containsKey(accent_aigu)
  → true → keyToAdd = ´

Step 2: Script compatibility?
  "latin" == "latin" → true

Step 3: Presence check?
  !{a,b,c,e,f}.containsAll([´]) → true (´ is missing)

Result: Adds ´ key with hint to place near 'e'
```

### Merge Algorithm

Combines multiple ExtraKeys sources (user prefs + layout defaults + system):

```kotlin
fun ExtraKey.mergeWith(other: ExtraKey): ExtraKey {
    require(kv == other.kv)
    return ExtraKey(
        kv = kv,
        script = if (script == other.script) script else null,  // Generalize
        alternatives = (alternatives + other.alternatives).distinct(),
        nextTo = nextTo ?: other.nextTo  // Prefer non-null
    )
}

fun ExtraKeys.Companion.merge(keysList: List<ExtraKeys>): ExtraKeys {
    val mergedMap = mutableMapOf<KeyValue, ExtraKey>()
    for (ks in keysList) {
        for (key in ks.keys) {
            val existing = mergedMap[key.kv]
            mergedMap[key.kv] = existing?.let { key.mergeWith(it) } ?: key
        }
    }
    return ExtraKeys(mergedMap.values.toList())
}
```

### Position Hint System

```kotlin
data class PreferredPos(
    var nextTo: KeyValue? = null
) {
    companion object {
        val DEFAULT = PreferredPos(null)
    }
}

// In KeyboardData.kt
fun addKeyAtPreferredPosition(kv: KeyValue, pos: PreferredPos) {
    if (pos.nextTo != null) {
        // Find target key and insert nearby
        val targetRow = findRowContaining(pos.nextTo)
        val targetIndex = findKeyIndex(pos.nextTo)
        insertKeyAt(targetRow, targetIndex + 1, kv)
    } else {
        // Add to default extra keys row
        extraKeysRow.add(kv)
    }
}
```

### Integration with Config

```kotlin
// Config.kt
fun loadExtraKeys(prefs: SharedPreferences): ExtraKeys {
    val customStr = prefs.getString("extra_keys_custom", "") ?: ""
    val custom = ExtraKeys.parse(null, customStr)

    return ExtraKeys.merge(listOf(
        getSystemExtraKeys(),  // F-keys, common symbols
        custom
    ))
}
```

## Layout XML Format

```xml
<!-- src/main/layouts/latn_qwerty_us.xml (served as R.raw.latn_qwerty_us) -->
<keyboard name="qwerty" script="latin">
    <row>
        <key key0="q" key1="1" key2="!" />
        <key key0="w" key1="2" key2="@" />
        <!-- ... -->
    </row>
    <!-- ... -->
</keyboard>
```

Key attributes:
- `key0`: Primary key (tap)
- `key1`-`key8`: Swipe directions (N, NE, E, SE, S, SW, W, NW)
- `width`: Key width multiplier (default 1.0)
- `shift`: Behavior on shift (0=normal, 1=uppercase)

### Row height and numeric scaling (GH #90)

`bottom_row="false"` controls whether the standard bottom row is appended. It does
not identify a numeric layout or request full-height scaling. Text, macro and navigation
layouts with fewer rows keep the normal row unit, so a single-row board stays compact.

`numpad_height="true"` opts a layout into Appearance → Scale Numpad Height. It defaults
to `false` when absent. The built-in `numeric.xml` and `pin.xml` opt in explicitly;
a custom numeric layout can opt in using the same attribute. With the preference off,
even opted-in layouts use the normal row unit.

`Theme.Computed` divides the configured height by `keysHeight` only when both the
preference and the XML flag are true; otherwise it divides by the normal 3.95 units.
Row `height` and `shift` remain proportional, and the height of the rows is capped at
the screen height before outer margins. All `KeyboardData` transformations preserve the
immutable flag from the source layout; appending a numpad to a text board does not opt
the text board into scaling.
Custom layout persistence stores original XML, and mapping export preserves root attributes.

`NumpadKeySizeTest` exercises production XML parsing and theme geometry in `runMockTests`,
including compact custom rows, fractional row height/shift, numeric/PIN preference on/off,
flag defaults, transformed layouts and the screen-height cap. The existing #58 width tests
retain their release-anchored names.

### Language binding (`language`, GH #186/#61)

`<keyboard language="fa" …>` declares the layout's DEFAULT language binding. The parser keeps
the raw value in `KeyboardData.declared_language` and never fails on it (a stored layout must
not disappear over its attribute); `LayoutLanguageBinding.effective` validates it with the
language-pack code shape (`^[a-z]{2,3}(?:[_-][a-z0-9]{1,16}){0,4}$`, case-insensitive) and the
Layout Manager editor refuses to save an invalid value. Built-in layouts declare no language.

The user's choice is stored per entry of the `layouts` preference (`language` field: absent =
use the XML default, `"none"` = explicitly unbound, a code = bound) and overrides the XML
default. While a bound layout is current, its language is the only active language for
prediction, autocorrect, swipe routing, contractions and learning. Resolution, the single
language-change path and the secondary-language policy are specified in
[dictionary-and-language-system.md](dictionary-and-language-system.md#per-layout-language-binding-gh-186-gh-61--2026-10-07).

### Per-layout theme binding (`theme`, 2026-10-15)

Each layout entry can render in its own theme; switching layouts can therefore switch theme.
Modelled on the language binding above, with the same three-state entry and the same storage.

**XML.** `<keyboard theme="rosepine" …>` declares the layout's DEFAULT theme binding. The parser
keeps the raw value in `KeyboardData.declared_theme` and never fails on it. A valid value is a
theme id as Theme Manager stores it in `theme`: a built-in id (`ThemeProvider.BUILT_IN_THEME_IDS`),
`decorative_<predefined id>`, or `custom_<uuid>` — shape `^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$`,
case kept (custom ids are store keys). The Layout Manager editor refuses to save a malformed
value; a well-formed id of a theme that is not on this device is accepted (it may be a DIY theme
from another device). Built-in layouts declare no theme. An imported or added custom layout's
attribute applies as its binding until the user picks something else. **Export XML** writes the
entry's stored choice onto the root (`LayoutThemeBinding.applyToXml`): a theme id sets
`theme="…"`, "Follow global theme" over an XML default removes it, no choice leaves the XML as
written — so re-importing an exported file restores the binding.

**Storage.** The `theme` field of a `layouts` preference entry: absent = use the XML default,
`"none"` = explicitly follow the global theme, an id = bound. A bound named layout uses the
object form `{"kind":"named","name":…,"theme":…}`; unbound entries keep their exact pre-binding
encoding. Loading sanitises the field through `LayoutThemeBinding.normalizeEntry` (a hostile or
malformed value from a backup is dropped, the layout survives). Reordering, editing the XML and
the legacy preference dialog all carry the binding with the entry.

**Resolution** (`LayoutThemeBinding.resolve`, called by `Config.recomputeActiveTheme` at the end
of every `Config.refresh`): the current layout's effective binding (index past the end resolves
to layout 0, as `LayoutManager` does) when `ThemeProvider.isAvailable` says it exists; otherwise
the user's global `theme` preference. `Config.themeName`/`theme` are the EFFECTIVE theme;
the preference is never written by a binding. `layout_bound_theme` / `layout_missing_theme`
expose the state. A binding to a missing theme (deleted DIY theme, backup from another device)
renders the global theme — not ThemeProvider's generic fallback — and Layout Manager shows it.
`Config.themeAvailability` is installed by `CleverKeysService.onCreate` before
`initGlobalConfig`; without it every well-formed id counts as available.

**Applying it.** No new path: `Config.set_current_layout` writes the selection preference, whose
listener runs `ConfigurationManager.refresh`; it compares theme id AND name before/after and
calls `onThemeChanged` only when the rendered theme changed, so switching between layouts with
the same effective theme never re-inflates. `set_current_layout` must not resolve the theme
itself (that would hide the change from the comparison; pinned by `CoreImeHygieneDriftTest`).
`CleverKeysService.onThemeChanged` inflates the new view and, while the keyboard is shown,
rebuilds the whole input hierarchy through `installPredictionViews` (the setup
`onStartInputView` uses), so the suggestion strip survives a mid-typing switch; the
stale-view check in `onStartInputView` compares the theme name as well as the style id
(runtime themes share one style). Layout Manager edits, reorders and keyboard start reach the
same refresh. Deleting a custom theme broadcasts `ACTION_THEME_CHANGED` so bindings to it
re-resolve. Limitation: `swipe_trail_color` stays the global theme's synced colour.

**Backup & Restore.** The binding adds no preference key: it travels inside the `layouts` JSON
blob, which is already classified (`SettingsImportPlanBuilder.JSON_BLOB_KEYS`), so the
three-bucket classification and its drift test are unchanged. The import preview renders each
entry whose effective theme changes (`LayoutThemeBindingDiff`, "<layout>: theme A → B").
Custom themes themselves are not part of a backup; a restored binding to one that is not on the
device degrades to the global theme as above.

**Suggestion bar colours (2026-10-17).** The bar takes every colour from ONE `Theme`: the
rebuilt keyboard view's, which `Keyboard2View` builds from the effective theme
(`SuggestionBarPane.initialize(…, keyboardView.getTheme(), …)`). There is no second source for
text, background, separators, chip accents or ripple, so a bound theme and the global theme
cannot mix in one bar. Each (re)build logs `Suggestion bar built: theme=… text=… highlight=…
bg=…` (tag `PredictionViewSetup`). `PreferenceUIUpdateHandler` reads the CURRENT bar and view
through providers; it used to keep the first ones, so after any rebuild an opacity change went
to a detached bar. The three text roles (chips, highlighted commit chip, prompt chips) pass
through `SuggestionBarPalette.resolve` against the bar background: a role below 3:1 falls back
to the text colour, text below 3:1 to black or white. Readable colours are unchanged; this fixed
highlights that equalled the bar on Cobalt, Pine and ePaper Black (1.00:1, `colorLabelActivated`
was designed for `colorKeyActivated`) and lifted Desert, Jungle, Jewel/CleverKeys Light, Everforest
Light, Rose Pine prompts, and the three pastel and the Desert decorative highlights (Desert's
prompts too). Ordinary chip text needed
no change on any built-in (lowest 4.29:1, Everforest Light). Monet styles are not covered
(wallpaper colours at runtime).

Device report (Pixel, release `d8be659b`, global Cyberpunk, iOS-style bound to Light): after the
switch the bar showed no text at all (uniform grey, no glyphs). Not reproduced on emulator.wtf
Pixel 7 API 34 with the same theme pair and layout, switched by a real space-bar swipe while
shown, in an EditText and in Chrome on a `data:` textarea with `input text` (the device
sequence): the on-screen bar was the one the pipeline writes to and showed black chips
(`#FF000000`, highlight `#FF0066CC`) on `#CCCCCC`, also after hide/show and back to Cyberpunk.
Black on that grey cannot have zero variance, so the device bar held no chips rather than
same-coloured text.

Re-check on the Pixel with the fixed build (2026-10-17 02:54–02:58, after `9e2a9804`;
`build/oct17b-pixel/60–72`, `logcat-b1-ios.txt`, `logcat-b1-global.txt`): iOS-style bound to
Light over global Cyberpunk, Chrome `data:` textarea, typed `hel`/`wor` — the Light bar showed
black chips (`hello | help | hel | held | hell`, highlight blue) on grey, and switching back to the
global layout showed Cyberpunk's cyan chips; the log carried `Suggestion bar built: theme=light
text=#FF000000 highlight=#FF0066CC bg=#FFCCCCCC` on the switch. The empty bar did not recur. Its
cause was not isolated: the fix that landed between the two runs changed the bar's readable-colour
fallbacks and the preference handler's stale bar reference, neither of which explains an empty bar
on the first run, so treat a recurrence as a new report rather than a regression of this fix.

Tests: `LayoutThemeBindingTest` (pure), `LayoutThemeBindingConfigTest` (mock: Config,
ConfigurationManager re-inflate gate, serializer round trip, XML attribute, built-in id drift),
`BackupRestorePreviewRenderTest`, `SettingsImportPlanBuilderTest`, `CoreImeHygieneDriftTest`,
`SuggestionBarPaletteTest` and `PreferenceUIUpdateHandlerViewsTest` (mock),
`LayoutThemeSuggestionBarInstrumentedTest` (ew-cli: the real IME, bound Light over Cyberpunk,
EditText and Chrome).

### CTC swipe eligibility

A layout can reach the CTC engine only when all of these checks pass:

1. Its `script` value is registered as routable in `CtcScriptSupport`.
2. CTC serves the active language.
3. Every character in that language's emission alphabet appears as a **primary/centre**
   key value: `c="…"` in the compact schema or `key0="…"` in the numbered schema.

Directional values (`n`, `ne`, … or `key1`–`key8`) do not satisfy the third check.
The encoder receives geometry only for primary keys, so a letter available solely as a corner
character is still absent from the CTC layout. Promote it to `c`/`key0` or accept geometric
fallback for that board. A missing or unknown `script` also falls back to geometric.

Settings → Swipe Typing reports these layout-axis fallbacks separately from language/dictionary
fallbacks and names corner-only or missing letters. `SwipeEngineFallback.factsFor` and
`CtcEngineAdapter.supportsLayout` share this primary-value definition; authoring tools and
tests must recognize both live XML schemas.

### Native compact-height regression (October 6)

`Keyboard2ViewCustomMappingRenderTest` also parses real XML and computes native theme
geometry for a compact bottom-row-free layout versus explicit numeric opt-in, with
scaling enabled and disabled. This protects GH #90 beyond the mock geometry suite.
See [testing strategy](testing-strategy.md) for the full cloud verdict.

## Modified-layout cache and recent layout fixes (2026-10)

- **Cache key (`ea88d975`, 2026-10-20).** `LayoutModifier` caches each modified board under
  `LayoutCacheKey(layoutIdentity, layoutName, configVersion, layoutCount, actionLabel,
  swapEnterActionKey, offerVoiceTyping, subtypeExtraKeysIdentity)` (`LayoutModifier.kt:30`).
  `refresh_action_label` sets `Config.actionLabel`/`swapEnterActionKey` at every field start
  without bumping `Config.version`, so the old identity+name+version+count key served the first
  field's Enter key to every later field (Saga: "Go" on a Send textarea; the #139 newline
  switch never took effect). The per-field Enter state is now part of the key.
- **#187 below-accent dead keys (FIXED, unreleased `d0ab58af`).** Four below-accent combining
  commands joined the catalogue (240 commands as of 2026-10-20); the dead keys compose on the
  following letter like the existing accent keys. Status per `docs/audit/gh-issue-resolution.md`.
- **Per-layout bindings.** The `language` (GH #186/#61) and `theme` (2026-10-15) root attributes
  above are the two per-layout bindings; both are three-state in the Layout Manager editor and
  stored with the layout. `LayoutLanguageBindingTest` / `LayoutThemeBindingTest` pin them.
