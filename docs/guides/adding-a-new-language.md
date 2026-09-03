# Adding support for a new language — the complete procedure

**Written 2026-09-02 against app HEAD `a69a06cf`**, from a four-way audit of the working tree,
`CleverKeys-ML/ctc/APP_WIRING_CHECKLIST.md`, and the dictionary scripts. Companion documents:

| document | question it answers |
|---|---|
| `docs/specs/ctc-architecture-and-multiscript-guide.md` | how the CTC engine works and why one model serves all Latin layouts |
| `.claude/skills/dictionary-pipeline.md` | dictionary classifier internals (⚠ carries stale sections — see `memory/language-support-todo.md` §D) |
| `memory/language-support-todo.md` | what is still outstanding, found by the 2026-09-02 audit |
| `CleverKeys-ML/ctc/APP_WIRING_CHECKLIST.md` §2 | ML-side artifact hashes for the six trained scripts |

## 0. What "supporting a language" means here

Three engines consume a language, with **increasing** requirements:

1. **TAP** (autocorrect / suggestion strip) — needs only a dictionary. Every imported langpack
   serves TAP immediately; there is no eligibility gate.
2. **GEOMETRIC** swipe — needs only a dictionary and a layout. Also ungated; it is the fallback
   for every language CTC cannot serve.
3. **CTC** swipe (the accurate one) — needs a dictionary **plus** either
   (a) *Latin track*: nothing else — the bundled English-trained encoder is layout-agnostic and
   serves any a–z-projectable Latin-script language zero-shot (this is how fr/de/es/it/pt/sv
   ship, and how an imported nl/id/ms/sw/tl pack gets CTC after passing the a–z projectability
   measurement), or
   (b) *Script track*: a per-script ONNX model + golden fixture + a `CtcScriptSupport` table row
   (this is how ru and el ship).

So the decision tree is:

- **Latin-script language** → build the langpack (§1), done. If ≥98 % of its words project to
  a–z (measured automatically on import by `CtcImportedPackSupport`), CTC serves it; otherwise
  it gets TAP + geometric. No code change, no model, no retraining.
- **Non-Latin script already trained** (cyrillic, greek, hebrew — models exist for ru, el, uk,
  bg, mk, he) → langpack (§1) + layout (§2) + wiring row (§3). uk/bg/mk/he need only their
  model + fixture bytes copied and the table row flipped — everything else already landed.
- **Non-Latin script with no trained model** (kk, sr, hy, ka, …) → all of the above **plus** an
  ML-side training run (§4). Budget roughly one GPU-day per script; the recipe is proven.

## 1. The dictionary / langpack pipeline

Everything lives in **this repo, under `scripts/`**. There is no ML-side langpack pipeline —
`CleverKeys-ML` only consumes the packs this pipeline produces.

### 1.1 Command sequence for a new language `xx`

```bash
# 0. Configure: add a LangConfig row to LANG_CONFIG in scripts/build_wordlist.py
#    (alphabet string, script, oracle choice, case_policy, foreign-language list,
#     must_include sentinels) and a SUPPORTED_LANGUAGES row in
#     scripts/build_all_languages.py with 'boost': False.
#    If an AOSP LatinIME wordlist exists for xx, snapshot it to
#    scripts/data/aosp_xx_wordlist.txt.gz and add a PROVENANCE.md row.
mkdir -p scripts/dictionaries/xx     # seed xx_allowlist.txt / xx_blocklist.txt (may be comment-only)

# 1. Dry run — report mode, nothing written; READ THE REVIEW ARTIFACTS before proceeding
python3 scripts/build_wordlist.py --lang xx --bootstrap

# 2. Write mode — emits scripts/dictionaries/xx/xx_words.txt + xx_enhanced.bin (CKDT v2)
python3 scripts/build_wordlist.py --lang xx --write --bootstrap

# 3. Unigrams for in-app language detection
python3 scripts/generate_unigrams.py --lang xx \
        --output scripts/dictionaries/xx/unigrams.txt --top-n 5000

# 4. Deterministic zip
python3 scripts/build_langpack.py --lang xx --name "Xxish" \
        --dict scripts/dictionaries/xx/xx_enhanced.bin \
        --unigrams scripts/dictionaries/xx/unigrams.txt \
        --version 1 --output scripts/dictionaries/langpack-xx.zip
```

Notes that will save you a failed build:

- **`--bootstrap` is mandatory for a first build.** It tolerates the missing shipped-carryover
  basis (loudly) and enables the zipf ≥ 3.0 fallback for 2-character words — without it a
  never-shipped language ships zero 2-char words and loses its core function words (uk `що`,
  bg `не`/`на`/`да`). An *established* language losing its basis is still a hard exit, by design.
- After the first pack exists, `python3 scripts/build_all_languages.py --lang xx` is the repeat
  path (it runs steps 2–4 but never passes `--bootstrap`).
- Set `'boost': False`. `compute_prefix_boosts.py` is OBSOLETE (its consumer died with the
  neural engine, 2026-08-18); `prefix_boost.bin` is a dead payload the importer accepts only
  for backward compatibility.
