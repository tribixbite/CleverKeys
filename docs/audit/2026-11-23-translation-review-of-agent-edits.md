# Review of the two AI agents' translation edits (2026-11-23)

Maintainer, 2026-11-23: "review the output of the 2 other agents who committed translations"
(Fable 5.1 and Gemini 4 Neon; GPT-6 committed reports only). An independent reviewer subagent
(Claude Opus 5.5, a model other than the two reviewed) read every changed cell, read-only; the
session that asked for it verified its confirmed items against the files and applied the five
below. The rest of this document is the reviewer's report, with one correction marked [session].

## Applied (session, 2026-11-23)

| locale / key | was | now | why |
|---|---|---|---|
| in `autocorrect_dropped_double_margin_desc` | Seberapa lebih jarang … agar tetap menang | Seberapa jauh lebih jarang … boleh dibanding kata lain dan tetap menang | the English is a tolerance ("may be … and still win"); Gemini's "agar" (so that) made being rarer the way to win (§4 item 1) |
| in `autocorrect_channel_inflection_cost_desc` | Seberapa jauh lebih baik koreksi agar dapat … | Seberapa jauh koreksi harus lebih baik agar dapat … yang berakhiran | "must" restored (§4 item 5) |
| ro `suggestion_prefer_when_swiping_decline` | Nu mai întrebați | Nu mai întreba | Android's own Romanian for the same button (`never_ask_again`, `request_role_dont_ask_again`: "Nu mai întreba"; checked in the LineageOS 22.2 mirror); the file mixes forms of address (Selectează / Atingeți), so a native speaker should still confirm (§3) |
| de `gesture_max_distance_desc` | darunter = kurze Wischgeste | bis einschließlich dieses Werts = kurze Wischgeste | "at or below": the boundary value itself is a short swipe; the other 19 locales already say so (§4 item 3) |
| es `gesture_max_distance_desc` | por debajo = deslizamiento corto | igual o por debajo = deslizamiento corto | the same (§4 item 4) |

The in and ro cells went through their review sheets (`your_fix`, then
`scripts/export_translation_review.py --import`: applied 3, refused 0), so sheet and XML agree;
de/es `gesture_max_distance_desc` is not in the sheets and was edited in the XML. Items 6–10 and
§5 are left to a native speaker: they are idiom or consistency calls, not meaning errors.

Reviewer: Claude Opus 5.5, one AI model checking the work of other AI models. I am not a native
speaker of any of these 21 languages. This is not the native-speaker review, and it decides
nothing about M2. Where a call depends on idiom, I mark it as a native-speaker decision instead
of guessing. The review was read-only: no repository file was edited, no state-changing git
command was run, and Gradle was not invoked.

Revisions: base `cab9c9a40`, after Fable `50d538e5d`, after Gemini `afe394b3b` (HEAD).
The English source is `res/values/strings.xml` at HEAD.

## 1. Coverage

I read every changed cell, that is, every `<string>` and every plural item whose text differs
between base and HEAD. For each one I compared the English source, the base text, the text after
Fable and the text after Gemini. That is **490 unique cells**: 454 changed by Fable, 39 by
Gemini, and 3 changed by both. Where the meaning depended on context, I also read the sibling
strings, the screen titles, the glossary or the Kotlin call site (listed where they are used
below).

| locale | cells read | Fable | Gemini | | locale | cells read | Fable | Gemini |
|---|---:|---:|---:|---|---|---:|---:|---:|
| cs | 26 | 26 | 1 (overlap) | | lv | 32 | 29 | 3 |
| de | 17 | 15 | 2 | | nl | 36 | 34 | 2 |
| es | 18 | 18 | 0 | | pl | 20 | 19 | 2 (1 overlap) |
| fa | 22 | 20 | 2 | | pt | 24 | 24 | 0 |
| fil | 23 | 20 | 3 | | ro | 34 | 30 | 5 (1 overlap) |
| fr | 29 | 28 | 1 | | ru | 16 | 14 | 2 |
| hu | 28 | 27 | 1 | | tr | 19 | 14 | 5 |
| in | 21 | 19 | 2 | | uk | 17 | 15 | 2 |
| it | 17 | 16 | 1 | | vi | 17 | 17 | 0 |
| ja | 23 | 22 | 1 | | zh-rCN | 27 | 27 | 0 |
| ko | 24 | 20 | 4 | | **total** | **490** | **454** | **39** |

