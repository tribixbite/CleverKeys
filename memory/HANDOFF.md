# HANDOFF — updated 2026-08-27

Read this first, then `docs/specs/ctc-architecture-and-multiscript-guide.md` (architecture,
routing rule, multi-script recipe, full audit table). This file is the **task list**; the guide is
the **reference**. Where they overlap, the guide wins on technical detail and this file on
priority.

**Completed work is DELETED from this file, not struck through.** Git history is the record of
what was done; this file is only what is left. Anything below is open.

## State at `aadb45d3`

Swipe is **CTC (default) + geometric**; the neural engine was deleted 2026-08-18
(`a7d03bc8`..`83220634`), −26.4 MB APK. `CtcLanguageSupport.SUPPORTED` is **eight** languages:
en/fr/de/es test-validated, it/pt/sv `PROVISIONAL` (scale-transferred, no per-language bar), and
**ru** `VAL_ONLY` since 2026-08-29 (`1561dbaf`, `da012ded` — the first non-Latin script; see the
geometric-removal section below for what is and is not established about it). **The table is no
longer the whole membership**: since `05c0c25d` an imported LATIN language pack that measures
a–z-typeable is served too (`CtcImportedPackSupport`), so `SUPPORTED.keys` is a lower bound and
`CtcLanguageSupport.sourceFor`/`isSupported` is the answer.
Gates: `runPureTests` **2006**, `runMockTests` **330** as of wave R1 2026-08-29 (was 1954/325
at `aadb45d3`), `lintDebug` 0 errors, both compiles;
`assembleRelease` builds minified (R8 on since `37ed9804`) and byte-deterministic.
Last full instrumented run (ew-cli, Pixel7 API 34, 2026-08-29, run `30e9cd42` at `20ef0dae`):
**1,449 tests, 6 red — all explained, none a code regression**: the 2 permanent by-design
`CtcOnnxLatencyBenchmarkTest` reds (ctc_bench models unstaged, `3fcbf7b8` — expect them in every
full run, do not chase) + 4 `TermuxDeletionInstrumentedTest` reds caused by the TEST harness
(a directly-constructed `BaseInputConnection` edits its own empty fake editable, so seeded
EditText text was invisible to every read; fixed in `aadb45d3` by overriding `getEditable()` —
re-run `76bbbeb4` is **7/7 green**, every originally-pinned DEL count correct once reads saw
real text). On-device confirmations from this run: **UT-7 fixed** (typed `id` →
`[I'd, id, idea…]`, single `I'll`, `im` leads — ARC-013's device half DONE); the ru
`CtcEmissionModelParityTest` row green (packaged Cyrillic ONNX reproduces its fixture);
`CtcImportedPackInstrumentedTest` 4/4 (pack import → CTC serving + the collision-scanner branch
finally reached on emulator); Wave K's 223 strengthened release-gate assertions all green.
CI is green on `github/main` (blocking Trivy gate operational after the SARIF-severity split).

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

### 0. The ARC backlog — full index in `docs/audit/2026-08-28-archive-verification.md`

The 2026-08-28 archive-verification pass recovered 52 findings (ARC-001..052); the 2026-08-28/29
remediation waves (A–N, commits `31685cac..b12c4365`) fixed 45 of them plus the CI security-gate
hardening, multiscript CTC (ru + imported Latin packs), R8, the reorg and the i18n extraction —
every fix cites its ARC ID in its commit. A **second line-by-line pass over all 27 archived
docs (2026-08-29, ~900 instances re-verified by symbol)** added **ARC-079..098** and corrected
two earlier ledger entries (ARC-050's annotation was false — fixed in both live docs; ARC-043
is closed). **The open set = the ledger's ARC-053..078 + ARC-079..098 sections PLUS the
unstruck earlier items ARC-027/028/029 (geo OQ backlog) and ARC-046 (web-demo regression gate +
Tailwind vendoring — both halves confirmed untouched)**; this is the priority order:

**Release-gated — must close before any v1.6.0 tag**
- **ARC-053** maintainer soak of the MINIFIED release APK (R8 on since `37ed9804`; ew-cli does
  NOT discharge this — it builds unminified debug). SCHEDULE it, don't just intend it (the M-1
  lesson). Pair with **ARC-062** (delete the dead coroutines `META-INF/services` excludes) and
  **ARC-096** (lint has never seen the release variant — flip `checkReleaseBuilds` or add
  `lintVitalRelease`) in the same soak-covered change. **ARC-090** (NOTICE must enumerate the
  ru model) rides with the ARC-054 notes decision.
