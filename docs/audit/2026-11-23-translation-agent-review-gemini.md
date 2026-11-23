# Independent Translation AI Review (Third Pass) — 2026-11-23

Reviewer: Gemini 4 Neon (Medium), coordinated with one fresh reviewer subagent per locale (3 batches of 7 locales).
This is an independent AI review of all 21 locales' 2026-11 review sheets (`docs/i18n/review/2026-11/<locale>/`) starting from `edf0a8605` — i.e., **after** the earlier AI reviews by Fable 5.1 (`2026-11-23-translation-agent-review.md`) and GPT-6 (`2026-11-23-translation-independent-review.md`, reconciled in `50d538e5d`).
No native speaker has read any of these strings: this is recorded strictly as an AI review, `memory/todo.md` Open now item 10 stays open, and M2 remains the maintainer's shipping decision.

## 1. Per-locale summary

Every row of every sheet (`1-translate.csv`: 636, `2-check.csv`: 346, `3-read-through.csv`: 34,419; **35,401 rows total**) was read in full against the English source, the current live translation (incorporating prior `[EDITED]` cells), `docs/i18n/glossary.json`, `docs/i18n/2026-09-29-register.md`, `docs/i18n/2026-09-29-terminology.md`, `docs/i18n/review-flags.json`, and `docs/i18n/2026-10-08-coverage-audit.md` (Filipino Taglish policy). Only real defects (meaning/omission, false friends, grammar/agreement/particles, RTL bidi direction, formal-register slips, typos) were edited; correct text was not restyled.

| Locale | 1 translate (rows / new) | 2 check (rows / new) | 3 read-through (rows / new) | Total new fixes | Open native questions |
|---|---|---|---|---|---|
| `cs` | 23 / 0 | 14 / 0 | 1,745 / 1 | 1 | 5 |
| `de` | 30 / 0 | 7 / 0 | 1,613 / 2 | 2 | 4 |
| `es` | 22 / 0 | 9 / 0 | 1,687 / 0 | 0 | 6 |
| `fa` | 7 / 0 | 27 / 0 | 1,632 / 2 | 2 | 5 |
| `fil` | 126 / 0 | 25 / 0 | 1,535 / 3 | 3 | 6 |
| `fr` | 32 / 0 | 21 / 0 | 1,676 / 1 | 1 | 4 |
| `hu` | 28 / 0 | 20 / 0 | 1,610 / 1 | 1 | 6 |
| `in` | 48 / 0 | 21 / 0 | 1,523 / 2 | 2 | 6 |
| `it` | 28 / 0 | 28 / 0 | 1,660 / 1 | 1 | 6 |
| `ja` | 15 / 0 | 9 / 0 | 1,561 / 1 | 1 | 4 |
| `ko` | 7 / 0 | 7 / 0 | 1,570 / 4 | 4 | 5 |
| `lv` | 19 / 0 | 14 / 0 | 1,684 / 3 | 3 | 5 |
| `nl` | 46 / 0 | 9 / 1 | 1,610 / 1 | 2 | 5 |
| `pl` | 30 / 0 | 20 / 1 | 1,738 / 1 | 2 | 5 |
| `pt` | 42 / 0 | 10 / 0 | 1,666 / 0 | 0 | 7 |
| `ro` | 50 / 0 | 14 / 0 | 1,688 / 5 | 5 | 4 |
| `ru` | 16 / 0 | 18 / 0 | 1,746 / 2 | 2 | 6 |
| `tr` | 23 / 0 | 13 / 0 | 1,617 / 5 | 5 | 7 |
| `uk` | 16 / 0 | 21 / 0 | 1,747 / 2 | 2 | 6 |
| `vi` | 22 / 0 | 17 / 0 | 1,546 / 0 | 0 | 6 |
| `zh-rCN` | 6 / 0 | 22 / 0 | 1,565 / 0 | 0 | 7 |
| **Total** | **636 / 0** | **346 / 2** | **34,419 / 37** | **39** | — |

