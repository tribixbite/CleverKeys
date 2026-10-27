---
title: Layout XML Reference
description: Every element and attribute of a CleverKeys layout file, including its own theme and per-key styling
category: Layouts
difficulty: advanced
---

# Layout XML Reference

A CleverKeys layout file describes the keys **and, optionally, how the keyboard looks** while
that layout is active: a whole theme (colours for light and dark mode, key gaps, corners,
shadows, hints, key-press preview) and per-row or per-key colours and gaps. Everything is
optional: a layout without styling looks like your chosen theme, exactly as before.

Paste or import a file in **Settings > Layout Manager > Add Layout > Custom** (see
[Custom Layouts](custom-layouts.md)). The editor checks the file: values it cannot use are
listed and **Save** stays disabled until they are fixed. A layout that reaches the keyboard
some other way (a backup, an older file) still loads; values it cannot use are skipped.

## Quick Summary

| What | Description |
|------|-------------|
| **Elements** | `<keyboard>` (root), `<theme>` with `<light>` / `<dark>`, `<row>`, `<key>`, `<modmap>` |
| **Own theme** | `<theme>` inside `<keyboard>`; anything it leaves out comes from your global theme |
| **Per-key style** | Colour and gap attributes on `<key>`, or on `<row>` for all its keys |
| **Keep it** | Layout Manager > **Save theme as custom theme** puts the layout's theme in Theme Manager |
| **Share it** | Theme Manager > **Export as layout XML**; Layout Manager > **Export XML** |

## Which setting wins

**Theme** (colours and look), most specific first:

1. the theme you pick with the layout's **Theme** chip in Layout Manager;
2. the `theme="…"` attribute on `<keyboard>` (a theme ID);
3. the layout's own `<theme>` element;
4. your global theme (Theme Manager).

**Follow global theme** on the Theme chip skips 2 and 3. **Layout's own theme** (offered when
the file has a `<theme>` and no `theme="…"`) uses 3. If 1 or 2 names a theme that is not on
this device, the layout's own `<theme>` is used, else the global theme. Fields the `<theme>`
leaves out are taken from your global theme in the current light/dark mode, so changing your
global theme also changes them.

**Key gaps and borders**, most specific first:

1. the attribute on `<key>`;
2. the attribute on its `<row>`;
3. the layout's **Key spacing & borders** chip in Layout Manager;
4. the `key_*` attributes on `<keyboard>`;
5. the `<theme>` (or the bound theme, if it is a familiar look);
6. **Settings > Appearance**.

Your Appearance settings are never changed by a layout.

## `<keyboard>` (root)

