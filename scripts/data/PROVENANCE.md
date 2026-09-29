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

## Static context LM corpora (`scripts/build_static_lm.py` → `src/main/assets/lm/<lang>.cklm`)

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

### Other languages (2026-09-29, `--lang es|de|fr|it|pt|sv`)

Each language is one `LangConfig` in `scripts/build_static_lm.py`; the pins below are copied from
it. Every row was fetched 2026-09-29. Whether a language's model SHIPS is decided by its S1 gate
(`docs/eval/2026-09-29-static-lm-multilingual.md`); a row stays recorded either way so a failed
candidate can be rebuilt byte-identically. **As of 2026-09-29 none of these languages ships** (Spanish failed
S1, which stopped the rollout); "counts only" below describes what a shipped model would carry. Tatoeba rows are the `*_sentences_detailed` exports
(the per-sentence username is what the CC BY 2.0 FR contributor list is generated from).

| Lang | Key | File | URL | License | Shipped? | Fetched | sha256 |
|---|---|---|---|---|---|---|---|
| es | leipzig | `leipzig/spa_web_2016_300K.tar.gz` | https://downloads.wortschatz-leipzig.de/corpora/spa_web_2016_300K.tar.gz | CC BY 4.0 | counts only | 2026-09-29 | e7f921d53d542fc9997c8d8b9c198add96961624b274e50289ee9936bcfd362d |
| es | tatoeba | `tatoeba/spa_sentences_detailed.tsv.bz2` | https://downloads.tatoeba.org/exports/per_language/spa/spa_sentences_detailed.tsv.bz2 | CC BY 2.0 FR | counts only | 2026-09-29 | 76425b39fcfba2e0acb5348fb172bb5eeead611876f1b1daf4f3f00a7ae9ac9d |
| es | ood_dev | `ud/es_gsd-ud-dev.conllu` | UD_Spanish-GSD @ `267f3530d4f122ee85d1891800211a06dfb79347` | CC BY-SA 4.0 | **no** (weight selection only) | 2026-09-29 | 704fb19afc0a34cff476e1a70351b7026c4364f862e8e8388b02873852567c22 |
| es | ood_test | `ud/es_gsd-ud-test.conllu` | UD_Spanish-GSD @ `267f3530d4f122ee85d1891800211a06dfb79347` | CC BY-SA 4.0 | **no** (gate evaluation only) | 2026-09-29 | ecce253f44bffaa9803ae7ec0c10911c13e4f1fc3cb439dbd1db2cebe0a12741 |
| de | leipzig | `leipzig/deu-de_web_2021_300K.tar.gz` | https://downloads.wortschatz-leipzig.de/corpora/deu-de_web_2021_300K.tar.gz | CC BY 4.0 | counts only | 2026-09-29 | 3fed1175ee2c76fd169d00ff28b402e139082ae9bf02b9c72a04f0ce163185bc |
| de | tatoeba | `tatoeba/deu_sentences_detailed.tsv.bz2` | https://downloads.tatoeba.org/exports/per_language/deu/deu_sentences_detailed.tsv.bz2 | CC BY 2.0 FR | counts only | 2026-09-29 | eb80b6a8d6b938a48fc33d65848d6efaf53d264c96e458ba6e4deec2deaaab59 |
| de | ood_dev | `ud/de_gsd-ud-dev.conllu` | UD_German-GSD @ `ce54dbe9c6a5640c93e9952f069f582f6cd1f9fc` | CC BY-SA 4.0 | **no** (weight selection only) | 2026-09-29 | 01e8e674973592747ffe9a8c77fcf9d2f5936a8484e731ad4f76254318a8952c |
| de | ood_test | `ud/de_gsd-ud-test.conllu` | UD_German-GSD @ `ce54dbe9c6a5640c93e9952f069f582f6cd1f9fc` | CC BY-SA 4.0 | **no** (gate evaluation only) | 2026-09-29 | 595070aa50b706a91dc66f17c296f7a9a25cbc75269f177c27680fb1c21528ab |
| fr | leipzig | `leipzig/fra-fr_web_2013_300K.tar.gz` | https://downloads.wortschatz-leipzig.de/corpora/fra-fr_web_2013_300K.tar.gz | CC BY 4.0 | counts only | 2026-09-29 | 4c917a4929a12d6e7c8abf1c90b3fc41e6839a90d88f18a834180bdc2dbc7593 |
| fr | tatoeba | `tatoeba/fra_sentences_detailed.tsv.bz2` | https://downloads.tatoeba.org/exports/per_language/fra/fra_sentences_detailed.tsv.bz2 | CC BY 2.0 FR | counts only | 2026-09-29 | c54845fd6a01649d4cbb17d1af736ad24afce93eabd045d63db2f1f0d6c5492a |
| fr | ood_dev | `ud/fr_gsd-ud-dev.conllu` | UD_French-GSD @ `94d5b68e185fc22a9ef292040e84f476d36d9b0e` | CC BY-SA 4.0 | **no** (weight selection only) | 2026-09-29 | 9221e5084cc6a1b1540671cfbd2fc0456efc223b2fe8dd2146c2bad921be02d4 |
| fr | ood_test | `ud/fr_gsd-ud-test.conllu` | UD_French-GSD @ `94d5b68e185fc22a9ef292040e84f476d36d9b0e` | CC BY-SA 4.0 | **no** (gate evaluation only) | 2026-09-29 | eee5a599b429658b6ee8582fae9993eb07161247fc64bad57d16b2050ed4eb1a |
| it | leipzig | `leipzig/ita-it_web-public_2019_300K.tar.gz` | https://downloads.wortschatz-leipzig.de/corpora/ita-it_web-public_2019_300K.tar.gz | CC BY 4.0 | counts only | 2026-09-29 | e8be0d4f3a49a627a8bcf5cb93160419ba170ac794f561d16ea89de413def42e |
| it | tatoeba | `tatoeba/ita_sentences_detailed.tsv.bz2` | https://downloads.tatoeba.org/exports/per_language/ita/ita_sentences_detailed.tsv.bz2 | CC BY 2.0 FR | counts only | 2026-09-29 | a07bbd0f64f226edb7109f7f955a6456518863fef50d64f83573ed5efc99a399 |
| it | ood_dev | `ud/it_twittiro-ud-dev.conllu` | UD_Italian-TWITTIRO @ `ff4c607327db615e219e1613b6e804ec55a4cffc` | CC BY-SA 4.0 | **no** (weight selection only) | 2026-09-29 | 1f16040ae17395d2910784742e6b3344620a67937524d47ed96fd56f811a8c99 |
| it | ood_test | `ud/it_pud-ud-test.conllu` | UD_Italian-PUD @ `71d932aa096368331ba2fd9bc906ec8dd853da8e` | CC BY-SA 3.0 | **no** (gate evaluation only) | 2026-09-29 | ad5d302bdfd05194155d9ad43dc54c3cb915076d66b3eb6ef33eca9a11f1372a |
| pt | leipzig | `leipzig/por-pt_web_2015_300K.tar.gz` | https://downloads.wortschatz-leipzig.de/corpora/por-pt_web_2015_300K.tar.gz | CC BY 4.0 | counts only | 2026-09-29 | 8ba1f5e84cbcc3924277396962b2b6851a06472aad640a80f8837694b9d64695 |
| pt | tatoeba | `tatoeba/por_sentences_detailed.tsv.bz2` | https://downloads.tatoeba.org/exports/per_language/por/por_sentences_detailed.tsv.bz2 | CC BY 2.0 FR | counts only | 2026-09-29 | 3c76d939d12528971264728fa48f4de52170d13eb2a36340b82fde311c3ad8f4 |
| pt | ood_dev | `ud/pt_bosque-ud-dev.conllu` | UD_Portuguese-Bosque @ `884288537f7e8e02e50f125791cf279d905d1043` | CC BY-SA 4.0 | **no** (weight selection only) | 2026-09-29 | f8a67abae12fbab85a3995204a6459d1958c11065062d4ea5349d6b914e78f81 |
| pt | ood_test | `ud/pt_bosque-ud-test.conllu` | UD_Portuguese-Bosque @ `884288537f7e8e02e50f125791cf279d905d1043` | CC BY-SA 4.0 | **no** (gate evaluation only) | 2026-09-29 | 9a824650b7a02cf411f6e09b39e8fb423c85e1a5fb3a55b87e7f4c0c2ea5b3bb |
| sv | leipzig | `leipzig/swe-se_web_2023_300K.tar.gz` | https://downloads.wortschatz-leipzig.de/corpora/swe-se_web_2023_300K.tar.gz | CC BY 4.0 | counts only | 2026-09-29 | c5a86fb055db346076055a64b8a4537e5a8787e61a4131bf9cf81f11ab4a0773 |
| sv | tatoeba | `tatoeba/swe_sentences_detailed.tsv.bz2` | https://downloads.tatoeba.org/exports/per_language/swe/swe_sentences_detailed.tsv.bz2 | CC BY 2.0 FR | counts only | 2026-09-29 | 63fa7da77b5a9f72d0d30777bb1868b9ae871a020c79e8b8d0db213d3efbe71a |
| sv | ood_dev | `ud/sv_talbanken-ud-dev.conllu` | UD_Swedish-Talbanken @ `c434778d9511be5c35a6a11531f0107a960fb5d6` | CC BY-SA 4.0 | **no** (weight selection only) | 2026-09-29 | e1c14ae088f575d9f5f2d870d456b9e3911a04725f141752bb8c972a81cb6c6c |
| sv | ood_test | `ud/sv_talbanken-ud-test.conllu` | UD_Swedish-Talbanken @ `c434778d9511be5c35a6a11531f0107a960fb5d6` | CC BY-SA 4.0 | **no** (gate evaluation only) | 2026-09-29 | f7bc84ce37cd6a71e95b8d8684801da0bb6d2be4e419ba2e865eb109d6cd1bc6 |

