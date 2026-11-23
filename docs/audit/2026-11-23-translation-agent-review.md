# Translation agent review — 2026-11-22/23 (AI review, not the native-speaker review)

Reviewer: Fable 5.1 (one coordinating session, one fresh reviewer agent per locale, 4 at a time).
This is an AI review of the strings added or changed since 2026-09-29 in the 21 locales. It is
recorded as such: no native speaker has read any of these strings, todo item 10 stays open and the
M2 shipping decision stays the maintainer's. A second, independent AI review by GPT-6 ran in the
same checkout at the same time (`2026-11-23-translation-independent-review.md`); its reconciliation
table is answered in §5.

## 1. Scope and result in one table

Sheets: `docs/i18n/review/2026-11/<locale>/` as re-exported 2026-11-22 at `17e8b8d4`
(636 translate rows, 346 check rows, 34,419 read-through rows). Every row of every sheet was read
by the locale's reviewer agent; a cell was written only for a real defect (wrong meaning, wrong
language or leftover English, broken placeholder, register against the locale's rule, glossary
term used inconsistently) or for a translation of a still-English row where the language has its
own word. "written" in sheet 1 counts translations; the other sheet-1 rows were left English on
purpose (printed key names, diacritic and theme names, units, codes, Taglish in fil).

| locale | 1 translate (rows / written) | 2 check (rows / fixed) | 3 read-through (rows / fixed) | native questions |
|---|---|---|---|---|
| cs | 23 / 2 | 14 / 7 | 1745 / 11 | 1 |
| de | 30 / 0 | 7 / 2 | 1613 / 13 | 1 |
| es | 22 / 1 | 9 / 4 | 1687 / 13 | 0 |
| fa | 7 / 1 | 27 / 12 | 1632 / 7 | 8 |
| fil | 126 / 4 | 25 / 10 | 1535 / 6 | 4 |
| fr | 32 / 0 | 21 / 14 | 1676 / 14 | 2 |
| hu | 28 / 1 | 20 / 11 | 1610 / 10 | 2 |
| in | 48 / 1 | 21 / 11 | 1523 / 7 | 1 |
| it | 28 / 0 | 28 / 4 | 1660 / 12 | 1 |
| ja | 15 / 0 | 9 / 6 | 1561 / 16 | 3 |
| ko | 7 / 3 | 7 / 3 | 1570 / 14 | 1 |
| lv | 19 / 3 | 14 / 6 | 1684 / 20 | 1 |
| nl | 46 / 0 | 9 / 4 | 1610 / 30 | 2 |
| pl | 30 / 0 | 20 / 13 | 1738 / 6 | 4 |
| pt | 42 / 0 | 10 / 3 | 1666 / 21 | 2 |
| ro | 50 / 0 | 14 / 11 | 1688 / 19 | 2 |
| ru | 16 / 0 | 18 / 3 | 1746 / 10 | 0 |
| tr | 23 / 0 | 13 / 7 | 1617 / 7 | 2 |
| uk | 16 / 0 | 21 / 5 | 1747 / 9 | 1 |
| vi | 22 / 0 | 17 / 5 | 1546 / 12 | 3 |
| zh-rCN | 6 / 2 | 22 / 11 | 1565 / 14 | 4 |
| **total** | **636 / 18** | **346 / 152** | **34419 / 271** | **45** |

Sheet-2 rows not fixed (194) were confirmed right by the reviewer with a reason in the locale's
notes (most often: the glossary lead pointed at the everyday sense of "layout", at the literal
`{clipboard}` template token, at Android's own row-swipe verb, or at the Settings path verified
against AOSP). 13 further strings outside the sheets were corrected directly (§5). No `--force`
was used anywhere; every cell passed the placeholder, literal-%, length, glossary and register
checks before it was written.

## 2. Method

- Reading order per locale: sheet 1, sheet 2, then sheet 3 in chunks of ~150 rows, each row with
  its English and current translation; `where`/`code` and the source line were opened when the
  meaning was ambiguous (a key label, a toast and a settings title need different wording).
- The reviewer could write only `your_translation` / `your_fix`, through a helper that refused a
  cell when it would fail the import or the pure tests: placeholder multiset against the current
  resource, bare `%` in a formatted string, the `TranslationLengthTest` slot limits (titles and
  `cmd_*` 2× English, `suggestion_*` 1.75×, never under 40 characters), the `TranslationGlossaryTest`
  allowed/forbidden stems on glossary-keyed strings, and the locale's address-register regexes
  from `docs/i18n/glossary.json`. An uncertain row was left empty and recorded as a question.
