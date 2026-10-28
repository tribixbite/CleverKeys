# Theme System Skill

Use this skill for anything about keyboard theming: the `theme` preference, Theme Manager,
Monet / Material You, decorative themes, DIY (custom) themes and the Theme Creator, per-layout
theme bindings, keyboard LOOKS (iOS-/Gboard-/SwiftKey-/Samsung-style: colours + key geometry),
key-magnifier or suggestion-bar colours. As built at HEAD, 2026-10-27.

## Rules

1. **ONE theme-name → style map.** `Config.builtInThemeStyleId(themeName, systemLight, sdk)`
   (`Config.kt` ~1957) is the only map; `Config.isSystemLight(res)` (uiMode night bit) is the
   only light/dark cue. The keyboard view (`Config.getThemeId`) and `ThemeProvider.loadBuiltInTheme`
   (key magnifier, Short Swipes preview, theme previews) both call it (`ddac0605`, audit H2: the
   old second map drew a dark magnifier under a light "Monet (Auto)" keyboard). Never add another.
   - `monet` follows the system; `monetlight` / `monetdark` are fixed. Monet needs API 31 — below
     it the three fall back to `Light` / `Dark`.
   - `""` (unset) follows the system as `CleverKeysLight` / `CleverKeysDark` (ARC-111); an unknown
     name follows it as `Light` / `Dark`.
   - `decorative_*` and `custom_*` take `R.style.CleverKeysDark` as the base style; their colours
     come from a `KeyboardColorScheme`, not from XML.
2. **Four theme kinds, told apart by id prefix** (`ThemeProvider.getTheme`):
   - built-in XML: the 18 ids in `ThemeProvider.BUILT_IN_THEME_IDS`, drift-pinned against
     `ThemeSettingsActivity.BUILTIN_THEMES` by `LayoutThemeBindingConfigTest`; styles in
     `res/values/themes.xml`, `Theme(context, attrs)` reads them;
   - `decorative_<category>_<name>`: the 18 `theme*()` palettes in `theme/PredefinedThemes.kt`
     (gemstone, neon, pastel, nature, utilitarian, modern × 3 — e.g. `neon_electric_blue`,
     `neon_hot_pink`, `neon_cyberpunk`; there is no "Neon Cyan/Magenta");
   - `custom_<uuid>`: DIY themes in `CustomThemeManager` (its own prefs file
     `custom_keyboard_themes`, JSON export/import, StateFlow list);
   - `look_<name>`: keyboard LOOKS (`theme/KeyboardLook.kt`, registry `KeyboardLooks`, 2026-10-27)
     — rule 12.
   `Keyboard2View` picks the constructor by `snap.isRuntimeTheme`: `Theme(context, colorScheme)`
   for the last three (`ConfigSnapshot.isRuntimeThemeName` is the one definition), `Theme(getContext(), attrs)` for XML.
3. **`ThemeProvider.getTheme` must stay TOTAL** — it runs inside `Keyboard2View`'s constructor.
   A dangling `custom_` / `decorative_` id falls back to `FALLBACK_THEME_ID = "cleverkeysdark"`
   (audit H-2: a thrown exception there was an IME crash loop with no keyboard to fix the pref).
   `isAvailable(id)` is the strict check (used by per-layout bindings); `getTheme` never throws.
4. **Pref coherence across two prefs files** (`CustomThemePrefPolicy`): deleting the ACTIVE
   custom theme resets `theme` to the fallback BEFORE deleting; saving the active theme re-syncs
   `swipe_trail_color`. Writes use `commit()`, because `ThemeSettingsActivity` kills its process
   right after selection writes. `ThemeProvider` is a process singleton
   (`getInstance(appContext)`) whose `customThemeManager` is THE store the editor must mutate
   (H-4) — a second `CustomThemeManager` leaves the provider's in-memory list stale.
5. **Per-layout theme binding** (`e237fa95`, 2026-10-15; spec `docs/specs/layout-system.md`
   "Per-layout theme binding"). `LayoutThemeBinding` (pure): an entry is `null` (use the XML
   `<keyboard theme="…">` default), `"none"` (follow the global theme) or a theme id.
   `Config.recomputeActiveTheme` resolves it at the END of every `refresh()` — never in
   `set_current_layout` — through `ThemeProvider.isAvailable` (`Config.themeAvailability`).
   `Config.themeName` / `Config.theme` are the EFFECTIVE theme; the global `theme` preference is
   never written by a binding; `layout_bound_theme` / `layout_missing_theme` expose the state
   (a missing theme renders the global one). Backup carries the binding inside the classified
   `layouts` blob; it is NOT a preference (`settings-preferences.md`).
