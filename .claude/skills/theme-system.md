# Theme System Skill

Use this skill for anything about keyboard theming: the `theme` preference, Theme Manager,
Monet / Material You, decorative themes, DIY (custom) themes and the Theme Creator, per-layout
theme bindings, key-magnifier or suggestion-bar colours. As built at HEAD, 2026-10-23.

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
2. **Three theme kinds, told apart by id prefix** (`ThemeProvider.getTheme`):
   - built-in XML: the 18 ids in `ThemeProvider.BUILT_IN_THEME_IDS`, drift-pinned against
     `ThemeSettingsActivity.BUILTIN_THEMES` by `LayoutThemeBindingConfigTest`; styles in
     `res/values/themes.xml`, `Theme(context, attrs)` reads them;
   - `decorative_<category>_<name>`: the 18 `theme*()` palettes in `theme/PredefinedThemes.kt`
     (gemstone, neon, pastel, nature, utilitarian, modern × 3 — e.g. `neon_electric_blue`,
     `neon_hot_pink`, `neon_cyberpunk`; there is no "Neon Cyan/Magenta");
   - `custom_<uuid>`: DIY themes in `CustomThemeManager` (its own prefs file
     `custom_keyboard_themes`, JSON export/import, StateFlow list).
   `Keyboard2View` picks the constructor by `snap.isRuntimeTheme`: `Theme(context, colorScheme)`
   for the last two, `Theme(getContext(), attrs)` for XML.
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
9. `MaterialThemeManager` is the Settings app's Material 3 (Compose) theming, with Device
   Encrypted storage; it is not the keyboard `Theme` and does not affect the IME window.

## Traps

- `jewel` is in the style map and `themes.xml` but NOT in `BUILT_IN_THEME_IDS`: Theme Manager
  never offers it, and a per-layout binding to it counts as missing. Do not "fix" one side alone.
- `ThemeProvider.getBuiltInColorScheme` holds APPROXIMATE palettes of the XML themes (Theme
  Creator starting colours). The keyboard renders the XML attributes, not these.
- Compose `Dialog` stacking and the "Recording currently in progress" crash (CLAUDE.md) both bit
  theme/customization dialogs; compose sub-dialogs after the parent.
- Preview surfaces (`Keyboard2View.applyPreviewTheme(theme, trailColor)`,
  `theme/ThemeEditorPreview.kt`, `ThemePreviewScene.kt`): obtain the `Theme` from
  `ThemeProvider`, so a preview and the keyboard cannot disagree on the light/dark cue again.

## File map

| File | Role |
|------|------|
| `Config.kt` — `builtInThemeStyleId`, `isSystemLight`, `getThemeId`, `recomputeActiveTheme`, `themeName` / `theme` / `layout_themes` / `layout_bound_theme` / `layout_missing_theme`, `themeAvailability` | map, cue, effective theme |
| `Theme.kt` (`Theme`, `Theme.Computed`, `SuggestionBarPalette`) | the rendered colours; two constructors |
| `theme/ThemeProvider.kt` | id → `Theme`; `BUILT_IN_THEME_IDS`, `FALLBACK_THEME_ID`, `isAvailable`, `getAllThemeIds` |
| `theme/PredefinedThemes.kt`, `theme/KeyboardColorScheme.kt` | decorative palettes, the 17-field scheme |
| `theme/CustomThemeManager.kt`, `theme/CustomThemePrefPolicy.kt` | DIY store, pref coherence |
| `theme/ThemeEditorPreview.kt`, `theme/ThemePreviewScene.kt` | Theme Creator preview |
| `theme/MaterialThemeManager.kt`, `theme/CleverKeysTheme.kt`, `KeyboardShapes.kt`, `KeyboardTypography.kt` | Settings-app Compose theme |
| `activities/ThemeSettingsActivity.kt` | Theme Manager + Theme Creator UI, `BUILTIN_THEMES` |
| `activities/LayoutThemePicker.kt`, `LayoutThemeBinding.kt`, `backup/LayoutThemeBindingDiff.kt`, `prefs/LayoutsPreference.kt` | per-layout binding UI, rules, backup preview, storage |
| `customization/KeyMagnifierView.kt` | magnifier colours (`activeTheme`, `overlayLabelColor`) |
| `res/values/themes.xml` | the XML styles (`BaseTheme`, `CleverKeysDark/Light`, `Monet*`, `Jewel`, …) |

## Tests

- Pure / mock: `ThemeStyleMapTest` (Monet both ways on API 31+, Light/Dark before, unset and
  unknown names follow the system, runtime themes take the base style),
  `LayoutThemeBindingConfigTest`, `theme/ThemeProviderFallbackTest`,
  `theme/CustomThemePrefPolicyTest`, `theme/ThemeCreatorFieldWiringTest`,
  `SuggestionBarPaletteTest`, `ThemeBorderPaintOpacityTest`.
- Instrumented: `ThemeSettingsActivityComposeTest`, `Issue93ThemeHexInputComposeTest`,
  `LayoutThemeSuggestionBarInstrumentedTest`, `ClipboardThemeColorTest`, `ClipboardPaneTintTest`.
- Device: a custom-theme visual pass is pure-tier only so far (HANDOFF round 8); Monet (Auto)
  magnifier/keyboard agreement was checked on the Saga in light mode (`ddac0605`, `22b35f71`).

## Related

`settings-preferences.md` (binding is not a pref), `content-pane-layout.md` (rebuild on theme
change), `keyboard-tile-and-minimize.md` (`MinimizedKeyboardView` draws from `Theme`),
`docs/specs/layout-system.md`, `docs/wiki/specs/customization/themes-spec.md` (user-facing).