- Each locale was then imported with `scripts/export_translation_review.py --import <locale>/*.csv`
  (dry run first; 0 refusals in every locale) and checked by a pure-Python stand-in for the five
  translation test classes over the whole locale file (coverage by name and kind, format-argument
  parity, literal `%`, quote escaping, length, glossary, register, catalog English ratio). The
  Gradle classes themselves could not run during the review because `compileDebugUnitTestKotlin`
  was red on another session's in-flight test files (§6).
- Register, terminology, keepEnglish and the Filipino Taglish policy follow
  `docs/i18n/2026-09-29-register.md`, `2026-09-29-terminology.md`, `glossary.json`,
  `review-flags.json` and `2026-10-08-coverage-audit.md`; the LanguageTool items of
  `2026-10-22-translation-audit.md` §8 were answered in sheet 2 (de Gestenhinweise made consistent,
  nl "Zijwoorden gedimd" replaced, it "input vocale" kept as Android's own term, pt "app"/"depois
  de" kept).

## 3. Findings that recur across locales

- `dict_word_too_long_for_swipe_msg`: every one of the 21 translations had dropped the last English
  sentence "Swiping will not offer it." Restored in all 21 (every plural item).
- `appearance_look_scene_overlay_desc`: 10 locales said the display hardware moves the aurora
  "instead of redrawing the keyboard"; the English says instead of *the keyboard* redrawing the
  aurora. Fixed in cs es fa fil hu ja ko pl ru uk (fr it pt nl vi zh-rCN were right).
- Glossary drift in the November strings: fr "glissement" (9 rows), in "usap" (3) and "papan klip"
  (6), fa "کلیپ‌بورد" (5) and "لغزش" (2), hu "húzás" (4), pl "przesuwanie" (9), ru/uk "ввод жестом"
  (3 each), tr "sözcük" (3), zh-rCN "滑行" (5) and "更正" (4), vi "khay nhớ tạm" (4), fil
  "diksiyonaryo" (7) — each replaced by the locale's agreed term.
- Product-name drift: the new strings named the Theme Manager / Layout Manager / Appearance screens
  differently from their own titles (de Design→Theme, it/ja/pt/tr/hu/fa/vi screen names, lv
  motīvs→tēma, ro aspect→aranjament for keyboard layouts). Aligned with the screen titles.
- Plural `other` items in ru and uk carried the `many` form; `other` is the fractional class and
  takes the genitive singular (3 keys each). cs `learning_data_phrase_count#many` likewise.
- Register slips the test regexes do not list: es usted forms in 5 strings, hu "importálod",
  ro "Restabilești"/"atinge" (5), pl a masculine first-person "wpisałem". Fixed.
- pt-PT vocabulary in the pt-BR file (aplicação, guardar, premir, definições, fecho, descodificar…):
  12 rows fixed; older pt-PT leaks outside the sheets are listed in §8.
- "latched" Shift worded as "locked" in fr/nl/tr/zh-rCN, colliding with Caps Lock: fr enclenchée,
  nl vastgezet, tr tek seferlik, zh-rCN 临时启用 (ko 고정된 and ro fixată left as questions).
- cs/hu "opacity" was translated as transparency (Průhlednost / átlátszóság), inverting what the
  slider means: Neprůhlednost / átlátszatlanság in the 6 scoped keys and the 11 older opacity keys.

## 4. Notable per-locale fixes (the full list is in each locale's sheets; one line per theme)

- **cs**: "tažení/přejetí" → "krátké tahy"; `clipboard_entry_preview_truncated` "po stranách" (along the sides) → page by page; `autocorrect_short_deletion_scope_title` lost "short-word"; theme_category Neon/Pastel → Neonové/Pastelové.
- **de**: Gesten-Hinweise → Gestenhinweise; Design(-verwaltung) → Theme(-Manager) ×6; Rückschritt → Rücktaste; Wischtippen → Wischeingabe; `import_preview_custom_themes_warning#one` grammar; Autofill and the Settings path verified against AOSP de.
- **es**: usted → tú ×5; `cmd_accent_tilde` Tilde → Virgulilla (matches the extra-key label); `cmd_insert` Insertar → Insert (printed key name); `backup_headless_dict_exported` missing "palabras"; `clipboard_merge_copied` gender.
- **fa**: کلیپ‌بورد → بریده‌دان, لغت‌نامه → واژه‌نامه, لغزش → کشیدن; `common_copied_to_clipboard` → AOSP "کپی شد"; leftover English "backspace" → پس‌بر; Caps Lock label → قفل حروف بزرگ.
- **fil**: Taglish policy kept (122 of 126 sheet-1 rows stay English); diksiyonaryo → diksyunaryo; "Settings" → "Mga Setting"; sinwipe → na-swipe; Itsura → Hitsura; theme_mode_dark/light translated; Pag-ikot (rotation) → Pagbilog ng Sulok; Malabo (blurry) → Pinadilim.
- **fr**: glissement → glissé ×9; `appearance_look_scene_overlay_desc` meaning; `gesture_finger_occlusion_desc` and `swipe_context_rescoring_desc` dropped clauses; `gesture_max_distance_desc` inclusive boundary; `intent_preset_open_maps` Plans → Maps; `extra_keys_reset_title` meaning; indices → indications; Maj verrouillée → enclenchée; "Effacer la sélection" → "Tout désélectionner".
- **hu**: "importálod" (te) → "importálja"; húzás → csúsztatás; mozdulat → gesztus (Gesztustippek consistent); elrendezés → kiosztás; átlátszóság → átlátszatlanság ×11; `suggestion_prefer_when_swiping` conjugation.
- **in**: usap → geser; papan klip → clipboard; Android id Settings path; `theme_shape_shadow` "Geser Bayangan" (reads as swipe) → "Jarak Bayangan Tombol"; `command_palette_template_help` "Bentangkan" (unfold) → "Mengganti … dengan nilainya"; "warni" is not a word.
- **it**: "parola tracciata" → "digitata a scorrimento"; `appearance_look_scene_overlay_desc` meaning; Gestione temi / Gestione layout; "metodo di inserimento" → "immissione" (Android); `[piano]` → `[pianificato]`; "input vocale" ×18 kept as Android's own term, distinct from "Digitazione vocale" (the system voice-typing key).
- **ja**: 通知 (notification) → 告知 for legal NOTICE ×4; 端末 (device) → ターミナル; テーマ管理 → テーママネージャー; `cmd_paste_pinned_*_desc` omitted "clipboard"; 短スワイプ → ショートスワイプ; extra_key_accent_* aligned with cmd_accent_*.
- **ko**: 제안 → 추천; 오고넥/캐론 aligned with their pairs; 날짜(길게) (reads as long-press) → 긴 형식; 기하학 (the discipline) → 기하 방식; 이모티콘 → 이모지; Caps Lock / Scroll Lock labels translated.
- **lv**: Cedilla → Sedijs ×5; "vilcieni" (trains) → "vilkumi" (short swipes) ×3; motīvs → tēma ×6; mājiens → norāde ×4; `clipboard_private_copy_other_apps_desc` and `cmd_copy_private_desc` dropped clauses; zero-class genitive.
- **nl**: `input_capitalize_i_desc` wrong meaning (Dutch never capitalises "ik"); `gesture_short_swipes_only_desc` "Vegen staat uit" contradiction; `clipboard_filter_match_any` "één" → "minstens één"; `tile_subtitle_toggle` duplicated the switch tile; "Zijwoorden gedimd" → "Buitenste woorden gedimd"; Vergrendelde → Vastgezette Shift.
- **pl**: `gesture_min_swipe_distance_desc` dropped parenthetical; `clipboard_private_copy_other_apps_desc` quoted the wrong menu label; "wpisałem" → impersonal; skrótów → form ściągniętych; gest- in 9 rows.
- **pt**: pt-PT → pt-BR ×12; «» → “ ”; Gestor → Gerenciador; "aba de notificações" → "painel de notificações" (Android); clipe → recorte; sim/não → lig./desl.
- **ro**: aspect → aranjament ×6 (keyboard layout); notificări → mențiuni (legal notices) ×6; AOSP Settings path and the "Permite" button; -ți register ×5; "deformat" → "cu format nevalid"; "Majusculizează" is not a word; `theme_save_failed` calque.
- **ru**: `other` plural items; ввод жестом → свайпом; `theme_preview_balloon` Шар (a ball) → Выноска (the iOS callout); hook above "хвостик" → "крючок" ×5; `clipboard_private_copy_other_apps_desc` disclosure restored.
- **tr**: `autocorrect_short_deletion_scope_title` lost "short-word"; sözcük → kelime; gizli → özel (private copy); leftover English "haptic"/"backspace"; Kilitli → Tek Seferlik Shift; `theme_save_failed` calque.
- **uk**: Settings path → AOSP "Мова й введення"; введення жестом → свайпом; `other` plural items; `input_swipe_distance_threshold_desc` dropped clauses; програм → застосунок; `clipboard_private_copy_other_apps_desc` disclosure restored.
- **vi**: "khay nhớ tạm" → "bộ nhớ tạm hệ thống"; giao diện → chủ đề for theme ×6; "thẻ" (tags) → "tab"; extra_key_accent_* aligned with cmd_accent_*.
- **zh-rCN**: 滑行 → 滑动 ×5, 更正 → 纠正 ×4; `input_delete_word_url_pairs_desc` unreadable; 小数 (decimal fractions) → 小数字; half-width → full-width punctuation ×7; 锁定的 Shift → 临时启用的 Shift; Scroll Lock → 滚动锁定; caron 抑扬符 / circumflex 扬抑符 confirmed correct.

## 5. Reconciliation with the independent GPT-6 review (same day)

Its "Findings requiring reconciliation" table, checked against the tree after the per-locale pass:

| Finding | State now |
|---|---|
| `clipboard_private_copy_other_apps_desc` disclosure missing in lv, ru, uk | lv had been restored by the lv pass; ru and uk are NOT in their sheets (unchanged since 2026-09-29), so they were corrected directly in `res/values-ru`/`-uk/strings.xml` (`50d538e5d`). |
| `appearance_look_scene_overlay_desc` redraw agent in 10 locales | Fixed in all 10 (sheet 3 cells, imported). |
| cs/hu opacity as transparency (6 scoped keys, plus older `appearance_keyboard_opacity_*`, `appearance_activated_key_opacity_*`, `input_suggestion_bar_opacity_*`, cs `theme_color_opacity`) | All fixed: 6 sheet keys per locale, and the 11 older keys directly (cs 6, hu 5; hu `theme_color_opacity` already said Átlátszatlanság). `input_suggestion_bar_opacity_desc` left: the English itself says "Transparency" there (§7). |
| fr `gesture_max_distance_desc` boundary, `swipe_context_rescoring_desc` condition | Both fixed. |
| es `import_preview_custom_themes_warning#one` "el %1$d tema" | Fixed ("por %1$d tema del archivo"); the same construction in nl fixed too; de had been fixed by the de pass. |
| fil `theme_preview_corner` rotation vs rounding | Fixed (Pagbilog ng Sulok). |
| latched vs locked Shift in fr, nl, tr, zh-rCN | Fixed (§3). ro "fixată", ko "고정된", lv "Fiksēts", hu "Rögzített", ru/uk "зафиксирована" are distinguishable from their Caps Lock labels and stay as native questions. |
| fil `dict_word_too_long_for_swipe_msg` | Already restored by the fil pass. |

Also taken from its question list: zh-rCN `input_numpad_layout_low_first` 小数 → 小数字; fr
`clipboard_selection_clear` → "Tout désélectionner"; fil `theme_bar_emphasis_centre` "Malabo"
(blurry) → "Pinadilim"; ro/tr/zh-rCN `theme_save_failed` literal "changes still open" → "remain
open in the editor". Not taken: fa `io_error_no_backup_password` — "هیچ گذرواژهٔ پشتیبانی" is
پشتیبان + the indefinite ی that هیچ requires, not "support"; the ja 通知, ko 카론/오고네크, vi giao
diện and khay nhớ tạm, lv motīva items were already fixed by the per-locale pass. Its remaining
native-speaker questions are folded into §6.

Direct (out-of-sheet) edits, all in `50d538e5d`: ru, uk `clipboard_private_copy_other_apps_desc`;
cs `theme_color_opacity`, `appearance_keyboard_opacity_title/_desc`,
`appearance_activated_key_opacity_title/_desc`, `input_suggestion_bar_opacity_title`; hu
`appearance_keyboard_opacity_title/_desc`, `appearance_activated_key_opacity_title/_desc`,
`input_suggestion_bar_opacity_title`.

## 6. Open questions for a native speaker (every one; locale — key — why)

- **cs** — `cmd_accent_hook_above` (+ `_desc`, `cmd_combining_hook_above`, `_desc`, `extra_key_accent_hook_above`): "Háček nad" for U+0309 while háček is the Czech caron; confirm the name. (GPT-6) `short_swipe_reset_all_message#many`, `import_preview_custom_themes_warning#many`: fractional-class morphology. Opacity term: Neprůhlednost (chosen) vs Krytí.
- **de** — `extra_key_title_voice` "Sprache" (language/speech) on a ~5-character key: keep, or Diktat/Mikro? (GPT-6) `intent_preset_dial_phone` "Telefon wählen" vs "Telefonnummer wählen"; `autocorrect_short_deletion_scope_desc` "diese Kosten … ist sie" agreement; `clipboard_delete_selected_result#one` "ausgewähltem Clip" dative.
- **fa** — `short_swipe_direction_short_n/ne/e/se/s/sw/w/nw`: no established one-letter compass abbreviations; ش serves شمال and شرق, and ق for east is unrecognisable. Keep, Latin N/E/S/W, or خ/ب (خاور/باختر)? Labels must stay 1–3 characters.
- **fil** — `short_swipe_direction_short_*` badges H/S/T/K (S = Silangan collides with the English S = south; `settings_faq_numbers_a` must follow); `theme_preview_bubble/column/plate` English vs Bula/Kolum/Plato (Balloon is "Lobo"); `swipe_trail_effect_solid` Solid vs Solido; `settings_auto_capitalization_title` translated vs AOSP tl "Auto-capitalization". (GPT-6) `licenses_intro` "libreng" (gratis) vs "malayang" (free as in freedom) software; the five 2026-10-08 title questions stand.
- **fr** — `multilang_pack_deleted`: pack vs paquet split file-wide (23 vs 33) and paquet vs package for Android package names; `appearance_look_motion_fps_value` "%1$d i/s" vs ips/fps. Opacity/"enclenchée" for latched Shift is my choice.
- **hu** — `cmd_accent_breve` Breve vs Brevis; `theme_predefined_utilitarian_charcoal` "Faszén" (the fuel) vs Antracit/Szénszürke; átlátszatlanság (chosen) vs fedettség. (GPT-6) `extra_key_accent_bar`/`_slash` diacritic terms.
- **in** — `swipe_trail_effect_solid` "Solid" vs "Polos". (GPT-6) `short_swipe_direction_*` abbreviations U/T/S/B/TL/BL/TG/BD (the reviewer found them standard).
- **it** — `cmd_combining_slavonic_dasia`/`_psili` gender (made feminine as for the Greek breathings). (GPT-6) `clipboard_delete_selected_result#one` agreement; `swipe_debug_trace_count` "area di prova" could read as counting playgrounds.
- **ja** — `clipboard_url_handling_note` and `clipboard_sanitize_system_*` use 浄化 ("purification") for URL cleaning (file-wide) vs クリーンアップ/整理; `ime_default_prompt_toast` 既定に設定 vs デフォルト; `theme_preview_column` カラム vs a shape word.
- **ko** — `cmd_ole_desc` cantillation as 성조 기호 vs 영창/억양/칸틸레이션 기호. (GPT-6) `theme_color_latched_key/_label` 고정된 for a one-shot state.
- **lv** — `clipboard_clean_result#zero` and four other clipboard plurals: zero class as nominative plural vs the file's genitive convention. (GPT-6) `cmd_combining_cedille`/`_desc` still "cedilla" beside Sedijs (older rows, not in the sheets).
- **nl** — `dict_sort_match` "Match" loanword vs "Overeenkomst"; `dict_tab_user` "Gebr.-wdb." cryptic tab name. (GPT-6) `clipboard_move_to_top` "Naar boven" vs `clipboard_move_up` "Omhoog"; `swipe_debug_trace_count` "testomgeving" wording. "Vastgezette" for latched Shift is my choice.
- **pl** — `calibration_legend_long_range` Max vs Maks; `cmd_accent_grave` "Akcent słaby" vs gravis; `theme_section_hints` "podpowiedzi" (also the word for suggestions) vs "wskazówki"; `clipboard_swipe_delete_title` "przesunięciem" for the physical row swipe.
- **pt** — `theme_section_looks` "look" rendered visual/aparência/aspecto; `dict_tab_custom` Pessoais vs personalizadas.
- **ro** — `licenses_notices_header` (+5) "mențiuni" vs notă/note legale; `suggestion_prefer_when_swiping_decline` "Nu mai întreba" as an app-directed label in a formal file. (GPT-6) `theme_color_latched_key/_label` "fixată"; `theme_save_failed` wording (now "rămân deschise în editor").
- **ru** — (GPT-6) the six Church Slavonic mark names (Кавыка, Паерок, Покрытие, Дасия, Звательце, Взмет) were checked against Unicode by an AI, not by liturgical typography.
- **tr** — `theme_color_latched_key/_label` "Tek Seferlik Shift" (my choice for one-shot); `clipboard_swipe_delete_desc/_title` "giriş" vs "kayıt" for entry.
- **uk** — `theme_predefined_modern_midnight` "Північ" (midnight / north; also `swipe_direction_north`). (GPT-6) `short_swipe_direction_short_*` compact labels (the reviewer found Пн/Пд/Сх/Зх standard).
- **vi** — `common_copied_to_clipboard` and `extra_key_title_clipboard`: bộ nhớ tạm (58) vs bảng tạm (19) vs Android's own toast wording; `continuous_swipe_desc` "Chạm giữ nhanh"; `calibration_feedback_short/_tap/_long` "(%1$d%% phím)" terse. (GPT-6) `theme_preview_balloon` "Quả bóng"; "đoạn cắt" for clippings.
- **zh-rCN** — `cmd_combining_kavyka/payerok/pokrytie/vzmet`: ad-hoc transliterations while the `_desc` rows keep the Latin names; pick one convention. 临时启用的 Shift is my choice. (GPT-6) `theme_preview_balloon` 气球 (preference).
- **all** — (GPT-6) `ime_default_prompt_toast` labels vary by Android version/OEM; the reviewers matched AOSP (LineageOS lineage-22.2 Settings) wording in cs de es fa fil fr in it ja ko lv pl pt ro ru tr uk vi zh-rCN.

## 7. English-source problems (not changed; `res/values/strings.xml` is untouched)

- `backup_headless_dict_exported` "Dictionaries exported: %1$d custom + %2$d disabled": the
  arguments are word counts (`BackupRestoreActivity.performExportDictionaries`), so "Dictionaries"
  misleads; es/it/ro translations now say words.
- `cmd_timestamp_date_short_desc` "(MM/dd/yy)" and `_long_desc` "(Day, Month DD, YYYY)": the
  formats are hard-coded US patterns for every locale (`KeyValue.kt:849-850`), so the hint is
  accurate but the feature is unlocalised.
- `input_suggestion_bar_opacity_desc` says "Transparency of the suggestion bar" under an
  "Opacity" title; cs/hu descriptions mirror that inconsistency.
- `clipboard_bulk_already_present` "%1$d was/were already there." has no noun.
- `swipe_debug_session_intro` "Swipe Playground" vs `advanced_swipe_debug_log_title` "Swipe Debug
  Log": two names for one screen.
- (GPT-6) `gesture_enable_short_desc` "quick words (it, is, at…)" may be stale for the
  `short_gestures_enabled` switch; `clipboard_delete_selected_result` and `swipe_debug_trace_count`
  select the plural category by `%2$d` (the total) while `%1$d` is the count shown.
- `settings_faq_numbers_a` "Settings → Activities → …" was flagged as stale by two reviewers; it is
  not: `activities_section_title` is "📱 Activities".

## 8. Glossary, tooling and out-of-window observations (for a follow-up, not changed)

- Glossary gaps the reviewers hit: de theme→Theme and backspace→Rücktaste; vi theme (chủ đề vs
  giao diện); in "ketik geser"; hu register pattern misses `-álod/-álsz` forms (importálod slipped
  through); lv stem `vilk` misses the imperative "velciet/velkat" (6 correct strings flagged);
  cs stem `schránk` misses the declined "schránce"; tr `Locale.ROOT` lower-casing turns "KAYDIRMA"
  into "kaydirma" (dotless ı) so an ALL-CAPS Turkish string with I would mis-match; fil `swip`
  word-start stem flags bare-infixed forms (sinwipe) but the file's hyphenated affixes pass.
- Older strings outside the sheets that carry the same defects: pt-PT leaks in
  `advanced_ime_prompt_title/_desc`, `clipboard_media_enabled_*`, `clipboard_max_media_size_*`,
  `clipboard_exclude_password_managers_desc`, `privacy_on_device_learning_desc`,
  `swipe_engine_fallback_desc`; nl `input_capitalize_i_title`, `clipboard_max_item_size_desc`,
  `clipboard_pinned/todo_tab_desc`; pl gest+przesuwania pairs in `short_swipe_tap_hint`,
  `accessibility_swipe_complete_desc`, `advanced_swipe_debug_log_desc`, `gesture_enable_short_desc`;
  tr `key_descr_compose` "Oluştur" (Create), `key_descr_capslock`, `key_descr_delete_word`
  "sözcük", `clipboard_filter_private_only(_hint)` "gizli"; in/fa `clipboard_media_enabled_desc`
  (papan klip / کلیپ‌بورد); ko full-width parentheses in ~10 titles; zh-rCN half-width punctuation
  in `clipboard_exclude_password_managers_desc`, `input_show_exact_typed_desc`; lv
  `cmd_combining_cedille`; hu `short_swipe_tap_hint` "geszt".
- keepEnglish lists `import_preview_type_str`, but es/pt/ja translate it (Texto / テキスト); left.
- The sheet writer emits CRLF; `.gitattributes` `text=auto` normalises on commit (diffs are line-exact).

## 9. Verification

- Pure-Python stand-in for TranslationFormatParityTest, TranslationCoverageDriftTest,
  TranslationGlossaryTest, TranslationLengthTest and CommandCatalogLocalizationTest over all 21
  whole locale files: clean after every batch and at the end.
- Gradle: `runPureTests -PtestClass=TranslationFormatParityTest,TranslationCoverageDriftTest,TranslationGlossaryTest,TranslationLengthTest,customization.CommandCatalogLocalizationTest`
  failed at `compileDebugUnitTestKotlin` on the first attempt (2026-11-22 23:58) — foreign
  cross-talk: another session's red-phase tests (`RotationModifierCarryTest`,
  `HeldModifierCarryTest`, `LayoutSwitchAutocapTest`, `AutocapitalisationTest`: unresolved
  `RotationModifierCarry`, `heldModifiers`, `refresh` …), not the strings. Result of the re-run
  after that session's commit, the full `runPureTests` / `runMockTests` and CI: see the status
  appended below.