What I did not do:
- I did not read the ~35,000 unchanged sheet rows. The one exception is targeted sibling checks,
  and those turned up two leftover defects (§4).
- I did not re-run Gradle or the import dry-run.
- I did not check any string on a device.

My own checks were Python scripts over `git show` output. They were kept in the session's
scratchpad (`i18n/rev/extract.py`, `mech.py`, per-locale dumps), not in the repo.

## 2. Result in brief

- **Mechanical checks: clean.** The checks covered placeholders, escaping, literal `%` with
  `formatted="false"`, plural completeness and CSV↔XML sync.
- **Confirmed problems:**
  - 4 at medium or higher confidence:
    - 2 introduced by Gemini: in `autocorrect_dropped_double_margin_desc`, and the ro revert.
    - 2 left over: de and es `gesture_max_distance_desc`. [session] Not in the review sheets (its
      English predates their 2026-09-29 scope), so none of the three reviews was given it.
  - 6 minor or low-confidence items.
  - I found no case where Fable's change made a correct translation wrong in meaning. The worst
    Fable change is es `Breve invertida`, which was unnecessary.
- **Overlap cells:**
  - Gemini improved cs and pl.
  - Gemini reverted ro to the pre-review text, against AOSP's own Romanian wording.
- **GPT-6 reconciliation:**
  - All 54 texts from `50d538e5d` are still in the final tree.
  - The only one changed afterwards is cs `appearance_look_scene_overlay_desc`, and Gemini's
    change there is an improvement.
- **Report claims:**
  - The cell counts and tables in both reports match the commits exactly.
  - Fable's §3 contains two factual errors, and Gemini's report contains one small one (§6).
  - Neither report's Gradle results can be verified from evidence on disk.

## 3. The three cells both agents changed

| locale / key | base → Fable → Gemini | verdict |
|---|---|---|
| cs `appearance_look_scene_overlay_desc` | Fable fixed who redraws what ("místo toho, aby ji překreslovala klávesnice"). Gemini then changed `polární záři` (accusative) → `polární září` (instrumental) | **Gemini improved it.** *pohybovat* takes the instrumental (*pohybovat čím*). Fable's meaning fix is intact. Confidence high. |
| pl `cmd_replace_text_menu_desc` | Fable: `podpowiedzi`, `z pisania gestem` (glossary terms). Gemini: `inne kandydaty` → `innych kandydatów` | **Gemini improved it.** *kandydat* is masculine-personal, so its accusative plural is `kandydatów`. Fable's terms are kept. Confidence medium-high. |
| ro `suggestion_prefer_when_swiping_decline` | `Nu mai întrebați` → Fable `Nu mai întreba` → Gemini `Nu mai întrebați` (back to base) | **Gemini undid Fable's change.** This is a button label the user says to the app. AOSP Romanian uses the 2sg form for exactly this label: PermissionController `never_ask_again` and `request_role_dont_ask_again` are both "Nu mai întreba" (LineageOS lineage-22.2). The ro file is formal, but that convention covers instructions to the user, not words the user says to the app. Proposed: restore `Nu mai întreba`. Confidence medium; a native speaker should confirm. |

## 4. Confirmed problems

"Introduced by" names the agent whose edit produced the current text. "Residual" means none of
the three reviewers changed it.

