# Blind back-translation review — 2026-09-29

**Reviewer:** model (Claude "fable" subagents, 4 batches). Not a native speaker. Agreement
here does not certify fluency, and each open note still needs a native speaker.

**Method:** each reviewer received only the target-language strings, under neutral IDs (S1…),
with no English and no key names. It was asked for a literal English back-translation plus
notes on ambiguity, register and in-locale inconsistency. The author then compared the
result with the English source. Priority was privacy and destructive-action meaning:
recording vs reading, what gets deleted, what remains, and reversibility. No external
services were used.

**Scope:** strings added or changed since 2026-09-25 (13 keys), plus
`privacy_forget_learned_body` and the 3 Filipino former English copies. Strings are as of
commit 535a2a28, and fixes landed in 4491e3fc. Per-string reviewer output is summarised
below. Verdicts: **OK** means the back-translation preserves the English claim. **FIXED**
means a clear error was corrected. **NOTE** means a wording concern that was left for
native review.

## Cross-locale findings

1. **Selection history (privacy, FIXED in 22 locales):** the learning-off prompt said a bare
   "selection history", which reviewers read as *text-selection* history in about 15 locales.
   The English source had the same ambiguity. All locales now say suggestion-selection history.
2. **Recording claims:** every locale says "nothing new will be recorded" (ko said "there are
   no new records" and was FIXED). No locale turned "recorded" into "read" or "shown".
3. **Deletion scope:** all 21 locales list the same four categories as English (pairs/triples,
   usage counts, suggestion-selection history, swipe corrections). All keep "dictionaries and
   settings are not affected", and all say the action cannot be undone. A few (fr, ro, in,
   vi) attach "learned" to *words* rather than to the pairs. That shifts scope slightly without
   changing what gets deleted — NOTE.
4. **Undo vs cancel:** fr, it, ro, ru, uk use the platform verb that means both undo and cancel.
   This is standard Android usage and was left as is.
5. **Swipe verb ambiguity (NOTE, native review):** it *scorrimento* and tr *kaydırma* also mean
   "scroll". fa *کشیدن* and lv *vilkšana* mean "drag". pt *deslize* also means "slip-up".
   Each is the dominant established term in its file, so it was not churned.
6. **Style:** quote styles are mixed within locales (straight vs curly), and "Don't ask" has
   no "again" in de/nl/it/es/pt/hu. These were left as is.

## Per-string verdicts

### cs

| Key | Verdict | Finding |
|---|---|---|
| `suggestion_prefer_when_swiping` | OK | - |
| `suggestion_prefer_when_swiping_decline` | OK | - |
| `suggestion_prefer_when_swiping_added` | OK | - |
| `suggestion_added_to_dictionary` | OK | - |
| `suggestion_tap_again_to_undo` | OK | - |
| `suggestion_removed_from_dictionary` | OK | - |
| `provenance_origin_typo_correction` | OK | - |
| `provenance_note_typo_correction_of` | OK | - |
| `input_next_word_desc` | NOTE | "spojení" could be "slovní spojení"; mixed tense |
| `help_third_party_data` | OK | - |
| `privacy_learned_source_swipe_corrections` | NOTE | "Opravy tahů" (stroke corrections) relies on context; consistent with the file's psaní tahem |
| `privacy_learned_source_selections` | OK | - |
| `privacy_learned_forget_body` | OK | - |
| `privacy_forget_learned_body` | FIXED | bare "selection history" read as text selection; now names suggestion-selection history (English source changed too) |

### de

| Key | Verdict | Finding |
|---|---|---|
| `suggestion_prefer_when_swiping` | OK | - |
| `suggestion_prefer_when_swiping_decline` | NOTE | no "again"; unclear whether this time or always (English "Don't ask" is equally terse) |
| `suggestion_prefer_when_swiping_added` | OK | - |
| `suggestion_added_to_dictionary` | OK | - |
| `suggestion_tap_again_to_undo` | OK | - |
| `suggestion_removed_from_dictionary` | OK | - |
| `provenance_origin_typo_correction` | OK | - |
| `provenance_note_typo_correction_of` | OK | - |
| `input_next_word_desc` | OK | - |
| `help_third_party_data` | OK | - |
| `privacy_learned_source_swipe_corrections` | OK | - |
| `privacy_learned_source_selections` | NOTE | "Vorschlagsauswahl" can read as the offered assortment of suggestions |
| `privacy_learned_forget_body` | OK | - |
| `privacy_forget_learned_body` | FIXED | bare "selection history" read as text selection; now names suggestion-selection history (English source changed too) |