- `--limit 50000` is the conventional size cap; wordfreq's small lists can undershoot it
  (bg tops out at 35,027 — that is the corpus ceiling, not an error).

### 1.2 What the classifier does (so you can read its output)

`build_wordlist.py` is an evidence classifier, not a frequency cut:

- **Candidates**: `wordfreq.iter_wordlist(lang)` to `--top` depth, gated by
  `2 ≤ len ≤ 25`, `isalpha()`, and the per-script Unicode gate `_is_script_word`
  (latin / greek 0x0370–0x03FF∪0x1F00–0x1FFF / cyrillic 0x0400–0x04FF / hebrew 0x0590–0x05FF).
- **Positive oracles**: hunspell (three case probes: lower/Cap/UPPER) or aspell; AOSP wordlist;
  language-specific extras (NLTK for en). Oracle *tiers* degrade gracefully: A (en fr de es nl
  ru, full spell oracle) → B (it pt) → C (sv el tr he, AOSP-only) → D (id ms tl uk bg mk,
  negatives-only — band 2 is disabled by setting band = top).
- **Negative evidence**: an edit-distance-1 typo detector tiered by word length and zipf gap,
  an elongation exemption (`sooo` is not a typo), and *foreign-language dominance* (a word is
  dropped only when another configured language's zipf beats the target's by > 1.0 and is > 3.0
  — this is the load-bearing filter against cross-Cyrillic Russian contamination in uk/bg/mk).
- **Decision order**: blocklist → function-word force-keep → allowlist → 1-char (carryover
  only) → 2-char rule → band 1 (conservative: keep unless condemned) → band 2 (aggressive:
  keep only with a positive oracle) → carryover → drop.
- **Guards**: a lost `must_include` word or a kept blocklisted word exits non-zero. Review
  artifacts (`cleverkeys-dictgen-xx-{drops-review,shipped-lost,keep}.txt`) land in
  `--review-dir` (default `~/git/swype`) — read the drops review before `--write`.

### 1.3 Frequency model and binary format

`build_dictionary.py` maps wordfreq frequency → an 8-bit **rank** (0 = most frequent, 255 =
least) via `rank = (1 − log(f+1)/log(max_f+1)) · 255`. The app reads it back as
`freq = max(1, 255 − rank)`. This uniform "CKDT scale" is why one CTC λ constant
(`tunedRuCkdt`, λ = 2.0) serves every langpack regardless of language. (English is permanently
excluded from the imported-pack path because its bundled JSON uses a different, compressed
scale.)

`dictionary.bin` is **CKDT v2**: 48-byte little-endian header (magic `0x54444B43` "CKDT",
version 2, lang, wordCount, three section offsets), a canonical section (len-prefixed UTF-8 +
rank byte per word), a normalized section, and an accent map. **There is no trie in the file**
— tries are built at runtime (`CtcLexiconTrie` for the beam, prefix indices for TAP).
Accent normalization at build time: NFD minus combining marks, plus a special fold map
(ß→ss, ø→o, æ→ae, œ→oe, …).

The langpack zip is deterministic (fixed 1980 timestamps, sorted entries — a pure function of
its contents) and contains `manifest.json` + `dictionary.bin` (required), `unigrams.txt` and
`contractions.json` (optional). The manifest has **no script field**; script gating happens
in-app at measurement time.

### 1.4 What happens on import

`LanguagePackManager.importLanguagePack` extracts (zip-slip-safed), validates magic/version,
and installs to `filesDir/langpacks/<code>/`. Immediately after import,
`CtcInstalledPacks.evaluateNow` measures CTC eligibility (`CtcImportedPackSupport`): ≥1,000
words, ≥98 % a–z-projectable overall and ≥99 % over the 1,000-word head. Latin packs that pass
get CTC; everything else serves TAP + geometric. Languages with a `CtcScriptSupport` row (ru,
el, uk, bg, mk, he) are deliberately excluded from this path — they are served (or blocked) by
the script track instead.

## 2. Layout

Ship a layout XML under `src/main/layouts/` whose `script` attribute declares the real script
(`cyrillic`, `greek`, `hebrew`, …). `LayoutScriptDeclarationTest` enforces both the
latin ⟺ a–z-complete biconditional and, for every wired script row, that the row's layout XML
declares exactly that script. Getting this wrong is the "Greek QWERTY trap": `grek_qwerty.xml`
declared `script="latin"` for months and routed Greek boards to an engine that could not
represent them.

**No geometry work is needed.** The app computes key centers at runtime from the rendered
layout — user margins, heights, and custom XML included — and normalizes trace and keys in the
same letter-box frame. `CleverKeys-ML/ctc/app_layout.py` replicates this math to 4.7e-4, which
is what makes offline training/eval geometry match the app.

## 3. CTC wiring for a non-Latin script (the ru/el pattern)

All of this is in place for ru and el; read those rows as the worked example.

