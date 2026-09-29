# Terminology decisions — 2026-09-29

Machine-derived (model reviewer, not a native speaker). Enforced by
`TranslationGlossaryTest` from `docs/i18n/glossary.json`; this page records why each
choice was made.

## Method

1. For each locale, count how many `strings.xml` entries use each candidate term for a concept
   (swipe, dictionary, suggestion, learned/learning, clipboard, layout, gesture; plus
   correction for zh/vi and "word" for tr). The dominant established term wins.
2. Where usage was genuinely split, use the term AOSP LatinIME uses in that language. The
   AOSP files came from the LineageOS mirror of `platform/packages/inputmethods/LatinIME`
   (Apache-2.0), `lineage-22.2` branch, `java/res/values-<lang>/strings.xml`, fetched for
   cs, fa, fr, hu, in, ja, pl, tl, tr, vi, zh-rCN. android.googlesource.com returned 503, and
   the aosp-mirror GitHub repository no longer exists.
3. Strings added since 2026-09-25 were changed to match. Older strings changed only when they
   were the outlier.

## Decisions (outliers fixed)

| Locale | Concept | Established term (count) | Outlier(s) fixed |
|---|---|---|---|
| cs | swipe | tah / psaní tahem (~62) | swipování ×3, tažení ×1 (2026-09-26 strings) |
| in | swipe | geser (79) | usap ×1 (forget dialog) |
| pl | swipe | pisanie gestem / gest* (AOSP: "pisanie gestami") | przesuwanie ×4 (2026-09-26 strings) |
| fr | swipe | glissé (72) | glissement ×9 (cursor-slide string kept) |
| hu | swipe | csúsztatás (80) | húzás ×3, incl. the section title |
| nl | swipe | veeg / vegen (80) | swipen/swipes ×5 |
| ja | suggestion | 候補 (26; AOSP also 候補) | サジェストバー ×1 |
| zh-rCN | swipe | 滑动 (86; AOSP says 滑行 but the file is not split) | 滑行 ×1 |
| zh-rCN | correction | 纠正 (11; AOSP says 更正) | 更正 ×1 |
| zh-rCN | suggestion bar | 候选栏 / 候选词 (bar items, 15) | 建议栏 ×2; 建议 stays for the verb "suggest" |
| tr | word | split: kelime 38 / sözcük 33, so AOSP decides: kelime (15/0) | sözcük in the 2026-09-26 forget dialog |
| vi | correction | sửa lỗi vuốt (source label) | sửa từ vuốt in the forget dialog |
| fil | suggestion | split: mungkahi 10 / suhestiyon 11; AOSP (values-tl) leans mungkahi 5/4 | English "Suggestion Bar" ×4. The bar titles now say "Bar ng Suhestiyon" to match the neighbouring theme strings. The recent privacy strings keep mungkahi. |
| fil | learning | pagkatuto / natutunan | pag-aaral ×1 |

## Known splits left for native review (not in recent strings)

- es layout: diseño 22 / distribución 8
- nl layout: lay-out 19 / indeling 11
- uk layout: макет 26 / розкладка 4
- fa layout: چیدمان 25 / طرح 6
- vi clipboard: bộ nhớ tạm 17 / bảng tạm 11
- tr word: older sözcük strings (33)
- fil suggestion: mungkahi vs suhestiyon
- fil: "Dictionary/Theme/Layout Manager" are English screen names. They are treated as
  intentional borrowing for now, because Filipino UI commonly borrows English for these.

## Filipino English copies

`import_preview_source_screen`, `provenance_aggression` and `input_learning_aggression_title`
are now translated as "Pinagmulang screen", "Pagka-agresibo" and "Pagka-agresibo ng
Pagkatuto". "screen" stays in English, as it does in the neighbouring
`import_preview_current_screen` ("Kasalukuyang screen"). "Pagka-agresibo" follows from the
existing value label "Agresibo".
