# HANDOFF — updated 2026-08-20

Read this first, then `docs/specs/ctc-architecture-and-multiscript-guide.md` (architecture,
routing rule, multi-script recipe, full audit table). This file is the **task list**; the guide is
the **reference**. Where they overlap, the guide wins on technical detail and this file on
priority.

**Completed work is DELETED from this file, not struck through.** Git history is the record of
what was done; this file is only what is left. Anything below is open.

## State at `98307dc2`

Swipe is **CTC (default) + geometric**; the neural engine was deleted 2026-08-18
(`a7d03bc8`..`83220634`), −26.4 MB APK. `CtcLanguageSupport.SUPPORTED` is **seven** languages:
en/fr/de/es test-validated, it/pt/sv `PROVISIONAL` (scale-transferred, no per-language bar).
Gates: `runPureTests` **1678**, `lintDebug` 0 errors, both compiles, `assembleRelease` clean.
Last full instrumented run 1395 tests / 0 failures.

---

## Open work, in priority order

### 1. Contraction follow-ups, all deferred deliberately

- **Cross-language contraction destruction is FIXED, with residuals.** The tap path merged every
  active language's REPLACE keys into one map with no provenance, so a key with no reading in one
  language was applied to a real word of the other. Measured casualties, all live before
  2026-08-20: fr+en typing French `dont` got `don't`; de+en typing German `im` got `I'm`; de+en
  typing English `hats` got `hat's`. `im` was destroyed in EVERY non-English bundled language.
  Fix: `ContractionCollisionDemotion` + shipped `contraction_collisions_<lang>.json` sidecars,
  demoting a colliding key to PAIRED so both spellings stay reachable. `rendezvous` ships as a
  result. Worst pair is fr+en at 158 demotions of ~18k keys (<1%).
  **Residuals, both untested:** an imported language pack has no sidecar, so an uncurated pack can
  still destroy the other language's words on the typing path (the swipe path keeps the overlay's
  rank guard); and a user's own custom words are invisible at generation time — hardening would be
  to skip the transform when `DictionaryManager.isUserWord(word)`, which is reachable at that call
  site. Neither is verified on a device yet.
- **Verb inversions** (`est-elle`, `a-t-on`) deferred with named landmines: `estelle` is a native
  word @16343, `aton` is ASK-attested, `entretemps` is a classifier misfire needing
  `FORCED_APPEND`.
- **German injection stays mostly inert** even after `98307dc2`: de's rarest real word is freq 12,
  so the derived floor is 11 and the headroom is small. Scale-specific, not a bug.

### 2. Context-LM rescoring of the CTC slate — design done, build not started

`scripts/ctc_injection_ab.py` is now the worked example of extending a replay harness with a new
dimension — reuse its shape rather than starting over. Note especially its metric lesson: score
by CORRECTNESS against the target, never by the shape of the change. Its first version called a
fix a regression because the right answer happened to be an injected key.

`docs/specs/ctc-context-rescoring-and-tunables.md` has the full plan: hook at
`SuggestionHandler.handleSwipePredictionResults` (both engines benefit), log-linear within-slate
combination, privacy-gated on `LearningGate`, default-OFF, rank-1 displacement bounded.

**Do not enable by default without evidence, and the evidence does not exist yet** — every replay
corpus in the repo is context-free isolated words, so step 5 of that plan is "build the harness".

### 3. Smaller, ride-along

- ~~the four `ctc_bench` models may never have been packaged into the androidTest APK~~ —
  **disproven 2026-08-20, by experiment.** androidTest assets ARE packaged: the existing
  `assets/ctc_bench/README.md` is present in `CleverKeys-debug-androidTest.apk` at its full
  1,182 bytes, as are `assets/ctc/ctc_golden.json` and `assets/dictionaries/en_enhanced.json`.
  Confirmed positively by dropping a 1 MB probe `ch128_s1234.onnx` into the directory and
  rebuilding: it appears in the APK at `compressed == uncompressed == 1000000`, because
  `build.gradle:136` sets `noCompress 'onnx'`. So 11 MB of models adds ~11 MB, and the
  258-byte delta can only have come from a build where they were already absent (they are
  gitignored and were never committed, so any fresh checkout or CI build lacks them).
  Nothing to fix. `CtcOnnxLatencyBenchmarkTest` already fails with restore instructions rather
  than skipping, so the benchmark cannot silently no-op — restore per the README to run it.
- ~~`CoroutineScopeLifecycleTest` flakes in combined runs~~ — **disproven 2026-08-20.** It passed
  4/4, including a full combined run and three isolated runs under heavy CPU oversubscription.
  Its only output is the deliberate "boom" stack trace, which is almost certainly why it was
  blamed — it is the loudest thing in a red suite log. Nothing to fix; **read the
  `N) <test>(<class>)` failure header, never the noisiest stack trace.**

  ~~The real flake is `GeoBenchmarkTest`'s NFR-1 wall-clock p95~~ — **also wrong, and the
  correction is the useful part.** That investigation measured warm p95 at 40.61 / 56.58 / 59.36 /
  63.13 / 64.72 / 68.40 / 70.09 / 86.40 ms against a 60 ms budget, concluded the pure suite could
  not measure a tail statistic, and landed a heap-settle, a best-of-two retry, and a demotion of
  the p95 assert to measured-and-printed. **All of it has been reverted.** The premise was an
  artefact: that session leaked 23 `while :; do :; done` CPU burners from its own load tests, so
  the 4-core device ran at load average 36+ for every measurement. Its "clean box" check grepped
  for `java` processes and could not see them by construction.

  Re-measured with the burners killed: isolated warm p95 **8.46 / 10.26 / 8.52 ms**, inside the
  full 1,678-test suite **9.51 ms**, medians 2.7–3.2 ms against 30. No suite inflation, ~6x
  headroom. `GeoBenchmarkTest` is healthy and the NFR-1 asserts stand as written.

  Carry forward: verify a quiet machine by LOAD, via `uptime` — it reads `sysinfo()` and works on
  Android even though `/proc/loadavg` is SELinux-denied (which is separately why the JVM's
  `getSystemLoadAverage()` returns -1 here). Grepping for one process name is not a quiet check.
- Translations owed: `swipe_engine_fallback_*`, `gesture_touch_smoothing_*`,
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
alone. `phaseIB-ru-synth` saw zero real Cyrillic rows and decodes real Russian at 77.41 in-dict
top-1.

Equally, "the ALPHABET is hardcoded a–z" is true of two constants in one adapter file and false
of everything else: the model has no alphabet (64 geometry-conditioned slots plus blank,
`keyEmbed` a function of `(cx, cy)` never of slot index), and `CtcLayout`, `CtcLexiconTrie`
(bounded by `MAX_KEYS`=64) and `CtcEmissions.sliceFromHead` are already script-generic.

**Russian is delivered**: `CleverKeys-ML/ctc/artifacts/ru_synth_ch80_fp16w.onnx`, 589,406 B, sha
`84ac284d…`, plus its golden fixture.

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

- **Manual, on the maintainer's device**: Italian swipe (moved neural→geometric→CTC in one day);
  first-swipe warm-up now that neural preload is gone; a pre-v1.6.0 backup import (no `neural_*`
  rows written); a pre-v1.1.86 upgrade (`migrateToLanguageSpecific` moved into
  `DictionaryManager.init`, ordering test-pinned but the migration untested end to end).
- **`docs/ARCHITECTURE_MASTER.md` §1/§4/§5** and the data-flow diagram were substantially
  rewritten from code by an agent whose bulk find/replace corrupted six settings docs mid-run
  (caught and redone by hand). Worth a read.
