# CTC on boards with accented TOP-LEVEL keys (bépo `é`, QWERTZ `ü ö ä`, `å`, `ñ`)

Date: 2026-10-25. Trigger: maintainer soak report — on bépo, swiping `été` over the `é` key gave
`tp`, while swiping `ete` over the `e` key gave `été`. Code: `swipe/CtcMappedLayoutBuilder.kt`.

## 1. Root cause

The CTC encoder emits one column per key SLOT; the app filled the slots with the 26 a–z keys
only (`CtcEngineAdapter.buildMappedLayout`, before this change). A centre key whose letter is
not a–z — bépo's `é` (top row, between `b` and `p`), QWERTZ `ü` (right of `p`), `ö ä` (right
of `l`), Swedish `å ö ä`, Spanish `ñ`, Swiss-French `è é à` — had no slot and did not enter
the model frame (the a–z keys' bounding box). So:

- a path over bépo's `é` was read as the nearest a–z keys (`b`, `p`, `u`): `é-t-é` → `tp`;
- a path over a key outside the a–z box (QWERTZ `ü`, `ä`) was additionally CLIPPED to the
  box edge by the featurizer, i.e. it landed on `p`/`l`.

The geometric engine never had this problem: `LayoutProjection` tier 1 maps `é` to the `é`
key when the layout has one.

## 2. Change

Each such key becomes an ALIAS slot (after the K alphabet slots) at its own centre; its
emission column is folded into its base letter's column by log-sum-exp
(`CtcEmissions.sliceFromHead`), and its rect joins the model-frame box. The base letter is the
key's single-letter a–z projection (`CtcAzProjection.project`): `é→e ü→u å→a ñ→n ø→o`.
Letters projecting to several or no a–z letters (`ß`, `æ`, `œ`, `ı`, `þ`, `ð`) stay slotless as
before. Latin family only (script languages build no aliases). A board without such keys gets a
byte-identical layout and frame (`CtcAccentKeyLayoutReplayTest.anAzOnlyBoardGetsNoAliasesAndAnUnchangedFrame`),
so en/QWERTY, AZERTY and every a–z-only board are unchanged.

The encoder's key embedding is a function of the key centre, never of the slot index
(`ctc-architecture-and-multiscript-guide.md` §1.1), which is why an extra slot is meaningful
without retraining. The ML campaign's `full` arm (`CleverKeys-ML/ctc/ALT_LAYOUT_EVAL.md` §4)
added the extra keys as slots but DISCARDED their columns; it measured no gain. Folding the
columns into the base letter is the difference.

## 3. Evidence on real human traces

**Not pre-registered**: the arms below were defined before any number was seen, but there was
no held-out split — this is one pass over each corpus. Script:
`scripts/ctc_accent_key_eval.py` (needs `~/git/swype/CleverKeys-ML/ctc` and the FUTO swipe-5
caches in `~/.cache/cleverkeys-test/`). Shipped encoder `models/ctc_swipe_encoder.onnx`,
shipped CKDT lexicons projected as the app does (`CtcAzProjection`), shipped Latin CKDT preset
(γ 0.9, λ 2.0, β 0.25, γp 0.25, βp 0.9882, beam 100). Corpora: FUTO `swipe-5` "german"
(the app's `latn_qwertz_de` arrangement: `ü` right of `p`, `ö ä` right of `l`) and "spanish"
(`ñ` right of `l`), real users, in-dictionary rows only. Geometry is FUTO's own layout json,
not the app's XML.

Arms: **app_now** = a–z box frame, 26 slots (the app before this change; the path is
re-normalized into the a–z box and clipped); **az26_all** = all-letter-keys frame, 26 slots
(frame change alone); **fold_all** = all-letter-keys frame + alias slots folded (this change).

| corpus | rows | app_now t1 | az26_all t1 | **fold_all t1** | accented-word rows | app_now | **fold_all** |
|---|---|---|---|---|---|---|---|
| german | 2,228 | 81.46 | 82.41 | **83.75** | 183 | 53.55 | **77.05** |
| spanish | 1,758 | 90.22 | 90.22 | **90.39** | 17 | 52.94 | **100.00** |

German paired (app_now vs fold_all, top-1): 104 traces fixed, 53 broken; exact McNemar
p = 5.7e-5; 127 distinct words change outcome. Of the 53 broken, 2 contain an umlaut; of the
104 fixed, 45 do. The frame change alone accounts for 68 fixed / 47 broken; the fold on top of
the new frame for 43 fixed / 13 broken. Spanish: 6 fixed (all `ñ` words), 5 broken (no `ñ`);
the `ñ` key sits inside the a–z box there, so the frame does not change.

No real corpus exists for bépo, Swedish or Swiss French; for those the evidence is the
structural fix plus synthetic pins (`CtcAccentKeyLayoutReplayTest`), which are not ranking
evidence.

## 4. Left open

- The 53 German regressions are mostly common words without umlauts (`im` ×3, `allen`,
  `einem`, `silber`); not analysed further and nothing was tuned (no held-out split).
- `ß` (bone/neo2), `æ` (Danish), `œ` keys still have no slot; their words remain reachable by
  the `ss`/`ae`/`oe` spellings only. A multi-letter alias would need the beam to emit two
  letters for one slot.
- Script boards (Cyrillic `ё` as a centre key, Greek tonos letters) build no aliases; unmeasured.
