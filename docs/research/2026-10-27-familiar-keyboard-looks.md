# Familiar keyboard looks: iOS, Gboard, SwiftKey, Samsung Keyboard (2026-10-27)

## Purpose and scope

This note records the default visual look of four widely used phone keyboards, in system light
and dark mode, as input for CleverKeys' "-style" looks. CleverKeys reproduces them with its own
drawing code: plain rounded rectangles, flat colours, its own icons and the system sans-serif
font. SF Pro / SF Compact, Google Sans and Samsung One / SECKeypad are not bundled, and no image,
icon or other asset from these products is copied.

CleverKeys is not affiliated with or endorsed by Apple, Google, Microsoft or Samsung. iOS,
Gboard, SwiftKey and Samsung Keyboard are trademarks of their respective owners.

How the values were obtained (best evidence first):

1. **Values read from the shipping apps' own theme data.** Gboard, Microsoft SwiftKey and
   Samsung Keyboard are installed on the Termux host phone. Their APKs were copied out of
   `pm path` locations into the session scratchpad and their resource tables, theme
   stylesheets and theme JSON were read with `aapt2 dump`, `protoc --decode_raw` and Python.
   Versions: Gboard `18.5.4.993608717-beta`, Microsoft SwiftKey `9.13.14.5`, Samsung Keyboard
   (HoneyBoard) `5.9.40.75` (One UI 8 build, compileSdk 36). Nothing was extracted into the
   repository.
2. **Open-source recreations with explicit values.** KeyboardKit 8.9.0 (the iOS 18-era colour
   asset catalogue and layout constants) and the OpenWhispr iOS keyboard extension, whose
   iOS 26 values were measured against Apple's keyboard on the iOS 26.5 simulator.
3. **Material Color Utilities** (`@material/material-color-utilities`, SchemeTonalSpot) to turn
   Gboard's dynamic-colour role names into hex values for a stated seed.
4. Press coverage for which theme is the default.

"Confidence" applies to the value as a description of what a user sees on a 2025–2026 device:
**high** = read from the product's own data or a measured recreation, and the context is
unambiguous; **medium** = from product data, but which theme or flag applies depends on device
state, or the value comes from a recreation; **low** = estimate or inference. Units are dp for
Android and pt for iOS (1 pt ≈ 1 dp for our purposes).

---

## 1. Apple iOS keyboard

Primary target: **iOS 26 ("Liquid Glass")**. The iOS 18 values follow because they differ
materially (separate function-key colour, 5 pt corners, key shadow).

On both versions the real keyboard is drawn over a system blur/glass material, so the
background is not one flat colour. The values below are composited, opaque estimates over a
plain backdrop.

### 1a. iOS 26 (primary)

| Attribute | Light | Dark | Confidence | Source |
|---|---|---|---|---|
| Keyboard background (glass, composited) | `#E2E4E8` | `#171717` | medium (translucent in reality) | [2] |
| Letter-key fill | `#FFFFFF` | `#3D3D3D` | high | [2] |
| Modifier / function keys (shift, delete, 123, globe/emoji) | same as letter keys: `#FFFFFF` | `#3D3D3D` | high ("one colour for every key") | [2] |
| Return key, default type | key colour; **icon** `return.left` glyph (↵), black / white | same | high | [2] |
| Return key, prominent (Go / Search / Send / Done) | `#007AFF` fill, white icon (→, magnifier, ↑, ✓) | `#007AFF`, white | high | [2] |
| Return key disabled (empty field) | key `#BFC1C5`, glyph `#ACADB1` | key `#747474`, glyph `#818181` | high | [2] |
| Key label colour | `#000000` | `#FFFFFF` | high | [2] |
| Hint / secondary label | none shown | none | high | [1][2] |
| Corner radius | 8 pt, continuous ("squircle") curve; 7 pt on 320 pt-wide Display Zoom | same | high | [2] |
| Horizontal gap | 6 pt; side margin 6.5 pt | same | high | [2] |
| Vertical gap / key height | 11 pt gap, 43 pt key (17 Pro); 45 pt key (Pro Max); SE: 12 pt / 42 pt | same | high | [2] |
| Key shadow / border | **none** | none | high | [2] |
| Pressed letter key | no fill change; key-pop balloon instead: head 12 pt wider than the key on each side, 12 pt corners, 50 pt tall, top 66 pt above the key, no shadow, fill `#FFFFFF` | balloon `#505050` | high | [2] |
| Pressed function key | `#C4C5C8` | `#7D7D7D` | high | [2] |
| Label font | SF Compact; lowercase ~24.7 pt at weight ~420 (just above Regular), capitals ~22.4 pt at ~480; "123"/"ABC" 17.75 pt Regular; lowercase shown until Shift | same | high | [2] |
| Suggestion bar | about 40 pt strip above the keys, on the glass; 3 slots | same | low (not measured in [2]) | [2] |
| Suggestion separators | thin vertical lines between the 3 slots (carried over from iOS 18) | same | low | [1] |
| Swipe (QuickPath) trail | thin tapering line, blue-tinted; estimate `#007AFF` at ~60 % | estimate the same | low | — |

### 1b. iOS 18 (for comparison)