| # | locale / key | current text | what is wrong | proposed | conf. | introduced by |
|---|---|---|---|---|---|---|
| 1 | in `autocorrect_dropped_double_margin_desc` | Seberapa lebih jarang kata berhuruf ganda dibanding kata lain agar tetap menang. … | The English is a tolerance: how much less common the word *may* be and still win. Gemini removed `boleh` (may) and added `agar` (in order to), so being rarer now reads as the way to win. `Seberapa lebih` also lacks `jauh`. The base, though clumsy, kept the tolerance reading. | Seberapa jauh lebih jarang kata berhuruf ganda boleh dibanding kata lain dan tetap menang. Nilai lebih besar lebih sering memulihkan huruf ganda. | medium-high | Gemini |
| 2 | ro `suggestion_prefer_when_swiping_decline` | Nu mai întrebați | See §3. Diverges from the AOSP ro label for the same action. | Nu mai întreba | medium (native confirm) | Gemini (revert) |
| 3 | de `gesture_max_distance_desc` | … (% der Tastendiagonale): darunter = kurze Wischgeste, … | This is the same defect GPT-6 found in fr: "at or below" became "below", so the inclusive boundary is lost. [session] Not in the sheets; see §2. | … (% der Tastendiagonale): bis einschließlich dieses Werts = kurze Wischgeste, darüber = Wischeingabewort. … | medium | residual |
| 4 | es `gesture_max_distance_desc` | … (% de la diagonal de tecla): por debajo = deslizamiento corto, … | The same inclusive-boundary loss. | … : igual o por debajo = deslizamiento corto, más allá = palabra por deslizamiento. … | medium | residual |
| 5 | in `autocorrect_channel_inflection_cost_desc` | Seberapa jauh lebih baik koreksi agar dapat mengganti kata dikenal berakhiran. … | Gemini fixed the `harus untuk` calque by dropping `harus` (must). The requirement is now only implied by `agar`. The sentence is readable but lost its modal. | Seberapa jauh koreksi harus lebih baik agar dapat mengganti kata dikenal yang berakhiran. … | low (native) | Gemini |
| 6 | es `cmd_combining_inverted_breve` / `_desc` | Breve invertida / Añadir breve invertida (◌̑) | Fable changed this from masculine. Spanish Wikipedia titles the mark "El breve invertido". Both genders are attested, so this "fixed" something that was not clearly broken. | Breve invertido / Añadir breve invertido (◌̑), or leave it | low | Fable |
| 7 | nl `command_palette_template_help` | Vervangt {clipboard}, {selection}, {uuid} en één {cursor}. … | `Vervangt` ("replaces") has no "with what", and `{cursor}` is a position marker, not a value. The base `Vouw … uit` was a calque. Gemini's text is understandable but incomplete. | Vult {clipboard}, {selection}, {uuid} en één {cursor} in. … | low (native) | Gemini |
| 8 | tr `input_swipe_distance_threshold_desc` | … sınırlar; böylece hızlı kaydırmaları kolay kalır. | The restored clause is right in meaning. However, `kaydırmaları` with no possessor reads at first as an accusative object, and `kalır` cannot take one. | … sınırlar; böylece bu tuşlarda hızlı kaydırmalar kolay kalır. | low (native) | Gemini |
| 9 | zh-rCN `layout_language_dialog_desc`, `layout_language_not_installed` | …自动纠正… | Fable followed the glossary stem (更正 → 纠正). However, the feature's own name in this file is 自动纠错 (section title `settings_section_autocorrection`, `autocorrect_enable_title`, `autocorrect_settings_title`). The file was already mixed (7 vs 8), so this is consistency, not meaning. | 自动纠错 in both | low | Fable |
| 10 | nl `privacy_swipe_data_exported#one/#other` | … geëxporteerd naar %2$s | Fable changed `als` → `naar`. `%2$s` is the format, "JSON"/"NDJSON" (`SettingsSwipeDataHandlers.kt:16-19`). Both wordings are idiomatic, so the change was unnecessary but harmless. Listed only because GPT-6 had already said no fix was justified here. | no change needed | — | Fable |

## 5. Native-speaker decisions (leads, not defects)

These are judgement calls where my evidence is mixed. Several are already in the agents' own
question lists; I add what I checked.