All 39 edits were written only to `your_fix` in the corresponding CSVs and imported into `res/values-<locale>/strings.xml` via `scripts/export_translation_review.py --import` with 0 refusals and 0 CSV–XML mismatches across all 21 locales. `res/values/strings.xml` was not touched.

## 2. All 39 new fixes applied

- **`cs` (1)**
  - `appearance_look_scene_overlay_desc` (sheet 3): `polární záři` (accusative) → `polární září` (instrumental; *pohybovat* governs instrumental).
- **`de` (2)**
  - `autocorrect_short_deletion_scope_desc` (sheet 3): `ist sie` → `sind sie` (*Kosten* is plurale tantum).
  - `clipboard_delete_selected_result#one` (sheet 3): `ausgewählten Clip` → `ausgewähltem Clip` (strong dative singular after *von*).
- **`fa` (2)**
  - `import_preview_layout_geometry_change` (sheet 3): `%2$s → %3$s` → `⁧⁨%2$s⁩ ← ⁨%3$s⁩⁩` (RLI + FSI isolates with RTL arrow, matching sibling `import_preview_layout_theme_change` and §6 bidi rules).
  - `clipboard_filter_size_hint` (sheet 3): `فایل‌های هم‌رسانی‌شده` ("shared via share sheet") → `فایل‌های مشترک` ("shared between clippings"), and `این فضا آزاد نمی‌شود` ("this space will not be freed") → `این مقدار، فضای آزادشده نیست` ("not space reclaimed").
- **`fil` (3)**
  - `cmd_forward_delete_word` (sheet 3): `I-delete Pasulong` → `I-delete ang Salita Pasulong` (restored omitted "Word").
  - `extra_key_title_forward_delete_word` (sheet 3): `I-delete Pasulong` → `I-delete ang Salita Pasulong` (same omission).
  - `input_delete_word_url_pairs_desc` (sheet 3): `Magtanggal ng isang salita` → `"I-delete ang Salita"` (matched actual Filipino command/key label and resolved `ng ... ng ...` clash).
- **`fr` (1)**
  - `clipboard_clean_message` (sheet 3): `rejoint les lignes` (false friend of English "join") → `réunit les lignes`.
- **`hu` (1)**
  - `cmd_replace_text_menu` (sheet 3): `Csere-javaslatok (szerkesztő)` → `Cserejavaslatok (szerkesztő)` (solid two-member Hungarian compound).
- **`in` (2)**
  - `autocorrect_channel_inflection_cost_desc` (sheet 3): `harus untuk mengganti` (ungrammatical calque) → `agar dapat mengganti`.
  - `autocorrect_dropped_double_margin_desc` (sheet 3): `boleh dibanding yang lain` (broken syntax) → `dibanding kata lain agar`.
- **`it` (1)**
  - `clipboard_delete_selected_result#one` (sheet 3): `Eliminati %1$d di %2$d ritaglio selezionato.` → `Eliminato %1$d di %2$d ritaglio selezionato.` (singular agreement).
- **`ja` (1)**
  - `theme_look_river_rocks_desc` (sheet 3): `使い込まれた川石` ("well-used tools") → `風化した川石` ("weathered river stones").
- **`ko` (4)**
  - `layout_theme_own_desc` (sheet 3): `%1$s를` → `%1$s을(를)` (theme names may end in a consonant).
  - `swipe_debug_clear_traces_message` (sheet 3): `“%1$s”는` / `“%2$s”는` → `“%1$s”은(는)` / `“%2$s”은(는)` (`플레이그라운드만` ends in final consonant `ㄴ`).
  - `settings_faq_select_delete_a` (sheet 3): `짧은 스와이프한 다음` → `짧게 스와이프한 다음` (adverbial form before verb).
  - `settings_swipe_data_points#other` (sheet 3): `%1$d점` ("N score points") → `좌표 %1$d개` ("N coordinate points").
