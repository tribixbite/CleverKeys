# Data licensing audit — word lists and language packs (2026-09-26)

**Scope:** every word-frequency list CleverKeys distributes: the bundled dictionaries
(`src/main/assets/dictionaries/`) and the prebuilt language packs
(`scripts/dictionaries/langpack-*.zip`, mirrored on the
[`langpacks` release](https://github.com/tribixbite/CleverKeys/releases/tag/langpacks)).
The context-LM corpora (Leipzig, Tatoeba) and the CTC models were already covered in `NOTICE`
and `scripts/data/PROVENANCE.md` and are out of scope here.

**Verdict source:** Fable audit, verified against the primary sources on 2026-09-26. The
repository-side remediation below is done; the release-side actions at the end
were authorized on 2026-09-27 and completed the same day.

## Verdicts

| Pack(s) | Upstream data | Licence | Verdict |
|---|---|---|---|
| `langpack-en-norvig-50k.zip` | Peter Norvig `count_1w.txt` — derived from Google Web 1T 5-gram (LDC2006T13) | norvig.com's MIT covers only the **code**; LDC2006T13 §1.2 forbids redistribution | **No redistribution grant → removed** |
| `langpack-en-opensubtitles.zip`, `langpack-en-opensubtitles-50k.zip` | hermitdave/FrequencyWords 2018 (English), computed from OPUS OpenSubtitles / OpenSubtitles.org (Lison & Tiedemann 2016) | "MIT License for code. CC-by-sa-4.0 for content." → CC BY-SA 4.0 | Compliant; attribution was missing → **fixed** |
| 19 wordfreq packs: `en`, `en-wordfreq`, `de es fr it nl pt sv ru uk bg mk el he tr id ms tl` | wordfreq (Robyn Speer et al.) — incl. SUBTLEX, OpenSubtitles, Google Books Ngram, Wikipedia, Leeds, ParaCrawl | data CC BY-SA 4.0 (credit the SUBTLEX authors, attribute OpenSubtitles; Google Books Ngram link appreciated). wordfreq's FAQ: bare extractions without attribution do not satisfy BY-SA — **the attribution must travel inside the pack** | Compliant once attributed in-pack → **fixed** |
| 11 of those: `es fr de it nl pt sv ru el tr he` | + AOSP LatinIME word lists as an inclusion oracle (build_wordlist.py `LANG_CONFIG`; he = upstream `iw`) | Apache-2.0 | Compliant; credited in-pack → **fixed**. `en`/`en-wordfreq` packs predate the oracle (2026-01-06); `uk bg mk id ms tl` have no AOSP dictionary upstream |
| `langpack-sw.zip` | Swwiki, Kevin Donnelly (https://kevindonnelly.org.uk/swahili/swwiki/), from Swahili Wikipedia | CC BY-SA 3.0 → adapted under BY-SA 4.0 (3.0 §4(b), later version with the same licence elements) → GPLv3 one-way compatible | Compliant via the stated chain; attribution was missing → **fixed** |

**GPL-3.0 compatibility.** CC BY-SA 4.0 lists GPLv3 as a compatible licence
(https://creativecommons.org/compatible-licenses/). The conditions
(https://wiki.creativecommons.org/wiki/ShareAlike_compatibility:_GPLv3): keep the
attribution, licence notice and link; indicate changes; license the adaptation under
GPLv3 — CleverKeys uses GPL-3.0-only. CC BY-SA 3.0 is **not** itself GPL-compatible, which is
why the Swahili chain runs through 4.0 explicitly.

## Remediation (repository side — done)

| # | Change | Commit |
|---|---|---|
| 1 | `scripts/build_langpack.py`: `DATA_SOURCES` + `PACK_SOURCES` tables; manifest keys `license` / `attribution` / `source`; a `NOTICE.txt` member in every pack (source, licence, link, required third-party credits, change note "Modified: converted to a CleverKeys frequency list.", compatibility statement); flags `--data-source`, `--license`, `--attribution`, `--source`; refuses to build a pack with no known source; `--repack` mode | `19c132f7` |
| 2 | All 22 remaining packs rewritten with `--repack` (payload members byte-identical — verified per member by sha256; a second repack reproduces every zip byte-for-byte). `langpack-en-norvig-50k.zip` deleted | `19c132f7` |
| 3 | `LanguagePackImportTest.everyShippedPackImportsAndCarriesItsAttribution`: every shipped pack imports through the real importer, carries the three keys + `NOTICE.txt`, and the **installed** manifest keeps the attribution. Negative control: the pre-audit `langpack-nl.zip` fails it. `en-norvig-50k` name-shape literal → `en-web-50k` (test + `LanguagePackManager` KDoc) | `19c132f7` |
| 4 | `NOTICE`: removed the false "all prebuilt packs are generated from wordfreq" claim; per-list source map; sections for wordfreq (SUBTLEX authors, OpenSubtitles, Google Books Ngram link), FrequencyWords + OpenSubtitles.org/OPUS (Lison & Tiedemann 2016), Swwiki (3.0 → 4.0 → GPLv3 chain); AOSP section gains he | `3be9b3c4` |
| 5 | In-app: `help_third_party_data` (Help & FAQ) names the word-list sources in all 22 locales | `d4be49b2` |
| 6 | Docs: README langpack section + attribution paragraph, English pipeline diagram corrected (Norvig/OpenSubtitles never fed the bundled dictionary), Greek spec's "FrequencyWords is CC-BY-SA-3.0" corrected to 4.0, wiki `language-packs.md` attribution table, this audit, HANDOFF pointer | this commit |

### Why the packs were repacked, not rebuilt

A from-inputs rebuild cannot reproduce the published packs: the raw inputs are untracked
(`scripts/en_opensubtitles_*.txt`, `swahili_freq.ods`), the AOSP oracle snapshots were removed
from the tree (`scripts/data/PROVENANCE.md`, 2026-09-09), and the `prefix_boost.bin` members
came from `src/main/assets/prefix_boosts/`, deleted with the neural engine (ADR-011). A rebuild
would therefore change dictionary content, which needs its own evaluation; a licensing fix must
not carry one in. `--repack` changes only `manifest.json` (three keys appended; all existing
keys and their order preserved) and adds `NOTICE.txt`.

### Importer compatibility

`LanguagePackManager.importFromStream` extracts every member to a temp dir but installs only
the members it knows (`dictionary.bin`, `unigrams.txt`, `contractions.json`,
`prefix_boost.bin`, a declared `model.onnx`, `manifest.json`); `parseManifest` reads with
`optX` and ignores unknown keys. So current and older app versions import the new packs
unchanged: `NOTICE.txt` is not installed, the manifest (with the attribution keys) is.

**Follow-up (not built):** surface a pack's `attribution` / `license` / `source` in the
language-pack manager UI (and optionally keep `NOTICE.txt` on install). `LanguagePackManifest`
would need the three fields; `parseManifest` already tolerates them.

### Not regenerated

`web_demo/wiki/layouts/language-packs.html` retains historical content locally, but the deploy
workflow generates a redirect to `/wiki/layouts/language-packs/`; it does not publish the old
body. The canonical Markdown page builds successfully with Astro (verified 2026-09-27).

## New pack hashes (for the `langpacks` release body)

| Asset | Bytes | SHA-256 |
|---|---:|---|
| `langpack-bg.zip` | 910636 | c2e6ecbd436f62d52d35a46e5b5e56023161e137d20d60dc6679a3a070389808 |
| `langpack-de.zip` | 1416484 | e130bbf1dda779aa02fc199d9652e7ac6b4e6450b0ae008116d3ae516b8e1d7c |
| `langpack-el.zip` | 979829 | 60c66d50a2619ced2b484214145110c48957448d8d6f82e99230311672435f61 |
| `langpack-en-opensubtitles-50k.zip` | 575060 | 9d8651a743b623915edd4174b0a86ff8e3ce0a41cbfbfa27d2f6f1f758b9d5e1 |
| `langpack-en-opensubtitles.zip` | 290541 | 4650834213e915dfdf48b432467718370bc2e7ff27fe4285c8138ef5c6517317 |
| `langpack-en-wordfreq.zip` | 292634 | f44dec74ab5270c2dc84a1f1b88eadf0e53fa9e1985118bd188220f78044b21e |
| `langpack-en.zip` | 573466 | 06491654f867b25a4b38af4bcf88957f6056685fdd4abab5d03538f0fd6da24f |
| `langpack-es.zip` | 1404804 | 125ae24180708bfc13aebc8cc5cfc50ec661ee9ed58275867b0a1537a9e1517a |
| `langpack-fr.zip` | 1438435 | 586cf639fb48bbb46b1eee02c66afd85bed948e9eb64912e1b31f7ffce9c5793 |
| `langpack-he.zip` | 994019 | 21f49961fa9afaf17bc77225f16eb558ebb7410a2dba4d732f66436b0a014987 |
| `langpack-id.zip` | 1221165 | 469c84a17cc31a88e91d2e81c0f4c51371fe86bc0d73412ed65b1be958c20c28 |
| `langpack-it.zip` | 1351575 | 36f11c60c316c5834bd9fcc6921725b29a7464f9c283b46b9bcc36a4ace59240 |
| `langpack-mk.zip` | 1064991 | 607c8f66f24bae7eddb80c0814e217ad7c3f938badc565623176e67db82d3e2e |
| `langpack-ms.zip` | 1146463 | 8765a84d78a6de4d693206a2e37b1bd86f620cab083fa184c6f1effaf0cb5093 |
| `langpack-nl.zip` | 1507750 | bc7b96a566a851f1f7db5182ee7d0ef0f3df967abd4d3c7c981bf2e4e0863115 |
| `langpack-pt.zip` | 1313201 | eb9da2a0bdb7ab688e7520bebf86b47fcd5d6720f6bb2e30352abcc0d1a24e06 |
| `langpack-ru.zip` | 1058900 | bb6034d065dc5674802bffab916db305fde94542a5a27ed9a02e37f44c428a43 |
| `langpack-sv.zip` | 1499593 | 322f3cc7886d751f0549a8694c9878779c8ed5933b8793e27bd040aa221fed02 |
| `langpack-sw.zip` | 914994 | a2a2c4fa06d69286ba4811b152c04ae67721b9636de46060b94bc9380ba4f2f7 |
| `langpack-tl.zip` | 1080797 | 48af32fbdb82eea5459699681ecb68b39be1033f9b1f8ff7baecc3e6df1b62e2 |
| `langpack-tr.zip` | 393073 | 8d0b004a942e99bff7a922961d781fd2478a0fab1c56f1219c14a5332f5c555c |
| `langpack-uk.zip` | 1072032 | 842bb1f937b4d985aa52cd42e0575d32f6b03ecf591d6008751455c170f0e10b |

The six script packs' `model.onnx` members are unchanged, so their `manifest.model.sha256`
values and the app's `CtcScriptSupport` pins still match.

## Release-side actions — completed 2026-09-27

The maintainer authorized updating the existing release in the 2026-09-27 transcript.
Actions 1–3 below are complete. All 22 published ZIPs were downloaded again and their full
SHA-256 hashes match the table above. No new release or tag was created.
The reviewed body and before-state snapshot are in ignored `build/release-reconciliation/`.

1. **Delete** the `langpack-en-norvig-50k.zip` asset from the `langpacks` release (the data has
   no redistribution grant; removing it from the repo does not remove the published copy).
2. **Re-upload** the 22 rebuilt packs above over their existing assets (`--clobber`), from
   `scripts/dictionaries/` at or after commit `19c132f7`.
3. **Update the release body**: remove every mention of the Norvig pack, replace the SHA-256
   table with the one above, and add a short attribution paragraph (wordfreq / FrequencyWords
   / Swwiki / AOSP LatinIME, pack licence GPL-3.0-only, full text in each zip's `NOTICE.txt`).
4. Optional: a `CHANGELOG.md` line for the next release — "Language packs now carry their data
   attribution (NOTICE.txt + manifest licence keys); the English Norvig variant was withdrawn
   (no redistribution licence)." (Not added here: another session owns `CHANGELOG.md`.)
