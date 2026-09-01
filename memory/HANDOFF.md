# HANDOFF — updated 2026-09-01

Read this first, then `docs/specs/ctc-architecture-and-multiscript-guide.md` (architecture,
routing rule, multi-script recipe, full audit table). This file is the **task list**; the guide is
the **reference**. Where they overlap, the guide wins on technical detail and this file on
priority.

**Completed work is DELETED from this file, not struck through.** Git history is the record of
what was done; this file is only what is left. Anything below is open.

## Active state after local implementation commit `5fb58037`

Local `main` contains five campaign implementation commits beyond `origin/main`: `b16d9dd9`,
`1e2cc2a0`, `395c8341`, `7c2628f1`, and `5fb58037`. This handoff consolidation is committed
immediately after the implementation commit. Preserve these local commits: neither commit was
pushed. The older remote snapshot `refs/wip/campaign-20260830-1` at `0f0bc835` predates them.

**Implemented in `5fb58037`:** ARC-055 Greek wiring; ARC-062/090 release metadata;
ARC-065/066; ARC-086/088; ARC-091/092/093/094/095; ARC-074; ARC-058/064/077 test coverage;
ARC-087 structured/resource-backed provenance; ARC-089; ARC-100/101/102; and ARC-076 fixture
relocation plus deletion of `tools/test_cli_predict.ts` and `scripts/swipedata_metrics.py`.
ARC-030 was already closed in local history. The 21 locale files currently contain only the
ARC-066 description reword from this wave; ARC-067 remains open.

**Verification evidence:** the final guarded suites pass on the exact implementation commit:
`runPureTests` reports 2,087 tests and `runMockTests` reports 343 tests. Focused suites also
passed: KeyModifierMemo 7, archive-limit mock 19, KeyboardGeometry 15, provenance 12,
NextWordPredictor 28, and GeoLocalCorpusReplay 2 (heavy replay skipped honestly, fixture test
ran). `processDebugResources` and the complete `compileDebugAndroidTestKotlin` source set passed
after ARC-087. All new instrumented behavior still needs Wave J.

**Termux editing/build notes:** `apply_patch` is unusable here because bwrap cannot read
`/proc/sys/kernel/overflowuid`; changes were applied as reviewed unified diffs with `git apply`,
leaving the index untouched. Continue to use `scripts/gradle-guard.sh` for every Gradle call.

**Handoff boundary:** no implementation item is intentionally left half-applied. ARC-067 was
investigated but no translation batch was written; the locale diffs are only the completed
ARC-066 description change. Everything listed as open below is a clean next task, not a repair
needed to make the present source tree coherent.

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

### 0. Full-backlog campaign continuation (2026-09-01)

The live execution plan is `docs/plans/2026-08-30-full-backlog-campaign.md`. Waves A–C are
implementation-complete; device-only acceptance remains in Wave J/K. Wave D has ARC-066 and
ARC-087 complete, with ARC-067 still open. Wave E has ARC-089 and ARC-076 complete; its other
items remain open. Implementation is committed locally at `5fb58037`; this documentation
consolidation follows it. Nothing was pushed, tagged, or released.

**Release/maintainer gates:**
- ARC-053: minified release soak. ARC-062 and ARC-096 are implemented, but the soak remains.
- ARC-054: decide whether release notes announce ru and newly wired el. Both remain in
  `SERVED_BUT_NOT_YET_ANNOUNCED`; do not advertise Greek synthesis-holdout evidence as accuracy.
- ARC-063: narrow blanket R8 keeps only after the first minified soak.
- Nonzero `finger_occlusion_offset` default still needs maintainer device-trace A/B evidence.

**Highest-value executable work:**
- ARC-067: translate the common 384-entry missing default-resource set into all 21 locales.
  The mode description is translated everywhere, but the 39 new provenance resources and the
  earlier ARC-045/wave strings still fall back to English. Google/SimplyTranslate/Lingva public
  endpoints were unavailable; MyMemory worked but cannot cover the volume under anonymous quota.
  Preserve every `%N$` placeholder and plurals element shape; do not paste English copies.