1. **`CtcScriptSupport` row** — the single source of truth: alphabet string (**codepoint-sorted;
   the string IS the emission-slot order** — a permutation silently garbles every decode and
   throws nothing), layout XML name, langpack code, model asset, golden fixture. The `init`
   block enforces sortedness/uniqueness and refuses `Status.ROUTED` unless model + fixture are
   both present. The router derives `ROUTABLE_SCRIPTS` from `ROUTED` rows only — you cannot
   widen routing by editing the router.
2. **Projection** (`CtcScriptProjection`) — how lexicon words map onto the layout's key set:
   lowercase + joiner strip for all; NFD/drop-marks/NFC for el and he **only** (NFD destroys
   Cyrillic й); character folds ru ё→е ъ→ь, bg ѝ→и, mk ѐ→е ѝ→и; uk words containing ї or ґ
   rejected as untypeable; Greek word-final σ→ς **after** mark stripping
   (`CtcGreekOrthography.repairFinalSigma` — both halves or neither).
3. **Model + fixture bytes** — from `CleverKeys-ML/ctc/artifacts/` ONLY (the registry is the
   licence boundary: if a file is not in `ctc/artifacts/`, it is not wirable). Generation 4 is
   the deployable one: `<code>_synth_v3_ch80_fp16w.onnx` (589,406 B each) + its
   `*_golden.json`. Verify sha256 against `APP_WIRING_CHECKLIST.md` §2.2 before copying. Model
   → `src/main/assets/models/`; fixture → both `src/test/resources/ctc/` and
   `src/androidTest/assets/ctc/` (byte-identical copies).
4. **Preset** — nothing to add: every script row returns `CtcScoringParams.tunedRuCkdt`
   (γ 1.05 / λ 2.0 / β 0.2 / prunes 0.3734/0.9882), because every langpack is on the same CKDT
   frequency scale.
5. **Language row** — add the code to `CtcLanguageSupport.SUPPORTED` with
   `LexiconSource.CKDT_LANGPACK`. Dispatch hard-gates on the langpack file existing on disk, so
   a user without the pack falls through to geometric, correctly and silently.

### 3.1 Gates before you trust the wiring

- **Slot order**: `CtcScriptSupportTest` pins each alphabet as a literal and asserts the
  fixture's `layout.letters` equals it character-for-character.
- **32-frame budget**: the encoder emits `[1,32,65]`; a word is decodable iff
  `length + adjacent-duplicate-pairs ≤ 32`. Extend `CtcBundledLexiconEmitBudgetTest` to sweep
  the new pack through its real projection (ru is swept; an over-budget word is unemittable
  with no error).
- **Parity rows**: `CtcParityTest` and `CtcEmissionModelParityTest` are table-derived from
  `CtcScriptSupport` — a row with a fixture contributes automatically; confirm the new row
  actually runs. The emission parity test is in the CI gate (`emulator-ci.sh`).
- **Latency/memory**: ARC-058's rotation test covers memory bounds; per-script latency has
  never been measured — expectation (script graphs are half the main model's size) is not
  measurement.

## 4. Training a model for a new script (ML side, pointer)

The full recipe is `docs/specs/ctc-architecture-and-multiscript-guide.md` §3.2 and lives in
`CleverKeys-ML/ctc/`. In outline: layout JSON via `app_layout.py` → synthetic traces from the
generation-4 learned generator (`synth_v3.py`, conditional rectified flow, MIT-licensed
training data only) → train ch80 (~1 GPU-day) → `export_onnx.py` with real-trace parity gate →
`quantize_onnx.py` fp16w → `make_golden.py` fixture → register hashes. Hard rules: never train
on Yandex-derived data or FUTO weights/outputs (eval-only / forbidden — see guide §7); only
artifacts registered in `ctc/artifacts/` may be wired; quote synthesis-holdout numbers as
margins against the English zero-shot control, never as accuracy levels.

## 5. Distribution

The pack must reach users. Today (2026-09-02) packs are **only** in the git tree at
`scripts/dictionaries/langpack-*.zip` — no GitHub release has ever carried one (verified across
all 509 releases). The outstanding tasks — create a langpacks release, update the README table,
add in-app pointers — are tracked in `memory/language-support-todo.md` §A. When packs are
rebuilt, re-upload: the zips are deterministic, so a changed byte means changed content.

## 6. Final checklist

- [ ] `LANG_CONFIG` row + review artifacts read + langpack built, guards green
- [ ] layout XML with correct `script=`; `LayoutScriptDeclarationTest` green
- [ ] (script track) `CtcScriptSupport` row, projection rules, model + fixture copied, shas
      verified against the ML registry
- [ ] `CtcScriptSupportTest` + `CtcParityTest` + emit-budget sweep green;
      `CtcEmissionModelParityTest` green on emulator CI
- [ ] langpack published (release page + README row + display name in
      `SettingsLanguagePackHandlers.getLanguageDisplayName`)
- [ ] release notes announce it (`ReleaseMetadataDriftTest.SERVED_BUT_NOT_YET_ANNOUNCED`)