- **ARC-054** release-notes decision: main serves 8+ languages (ru val-only + eligible packs),
  the notes say seven. Pinned by `SERVED_BUT_NOT_YET_ANNOUNCED = {ru}`. Note: tagging v1.6.0
  now also freezes its `docs/RELEASE_RECORD.md` section (`PENDING_RELEASES` in
  `ReleaseRecordDriftTest`).

**Decisions (cheap, one sitting each)**
- **ARC-059** `CtcLatencyGateTest` measures `CtcSwipeDecoder`, which release R8 strips (zero
  prod callers) — move it to `src/test` or repoint the gate at `CtcBeamDecoder`; pick ONE.
- **ARC-055** route Greek: two file copies + one table row + a parity run; blocked only on
  evidence-tier appetite (no Greek probe exists at any tier).

**Maintainer-device verification (consolidated)**
- **ARC-068** #79 visual pass (fix landed `df396f86`, but the defect postdates the original
  report; the v1.2.5-era inset-conflict candidate + hwui discriminator are in the ledger).
- **ARC-069** the device checklist: #148 visual, `.ckenc` export, next-word cold-start bar,
  nonzero occlusion on a geometric layout, the collision-warning DIALOG rendering, Italian
  swipe, first-swipe warm-up, pre-v1.6.0 backup import, pre-v1.1.86 upgrade.
- **ARC-070** long-run `MemoryProbe` + `dumpsys meminfo` (the unexplained 2026-08-17 OOM).

