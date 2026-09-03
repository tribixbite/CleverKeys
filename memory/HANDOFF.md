# HANDOFF — updated 2026-09-01

Read this first, then `docs/specs/ctc-architecture-and-multiscript-guide.md` (architecture,
routing rule, multi-script recipe, full audit table). This file is the **task list**; the guide is
the **reference**. Where they overlap, the guide wins on technical detail and this file on
priority.

**Completed work is DELETED from this file, not struck through.** Git history is the record of
what was done; this file is only what is left. Anything below is open.

## State after the 2026-08-30..09-02 full-backlog campaign (all pushed through `e87c5b97`+)

**The executable backlog is CLEARED.** Every ARC item that did not require maintainer input is
closed — see `docs/audit/2026-08-28-archive-verification.md` (waves R1/R2/R3, D, G, J, K
sections; every closure cites its commit) and `docs/plans/2026-08-30-full-backlog-campaign.md`
(final wave table). Highlights: ARC-001..102 resolved or dispositioned except the
maintainer-gated set below; el wired (langpack-sourced, synthesis-holdout tier, unannounced);
21-locale ARC-067 complete with lint-enforced coverage (zero MissingTranslation suppressions);
ARC-072 slices 1-3 done (`wiring/KeyboardComponentGraph`, root 158→101); French verb
inversions shipped PAIRED-only; geo OQs closed as measured declines with re-runnable sweeps;
uk/bg/mk/he langpacks built (ARC-056) and their CTC wiring landed 2026-09-03 (`1b17c318` —
all six table scripts ROUTED).

**Verification**: `runPureTests` **2104+**, `runMockTests` **343**, lintDebug/lintVitalRelease
green (release lint enabled and exercised); Wave J full ew-cli run **1,466 tests** — only the
2 permanent bench reds remain (3 first-run reds were all TEST bugs, fixed `86d77a16`, zero
production bugs); Wave K Saga device pass complete
(`docs/eval/2026-09-02-wave-k-device-verification.md`): ARC-070 closed no-leak, warm-up
non-issue (61 ms), #79 non-reproducing, decode e2e verified, restore verified. Fresh verbose
release v1.6.0 (sha `6894b2cc…`) is INSTALLED on the Saga awaiting the maintainer soak.

**Still open after round 2 (2026-09-03) — the complete list (ledger "Round 2 closure"
section is authoritative for what closed):**
- **ARC-053 minified soak (yours).** Both phones carry the Sep-3 release with ALL round-2
  code (uk/bg/mk/he CTC, minSdk 24 + ORT 1.21.1, theme fixes, playground, preview-count
  fix); N-DEV drove the full daily-typing suite green first. ARC-063 keep-narrowing AFTER.
- **ARC-060 rider 2 (training box)**: one-shot ru real-probe with the new geometry —
  |result − 85.30| must sit within ±0.207. Everything else about the swap is DONE
  (rider 1: replica agreement now exact 0.0). Until it runs, every published ru accuracy
  number predates the shipped geometry.
- Translation NATIVE REVIEW: still machine-era after the multi-source QA pass (52 fixes,
  21 locales fully swept 2026-09-03); highest-value locales first: **tr, vi**.
- `finger_occlusion_offset` A/B: the capture mechanism now EXISTS (Swipe Debug Log
  playground: per-key geometry + ranking + latency traces, export/share) — swipe in the
  playground and export when ready; the A/B analysis follows your traces.
- Announce uk/bg/mk/he at the next release-notes edit
  (`SERVED_BUT_NOT_YET_ANNOUNCED = {uk, bg, mk, he}` is the pin).
- ARC-114 (LOW): #79 A17 inset-strip observable. Import-preview "Invalid/skipped" label
  wording (the skips are intentional categories, "Invalid" oversells) — cosmetic.
- Langpack manifest-version normalize on next pack rebuild (byte-identity rule defers it).
- Evidence-beyond-holdout for el/uk/bg/mk/he: real-swipe probes are an ML-repo/device
  question; synthesis-holdout levels stay unquotable as accuracy. Per-script LATENCY is
  now measured and published (guide, `55da93fb`).
- M2 FUTO-official-test final read; send `FUTO_PRESET_NOTE.md` or not; `EW_API_TOKEN`
  laptop-side (unchanged standing decisions).