| Attribute | Light | Dark | Confidence | Source |
|---|---|---|---|---|
| Keyboard background | `#D5D6DD` (KeyboardKit asset; screenshot samplers often quote `#D1D3D9`) | `#2C2C2C` | medium (real background is a blur) | [1] |
| Letter-key fill | `#FFFFFF` | `#6B6B6B` opaque equivalent (KeyboardKit draws white at 30 % over the dark material) | high / medium | [1] |
| Function keys (shift, delete, 123, globe, return) | `#ABB1BA` | `#474747` opaque equivalent (white at 10 % over the material) | high / medium | [1] |
| Return key, default | function-key colour, **text** label "return" | same | high | [1] |
| Return key, prominent (Go/Search/Send…) | `#007AFF`, white text | `#007AFF`, white text | medium | [1] |
| Key label colour | `#000000` | `#FFFFFF` | high | [1] |
| Corner radius | 5 pt | 5 pt | high | [1] |
| Horizontal gap | 6 pt (3 pt inset per side) | same | high | [1] |
| Vertical gap / row height | 12 pt (6 pt inset top and bottom), 54 pt row → 42 pt key (Pro Max: 56 pt row, 11 pt gap) | same | high | [1] |
| Key shadow | 1 pt, straight down, `#000000` at 30 % | `#000000` at 70 % | high | [1] |
| Pressed letter key | key-pop callout (fill = key colour, border black at 50 %, 10 pt corners, shadow black 10 % / radius 5) | same shape | medium | [1] |
| Pressed function key | `#FFFFFF` | the letter-key colour | high | [1] |
| Letter font | SF Pro; lowercase 26 pt Light, other characters 23 pt; system keys 16 pt | same | high | [1] |
| Suggestion bar | transparent over the keyboard background; autocorrect candidate on white 50 % with 4 pt corners | same | medium | [1] |
| Suggestion separators | 1 pt × 30 pt vertical line, `secondary` label colour at 50 % | same | medium | [1] |

**iOS 18 → iOS 26 differences:** function keys stop being a darker grey and take the letter-key
colour; corners go from 5 pt to 8 pt (continuous curve); the 1 pt key shadow disappears; return
becomes an icon instead of the text "return"; the keyboard sits on Liquid Glass with rounded
outer corners and the app showing through.

---

## 2. Google Gboard

Gboard themes are compiled stylesheets in `assets/theme/*.binarypb`. Each theme package is an
ordered list of sheets (for example `theme_package_metadata_dynamic_color_light_base` =
`color_common` + `color_label_black` + `color_expression_light` + `color_rules` +
`dynamic_color_rules` + `dynamic_color_light`, plus `*_border` sheets when "Key borders" is
on). Variables in the dynamic theme resolve to Material 3 colour roles (`color_gm3_*`). Source [3]
for every row unless stated otherwise.

### 2a. Role mapping (what Gboard uses for what)

| Element | Light role | Dark role |
|---|---|---|
| Keyboard background (body, header, suggestion strip) | `surfaceContainer` | `surfaceContainer` |
| Letter key (`default_bordered_key_color`) | `surfaceContainerLowest` | `surfaceBright` |
| Letter key pressed | `surfaceContainerHighest` | label colour at 10 % over the key |
| Function keys: shift, delete, ?123, emoji, comma, period (`keytop.dark` / modifier) | `secondaryContainer` | `secondaryContainer` |
| Function key label / icon | `onSecondaryContainer` | `onSecondaryContainer` |
| Function key pressed | `onSecondaryContainer` at 10 % over `secondaryContainer` | same |
| Enter key (`keytop.for-action-key` → `default_generic_accent_color`) | `secondaryContainer`, `onSecondaryContainer` icon (same as the other function keys) | same |
| Enter key "highlighted" (search/send fields) | `primary`, `onPrimary` icon | `primary`, `onPrimary` |
| Shift / Caps active | `primary`, `onPrimary` | same |
| Key label | `onSurface` | `onSurface` |
| Suggestion text | `onSurface` | `onSurface` |
| Suggestion separators (`divider.vertical.for-candidate-key`) | `outlineVariant` | `outlineVariant` |
| Glide trail (`track.for-gesture` → `default_generic_accent_color_strong`) | `primary` | `primary` |
| Key-press popup | `surfaceContainerLowest` | `surfaceBright` |

This matches the 2024–2025 Dynamic Color revision in which shift, ?123, comma/emoji, period,
enter and backspace all share one colour [8][9].

### 2b. Material You default — seed `#1B6EF3`

`#1B6EF3` is the Android fallback seed ("GOOGLE_BLUE") used when the wallpaper provides no
colour. Values computed with Material Color Utilities SchemeTonalSpot (2021 spec) [4]. A real
Pixel derives its seed from the wallpaper, and Android 16's expressive palette spec shifts these
slightly, so treat them as **medium** confidence representatives of the look rather than exact
Pixel values.