| Attribute | Value | Meaning |
|-----------|-------|---------|
| `name` | text | Name shown in Layout Manager; also the name a saved custom theme gets. |
| `script` | `latin`, `cyrillic`, `persian`, … | Script of the letters; needed for CTC swipe (see [Custom Layouts](custom-layouts.md#keeping-ctc-swipe-available)). |
| `numpad_script` | script name | Script of the number pane (default: `script`). |
| `bottom_row` | `true` (default) / `false` | Add CleverKeys' standard bottom row. |
| `embedded_number_row` | `true` / `false` (default) | The layout has its own number row; none is added. |
| `locale_extra_keys` | `true` (default) / `false` | Add the language's extra keys. |
| `numpad_height` | `true` / `false` (default) | Opt in to **Scale numpad height**. |
| `width` | number | Total width in key units (default: the widest row). |
| `language` | language code (`de`, `fa`, `pt_br`) | Default language binding. |
| `theme` | theme ID (`rosepine`, `look_gboard`, `custom_…`) | Default theme binding (beats `<theme>`). |
| `key_horizontal_gap` | 0–5 | Horizontal key gap, in the Appearance slider's unit. |
| `key_vertical_gap` | 0–5 | Vertical key gap, in the Appearance slider's unit. |
| `key_border` | `true` / `false` | Draw the custom border (radius and width below) or the theme's own frame. |
| `key_border_radius` | 0–100 | Corner radius, percent of the key width. |
| `key_border_width` | 0–10 | Border line width in dp. |

## `<theme>`

One `<theme>` element, anywhere inside `<keyboard>` (a second one is ignored). Every attribute
is optional. Colours are `#RRGGBB` or `#AARRGGBB` (alpha first; `#00…` is fully transparent).

### Colours

The same colour attributes are allowed on `<theme>` (both modes), `<light>` and `<dark>`
(replacing the `<theme>` value while the system is in that mode).

| Attribute | Colours… | If left out |
|-----------|----------|-------------|
| `key` | letter-key fill | global theme |
| `key_pressed` | fill of a pressed key | global theme |
| `key_locked` | fill of Caps Lock and other locked modifiers | global theme |
| `key_modifier` | fill of Shift, Ctrl, Alt | global theme |
| `key_special` | fill of Backspace, 123, emoji and other function keys | global theme |
| `label` | key labels | global theme |
| `sub_label` | corner labels (gesture hints) | global theme |
| `secondary_label` | dimmed labels of function keys | global theme |
| `border_color` | key outline | global theme |
| `border_color_pressed` | outline of a pressed key | global theme |
| `swipe_trail` | swipe trail | your swipe-trail colour |
| `ripple` | press feedback in the suggestion bar | global theme |
| `bar_text` | suggestion text | global theme |
| `bar_background` | suggestion bar | global theme |
| `bar_highlight` | the suggestion space will commit | global theme |
| `keyboard_background` | behind the keys | global theme |
| `surface` | pop-up panels of the suggestion bar | global theme |
| `label_pressed` | label of a pressed key | global theme |
| `locked_label` | label on Caps Lock | global theme |
| `enter_key` | plain Enter fill | `key_special` |
| `enter_label` | label on plain Enter | normal labels |
| `action_key` | Enter replaced by Go / Search / Send / Done | `key_special` |
| `action_label` | label on the action key | normal labels |
| `modifier_label` | labels on modifier and function keys | normal labels |
| `key_shadow` | shadow under each key (drawn when `shadow_dy` > 0) | none |
| `bar_separator` | lines between suggestions (`#00FFFFFF` = none) | sub-label colour, faint |
| `popup` | key-press preview and long-press panel | `key` |
| `popup_label` | label in the preview and panel | `label` |
| `popup_selected` | selected long-press alternative | `key_locked` |
| `popup_selected_label` | its label | `locked_label` |

### Shape and size

| Attribute | Value | Meaning |
|-----------|-------|---------|
| `gap_horizontal` | 0–0.5 | Gap between keys, as a fraction of one key's width. |
| `gap_vertical` | 0–0.5 | Gap between rows, as a fraction of the row height. |
| `corner_radius` | 0–64 (dp) | Key corner radius. |
| `border_width` | 0–8 (dp) | Key outline width (0 = none). |
| `shadow_dy` | 0–8 (dp) | How far below each key its `key_shadow` is drawn (0 = none). |
| `label_scale` | 0.5–2 | Label size relative to **your** label size setting. |
| `label_weight` | 100–900 | Label font weight (400 regular, 700 bold; Android 9+). |

Gaps here are relative so they fit any keyboard height; the `key_*` gaps on `<keyboard>`,
`<row>` and `<key>` use the Appearance slider's unit and win over these.

### Hints, keys and preview

| Attribute | Value | Meaning |
|-----------|-------|---------|
| `hints` | `all` / `digits` / `none` | Which corner labels are drawn (gestures always work). **Appearance > Show gesture hints on familiar looks** draws them all. |
| `hint_size` | 0.1–1 | Corner-label size as a fraction of the main label. |
| `hint_weight` | 100–900 | Corner-label font weight. |
| `pill_keys` | any of `enter`, `action`, `numbers` (space-separated; empty = none) | Keys drawn fully rounded. |
| `letter_keys` | `emoji` | Draw the emoji key like a letter key. |
| `function_chars` | up to 16 characters, e.g. `,.` | Characters drawn with the function-key colours. |
| `preview` | `none` / `bubble` / `balloon` / `column` / `plate` | Key-press preview shape (**Appearance > Key-press preview on familiar looks** turns previews off). |
| `preview_width` | 0.5–3 | Preview width in key widths (needs `preview`). |
| `preview_height` | 0.5–3 | Preview height in key heights. |
| `preview_label_scale` | 0.5–3 | Preview label size relative to the key's label. |
| `preview_corner` | 0–1 | Preview corner radius as a fraction of the key height. |
| `preview_shadow` | `true` / `false` | Soft shadow under the preview. |
| `dynamic_color` | `true` / `false` | Follow the wallpaper colours on Android 12+ (Material You); the colours above are the fallback. |
| `name` | text | Name for the theme when it is saved or exported. |

## `<row>`

| Attribute | Value | Meaning |
|-----------|-------|---------|
| `height` | number ≥ 0.5 (default 1) | Row height in row units. |
| `shift` | number ≥ 0 | Empty space above the row. |
| `scale` | number | Stretch the row's keys to this total width. |
| styling | see below | Default styling for every key of the row. |

## `<key>`

| Attribute | Value | Meaning |
|-----------|-------|---------|
| `c` or `key0` | key value | The key's main value (letter, `shift`, `space`, `label:action`, …). |
| `nw` `ne` `sw` `se` `w` `e` `n` `s` (or `key1`–`key8`) | key value | Swipe-direction values (`loc …` only when that key is enabled). |
| `anticircle` | key value | Value of the anticlockwise circle gesture. |
| `width` | number (default 1) | Key width in key units. |
| `shift` | number | Empty space to the left of the key. |
| `indication` | text | Small text printed on the key. |
| styling | see below | This key's own styling (beats its row's). |

### Styling attributes (`<key>` and `<row>`)

| Attribute | Value | Meaning |
|-----------|-------|---------|
| `key_horizontal_gap` | 0–5 | This key's horizontal gap (Appearance slider unit). |
| `key_vertical_gap` | 0–5 | This key's vertical gap. |
| `key_border` | `true` / `false` | Custom border on, or the theme's own frame. |
| `key_border_radius` | 0–100 | Corner radius, percent of the key width. |
| `key_border_width` | 0–10 (dp) | Border line width. |
| `key_background` | colour | Fill at rest. |
| `key_background_pressed` | colour | Fill while pressed. |
| `key_border_color` | colour | Outline colour. |
| `key_label_color` | colour | Label colour at rest. |
| `key_label_color_pressed` | colour | Label colour while pressed. |

A key's own gap changes only how large its key is **drawn** inside its place on the
keyboard: where you touch, and the key positions swipe typing uses, stay exactly the same.

Key and row colours are the **same in light and dark mode**; only the `<theme>` (with its
`<light>`/`<dark>` children) changes with the mode. A key that sets `key_background` but no
`key_label_color` keeps the theme's label colour for the current mode, which can vanish on a
fixed background (a light fill under dark mode's light labels). Set `key_label_color` together
with `key_background`, and pick colours that read in both modes. A latched Shift or other
modifier keeps its `key_label_color` on the pressed fill, so give such a key a
`key_background_pressed` (and `key_label_color_pressed`) too.

## `<modmap>`

Optional remapping of keys under a modifier: `<modmap><shift a="a" b="A"/></modmap>`; children
`<shift>`, `<fn>` or `<ctrl>`, each with `a` (the key) and `b` (what it becomes).

## Saving and sharing a layout's theme

- **Layout Manager > Save theme as custom theme** (shown for a layout with a `<theme>`) stores it
  as a custom theme named after the layout. Anything the `<theme>` left out is filled in from
  your current global theme, so the custom theme looks exactly like the layout did. It appears
  in Theme Manager under Custom, can be selected or bound to other layouts, and opens in the
  Theme Creator, whose **Keyboard Look** section edits every field above.
- **Theme Manager > Export as layout XML** (the share button on a custom theme or a familiar
  look) writes that theme as a `<theme>` element to paste into a layout.
- **Layout Manager > Export XML**, for a layout whose theme is a custom theme, can include it
  as the file's `<theme>` (**Include the layout's custom theme in the file**). The `theme="…"`
  binding is kept: on another device without that custom theme, the included copy is used.
- Saving a layout's theme and exporting it again gives back every value the `<theme>` had.

## Worked example

This complete layout uses every element and styling attribute. It is also the file the app's
tests load (`docs/wiki/layouts/examples/harbour.xml`).

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Harbour: a complete example of a self-styled CleverKeys layout.
     Reference: docs/wiki/layouts/layout-xml-reference.md. Paste it into
     Settings > Layout Manager > Add Layout > Custom, or import the file there.
     Every attribute of <theme>, <row> and <key> styling is used at least once. -->
<keyboard name="Harbour" script="latin" bottom_row="false"
          key_horizontal_gap="1.2" key_vertical_gap="1.5"
          key_border="true" key_border_radius="18" key_border_width="1">
  <theme
      name="Harbour"
      key="#FFFFFF"
      key_pressed="#C9D6E3"
      key_locked="#1F6FB2"
      key_modifier="#D7E2EC"
      key_special="#D7E2EC"
      label="#102A43"
      sub_label="#52667A"
      secondary_label="#52667A"
      border_color="#B8C7D6"
      border_color_pressed="#1F6FB2"
      swipe_trail="#1F6FB2"
      ripple="#C9D6E3"
      bar_text="#102A43"
      bar_background="#E8EEF4"
      bar_highlight="#1F6FB2"
      keyboard_background="#E8EEF4"
      surface="#FFFFFF"
      label_pressed="#102A43"
      locked_label="#FFFFFF"
      enter_key="#1F6FB2"
      enter_label="#FFFFFF"
      action_key="#E07A1F"
      action_label="#FFFFFF"
      modifier_label="#243B53"
      key_shadow="#33102A43"
      bar_separator="#00FFFFFF"
      popup="#FFFFFF"
      popup_label="#102A43"
      popup_selected="#1F6FB2"
      popup_selected_label="#FFFFFF"
      gap_horizontal="0.12"
      gap_vertical="0.16"
      corner_radius="10"
      border_width="0"
      shadow_dy="1"
      label_scale="1.15"
      label_weight="500"
      function_chars=",."
      hints="digits"
      pill_keys="enter action"
      letter_keys="emoji"
      preview="bubble"
      preview_width="1.3"
      preview_height="1.25"
      preview_label_scale="1.2"
      preview_corner="0.2"
      preview_shadow="true"
      hint_size="0.4"
      hint_weight="400"
      dynamic_color="false">
    <light keyboard_background="#E8EEF4" bar_background="#E8EEF4"/>
    <dark key="#1C2B3A" key_pressed="#2E4458" key_modifier="#132130" key_special="#132130" label="#E6EEF5" sub_label="#9FB3C8" secondary_label="#9FB3C8" border_color="#2E4458" bar_text="#E6EEF5" bar_background="#0B1621" bar_highlight="#7FB8E6" keyboard_background="#0B1621" surface="#1C2B3A" label_pressed="#E6EEF5" modifier_label="#D9E2EC" popup="#2E4458" popup_label="#E6EEF5"/>
  </theme>
  <row>
    <key c="q" ne="1"/>
    <key c="w" ne="2"/>
    <key c="e" ne="3" se="loc €"/>
    <key c="r" ne="4"/>
    <key c="t" ne="5"/>
    <key c="y" ne="6"/>
    <key c="u" ne="7"/>
    <key c="i" ne="8"/>
    <key c="o" ne="9"/>
    <key c="p" ne="0"/>
  </row>
  <row>
    <key shift="0.5" c="a"/>
    <key c="s"/>
    <key c="d"/>
    <key c="f" key_label_color="#3A86C8"/>
    <key c="g"/>
    <key c="h"/>
    <key c="j" key_label_color="#3A86C8"/>
    <key c="k"/>
    <key c="l"/>
  </row>
  <row key_background="#3E6A93" key_border_color="#9FB3C8" key_label_color="#FFFFFF">
    <key width="1.5" c="shift" nw="home" ne="end" sw="loc capslock" key_background="#1F6FB2"
         key_background_pressed="#174F80" key_label_color_pressed="#FFFFFF"/>
    <key c="z"/>
    <key c="x"/>
    <key c="c"/>
    <key c="v"/>
    <key c="b"/>
    <key c="n"/>
    <key c="m"/>
    <key width="1.5" c="backspace" nw="delete_last_word" ne="delete" key_background="#1F6FB2"/>
  </row>
  <row height="0.95" key_vertical_gap="2">
    <key width="1.5" c="?123:switch_numeric" nw="ctrl" ne="fn" sw="loc switch_greekmath"/>
    <key c=","/>
    <key c="switch_emoji" nw="loc change_method" ne="config" sw="loc switch_clipboard" se="switch_gif"/>
    <key width="4" c="space" n="loc switch_forward" s="loc switch_backward" w="cursor_left" e="cursor_right" key_horizontal_gap="0.6"/>
    <key c="." nw="'" n="\?" ne="!" sw="-" se=":"/>
    <key width="1.5" c="enter" ne="action" n="up" s="down" w="left" e="right"
         key_border="false" key_background_pressed="#174F80" key_label_color_pressed="#FFFFFF"/>
  </row>
</keyboard>
```

What it does: white letter keys on a light blue-grey background (dark navy in dark mode),
a blue Enter (and orange in Go / Search / Send fields), Gboard-like digit hints on the top row,
a bubble preview, `f` and `j` with blue labels, the third row in steel blue with white labels
and a blue-grey outline (Shift and Backspace in the Enter key's blue, a latched Shift dark blue;
these colours read in both modes), a taller gap above the bottom row, a slightly wider space bar frame and an Enter key
without the custom border that turns dark blue while pressed.

## Related Features

- [Custom Layouts](custom-layouts.md) - Adding a layout file
- [Themes](../customization/themes.md) - Theme Manager, custom themes and familiar looks
- [Switching Layouts](switching-layouts.md) - Space-bar switching
