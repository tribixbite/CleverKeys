# Current work queue

Updated: 2026-08-23. Historical task ledger archived at
[`docs/history/task-ledgers/2026-08-23-pre-audit-ledger.md`](../docs/history/task-ledgers/2026-08-23-pre-audit-ledger.md).

## Release 1.6 verification

- [ ] Run `./build-on-termux.sh debug` after the audit remediation suite is green.
- [ ] Run `runPureTests`, `runMockTests`, `lintDebug`, and Android-test compilation.
- [ ] Run the curated API-34 emulator gate in GitHub Actions or ew-cli 1.3.4; local Termux has no ADB/device target.
- [ ] Gather independent on-device CTC shadow traces before considering context rescoring; keep it default-off until it shows positive benefit on confirmation data.
- [ ] Re-run language-specific evaluation after the `ß/œ/æ/ø` projection expansion and record the results under `docs/eval/`.
- [ ] Review the remediation report and audit evidence before any release commit.

## Release authority

Do not commit, tag, push, publish, or open external issues without explicit user authorization.
