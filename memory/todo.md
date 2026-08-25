# Current work queue

Updated: 2026-08-25. Historical task ledger archived at
[`docs/history/task-ledgers/2026-08-23-pre-audit-ledger.md`](../docs/history/task-ledgers/2026-08-23-pre-audit-ledger.md).
Remediation verification + residual plan (CK-150-019…036):
[`docs/audit/2026-08-25-remediation-verification.md`](../docs/audit/2026-08-25-remediation-verification.md).

## Release 1.6 verification

Implementation wave landed 2026-08-25 (`0bcce870..ff5c124b`): CK-150-019/020/021/022/023/024/
025/028/030/032/034/035 + LOW-2/8/9 fixed — ledger in
`docs/audit/2026-08-25-remediation-verification.md` §1b. Remaining:

- [ ] Run the curated API-34 emulator gate (GitHub Actions or ew-cli 1.3.4) on the exact
  candidate SHA — now 6 classes incl. `CtcEmissionModelParityTest` and the new negative
  routing + dual-language latency tests (written, never executed on device).
- [ ] **French-only** held-out evaluation for the `ß/œ/æ/ø` projection change (German is a no-op —
  `de_enhanced.bin` has no `ß` words; CK-150-036 is the product decision); record under `docs/eval/`.
- [ ] Re-baseline the `-PgeoFull` context-rescoring replay after `c83d6ff2` (rescue no longer
  reshapes the slate; expect the ratio median back toward 0.254). Coordinate with the rescoring
  session; keep the pref default-off.
- [ ] First real PR-path Trivy run (now blocking, `exit-code: '1'`) — watch the next PR.
- [ ] P2/P3 leftovers: CK-150-027 (a11y dense parity sweep), CK-150-029 (touch-exploration-ON
  smoke), CK-150-031 (EN rescue accented entries), headless-toast/dialog i18n backlog
  (verification §1b), pure pin of the languages threading (adapter→coordinator→handler).
- [ ] Lockfile caveat: after any `--write-locks` regeneration, re-check
  `kotlin-stdlib-common:2.0.0` still lists `debugAndroidTestRuntimeClasspath` (`8e2dd63d`).

## Post-1.6

- [ ] CTC multi-script wiring (ru/el/uk/bg/mk/he), staged wiring-before-model:
  [`docs/plans/2026-08-25-ctc-multiscript-wiring-plan.md`](../docs/plans/2026-08-25-ctc-multiscript-wiring-plan.md)
  (ML checklist §1 doc items closed 2026-08-25; 1.2 CI class + LOW-2/LOW-8/LOW-9 remain).

## Release authority

Do not commit, tag, push, publish, or open external issues without explicit user authorization.