- Wave E remainder: ARC-073 doc/citation drift and micro-bucket, ARC-098 phantom-`keyboard2`
  tooling sweep, the four verified-doc-claim audits, and deletion of the zero-reference
  `contraction_pairings_cleaned.json` after a gate run.
- ARC-072 slice 3: Initializers → `wiring/KeyboardComponentGraph`, folded with the gesture/
  reorg portion of ARC-098. Then continue later Config/SettingsActivity decomposition slices.
- Evidence-gated geometric OQs ARC-027/028/029: do not change defaults unless local-corpus replay
  shows a non-regressing improvement. ARC-030 floors are already present.
- Web: ARC-071 Astro 6 migration and ARC-046 regression gate + Tailwind vendoring.
- ML repo: ARC-056 lexicons/langpacks, ARC-060 ru layout regeneration, ARC-061 golden-path fix.
- ARC-044 remainder: strengthen the non-curated instrumented tests without adding Truth to
  androidTest dependency configurations.

**Verification still owed:**
- Wave J full ew-cli run for the newly compiled ARC-058/064/074/077/091/092/095 coverage plus
  prior owed instrumented items. The ARC-058 rotation test is the second-session memory gate.
- Wave K on both authorized phones per the plan: preserve/restore current IME and properties;
  never framework-restart Saga. Includes ARC-068/069/070 evidence.
- Final Wave L ledger/HANDOFF consolidation and maintainer-input report.

**Do not reopen as pending:** ARC-055 wiring, ARC-059, ARC-062, ARC-065, ARC-066, ARC-074,
ARC-075, ARC-076, ARC-086–096 (except maintainer/device gates above), ARC-099–102 are implemented
in local commits. ARC-087 extraction is implemented; its locale work is intentionally counted
under ARC-067.

### 1. Contraction follow-ups, all deferred deliberately

- **Owed translations** for `collision_warning_title/body/examples` — English-only behind
  `tools:ignore="MissingTranslation"`.
- **RESOLVED 2026-08-29 — the user-word guard is now case-TOTAL** via a read-side fold
  (`DictionaryManager.isUserWordIgnoringCase`, invalidated on every set mutation); the persisted
  `userWords` keeps its exact case-sensitive add/remove/dedup semantics, pinned by
  `ContractionUserWordGuardTest.storedUserWordsStayCaseSensitive`. Commit
  `fix(contractions): make the user-word REPLACE guard case-total via a read-side fold`.
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
- Translation debt is consolidated under ARC-067: the same 384 default entries are missing
  from every locale, including `pref_secondary_prediction_weight`, backup/private-copy copy,
  pack refusal reasons, and the new provenance sheet. `swipe_engine_mode_desc` itself is now
  deliberately dynamic and translated in all 21 locales; `processDebugResources` passes.
- `finger_occlusion_offset` ships at default 0. A nonzero default needs a device-trace A/B at
  {0, 8, 12.5, 16}% — the old 12.5% was never measured anywhere in this repo's history.

---

## The geometric-removal question

The **language** dimension is closed for Latin — including, since `05c0c25d`, the imported-pack
cell that used to be the loudest counterexample: a user with `langpack-nl.zip` got geometric for a
language CTC decodes fine. **Cyrillic is now half-open**: the wiring is generic and Russian is
routed. What remains is the other five scripts' lexicons, and layout.

Layout census (`src/main/layouts/`, 86 XML — the tree `copyLayoutDefinitions` ships;
`srcs/layouts/` is divergent and read by no build task):

| bucket | count | routing |
|---|---|---|
| `script="latin"` and a–z-complete | 46 | CTC — for the eight table languages AND for any imported Latin pack that measures a–z-typeable (`05c0c25d`); a pack that does not (Turkish: 73 %) stays geometric, deliberately |
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

### Imported Latin packs — `05c0c25d`, 2026-08-29

`CtcLanguageSupport.sourceFor` consults the static table first and the installed packs on a miss,
so an imported pack that measures a–z-typeable resolves to `CKDT_LANGPACK` and everything
downstream follows with **no new branch** — dispatcher, prewarm, `presetFor`'s λ,
`hasLexiconSource`, settings card. The precedent is it/pt/sv verbatim: the encoder never sees a
language, and λ calibrates to the lexicon's frequency SCALE, which an imported pack shares exactly
with the bundled six.

