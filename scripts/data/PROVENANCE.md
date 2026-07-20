# scripts/data/ — snapshotted build-time resources

Offline snapshots used by `scripts/build_wordlist.py` (dictionary generation is a
dev-time step; these assets keep reruns deterministic and network-free — NFR-1 of
`docs/specs/typo-drop-rescue-pipeline.md`).

All rows are AOSP LatinIME `dictionaries/<code>_wordlist.combined.gz` snapshots
(android.googlesource.com, platform/packages/inputmethods/LatinIME @
refs/heads/main), Apache-2.0, converted to `headword\tflags` lines and
re-gzipped with `mtime=0` (reproducible bytes). Entries flagged `nonword` are
shortcut-routing targets and are EXCLUDED from the oracle by the builder.
The upstream `dictionary=` header (per-language build date + format version)
is recorded in the Source column.

| Asset | Source (upstream header) | License | Fetched | Rows | sha256 (first 12) |
|---|---|---|---|---|---|
| `aosp_en_wordlist.txt.gz` | `en`, version 54, date=1414726273 | Apache-2.0 | 2026-07-02 | 165,544 | e3c3a539ec05 |
| `aosp_es_wordlist.txt.gz` | `es`, version 54, date=1414726268 | Apache-2.0 | 2026-07-20 | 236,193 | d9b70642a4a1 |
| `aosp_fr_wordlist.txt.gz` | `fr`, version 54, date=1414726264 | Apache-2.0 | 2026-07-20 | 190,425 | bda1d7d639fa |
| `aosp_de_wordlist.txt.gz` | `de`, version 54, date=1414726263 | Apache-2.0 | 2026-07-20 | 205,888 | 34f19e7d1af1 |
| `aosp_it_wordlist.txt.gz` | `it`, version 54, date=1414726258 | Apache-2.0 | 2026-07-20 | 172,831 | 62e53346dfc0 |
| `aosp_nl_wordlist.txt.gz` | `nl`, version 54, date=1414726258 | Apache-2.0 | 2026-07-20 | 178,444 | 02cc5c8ad174 |
| `aosp_pt_wordlist.txt.gz` | union of `pt_BR` (v54, date=1414726257) + `pt_PT` (v54, date=1414726273) | Apache-2.0 | 2026-07-20 | 259,831 | b7d6f1ed102e |
| `aosp_sv_wordlist.txt.gz` | `sv`, version 54, date=1414726264 | Apache-2.0 | 2026-07-20 | 196,739 | b3ce19a0e700 |
| `aosp_ru_wordlist.txt.gz` | `ru`, version 54, date=1414726277 | Apache-2.0 | 2026-07-20 | 220,492 | d79dd24f169d |
| `aosp_el_wordlist.txt.gz` | `el`, version 44, date=1393228134 | Apache-2.0 | 2026-07-20 | 184,303 | 77075623925a |
| `aosp_tr_wordlist.txt.gz` | `tr`, version 54, date=1414726261 | Apache-2.0 | 2026-07-20 | 180,841 | f73f52b2e2f7 |

Role: positive keep-oracle — mobile-keyboard-curated vocabulary (names, casual
register, abbreviations). For sv/el/tr the AOSP snapshot is the SOLE band-2
oracle (Tier C in `build_wordlist.py`'s LANG_CONFIG). id/ms/tl have no AOSP
dictionary upstream (probed 2026-07-20 — no `<code>_wordlist.combined.gz` in
the LatinIME tree) and run oracle-less (Tier D, band == top).

Notes:
- The AOSP lists are 2014-vintage (el is 2014/v44): excellent for
  names/standard/casual-2014 words, contain zero of the classic typo sets, but
  lack post-2014 internet slang — modern slang is covered by the wordfreq
  frequency-protection tier and the curated allowlists
  (`scripts/dictionaries/<lang>/<lang>_allowlist.txt`) instead.
- Attribution: see repo `NOTICE` (Apache-2.0 — AOSP LatinIME wordlists).
- Evidence-only oracles that are NOT redistributed (no NOTICE entry required):
  hunspell system dictionaries (en_US fr_FR nl_NL ru_RU), aspell dictionaries
  (en_GB de es fr), pyspellchecker word lists, NLTK words/names (en only).
  They gate keep/drop decisions at build time; none of their data ships.
- Refresh procedure: re-run the fetch documented in `build_wordlist.py --help`
  (googlesource `?format=TEXT` base64 endpoint), regenerate the gz
  (`gzip.compress(body, 9, mtime=0)`), update this table.