6. **Theme change = view re-inflation.** `ConfigurationManager` fires `onThemeChanged` when the
   style id OR `themeName` changes (runtime themes share a base style). The service inflates a new
   `Keyboard2View`, drops `_emojiPane`, cleans up the clipboard manager and re-wires swipe
   components; `PredictionViewSetup` rebuilds the whole container when it does not hold the new
   view (Saga 2026-10-07: a theme switch, and Monet Auto following dark mode, were invisible until
   the process restarted). A minimized keyboard survives this through `KeyboardMinimizer.resolve`.
   **Stale-view detection is `theme/KeyboardViewThemeTracker`** (Pixel 2026-10-25): every
   keyboard view is stamped in `inflate_view` with style id + effective theme name + (runtime
   themes only) the `KeyboardColorScheme` ThemeProvider holds. An edit of the displayed custom
   theme changes ONLY the colours — the old id/name check missed it, and the
   `ACTION_THEME_CHANGED` receiver rebuilt only `if (isInputViewShown)`, so a save made while
   the keyboard was hidden never reached it until a restart. Now the receiver rebuilds when
   shown AND stale, and `onStartInputView` rebuilds whatever is stale; nothing is remembered
   between them and nothing rebuilds when the colours did not change. The editor notifies on
   EVERY edit save (the theme may be bound to the current layout). Never compare id/name alone
   again; pinned by `theme/KeyboardViewThemeTrackerTest` (mock).
7. **Every colour field must reach a renderer.** `KeyboardColorScheme` has 17 colour fields;
   nine Theme-Creator fields were persisted but consumed by nothing until H-4
   (`ThemeCreatorFieldWiringTest` pins each: keyLocked, keyModifier, keySpecial,
   keyBorderActivated, ripple, suggestionText, suggestionBackground, suggestionHighConfidence,
   keyboardSurface). Adding a field means `KeyboardColorScheme` → `Theme` → the paint that uses it
   → a wiring test.
8. **Non-keyboard surfaces use the active theme, not constants.** `KeyMagnifierView.activeTheme`
   draws the Customize-key magnifier; its direction names use `overlayLabelColor` = the theme's
   `subLabelColor` (`22b35f71`; hard-coded 80 % white vanished on light themes). Bar text colours
   come from `SuggestionBarPalette` (pure, in `Theme.kt`): every text role ≥ 3:1 against the bar
   background, also under a bound per-layout theme (`SuggestionBarPaletteTest`).