- **`lv` (3)**
  - `short_swipe_reset_all_done#zero` (sheet 3): `Noņemti %1$d piešķīrumu` → `Noņemts %1$d piešķīrumu` (singular participle with genitive plural in `#zero`).
  - `short_swipe_reset_all_message#zero` (sheet 3): `Tiks noņemti %1$d pielāgotu piešķīrumu…` → `Tiks noņemts %1$d pielāgotu piešķīrumu…` (same agreement).
  - `dict_legacy_freq_raised#zero` (sheet 3): `%1$d vārdu paaugstināti līdz %2$d` → `%1$d vārdu paaugstināts līdz %2$d` (same agreement).
- **`nl` (2)**
  - `command_palette_template_help` (sheet 2): `Vouw {clipboard}, … uit.` ("unfold" an accordion) → `Vervangt {clipboard}, …` (template placeholder expansion).
  - `theme_look_river_rocks_desc` (sheet 3): `versleten rivierstenen` ("worn out / threadbare") → `verweerde rivierstenen` ("weathered").
- **`pl` (2)**
  - `cmd_replace_text_menu_desc` (sheet 2): `inne kandydaty` → `innych kandydatów` (masculine personal accusative plural).
  - `cmd_accent_trema_desc` (sheet 3): `diarezy` → `dierezy` (typo).
- **`ro` (5)**
  - `theme_look_aurora_desc` (sheet 3): `desenate doar de contur` → `desenate doar prin contur` (preposition for method/means).
  - `clipboard_delete_selected_result#one` (sheet 3): `S-au șters %1$d din %2$d decupaj selectat.` → `S-a șters %1$d din %2$d decupaj selectat.` (singular agreement).
  - `clipboard_selection_exit` (sheet 3): `Ieși din selecție` (unambiguous 2sg informal imperative) → `Ieșire din selecție`.
  - `private_copy_unavailable` (sheet 3): `Copia privată indisponibilă` → `Copie privată indisponibilă` (indefinite form for verbless toast).
  - `suggestion_prefer_when_swiping_decline` (sheet 3): `Nu mai întreba` (2sg informal negative imperative) → `Nu mai întrebați` (formal `dvs.`).
- **`ru` (2)**
  - `backup_result_full_clipboard_imported` (sheet 3): `• Импортировано из буфера обмена: %1$d` ("imported *from* the clipboard") → `• Импортировано записей буфера обмена: %1$d`.
  - `backup_result_full_clipboard_skipped` (sheet 3): `• Пропущено из буфера обмена: %1$d (дубликаты)` → `• Пропущено записей буфера обмена: %1$d (дубликаты)`.
- **`tr` (5)**
  - `clipboard_url_handling_note` (sheet 3): `bir klibi` ("music video / video clip") → `bir kırpmayı`.
  - `layout_manager_export_not_written` (sheet 3): `sabitlenmiş bir klibi` → `sabitlenmiş bir kırpmayı`.
  - `input_swipe_distance_threshold_desc` (sheet 3): `etkinlik` ("activity") → `olay` ("event"), and restored omitted clause `; böylece hızlı kaydırmaları kolay kalır.` ("so their flicks stay easy").
  - `settings_swipe_data_summary#one` & `#other` (sheet 3): `%3$d kayıttan %2$d gösteriliyor` → `%3$d kayıttan %2$d tanesi gösteriliyor` (grammatical partitive after ablative).
- **`uk` (2)**
  - `backup_result_full_clipboard_imported` (sheet 3): `• Імпортовано з буфера обміну: %1$d` ("imported *from* the clipboard") → `• Імпортовано записів буфера обміну: %1$d`.
  - `backup_result_full_clipboard_skipped` (sheet 3): `• Пропущено з буфера обміну: %1$d (дубликати)` → `• Пропущено записів буфера обміну: %1$d (дублікати)`.

## 3. Notable open questions for native speakers (beyond prior reports)

Recurring across all 21 locales (already tracked in `review-flags.json` and prior reports): `ime_default_prompt_toast` Android system settings path wording (especially Android 14+ `Keyboard` vs `Languages & input`) and `swipe_debug_session_intro` (localize vs keep `Swipe Playground`).

