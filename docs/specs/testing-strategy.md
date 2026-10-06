# Testing Strategy Specification

Updated: 2026-10-06. Counts below describe executed tests, not source annotations or
estimated coverage percentages. Automated passing results do not certify human swipe
accuracy, all editor implementations, device fonts, or translated copy fluency.

## Expanded-coverage baseline (`ef36222c`)

| Suite | Result | Evidence |
|-------|--------|----------|
| Pure JVM | 2,741 passed | `build/oct6-selection-guard-tests-build.log` |
| MockK | 953 passed | `build/oct6-selection-guard-mock-recheck.log`; stale cursor fixture corrected |
| Android lint | Passed; 0 errors, 216 warnings | `build/oct6-expanded-gap-lint.log`; 46m54s |
| Full instrumented | 1,491 distinct tests passed; 0 failures/errors/skips/flakes | Pixel7/API34, orchestrator, three unfiltered shards, run `3f0c33a3-c71b-49ff-a87b-da044859eb54` |

Final cloud artifacts are under `~/ew-output/oct6-full-expanded-green`. Frozen APK SHA-256:

- App: `29753b130a5c594202425b8dc2ca206b67639506bfea2a315ab0d1e648e938a5`
- Test: `871f883c956156221c681ab9d4d8659efad8e69cf53231936ecfbc33d6de30cc`