- **de `clipboard_delete_selected_result#one`**
  - Gemini changed `von %2$d ausgewählten Clip` → `ausgewähltem Clip`.
  - If the digit "1" is read as *einem*, the weak `-en` follows. Bare-numeral usage often
    writes `-em`.
  - Neither form is clearly wrong, so the change did not clearly improve it.
- **lv cedilla**
  - Fable's `Sedijs` (5 cells) is attested in Tēzaurs ("sedijs — diakritiska zīme zem burta c").
  - Latvian Wikipedia titles the mark `Sediļa`. Gemini also raised this.
  - Pick one; both are defensible.
- **lv `tēma`, vi `chủ đề` (theme)**
  - Fable aligned both with the app's screen titles (`Tēmu pārvaldnieks`,
    `Trình quản lý chủ đề`), and both files are now internally consistent.
  - A native speaker should still confirm against the platform's own terms (I believe Android
    lv uses *motīvs* and vi uses *giao diện*). The app could equally move the titles.
- **nl `theme_color_latched_*` "Vastgezette Shift-toets"**
  - The same file uses `vastgezette` for pinned clipboard items (`cmd_paste_pinned_*_desc`).
  - *Vastgezet* ("fixed in place") may still read as locked. Fable flagged this itself.
- **fil `theme_bar_emphasis_centre` "Pinadilim"** ("darkened")
  - On a light bar, "dimmed" text becomes lighter, so "darkened" may be wrong there.
  - `Pinalamlam` ("made faint") may fit better.
- **ja `告知` for the legal NOTICE** (4 cells)
  - It avoids 通知 (notification), but it is not the usual term for a licence notice.
  - 著作権表示 or 表示 are candidates.
- **it `cmd_combining_slavonic_dasia/_psili`, made feminine**
  - Fable's stated reason, "as for the Greek breathings", has nothing to follow: the it file has
    no Greek-breathing strings.
- **cs, in, tr `autocorrect_short_deletion_scope_title`**
  - The renderings mean "deletion cost onto a short word" (`na krátké slovo`, `ke Kata Pendek`,
    `Kısa Kelimeye`).
  - That matches what the setting does (tpo → to), but the phrasing is awkward.
- **es `clipboard_merge_copied` `listo` / it `pronto`**
  - Both are masculine, agreeing with recorte/ritaglio. This is correct if the antecedent is the
    clipping.

## 6. Factual claims in the reports, checked against the commits

**Fable report (`2026-11-23-translation-agent-review.md`)**
- §1 table, every locale and sheet: **matches** the filled sheet cells at `50d538e5d` exactly
  (18 / 152 / 271 = 441).
