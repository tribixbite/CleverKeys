# HANDOFF — updated 2026-08-27

Read this first, then `docs/specs/ctc-architecture-and-multiscript-guide.md` (architecture,
routing rule, multi-script recipe, full audit table). This file is the **task list**; the guide is
the **reference**. Where they overlap, the guide wins on technical detail and this file on
priority.

**Completed work is DELETED from this file, not struck through.** Git history is the record of
what was done; this file is only what is left. Anything below is open.

## State at `da012ded`

Swipe is **CTC (default) + geometric**; the neural engine was deleted 2026-08-18
(`a7d03bc8`..`83220634`), −26.4 MB APK. `CtcLanguageSupport.SUPPORTED` is **eight** languages:
en/fr/de/es test-validated, it/pt/sv `PROVISIONAL` (scale-transferred, no per-language bar), and
**ru** `VAL_ONLY` since 2026-08-29 (`1561dbaf`, `da012ded` — the first non-Latin script; see the
geometric-removal section below for what is and is not established about it).
Gates: `runPureTests` **1920**, `runMockTests` **325**, `lintDebug` 0 errors, both compiles.
Last full instrumented run (ew-cli, Pixel7 API 34, 2026-08-28, run `2ca8b7c9` at `6d67a7c8`):
**1,430 tests, 3 red — all explained, none a code regression**: 2 `CtcOnnxLatencyBenchmarkTest`
reds that are BY DESIGN whenever the ctc_bench models are not staged (`3fcbf7b8`; restore via
`cp` from CleverKeys-ML/ctc/artifacts/ into src/androidTest/assets/ctc_bench/ only when actually
benchmarking — expect these 2 reds in any full run, do not chase them) + 1 first-revision
`ContractionSentenceStartMeasureTest` red (asserted at the wrong layer; rewritten against the
real SuggestionHandler+SuggestionBar wiring and re-verified green 3/3 on-device, run `1a851d40`).
Every wave-added instrumented test passed its first device execution, including
`CtcLatencyGateTest` (cold build **3,162 ms** vs the 4,500 ms budget — real margin, not
vacuous) and the dual-language latency tests CK-150-026 had left unexecuted.

**Contractions**: the whole system is now documented as-built in
`.claude/skills/contraction-system.md` — data model, the four guards, the regressions each one
prevents, regeneration commands, and the invariant→test table. Read it before touching anything
named `contraction*`. The 2026-08-20/21 cross-language, user-word and paired-base fixes live
there and in git history, not in this file.

---

## Before you trust an entry below

Three claims inherited by the 2026-08-20 session were investigated and **all three were false**
(`SwipeResampler` "consumer-less", `CoroutineScopeLifecycleTest` "flakes in combined runs",
`ctc_bench` "never packaged"). Inherited claims decay; the cheap experiment beats the plausible
story. Two habits came out of it and are worth keeping:

- **Read the `N) <test>(<class>)` failure header, never the noisiest stack trace.**
  `CoroutineScopeLifecycleTest` throws "boom" ON PURPOSE to prove `SupervisorJob` isolation, which
  made it the loudest thing in every red log and got it blamed for a year of flakes it never
  caused. Note the header does not always start a line — stdout and stderr interleave, so anchor
  the search with `[0-9]+\)` rather than `^[0-9]+\)`.