The merged XML contains all 1,491 distinct methods, matching the first unfiltered
inventory except for the corrected settings test name. All 14 new cases passed.
Final run: [emulator.wtf results](https://emulator.wtf/o/64da92b3-67fb-427a-b56d-11e62fff8751/r/3f0c33a3-c71b-49ff-a87b-da044859eb54),
about 12 minutes wall time across three shards. No test filter, skip or timeout was used.

The first unfiltered run `3c321584-4bdb-45e3-b834-4daa1b0c36a3` passed 1,488/1,491
in about 34 minutes; failures were an obsolete Word Prediction section assertion and
two benchmarks missing experimental model assets. The assertion was corrected to
Input Behavior and four actual test-only encoders were supplied. Both affected classes
then passed 9/9 with no errors/skips (`7beda687-b5c6-4792-b352-a16a9e6b3730`) before
the complete rerun. Earlier test-APK SHA was
`fede978725fe39aaaace59eeff411d235161f22e240905c930a3748c24b8f53e`;
that red run is retained as diagnosis evidence, not the final verdict.

## Accepted-commit follow-through

After the full baseline, six host regressions and two native rejection/exception
wrappers were added. The shared engine now requires editor acknowledgement before
new-word ownership/learning and returns the actual inserted spelling. The swipe caller
removes prediction fallbacks after failure. Legacy pre-commit replacement deletion
remains a separate verified-receipt gap.

- Full Kotlin compilation, **2,741 pure + 959 mock** pass; debug APKs build
  (`build/oct6-accepted-commit-full-tests-build-v2.log`, 6m56s).
- Focused fail-first: 22/27; fixed including capitalization control: 28/28.
- Native **33 distinct punctuation tests** pass, 0 failures/errors/skips:
  [results](https://emulator.wtf/o/64da92b3-67fb-427a-b56d-11e62fff8751/r/3e8f6000-e943-4110-9746-c5bd8508e46e).
- Frozen app SHA: `ffbfce11f79cf2b9e362331370c764b3bdc9cf65d370a9f7387c8245c49bc4f6`.
- Frozen test SHA: `fff71ee151e8d9e4ffed6e037e32763666b5012c3137a1157fc5fffba0087dd9`.

The 1,491-test full run above predates this follow-through. New native cases will be
included in the final full run after the three named features; do not merge different
APK results into an invented full-suite verdict.

## October 6 missed-gap coverage

Fourteen native regressions were added to existing test files:

| Production boundary | New cases | Test class |
|---------------------|-----------|------------|
| Config refresh and terminal routing | 2 | `TerminalUtilsInstrumentedTest` |
| Minimized native touch geometry and compact layout sizing | 3 | `Keyboard2ViewCustomMappingRenderTest` |
| Themed emoji glyph filtering and text faces | 2 | `EmojiSearchTest` |
| Oversized pack update despite understated manifest count | 1 | `swipe/CtcImportedPackInstrumentedTest` |
| Literal curly apostrophe, manual space, selected-range replacement | 3 | `SmartAutoSpaceTest` |
| Actual size dialog and confirmed multi-page deletion races | 2 | `ClipboardFilterDialogTest` |
| Persisted custom gesture on cold start with swipe disabled | 1 | `PointersGestureRoutingTest` |

Existing Config checks now assert initialization instead of swallowing fixture
exceptions, and the SmartAutoSpace predictor fixture fails rather than silently
skipping on OOM. Selected-range fixtures report both real selection endpoints.
UIAutomator operates the deliberately nonfocusable IME dialog; tests wait for actual
dismissal before reopening it. Test clipboard rows are uniquely scoped and cleaned.

The selected-range regression exposed a production defect: a range starting at an
owned auto-space stamp could remove the space before that range. Punctuation now
checks both reported selection endpoints before reclaiming that automatic space.
Editors without selection data retain the existing text/ownership fallback; this is
separate from the strict readback required by planned suffix commands.

- Fail-first run `0685b528-9d4c-4624-8cb5-077b750852d0`: 56/57 passed; the regression
  expected `Bowie '` but observed `Bowie'`.
- Fixed regression run `41ecb6e6-ac8d-4ce0-8f5b-ec66a45cc214`: 1/1 passed,
  zero errors/skips, using the same app and the earlier test APK described above.
- Earlier focused runs exposed and corrected fixture assumptions about text faces,
  detached RTL remeasurement, one-pixel bounds rounding, and asynchronous dialogs.
  They are diagnosis runs, not a claim that the final full suite passed.

## Experimental benchmark fixture provenance

Full-suite test inputs come from `../CleverKeys-ML/ctc/artifacts/`, copied into ignored
`src/androidTest/assets/ctc_bench/`. They are not production encoders and are not
committed or packaged in the app APK.

| Model | SHA-256 |
|-------|---------|
| ch128_s1234 | `6c1144949e545f626419e1fa7b29e80f9ecf3e303886f30411fc37ae72c45c51` |
| ch192_s1234 | `d5b5f10ea16f08743d0742b3c60aa37a469ada11c418a7f459d5ae4cff20c666` |
| fast_resbn80_s1234 | `5e8c88756cbad5a5a8b8b3f289a990174fa6f3b6edfead46d8dbdb2927fb06f2` |
| fast_resbn72_s1234 | `6567366b61bbbd04b5353f7f780aedb9aa507f7a87f52a381089cb54bf510985` |

## Minified device artifact

Release Kotlin compilation, lint-vital, R8 and resource shrinking passed in 5m45s
(`build/oct6-expanded-gap-release.log`). ARM64 APK signature v2, ZIP CRC, page/4-byte
alignment via the installed `zipalign -c -p 4`, native ELF architecture and exclusion
of test encoders were verified. SHA-256:
`3f2fed25767860d8d857340264ed0ec4c61c7682ff44a9e99ad452c88c6afc4c`.
Logs: `build/oct6-expanded-gap-release-verification.log` and
`build/oct6-expanded-gap-release-alignment.log`. The installed zipalign lacks `-P`;
this does not establish 16 KiB page compatibility. Seeker was absent from `adb devices`,
so this artifact has not been installed or device-tested. No tag/version/push/release.

## Documentation verification

The updated wiki builds all 84 Astro pages. Eight touched paired-spec `user_guide`
frontmatter routes and 428 local wiki/spec links in the affected rendered pages
resolve. Internal engineering notes link to GitHub source because they are not public
Astro pages. Log: `build/oct6-expanded-docs-site-final.log`.

## Running checks

All Gradle operations on this Termux checkout use the singleton guard. Standard
`testDebugUnitTest` is disabled locally: `runPureTests` runs pure JVM tests directly;
`runMockTests` adds MockK and Android stubs. Run both after code changes.

```bash
./scripts/gradle-guard.sh compileDebugKotlin runPureTests runMockTests
./scripts/gradle-guard.sh assembleDebug assembleDebugAndroidTest
mkdir -p ~/ew-output/new-full-run
EW_VERSION=1.3.4 ew-cli \
  --app build/outputs/apk/debug/CleverKeys-v2.0.0-x86_64.apk \
  --test build/outputs/apk/androidTest/debug/CleverKeys-debug-androidTest.apk \
  --device model=Pixel7,version=34 --use-orchestrator --timeout 40m \
  --outputs merged_results_xml,logcat --outputs-dir ~/ew-output/new-full-run
```

Use a fresh output directory and freeze/hash the app/test pair before a long run.
Read the final CLI verdict and merged XML together; missing tests after timeout or
skips do not establish full completion. Orchestrator discovery/start messages can
count a test twice in logcat: count distinct executed test names, not raw starts.
Never print `EW_API_TOKEN`. The exact workflow and troubleshooting are in
[ew-cli skill](../../.claude/skills/ew-cli-testing.md).

Cloud tests use debug x86_64 APKs with matching debug signatures. Seeker device checks
use the signed, minified ARM64 release build. A debug cloud pass does not replace R8,
release lint, or installed-APK freshness checks. Do not change the app version or
publish a release just to test it.

## Remaining manual and architectural coverage

- Actual IME lifecycle and touch pass-through across different apps, RTL service-window
  placement, accessibility, large fonts, and device-specific emoji fonts.
- External terminal editor behavior; host mocks establish routing rather than every
  SSH application's handling of key events.
- Fresh writer/session-separated human traces for `ad`/`wet`; synthetic paths and suite
  totals do not establish encoder accuracy.
- Continuous multiword swipe, explicit apostrophe suffix transactions/undo, and dynamic
  templates are pending implementations. Existing tests do not cover unimplemented
  behavior.
- Final language-pack directory-swap recovery if rename fails after deleting the old
  directory. The oversized-update test protects preflight rejection only.

<!-- TODO: Add final-swap recovery coverage when the importer retains an old-directory
backup; add feature-specific native coverage with each pending implementation. -->

## Historical reference

The August 18 full cloud sweep reported 1,395 tests. September 27 host suites reported
2,610 pure and 859 mock tests. These dated measurements do not replace current results.
The original January `:core` module proposal was never implemented: testable CTC and
geometric code instead lives directly under `swipe/ctc/` and `swipe/geometric/`.
The transformer-era beam/vocabulary/prefix-boost classes were removed with ADR-011.

## Translation verification (2026-09-27, extended 2026-09-29)

All 21 locales are machine-translated, and none has had a native-speaker review. The
automated gates below catch structural, terminology and length defects. They cannot certify
fluency.

### Automated (pure JVM, in `runPureTests`)

- **`TranslationCoverageDriftTest`** checks structure. It pins which resources are
  `<string>` vs `<plurals>` and requires every pinned key in every locale. It also checks
  per-item plural placeholders, indexed format arguments, and guards against English
  copy-paste (at least 12 of 21 locales must differ from English). The 2026-09-27 audit
  confirmed all 21 files parse. Each has all 936 translatable resources, and the 19
  default-only ones are `translatable="false"`. Literal percentages in
  `formatted="false"` strings and Hungarian `%-a` are not formatter arguments. Android lint
  remains the formatting-syntax gate.
- **`TranslationGlossaryTest`** checks terminology. `docs/i18n/glossary.json` maps each
  product concept (swipe, dictionary, suggestion, learn, clipboard, layout, gesture;
  correction for zh/vi; "word" for tr) to the keys that express it. For each locale it lists
  allowed and forbidden term stems. Every keyed string must contain an allowed stem and no
  forbidden one. Matching is NFC-normalized, ROOT-lowercased, drops U+0307, and is
  word-initial unless the locale sets `matchAnywhere` (compounding languages, CJK). The
  checks are key-scoped, so Czech *tažením* meaning "drag" in an unrelated string is not
  flagged. The test also requires the glossary to cover exactly the `res/values-*` locales,
  and it pins the matcher with unit cases. Fail-first: 66 violations before the 2026-09-29
  fixes, 0 after. The choices and counts are in `docs/i18n/2026-09-29-terminology.md`.
  When adding a locale or concept, add its glossary entry with a `note` saying why.
- **`TranslationLengthTest`** is an overflow heuristic. It flags a translation longer than
  **1.75×** English for suggestion-bar copy (`suggestion_*`; a 2-line chip in a horizontal
  strip), or longer than **2.0×** English for `*_title`, `provenance_origin_*` and the
  single-line command-palette rows (`cmd_*`, `command_category_*`, added 2026-09-30). In all
  cases the translation must also exceed **40** code points. Format arguments count as a
  4-character word. It counts code points, not glyph width, so CJK expansion is invisible
  to it. A justified exception goes in its `accepted` map with a reason. On 2026-09-29 it
  found 8 offenders, all shortened: hu ×4 (2 titles, 2 bar offers), ru and es titles, uk and tr bar offers.

- **`TranslationGlossaryTest` also checks the address register.** It became an address-register
  gate on 2026-09-29. Each locale's `register` entry in `glossary.json` lists Java regexes for
  forms of the register that locale does not use. No `<string>` and no `<plurals>` item may match
  one of them. hu is pinned to formal Ön; the on-device finding mixed te and Ön forms in the
  Privacy section. The decisions, counts and AOSP comparison are in
  `docs/i18n/2026-09-29-register.md`.
- **`HardcodedUiStringTest`** (2026-09-29) finds user-visible text that was never a resource.
  It is a pure source scan for prose literals in rendering positions: Compose `Text`, named
  display parameters, toasts, dialogs, status and suggestion-bar messages, dropdown option
  lists, and `when` branches that return labels. Hardcoded manifest `android:label` values also
  fail it. Fail-first: 439 literals in 44 files, and 0 after the sweep. Exceptions live in its
  `allowed` map, each with a reason. The inventory and deferred items are in
  `docs/i18n/2026-09-29-hardcoded-ui-sweep.md`.
- **`RtlMirroringDriftTest`** requires `Icons.AutoMirrored.*` for back/forward/next arrows,
  because the fa device run showed them pointing the wrong way.

### Model-assisted (repeatable, not automated)

- **Blind back-translation.** Give a separate model only the target-language strings,
  under neutral IDs with no English and no key names. Ask for a literal English rendering
  plus notes, then diff it against the English source. Focus on privacy and destructive
  actions: recording vs reading, what gets deleted, what remains, reversibility. The first
  run is recorded in `docs/i18n/2026-09-29-back-translation-review.md` (reviewer = model).
  It found that the learning-off prompt's bare "selection history" read as text selection
  in about 15 locales. That was fixed in all 22, including English.
- **Terminology dominance.** Count the candidate terms per concept across each
  `strings.xml`. When usage is split, use AOSP LatinIME's translation (Apache-2.0; LineageOS
  mirror, since android.googlesource.com and the old aosp-mirror were unavailable).

### Pseudolocales (visual long-text and RTL checks)

The debug build type sets `pseudoLocalesEnabled true`; release does not. Install a debug
build on a dedicated test phone or emulator, never the Termux host. Then switch the
device language to **English (XA)** (accented, about 30% longer, bracketed) or **Arabic
(XB)** (mirrored RTL). Both appear after enabling Developer options. From adb:
`adb shell am start -a android.settings.LOCALE_SETTINGS`. On API 33+ you can instead set
the per-app language: `adb shell cmd locale set-app-locales tribixbite.cleverkeys.debug
--locales en-XA`, then reset with `--locales ""`. Restore the device language afterwards
(leave no trace). Resize screenshots below 2000 px in both dimensions and 4 MB before
processing. Check the suggestion-bar offers, the privacy dialogs, settings rows and the
Persian (real RTL) locale.

### Still requires native speakers

- Semantic and fluency review of every locale, starting with privacy and deletion copy. Each
  back-translation NOTE in the review doc is an open question.
- The established splits listed in `docs/i18n/2026-09-29-terminology.md`: es/nl/uk/fa layout,
  vi clipboard, tr kelime/sözcük, fil mungkahi/suhestiyon, and whether it *scorrimento*,
  tr *kaydırma*, fa *کشیدن*, lv *vilkšana* and pt *deslize* read as swipe typing.
- Device checks on a dedicated test phone or emulator, never the host phone: long text with
  enlarged fonts, TalkBack, and Persian RTL.

Record the reviewer's language competence and any unresolved wording. Never label machine
output as native-reviewed.
