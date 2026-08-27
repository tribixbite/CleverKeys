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
Gates: `runPureTests` **1742**, `lintDebug` 0 errors, both compiles, `assembleRelease` clean.
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

### 2. Context-LM rescoring of the CTC slate — steps 1-4 done, feature reachable but OFF

**Landed**: `SwipeContextRescorer` (log-linear math, identity-at-empty-stores, rank-1 score-ratio
guard AND the strict `NextWordPredictor` evidence floors); the non-loading store peeks +
`ContextModel.getContextEvidence`/`boostFor`; the M2-gated `WordPredictor.getSwipeContextEvidence`;
the `swipe_context_rescoring` pref (default OFF) with its settings toggle; and the seam at
`SuggestionHandler.handleSwipePredictionResults` behind all four §3 gates, with provenance on a
context-promoted rank 1.

Verified with the pref OFF on-device: 90 instrumented tests unchanged, incl. the full 62-test
`TypingSimulationTest`.

**The step-5 plan is written up**: `docs/plans/2026-08-22-context-rescoring-step5-harness.md`
— stages, the corpus research with verified licences, the Discord position, and the honest
limits to state in the results. Read it before starting.

**Measured 2026-08-21**: the maintainer's device export holds 6,589 en pairs, but **only 642
(9.7%) clear the store's `minFrequency=2` floor** — 90% are hapax and contribute zero boost.
The usable context model is ~642 pairs, so one user's data gives a THIN signal. Any plan that
assumed 6.5k pairs of evidence is wrong by 10x.

**Step 5 is BUILT; the CTC arm has RUN on TWO corpora (re-baselined 2026-08-26 at `27eb1a11`
after the decoder moved twice) — the answer is NO.** Full writeup:
`docs/eval/2026-08-22-context-rescoring-first-replay.md` (§8 is the retraction ledger, 13 entries).

**CTC — the shipping default — fails the bar on BOTH corpora:**

| CTC @ `27eb1a11` | device export (no eviction) | Ubuntu |
|---|---|---|
| fixed / broken | **0 / 6** | **3 / 2** |
| errRatio (bar <0.20) | INFINITE | 0.667 |
| Δtop-1 (bar >0) | -0.0016 | +0.0005 |
| tune -> confirm sweep | no point clears on tune | best point LOSES on held-out: 1 fixed / 2 broken |

Geometric passes on both and its tuned point survives held-out (35/0 Ubuntu -> confirm 26/0; 22/0
device -> confirm 21/0). Strongest fact: on the device corpus — the only one whose activation rate
is real (6,589 pairs, zero eviction, 262/262 queryable) — rescoring fixed **nothing** (0 of 481) and
broke 6. CTC was already correct on 469/481, so its whole headroom is 12 cases.

**RESOLVED — the fuzzy-rescue knife-edge** (`c83d6ff2`, by the flag's owner). Verified in code:
`CtcFuzzyRescue.mergeIntoBeam` appends rescues BELOW the beam into spare TOP_K slots, scored
`minOf(lastReal-1, (top-1)/2)`; checked at top=884/913/800, all below `R_MIN*top`. 12 tests pin it.
Better than the note claimed: **the hand-copy is gone** — adapter and `CtcReplayEngine` now call one
pure function, so that drift class cannot recur. `436911d9` does not touch the harness (it changes
`rescoreWithContext`; the replay calls `rescoreOrder`, signature intact).

Its effect on the eval was total: both corpora reverted EXACTLY to their pre-`20d620f4` figures
(median runner-up ratio 0.500 -> 0.261 device / 0.244 ubuntu). The old "peakedness protects CTC"
explanation is retired; §3 of the doc now records the whole episode.

**`c83d6ff2` also fixed a defect the adversarial audit MISSED** (now H13): the old clamp inverted at
`[800,800]`, scoring a rescue 801 — above rank one. The audit verified the replay's copy matched the
shipped copy and passed it. Checking that a mirror matches is not checking the thing it mirrors.

**NEW: a drift canary** (`27eb1a11`). `CtcReplayEngineSmokeTest.slateShapeHasNotDrifted` pins the
fraction of slates with a runner-up at/above `R_MIN*top-1` — the guard's own precondition and the
quantity the whole eval is a function of. 6/40 = 0.150 at HEAD, band ±0.10; `20d620f4` would have
read ~0.54. A red canary means RE-BASELINE, not "decoder broken". Skips without the local corpus.
Verified it can actually fail (forced red; that caught a `.format()`-binding bug in its own message).

**Three earlier conclusions of mine are RETRACTED**: "the strict floors never bind" (they bind on
device data — 2 CTC, 7 geometric; vacuous only on a saturated store, so KEEP them); "all 24 breaks
are one trace" (an H9 artefact — now 2 breaks/2 traces on Ubuntu, though device still concentrates
at 6/1, so concentration is corpus-specific, not a law); and every number published before
2026-08-26.

