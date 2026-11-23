# Independent translation AI review — 2026-11-23

Reviewer: GPT-6. This is an AI review, not a native-speaker review or M2 sign-off.
Primary report: [translation agent review](2026-11-23-translation-agent-review.md).

## Shared-checkout boundary

The requested prompt was read from the supplied scratchpad path. `git pull --ff-only`
reported already up to date. HEAD initially contained other-session AI review commits
`b0b955426` (cs/de/es/fr/it/nl/pl/pt) and `ff8eb91a2` (hu/ru/uk), ahead of origin/main.
Romanian and Turkish sheets already had uncommitted corrections; Persian, Latvian and
Indonesian edits appeared during this review. Those changes belong to another active
session. This session performs an independent read-only review of sheets/resources and
records findings here; it does not import, stage, commit or push the other session's work.
A text question about review ownership was sent to the maintainer; no answer was assumed.

The other session's commits are evidence of their recorded fixes, not evidence that this
session independently read every unchanged row. Coverage below describes only this review.

## Findings and reconciliation

The table preserves the findings as recorded during review. **The actionable corrections
have since been reconciled by the active reviewer in `50d538e5d`** (41 cells in 15 locales,
54 resource texts total). The remaining native questions are separate below. No additional
translation patch from this session is needed. The final import dry-run and complete pure-suite recheck passed.

| Priority | Locale | Key | Finding / action |
|---|---|---|---|
| High | lv, ru, uk | `clipboard_private_copy_other_apps_desc` | The final disclosure, “Enabling adds an app entry other apps can see,” is absent. Restore the visibility disclosure in each translation. Verified against all 21 source resources. This key is absent from the current ru/uk scoped sheets: record a separate correction or refresh sheets while preserving reviewer cells; do not append rows under the fixed-column-only rule. |
| Meaning | cs, es, fa, fil, hu, ja, ko, pl, ru, uk | `appearance_look_scene_overlay_desc` | Text describes redrawing the keyboard instead of the keyboard redrawing the aurora. Preserve who draws what. fr/it/pt already express the English correctly; nl/vi/zh omit an object but retain the keyboard as the drawing agent. |
| Meaning | cs, hu | `theme_shape_key_border_opacity`, `theme_shape_key_fill_opacity`, `appearance_key_border_opacity_desc`, `appearance_key_border_opacity_title`, `appearance_key_opacity_desc`, `appearance_key_opacity_title` | Opacity is translated as Průhlednost (cs) / átlátszóság (hu), meaning transparency and inverting the alpha control. Candidates: Neprůhlednost (cs), fedettség / átlátszatlanság (hu); confirm native terminology. |
| Meaning | fr | `gesture_max_distance_desc` | At-or-below boundary becomes only below; retain the inclusive boundary in the whole-cell correction. |
| Missing meaning | fr | `swipe_context_rescoring_desc` | Explicit no-effect-until-learned condition is reduced to a short local-learning requirement. Restore the disabled/unlearned behavior. |
| Grammar | es | `import_preview_custom_themes_warning#one` | `por el %1$d tema` produces the unnatural `el 1 tema`; candidate omit el in the whole cell. |
| Meaning | fil | `theme_preview_corner` | `Pag-ikot ng Sulok` means corner rotation, not corner rounding. Candidate: `Pagbilog ng Sulok`. |
| Meaning | fr, nl, tr, zh-rCN | `theme_color_latched_key`, `theme_color_latched_label` | Locked wording conflates temporary, one-shot Shift activation with locked Shift/Caps Lock. Preserve that distinction; native terminology remains open. |
| Missing meaning | fil | `dict_word_too_long_for_swipe_msg#one`, `#other` | Final sentence “Swiping will not offer it” remains absent. The same omissions found in ja/ko/vi/zh-rCN have since been restored by the active reviewer, as recorded below. |

The reconciliation also corrects older `appearance_keyboard_opacity_*`,
`appearance_activated_key_opacity_*`, and `input_suggestion_bar_opacity_title` strings in
cs/hu, plus cs `theme_color_opacity`. `input_suggestion_bar_opacity_desc` correctly follows
the English Transparency wording and remains unchanged: key names alone do not determine
the displayed concept. These are adjacent source findings, not additional scoped-sheet rows.

