# Language & custom-layout support — outstanding work

**Audit date 2026-09-02, app HEAD `a69a06cf`, ML HEAD `acbc96d`.** Produced by a four-way audit
of `APP_WIRING_CHECKLIST.md` §1/§2, the dictionary pipeline, the GitHub release inventory, and
the guide mirrors. Verified-done work is NOT listed here (checklist §1 items 1.1–1.5, §2.1
items 1–6, projection incl. both Greek halves, ru+el wiring, ARC-055/056/057/058/059 — all
confirmed in code). Companion: `docs/guides/adding-a-new-language.md`.

## A. Distribution — the packs are unreachable (highest user-facing value)

- [x] **DONE 2026-09-03** — https://github.com/tribixbite/CleverKeys/releases/tag/langpacks (prerelease, 23 assets, sha256 table; F-Droid-safe: non-v* tag + prerelease never becomes releases/latest). **Create the langpacks GitHub release.** No release on `tribixbite/CleverKeys` (all 509
  checked) has ever carried a langpack asset; `memory/HANDOFF.md` says ru CTC "only works once
  the user imports langpack-ru.zip", yet the only way to get it is cloning the repo. Publish a
  `langpacks` pre-release with all current zips (18 real languages + en variants), body listing
  language/word-count/sha256 per asset. Add new uk/bg/mk/he; re-upload any pack whose zip
  changes (they're deterministic — byte change = content change).
- [x] **DONE 2026-09-03** (`6621168d`, `434d2b0d`) **README downloadable-packs table**: still "(14)", missing uk/bg/mk/he rows; repoint the
  download link at the release page once it exists.
- [x] **DONE 2026-09-03** (`7e1034be` — existing "Browse available packs" link repointed at the release) **In-app pointer**: Settings → Multi-Language import flow has no hint where packs live.
- [x] **DONE 2026-09-03** (`7e1034be`) `SettingsLanguagePackHandlers.getLanguageDisplayName` lacked `mk`/`he` entries (renders
  "MK"/"HE"); its "Downloadable language packs" comment lists only id/ms/sw/tl.
- [x] **DONE 2026-09-03** (`f6cc401d`; pin cleared to {}, then M-LANG re-seeded it with {uk,bg,mk,he} for the newly wired scripts — announce those at the next notes edit) ARC-054: announce ru + el swipe in release notes/changelog
  (`ReleaseMetadataDriftTest.SERVED_BUT_NOT_YET_ANNOUNCED = {ru, el}` is the pin to clear).
- [x] **DONE 2026-09-03** (`4a441925`, all 22 locales) `res/values/strings.xml` CTC settings intro said "Latin layouts" — inaccurate since
  ru/el routed.
- [ ] DEFERRED (byte-identity rule — normalize on next rebuild) Manifest `version` is 1 for the ARC-056 packs vs 2 for the 2026-08-07 packs — harmless
  (importer ignores it) but normalize on next rebuild.

## B. Test/gate gaps (cheap, mechanisms already exist)

- [x] **DONE 2026-09-03** (`e99bccc1` — zero over-budget words in all five; he's true max is 14 frames (abjad), handled with a documented per-language floor override he→12) **Emit-budget sweep missed el and the four new packs.** `CtcBundledLexiconEmitBudgetTest`
  sweeps en + CKDT six + ru; `langpack-el.zip` (39,860 words, long inflected forms) and
  uk/bg/mk/he are never checked against the 32-frame budget. An over-budget word is unemittable
  silently. The ru sweep block is reusable as-is.
- [x] **DONE 2026-09-03** — `CtcScriptLatencyMeasurementTest` (real packs staged into the test APK), run `55da93fb`: ru 42.0/59.4, el 21.5/24.2, uk 22.6/22.9, bg 21.8/23.7, mk 17.4/18.5, he 27.0/27.5 ms (median/p90, Pixel7 API34 warm); published in the guide. **Per-script latency never measured** (ru/el, emulator or hardware). ARC-058 measured
  memory bounds but no number is published in docs/. ew-cli run on real hardware is the honest
  home (needs EW_API_TOKEN on this side, or run from the Termux device).
- [x] **RESOLVED 2026-09-03** (`1b17c318` — fixtures landed, slot-order gates active) uk/bg/mk/he fixture-less scripts rested on literal alphabet pins only — the slot-order
  fixture assertion activates automatically when their fixtures land (see C).

## C. Per-script rollout — the remaining four, then the long tail

- [x] **DONE 2026-09-03** (`e99bccc1`+`1b17c318`, all hashes verified, PROVISIONAL tier, budget sweep same change) **Wire uk, bg, mk, he.** Everything is in place except bytes: copy
  `<code>_synth_v3_ch80_fp16w.onnx` + `_golden.json` from `CleverKeys-ML/ctc/artifacts/`
  (hashes in `APP_WIRING_CHECKLIST.md` §2.2; verify sha256 on copy), fixture into both test
  trees, flip the `CtcScriptSupport` row to `ROUTED` (init refuses without both files), add to
  `CtcLanguageSupport.SUPPORTED`. Parity rows are table-derived. +589 KB APK each, ~+3 t1 over
  zero-shot. Do the B-item budget sweep in the same change.
- [ ] BLOCKED unchanged (GPU training + missing lexicon paths). **Long tail**: kk/sr/hy/ka layouts exist or are trivial, but models are untrained and
  sr/hy/ka are blocked on dictionaries (no usable wordfreq depth / no oracle). Each needs the
  §4 ML recipe (~1 GPU-day) once a lexicon path exists. uk apostrophe forms (м'ясо — wordfreq
  uses U+02BC, rejected by the Cyrillic gate) and uk ї/ґ serving need the corner-alias input
  mode — a different feature, unscheduled. Also listed in `memory/HANDOFF.md`'s "Still open
  after round 2" so it is tracked outside this file.
- [x] **DECIDED 2026-09-03** (permanent TAP+geometric; recorded in CtcScriptSupport KDoc + guide, pinned in CtcLanguagePresetTest; reopening condition = ML-side tr-fold holdout evidence) **Turkish CTC**: rejected by a–z measurement (dotless ı has no NFD decomposition; 73.34 %
  projectable). Either a tr-specific fold (ı→i) in a projection row + eligibility exception, or
  accept TAP+geometric permanently. Decide, don't drift.
- [x] **EXECUTED 2026-09-03** (app `128c93f8`, ML `66c60ad`+`8778fef`; rider 1: ru replica agreement now exact 0.0 — the root issue; rider 2 BLOCKED on-device: the 85.30±0.21 one-shot confirm needs the training box's ~/ctc-train val set — OWED there) **ARC-060 decision (user)**: ru layout-geometry swap — regenerated `ru_jcuken_default.json`
  differs materially (cx max 3.35e-3 vs 4.7e-4 noise; 20/124 values). If swapped: geometry +
  fixture regen + parity re-run as ONE unit, plus verify the replica's ru frame math against
  the app the way en was verified, plus one ru real-probe decode to confirm the delta is inside
  the 0.21 seed sd. Recommendation on file: do it (no retraining involved).

## D. Documentation drift (app repo) — ALL DONE 2026-09-03 (M-DOCS `72d71ae6`/`44fe4c6c`/`dac07d62`/`17ea48ab`/`c7a01e2b`/`776db37f`; gap strings via `1b17c318`; three todo claims were themselves wrong — no stale 84ac284d cites existed, en 98,140 was correct, ARC-058 nuance — see the wave report in the ledger round-2 section)

- [x] `CtcScriptSupport.kt` gap strings for uk/bg/mk/he all say "no lexicon exists" — falsified
  by ARC-056; he's also still says the hebrew `_is_script_word` branch is needed (it landed).
  Status verdicts are still correct; only the stated reasons are stale.
- [x] `memory/HANDOFF.md` contradicts HEAD in four places: el "deliberately NOT routed"
  (it is routed), emit budget "never checked against a real script lexicon" (ru is swept),
  "nothing on-device has ever been measured for any script model" (ARC-058 measures memory),
  ARC-056 still listed as open (also stale at `memory/todo.md` ARC-056 row and
  `docs/audit/2026-08-28-archive-verification.md`).
- [x] Guide (`docs/specs/ctc-architecture-and-multiscript-guide.md`) defects: the
  superseded-generations table lists `ru_synth_v3_ch80_fp16w.onnx` @ `9004befb…` as superseded
  — filename typo for `ru_synth_v2…`, and it collides with the ship row (`8fffa75c…`) — exactly
  the "which ONNX?" confusion shape; the §6 open-items table still shows NEW-6 and
  MEDIUM-5/LOW-9 as open (closed 2026-08-25); stale gen-1 `84ac284d` citations remain in
  superseded-context rows and in `docs/specs/ctc-swipe-engine.md`; the ctc-swipe-engine spec's
  `Config.kt:311` cite is off by one (constant at `:312`).
- [x] `docs/audit/2026-08-28-archive-verification.md` writes
  `SERVED_BUT_NOT_YET_ANNOUNCED = {ru}` — stale by one script (= {ru, el} at HEAD).
- [x] `.claude/skills/dictionary-pipeline.md` (7 items): names deleted `OptimizedVocabulary` as
  the runtime loader (real: `BinaryDictionaryLoader`/`DictionaryDataSource`/`WordPredictor`/
  `CkdtDictionaryReader`); tier lists miss he (C) and uk/bg/mk (D); `--bootstrap` undocumented;
  supported-languages list omits the four new packs; "available via wordfreq" still lists
  he/uk/bg as unconfigured; en count 98,140 vs measured 98,122 distinct; pitfalls still mandate
  the OBSOLETE prefix-boost step.
- [x] `scripts/build_all_languages.py` still has `'boost': True` on 11 languages — a bulk
  rebuild today would resurrect dead `prefix_boosts/` assets. Flip to False everywhere.
- [x] LOW-6 residue: both `ctc_golden.json` copies (and the new ru/el fixtures) embed dev
  absolute `source_onnx` paths. Fix belongs in ML-side `make_golden.py`; app copies change only
  on the next fixture regen (byte-identity rule) — fold into the ARC-060 unit if it proceeds.

## E. ML-repo side (CleverKeys-ML) — ALL DONE 2026-09-03 (7343355 was real-but-unpushed, now pushed; `dd8cab5`/`7cc0999`/`e805e5d`/`4f01961`+ARC-060's `66c60ad`/`8778fef`; origin/main == HEAD)

- [x] **Reconcile the phantom ML commit.** `docs/plans/2026-08-30-full-backlog-campaign.md`
  cites ML commit `7343355` (ARC-061 make_golden fix); the ML repo at `acbc96d` does not
  contain it. The Termux device holds unpushed ML commits — push them, or correct the citation.
- [x] `script_registry.py` still declares uk/bg/mk/he lexicons as `kind="wordfreq"`; the app
  packs now exist — switch to `kind="ckdt"` like ru/el, and state in the guide that published
  uk/bg/mk/he holdout numbers were measured on raw wordfreq lists, not the shipped packs.
- [x] `APP_WIRING_CHECKLIST.md` §2.2 lexicon column ("must be built" ×4, he `_is_script_word`
  claim) and the same text in `PHASE_O.md` — falsified by ARC-056.
- [x] The ML guide mirror's header warning ("app copy … two model generations stale") is now
  factually wrong — the app copy is AHEAD (three hunks, all app-newer). Reverse-sync the app
  copy's three addenda hunks and drop the warning.
- [x] `make_golden.py`: strip/relativize `source_onnx` (ARC-061) if `7343355` turns out not to
  contain it.

## F. Standing user decisions (unchanged)

- M2 FUTO-official-test final read (1 of 3 remains; recommendation: bank it).
- Send `FUTO_PRESET_NOTE.md` to the FUTO team or not.
- `EW_API_TOKEN` for laptop-side ew-cli latency runs (else Termux device owns B-item 2).
