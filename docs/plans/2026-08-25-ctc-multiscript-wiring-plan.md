# CTC multi-script wiring — app-side implementation plan

**Written:** 2026-08-25, against app HEAD `9f3b6b94`.
**Source of truth for ML-side facts:** `CleverKeys-ML/ctc/APP_WIRING_CHECKLIST.md` (2026-08-20,
written against app `d717bda7`; its §1 statuses are re-verified below against today's HEAD),
`PHASE_Q.md` (generation 4), `PHASE_O.md` (projection + zero-shot measurements).
**Reference:** `docs/specs/ctc-architecture-and-multiscript-guide.md` — refreshed to the ML
Phase-Q text on 2026-08-25 with an app-state addendum in its header.
**Sequencing:** this is **post-v1.6** work. The v1.6 drift tests deliberately pin the served
language set (`ReleaseMetadataDriftTest`, `CtcLanguageSupport.SUPPORTED` = en/fr/de/es/it/pt/sv);
do not start Milestone A on the release branch. The open CK-150 items in
`docs/audit/2026-08-25-remediation-verification.md` §5 come first.

---

## 0. Checklist §1 re-verification at `9f3b6b94`

| ML checklist item | Status 2026-08-20 | Status now | Evidence / where it went |
|---|---|---|---|
| 1.1 banner the execution brief (MEDIUM-3) | open | **CLOSED 2026-08-25** | banner added atop `docs/audit/remediation-plans/ctc-integration-execution-brief.md` |
| 1.2 emission check runs nowhere automatic (HIGH-4 residue) | open, 3-class gate | **still open, 5-class gate** | `emulator-ci.sh:124` now also runs a11y + Keystore classes but still not `tribixbite.cleverkeys.swipe.CtcEmissionModelParityTest`; fold the one-line addition into the CK-150-028 curated-list work (verification plan §4.5) so the list-pin test lands with it |
| 1.3 two unmarked `sw2345` citations (HIGH-2 residue) | open | **CLOSED 2026-08-25** | finding 13 struck in `docs/audit/2026-08-17-neural-vs-ctc-parity.md`; superseded-figure notes added at `docs/eval/2026-08-15-ctc-per-language-lambda.md` (both sites) |
| 1.4 app CTC references a generation behind (NEW-6) | open | **CLOSED 2026-08-25** | `memory/HANDOFF.md` (both paragraphs) and `docs/specs/ctc-swipe-engine.md:786` updated to `ru_synth_v3_ch80_fp16w` / sha `8fffa75c…` / 85.07; guide mirror refreshed |
| 1.5 11 MB superseded ONNX in androidTest (MEDIUM-4) | open | **already closed pre-verification** | `src/androidTest/assets/ctc_bench/` holds only a README (2026-08-20) explaining the deletion and the "ship candidate" history; restoration steps included |
| 1.6 LOW-6 dev absolute path in fixtures | open | **closed** | `rg kd_fp16w src/` → no matches |
| 1.6 spec stale cite `Config.kt:300` | open | **CLOSED 2026-08-25** | `ctc-swipe-engine.md` header now cites `:311` |
| 1.6 LOW-9 negative gate-3 test | open | **still open** | no `supportsLayout(...) == false` assertion for a Cyrillic/Greek `KeyboardData` anywhere in `src/test` or `src/androidTest`; becomes **load-bearing** in Milestone A (see A4) — do it as A0 |
| 1.6 LOW-2 phantom `weight` in formula comment | open | still open | `CtcScoringParams.kt:13` — `final_score = ctc/max(len,1)^gamma + weight * beta * len + …`; there is no `weight` property; delete the word |
| 1.6 LOW-8 `"futo"` search keyword | open | still open | `SettingsActivity.kt:589` `SearchableSetting("CTC Settings", listOf("ctc", "futo", …))`; remove `"futo"` |

Guide mirror (checklist §3): **CLOSED 2026-08-25** — copied verbatim from
`CleverKeys-ML/ctc/ctc-architecture-and-multiscript-guide.md`, mirror-warning block replaced with
an app-side header and an addendum listing what moved after `d717bda7`.

---

## 1. The strategy: two milestones, model last

Per `APP_WIRING_CHECKLIST.md` §2.0 (measured in `PHASE_O.md` §2.1): the **shipped English
encoder, zero-shot on real Russian with only the correct layout slots and the correct trie,
reads 76.32 in-dict top-1** — at or above the geometric engine's cross-layout anchors (71–77).
The purpose-built generation-4 ru model adds +3.41 → 79.73; its v3 successor reads 85.07.

- **Milestone A** — all wiring (alphabet, layout, routing, preset, projection, trie), **zero new
  model assets**. APK grows by ~nothing except the imported langpack tries. Every silent-failure
  mode (slot order, projection, preset scale) lives here, and landing it model-free means a
  mistake shows up as *worse decoding*, not as "the model didn't train".
- **Milestone B** — per-script model assets (589,406 B each, six scripts ≈ 3.5 MB), delivered
  via the langpack import rather than bundled. Each is worth ≈ +3 to +9 over zero-shot.

## 2. Milestone A — shared wiring, exact changes

All in `src/main/kotlin/tribixbite/cleverkeys/` unless noted. Current line numbers verified at
`9f3b6b94`.

### A0 — the negative routing test, first

Add to `src/test/kotlin/tribixbite/cleverkeys/swipe/` (or extend
`CtcMultiLanguageInstrumentedTest`): assert `CtcEngineAdapter.supportsLayout(kd, …) == false`
for a loaded `cyrl_jcuken_ru.xml` and `grek_qwerty.xml` `KeyboardData` **today**. This pins
gate 3's current behavior so A4's router change is observable as a deliberate flip, not a silent
widening. (Checklist LOW-9's remaining half.)

### A1 — per-script alphabet

`swipe/CtcEngineAdapter.kt:117`: `private val ALPHABET = CharArray(26) { ('a' + it) }` becomes a
per-language lookup. Put the strings in `swipe/ctc/CtcLanguageSupport.kt` next to `SUPPORTED`
(one map `alphabetFor(language): CharArray`), **copied character-for-character from
`APP_WIRING_CHECKLIST.md` §2.2** — they are codepoint-sorted and **the model's emission slot
order IS this string**; a permutation does not throw, it silently permutes every decode:

| lang | K | alphabet (verbatim) |
|---|---|---|
| ru | 31 | `абвгдежзийклмнопрстуфхцчшщыьэюя` |
| el | 25 | `αβγδεζηθικλμνξοπρςστυφχψω` |
| uk | 31 | `абвгдежзийклмнопрстуфхцчшщьюяєі` |
| bg | 30 | `абвгдежзийклмнопрстуфхцчшщъьюя` |
| mk | 31 | `абвгдежзиклмнопрстуфхцчшѓѕјљњќџ` |
| he | 27 | `אבגדהוזחטיךכלםמןנסעףפץצקרשת` |

Latin languages keep the existing 26-char array. Gate test: assert the app's string equals the
ML layout JSON's `letters` field per script (bundle the JSON `letters` strings as test fixtures).

### A2 — `buildMappedLayout` generalization

`swipe/CtcEngineAdapter.kt:311-345` (`buildMappedLayout`): today it collects only a–z key rects
(`letterOf`'s `'a'..'z'` filter), sizes `FloatArray(26)`/`BooleanArray(26)`, and normalizes over
the a–z bounding box. Change: size arrays by `alphabet.size`, filter by
`alphabet.contains(char)`, normalize over the collected script keys' bounding box. **Geometry
needs no other change** — the ML `app_layout.py` replicates `KeyboardGeometry.computeKeyRects`
+ `buildMappedLayout` to 4.7e-4, so runtime-computed geometry is what the models trained on.
Note the existing Latin behavior is a special case and must be byte-identical after refactor
(pin with the existing `CtcParityTest` featurizer cases).

### A3 — per-language model asset

`swipe/CtcEngineAdapter.kt:102`: `MODEL_ASSET` is one constant. Milestone A keeps it — the
English encoder serves all scripts zero-shot. Add the seam only: resolve the asset path through
a function `modelAssetFor(language)` that returns the constant today. Milestone B fills the map.

### A4 — routing

`swipe/SwipeEngineRouter.kt:118-123`: `if (isLatinScript(script)) Engine.CTC else GEOMETRIC`
becomes a membership check against the set of wired scripts (start: latin + the scripts whose
trie/projection/layout wiring has landed AND whose language is in `CtcLanguageSupport.SUPPORTED`).
Keep the three-gate structure; gate 3 (`supportsLayout`) stays the dispatch-time guard.
**Extend `LayoutScriptDeclarationTest`** (its bidirectional assertion currently encodes
"latin ⟺ a–z-complete"): per script, "script S ⟺ layout exposes S's full alphabet as centre
keys" — extend, do not weaken (the test's own KDoc says per-script routing makes the attribute
load-bearing).

### A5 — make `tunedRuCkdt` the script preset

`swipe/ctc/CtcScoringParams.kt:155-165` (`presetFor`) branches on `LexiconSource` and can never
return `tunedRuCkdt` (`:205`). The ML side has since made the footing decision the `:172-203`
KDoc says was open: **all six script lexicons decode at `tunedRuCkdt` verbatim** (γ 1.05 /
λ 2.0 / β 0.2 / γ-prune 0.3734 / β-prune 0.9882; λ is the CKDT *scale* constant, not a Russian
one — checklist §2.2). Add a script branch in `presetFor` returning
`tunedRuCkdt(beamWidth, topK)` for script languages and update the KDoc (delete the "not
reachable" paragraph, keep the evidence-tier text). **Do not change λ** — the Phase-Q sweep was
monotone-decreasing with the optimum off-grid low and the pre-registered rule refused adoption;
a −0.63 t1 shortfall is measured-unconfirmed and any change is ML-side first
(fixture-and-preset rule).

### A6 — fuzzy rescue must not go silently dead (app-only insight, not in the ML checklist)

`swipe/ctc/CtcFuzzyRescue.kt:56` (`fromFrequencies`) filters `word.all { it in 'a'..'z' }`. For
a Cyrillic/Greek/Hebrew lexicon this drops **every** word — rescue builds an empty index and is
silently inert. Parametrize `fromFrequencies(freqs, alphabet: Set<Char>)` and pass the script
alphabet at the `CtcEngineAdapter.kt:550` construction site. This also fixes CK-150-031 for EN
if the index is built from projected surfaces. Do it together with the CK-150-025 clamp fix
(verification plan §4.3) since both touch the same seam.

### A7 — projection module (checklist §2.3, mirrored exactly)

New file `swipe/ctc/CtcScriptProjection.kt`, applied to the lexicon **and** to anything compared
against a decode:

- all scripts: lowercase; strip `- ' ’ ʼ ‘ \``.
- **el, he**: NFD → drop `Mn` combining marks → NFC (Greek accents and Hebrew niqqud are not
  keys). **el additionally**, *after* mark stripping: word-final `σ` → `ς` — this half already
  exists as `swipe/ctc/CtcGreekOrthography.kt` (`repairFinalSigma`/`repairLexicon`, zero
  production callers today — awaiting this consumer, do **not** sweep it as dead code). The
  mark-strip half has **no** implementation (`CtcAzProjection` is Latin-specific). **Both halves
  or neither**: sigma-only upgrades "25.7 % of the pack scored against the wrong key" to "most
  of the pack unrepresentable" (unprojected `λόγος` carries `ό`, which has no emission slot).
- **ru, bg, mk**: **no NFD** (it decomposes `й` → `и`+breve and destroys the alphabet).
  Character folds instead: ru `ё→е`, `ъ→ь`; bg `ѝ→и`; mk `ѐ→е`, `ѝ→и`.
- **uk**: no folds; words containing `ї` or `ґ` are rejected as untypeable (4.03 % of the
  vocabulary; serving them is a different input mode).

Keep canonical display forms and deterministic highest-frequency-wins collision resolution,
same as `CtcAzProjection.projectLexicon` (`:95-116`) — reuse its structure.

### A8 — trie width and memo capacity

The `MAX_CHILDREN = 26` clamp is already gone (constructor check against emission-head width) —
do not reintroduce it. `trieMemos` LRU evicts at `size > 2` (`CtcEngineAdapter.kt:376-380`);
primary + secondary + one script language thrashes a ~19 MB rebuild per switch. Measure first
(CK-150-026's dual-language latency gate), then decide 2 vs 3.

### A9 — the 32-frame budget gate, per lexicon

The encoder emits fixed `[1,32,65]`; a word is decodable iff
`length + adjacent-duplicate-pairs ≤ 32`. `CtcDecodableLength` already computes this and a test
covers `en_enhanced.json`. Add the same loop over each imported script trie's word list at
langpack-import time or in a pure per-pack test — **no script lexicon has been checked**, Greek
and Ukrainian carry long inflected forms, and an over-budget word is unemittable with no error.

### A10 — tests and drift pins that must move together

- `CtcParityTest` grows a fixture **row** per wired script (10 cases each, same shape as en) —
  a mechanism change is not needed (checklist §2.4.3).
- `ReleaseMetadataDriftTest` pins `SUPPORTED` and release-note language wording — extend the
  pinned set and the notes in the same commit.
- Contraction injection is already scale-safe (`minReal − 1` per lexicon, `98307dc2`); do not
  reintroduce a constant.
- Add `tribixbite.cleverkeys.swipe.CtcEmissionModelParityTest` to `emulator-ci.sh:124` `CLASSES`
  (checklist 1.2) together with the CK-150-028 `OK (0 tests)` regex fix and list-pin test.

**Milestone A exit criteria:** ru (first script — its langpack `langpack-ru.zip` already exists,
50 k words, CKDT v2) decodes on `cyrl_jcuken_ru.xml` through the English encoder; slot-order
test green; 32-frame gate green; A0's negative flipped deliberately; zero-shot in-dict top-1 on
the eval corpus in the vicinity of the measured 76.3; no regression in the seven Latin languages'
pinned tests.

## 3. Milestone B — per-script model assets

Only after Milestone A ships. Per script: bundle-or-import the ONNX + golden fixture, fill
`modelAssetFor`, add the `CtcParityTest` row against the new fixture, run the emission parity
test on device.

**Generation rule:** wire **only** `*_synth_v3_ch80*` (generation 4, uniform suffix). Every
`*_synth_ch80*`, `*_synth_v2_ch80*`, `*_synth_v2full_ch80*` file in `ctc/artifacts/` is
superseded and kept only because published numbers were measured on it. **If a file is not in
`CleverKeys-ML/ctc/artifacts/`, it is not wirable** (the `RESEARCH_ONLY`/Yandex seal — see §4).

Ship bytes (all 589,406 B; hashes from `APP_WIRING_CHECKLIST.md` §2.2, taken off disk
2026-08-20 — re-verify with `sha256sum` at copy time):

| script | ONNX | sha256 (prefix) | fixture | lexicon status |
|---|---|---|---|---|
| ru | `ru_synth_v3_ch80_fp16w.onnx` | `8fffa75c…` | `ru_synth_v3_ch80_fp16w_golden.json` | `langpack-ru.zip` exists, importable today |
| el | `el_synth_v3_ch80_fp16w.onnx` | `7083794c…` | `el_…_golden.json` | `langpack-el.zip` exists; needs A7's **both halves** |
| uk | `uk_synth_v3_ch80_fp16w.onnx` | `af9959a8…` | `uk_…_golden.json` | must be built ML-side (`build_wordlist.py --lang uk`) |
| bg | `bg_synth_v3_ch80_fp16w.onnx` | `119d42f7…` | `bg_…_golden.json` | must be built ML-side |
| mk | `mk_synth_v3_ch80_fp16w.onnx` | `4e371d96…` | `mk_…_golden.json` | must be built ML-side |
| he | `he_synth_v3_ch80_fp16w.onnx` | `a382371…` | `he_…_golden.json` | must be built ML-side + a `hebrew` branch in `build_wordlist._is_script_word` |

Full 64-hex hashes: `APP_WIRING_CHECKLIST.md` §2.2 (do not trust any other document for them).
he's old parity flag is a generation-2 fact; generations 3 and 4 export clean at default
tolerance — do not hold he back on it. Model-swap rule: asset + preset + **both fixture copies**
move in ONE commit, never independently.

On-device latency/memory has **never** been measured for any script model (the graphs are half
the shipped model's bytes, so expectation is favorable; expectation is not measurement) — run
the ew-cli latency gate per script before enabling.

## 4. What NOT to wire (binding, from checklist §4)

1. **Nothing Yandex-derived** — no training rows, teachers, fine-tunes, or artifacts whose
   pipeline touched the corpus. Sealed `RESEARCH_ONLY` artifacts live outside `ctc/artifacts/`
   and produced exactly one number (the 85.95 upper bound), no bytes. If an accuracy sounds too
   good (e.g. 89.64), check whether it is that model.
2. **No FUTO weights or model outputs**; corpus + decode-algorithm lineage is the permitted
   inheritance (`NOTICE:46-64`) — do not "improve" that wording.
3. **No per-node trie cap** — the alphabet-vs-head-width constructor check is the real bound.
4. **Not `CtcFeaturizer.normalizeRawX/Y`** — the encoder trained on letter-box normalization,
   not FUTO's 4/3 frame.
5. **Never quote a synthesis-holdout level as accuracy** (el 92.12 is not "Greek at 92.12");
   quote margins against the fixed control, and only ru has a real-probe level (85.07).

## 5. Coordination and ordering

- **Concurrent-agent boundary:** a context-rescoring agent currently owns
  `docs/specs/ctc-context-rescoring-and-tunables.md`,
  `docs/plans/2026-08-22-context-rescoring-step5-harness.md`,
  `docs/eval/2026-08-22-context-rescoring-first-replay.md`, and likely
  `SwipeContextRescorer.kt` / `ContextRescoringReplayTest.kt` / `CtcReplayEngine.kt`. This plan
  does not touch those files; A6/CK-150-025 changes that must be mirrored into
  `CtcReplayEngine` (rescue behavior) should land **after** coordinating with that work.
- Order: v1.6 release blockers (verification plan §5) → checklist 1.2 + 1.6 leftovers (one
  small commit: CI class line + `weight` comment + `"futo"` keyword) → Milestone A (ru first,
  el second) → Milestone B per script as ML delivers lexicons.