### es

| Key | Verdict | Finding |
|---|---|---|
| `suggestion_prefer_when_swiping` | OK | - |
| `suggestion_prefer_when_swiping_decline` | NOTE | no "again"; unclear whether this time or always (English "Don't ask" is equally terse) |
| `suggestion_prefer_when_swiping_added` | OK | - |
| `suggestion_added_to_dictionary` | OK | - |
| `suggestion_tap_again_to_undo` | OK | - |
| `suggestion_removed_from_dictionary` | OK | - |
| `provenance_origin_typo_correction` | NOTE | "errata" is a print term; "error de escritura" is more usual |
| `provenance_note_typo_correction_of` | NOTE | "de lo escrito" is stilted |
| `input_next_word_desc` | OK | - |
| `help_third_party_data` | OK | - |
| `privacy_learned_source_swipe_corrections` | OK | - |
| `privacy_learned_source_selections` | OK | - |
| `privacy_learned_forget_body` | OK | - |
| `privacy_forget_learned_body` | FIXED | bare "selection history" read as text selection; now names suggestion-selection history (English source changed too) |

### fa

| Key | Verdict | Finding |
|---|---|---|
| `suggestion_prefer_when_swiping` | NOTE | کشیدن = drag/pull; it is the established fa swipe term (80 strings). Native review should confirm it reads as swipe typing |
| `suggestion_prefer_when_swiping_decline` | NOTE | informal singular imperative beside polite plural elsewhere |
| `suggestion_prefer_when_swiping_added` | OK | - |
| `suggestion_added_to_dictionary` | OK | - |
| `suggestion_tap_again_to_undo` | OK | - |
| `suggestion_removed_from_dictionary` | OK | - |
| `provenance_origin_typo_correction` | OK | - |
| `provenance_note_typo_correction_of` | OK | - |
| `input_next_word_desc` | OK | - |
| `help_third_party_data` | OK | - |
| `privacy_learned_source_swipe_corrections` | FIXED | "اصلاحات کشیدن" read as "drag reforms" -> تصحیح‌های کشیدن |
| `privacy_learned_source_selections` | NOTE | "انتخاب‌های پیشنهاد" can read as "choices offered by the suggestion"; clearer wording needs native review |
| `privacy_learned_forget_body` | FIXED | اصلاح‌ها -> تصحیح‌ها, same term as the source label |
| `privacy_forget_learned_body` | FIXED | "اصلاحات" also means political "reforms" -> تصحیح‌ها (matches typo-correction strings) |

### fil

| Key | Verdict | Finding |
|---|---|---|
| `suggestion_prefer_when_swiping` | NOTE | completed aspect "nag-swipe" (swiped) rather than habitual; understandable |
| `suggestion_prefer_when_swiping_decline` | OK | - |
| `suggestion_prefer_when_swiping_added` | OK | - |
| `suggestion_added_to_dictionary` | OK | - |
| `suggestion_tap_again_to_undo` | OK | - |
| `suggestion_removed_from_dictionary` | OK | - |
| `provenance_origin_typo_correction` | OK | - |
| `provenance_note_typo_correction_of` | NOTE | curly quotes vs straight quotes elsewhere in fil (style only) |
| `input_next_word_desc` | OK | - |
| `help_third_party_data` | OK | - |
| `privacy_learned_source_swipe_corrections` | FIXED | pagtatama -> pagwawasto (the word used in every other correction string) |
| `privacy_learned_source_selections` | OK | - |
| `privacy_learned_forget_body` | OK | - |
| `privacy_forget_learned_body` | FIXED | bare "selection history" read as text selection; now names suggestion-selection history (English source changed too) |
| `import_preview_source_screen` | FIXED | was an English copy; back-translates to "Source screen" - matches |
| `provenance_aggression` | FIXED | was an English copy; back-translates to "Aggressiveness" - matches |
| `input_learning_aggression_title` | FIXED | was an English copy; back-translates to "Aggressiveness of Learning" - matches |

### fr