| Attribute | Light | Dark | Confidence | Source |
|---|---|---|---|---|
| Keyboard background | `#EEEDF4` | `#1E1F25` | medium | [3][4] |
| Letter-key fill | `#FFFFFF` | `#38393F` | medium | [3][4] |
| Modifier / function-key fill | `#DCE2F9` | `#404659` | medium | [3][4] |
| Function-key label / icon | `#404659` | `#DCE2F9` | medium | [3][4] |
| Enter key fill + icon | `#DCE2F9` fill, `#404659` **icon** (↵ / → / magnifier) | `#404659`, `#DCE2F9` | medium | [3][4] |
| Enter key, highlighted fields | `#475D92`, `#FFFFFF` | `#B0C6FF`, `#152E60` | medium | [3][4] |
| Key label colour | `#1A1B20` | `#E2E2E9` | medium | [3][4] |
| Hint / secondary label | not shown by default ("Long press for symbols" off); when on: top-right, label colour at 80 % (`#CC333333` light, `#CCFFFFFF` dark) | — | low (default) / high (colour) | [3] |
| Corner radius | 8 dp on phones ≥ 400 dp wide (`style_sheet_default_sw400`), 6 dp below that; 8–12 dp on tablets/foldables | same | high | [3] |
| Horizontal / vertical gaps | estimate 6 dp / 10 dp (key padding is resolved in obfuscated layout code) | same | low | — |
| Key shadow | 1 dp elevation (`default_keytop_elevation` = 1 in `color_common`); a `default_no_key_shadow` sheet exists and can set it to 0 | same | medium | [3] |
| Pressed key | colour change only, no pop-up by default ("Popup on keypress" off); a `silk_key_press` variant fades the label | — | medium | [3] |
| Suggestion bar | background = keyboard background `#EEEDF4` / `#1E1F25`, text `onSurface`, no fill behind suggestions | same | high (roles) | [3] |
| Suggestion separators | `#C5C6D0` | `#44464F` | medium | [3][4] |
| Glide trail | `#475D92` | `#B0C6FF` | medium | [3][4] |
| Font | Google Sans (not bundled), regular weight; lowercase letters until Shift | same | medium | — |

"Key borders" on is the variant that gives keys a fill. With it off (no `_border` sheets), letter
and function keys are transparent on the background and only the space bar keeps a fill.

### 2c. Neutral fallback (no dynamic colour)

On devices without dynamic colour, the same role names resolve to Gboard's bundled Google
Material 3 baseline palette. The hex values below all occur in Gboard's resource table, but their
role assignment follows Google's GM3 baseline and was not traced through the obfuscated resource
names.

| Attribute | Light | Dark | Confidence |
|---|---|---|---|
| Keyboard background (`surfaceContainer`) | `#F0F4F9` | `#1E1F20` | medium |
| Letter key (`surfaceContainerLowest` / `surfaceBright`) | `#FFFFFF` | `#37393B` | medium |
| Function keys + enter (`secondaryContainer`) | `#C2E7FF` | `#004A77` | medium |
| Function label (`onSecondaryContainer`) | `#004A77` | `#C2E7FF` | medium |
| Highlighted enter / trail (`primary`) | `#0B57D0` | `#A8C7FA` | medium |
| Label (`onSurface`) | `#1F1F1F` | `#E3E3E3` | medium |
| Separator (`outlineVariant`) | `#C4C7C5` | `#444746` | medium |

Gboard also still ships the older non-dynamic themes with literal values. "Google Blue" light:
background `#F8F9FA` (body `#F1F3F4`), keys `#FFFFFF`, function keys `#CCCED5`, pressed key
`#E6E6E6`, accent `#1A73E8`, label `#000000`. "Google Blue" dark: background `#363B40` / body
`#292E33`, keys `#484C4F`, function keys `#373C41`, accent `#5E97F6`, label `#FFFFFF`. The 2016
"Material" light/dark themes (teal accent `#4DB6AC` / `#80CBC4`, background `#ECEFF1` /
`#263238`) are also present. All of these are **high** confidence as data but are not the
default.

---

## 3. Microsoft SwiftKey

SwiftKey 9.13 ships its built-in themes as `assets/themes/<uuid>/style.json` plus 9-patch PNGs
[5]. `themelist.json` lists "Fluent Adaptive", "Adaptive", "Dark", "Light" and the high-contrast
themes. "Adaptive" (2020) follows system light/dark and was the default for new installs.
"Fluent Adaptive" (2023, v8.10.34.6) is described as the new default look [10] and is the theme
being promoted. Both are given below; **Fluent Adaptive is the recommended reproduction target**.

### 3a. Fluent Adaptive (primary)

| Attribute | Light | Dark | Confidence | Source |
|---|---|---|---|---|
| Keyboard background | `#F1F1F1` | `#141414` | high | [5] |
| Letter-key fill | `#FFFFFF` | `#232323` (PNG fill; palette fallback `#292929`) | high | [5] |
| Modifier / function keys (shift, backspace, 123, emoji, enter) | `#FAFAFA`, practically the letter colour | `#232323`, same asset as the letter keys | high | [5] |
| Enter key | function-key fill, **icon** in main text colour (`#292929` / `#FFFFFF`), not accent-coloured | same | high | [5] |
| Key label colour | `#292929` | `#FFFFFF` | high | [5] |
| Hint / secondary characters | shown by default above the main label; `#B7B7B7` at Light weight | `#777777` | high (colour) / medium (position: top, centred on function keys, top-right on outlined keys) | [5] |
| Space bar branding text | `#767676` | `#939393` | high | [5] |
| Corner radius | ≈ 8 dp (measured from the xxhdpi 9-patch) | ≈ 8 dp | high | [5] |
| Horizontal / vertical gap | ≈ 5 dp / ≈ 5.3 dp (transparent margins 7–8 px L/R, 9 px top, 7 px bottom per xxhdpi key image) | same | medium | [5] |
| Key shadow | soft drop shadow, black, ≈ 16 % alpha at the edge falling to ~2 %, offset ≈ 0.7 dp down, blur ≈ 2.5 dp (drawn into the key image) | same shape | high | [5] |
| Pressed letter key | `#F9F9F9` | `#212121` | high | [5] |
| Pressed function key | `#E3E3E3` | `#212121` | high | [5] |
| Key-press popup | `#E3E3E3` | `#404040` | high | [5] |
| Suggestion bar | background `#F1F1F1` (same as keyboard); middle candidate `#292929`, side candidates `#777777` | `#141414`; middle `#FFFFFF`, sides `#939393` | high | [5] |
| Suggestion separators | none in the theme | none | high | [5] |
| Toolbar background | `#F9F9F9` | `#212121` | high | [5] |
| Flow (swipe) trail | accent `#3961ED` | `#9DC3FF` | high | [5] |
| Label weight | regular (not bold, not light); lowercase letters until Shift | same | high (weight) / medium (case) | [5] |