**Harness defects found since the audit, all fixed**: **H9** `loadTraces` created word buckets
BEFORE filtering — pool printed 4,907 when only **2,197** words have a usable trace, `pairable`
counted zero-trace bigrams, and decoys drawn from phantom keys silently shrank the adversarial arm;
**H10** English trie built with `CtcAzProjection` instead of the shipped `loadStrippingNonAlphabet`,
plus truncated scores and a non-shipping ONNX EP; **H11** the decoder changed under the measurement;
**H12** the floors over-generalisation; **H13** above.

**The `-PreplayMaxCtx` power run RAN (2026-08-26) and is a NEGATIVE result.** It was this file's
own proposed remedy for the harness's binding statistical limit; it does not work. Device CTC,
decoys 10->30 with maxCtx=2: distinct adversarial traces 926 -> 1615 (+689), but EXPOSED distinct
traces 107 -> **111** (+4); exposure per distinct trace 11.6% -> 6.9%. The bottleneck was never
multiplicity — the set of trace-pool words that can be exposed at all against this corpus is small
and now **nearly enumerated at ~111**, of which exactly **one** breaks. Sampling harder cannot lift
it. Honest safety statement: **1 of ~111 exposed distinct adversarial traces breaks (~0.9%)** — at
least a rate in a coherent unit now, instead of "6 breaks" that were one trace counted six times.

What WOULD add power (not built, recorded so the next attempt doesn't repeat this one): draw decoys
confusable with any **hub continuation** — the function words (`the`, `to`, `a`, `and`, `it`) that
are learned continuations of a large share of preceding words; ~35% of usable device pairs point at
a top-10 hub — rather than only with the pair's own `word2`. That targets the damage surface
directly instead of sampling around it.

**DECIDED 2026-08-26 (maintainer): the investigation is CLOSED — no shadow mode.** Rescoring
stays default-OFF permanently. The pivot: the learned context data's consumer is **next-word
prediction** (tap-to-accept suggestions, where a wrong suggestion costs nothing) instead of swipe
rescoring (silent auto-insert, where the same signal measurably overturned correct decodes). The
2026-08-26 next-word audit landed: the `context_aware_predictions_enabled` gate added to
`NextWordPredictor.shouldShow` as a REQUIRED param + the cursor-park editor read's cheap-gate set
(it read editor text in a state where no candidate could surface); the next-word settings toggle is
now VISIBLE-BUT-DISABLED (not hidden) when the context LM is off, so a stale-on pref always has a
visible owner; search entries for all eight Advanced-Prediction-panel controls now open the panel
(they previously landed nowhere); all pinned by drift tests (`LearningWiringDriftTest`,
`SettingsSearchCoverageTest.advancedPanelSlugSetMatchesThePanelContents`).

Still open if anyone resumes the rescoring question: a hub-confusable decoy rule (offline safety
bound); a second language.

**Was: Step 5 is BUILT; its numbers were WRONG TWICE before an audit corrected them** —
current then: 51 fixes / 0 breaks in 243 exposed favourable cases (~21% when it fires), safety
underpowered (15 adversarial exposures). Superseded: that run was geometric-only AND contaminated
by H5.

**Was: Step 5 is BUILT and has produced first numbers** — benefit real (29 fixes, 0 regressions,
~19% fix rate); safety not established. **The feature is inert on ~97% of swipes.** Owed: a CTC arm
(believed to need an instrumented run — WRONG, `extractOrtNative` unblocked pure JVM), a larger
adversarial sample, a WEIGHT tune/confirm split.

**Was: ONLY step 5 remains, and it is the blocker for everything else**: the offline context replay
harness (§7.1) — replay a sentence corpus through `ContextModel.recordCommit`, draw matching FUTO
traces, decode through the shipping adapter, apply the rescorer, and report Δtop-1 AND the
promotion-error rate separately. `scripts/ctc_injection_ab.py` is the worked example to extend;
reuse its metric lesson — score by CORRECTNESS against the target, never by the shape of the
change.

**No context corpus exists in this repo.** Every replay corpus is context-free isolated words
(FUTO rows are `{word, trace}`; the local replay emits `{word, w, h, pts}`; `ctc_golden.json` is
decoder parity). Sourcing one is step 5's first task, not an afterthought.

**The default may not flip without that evidence** — §7.3's bar is a net top-1 gain with promotion
errors well under errors fixed. That is a release decision, not a code change.

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
- **Manual, on the maintainer's device**: Italian swipe (moved neural→geometric→CTC in one day);
  first-swipe warm-up now that neural preload is gone; a pre-v1.6.0 backup import (no `neural_*`
  rows written); a pre-v1.1.86 upgrade (`migrateToLanguageSpecific` moved into
  `DictionaryManager.init`, ordering test-pinned but the migration untested end to end).
- **`docs/ARCHITECTURE_MASTER.md` §1/§4/§5** and the data-flow diagram were substantially
  rewritten from code by an agent whose bulk find/replace corrupted six settings docs mid-run
  (caught and redone by hand). Worth a read.