## 10. Commits

`b0b955426` (cs de es fr it nl pl pt), `ff8eb91a2` (hu ru uk), `f6d7aac7b` (in ro tr),
`6ba71d91b` (fa lv), `148f20a66` (ja ko), `a993ddfed` (vi zh-rCN), `1ee52c20f` (fil),
`50d538e5d` (reconciliation with the independent review). Each commit holds the filled sheets and
the imported `strings.xml` together; they are the record of the review.

— Fable 5.1


## Verification update — 2026-11-23 05:44 UTC, independent GPT-6 session

The earlier compile obstruction is resolved in the current shared checkout:

- Existing import/export unit suite: **14 passed**.
- Five requested Gradle translation classes: **24 passed**, `BUILD SUCCESSFUL` (6m 21s).
- Standard full `runPureTests runMockTests`: **3,986 pure + 1,896 mock passed**, zero
  failures, `BUILD SUCCESSFUL` (14m 47s). Optional geoFull replays were disabled.
- The full run began before reconciliation commit `50d538e5d`. A second complete pure
  run against the final resources has now passed **3,986 tests**, including all five
  translation classes, at HEAD `4b98b41f0`. That concurrent broad build continues with
  mock/APK tasks; its later results are not claimed here.
- Final import dry-run: **441 already imported, 0 would apply, 0 refused**.
  All source columns and rows were preserved in the reconciliation CSV changes:
  **41 cells / 54 resource texts**, of which **13 texts** are outside the scoped sheets.

Logs: `build/translation-independent-full-tests.log` and
`build/translation-independent-final-pure.log`. Results include the other session's
unrelated uncommitted keyboard work and describe the live shared tree. No push or CI run
was initiated by this session. Native-speaker review and the maintainer's M2 decision remain
open. The [independent audit](2026-11-23-translation-independent-review.md) records coverage,
reconciliation, native questions and the distinction between originating question counts
and later independent additions.

— GPT-6