Source context: `theme/LookPopups.kt` explicitly defines BALLOON as the wide-head-and-neck
preview shape. Literal translations of Balloon are naming questions, not established defects.
No change to the English source is proposed for these findings.

## Concurrent fixes verified during this review

These are other-session changes, not fixes authored or imported by this session:

- `148f20a66` (ja/ko): restored the missing swipe-unavailability warning and changed Japanese
  `advanced_custom_terminal_title` from device-package wording to カスタムターミナルパッケージ.
- `a993ddfed` (vi/zh-rCN): restored the missing swipe-unavailability warning; the eight Chinese
  glossary findings listed in the independent review now use the agreed 纠正/候选/滑动 terms.
- Other observed review commits: `b0b955426`, `ff8eb91a2`, `f6d7aac7b`, `6ba71d91b`.
  Their filled-cell records and resources remain owned by the active reviewer.
- `1ee52c20f` completed Filipino; `50d538e5d` explicitly reconciles this independent review.
  I compared every CSV column between that commit and its parent: only reviewer cells changed,
  with zero added/removed rows or resources. There are exactly **41 changed cells / 54 resource
  texts**; **13 resource texts** are outside scoped sheets. The direct changes comprise the
  older opacity strings above and the two ru/uk private-copy disclosure strings.

## Native-speaker questions

These are the independent questions still needing native judgement, not automatic corrections.
The primary report §6 also contains the locale reviewers’ questions; its table’s 45 is their
original question tally, not a deduplicated count including every later independent lead.

- All 21 locales: `ime_default_prompt_toast` actual Android settings labels vary by version/OEM;
  verify the platform wording. `swipe_debug_session_intro`: confirm localized screen naming.
- de: `intent_preset_dial_phone` is `Telefon wählen` for ACTION_DIAL (`IntentDefinition.kt`);
  is `Telefonnummer wählen` clearer? `autocorrect_short_deletion_scope_desc`: check agreement
  between plural `diese Kosten` and singular `ist sie`. `clipboard_delete_selected_result#one`:
  verify dative agreement (`ausgewähltem Clip`). The call site selects the plural category
  by selected total `%2$d`, not the number actually deleted `%1$d`; see the source notes below.
- fil: `licenses_intro`: does `libreng software` communicate
  software freedom rather than only zero price? Consider `malayang software`. Cardinal hints
  `short_swipe_direction_*`: confirm compact native abbreviations. `diksiyonaryo`/`diksyunaryo`
  may be spelling variants; avoid glossary-driven restyling without a native decision.
- in: `short_swipe_direction_*` compact direction abbreviations; Latin Home/End/Page Up/Page
  Down key names may be suitable borrowing. Confirm UI naturalness rather than translating
  technical keycaps automatically.
- ko: `theme_color_latched_key`/`_label`: does 고정된 convey temporary activation clearly?
- fa: `short_swipe_direction_short_*` abbreviations: especially ق for east, and ش shared
  by north/east. A native reviewer should choose compact labels consistently with the FAQ.
- hu: `extra_key_accent_bar` / `extra_key_accent_slash`: confirm specialist diacritic terms.
- uk: `short_swipe_direction_short_{e,n,ne,nw,s,se,sw,w}`: confirm compact compass labels.
- ru: `cmd_combining_kavyka`, `cmd_combining_payerok`, `cmd_combining_pokrytie`,
  `cmd_combining_slavonic_dasia`, `cmd_combining_slavonic_psili`, `cmd_combining_vzmet`:
  Church Slavonic mark names require liturgical typography expertise.
- fil: retain the existing five native terminology questions for
  `autocorrect_typo_forgiveness_title`, `gesture_speed_smoothing_title`,
  `input_backspace_only_repeat_title`, `gesture_selection_delete_header`,
  `settings_auto_capitalization_title`.
- lv: verify zero-category noun/participle agreement in
  `clipboard_added_to_pinned_result#zero`, `clipboard_added_to_todos_result#zero`,
  `clipboard_bulk_media_skipped#zero`, `clipboard_clean_result#zero`,
  `clipboard_merge_result#zero`, `short_swipe_reset_all_done#zero`,
  `dict_legacy_freq_raised#zero`, `short_swipe_reset_all_message#zero`. `cmd_combining_cedille`/`_desc` use cedilla while sibling
  accent labels use Sedijs; confirm the intended native term.