9. **DIY themes are in the backup** (Saga 2026-10-25; before that NO backup carried them).
   `config.json` section `custom_themes` (`backup/CustomThemeBackup.kt`: validation, per-theme
   diff, Skip/Merge/Replace merge — the same `ShortSwipeImportMode` and `forMode` rules as short
   swipes). The applier writes them through `CustomThemeStore` (`RealCustomThemeStore` wraps the
   PROVIDER's `customThemeManager`, rule 4) BEFORE the settings commit, so a restored
   `theme = custom_<id>` resolves on the first refresh; a Replace that removes the selected
   theme selects the fallback. `CustomThemeManager.replaceAllThemes` is all-or-nothing and
   commits. Full-backup rollback restores the themes too. Tests: `backup/CustomThemeBackupTest`,
   `CustomThemeImportPlanTest` (pure), `CustomThemeImportApplyTest`,
   `theme/CustomThemeBackupFormatTest`, `BackupRestoreFullBackupTest` round trip (mock).
10. **Theme Creator hex field** (`theme/HexColorText`): keep the user's text and caret; apply
   only a complete `#RRGGBB`/`#AARRGGBB`; replace the text only when the colour changed to one
   it does not denote. The picker holds the exact typed/preset ARGB (`exactArgb`) because the
   HSL round trip is lossy (Saga 2026-10-25: a typed `#FF0000` was rewritten to `#FFFF0000`
   mid-entry).
11. `MaterialThemeManager` is the Settings app's Material 3 (Compose) theming, with Device
   Encrypted storage; it is not the keyboard `Theme` and does not affect the IME window.
12. **Keyboard looks carry geometry; only a look does** (2026-10-27, spec
   `docs/specs/layout-system.md` "Keyboard looks"). `KeyboardLook` = id + light `LookPalette` +
   dark `LookPalette` + `LookGeometry` (pure ARGB/fractions/dp; `KeyboardLooksTest` pins
   registry, light-vs-dark and WCAG contrast). `ThemeProvider.loadLook` picks the palette by
   `Config.isSystemLight` and builds `Theme(context, KeyboardLookSchemes.colorScheme(palette),
   lookPalette = palette, lookGeometry = …)`; `builtInThemeStyleId("look_…")` is
   CleverKeysLight/Dark by the same cue, so a light/dark switch changes the style id and
   re-inflates (rule 6). `Theme.Computed` resolves geometry through `KeyboardLookGeometry.resolve`
   (pure, `KeyboardLookGeometryTest`): attributes the effective look DEFINES win (gaps as
   fractions of key width / row height, radius, outline, shadow, label weight, label scale —
   relative to the user's size), everything else and every non-look theme keep the user's
   prefs; prefs are never written. Look-only roles on `Theme` (via `roleOf(KeyValue, …)`; every
   other theme gets `colorKeySpecial` for both and renders pixel-identically): `colorKeyEnter`
   (plain Enter → `KeyRole.ENTER`) and `colorKeyAction` (the Go/Search/Send action key →
   `KeyRole.ACTION`), `enterLabelColor` / `actionLabelColor` / `modifierLabelColor` (0 = none;
   applied by `Theme.Computed.frameLabelColor`, which replaces the plain AND the secondary label
   colour — function keys carry FLAG_SECONDARY, Pixel 2026-10-27), `functionChars` (Gboard's `,.`
   → MODIFIER), `lockedColor` from the look's Caps Lock label, `keyShadowColor`,
   `suggestionSeparatorColor` (others: sub-label at alpha 100, as always), `swipeTrailColor`
(0 = the user's trail), `lookLegends` (corner labels: `LookLegendPolicy` — NONE / DIGITS /
   ALL; the `look_show_hints` pref, default off, restores ALL; drawing only, a11y untouched) and
   `lookPillKeys` (`LookShapes`: ENTER / ACTION / NUMBERS drawn with radius = half the shorter
   side, decided by key identity in `Keyboard2View.onDraw`). New look = a `KeyboardLooks` entry + a `ThemeSettingsActivity.LOOK_THEMES`
   row (drift: `LayoutThemeBindingConfigTest`) + 6 strings × 22 locales; render pinned by
   `theme/KeyboardLookRenderTest` (mock).
13. **Look pop-ups, hint style, letter keys, wallpaper colours** (2026-10-28,
   `theme/LookPopups.kt`, `theme/LookDynamicColors.kt`; spec `layout-system.md`). A look's
   `preview` (`LookPreviewShape` BUBBLE Gboard / BALLOON iOS / COLUMN SwiftKey / PLATE Samsung)
   is drawn by `Keyboard2View.drawKeyPreview` for the pressed CHARACTER key, inside the view
   (`LookPreviewPolicy.frame` moves a top-row preview down and edge previews inward), unless
   `look_key_preview` (default ON) is off; non-look themes draw none. Preview and popover
   colours are `LookPopupColors.of(palette)` (`popup*` fields, 0 = letter key / Caps Lock
   roles); `SubkeyPopoverRenderer` uses them when `Theme.lookPopupColors` is set. Corner labels
   on a look use `LookHintStyle` via `LookHintLayout` (fraction of the look's label × the user's
   secondary scale, ≤ 30 % of the key; corner inset ≥ radius × (1 − 1/√2) so pills never clip).
   `letterKeys` (`LookLetterKey.EMOJI`) maps `switch_emoji` to `KeyRole.LETTER` = letter fill
   + letter label (`key_letter`). `dynamicColor` (Gboard only): `ThemeProvider.lookPalette`
   reads the system Material roles (API 34+ `system_*_light/dark` role colours, 31–33 tonal
   shades) → `LookDynamicColors.gboardPalette`; falls back to the fixed palette below API 31 or
   when `LookContrast` fails. `getColorScheme` uses the same palette, so the stale-view stamp
   (rule 6) catches wallpaper changes. Tests: `theme/LookDynamicColorsTest`,
   `theme/LookPopupsTest` (pure, MCU fixtures for nine seeds), `KeyboardLookRenderTest` (mock).

14. **Per-layout key gaps / border beat a look** (2026-10-27, spec `docs/specs/layout-system.md`
   "Per-layout key geometry"). Per field (`KeyGeometryField`: horizontal/vertical gap, border
   on/off, radius, line width — the global prefs' own units): user per-layout choice (`layouts`
   entry `key_geometry`, `"inherit"` = follow global) > layout XML `key_*` attribute > the
   EFFECTIVE look (`KeyboardLookGeometry.resolve`) > global Appearance prefs.
   `Theme.Computed` applies `LayoutKeyGeometry.applyTo` AFTER the look, reading
   `Config.currentKeyGeometry()`; the user layer's border switch is
   `LayoutKeyGeometry.effectiveBorderOn` (a layout OFF = the theme's/look's frame).
   `Config.set_current_layout` bumps the snapshot version when the effective overrides change
   (`Keyboard2View`'s frame cache is keyed on it; never read live Config there — ratchet test).
   Prefs are never written.
   Tests: `LayoutKeyGeometryTest` (pure), `LayoutKeyGeometryConfigTest` (mock).

16. **A look arranges the suggestion bar** (2026-10-27, `theme/LookBar.kt`, research §10, spec
   "Keyboard looks"). `KeyboardLook.bar` / `ThemeSpec.bar` → `Theme.lookBar` (null on every
   non-look theme = scrolling chips). `LookBarArrangement.arrange` (pure) maps the bar's entries
   (RANKING order) to visual slots: best (= highlighted = what space commits, else the first
   word) in the centre, the typed word or "+word" chip left of it (quoted on iOS), the rest
   outwards; prompts are never arranged. DRAWING ONLY — never reorder `currentSuggestions`,
   scores, metas or the highlight index: pool view *i* taps entry *i* and children are added in
   visual order. The typing path passes the typed word (`setSuggestionsWithScores(…, typedWord)`).
   `SuggestionBar.onMeasure` gives the slots the scroll view's width. Separators stay a colour
   (`barSeparator`), drawn only between filled slots. A slot shows the plain word
   (`LookBarArrangement.slotText`; the "+word" chip loses its "+" and italic but still taps the
   tap-to-add entry) and is inked by `paint(bar, centre, commit)` per `LookBar.emphasis`
   (COMMIT: bold highlight only on the space commit, none for next-word; CENTRE = SwiftKey: centre
   text colour, sides `sideColor`). Never infer "commit" from the centre position.
17. **Sublabel bands never overlap** (Pixel 2026-10-27, "ABC" over "GIF"). A key's top (NW, N,
   NE) and bottom (SW, S, SE) labels share one shrink factor, `SubLabelSizing.bandScale`, measured
   at their individually fitted sizes (`Keyboard2View.bandScale` / `subLabelPaint`); bands with
   one label draw exactly as before. Pinned by `SubLabelSizingTest` (pure) and
   `LookHintOverlapRenderTest` (instrumented, measured ink bounds).
18. **A look draws its product's editor-action key** (2026-10-28, `theme/LookActionKeys.kt`,
   research §11). `KeyboardLook.action` / `ThemeSpec.action` → `Theme.lookAction`
   (`LookActionStyle`: icon per `ActionKind`, `accent` = actions on the action colours, the rest
   on the Enter frame; `lowercase` text; `ENTER` = plain Enter). The service pushes the field's
   standard action with `Keyboard2View.setEditorAction` before every `setKeyboard` (null when the
   editor gives its own label → text; never a live `Config` read — `ConfigSnapshotRatchetTest`).
   `Keyboard2View` swaps the
   ACTION role for ENTER when `usesActionFill` is false and strokes `LookActionKeys.parts` (our
   own line icons, never product artwork) in place of the label. Gboard: icons, function fill;
   SwiftKey: its own icon mapping (Go = ✓); iOS 26: blue + icon for Go/Search/Send/Done; iOS 18:
   lower-case text; Samsung: blue icon on the dark function fill (One UI screenshots, 2026-10-28).
   XML `action_icons` / `action_accent` / `action_lowercase`, JSON `action`.
19. **Space-bar language, layout symbol panes** (2026-10-28, research §12). `KeyboardLook.spaceLanguage`
   → `Theme.lookSpaceLanguage`; drawn only with `snap.look_space_language` (Appearance switch,
   default on) via `SpaceLanguageLabel`. `<keyboard symbols="symbols_…">` + `switch_symbols`
   (`LayoutManager.loadSymbols(view's keyboard)`, resolved by `getIdentifier`): name new panes
   `symbols_*` or the release shrinker drops them (`res/raw/keep_symbol_panes.xml`).

15. **Layouts style themselves** (2026-10-28, spec `docs/specs/layout-system.md` "Layout themes
   and per-key styling", user reference `docs/wiki/layouts/layout-xml-reference.md`). A layout
   XML's `<theme>` is a partial `ThemeSpec` (`theme/ThemeSpec.kt`, codec `ThemeSpecCodec.kt`)
   over the global theme: the FIFTH theme kind, id `embedded_<n>.<globalTheme>`
   (`EmbeddedThemes`, in-process, never persisted; runtime theme, light/dark base style).
   Precedence user binding > XML `theme=` > embedded > global (`LayoutThemeBinding.resolve`).
   Per-key/row styling (`KeyStyle`) is drawing only — `KeyStyles.frame` keeps every frame centred
   on its cell (swipe/hit-test unchanged). Custom themes gained format version 2
   (`CustomTheme.extension`, JSON `look`): v1 JSON and rendering byte-identical; the Theme
   Creator's "Keyboard Look" section edits it; never construct `CustomTheme(id, name, colors)`
   from an edited theme without carrying `extension` (the creator used to). Layout → custom
   theme and theme → `<theme>` XML go through `LayoutThemeTransfer` with THE provider (rule 4).
   The latched Shift colours are spec roles `LATCHED_KEY` / `LATCHED_LABEL` (`latched_key`,
   `latched_label`; JSON `latchedKey` / `latchedLabel` in the `look` extension; absent = 0 = the
   pressed fill, so older custom themes render unchanged): a theme saved from Gboard-/Samsung-
   style keeps them (2026-10-27, `ThemeSpecRenderTest`).
   Pure model tests can not touch `KeyboardColorScheme` (Compose colours are not on the pure
   classpath): rendering tests are mock tier (`ThemeSpecRenderTest`).

## Traps

- Theme's runtime constructor converts colours through Compose float channels and TRUNCATES
  (`(red * 255).toInt()`): a look colour can land one unit low per channel. Compare with a
  ±1 tolerance in tests; `colorKeyAction` and the other look-only roles are passed as ints, exact.
- A predefined layout's `theme=` default is read by Config from the parsed XML, but the pure
  backup preview and Layout Manager's chip read `PredefinedLayoutThemes` — `StyleLayoutsTest`
  pins the map against every layout file.
- `jewel` is in the style map and `themes.xml` but NOT in `BUILT_IN_THEME_IDS`: Theme Manager
  never offers it, and a per-layout binding to it counts as missing. Do not "fix" one side alone.
- `ThemeProvider.getBuiltInColorScheme` holds APPROXIMATE palettes of the XML themes (Theme
  Creator starting colours). The keyboard renders the XML attributes, not these.
- Compose `Dialog` stacking and the "Recording currently in progress" crash (CLAUDE.md) both bit
  theme/customization dialogs; compose sub-dialogs after the parent.
- Preview surfaces (`Keyboard2View.applyPreviewTheme(theme, trailColor)`,
  `theme/ThemeEditorPreview.kt`, `ThemePreviewScene.kt`): obtain the `Theme` from
  `ThemeProvider`, so a preview and the keyboard cannot disagree on the light/dark cue again.
  The preview's LAYOUT is `ThemeEditorPreview.previewLayout`: the user's first layout only when it
  is plain (`KeyboardData.carriesStyling` false), else built-in QWERTY — a styled layout's row
  colours were drawn into every theme's preview (Saga 2026-10-27, `ThemePreviewLayoutTest`).
- Theme Manager cards take `ThemeProvider.getColorScheme(id)` (current mode, variants
  flattened), never `CustomTheme.colors` — that is only a v2 theme's base (light) scheme
  (`ThemeManagerCustomCardColoursTest`).

## File map

| File | Role |
|------|------|
| `Config.kt` — `builtInThemeStyleId`, `isSystemLight`, `getThemeId`, `recomputeActiveTheme`, `themeName` / `theme` / `layout_themes` / `layout_bound_theme` / `layout_missing_theme`, `themeAvailability` | map, cue, effective theme |
| `Theme.kt` (`Theme`, `Theme.Computed`, `SuggestionBarPalette`) | the rendered colours; two constructors |
| `theme/ThemeProvider.kt` | id → `Theme`; `BUILT_IN_THEME_IDS`, `FALLBACK_THEME_ID`, `isAvailable`, `getAllThemeIds` |
| `theme/PredefinedThemes.kt`, `theme/KeyboardColorScheme.kt` | decorative palettes, the 17-field scheme |
| `theme/CustomThemeManager.kt`, `theme/CustomThemePrefPolicy.kt` | DIY store, pref coherence |
| `theme/KeyboardViewThemeTracker.kt` | keyboard-view stale detection (id + name + runtime colours) |
| `theme/KeyboardLook.kt`, `theme/KeyboardLooks.kt`, `theme/KeyboardLookSchemes.kt`, `theme/LookTypeface.kt`, `PredefinedLayoutThemes.kt` | looks: data + geometry precedence, the four built-ins, palette → scheme, label weight, predefined layouts' XML looks |
| `theme/LookPopups.kt`, `theme/LookDynamicColors.kt` | looks: key-press preview, popup colours, hint style; wallpaper (Material You) mapping + contrast floors |
| `theme/ThemeEditorPreview.kt`, `theme/ThemePreviewScene.kt` | Theme Creator preview |
| `theme/MaterialThemeManager.kt`, `theme/CleverKeysTheme.kt`, `KeyboardShapes.kt`, `KeyboardTypography.kt` | Settings-app Compose theme |
| `activities/ThemeSettingsActivity.kt` | Theme Manager + Theme Creator UI, `BUILTIN_THEMES` |
| `activities/LayoutThemePicker.kt`, `LayoutThemeBinding.kt`, `backup/LayoutThemeBindingDiff.kt`, `prefs/LayoutsPreference.kt` | per-layout binding UI, rules, backup preview, storage |
| `LayoutKeyGeometry.kt`, `activities/LayoutKeyGeometryDialog.kt` | per-layout key gaps / border: rules + precedence, Layout Manager editor |
| `theme/ThemeSpec.kt`, `theme/ThemeSpecCodec.kt`, `theme/EmbeddedThemes.kt`, `theme/LayoutThemeTransfer.kt`, `theme/ThemeSpecEdits.kt`, `KeyStyle.kt`, `activities/ThemeLookEditor.kt` | layout `<theme>` model/XML/JSON, embedded ids, save/export, Theme Creator "Keyboard Look", per-key styling |
| `customization/KeyMagnifierView.kt` | magnifier colours (`activeTheme`, `overlayLabelColor`) |
| `res/values/themes.xml` | the XML styles (`BaseTheme`, `CleverKeysDark/Light`, `Monet*`, `Jewel`, …) |

## Tests

- Pure / mock: `ThemeStyleMapTest` (Monet both ways on API 31+, Light/Dark before, unset and
  unknown names follow the system, runtime themes take the base style),
  `LayoutThemeBindingConfigTest`, `theme/ThemeProviderFallbackTest`,
  `theme/CustomThemePrefPolicyTest`, `theme/ThemeCreatorFieldWiringTest`,
  `SuggestionBarPaletteTest`, `ThemeBorderPaintOpacityTest`,
  `theme/KeyboardViewThemeTrackerTest` (hidden/shown edits, switch, binding, delete, XML flip),
  `theme/KeyboardLookGeometryTest`, `theme/KeyboardLooksTest`, `theme/LookDynamicColorsTest`,
  `theme/LookPopupsTest`, `theme/LookBarArrangementTest`, `theme/ThemeManagerCustomCardColoursTest`
  (pure), `theme/KeyboardLookRenderTest`, `theme/ThemePreviewLayoutTest` (mock), `theme/ThemeSpecTest`,
  `theme/ThemeSpecEditsTest`, `theme/EmbeddedThemesTest`, `KeyStyleTest` (pure),
  `theme/ThemeSpecRenderTest`, `LayoutXmlStylingTest`, `LayoutEmbeddedThemeTest` (mock).
- Instrumented: `LookSuggestionBarInstrumentedTest` (also writes the looks showcase PNGs),
  `LookHintOverlapRenderTest`, `LayoutXmlThemeKeyboardInstrumentedTest`, `LayoutThemeSaveAsCustomComposeTest`,
  `ThemeSettingsActivityComposeTest`, `Issue93ThemeHexInputComposeTest`,
  `LayoutThemeSuggestionBarInstrumentedTest`, `ClipboardThemeColorTest`, `ClipboardPaneTintTest`.
- Device: a custom-theme visual pass is pure-tier only so far (HANDOFF round 8); Monet (Auto)
  magnifier/keyboard agreement was checked on the Saga in light mode (`ddac0605`, `22b35f71`).

## Related

`settings-preferences.md` (binding is not a pref), `content-pane-layout.md` (rebuild on theme
change), `keyboard-tile-and-minimize.md` (`MinimizedKeyboardView` draws from `Theme`),
`docs/specs/layout-system.md`, `docs/wiki/specs/customization/themes-spec.md` (user-facing).