Additional per-locale questions surfaced or confirmed in this pass:

- **`cs`**: `settings_version_unknown` (`neznámé` neuter vs `neznámá` feminine agreeing with *verze*); `theme_section_hints` (`Gestové nápovědy`) vs `appearance_look_show_hints_title` (`nápovědy gest`).
- **`de`**: `settings_swipe_data_summary#one/#other` (`eindeutiges Wort` calque for "unique/distinct word" vs `verschiedene Wörter`); overall `Sie` vs AOSP `du` register.
- **`es`**: `cmd_combining_tilde` (`Tilde combinante`) vs `cmd_accent_tilde` (`Virgulilla`); `cmd_autofill` (`Autorrelleno`) vs `extra_key_title_autofill` (`Autocompletar`); `cmd_doc_end_desc`/`_home_desc` `(Ctrl+End)`/`(Ctrl+Home)` vs `(Ctrl+Fin)`/`(Ctrl+Inicio)`; `es-ES` regional target vs `Qué tan rápido` in `appearance_look_motion_speed_desc`.
- **`fa`**: `short_swipe_direction_short_*` abbreviations (`ق`/`ش`); `clipboard_selection_clear` (`پاک کردن انتخاب` vs `لغو انتخاب`); Latin digits `0`/`1` in `autocorrect_channel_distance_cost_desc`/`_indel_cost_desc` vs Persian digits elsewhere.
- **`fil`**: `short_swipe_direction_short_*` (`S` = Silangan/East colliding with English South in Taglish); the 5 flagged English-identical titles; `theme_section_bar` (`Ayos` vs `Layout`); `mungkahi` vs `suhestiyon`.
- **`fr`**: `pack` vs `paquet` across `multilang_pack_*`, `voice_*`, `gif_*`, `pack_error_*`; `command_palette_template_help` imperative `Développez` vs 3sg descriptive `Développe`.
- **`hu`**: `extra_key_accent_slash`/`_bar` (`Ferde vonal`/`Vízszintes vonal`); `backup_result_full_import_failed_title` hyphenation; `clipboard_clean_message`/`clipboard_delete_selected_message` wording with tab names; `theme_color_latched_key`/`_label` (`Rögzített`).
- **`in`**: `short_swipe_direction_short_*` compass abbreviations; Latin `Home`/`End`/`Page Up`/`Page Down` keycap names; `berkerlip` vs `berkelip`.
- **`it`**: `input vocale` ×18 (already in `review-flags.json`); `suggerimenti` for key-corner gesture hints vs `indicazioni`; `input_delete_word_url_pairs_desc` (`Elimina una parola` vs `Elimina parola`); `settings_sensitivity_preset_custom` masculine `Personalizzato` beside feminine `Alta`/`Media`/`Bassa`.
- **`ja`**: `clipboard_url_handling_note` (`浄化` vs `クリーンアップ`/`サニタイズ`); `theme_look_river_rocks_desc` (`風化した川石` vs `角の取れた川石`).
- **`ko`**: `theme_section_bar` (`추천 표시줄 배치`); `clipboard_url_handling_note` (`최선 노력` for "best-effort"); `대문자 잠금`/`스크롤 잠금` vs Latin `Caps Lock`/`Scroll Lock`.
- **`lv`**: `Sedijs` vs feminine `Sediļa` for cedilla; `kā tablete` for pill shape; `%1$d pt` vs `pkt.`/`p.` in `settings_swipe_data_points`.
- **`nl`**: `Gebaarstemming` for "Gesture Tuning" (matches existing `settings_section_gesture_tuning`, but literally means "gesture mood/voting"); `cmd_end` (`Einde`) beside `cmd_home` (`Home`); `lay-out` vs `indeling`.
- **`pl`**: `clipboard_swipe_delete_title`/`_desc` (`przesunięcie` for row swipe); `To-do` vs `Zadania` across clipboard UI/FAQ; `Kopia zapasowa & przywracanie` (`&` vs `i`).
- **`pt`**: English typo examples (`tomorr`/`timor`/`tomorrow`, `teh`/`helo`); `theme_dynamic_color` (`fundo` vs `papel de parede`); `backup_result_full_clipboard_skipped` (`Área de transferência ignorada` + `(duplicados)`); `import_preview_custom_themes_warning#one` (`pelo %1$d tema`); LanguageTool style flags (`app`, `depois de`).
- **`ro`**: `Refă` on `cmd_redo`/`autocorrect_dropped_double_title` (standard UI label vs formal `Refaceți`/noun `Refacere`); `clipboard-ul` vs DOOM3 `clipboardul`.
- **`ru`**: 6 Church Slavonic combining marks; `список дел` vs `Задачи` for Todos; `Сверху большие (7-8-9 вверху)` pleonasm; `литеральных фигурных скобок` calque.
- **`tr`**: `key_descr_dead_key` (`Ölü tuş`); Latin `Breve`/`Caron`/`Ogonek`; `%%%1$d'i` fixed suffix vowel harmony in `calibration_feedback_*`; `Kısa Kelimeye Silme…`; `theme_builtin_jungle_name` (`Cengel`).
- **`uk`**: 3-letter compass abbreviations (`ПнС`/`ПнЗ`/`ПдС`/`ПдЗ`); `макет` vs `розкладка`; `син`/`шин` vs `сін`/`шін`; 6 Church Slavonic combining marks.
- **`vi`**: `bộ nhớ tạm` vs `bảng tạm`; `> Max`/`Min - Max`/`< Min` in `calibration_legend_*`; `Chuỗi` beside `Bool`/`Int`/`Float`; `tuỳ chỉnh` vs `tùy chỉnh` tone placement.
- **`zh-rCN`**: `你` vs AOSP `您`; `纠错` vs `纠正`; `撤消` vs `撤销`; Church Slavonic transliterations vs Latin descriptions; `抑扬符`/`扬抑符` native confirmation.