- **Verify a quiet machine by LOAD, not by grepping for one process name.** `uptime` reads
  `sysinfo()` and works on Android even though `/proc/loadavg` is SELinux-denied (which is
  separately why the JVM's `getSystemLoadAverage()` returns -1 here). A "clean box" check that
  grepped for `java` missed 23 leaked `bash` CPU burners and produced eight bogus benchmark
  numbers plus two commits that had to be reverted.

---

## Open work, in priority order

### 0. Audit-archive leak ledger (2026-08-28) — remediation waves complete; residue below

The archive-verification pass (`docs/audit/2026-08-28-archive-verification.md`, ARC-001..052)
recovered 48 untracked findings; **41 were fixed the same day** across seven implementation
waves (commits `31685cac`..`fee6bd4d` + wave commits; every fix cites its ARC ID). Consult git
log and the verification doc before re-deriving anything. Still open:

- **ARC-008 (soak-gated)**: R8/ProGuard still disabled (`build.gradle` "REPRODUCIBILITY TEST"
  comment); re-enable needs a reflection-keep audit + full ew-cli soak + internal release, or a
  recorded decision to delete `proguard-rules.pro`.
- **ARC-012 (investigation)**: #79 settings header flicker — unreproduced; note the screen is
  `Column`+`verticalScroll`, NOT LazyColumn, so the old diagnosis is wrong.
- **Astro 5→6 migration owed** (2026-08-29): the site's two remaining HIGH CVEs are fixed only
  in astro 6.4.6, which needs vite 7+ — conflicting with the vite 6.4.3 CVE pin in
  `site/package.json` overrides. Suppressed in `.trivyignore` with rationale (build-time-only
  static generator). When migrating: bump astro, drop the vite/js-yaml overrides, delete the
  two `.trivyignore` lines, rebuild (84 pages expected).
- **ARC-013 FIXED 2026-08-29, awaiting device confirmation.** UT-5 closed 2026-08-28; UT-7's two
  measured defects are fixed in code and both root causes turned out NOT to be what the
  measurement doc guessed. (a) `id → i'd` was never missing from the DATA —
  `contraction_pairings.json` has carried it all along; the tap path's inline
  `partial.length >= 3` paired-injection floor excluded the only two-letter I-contraction base.
  The floor now lives in `ContractionInjectionPolicy`, which admits first-person contractions at
  two characters and nothing else — verified against the shipped table as **exactly one** base
  changing. No data change, so no regeneration and no new collision sidecar. (b) the doubled
  `I'll` was not two injection paths but ONE list holding the variant twice:
  `ContractionManager.loadPairedContractions` merged `contraction_pairings.json` on top of the
  binary-derived pairs with a blind `add()`, and the two sources overlap on **599** of 2,258
  bases (every doubled possessive too: `times → time's` twice). Deduped at the loader plus a
  final-list guard in `SuggestionHandler`. **Next ew-cli run must confirm on-device**:
  `ContractionSentenceStartMeasureTest` (now PINS `i'd` present for typed `id`, the literal `id`
  surviving alongside it, and no duplicate surface for typed `ill`) and
  `ContractionFlickerTest`'s rewritten prefix-guard cases.
- **ARC-019 CLOSED 2026-08-28**: same-inputs head-to-head on LOCAL combined (4,526 traces):
  CTC 90.7/95.4/96.1 vs geometric 63.0/75.2/78.3 top-1/3/5; geo-only recoveries 1.5%. The last
  accuracy argument for geometric-on-Latin is gone. Synthetic tiers: CTC degrades more
  gracefully than geometric (11.3 vs 19.6 pt TYPICAL→SLOPPY drop) but absolute synthetic levels
  carry a timing artifact — full record in `docs/eval/2026-08-28-arc019-ctc-local-head2head.md`.
- **ARC-044**: androidTest assertion quality — 271 `assertNotNull` / 0 `assertThat`; start with
  the 6 curated release-gate classes.
- **ARC-045**: ~168 raw Compose `Text("…")` literals unextracted (LearningDataSection 21,
  IntentEditorDialog 19, CommandPaletteDialog 18, LayoutManagerActivity 18, …).
- **ARC-048 (backlog, worsening)**: ConfigSnapshot absent (static consumers 28→33 files),
  145-file flat package root, 6 hand-wired Initializers, WordPredictor 2636 lines no interface,
  SettingsActivity 123 mutableStateOf + `SettingsScreen.kt:69-73` composition-body writes
  without `SideEffect{}` (stale `composeScope` no-ops scroll-to-setting). Plan:
  `docs/history/audits/remediation/5-architecture.md`.
- **ARC-049 (device)**: one long-run `MemoryProbe` + `dumpsys meminfo` on a current build to
  close the unexplained 2026-08-17 OOM.

**Written 2026-08-29, never executed on a device** — the next ew-cli run is their first:
`TermuxDeletionInstrumentedTest` (7 cases; ARC-007's owed test — Termux vs ordinary-app control
for REPLACE deletion, typed-partial deletion and delete-last-word) and the flipped
`ContractionSentenceStartMeasureTest` / `ContractionFlickerTest` contraction cases above.

**Verified by the 2026-08-28 full ew-cli run** (all green): ARC-023 cold-build budget
(3,162 ms), the provenance UI cases (14/14 `ClipboardPanelPrivateBadgeTest`), base64
temp-file / clipboard-cap / private-copy-toast instrumented tests, dual-language latency
(CK-150-026's device half).

**Still owed — manual on the maintainer's device or unwritten instrumented coverage**:
#148 visual pass (predictions off → open clipboard → keyboard stays visible below the pane);
private-media paste refusal behavior; ARC-005 nonzero occlusion on a geometric-served layout;
`.ckenc` SAF naming (one manual export-with-password); encrypted-import 🔒 preview rendering
(Compose dialog cases unwritten); GIF legacy-pack rejection paths (need a real ZIP +
GifDatabase); next-word cold-start bar on-device (opt into next-word, empty learned store).

**Translation debt added by the waves** (English-only, non-blocking):
`pref_secondary_prediction_weight` summary, `backup_base64_too_large`,
`clipboard_private_copy_toast_title/desc`, `clipboard_provenance_via/direct_launch`,
`privacy_on_device_learning_desc` (21-locale copies still name deleted swipe-calibration).

### 1. Contraction follow-ups, all deferred deliberately

- **Owed translations** for `collision_warning_title/body/examples` — English-only behind
  `tools:ignore="MissingTranslation"`.
- **User-word guard is case-partial by design.** `replaceModeContractionFor` probes the word plus
  its lowercase and capitalised forms, not a full case-insensitive match. Making it total means
  giving `userWords` case-insensitive membership, which changes add/remove/dedup semantics for a
  persisted user-owned set — its own change, not a side effect.
- **Verb inversions** (`est-elle`, `a-t-on`) deferred with named landmines: `estelle` is a native
  word @16343, `aton` is ASK-attested, `entretemps` is a classifier misfire needing
  `FORCED_APPEND`.

### 2. Context-LM: rescoring CLOSED; next-word is the consumer

**DECIDED 2026-08-26 (maintainer): `swipe_context_rescoring` stays default-OFF permanently; no
shadow mode; the investigation is closed.** The learned-context data's consumer is **next-word
prediction** (tap-to-accept) instead of swipe rescoring (silent auto-insert, which the offline
eval showed overturning correct decodes: 0 fixed / 6 broken on the no-eviction device corpus,
no (WEIGHT,R_MIN) grid point passing, tune winners losing on held-out).

Everything is in git history and these references — do not re-derive:

- **The eval + its 13-entry retraction ledger**: `docs/eval/2026-08-22-context-rescoring-first-replay.md`.
  Numbers are PINNED to a commit; the decoder moved twice under the measurement.
- **Drift canary**: `CtcReplayEngineSmokeTest.slateShapeHasNotDrifted` — red means RE-BASELINE
  the eval, not "decoder broken". Skips without the local trace corpus.
- **Harness**: `ContextRescoringReplayTest` (+ `CtcReplayEngine`, pure-JVM real CTC decode via
  `extractOrtNative`). Knobs: `-PreplayCorpus={device|ubuntu} -PreplayDecoys -PreplayMaxCtx`.
  Corpora live in `~/.cache/cleverkeys-corpora/` and are NEVER committed.
- **Next-word audit landed 2026-08-26** (`b9355be1`, `ececaa73`): `context_aware_predictions_enabled`
  is now a REQUIRED `NextWordPredictor.shouldShow` param and gates the cursor-park editor read;
  dependent settings controls (next-word switch, personalization strength, learning aggression)
  are visible-but-disabled, never hidden; search entries open the Advanced-Prediction panel.
  Pinned by `LearningWiringDriftTest` + `SettingsSearchCoverageTest` source-scans.

**Open (only if someone reopens rescoring)**: hub-confusable decoy rule; a second language.
**Open (next-word, minor)**: nothing known; feature remains opt-in default-OFF behind
`next_word_prediction_enabled` + the master learning gate + the context-LM pref.

### 3. Smaller, ride-along

- `contraction_pairings_cleaned.json` (32 entries, 5,177 bytes) has ZERO code references —
  verified 2026-08-21 across `src/`, `scripts/`, `tools/`. Candidate for deletion with the next
  data change; needs a gate run, not a decision.
- **Doc claims found but deliberately not fixed** during the 2026-08-21 deleted-class sweep,
  each needing verification against live code rather than a mechanical edit: invented API
  signatures in `core-keyboard-system.md` (`CleverKeysService.switchLayout`,
  `KeyEventHandler.handleKeyDown`); a self-contradicting test inventory in `testing-strategy.md`
  ("5 Robolectric / 6 instrumented" vs its own later 987/176/887); a `SwipeDetector` box and a
  `DATABASE_VERSION = 1` claim in `ARCHITECTURE_MASTER.md` §9/§7.2 (clipboard schema is V4); and
  an unverified file tree + "~3000 lines" in `settings-system.md`.
- Translations owed: `pref_secondary_prediction_weight` summary (English rescoped to tap-only
  2026-08-28/ARC-018; 21 locales still carry the unscoped wording), plus
  `swipe_context_rescoring_*`, `collision_warning_*`, `swipe_engine_fallback_*`, `gesture_touch_smoothing_*`,
  `gesture_finger_occlusion_*`, `dict_word_too_long_for_swipe_*` ship English-only behind
  `tools:ignore="MissingTranslation"`. The 21 `swipe_engine_mode_desc` translations were
  machine-extended and want a native reviewer.
- `finger_occlusion_offset` ships at default 0. A nonzero default needs a device-trace A/B at
  {0, 8, 12.5, 16}% — the old 12.5% was never measured anywhere in this repo's history.

---

## The geometric-removal question

The **language** dimension is closed for Latin. **Cyrillic is now half-open**: the wiring is
generic and Russian is routed. What remains is the other five scripts' lexicons, and layout.

Layout census (`src/main/layouts/`, 86 XML — the tree `copyLayoutDefinitions` ships;
`srcs/layouts/` is divergent and read by no build task):

| bucket | count | routing |
|---|---|---|
| `script="latin"` and a–z-complete | 46 | CTC |
| `script="latin"` but a–z-incomplete | 2 | geometric, via the alphabet gate |
| `script="cyrillic"` | 11 | **CTC at gate 1** since 2026-08-29; only `ru` is served, so ten of the eleven fall through at the LANGUAGE gate |
| other non-Latin declared (14 scripts) | 25 | geometric at gate 1 |
| no `script` attribute | 2 | `numeric.xml`, `pin.xml` — not letter layouts |

**"Non-Latin can only be served by geometric" is false, and is now also disproven in the app.**
Per-script models are required — a Latin-trained encoder does not zero-shot another script well
enough on its own, because motor statistics and the learned character-transition prior are
trained even though geometry is an input — but they are cheap and proven: ~94k steps of
`resbn:80`, under an hour on one GPU, from a word list and layout geometry alone.
`phaseIB-ru-synth` saw zero real Cyrillic rows and decoded real Russian at 77.41 in-dict top-1;
the generation-4 successor (`ru_synth_v3_ch80`, learned-generator synthesis, Phase Q) reads
**85.07** on the same eval-only probe.

"The ALPHABET is hardcoded a–z" **was** true of two constants in one adapter file and false of
everything else. Both are gone (`1561dbaf`).

### As built — 2026-08-29, `1561dbaf` + `da012ded`

The infrastructure is generic and Russian is routed end to end. What exists now:

- **`swipe/ctc/CtcScriptSupport.kt`** — THE per-script table: alphabet (codepoint-sorted, the
  model's emission slot order), layout XML, `script` attribute, model asset, golden fixture,
  status, and for the unrouted five the exact gap and its unblocking condition. All six scripts
  have a row. Only `Status.ROUTED` widens the router, and the row's `init` refuses ROUTED unless
  both artifacts are named — rule 4 as a type invariant.
- **`swipe/ctc/CtcScriptProjection.kt`** — PHASE_O §3.4 mirrored exactly, for all six scripts,
  unit-tested (ru/bg/mk folds and NO NFD, el mark-strip + final sigma, uk ї/ґ rejection, he
  niqqud). It also owns the single collision-resolving lexicon loop `CtcAzProjection` now
  delegates to.
- **Adapter** — per-language alphabet, per-language model asset, per-ASSET ONNX session and
  failure latch (a dead script graph can no longer disable English), `supportsLayout(…,
  language)`, a `hasLexiconSource` gate, an alphabet-scoped `CtcFuzzyRescue`, and a
  layout-derived finger-occlusion row count.
- **`presetFor`** returns `tunedRuCkdt` for any language with a script row. It was unreachable
  for months, so the constants all six script models were gated and fixture-generated at could
  not be selected. λ/γ/β and both prune terms are unchanged.
- **Router** — gate 1 consults `CtcScriptSupport.ROUTABLE_SCRIPTS`, not `isLatinScript`. Routing
  stays per SCRIPT; serving stays per LANGUAGE, and that division is test-pinned.

**Russian, exactly.** Ship bytes `src/main/assets/models/ru_synth_v3_ch80_fp16w.onnx`
sha `8fffa75c…` (589,406 B), fixture `ru_synth_v3_ch80_fp16w_golden.json` sha `2e8de3c5…`
(160,384 B, two byte-identical copies), preset `tunedRuCkdt`, layout `cyrl_jcuken_ru.xml`,
alphabet `абвгдежзийклмнопрстуфхцчшщыьэюя` (31 — ё and ъ are CORNER values and are folded away by
the projection, never emission slots).

**The ru lexicon is the imported langpack, not a bundled asset.** `LexiconSource.CKDT_LANGPACK`
reads `filesDir/langpacks/ru/dictionary.bin`, which is byte-identical (sha `2bd8f244…`,
2,088,865 B, CKDT v2, 50,000 words) to `scripts/dictionaries/ru/ru_enhanced.bin` and to
`langpack-ru.zip`'s payload — the file `eval_cyrillic.build_trie` reads, so every published
Russian number is on exactly this lexicon. Consequence to state plainly: **Russian CTC only
works once the user imports `langpack-ru.zip`**; without it ru is not even selectable, and the
`hasLexiconSource` gate hands the swipe to geometric if a backup import sets the pref anyway.

**Evidence tier — say it exactly this way.** 85.07 in-dict top-1, measured on the Yandex
valid-10k, which is **eval-only by licence**. Russian CTC is **val-only permanently**: the
test-2400 seal is spent and no Cyrillic model was ever decoded on it, so no Russian number may
ever be called "test-validated". The model is license-clean synthesis (learned generator, MIT
data only) and saw zero real Cyrillic rows; a sealed twin puts the upper bound at 85.95 and
produced one number and no bytes. The model arm is three seeds (85.30 ± 0.207) and the shipped
s1234 bytes are the LOWEST of the three; the generator and the ceiling arms are single-seed.
**Nothing on-device has ever been measured for any script model** — no latency, no memory.
λ = 2.0 carries a measured, unconfirmed −0.63 t1 shortfall; γ, β and the prune terms are E1's.

### What is left, per script

`el` is the cheapest by a distance and was deliberately NOT routed in this wave. Everything is
ready except two files and a decision: `grek_qwerty.xml` exposes all 25 letters as centre keys,
`langpack-el.zip` exists on the same CKDT scale (and `scripts/dictionaries/el/el_enhanced.bin`
is already built), and **both halves** of the el projection are implemented and unit-tested. The
gap is `el_synth_v3_ch80_fp16w.onnx` (sha `7083794c…`) + its fixture (sha `d08d5501…`), plus the
fact that **Greek has no real-swipe probe at any tier** — its 92.12 is a synthesis-holdout level
and may never be quoted as accuracy — so the ew-cli run would be the only evidence Greek swipe
works at all. Wiring it is a table row, an asset copy and a `SUPPORTED` line.

`uk`, `bg`, `mk`, `he` are infrastructure-ready and blocked on LEXICONS, which must be built
ML-side (`build_wordlist.py --lang <code>` and packaged as CKDT v2 langpacks); `he` additionally
needs a new `hebrew` branch (0x0590–0x05FF) in `build_wordlist._is_script_word`, which currently
raises on any script but latin/greek/cyrillic. Their models and fixtures are also unshipped. Each
row in `CtcScriptSupport` states its own gap; that table is the live list, not this paragraph.

**Still true of every script including ru**: the 32-frame budget has never been checked against a
real script lexicon. `CtcDecodableLength` computes it and a test covers `en_enhanced.json`; no
script pack has been swept, and Greek and Ukrainian carry long inflected forms. A word over
budget is unemittable with no error.

Geometric is removable **script by script**, ~an hour of GPU each plus a lexicon plus wiring. It
cannot be removed first: deleting it today does not downgrade a Bulgarian user, it removes their
swipe.

**Tier note**: Colemak and arbitrary user XML were never benchmarked. Covered by design; the
worst *measured* layout is german at 81.30 against geometric's ~77, so the expected-value case is
strong — but "Colemak ≥ geometric" is an inference, not a measurement. Say it that way.

---

## Rules that must not be broken

1. **No Yandex data** in any training run or shipped artifact — eval-only by licence. `ru-real`
   at 89.64 is better than everything else and permanently unusable.
2. **No FUTO weights or outputs** in anything we train or ship. Corpus + decode-algorithm lineage
   are the permitted inheritance; `NOTICE:46-64` states it correctly — do not "improve" it.
3. **Respect evidence tiers in prose.** "test-validated" = decoded on a sealed split whose read
   was spent from the ledger; everything else is val-only. Russian is val-only permanently,
   it/pt/sv are `PROVISIONAL`. Quoting a val-only finalist's number as the ship model's is exactly
   how the `sw2345` misattribution happened — twice.
4. **Never route a non-Latin script to CTC** without all three of: a per-script model, a
   per-script trie at the app's own frequency scale, and a golden fixture.
5. **Do not touch λ, γ, β, γ_prune, β_prune.** Corpus-fitted; the published-preset control
   measured −2.3 pt top-1. λ is per-lexicon-SCALE (4.0 en-JSON / 2.0 CKDT), so a raw user knob
   would be wrong by 2× on the wrong asset; expose only a bounded offset if ever.
6. **Testing policy**: never test locally via ADB (build-install and log-read only). ew-cli
   instrumented or pure JVM; if untestable, ask.
7. **CI emulator steps**: `reactivecircus/android-emulator-runner` runs each `script:` LINE as a
   separate `sh -c` (dash, no `pipefail`). Keep every `script:` a ONE-LINE call into
   `.github/scripts/emulator-ci.sh`; inline multi-line bash silently dies on line 1 and reports
   a misleading `adb: device offline`. That kept the workflow red for ~32 consecutive runs.
8. **`sh gradlew`**, not `./gradlew`. Temp files in the session scratchpad, never
   `$TMPDIR`/`$PREFIX/tmp`. `rg`, not `grep`. **Rebuild BOTH APKs before an ew-cli run** — a stale
   app APK gives `NoSuchMethodError` for code you just wrote.
9. **Consult Fable** when stuck, unsure of the optimal approach, on complex tasks, and to audit
   new architectures or risky changes. It has caught: the `sw2345` misattribution, the layout
   census, `finger_occlusion_offset` classified by filename rather than behaviour, and a bulk
   hyphen extraction that would have destroyed 73 native French words.

## Verification owed

- **The ru CTC path has never run on a device.** `da012ded` ships the Russian encoder and routes
  Cyrillic, and every gate that could be checked without hardware is green — but no instrumented
  run has happened since. The next ew-cli run must confirm, in this order: (1)
  `CtcEmissionModelParityTest` passes its NEW ru row, i.e. the packaged
  `ru_synth_v3_ch80_fp16w.onnx` actually loads through ORT and reproduces the fixture's emission
  matrices within 2e-3 and its top-k within 1e-3 — this is the only gate that executes the graph,
  and a stale-matrix fixture passes everything else; (2)
  `CtcMultiLanguageInstrumentedTest.theLayoutGateIsPerLanguage` — the deliberate flip: a Cyrillic
  board is now eligible for ru and still rejected for en, and no script board is eligible for
  another script's language; (3) `everyRoutedScriptShipsItsModelAssetInTheApk` — that aapt really
  packaged the new asset (589,406 B of ONNX is easy to lose to a packaging rule); (4) a ru decode
  end to end WITH `langpack-ru.zip` imported, which no emulator has today — the pack-installed
  path is unreachable on a clean emulator, so this half needs the maintainer's device; (5) the
  latency gate on a ru swipe, because **no script model has ever been timed or memory-profiled**
  — the graph is a fifth of the Latin encoder's bytes so the expectation is favourable, and
  expectation is not measurement. Also worth watching: the trie memo evicts at `size > 2`, and a
  ru primary now pulls a SECOND ORT session alongside the Latin one.
- **The collision-warning dialog has never been SEEN.** Its logic is instrumented-tested, but the
  dialog only appears when an imported language pack contributes a collision, and no pack is
  installed on the emulator — so the pack-collision path cannot be reached on emulator.wtf at all.
  Needs a device with a pack imported (nl is the bundled-adjacent one) alongside a bundled
  language, then a language re-selection to trigger the scan.
- **Manual, on the maintainer's device**: the 2026-08-26 disabled-not-hidden settings rendering
  (Settings → Input → Advanced Prediction Settings: toggle Context-Aware off → Next-Word switch
  should DIM, not vanish; toggle Personalized Learning off → strength slider + aggression dropdown
  should dim, and the dropdown must not open). Pure-JVM cannot see Compose rendering. Also still
  owed: Italian swipe (moved neural→geometric→CTC in one day);
  first-swipe warm-up now that neural preload is gone; a pre-v1.6.0 backup import (no `neural_*`
  rows written); a pre-v1.1.86 upgrade (`migrateToLanguageSpecific` moved into
  `DictionaryManager.init`, ordering test-pinned but the migration untested end to end).
- **`docs/ARCHITECTURE_MASTER.md` §1/§4/§5** and the data-flow diagram were substantially
  rewritten from code by an agent whose bulk find/replace corrupted six settings docs mid-run
  (caught and redone by hand). Worth a read.