### 3b. Adaptive / classic Light and Dark

| Attribute | Light | Dark | Confidence | Source |
|---|---|---|---|---|
| Keyboard background | `#ECEEF1` | `#1C1F27` | high | [5] |
| Letter-key fill | `#FFFFFF` with 1 dp `#CCCCCC` stroke | `#393A41` with 1 dp `#000000` stroke | high | [5] |
| Function keys | **no fill** (transparent); pressed `#E1E3E6` | no fill; pressed `#393A41` | high | [5] |
| Enter / shift icons | accent colour: `#000000` | `#FFFFFF` | high | [5] |
| Label | `#000000` | `#FFFFFF` | high | [5] |
| Secondary characters | `#C9C9C9` | `#707073` | high | [5] |
| Corner radius | 5 dp | 5 dp | high | [5] |
| Gaps | 2 dp inset on every side → 4 dp between keys | same | high | [5] |
| Pressed letter key | `#E6E8EB` | `#222226` | high | [5] |
| Suggestion bar | key colour (`#FFFFFF` / `#393A41`) with a 0.5 dp top line in the stroke colour | same construction | high | [5] |
| Flow trail | accent (`#000000` light, `#FFFFFF` dark), "LIGHT_DEFAULT" trail type | same | high | [5] |

The "SwiftKey blue" brand colours `#3961ED` (light) and `#7CAEFF` (dark) also appear in the
palettes.

---

## 4. Samsung Keyboard (One UI 7 / One UI 8)

Values from HoneyBoard 5.9.40.75's resource table [6]. The theme picker offers Light, Solid
light, Dark, Solid dark and the high-contrast themes; the default follows the system Light/Dark
setting. In this build the **"solid" variants are the flat ones**: their key fills are
`@color/transparent`. The non-solid Light/Dark themes, documented here, draw key shapes.