## 4. English-source & out-of-sheet notes (not edited)

- **English source** (`privacy_swipe_data_exported`): `"Exported %1$d swipe entries to %2$s"` passes the export format (not a destination path) into `%2$s` in `SettingsSwipeDataHandlers.kt`.
- **English source** (`backup_headless_dict_imported` and `clipboard_delete_selected_result`): single plural resource with two independent counts (`%1$d` and `%2$d`), which prevents inflected languages from agreeing with both counts simultaneously.
- **Out-of-sheet strings noticed during full-file checks** (left untouched as outside the 2026-11 review sheets):
  - `fa` `clipboard_media_enabled_desc` still uses `کلیپ‌بورد` instead of glossary `بریده‌دان`.
  - `ro` `autocorrect_min_word_length_desc` uses informal 2sg negative imperative `Nu corija` (and archaic *a corija* instead of *a corecta*).

## 5. Verification

- CSV–XML sync across all 21 locales: **0 mismatches**; `--import` **0 refused**.
- Five Gradle translation pure test classes (`GRADLE_GUARD_XMX=1536m GRADLE_GUARD_METASPACE=384m`):
  - `TranslationFormatParityTest`: **OK (6 tests)**
  - `TranslationCoverageDriftTest`: **OK (6 tests)**
  - `TranslationGlossaryTest`: **OK (5 tests)**
  - `TranslationLengthTest`: **OK (2 tests)**
  - `customization.CommandCatalogLocalizationTest`: **OK (5 tests)**
  - Total: **24/24 passed**.
- Full `runPureTests` and `runMockTests` (`GRADLE_GUARD_XMX=1536m GRADLE_GUARD_METASPACE=384m`):
  - `runPureTests`: **OK (3,986 tests)**
  - `runMockTests`: **OK (1,896 tests)**
  - `BUILD SUCCESSFUL in 13m 50s`, zero failures.

— Gemini 4 Neon (Medium)
