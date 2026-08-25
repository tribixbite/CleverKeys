# Current work queue

Updated: 2026-08-25. Historical task ledger archived at
[`docs/history/task-ledgers/2026-08-23-pre-audit-ledger.md`](../docs/history/task-ledgers/2026-08-23-pre-audit-ledger.md).
Remediation verification + residual plan (CK-150-019…036):
[`docs/audit/2026-08-25-remediation-verification.md`](../docs/audit/2026-08-25-remediation-verification.md).

## Release 1.6 verification

- [ ] **CK-150-019 (P1, new blocker):** `ClipboardDatabase.importFromJSON` swallows exceptions →
  failed imports report success and media rollback is dead code. Fix per plan §4.1 (+ §4.2
  directory-entry/rollback hardening).
- [ ] **CK-150-025:** fuzzy-rescue score-clamp inversion (`CtcEngineAdapter.applyFuzzyRescue`) can
  rank a rescued word above a confident decode; extract merge + add rank-1 tests (plan §4.3).
- [x] Run `runPureTests`, `runMockTests` — green at `6b3b8bb9` (1,757 + 292, quiet output,
  2026-08-25). Re-run with `lintDebug` + Android-test compilation at the candidate SHA.
- [ ] Fix `emulator-ci.sh` `OK (0 tests)` false-green + pin curated class list (plan §4.5), THEN
  run the curated API-34 emulator gate in GitHub Actions or ew-cli 1.3.4 on the exact candidate SHA.
- [ ] **French-only** held-out evaluation for the `ß/œ/æ/ø` projection change (German is a no-op —
  `de_enhanced.bin` has no `ß` words; see plan §2a/CK-150-036); record under `docs/eval/`.
- [ ] Gather independent on-device CTC shadow traces before considering context rescoring; keep it
  default-off until it shows positive benefit on confirmation data.
- [ ] P2 queue before/immediately after tag: possessive leak on secondary language (§4.4), Trivy
  gates + Gradle lockfile (§4.6), settings/dict rollback (§4.7), dual-language latency (§4.9).

## Post-1.6

- [ ] CTC multi-script wiring (ru/el/uk/bg/mk/he), staged wiring-before-model:
  [`docs/plans/2026-08-25-ctc-multiscript-wiring-plan.md`](../docs/plans/2026-08-25-ctc-multiscript-wiring-plan.md)
  (ML checklist §1 doc items closed 2026-08-25; 1.2 CI class + LOW-2/LOW-8/LOW-9 remain).

## Release authority

Do not commit, tag, push, publish, or open external issues without explicit user authorization.