- it: `clipboard_delete_selected_result#one` verb/count agreement;
  `swipe_debug_trace_count#one/#many/#other` area di prova could count playgrounds rather than
  traces; `input_numpad_layout_high_first`/`_low_first` are elliptical but understandable.
- nl: `clipboard_move_to_top` Naar boven versus `clipboard_move_up` Omhoog may not clearly
  distinguish top from one-step movement; `swipe_debug_trace_count#one/#other` testomgeving
  wording may count environments rather than traces.
- cs: native judgement for fractional-category morphology in
  `short_swipe_reset_all_message#many` and `import_preview_custom_themes_warning#many`;
  ordinary integer UI counts do not exercise these categories.
- ro: `theme_color_latched_key`/`_label` fixată may need explicit temporary-state wording.
  `input_space_before_opening_punctuation_desc` uses pune as a descriptive third-person
  summary; do not automatically convert it to a formal imperative.
  `clipboard_delete_selected_result#one`: check verb agreement with 0 or 1 deleted out of
  one selected clipping.
- tr: `multilang_pack_notice_missing`, `multilang_pack_notice_title`,
  `multilang_pack_view_notice`: confirm bildirim conveys legal NOTICE rather than a
  notification. ro/tr clipboard items decupaj / kırpma are established but need native
  terminology confirmation.
- vi: `theme_preview_balloon` Quả bóng is a native shape-naming question. Clipboard clips
  repeatedly use đoạn cắt; consider whether mục/đoạn sao chép better describes saved copies.
- zh-rCN: `cmd_accent_caron`, `cmd_accent_circonflexe`, `cmd_combining_caron`,
  `cmd_combining_circonflexe`, `extra_key_accent_caron`, `extra_key_accent_circumflex`:
  retain the existing native question about 抑扬符 versus 扬抑符.
- ko, zh-rCN: `theme_preview_balloon` 풍선/气球 names an explicitly balloon-shaped preview;
  treat alternative wording as preference, not a required fix.

## Source questions and rejected leads

- `backup_headless_dict_exported`: English says Dictionaries exported, but
  `BackupRestoreActivity.performExportDictionaries` passes `customWordsCount` and
  `disabledWordsCount`. Clarify word counts in English before translating a different unit.
- `gesture_enable_short_desc`: quick-word recognition wording appears stale for the
  `short_gestures_enabled` switch in `GestureTuningSection`; check intended feature meaning.
- `privacy_swipe_data_exported`: `%2$s` is the JSON/NDJSON format, not a destination path
  (`SettingsSwipeDataHandlers.toastSwipeDataExported`). German als is correct; no claim that
  a pre-existing Dutch als→naar edit fixed a destination error is justified.
- `clipboard_delete_selected_result`: `ClipboardManager` selects the plural category by
  total selected (`%2$d`); `%1$d` is the number deleted. Any correction must consider zero
  successful deletions out of one selection, not infer the selector from argument order.
- `swipe_debug_trace_count`: `SwipeDebugActivity` selects by total traces (`%2$d`) and passes
  playground trace count as `%1$d`. English “%1$d playground” is terse and can be clarified
  without implying that multiple playground screens are being counted.
- Persian `io_error_no_backup_password` remains correct: پشتیبانی is پشتیبان plus the
  indefinite ی after هیچ, rather than an established support/backup mistranslation.
- The ja legal NOTICE labels, Korean Caron/Ogonek spelling pairs, Vietnamese theme/system
  clipboard terminology and lv layout-theme wording were already corrected by the locale
  pass; verified again against current resources. Vietnamese Giao diện in `theme_scenes_desc`
  correctly names the Appearance screen, not the Theme selector.
- German `cmd_case_cycle_desc` klein → Titel → GROSS can itself demonstrate title case;
  this is not sufficient evidence of a mistranslation.

The English resource file remains untouched.

## Coverage and validation

All **35,401 rows** were read in this independent AI pass: **636 translate**,
**346 check**, **34,419 read-through** across all 21 locales. Reviewers read every target
text; bilingual/source comparison focused on flagged, changed and questionable entries.
This is not a claim of a full bilingual comparison of every unchanged row, or native fluency.

