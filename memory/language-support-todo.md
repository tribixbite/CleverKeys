# Language & custom-layout support — outstanding work

**Audit date 2026-09-02, app HEAD `a69a06cf`, ML HEAD `acbc96d`.** Produced by a four-way audit
of `APP_WIRING_CHECKLIST.md` §1/§2, the dictionary pipeline, the GitHub release inventory, and
the guide mirrors. Verified-done work is NOT listed here (checklist §1 items 1.1–1.5, §2.1
items 1–6, projection incl. both Greek halves, ru+el wiring, ARC-055/056/057/058/059 — all
confirmed in code). Companion: `docs/guides/adding-a-new-language.md`.

## A. Distribution — the packs are unreachable (highest user-facing value)

- [ ] **Create the langpacks GitHub release.** No release on `tribixbite/CleverKeys` (all 509
  checked) has ever carried a langpack asset; `memory/HANDOFF.md` says ru CTC "only works once
  the user imports langpack-ru.zip", yet the only way to get it is cloning the repo. Publish a
  `langpacks` pre-release with all current zips (18 real languages + en variants), body listing
  language/word-count/sha256 per asset. Add new uk/bg/mk/he; re-upload any pack whose zip
  changes (they're deterministic — byte change = content change).
- [ ] **README downloadable-packs table**: still "(14)", missing uk/bg/mk/he rows; repoint the
  download link at the release page once it exists.
- [ ] **In-app pointer**: Settings → Multi-Language import flow has no hint where packs live.
- [ ] `SettingsLanguagePackHandlers.getLanguageDisplayName` lacks `mk`/`he` entries (renders
  "MK"/"HE"); its "Downloadable language packs" comment lists only id/ms/sw/tl.
- [ ] ARC-054: announce ru + el swipe in release notes/changelog
  (`ReleaseMetadataDriftTest.SERVED_BUT_NOT_YET_ANNOUNCED = {ru, el}` is the pin to clear).
- [ ] `res/values/strings.xml` CTC settings intro still says "Latin layouts" — inaccurate since
  ru/el routed.
- [ ] Manifest `version` is 1 for the ARC-056 packs vs 2 for the 2026-08-07 packs — harmless
  (importer ignores it) but normalize on next rebuild.

## B. Test/gate gaps (cheap, mechanisms already exist)

- [ ] **Emit-budget sweep misses el and the four new packs.** `CtcBundledLexiconEmitBudgetTest`
  sweeps en + CKDT six + ru; `langpack-el.zip` (39,860 words, long inflected forms) and
  uk/bg/mk/he are never checked against the 32-frame budget. An over-budget word is unemittable
  silently. The ru sweep block is reusable as-is.
- [ ] **Per-script latency never measured** (ru/el, emulator or hardware). ARC-058 measured
  memory bounds but no number is published in docs/. ew-cli run on real hardware is the honest
  home (needs EW_API_TOKEN on this side, or run from the Termux device).
- [ ] uk/bg/mk/he fixture-less scripts rest on literal alphabet pins only — the slot-order
  fixture assertion activates automatically when their fixtures land (see C).

## C. Per-script rollout — the remaining four, then the long tail

- [ ] **Wire uk, bg, mk, he.** Everything is in place except bytes: copy
  `<code>_synth_v3_ch80_fp16w.onnx` + `_golden.json` from `CleverKeys-ML/ctc/artifacts/`
  (hashes in `APP_WIRING_CHECKLIST.md` §2.2; verify sha256 on copy), fixture into both test
  trees, flip the `CtcScriptSupport` row to `ROUTED` (init refuses without both files), add to
  `CtcLanguageSupport.SUPPORTED`. Parity rows are table-derived. +589 KB APK each, ~+3 t1 over
  zero-shot. Do the B-item budget sweep in the same change.
- [ ] **Long tail**: kk/sr/hy/ka layouts exist or are trivial, but models are untrained and
  sr/hy/ka are blocked on dictionaries (no usable wordfreq depth / no oracle). Each needs the
  §4 ML recipe (~1 GPU-day) once a lexicon path exists. uk apostrophe forms (м'ясо — wordfreq
  uses U+02BC, rejected by the Cyrillic gate) and uk ї/ґ serving need the corner-alias input
  mode — a different feature, recorded, unscheduled.
- [ ] **Turkish CTC**: rejected by a–z measurement (dotless ı has no NFD decomposition; 73.34 %
  projectable). Either a tr-specific fold (ı→i) in a projection row + eligibility exception, or
  accept TAP+geometric permanently. Decide, don't drift.
- [ ] **ARC-060 decision (user)**: ru layout-geometry swap — regenerated `ru_jcuken_default.json`
  differs materially (cx max 3.35e-3 vs 4.7e-4 noise; 20/124 values). If swapped: geometry +
  fixture regen + parity re-run as ONE unit, plus verify the replica's ru frame math against
  the app the way en was verified, plus one ru real-probe decode to confirm the delta is inside
  the 0.21 seed sd. Recommendation on file: do it (no retraining involved).

## D. Documentation drift (app repo)

- [ ] `CtcScriptSupport.kt` gap strings for uk/bg/mk/he all say "no lexicon exists" — falsified
  by ARC-056; he's also still says the hebrew `_is_script_word` branch is needed (it landed).
  Status verdicts are still correct; only the stated reasons are stale.
- [ ] `memory/HANDOFF.md` contradicts HEAD in four places: el "deliberately NOT routed"
  (it is routed), emit budget "never checked against a real script lexicon" (ru is swept),
  "nothing on-device has ever been measured for any script model" (ARC-058 measures memory),
  ARC-056 still listed as open (also stale at `memory/todo.md` ARC-056 row and
  `docs/audit/2026-08-28-archive-verification.md`).
- [ ] Guide (`docs/specs/ctc-architecture-and-multiscript-guide.md`) defects: the
  superseded-generations table lists `ru_synth_v3_ch80_fp16w.onnx` @ `9004befb…` as superseded
  — filename typo for `ru_synth_v2…`, and it collides with the ship row (`8fffa75c…`) — exactly
  the "which ONNX?" confusion shape; the §6 open-items table still shows NEW-6 and
  MEDIUM-5/LOW-9 as open (closed 2026-08-25); stale gen-1 `84ac284d` citations remain in
  superseded-context rows and in `docs/specs/ctc-swipe-engine.md`; the ctc-swipe-engine spec's
  `Config.kt:311` cite is off by one (constant at `:312`).
- [ ] `docs/audit/2026-08-28-archive-verification.md` writes
  `SERVED_BUT_NOT_YET_ANNOUNCED = {ru}` — stale by one script (= {ru, el} at HEAD).
- [ ] `.claude/skills/dictionary-pipeline.md` (7 items): names deleted `OptimizedVocabulary` as
  the runtime loader (real: `BinaryDictionaryLoader`/`DictionaryDataSource`/`WordPredictor`/
  `CkdtDictionaryReader`); tier lists miss he (C) and uk/bg/mk (D); `--bootstrap` undocumented;
  supported-languages list omits the four new packs; "available via wordfreq" still lists
  he/uk/bg as unconfigured; en count 98,140 vs measured 98,122 distinct; pitfalls still mandate
  the OBSOLETE prefix-boost step.
- [ ] `scripts/build_all_languages.py` still has `'boost': True` on 11 languages — a bulk
  rebuild today would resurrect dead `prefix_boosts/` assets. Flip to False everywhere.
- [ ] LOW-6 residue: both `ctc_golden.json` copies (and the new ru/el fixtures) embed dev
  absolute `source_onnx` paths. Fix belongs in ML-side `make_golden.py`; app copies change only
  on the next fixture regen (byte-identity rule) — fold into the ARC-060 unit if it proceeds.

## E. ML-repo side (CleverKeys-ML — commits go THERE)

- [ ] **Reconcile the phantom ML commit.** `docs/plans/2026-08-30-full-backlog-campaign.md`
  cites ML commit `7343355` (ARC-061 make_golden fix); the ML repo at `acbc96d` does not
  contain it. The Termux device holds unpushed ML commits — push them, or correct the citation.
- [ ] `script_registry.py` still declares uk/bg/mk/he lexicons as `kind="wordfreq"`; the app
  packs now exist — switch to `kind="ckdt"` like ru/el, and state in the guide that published
  uk/bg/mk/he holdout numbers were measured on raw wordfreq lists, not the shipped packs.
- [ ] `APP_WIRING_CHECKLIST.md` §2.2 lexicon column ("must be built" ×4, he `_is_script_word`
  claim) and the same text in `PHASE_O.md` — falsified by ARC-056.
- [ ] The ML guide mirror's header warning ("app copy … two model generations stale") is now
  factually wrong — the app copy is AHEAD (three hunks, all app-newer). Reverse-sync the app
  copy's three addenda hunks and drop the warning.
- [ ] `make_golden.py`: strip/relativize `source_onnx` (ARC-061) if `7343355` turns out not to
  contain it.

## F. Standing user decisions (unchanged)

- M2 FUTO-official-test final read (1 of 3 remains; recommendation: bank it).
- Send `FUTO_PRESET_NOTE.md` to the FUTO team or not.
- `EW_API_TOKEN` for laptop-side ew-cli latency runs (else Termux device owns B-item 2).
