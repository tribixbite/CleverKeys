# scripts/data/ — snapshotted build-time resources

Offline snapshots used by `scripts/build_en_wordlist.py` (dictionary generation is a
dev-time step; these assets keep reruns deterministic and network-free — NFR-1 of
`docs/specs/typo-drop-rescue-pipeline.md`).

| Asset | Source | License | Fetched | Rows | Role |
|---|---|---|---|---|---|
| `aosp_en_wordlist.txt.gz` | AOSP LatinIME `dictionaries/en_wordlist.combined.gz` (android.googlesource.com, platform/packages/inputmethods/LatinIME @ refs/heads/main, dictionary version 54, date=1414726273) | Apache-2.0 | 2026-07-02 | 165,544 headwords | Positive keep-oracle: mobile-keyboard-curated vocabulary (names, casual register, abbreviations). Format: `headword\tflags`. Entries flagged `nonword` (57) are shortcut-routing targets and are EXCLUDED from the oracle by the builder. |

Notes:
- The AOSP list is 2014-vintage: excellent for names/standard/casual-2014 words,
  contains zero of the classic typo set (teh/wich/recieve/seperate/becuase), but
  lacks post-2014 internet slang (lol/selfie/bruh are absent) — modern slang is
  covered by the wordfreq frequency-protection tier and the curated allowlist
  (`scripts/dictionaries/en/en_allowlist.txt`) instead.
- Attribution: see repo `NOTICE` (Apache-2.0 — AOSP LatinIME wordlist).
- Refresh procedure: re-run the fetch documented in `build_en_wordlist.py --help`
  (googlesource `?format=TEXT` base64 endpoint), regenerate the gz, update this table.