The table records sheet sizes and filled cells observed in the shared tree, including
other-session work. **This session translated/imported zero rows in every locale.**
The final column counts rows still lacking native-speaker sign-off, not uncertain AI guesses.
Specific unresolved questions are listed above. The final filled-cell count is **18 translations + 423 fixes = 441 cells**; a fresh import
dry-run reports **0 would apply, 441 already imported, 0 refused**. Thirteen additional
resource corrections are outside these sheets and are recorded above.

| Locale | Translate rows | Check rows | Read-through rows | Filled translate / fixes (other session) | Still without native sign-off |
|---|---:|---:|---:|---:|---:|
| cs | 23 | 14 | 1,745 | 2 / 18 | 1,782 |
| de | 30 | 7 | 1,613 | 0 / 15 | 1,650 |
| es | 22 | 9 | 1,687 | 1 / 17 | 1,718 |
| fa | 7 | 27 | 1,632 | 1 / 19 | 1,666 |
| fil | 126 | 25 | 1,535 | 4 / 16 | 1,686 |
| fr | 32 | 21 | 1,676 | 0 / 28 | 1,729 |
| hu | 28 | 20 | 1,610 | 1 / 21 | 1,658 |
| in | 48 | 21 | 1,523 | 1 / 18 | 1,592 |
| it | 28 | 28 | 1,660 | 0 / 16 | 1,716 |
| ja | 15 | 9 | 1,561 | 0 / 22 | 1,585 |
| ko | 7 | 7 | 1,570 | 3 / 17 | 1,584 |
| lv | 19 | 14 | 1,684 | 3 / 26 | 1,717 |
| nl | 46 | 9 | 1,610 | 0 / 34 | 1,665 |
| pl | 30 | 20 | 1,738 | 0 / 19 | 1,788 |
| pt | 42 | 10 | 1,666 | 0 / 24 | 1,718 |
| ro | 50 | 14 | 1,688 | 0 / 30 | 1,752 |
| ru | 16 | 18 | 1,746 | 0 / 13 | 1,780 |
| tr | 23 | 13 | 1,617 | 0 / 14 | 1,653 |
| uk | 16 | 21 | 1,747 | 0 / 14 | 1,784 |
| vi | 22 | 17 | 1,546 | 0 / 17 | 1,585 |
| zh-rCN | 6 | 22 | 1,565 | 2 / 25 | 1,593 |

<!-- # TODO: Native-speaker questions and the maintainer M2 decision remain open. -->

The existing Python import/export suite passed:
`python3 -m unittest scripts.test_export_translation_review` — **14 tests, zero failures**.

All 21 locale imports were dry-run on the inspected live sheets: exit 0 for every locale,
zero refused cells. This checks import mechanics, not translation quality or complete import
application. No translation file was written by this session. The five requested translation
suites were started through `scripts/gradle-guard.sh` with 1536m heap / 384m metaspace behind
another session's build. All five suites passed: **24 tests, zero failures**,
`BUILD SUCCESSFUL` (6m 21s). Kotlin app/test compilation passed with existing warnings.

The full `runPureTests runMockTests` batch passed through the same guard: **3,986 pure +
1,896 mock tests, zero failures; BUILD SUCCESSFUL in 14m 47s**. Standard optional geoFull
corpus replays were disabled. The run began before `50d538e5d`; the concurrent validation
session is now running a complete pure suite against the final resources. I cancelled only
my duplicate queued five-class check (before it acquired the guard) to avoid duplicate work.
Post-reconciliation pure-suite result: **3,986 tests passed**, including all five required
translation classes (`build.gradle` pure runner entries verified), observed 2026-11-23 05:44 UTC
at HEAD `4b98b41f0` with resource corrections from `50d538e5d`. Snapshot:
`build/translation-independent-final-pure.log`. The concurrent broad build has moved on
to its mock suite and APK tasks; those additional results are not claimed here.
The completed independent full-run log is `build/translation-independent-full-tests.log`. All results describe the
live shared checkout, including the other session's imports and unrelated uncommitted code;
they are not a claim that a clean final translation commit has been tested.
No push or CI run was initiated by this session.

Native review remains open in every locale; the shipping decision remains the maintainer's.
