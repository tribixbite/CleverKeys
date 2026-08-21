# HANDOFF — updated 2026-08-21

Read this first, then `docs/specs/ctc-architecture-and-multiscript-guide.md` (architecture,
routing rule, multi-script recipe, full audit table). This file is the **task list**; the guide is
the **reference**. Where they overlap, the guide wins on technical detail and this file on
priority.

**Completed work is DELETED from this file, not struck through.** Git history is the record of
what was done; this file is only what is left. Anything below is open.

## State at `d5e82e77`

Swipe is **CTC (default) + geometric**; the neural engine was deleted 2026-08-18
(`a7d03bc8`..`83220634`), −26.4 MB APK. `CtcLanguageSupport.SUPPORTED` is **seven** languages:
en/fr/de/es test-validated, it/pt/sv `PROVISIONAL` (scale-transferred, no per-language bar).
Gates: `runPureTests` **1696**, `lintDebug` 0 errors, both compiles, `assembleRelease` clean.
Last full instrumented run 1395 tests / 0 failures; targeted contraction runs 127/0 on
Pixel7 API 34.

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

### 2. Context-LM rescoring of the CTC slate — steps 1 and 3 done, feature inert

**Landed**: `SwipeContextRescorer` (log-linear math, identity-at-empty-stores, rank-1
displacement guard, 16 pure tests) and the full `swipe_context_rescoring` pref plumbing, default
OFF. Nothing calls the rescorer yet.

**Next, in order**: step 2 (gated boost accessor on `WordPredictor` returning per-word
`ContextContinuation?`, M2 fail-closed gating inside, non-loading fast path), then step 4 (wire
the seam at `handleSwipePredictionResults` behind the privacy gates, engine-slate-only, with
provenance meta), then step 5 (the offline replay harness).

**The default may not flip without step 5's evidence** — §7.3 bar is net top-1 gain with
promotion errors well under errors fixed. That is a release decision, not a code change.

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

- **The collision-warning dialog has never been SEEN.** Its logic is instrumented-tested, but the
  dialog only appears when an imported language pack contributes a collision, and no pack is
  installed on the emulator — so the pack-collision path cannot be reached on emulator.wtf at all.
  Needs a device with a pack imported (nl is the bundled-adjacent one) alongside a bundled
  language, then a language re-selection to trigger the scan.
- **Manual, on the maintainer's device**: Italian swipe (moved neural→geometric→CTC in one day);
  first-swipe warm-up now that neural preload is gone; a pre-v1.6.0 backup import (no `neural_*`
  rows written); a pre-v1.1.86 upgrade (`migrateToLanguageSpecific` moved into
  `DictionaryManager.init`, ordering test-pinned but the migration untested end to end).
- **`docs/ARCHITECTURE_MASTER.md` §1/§4/§5** and the data-flow diagram were substantially
  rewritten from code by an agent whose bulk find/replace corrupted six settings docs mid-run
  (caught and redone by hand). Worth a read.