**Second-pass P3 batch — wave R1 (2026-08-29, ledger §"Remediation wave R1") closed 079, 080,
084, 085, 097.** Still open: platform UserDictionary words invisible to swipe (081);
dictionary-mutation trie-rebuild stall (082); transient CTC exception clears the bar with no
geo retry (083 — in flight); layout-axis fallback invisible + unwritten layout authoring
requirements (086); provenance sheet English-only ×21 (087); `KeyModifier.modify` unmemoized
per frame (088); geometric spec pre-regeneration tables (089 — annotate only); **ARC-099
(new)** the dead `updateSwipePredictions`/`completeSwipePredictions`/`clearSwipePredictions`
chain + its 3 `CleverKeysService` pass-throughs (~30 lines, ARC-084's exact shape).
LOW tail: ARC-091..095 (zip-slip-through-importer test, private-copy pins, legacy occlusion
import decision, learned-data preview row, SuggestionBar recycling test), ARC-098 (finish
reorg + phantom-keyboard2 tooling sweep), **ARC-100 (new)** `NON_DEFAULTED_KEYS` stale
rationale, and the `gradle.properties` missing-`-Xmx` build-infra fix (ledger §ENV).

**Backlog (agent-executable, roughly by value)**
- **ARC-067** the 21-locale translation pass (317 ARC-045 strings + wave strings + the two
  stale-content fixes; details in the ledger and §3 below).
- **ARC-064** wave-J untested edges: pack dual-decode, pack-on-non-Latin board, pack
  contractions→trie injection. **ARC-057** 32-frame sweep for BUNDLED lexicons.
  **ARC-058** trie-memo `size > 2` + second-ORT-session memory under 3-language rotation.
- **ARC-044 (rest)** ~85 non-curated androidTest classes are still assertion-weak (curated six
  done, 141→223; NOTE: do NOT add Truth to androidTest — dependency-locked configs feed the
  Trivy gate; use JUnit+messages). **ARC-074** the unexercisable `catch (Throwable)` guard.
- **ARC-071** astro 5→6. **ARC-073** doc-path drift (~25 citations) + the phantom
  `verify-production-ready.sh` paths. **ARC-075** GifPanelSection English-anchored status
  match. **ARC-076** relocate the QWERTY geometry table, then delete `test_cli_predict.ts` +
  `swipedata_metrics.py`. **ARC-066** `swipe_engine_mode_desc` reword (invalidates 21 locales
  deliberately).
- **ARC-072** — live plan `docs/plans/2026-08-29-arc072-config-snapshot-and-composition-root.md`
  (supersedes the archived R3/R5 where they disagree). Slice 1 DONE (`caee60dc`:
  `ConfigSnapshot` read-model + Gesture/GestureClassifier, `ConfigSnapshotRatchetTest` ceiling
  33→31); slice 2 (Pointers gesture-scoped + Keyboard2View frame-scoped capture) in flight;
  slice 3 = Initializer collapse into `wiring/KeyboardComponentGraph` (+ ARC-098 fold-in).
  Later: SettingsActivity's 123 `mutableStateOf`, `CleverKeysService` static escape hatches,
  Keyboard2View pref-write extraction.
- **ARC-063** narrow the blanket Compose/lifecycle/savedstate/coroutines keeps AFTER the first
  minified soak. **ARC-065** out-of-band pack import first-swipe behavior (documented; optional).
- ML-side: **ARC-056** uk/bg/mk/he lexicons (+ hebrew branch), **ARC-060** ru layout-JSON
  provenance regeneration, **ARC-061** `make_golden.py` home-path leak (LOW-6 falsely closed).
- Older residuals still open: **ARC-077** = CK-150-027 (a11y dense parity) + CK-150-029
  (touch-exploration-ON smoke incl. `dispatchKeyEvent` non-swallow).

**Process notes that must survive compaction**
- **ARC-078**: the 2026-08-28 androidTest APKs carried ~10.3 MB of UNTRACKED filesystem payload
  (signature = stored-uncompressed ONNX under names the bench doesn't probe); a worktree build
  of the same SHA from tracked files is 3.5 MB, matching today. AGP packages the FILESYSTEM,
  not the index — when an APK size jumps, `unzip -l` first. (Two in-session theories about this
  were wrong before the worktree experiment settled it; both are retracted in the ledger.)
- The Termux-deletion harness lesson (`aadb45d3`): a directly-constructed `BaseInputConnection`
  edits its OWN empty fake editable — override `getEditable()` or seeded EditText text is
  invisible to every read.
- R8 traps recorded in the ledger + `proguard-rules.pro` comments: `usage.txt` bare names mean
  inlined-not-deleted; Kotlin package ≠ directory (judge keep rules by the `package` line);
  the ServiceLoader/`-assumenosideeffects` interaction (ARC-062).

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
- Translations owed: `pref_secondary_prediction_weight` summary (English rescoped to tap-only
  2026-08-28/ARC-018; 21 locales still carry the unscoped wording), plus
  `swipe_context_rescoring_*`, `collision_warning_*`,
  `swipe_engine_pack_not_typeable` / `swipe_engine_pack_head_not_typeable` /
  `swipe_engine_pack_unusable` (added `05c0c25d`; the imported-pack refusal reasons on the
  swipe-engine fallback card), `gesture_touch_smoothing_*`,
  `gesture_finger_occlusion_*`, `dict_word_too_long_for_swipe_*` ship English-only behind
  `tools:ignore="MissingTranslation"`. The 21 `swipe_engine_mode_desc` translations were
  machine-extended and want a native reviewer — **and the English is now CONTENT-stale** as well
  as unreviewed: it names seven languages, predating both `ru` (2026-08-29) and imported-pack
  membership (`05c0c25d`), which is a per-device answer a fixed list cannot carry. Rewording it
  invalidates all 21 locales, so it wants one deliberate pass (probably: drop the enumeration and
  point at the fallback card, which is measured and always right).
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
