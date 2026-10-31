# Licence texts

Full texts of the licences that [`NOTICE`](../NOTICE) cites, one file per licence, named by
SPDX identifier. They are packaged into the APK, together with `NOTICE` and `LICENSE`, by the
Gradle task `generateLicenseAssets` (assets `licenses/*.txt`) and shown in the app under
Settings → Help & FAQ → Open-source licenses (`OpenSourceLicensesActivity`).

The project's own licence, GPL-3.0-only, is `LICENSE` at the repository root; the build packages
it as `licenses/GPL-3.0-only.txt`, so it is not duplicated here.

`OpenSourceLicensesAssetDriftTest` fails when `NOTICE` names a licence that has no file here,
when a file here is cited by nothing, or when the packaged copies differ from these sources.
When a `NOTICE` section adds a licence, add its text here and its entry to
`OpenSourceLicenses.LICENSES` (`src/main/kotlin/tribixbite/cleverkeys/legal/`).

| File | Source (fetched 2026-10-31) | sha256 |
|---|---|---|
| `Apache-2.0.txt` | SPDX license-list-data `text/Apache-2.0.txt` | `074e6e32c86a4c0ef8b3ed25b721ca23aca83df277cd88106ef7177c354615ff` |
| `CC-BY-2.0-FR.txt` | <https://creativecommons.org/licenses/by/2.0/fr/legalcode> (French), legal-code body converted to plain text by `scripts/cc_legalcode_to_text.py`, title and URL lines added on top | `d6c0c7b1435a3648bb9166c1eb3ea06e5fd1d0cc6fbfa6c8e9a23afcad323074` |
| `CC-BY-4.0.txt` | SPDX license-list-data `text/CC-BY-4.0.txt` | `d557539df68e771cc1eedcc91d13f70fca930e508d11eedcafa4b15db49e3744` |
| `CC-BY-SA-3.0.txt` | SPDX license-list-data `text/CC-BY-SA-3.0.txt` | `3f941b3b89cf7b8370ceb83cc76d2120d471b58735d8ca60238a751a48d7f72f` |
| `CC-BY-SA-4.0.txt` | SPDX license-list-data `text/CC-BY-SA-4.0.txt` | `cde7883b9050a1104f4ac19a1572aafd6e5d7323b68351aaf51fbf4beba54966` |
| `LGPL-3.0-only.txt` | <https://www.gnu.org/licenses/lgpl-3.0.txt> | `e3a994d82e644b03a792a930f574002658412f62407f5fee083f2555c5f23118` |
| `MIT.txt` | SPDX license-list-data `text/MIT.txt` (the template; each MIT component's own copyright line is in `NOTICE`) | `b05785f9f18e6716bab63424b11454513b9943a222595b70411009202fc592b5` |
| `Unicode-3.0.txt` | SPDX license-list-data `text/Unicode-3.0.txt` | `f7db81051789b729fea528a63ec4c938fdcb93d9d61d97dc8cc2e9df6d47f2a1` |

SPDX files are from <https://github.com/spdx/license-list-data> (`main`, `text/`). SPDX has no
CC BY 2.0 FR entry, hence the conversion from Creative Commons' own page.
