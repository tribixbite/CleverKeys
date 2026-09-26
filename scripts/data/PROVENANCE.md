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

> **Snapshots removed from the tree 2026-09-09** (all rows except `aosp_en`,
> which the F-Droid metadata already scanignores): F-Droid's source scanner
> flags `.gz` files, and keeping the tree scanner-clean avoids an fdroiddata
> scanignore MR per release. The table below is now the RECORD, not an index of
> present files — each row's sha256 + upstream header pin the exact bytes, and
> the Refresh procedure at the bottom re-fetches byte-identical snapshots
> (googlesource `?format=TEXT`, `gzip.compress(body, 9, mtime=0)`) whenever a
> dictionary rebuild needs them. Rebuilds are dev-time only; nothing in the APK
> or CI consumes these files.

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
| `aosp_he_wordlist.txt.gz` | `iw` (legacy code for he), version 44, date=1393228136 | Apache-2.0 | 2026-09-01 | 94,799 | 3f3b47d53ae8 |

Role: positive keep-oracle — mobile-keyboard-curated vocabulary (names, casual
register, abbreviations). For sv/el/tr/he the AOSP snapshot is the SOLE band-2
oracle (Tier C in `build_wordlist.py`'s LANG_CONFIG); he's is published under
the legacy ISO code `iw`. id/ms/tl have no AOSP dictionary upstream (probed
2026-07-20 — no `<code>_wordlist.combined.gz` in the LatinIME tree) and run
oracle-less (Tier D, band == top); the same probe on 2026-09-01 found no
uk/bg/mk dictionary either, so those three also run Tier D.

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

## Static context LM corpora (`scripts/build_static_lm.py` → `src/main/assets/lm/en.cklm`)

Not committed (large, and two are only needed at build time); cached under
`~/.cache/cleverkeys-corpora/`. The builder refuses any file whose sha256 differs from the pin
below unless `--allow-unpinned` is given, and the sidecar `src/main/assets/lm/en.json` records
the hash each build actually used (`StaticLmAssetDriftTest` fails on an unpinned build).

| Key | File | URL | License | Shipped? | Fetched | sha256 |
|---|---|---|---|---|---|---|
| leipzig | `leipzig/eng-com_web-public_2018_300K.tar.gz` | https://downloads.wortschatz-leipzig.de/corpora/eng-com_web-public_2018_300K.tar.gz | CC BY 4.0 ([download terms](https://wortschatz.uni-leipzig.de/en/usage) — the download files, NOT the CC BY-NC web API) | counts only | 2026-09-26 | cc6a36b245e68523bc92b9d8131ac93592116b0167b3785418fcee9eb9c0dad7 |
| tatoeba | `tatoeba/eng_sentences_detailed.tsv.bz2` | https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences_detailed.tsv.bz2 | CC BY 2.0 FR | counts only | 2026-09-26 | 353d48de7905952cf6f1500f6a3158516ecf9e10cd844ba051982cfa4a11c111 |
| ood_dev | `ud/en_ewt-ud-dev.conllu` | UD_English-EWT @ `4a4d77f599ea53cc405f85d0cec4b2f14f81d42b` | CC BY-SA 4.0 | **no** (weight selection only) | 2026-09-26 | 39239e0a60db3ae68f4b7036189f11b6692741d10ff8240dd91f74f2760d90f8 |
| ood_test | `ud/en_ewt-ud-test.conllu` | UD_English-EWT @ `4a4d77f599ea53cc405f85d0cec4b2f14f81d42b` | CC BY-SA 4.0 | **no** (gate evaluation only) | 2026-09-26 | fa024f43dc5da3c5ac02563bc9bd0e974f46cbb1560823976a8f342a37dc494a |

**Tatoeba rotates its export weekly.** The URL above serves a new file every week, so a fresh
download will NOT match the pin and the builder will refuse it. Rebuilding this exact model
needs the 2026-09-26 snapshot (sha256 above) from a local mirror; nothing has been uploaded
anywhere. Rebuilding from a newer snapshot is legitimate — pass `--allow-unpinned`, then update
this row and the pin in `SOURCES` once the new model is accepted — but it is a different model
and must be re-evaluated (`StaticLmTapEvalTest`). The contributor list
`scripts/data/tatoeba-contributors-en.txt` is regenerated from the same file and must be
committed with the model.

Notes:
- The Leipzig 1M variant (`eng-com_web-public_2018_1M`) was not used: the 300K file was already
  local and the model is capped at 512 KB, so extra corpus mostly buys pairs the cap prunes.
- Artefact filter reference: `wordfreq` (already a NOTICE'd dependency of the dictionary
  pipeline); it only decides which words are dropped, none of its data ships in the model.
- The UD English-EWT files are evaluation data and never reach the APK.