| Attribute | Light | Dark | Confidence | Source |
|---|---|---|---|---|
| Keyboard background | `#E4E4E7` | `#010102` | high | [6] |
| Letter-key fill (`text_key_background`) | `#FCFCFF` | `#3A3A3D` | high | [6] |
| Modifier / function keys (shift, backspace, !#1, enter) | `#C9C9CC` | `#17171A` (darker than the letter keys) | high | [6] |
| Enter key | function-key fill, **icon** `#252528` / `#FAFAFF` | same | high (colours) / medium (icon vs text) | [6] |
| Shift active | icon `#2E65D4` (shifted), `#376FDE` (caps lock) | `#578FFF` family | high | [6] |
| Key label colour | `#252528` | `#FAFAFF` | high | [6] |
| Hint / secondary label | `#252528` at 50 % (`#80252528`), top-right; **off by default** ("Alternative characters") | `#FAFAFF` at 50 % | high (colour) / medium (default off) | [6] |
| Corner radius | 6 dp (`drawable/key_bg`); keyboard outer corners 10 dp | same | medium (code may override) | [6] |
| Horizontal gap | 5.5 dp | same | medium (settings-preview dimens) | [6] |
| Vertical gap / key height | 6.8 dp / 42.5 dp | same | medium | [6] |
| Key shadow | light theme only: key elevation 3 (`integer/key_elevation_light`; unit not resolved, small soft shadow) | none (elevation 0) | medium | [6] |
| Pressed key (letter and function) | `#A6A6A9` | `#47474A` | high | [6] |
| Ctrl/modifier latched | `#376FDE` fill, `#FAFAFF` text | `#578FFF` | high | [6] |
| Key preview popup | `#FCFCFF`, text `#252528` | `#2D2D30`, text `#FAFAFF` | high | [6] |
| Suggestion / toolbar strip | expression toolbar `#F6F6F8` (the strip above the keys appears to share the keyboard background) | `#17171A` | low | [6] |
| Suggestion separators | estimate `#CCCCCE` (light) / `#4D4D52` (dark) category divider colours | — | low | [6] |
| Auto-replace suggestion text | `#387AFF` | `#387AFF` | high | [6] |
| Swipe trail (`trace_paint_color`) | `#2793FC` | `#2793FC` | high | [6] |
| Font | bundled SECKeypad Regular/Medium (not reproduced); lowercase until Shift | — | medium | [6] |

---

## 5. Samsung default US-English QWERTY arrangement

Evidence: the Settings "Custom symbols" preview, which draws the default bottom row at 360 dp
width (`dimen/custom_symbols_qwerty_default_*`, `layout/custom_symbol_bottom_row_layout`), and
the default-setting reads in the keyboard code [6]. Widths are in dp at 360 dp, then in letter-key
units (w = 29.5 dp, gap g = 5.5 dp).

| Item | Default | Confidence |
|---|---|---|
| Number row ("Number keys") | **Off**. The setting key `SETTINGS_DEFAULT_USE_ADDTO_NUMBER_KEY_FIRST_LINE` is read with default `false`; the backup default is a build flag, so some regions or carriers may ship it on | medium |
| Letter-key hints ("Alternative characters") | **Off** (`SETTINGS_DEFAULT_USE_ALTERNATIVE_CHARACTERS` default `false`); when on, digits on q–p and symbols on the other letters, top-right, 50 % label colour | medium |
| Row 1 | q w e r t y u i o p, 10 × 29.5 dp, gaps 5.5 dp (344.5 dp) | high (derived) |
| Row 2 indent | a–l, 9 keys centred: about 17.5 dp per side ≈ 0.5 w + 0.5 g | medium (derived) |
| Row 3 | shift, z–m, backspace; shift = backspace ≈ 47 dp ≈ 1.59 w (the row then matches row 1's width) | medium (derived) |
| Bottom row order | **`!#1`** (Samsung's "range" key) · **`,`** ("CM key") · **space** (shows the language name, e.g. "English (US)") · **`.`** · **enter** | high |
| Bottom row widths | `!#1` 47 dp (1.59 w) · `,` 29.5 dp (1 w) · space 169.5 dp (5.75 w, one language) · `.` 29.5 dp (1 w) · enter 47 dp (1.59 w) | high |
| Comma (CM) key long-press | not extracted; historically a small mode icon on the key and a long-press panel of input modes (voice, settings, emoji and similar) | low |
| Period key long-press | a panel of about ten punctuation symbols ("ten extra signs" [7]), user-customisable through Settings → Custom symbols; there is an option to keep the panel open for several symbols. The exact default set was not extracted | medium (behaviour) / low (set) |
| Space-bar row style option | the comma key can be swapped for `.,` (global punctuation style); the default is a separate `,` key | high |
| Toolbar | on by default above the keys (emoji, clipboard, settings, more); its icons are Samsung's own and are not reproduced | medium |

---

## 6. Sources

1. KeyboardKit 8.9.0 (Daniel Saidi), colour asset catalogue and style service:
   `https://github.com/KeyboardKit/KeyboardKit/tree/8.9.0/Sources/KeyboardKit/Resources/Colors.xcassets`
   (keyboardBackground `0.835/0.839/0.867` = `#D5D6DD`, dark `#2C2C2C`; button `#FFFFFF` /
   `#6B6B6B`; dark button `#ABB1BA` / `#474747`; shadow black 30 % / 70 %),
   `.../Styling/Services/KeyboardStyle+StandardService.swift` (fonts, pressed colours, `.blue`
   primary action), `.../Layout/KeyboardLayout+Configuration.swift` (5 pt radius, insets
   3 × 6 pt, 54 pt row), `.../_Essentials/Views/Keyboard+ButtonStyle.swift` (1 pt shadow),
   `.../Autocomplete/Autocomplete+ToolbarSeparatorStyle.swift` (1 × 30 pt, secondary 50 %),
   `.../Callouts/Callouts+CalloutStyle.swift`. Supports the iOS 18 table. Newer KeyboardKit
   (10.x+) ships as a binary framework, so 8.9.0 is the last readable colour source.
2. OpenWhispr iOS keyboard rebuild, PR #2726 (merged):
   `https://github.com/OpenWhispr/openwhispr/pull/2726`, file
   `openwhispr-mobile/plugins/keyboard-extension/ios/KeyboardViewController.swift` at
   `9cb91a85`. Measured on the iOS 26.5 simulator at 3x (iPhone 17 Pro / Pro Max): one key
   colour (255,255,255) over (226,228,232) light and (61,61,61) over (23,23,23) dark; pressed
   function key (196,197,200) / (125,125,125); disabled return; `#007AFF` prominent return; key
   metrics (6.5 pt margin, 6 pt gap, 43 pt key, 11 pt row gap, 8 pt continuous corners); no
   shadow; key-pop geometry; SF Compact keycaps; return glyph per return-key type. Supports the
   iOS 26 table.
3. Gboard 18.5.4 APK, `assets/theme/*.binarypb` (decoded with `protoc --decode_raw`):
   `theme_package_metadata_dynamic_color_{light,dark}_base`,
   `style_sheet_dynamic_color_{rules,light,dark}`, `style_sheet_default{,_border,_sw400}`,
   `style_sheet_color_{common,rules,label_black,label_white}`,
   `style_sheet_google_blue_{light,dark}`, `style_sheet_material_{light,dark}`. Supports the
   role mapping, corner radius, elevation, trail and separator roles, and the legacy theme hex
   values.
4. Material Color Utilities (npm `@material/material-color-utilities`), `SchemeTonalSpot` with
   seed `#1B6EF3`, contrast 0. Supports the Gboard hex values in §2b.
5. Microsoft SwiftKey 9.13.14.5 APK, `assets/themes/themelist.json` and
   `assets/themes/{7c610bde… Fluent Adaptive, 82f54162… Adaptive, c9161612… Dark, 43ffed79…
   Light}/style.json`, plus Fluent Adaptive's xxhdpi 9-patch key images (fill colour, corner
   radius, shadow and margins measured with Pillow). Supports §3.
6. Samsung Keyboard (HoneyBoard) 5.9.40.75 APK resource table (`aapt2 dump resources`):
   `color/{keyboard_background,text_key_background,function_key_background,*_pressed,
   text_key_main_label,text_key_secondary_label,preview_*,trace_paint_color,
   candidate_auto_replace_text_color}_{light,dark}`, `style/keyboard_{light,dark}_theme`,
   `drawable/key_bg` (6 dp radius), `integer/key_elevation_light` (3), the
   `dimen/custom_symbols_qwerty_default_*` dimensions, `layout/custom_symbol_bottom_row_layout`,
   and `classes2.dex` (baksmali) reads of `SETTINGS_DEFAULT_USE_ADDTO_NUMBER_KEY_FIRST_LINE` /
   `SETTINGS_DEFAULT_USE_ALTERNATIVE_CHARACTERS`. Supports §4 and §5.
7. Android Police, "11 simple Samsung Keyboard tips…":
   `https://www.androidpolice.com/samsung-keyboard-tips-trick/` (period key holds "ten extra
   signs"; number row and alternative characters are settings under Layout).
8. Android Police, "Gboard's new Material You change makes themes less exciting":
   `https://www.androidpolice.com/gboards-new-material-you-themes-more-color-less-variety/`
   (shift, ?123, comma/emoji, period, enter and backspace now share one colour).
9. 9to5Google, "Gboard for Android rolls out tweaked Dynamic Color theme" (2025-02-04):
   `https://9to5google.com/2025/02/04/gboard-dynamic-color-update/`.
10. Neowin, "SwiftKey for Android gets updated with Unicode 15 emojis and a new theme":
    `https://www.neowin.net/news/swiftkey-for-android-gets-updated-with-unicode-15-emojis-and-a-new-theme/`
    (Fluent Adaptive introduced in 8.10.34.6); MSPowerUser on the 2020 adaptive default:
    `https://mspoweruser.com/microsoft-swiftkey-beta-for-android-gets-updated/`.
11. Medium / Bootcamp, "iOS 26 keyboard redesign…":
    `https://medium.com/design-bootcamp/ios-26-keyboard-redesign-when-done-turns-into-wait-where-did-it-go-ab382d59821b`
    (iOS 26 keyboard has rounded outer corners over the system background; Done replaced by a
    checkmark).

---

## 7. What cannot be reproduced (or only approximately)

- **iOS 26 Liquid Glass**: live blur and refraction of the app content behind the keyboard,
  specular edge highlights and the rounded outer keyboard corners showing the app beneath. We
  can only draw the composited flat colour (`#E2E4E8` / `#171717`). iOS 18's blur material has
  the same limit.
- **Continuous ("squircle") corners** on iOS keys: approximated by circular-arc rounded
  rectangles of the same radius unless we implement a superellipse path.
- **iOS key-pop balloon shape** (S-curve neck into the key, head above the key row) and its
  animation; IME windows also cannot draw above their own bounds, so top-row pops are clipped.
- **Fonts**: SF Pro / SF Compact (iOS), Google Sans (Gboard), SECKeypad / Samsung One
  (Samsung) and SwiftKey's typeface are not bundled. The system sans-serif changes letter widths
  and x-height, so iOS 26's "lowercase drawn larger than capitals" is only approximate.
- **Icons and glyphs**: SF Symbols (return.left, shift, delete.left, globe), Gboard's and
  Samsung's toolbar icons, SwiftKey's PNG icons and space-bar branding. We use our own icons.
- **Wallpaper-derived colours**: Gboard's real Material You palette comes from the user's
  wallpaper via the system. §2b fixes one seed; matching a given phone needs the system dynamic
  colours.
- **9-patch shadows** (SwiftKey Fluent): the soft, slightly offset shadow is baked into PNGs; we
  approximate it with a drawn shadow, which will not match pixel for pixel.
- **Brand elements**: SwiftKey's space-bar logo, Samsung's language-name space bar styling and
  Gboard's "G"/feature buttons are product identity and are deliberately not copied.
- **Exact gaps for Gboard** and the **Samsung comma/period popup contents** were not resolvable
  from data (obfuscated or code-built layouts); they are estimates above.

---

## 8. What CleverKeys ships (`theme/KeyboardLooks.kt`)

The looks take the values above with these deliberate choices and deviations:

- **iOS-style = iOS 26** (one key colour, 8 dp corners, no shadow, blue editor-action key in
  Go / Search / Send / Done fields). The iOS 18 values are a separate look, **iOS 18-style**
  (grey function keys and return, 5 dp corners, 1 dp shadow at 30 % / 70 % black), offered in
  Theme Manager and bound to no layout. Pressed letter keys get a fill (`#C4C5C8` / `#7D7D7D`,
  the pressed function-key colours) because CleverKeys has no key-pop balloon.
- **Gboard-style = Material You on seed `#1B6EF3`** (§2b): comma and period are drawn as
  function keys (`functionChars = ",."`); Caps Lock and the editor-action key use `primary`;
  a faint 0.6 dp shadow stands in for elevation 1 (light only). Gaps are the low-confidence
  estimate, 0.13 of a key slot horizontally and 0.17 of a row vertically.
- **SwiftKey-style = Fluent Adaptive** (§3a), soft shadow 16 % black 0.7 dp. Light hint
  darkened from `#B7B7B7` (2.0:1) to `#888888` to reach 3:1 on keys and on the bar.
- **Samsung-style = One UI 8 Light/Dark** (§4): Caps Lock shows the blue shift glyph
  (`#2E65D4` / `#578FFF`) on the function-key fill; the highlighted suggestion uses the
  auto-replace blue `#387AFF`; light hint darkened from `#908F92` to `#7E7E82` for 3:1 on the
  bar; no separators (low-confidence data).
- Suggestion-bar text uses the label colour for every chip (SwiftKey's grey side candidates
  are below 4.5:1 on its background). Since §10 the looks' bar has the products' centre slot.
- **Legends and shapes (after the lead review, 2026-10-27).** The products' defaults on the
  Pixel reference screenshots: Gboard draws only 1–0 over the top row and pill-shaped ?123 and
  Enter; SwiftKey 9.13 draws no hints and has no number row; Samsung's "Alternative
  characters" are off (§5); iOS draws none. The looks follow that (`legends`, `pillKeys`), and
  the Gboard-style layout gained Gboard's dedicated emoji key between comma and space.
- Contrast floors enforced by `KeyboardLooksTest`: letter and modifier labels 4.5:1, Enter /
  action glyphs, pressed and Caps Lock states 3:1 (iOS's white-on-`#007AFF` return is 4.0:1),
  hints 3:1 on keys and bar, bar text 4.5:1 and highlight 3:1.

---

## 9. Follow-up 2026-10-28: wallpaper colours, key-press previews, hint style

Reference captures on the Pixel 8 Pro (Android 16; wallpaper seed `#5D646B`, Tonal Spot, per
`theme_customization_overlay_packages`), real Gboard and SwiftKey, light and dark, kept outside
git in `build/oct28-looks2/`. Colours were sampled from full-resolution screencaps.

**Gboard with wallpaper colours.** Light: background `#EAEEF4`, letter keys and emoji key
`#FFFFFF`, shift / delete / ?123 / comma / period / Enter `#D3E5F5`, popover selection `#346384`.
Dark: background `#151A1F`, letter keys and emoji key `#262D33`, function keys `#2D3D4A`,
selection `#A8CBE8`. Material Color Utilities (SchemeTonalSpot, 2021 spec) for the same seed
gives light `surfaceContainer #EBEEF3`, `surfaceContainerLowest #FFFFFF`, `secondaryContainer
#D3E5F5` (exact), `primary #27638A`; dark values are a few tones darker on the device
(Android 16's newer scheme spec), which is why CleverKeys reads the system's own role colours
on API 34+ instead of computing them. The role mapping of §2a is confirmed, and one row is
corrected: **the emoji key is drawn like a letter key** (`surfaceContainerLowest` /
`surfaceBright`), not with the function keys. Confidence: high (device samples).

**Key-press previews** (the products' defaults as seen on the Pixel; iOS and Samsung from §1 and
§4):

| Product | Shape | Colours | Confidence |
|---|---|---|---|
| Gboard ("Pop-up on keypress" on) | stadium bubble about 1.3 keys wide and 1.25 keys tall, centred on the key, its foot overlapping the key's top edge; label larger than on the key; a "…" under it when the key has alternatives; top-row bubbles rise over the toolbar | letter-key colour, soft shadow | high |
| Gboard long-press popover | rounded panel above the key (corner ≈ 0.4 of a cell), alternatives drawn directly on it, the selected one on a `primary` circle with `onPrimary` text; the keyboard is dimmed behind it | panel = letter-key colour | high |
| SwiftKey (Fluent) | a column exactly as wide as the key, from the key's bottom to about one row above it, label in the top part; long press shows the same column with the hint character | `#E3E3E3` / `#404040` | high |
| iOS 26 | key-pop balloon: head 12 pt wider than the key on each side, ~50 pt tall, top 66 pt above the key, joined to the key by a neck | key colour (`#FFFFFF` / `#505050`) | high (from §1a) |
| Samsung One UI 8 | rounded plate above the key | `#FCFCFF` text `#252528` / `#2D2D30` text `#FAFAFF` | medium (colours from resources, shape not captured) |

**Hint style.** Gboard's top-row digits are small (about 40 % of the letter size), regular
weight, at the top right; SwiftKey's hints are light weight; Samsung's alternative characters
are at the top right at 50 % of the label colour. CleverKeys draws a look's hints (Gboard's
digits, or all hints when the user turns them on) at those proportions in the look's hint
colour, with corner hints kept clear of rounded and pill corners.

**What CleverKeys cannot reproduce.** A preview cannot rise above the keyboard window, so a
top-row preview is drawn lower than Gboard's or iOS's; the iOS balloon's S-curve neck is
approximated by a rounded neck rectangle; Gboard's "…" marker is not drawn.

---

## 10. Follow-up 2026-10-27 (evening): the suggestion bar's arrangement

All four products show a fixed row of **three equal slots** above the keys, not a scrolling list.

| Product | Slots | Best candidate | Typed word | Separators | Confidence |
|---|---|---|---|---|---|
| Gboard | 3 | centre | left slot while a correction is pending (verbatim, no quotes) | thin vertical lines (`outlineVariant`, §2a) | medium (Pixel references, §9) |
| SwiftKey | 3 | centre, bold; "press the spacebar to insert the middle prediction" | "the left hand or middle prediction … will contain your verbatim text" | none (§3a) | high [7] |
| Samsung Keyboard | 3 | centre (auto-replace candidate in blue, §4) | left | none in the theme (§4) | low (no device capture) |
| iOS (QuickType) | 3 | centre (the autocorrection, on a white bubble in iOS 18) | the leading (left) suggestion, **in the locale's quotation marks** ("“teh”"), so the user can keep it | thin lines (§1) | medium [8] |

[7] Microsoft Support, "How does the Microsoft SwiftKey Prediction Bar work?" (updated
2026-09-14). [8] KeyboardKit, "Improved autocomplete" (2023-03-11): "add the currently typed
word as a leading suggestion, wrapped in locale-specific quotation marks like in the native iOS
keyboards".

**What CleverKeys ships** (`theme/LookBar.kt`, `KeyboardLook.bar`): every look has a
`LookBar(slots = 3)` — the best candidate in the centre, the typed word (or the "+word" tap-to-add
chip of an unknown word) left of it, then the rest right / left outwards; iOS- and iOS 18-style
quote the typed word (`quoteTyped`) with the locale's quotation marks. Separators keep following
the look's bar-separator colour (Gboard and iOS draw them, SwiftKey and Samsung none) and are drawn
only between two filled slots. "Best" is the entry the bar highlights — the word space commits
(`BarHighlightPlanner`) — or the top prediction when space would keep a typed word the bar does not
list; the highlight colour still marks only what space commits. It is drawing only: the bar's list,
its ranking order, the highlight index, what a slot's tap commits, swipe alternatives and the
"Prefer … when swiping?" / "Add to dictionary?" prompts (shown as before, not arranged) are
unchanged, and TalkBack reads the slots left to right as drawn. Entries past the third are not shown
on a look (the products show three). Every non-look theme keeps CleverKeys' scrolling chips. Layout
`<theme>` and custom themes carry it as `bar_slots` / `bar_centre` / `bar_quote_typed` (JSON `bar`).

### 10a. Device references for the bar (Pixel 8 Pro, 2026-10-27 night)

Same chat scene (Chrome `data:` page, "Sounds good, see you " then typed "tomorr"), the real
Gboard and SwiftKey selected for the capture only, light and dark (evidence
`build/oct27-night-pixel/`, not in git):

| | typed "tomorr" | after "tomorrow " (next word) |
|---|---|---|
| Gboard | `tomorr` · **`tomorrow`** · `tomorrow's` — all #E0E6EE dark, centre bold | `at` · `morning` · `and` — nothing bold |
| SwiftKey | `tomorr` · `tomorrow` · `tomorrow's` — centre #FFFFFF / sides #939393 dark, #292929 / #777777 light, regular weight | `and` · `morning` · `evening`, same inking |

Neither shows a "+" or italics on the unknown prefix: it is an ordinary candidate, and both learn
a word tapped there (Gboard's learned words, SwiftKey's verbatim slot). Both also draw a toolbar
row (Gboard: apps grid left, mic right; SwiftKey: a collapse chevron left of the predictions and a
toolbar above) that CleverKeys does not have. Changes: a look's slot shows the plain word
(`LookBarArrangement.slotText`), `LookBar.emphasis` COMMIT / CENTRE inks the slots as above, and
next-word predictions are never drawn as the space commit. The "+word" tap still commits the word
as typed and adds it to the dictionary (the closest CleverKeys equivalent of the products'
learn-on-tap); the plain bar keeps the "+word" chip.

## 11. Follow-up 2026-10-28: the editor-action key

Pixel 8 Pro, one Chrome `data:` page with an input per `enterkeyhint` (go, search, send, next,
done, previous, enter), the real keyboard selected for the capture only, light and dark (evidence
`build/oct27-night-pixel/action/`, not in git):

| Action | Gboard | SwiftKey |
|---|---|---|
| go | → | ✓ |
| search | magnifier | magnifier |
| send | filled paper plane | outlined paper plane |
| next | →\| | → |
| done | ✓ | ↵ |
| previous | \|← | ↵ |
| enter (multi-line) | ↵ | ↵ |

Gboard draws every action on the function-key pill (light `#D3E5F5`-family, dark slate — the same
fill as period and shift), never the primary colour; this corrects §2a's "highlighted Enter in
search/send fields", which this Gboard build does not do. SwiftKey keeps its ordinary key fill.
iOS follows §1a (iOS 26: blue `#007AFF` with →, magnifier, ↑, ✓ for Go / Search / Send / Done) and
§1b (iOS 18: lower-case words); Next / Previous on iOS are not documented and use the plain
return (low confidence). Samsung Keyboard could not be checked (no Samsung phone) and is left
unchanged. CleverKeys draws its own line icons (`LookActionKeys.parts`), not the products'.