Choice of corpus (the Leipzig listing was read 2026-09-29): the newest general-WEB 300K corpus for
the language's main locale, since web text is closest to keyboard register (en uses web too).
fr has no France web corpus after 2013 and pt none for Brazil at all (Portugal 2015 is the newest
web corpus), so fr/pt are older and pt is European-Portuguese web; es/de/it/sv are 2016–2023.

Choice of evaluation treebank: a CC BY-SA UD treebank, preferring web text like en's EWT.
**Italian avoids ISDT/VIT/ParTUT/PoSTWITA** — they are CC BY-NC-SA ("research purposes only") —
and uses TWITTIRO (tweets, CC BY-SA 4.0) dev for weight selection plus PUD (CC BY-SA 3.0, 1,000
news/wiki sentences; TWITTIRO's test split is only 142 tweets) as the gate population.
Portuguese uses Bosque (PT + BR news), matching the pt_BR ∪ pt_PT lexicon.

Leakage: for these languages the builder drops any corpus sentence identical to an OOD dev/test
sentence before counting (per-corpus counts in each sidecar's `corpus.evalOverlapDropped`).
English predates the step and keeps its byte-identical build.

Every Tatoeba export rotates weekly: the same rebuild caveat as for English applies.

Notes:
- The Leipzig 1M variant (`eng-com_web-public_2018_1M`) was not used: the 300K file was already
  local and the model is capped at 512 KB, so extra corpus mostly buys pairs the cap prunes.
- Artefact filter reference: `wordfreq` (already a NOTICE'd dependency of the dictionary
  pipeline); it only decides which words are dropped, none of its data ships in the model.
- The UD English-EWT files are evaluation data and never reach the APK.