| Key | Verdict | Finding |
|---|---|---|
| `suggestion_prefer_when_swiping` | OK | - |
| `suggestion_prefer_when_swiping_decline` | OK | - |
| `suggestion_prefer_when_swiping_added` | NOTE | "le glissé" as a noun is unusual; the file-wide term (72 strings). préférer/privilégier vary |
| `suggestion_added_to_dictionary` | OK | - |
| `suggestion_tap_again_to_undo` | OK | - |
| `suggestion_removed_from_dictionary` | OK | - |
| `provenance_origin_typo_correction` | OK | - |
| `provenance_note_typo_correction_of` | OK | - |
| `input_next_word_desc` | OK | - |
| `help_third_party_data` | OK | - |
| `privacy_learned_source_swipe_corrections` | OK | - |
| `privacy_learned_source_selections` | OK | - |
| `privacy_learned_forget_body` | OK | - |
| `privacy_forget_learned_body` | NOTE | "habitudes d'expressions" is an odd rendering of phrase patterns (also FIXED: suggestion-selection history) |

### hu

| Key | Verdict | Finding |
|---|---|---|
| `suggestion_prefer_when_swiping` | FIXED | my length-shortening had said "should it be first" (stronger than prefer) -> "Előnyben részesíti ... ?" (prefer) |
| `suggestion_prefer_when_swiping_decline` | NOTE | no "again"; acceptable as a button label |
| `suggestion_prefer_when_swiping_added` | FIXED | "az első" (is first) -> "élvez előnyt" (is preferred) |
| `suggestion_added_to_dictionary` | OK | - |
| `suggestion_tap_again_to_undo` | OK | - |
| `suggestion_removed_from_dictionary` | OK | - |
| `provenance_origin_typo_correction` | OK | - |
| `provenance_note_typo_correction_of` | FIXED | colon made it unclear whether %1$s was the typed text or the result -> "A begépelt „%1$s” javítása" |
| `input_next_word_desc` | OK | - |
| `help_third_party_data` | OK | - |
| `privacy_learned_source_swipe_corrections` | OK | - |
| `privacy_learned_source_selections` | OK | - |
| `privacy_learned_forget_body` | OK | - |
| `privacy_forget_learned_body` | FIXED | was "kijelölési előzmények" (text-selection history) before this round; now javaslatválasztási, reviewer confirmed |

### in

| Key | Verdict | Finding |
|---|---|---|
| `suggestion_prefer_when_swiping` | OK | - |
| `suggestion_prefer_when_swiping_decline` | OK | - |
| `suggestion_prefer_when_swiping_added` | OK | - |
| `suggestion_added_to_dictionary` | OK | - |
| `suggestion_tap_again_to_undo` | NOTE | "mengurungkan" vs "dibatalkan" in the forget prompt for undo |
| `suggestion_removed_from_dictionary` | OK | - |
| `provenance_origin_typo_correction` | OK | - |
| `provenance_note_typo_correction_of` | OK | - |
| `input_next_word_desc` | OK | - |
| `help_third_party_data` | OK | - |
| `privacy_learned_source_swipe_corrections` | OK | - |
| `privacy_learned_source_selections` | OK | - |
| `privacy_learned_forget_body` | OK | - |
| `privacy_forget_learned_body` | FIXED | "mati" (dead/off, colloquial) and "direkam" (audio/video recording) -> nonaktif / dicatat |

### it

| Key | Verdict | Finding |
|---|---|---|
| `suggestion_prefer_when_swiping` | NOTE | "scorrimento" is also the usual Android word for "scrolling"; it is the established it swipe term (81 strings) - native review |
| `suggestion_prefer_when_swiping_decline` | NOTE | no "again"; unclear whether this time or always (English "Don't ask" is equally terse) |
| `suggestion_prefer_when_swiping_added` | NOTE | "scorrimento" is also the usual Android word for "scrolling"; it is the established it swipe term (81 strings) - native review |
| `suggestion_added_to_dictionary` | OK | - |
| `suggestion_tap_again_to_undo` | OK | - |
| `suggestion_removed_from_dictionary` | OK | - |
| `provenance_origin_typo_correction` | NOTE | "refuso" is a printing term (misprint); "errore di battitura" is more usual |
| `provenance_note_typo_correction_of` | OK | - |
| `input_next_word_desc` | OK | - |
| `help_third_party_data` | OK | - |
| `privacy_learned_source_swipe_corrections` | NOTE | "scorrimento" is also the usual Android word for "scrolling"; it is the established it swipe term (81 strings) - native review |
| `privacy_learned_source_selections` | OK | - |
| `privacy_learned_forget_body` | OK | - |
| `privacy_forget_learned_body` | FIXED | bare "selection history" read as text selection; now names suggestion-selection history (English source changed too) |