- CLOSED this round (see ledger): ARC-054 (announced), ARC-104 (policy replaced), ARC-107,
  ARC-111 (code + device-verified), ARC-113 (minSdk 24 + ORT 1.21.1), ARC-112 rider items,
  langpacks release, B2 latency, Pixel Wave-K residue.

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
- ~~ARC-067~~ **CLOSED 2026-09-01** (ledger §"Wave D"): all 21 locales complete, all 373
  `MissingTranslation` suppressions removed from the base file, lint green unsuppressed —
  coverage is now lint-enforced. All new translations are MACHINE translations pending the
  maintainer's native-review pass. New follow-ons: ARC-103 (`<plurals>` conversion for count
  strings), ARC-104 (ADB test-script policy decision), ARC-105 (stale IC:539 KDoc line).
- ~~Wave E remainder~~ — **DONE 2026-09-01**: ARC-073 doc/citation drift + micro-bucket
  (`d20ed3b5`), ARC-098 phantom-`keyboard2` tooling sweep (`f482faf4`), the four
  verified-doc-claim audits and the `contraction_pairings_cleaned.json` gate run (see §3).
  ARC-098's `gesture/` + Bridges/Initializers→`wiring/` half is NOT in that commit — it is
  source-tree work and stays with ARC-072 slice 3 below.
- ARC-072 slice 3: Initializers → `wiring/KeyboardComponentGraph`, folded with the gesture/
  reorg portion of ARC-098. Then continue later Config/SettingsActivity decomposition slices.
- Evidence-gated geometric OQs ARC-027/028/029: do not change defaults unless local-corpus replay
  shows a non-regressing improvement. ARC-030 floors are already present.
- Web: ARC-071 Astro 6 migration and ARC-046 regression gate + Tailwind vendoring.
- ML repo: ARC-060 ru layout regeneration, ARC-061 golden-path fix. (ARC-056 uk/bg/mk/he
  lexicons/langpacks CLOSED 2026-09-01 — `538a1633` toolchain + `86156ea3` artifacts.)
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
- **RESOLVED 2026-09-01 — verb inversions shipped as a PAIRED-only closed family** (commit
  `bd8984fe`): 272 subject-pronoun inversions (`est-elle`, `a-t-on`, `va-t-il` …) generated from
  the person-keyed `FRENCH_INVERSION_VERBS` table into `contraction_pairs_fr.json` (183 → 455),
  forced-append at classification so regeneration cannot flip them to REPLACE. The named
  landmines are pinned in `BundledContractionDataTest`: `estelle` (native @16343) and `aton`
  (ASK-attested) survive in-slot through the real overlay and are REPLACE-forbidden forever;
  `entretemps` stays out of both files and sits in `FORCED_APPEND["fr"]` as defence in depth
  against a future extraction misfire. Zero new REPLACE keys, sidecars byte-identical.

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

- ~~`contraction_pairings_cleaned.json`~~ — **CLOSED 2026-09-01.** The deletion had already
  landed in `030265ee` (this entry was stale); absence re-confirmed across `src/`, `scripts/`,
  `tools/`, `build.gradle`. The gate run this entry asked for has now happened:
  `swipe.BundledContractionDataTest` 18/18 and `swipe.ContractionCollisionDataTest` 6/6 green,
  plus the full `runPureTests` 2093/2093.