The gate is **measured a–z projectability**, not a manifest field (the manifest has no script), and
it is not ceremonial. Over every `scripts/dictionaries/langpack-*.zip`: nl/id/ms/sw/tl **100.00 %**,
ru/el **0.00 %**, tr **73.34 %** overall and **81.7 %** of the frequency head — dotless `ı` has no
NFD decomposition, so a quarter of Turkish cannot be spelled on an a–z board. Those words ARE
typeable on geometric, so serving Turkish would be a regression, not a gap. Thresholds 0.98 / 0.99
(head, top 1,000 by rank) sit in a 25-point empty band; the 1,000-word floor is a power floor on
the ratio.

State to respect: the verdict is cached in the single pref `ctc_langpack_verdicts`, keyed by the
pack file's length+mtime, and an UNMEASURED pack answers false and schedules the read — so the
first swipe after an import can go to geometric and every later one to CTC. `INTERNAL_KEYS`
excludes the pref from backups (a per-device measurement of per-device files). Tier: `PROVISIONAL`
by construction and permanently; **no accuracy number may ever be quoted for an imported pack**,
because there is no corpus and not even a fixed vocabulary.

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

**Still true of every SCRIPT including ru**: the 32-frame budget has never been checked against a
real script lexicon. `CtcDecodableLength` computes it and a test covers `en_enhanced.json`; no
script pack has been swept, and Greek and Ukrainian carry long inflected forms. A word over
budget is unemittable with no error. **The Latin half of that question is now closed**
(`CtcImportedPackSupportTest`, 2026-08-29): zero words over budget in nl/id/ms/sw/tl, worst case
`gemeenteraadsverkiezingen` at 27 of 32 frames — which is why imported-pack eligibility gates on
spelling and not on length. The sweep for the script packs is the same six lines of test.

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

- **Added by waves R1+R2 (2026-08-29), for the NEXT ew-cli run**: `PointersGestureRoutingTest`
  T13 (`configChangeMidGesture_doesNotAffectTheGestureInFlight`); the ARC-083 rider's
  `onDecodeFailure` assertion in `CtcMultiLanguageInstrumentedTest`; an instrumented pass
  through the injectable `userDictionarySource` seam (ARC-081 adapter wiring is source-scan
  pinned only today); an end-to-end typing test for the case-total user-word guard.

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
- **The imported-pack CTC path has never run on a device.** `05c0c25d` ships it and every
  hardware-free gate is green. The next ew-cli run must confirm
  `CtcImportedPackInstrumentedTest` (4 cases, written 2026-08-29, never executed): it builds a
  real CKDT v2 pack for the unassigned code `zz`, imports it through the shipping
  `LanguagePackManager`, and checks (1) the file lands where `candidateLangpackRelativePath` says,
  (2) `CtcInstalledPacks` measures it out of the real `filesDir` and the verdict reaches the
  STATIC `CtcEngineAdapter.supportsLanguage` gate, (3) the production merge path builds a trie
  from it, (4) deleting the pack unserves the language in the same process, and (5) a reimport
  with different content is re-measured. Watch for: `Uri.fromFile` through `contentResolver`
  (works in-process, but it is the one assumption that is device-shaped), and the trie memo
  evicting at `size > 2` if a pack language is active alongside two others.
- **The collision-warning dialog has still never been SEEN — but its precondition is now
  reachable in CI.** `CtcImportedPackInstrumentedTest.theCollisionScanSeesAnImportedPacksContractions`
  builds a fixture pack whose `contractions.json` rewrites a real English word (`were` → `we're`)
  and asserts `ContractionCollisionScanner.scan(…).hasPackCollisions`, which is the exact branch
  the dialog gates on and which no emulator could reach before (none has ever had a pack
  installed). What is still owed on the maintainer's device is the DIALOG itself — Compose
  rendering after a language re-selection with a real pack (nl is the bundled-adjacent one)
  alongside a bundled language.
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