- 441 sheet cells + 13 direct edits = 454: **matches**.
- The 13 out-of-sheet texts are exactly the cs×6, hu×5, ru×1, uk×1 listed.
- The native-question column sums to 45.
- **§3 scene-overlay claim is inaccurate.** It says "10 locales … fixed in cs es fa fil hu ja ko pl
  ru uk (fr it pt nl vi zh-rCN were right)".
  - The text actually changed in **12** locales.
  - fr's base ("au lieu de redessiner le clavier") and it's base ("invece di ridisegnare la
    tastiera") had the same wrong agent. They were fixed in `b0b955426`, which is correct.
  - So the list is wrong, not the strings. GPT-6's statement that fr and it "already express
    the English correctly" was wrong for the same reason.
- **§3 `dict_word_too_long_for_swipe_msg` claim is overstated.** It says "every one of the 21
  translations had dropped the last English sentence … Restored in all 21".
  - Only **19** locales lacked it. ru and uk already had it at base ("Свайп не будет его
    предлагать", "Свайп не пропонуватиме його"); their edit was only the `other` plural form.
  - At HEAD, every plural item in all 21 locales carries the sentence.
- "0 refusals": not re-run (read-only). It is consistent with the CSV↔XML check: all 477 filled
  cells at HEAD equal the XML text, with 0 mismatches.
- Gradle (24 tests; 3,986 pure + 1,896 mock): not re-run. The only logs on disk are GPT-6's
  `build/translation-independent-*.log` (00:34–00:44).

**GPT-6 report**
- "41 cells / 54 resource texts / 13 outside sheets": **matches**.
- Its reconciliation table: all 8 items are present in the final tree.

**Gemini report (`2026-11-23-translation-agent-review-gemini.md`)**
- 39 cells in 17 locales, with the per-locale counts and the sheet-2/sheet-3 split: **matches**.
- 3 of the 39 overwrote cells Fable had filled, so the number of filled cells rose by 36, to 477.
- 0 CSV↔XML mismatches: **confirmed**.
- uk `backup_result_full_clipboard_skipped`: the report claims it also fixed `(дубликати)` →
  `(дублікати)`. The base already read `(дублікати)`, so this is minor and inaccurate.
- fa `import_preview_layout_geometry_change`:
  - The report cites "§6 bidi rules", and no such section exists.
  - The fix itself is right. It matches the convention set in `12a425b23` (2026-10-24, RLI+FSI
    and ←) and the two siblings `import_preview_value_change` and
    `import_preview_layout_theme_change`.
  - Separately, `docs/i18n/2026-09-29-hardcoded-ui-sweep.md` lines 99–103 still describe the
    superseded LRI + → rule. fa `provenance_personalization` still uses it. This is outside this
    review's scope; noted for a follow-up.
- The per-locale "Open native questions" column (e.g. cs 5) cannot be traced to its §3 lists
  (cs lists 2 items).
- "Every row of every sheet was read in full": not verifiable.
- Gradle claims (24/24; 3,986 + 1,896; "13m 50s"):
  - No log on disk matches them; the guard's `~/.cache/cleverkeys-build-last.log` has since been
    overwritten by an unrelated build.
  - All four Gemini commits were made within 31 s (09:41:20–09:41:51), so any test run happened
    on the uncommitted tree beforehand.
  - Unverified, not disproved.

## 7. Mechanical checks run for this review

- **Changed cells (490):** placeholder multiset against English, unescaped `'`/`"`, literal `%`
  without `formatted="false"`, length outliers, English leaks.
  - 0 defects.
  - 3 hits, all accepted:
    - fr `gesture_max_distance_desc` is a false positive (it has `formatted="false"`).
    - de `theme_cycle_message` "Theme: %1$s" uses the agreed de term.
    - es `cmd_insert` "Insert" is the label printed on Spanish PC keyboards.
- **Whole files (21 locales):** placeholder parity and unescaped apostrophes, 0 issues.
- **Plural categories:** complete for every plurals resource in cs/pl/ru/uk (one/few/many/other),
  lv (zero/one/other) and ro (one/few/other).
  - So the ru/uk/cs `other` = genitive-singular edits only affect fractional counts. They are
    correct, and they cannot leak into integer counts.
- **Selector arguments checked in code:**
  - `dict_word_too_long_for_swipe_msg` selects on `%3$d`, which is a constant
    (`WordListFragment.kt:388`).
  - `short_swipe_reset_all_*` selects on the count.
  - `settings_swipe_data_points` (`SettingsDialogs.kt:234`) and `tile_subtitle_toggle`
    (HIDE/SHOW, `KeyboardTileAction.kt:132`) were checked for meaning.
- **lv `#zero` items:** Gemini's three participle edits (`Noņemts`, `Tiks noņemts`,
  `paaugstināts` + genitive plural) follow the file's majority convention (`Importēts %1$d
  iestatījumu`, `Izslēgts %1$d privātu ierakstu`, …).
  - The clipboard `#zero` items still use the nominative plural, which is the open question both
    earlier reports raised.

## 8. Sources consulted outside the repo

- AOSP PermissionController ro strings: LineageOS mirror, `lineage-22.2`
  (`android_packages_modules_Permission`, `PermissionController/res/values-ro/strings.xml`).
- lv.wikipedia "Sediļa" and the tezaurs.lv entry "sedijs" (search excerpts).
- es.wikipedia "Breve invertido" (search excerpt).

These support items 2 and 6 and the lv question in §5. None of them substitutes for a native
reader.