- **Doc claims found but deliberately not fixed** during the 2026-08-21 deleted-class sweep —
  **all four re-verified against live code 2026-09-01 (ARC-073/§3):**
  - `core-keyboard-system.md` invented API signatures (`CleverKeysService.switchLayout`,
    `KeyEventHandler.handleKeyDown`) — ALREADY CORRECT. The 2026-08-21 pass replaced them with
    an explicit "none of those methods exist" note plus the real event-driven flow. All six
    live signatures re-verified at HEAD (`current_layout`, `current_layout_unmodified`,
    `setTextLayout`, `incrTextLayout`, `setSpecialLayout`, `loadLayout`), as was
    `KeyEventHandler.key_up → recv.handle_event_key`. No edit needed.
  - `testing-strategy.md` self-contradicting inventory — the contradiction was already removed;
    the COUNTS were stale. Re-measured and rewritten: pure **2093** and mock **343**
    (both run 2026-09-01 on this device), instrumented 1395 carried as the last recorded ew-cli
    sweep (2026-08-18) and explicitly labelled as a floor, since ew-cli was not re-run.
  - `ARCHITECTURE_MASTER.md` §9 `SwipeDetector` box + §7.2 `DATABASE_VERSION` — both already
    corrected (the box states no such class exists; `DATABASE_VERSION` reads 5, not 1, and the
    live constant IS 5). Only the line anchor was stale: `ClipboardDatabase.kt:1828` → `:1869`.
  - `settings-system.md` file tree + "~3000 lines" — tree re-derived from
    `git ls-tree HEAD`: SettingsActivity 801→**845** lines, `ui/settings/io/` 8→**9** files
    (`CreateBackupDocument.kt` was missing). `ui/settings/` 10 and `sections/` 20 confirmed.
    CLAUDE.md's architecture block carried the same drift and was refreshed with it.
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
language CTC decodes fine. **All six table scripts are ROUTED since 2026-09-03** (ru `da012ded`,
el `5fb58037`, uk/bg/mk/he `1b17c318` after ARC-056's lexicons). What remains for geometric is
the long tail with no model or lexicon (kk/sr/hy/ka), Turkish (DECIDED permanent tap+geometric —
dotless ı, 73.34 % projectable), and layout.

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
sha `8fffa75c…` (589,406 B), fixture `ru_synth_v3_ch80_fp16w_golden.json` sha `8951d7a3…`
(159,778 B, ARC-060 regenerated geometry, two byte-identical copies), preset `tunedRuCkdt`, layout `cyrl_jcuken_ru.xml`,
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
**Per-script LATENCY has never been measured** (ru/el; open in
`memory/language-support-todo.md` §B). Memory is no longer unmeasured: the ARC-058 3-language
rotation test bounds the trie memo and the second ORT session with ru primary, and it ran green
in the Wave J full ew-cli run (2026-09-02, Pixel7 API 34).
λ = 2.0 carries a measured, unconfirmed −0.63 t1 shortfall; γ, β and the prune terms are E1's.

### What is left, per script

`el` **IS ROUTED** (ARC-055, wired in `5fb58037`, 2026-09-01): `el_synth_v3_ch80_fp16w.onnx`
(sha `7083794c…`) + its fixture (sha `d08d5501…`) ship, the `CtcScriptSupport` row is `ROUTED`,
`el` is in `SUPPORTED` (langpack-sourced, `PROVISIONAL` tier). Still true and permanent:
**Greek has no real-swipe probe at any tier** — its 92.12 is a synthesis-holdout level and may
never be quoted as accuracy; the device parity/latency run is its only runtime bar. ru + el
were announced 2026-09-03 (ARC-054, honest tiers, pack-gated).

`uk`, `bg`, `mk`, `he` are ROUTED too (2026-09-03, `1b17c318`): lexicons from ARC-056
(2026-09-01: `538a1633` hebrew `_is_script_word` branch + the four `LANG_CONFIG` entries,
`86156ea3` the CKDT v2 langpacks `scripts/dictionaries/langpack-{uk,bg,mk,he}.zip`), and the
generation-4 `*_synth_v3_ch80_fp16w.onnx` models + byte-identical golden fixtures copied in
with all eight sha256s verified. All four are `PROVISIONAL`; none has a real-swipe probe at
any tier. `CtcScriptSupport` is the live list, not this paragraph.

**The 32-frame budget IS checked against every script lexicon** —
`CtcBundledLexiconEmitBudgetTest` (ARC-057, `eac7594f`, 2026-08-29) sweeps en + the CKDT six +
ru through their exact production projections plus every alias table (zero surfaces over
budget, ru worst `высококвалифицированных` at 24 of 32), and since `e99bccc1` (2026-09-03) the
script list is derived from `CtcScriptSupport.SCRIPTS`, so el/uk/bg/mk/he are swept
automatically. **The Latin imported-pack half is also closed**
(`CtcImportedPackSupportTest`, 2026-08-29): zero words over budget in nl/id/ms/sw/tl, worst case
`gemeenteraadsverkiezingen` at 27 of 32 frames — which is why imported-pack eligibility gates on
spelling and not on length.

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

- **The ru CTC path — UPDATE 2026-09-02**: the Wave J full ew-cli run (1,466 tests, Pixel7
  API 34) executed the instrumented suite green, discharging the emulator-reachable steps
  below ((1)–(3), and the ARC-058 rotation memory bound). Still owed: the langpack-import
  device half (step 4, maintainer's device) and per-script LATENCY (step 5 — timed for no
  script model yet; `memory/language-support-todo.md` §B). Original list, for the record:
  `da012ded` ships the Russian encoder and routes
  Cyrillic; the ew-cli run had to confirm, in this order: (1)
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
  latency gate on a ru swipe, because **no script model has ever been TIMED** (memory is now
  bounded by ARC-058's rotation test, executed green in Wave J)
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