### ja

| Key | Verdict | Finding |
|---|---|---|
| `suggestion_prefer_when_swiping` | OK | - |
| `suggestion_prefer_when_swiping_decline` | NOTE | "今後表示しない" = "don't show from now on" rather than "don't ask"; the effect is the same (the offer is not shown again). Left as is |
| `suggestion_prefer_when_swiping_added` | OK | - |
| `suggestion_added_to_dictionary` | OK | - |
| `suggestion_tap_again_to_undo` | OK | - |
| `suggestion_removed_from_dictionary` | OK | - |
| `provenance_origin_typo_correction` | OK | - |
| `provenance_note_typo_correction_of` | NOTE | adds 候補 ("correction candidate"); the entry is a bar candidate, so it is accurate. Left |
| `input_next_word_desc` | OK | - |
| `help_third_party_data` | OK | - |
| `privacy_learned_source_swipe_corrections` | OK | - |
| `privacy_learned_source_selections` | OK | - |
| `privacy_learned_forget_body` | OK | - |
| `privacy_forget_learned_body` | FIXED | bare "selection history" read as text selection; now names suggestion-selection history (English source changed too) |

### ko

| Key | Verdict | Finding |
|---|---|---|
| `suggestion_prefer_when_swiping` | OK | - |
| `suggestion_prefer_when_swiping_decline` | OK | - |
| `suggestion_prefer_when_swiping_added` | OK | - |
| `suggestion_added_to_dictionary` | OK | - |
| `suggestion_tap_again_to_undo` | OK | - |
| `suggestion_removed_from_dictionary` | OK | - |
| `provenance_origin_typo_correction` | OK | - |
| `provenance_note_typo_correction_of` | NOTE | adds 후보 (candidate), same as ja; accurate for a bar entry. Left |
| `input_next_word_desc` | OK | - |
| `help_third_party_data` | OK | - |
| `privacy_learned_source_swipe_corrections` | OK | - |
| `privacy_learned_source_selections` | OK | - |
| `privacy_learned_forget_body` | NOTE | "3연쇄" (3-chain) is n-gram jargon for "triples" |
| `privacy_forget_learned_body` | FIXED | "새로운 기록은 없습니다" = "there are no new records" (states existence) -> "nothing new is recorded" |

### lv

| Key | Verdict | Finding |
|---|---|---|
| `suggestion_prefer_when_swiping` | NOTE | "vilkšana" = dragging/pulling; established lv swipe term (82 strings), native review |
| `suggestion_prefer_when_swiping_decline` | OK | - |
| `suggestion_prefer_when_swiping_added` | OK | - |
| `suggestion_added_to_dictionary` | OK | - |
| `suggestion_tap_again_to_undo` | OK | - |
| `suggestion_removed_from_dictionary` | OK | - |
| `provenance_origin_typo_correction` | OK | - |
| `provenance_note_typo_correction_of` | OK | - |
| `input_next_word_desc` | OK | - |
| `help_third_party_data` | NOTE | "atsauce" = reference/citation rather than attribution |
| `privacy_learned_source_swipe_corrections` | OK | - |
| `privacy_learned_source_selections` | OK | - |
| `privacy_learned_forget_body` | OK | - |
| `privacy_forget_learned_body` | FIXED | "frāžu paradumus" = phrase habits, "atlase" = text selection -> modeļi / ieteikumu izvēle |

### nl

| Key | Verdict | Finding |
|---|---|---|
| `suggestion_prefer_when_swiping` | OK | - |
| `suggestion_prefer_when_swiping_decline` | NOTE | no "again"; unclear whether this time or always (English "Don't ask" is equally terse) |
| `suggestion_prefer_when_swiping_added` | OK | - |
| `suggestion_added_to_dictionary` | OK | - |
| `suggestion_tap_again_to_undo` | OK | - |
| `suggestion_removed_from_dictionary` | OK | - |
| `provenance_origin_typo_correction` | OK | - |
| `provenance_note_typo_correction_of` | FIXED | telegraphic "van getypt" -> "van het getypte" |
| `input_next_word_desc` | OK | - |
| `help_third_party_data` | OK | - |
| `privacy_learned_source_swipe_corrections` | OK | - |
| `privacy_learned_source_selections` | OK | - |
| `privacy_learned_forget_body` | OK | - |
| `privacy_forget_learned_body` | FIXED | bare "selection history" read as text selection; now names suggestion-selection history (English source changed too) |

