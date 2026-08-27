# HANDOFF — updated 2026-08-27

Read this first, then `docs/specs/ctc-architecture-and-multiscript-guide.md` (architecture,
routing rule, multi-script recipe, full audit table). This file is the **task list**; the guide is
the **reference**. Where they overlap, the guide wins on technical detail and this file on
priority.

**Completed work is DELETED from this file, not struck through.** Git history is the record of
what was done; this file is only what is left. Anything below is open.

## State at `ececaa73`

Swipe is **CTC (default) + geometric**; the neural engine was deleted 2026-08-18
(`a7d03bc8`..`83220634`), −26.4 MB APK. `CtcLanguageSupport.SUPPORTED` is **seven** languages:
en/fr/de/es test-validated, it/pt/sv `PROVISIONAL` (scale-transferred, no per-language bar).
Gates: `runPureTests` **1778**, `lintDebug` 0 errors, both compiles, debug+androidTest APKs build.
Last full instrumented run (ew-cli, Pixel7 API 34, 2026-08-27, at `ececaa73`): **1,418 tests,
3 red — all explained, none a code regression**: 1 stale test (`SettingsSearchTest` asserted the
neural-era "Fuzzy Match Algorithm" control was findable; control deleted 2026-08-18, test
repointed at "Typo Forgiveness" and re-verified green 7/7 on-device) + 2
`CtcOnnxLatencyBenchmarkTest` reds that are BY DESIGN whenever the ctc_bench models are not
staged (`3fcbf7b8` removed the 11 MB duplicates from the repo; the benchmark fails loudly
rather than silently skipping — restore via `cp` from CleverKeys-ML/ctc/artifacts/ into
src/androidTest/assets/ctc_bench/ only when actually benchmarking). Expect those 2 reds in any
full run; do not chase them.

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
- Translations owed: `swipe_context_rescoring_*`, `collision_warning_*`, `swipe_engine_fallback_*`, `gesture_touch_smoothing_*`,
  `gesture_finger_occlusion_*`, `dict_word_too_long_for_swipe_*` ship English-only behind
  `tools:ignore="MissingTranslation"`. The 21 `swipe_engine_mode_desc` translations were
  machine-extended and want a native reviewer.
- `finger_occlusion_offset` ships at default 0. A nonzero default needs a device-trace A/B at
  {0, 8, 12.5, 16}% — the old 12.5% was never measured anywhere in this repo's history.

---

## The geometric-removal question

The **language** dimension is closed — all seven bundled dictionary languages run on CTC. What
remains is script and layout.

Layout census (`src/main/layouts/`, 86 XML — the tree `copyLayoutDefinitions` ships;
`srcs/layouts/` is divergent and read by no build task):

| bucket | count | routing |
|---|---|---|
| `script="latin"` and a–z-complete | 46 | CTC |
| `script="latin"` but a–z-incomplete | 2 | geometric, via the alphabet gate |
| non-Latin declared (15 scripts) | 36 | geometric at gate 1 |
| no `script` attribute | 2 | `numeric.xml`, `pin.xml` — not letter layouts |

**"Non-Latin can only be served by geometric" is false.** Per-script models are required — a
Latin-trained encoder does not zero-shot another script, because motor statistics and the learned
character-transition prior are trained even though geometry is an input — but they are cheap and
proven: ~94k steps of `resbn:80`, under an hour on one GPU, from a word list and layout geometry
alone. `phaseIB-ru-synth` saw zero real Cyrillic rows and decoded real Russian at 77.41 in-dict
top-1; the generation-4 successor (`ru_synth_v3_ch80`, learned-generator synthesis, Phase Q)
reads **85.07** on the same eval-only probe.

Equally, "the ALPHABET is hardcoded a–z" is true of two constants in one adapter file and false
of everything else: the model has no alphabet (64 geometry-conditioned slots plus blank,
`keyEmbed` a function of `(cx, cy)` never of slot index), and `CtcLayout`, `CtcLexiconTrie`
(bounded by `MAX_KEYS`=64) and `CtcEmissions.sliceFromHead` are already script-generic.

**Russian is delivered** (updated 2026-08-25 — the 2026-08-18 paragraph named the generation-2
bytes, since twice superseded): the current ship bytes are
`CleverKeys-ML/ctc/artifacts/ru_synth_v3_ch80_fp16w.onnx`, 589,406 B, sha `8fffa75c…`, at
**85.07** in-dict top-1, plus its golden fixture `ru_synth_v3_ch80_fp16w_golden.json`. All six
scripts (ru/el/uk/bg/mk/he) now have uniform `_v3_` generation-4 artifacts; hashes in
`CleverKeys-ML/ctc/APP_WIRING_CHECKLIST.md` §2.2 and the app plan
`docs/plans/2026-08-25-ctc-multiscript-wiring-plan.md`.

### The multi-script wiring plan — `CleverKeys-ML/ctc/PHASE_O.md` §3

Six scripts specified end to end (ru, el, uk, bg, mk, he) with per-script alphabets, K, lexicon
status and presets. Read §3.2 before designing anything. The essentials:

- **Slot order IS the alphabet array**, codepoint-sorted; emission column `c` is `letters[c]`. A
  mismatch **silently permutes every decode** rather than failing. Sharpest footgun in the plan.
- **The geometry needs no app-side change** — `app_layout.py` reproduces `en_qwerty.json` from the
  app's own XML to 4.7e-4.
- **Eight changes enumerated; two already done** — the trie width (`d671d19e`) and `CtcLayout`'s
  generic alphabet. Live: per-script `ALPHABET`, per-script routing, a per-language model asset
  (`MODEL_ASSET` is one constant), runtime-extensible `SUPPORTED`, a reachable `tunedRuCkdt`
  (exists but `presetFor` can never return it), a per-script fixture↔model↔preset row.
- **Per-script projection rules the app must mirror** (§3.4): all scripts lowercase and strip
  `- ' ’ ʼ ‘ \``; el/he NFD + drop marks + NFC; **ru/bg/mk must NOT use NFD** (it decomposes
  й into и + breve) — character folds instead; el word-final σ→ς; uk rejects ї/ґ words (4.03%).
- Lexicons: ru importable today, el needs the sigma repair (shipped in `6f30d60f`, not yet
  wired), **uk/bg/mk/he must be built**, and he needs a new `hebrew` branch in
  `build_wordlist._is_script_word`.

Geometric is removable **script by script**, ~an hour of GPU each plus wiring. It cannot be
removed first: deleting it today does not downgrade a Cyrillic user, it removes their swipe.

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