### pl

| Key | Verdict | Finding |
|---|---|---|
| `suggestion_prefer_when_swiping` | OK | - |
| `suggestion_prefer_when_swiping_decline` | OK | - |
| `suggestion_prefer_when_swiping_added` | OK | - |
| `suggestion_added_to_dictionary` | OK | - |
| `suggestion_tap_again_to_undo` | OK | - |
| `suggestion_removed_from_dictionary` | OK | - |
| `provenance_origin_typo_correction` | OK | - |
| `provenance_note_typo_correction_of` | OK | - |
| `input_next_word_desc` | OK | - |
| `help_third_party_data` | NOTE | "Dane zewnętrzne" = external data rather than third-party data |
| `privacy_learned_source_swipe_corrections` | OK | - |
| `privacy_learned_source_selections` | OK | - |
| `privacy_learned_forget_body` | OK | - |
| `privacy_forget_learned_body` | FIXED | bare "selection history" read as text selection; now names suggestion-selection history (English source changed too) |

### pt

| Key | Verdict | Finding |
|---|---|---|
| `suggestion_prefer_when_swiping` | OK | - |
| `suggestion_prefer_when_swiping_decline` | NOTE | no "again"; unclear whether this time or always (English "Don't ask" is equally terse) |
| `suggestion_prefer_when_swiping_added` | OK | - |
| `suggestion_added_to_dictionary` | OK | - |
| `suggestion_tap_again_to_undo` | OK | - |
| `suggestion_removed_from_dictionary` | OK | - |
| `provenance_origin_typo_correction` | OK | - |
| `provenance_note_typo_correction_of` | OK | - |
| `input_next_word_desc` | OK | - |
| `help_third_party_data` | OK | - |
| `privacy_learned_source_swipe_corrections` | NOTE | the noun "deslize" also means "slip-up"; established pt swipe term (85 strings) |
| `privacy_learned_source_selections` | OK | - |
| `privacy_learned_forget_body` | OK | - |
| `privacy_forget_learned_body` | FIXED | bare "selection history" read as text selection; now names suggestion-selection history (English source changed too) |

### ro

| Key | Verdict | Finding |
|---|---|---|
| `suggestion_prefer_when_swiping` | OK | - |
| `suggestion_prefer_when_swiping_decline` | FIXED | informal "întreba" beside polite register -> "Nu mai întrebați" |
| `suggestion_prefer_when_swiping_added` | OK | - |
| `suggestion_added_to_dictionary` | OK | - |
| `suggestion_tap_again_to_undo` | NOTE | "anula" = cancel or undo (standard Android ro) |
| `suggestion_removed_from_dictionary` | OK | - |
| `provenance_origin_typo_correction` | FIXED | telegraphic noun stack -> "Corectarea greșelii de tastare" |
| `provenance_note_typo_correction_of` | OK | - |
| `input_next_word_desc` | OK | - |
| `help_third_party_data` | OK | - |
| `privacy_learned_source_swipe_corrections` | OK | - |
| `privacy_learned_source_selections` | OK | - |
| `privacy_learned_forget_body` | OK | - |
| `privacy_forget_learned_body` | FIXED | bare "selection history" read as text selection; now names suggestion-selection history (English source changed too) |

### ru

| Key | Verdict | Finding |
|---|---|---|
| `suggestion_prefer_when_swiping` | OK | - |
| `suggestion_prefer_when_swiping_decline` | OK | - |
| `suggestion_prefer_when_swiping_added` | OK | - |
| `suggestion_added_to_dictionary` | OK | - |
| `suggestion_tap_again_to_undo` | OK | - |
| `suggestion_removed_from_dictionary` | OK | - |
| `provenance_origin_typo_correction` | OK | - |
| `provenance_note_typo_correction_of` | OK | - |
| `input_next_word_desc` | OK | - |
| `help_third_party_data` | OK | - |
| `privacy_learned_source_swipe_corrections` | OK | - |
| `privacy_learned_source_selections` | OK | - |
| `privacy_learned_forget_body` | OK | - |
| `privacy_forget_learned_body` | FIXED | bare "selection history" read as text selection; now names suggestion-selection history (English source changed too) |

### tr

| Key | Verdict | Finding |
|---|---|---|
| `suggestion_prefer_when_swiping` | NOTE | "kaydırma" also means scrolling in Turkish Android; established tr swipe term (89 strings) |
| `suggestion_prefer_when_swiping_decline` | NOTE | informal "Sorma" beside formal register; common Android button idiom |
| `suggestion_prefer_when_swiping_added` | OK | - |
| `suggestion_added_to_dictionary` | OK | - |
| `suggestion_tap_again_to_undo` | OK | - |
| `suggestion_removed_from_dictionary` | OK | - |
| `provenance_origin_typo_correction` | NOTE | "yazım hatası" can mean spelling error rather than typing slip |
| `provenance_note_typo_correction_of` | OK | - |
| `input_next_word_desc` | OK | - |
| `help_third_party_data` | OK | - |
| `privacy_learned_source_swipe_corrections` | NOTE | "kaydırma" also means scrolling in Turkish Android; established tr swipe term (89 strings) |
| `privacy_learned_source_selections` | OK | - |
| `privacy_learned_forget_body` | OK | - |
| `privacy_forget_learned_body` | FIXED | bare "selection history" read as text selection; now names suggestion-selection history (English source changed too) |

### uk

| Key | Verdict | Finding |
|---|---|---|
| `suggestion_prefer_when_swiping` | OK | - |
| `suggestion_prefer_when_swiping_decline` | OK | - |
| `suggestion_prefer_when_swiping_added` | OK | - |
| `suggestion_added_to_dictionary` | OK | - |
| `suggestion_tap_again_to_undo` | OK | - |
| `suggestion_removed_from_dictionary` | OK | - |
| `provenance_origin_typo_correction` | OK | - |
| `provenance_note_typo_correction_of` | OK | - |
| `input_next_word_desc` | OK | - |
| `help_third_party_data` | OK | - |
| `privacy_learned_source_swipe_corrections` | NOTE | "Виправлення" is the same in singular and plural |
| `privacy_learned_source_selections` | OK | - |
| `privacy_learned_forget_body` | OK | - |
| `privacy_forget_learned_body` | FIXED | bare "selection history" read as text selection; now names suggestion-selection history (English source changed too) |

### vi

| Key | Verdict | Finding |
|---|---|---|
| `suggestion_prefer_when_swiping` | OK | - |
| `suggestion_prefer_when_swiping_decline` | OK | - |
| `suggestion_prefer_when_swiping_added` | OK | - |
| `suggestion_added_to_dictionary` | OK | - |
| `suggestion_tap_again_to_undo` | OK | - |
| `suggestion_removed_from_dictionary` | OK | - |
| `provenance_origin_typo_correction` | OK | - |
| `provenance_note_typo_correction_of` | OK | - |
| `input_next_word_desc` | OK | - |
| `help_third_party_data` | OK | - |
| `privacy_learned_source_swipe_corrections` | NOTE | "sửa lỗi vuốt" reads as fixing swipe errors; matches the forget dialog |
| `privacy_learned_source_selections` | OK | - |
| `privacy_learned_forget_body` | OK | - |
| `privacy_forget_learned_body` | FIXED | bare selection history plus "mức dùng từ" (usage level) -> "lịch sử chọn gợi ý", "số lần dùng từ" (matches the Forget dialog) |

### zh-rCN

| Key | Verdict | Finding |
|---|---|---|
| `suggestion_prefer_when_swiping` | OK | - |
| `suggestion_prefer_when_swiping_decline` | OK | - |
| `suggestion_prefer_when_swiping_added` | OK | - |
| `suggestion_added_to_dictionary` | OK | - |
| `suggestion_tap_again_to_undo` | FIXED | variant 撤消 -> 撤销 (7:1 in the file, and used by the forget prompt) |
| `suggestion_removed_from_dictionary` | OK | - |
| `provenance_origin_typo_correction` | OK | - |
| `provenance_note_typo_correction_of` | OK | - |
| `input_next_word_desc` | OK | - |
| `help_third_party_data` | OK | - |
| `privacy_learned_source_swipe_corrections` | NOTE | bare 滑动 could read as "scroll"; 滑动 is the file-wide swipe term |
| `privacy_learned_source_selections` | OK | - |
| `privacy_learned_forget_body` | OK | - |
| `privacy_forget_learned_body` | FIXED | bare "selection history" read as text selection; now names suggestion-selection history (English source changed too) |
