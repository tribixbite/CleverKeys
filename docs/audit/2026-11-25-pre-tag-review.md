# Pre-tag review of everything since the last audit, and a remediation check (2026-11-25)

Maintainer 2026-11-25: "have fable sub agent audit changes since last audit and remediations of all
audits since 1.5. request task list for bugs /gaps before tagging release".

Scope:
- the 141 commits `bac666c1..baa75be8` (the 2026-11-14 review ended at `bac666c1`): 276 files under
  `src/main`, about 10k changed Kotlin lines, split by package into four batches;
- a fifth pass over every audit since v1.5.0 (2026-08-23 delta, 08-25 verification, 08-28 ARC
  ledger, 09-06 comprehensive, 09-10 memory, 09-26 learning and data licensing, 10-22 and 11-23
  translation, 10-31 readiness, 11-14 post-v1.5 review), checking the code at HEAD rather than the
  docs.

Five read-only Fable agents. Nothing was built, run or edited by them. Evidence tiers as in the
2026-11-14 review:
- **re-read**: the lead session re-read the cited lines afterwards and agrees;
- **traced**: the agent read every link end to end;
- **plausible**: the trigger depends on framework or device behaviour nobody observed.

Release r15 (`baa75be8`, arm64 sha256 `fd88c1edcd200c09…`) was built the same day and is not
installed on any phone; the Saga and the Note10+ still run r14 (11:46, before `a535b83a` and
`2265ec39`).

## 1. Blockers for the tag

| # | Finding | Where | Tier |
|---|---|---|---|
| B1 | The ß autocorrect step builds every reading of a run of `s` (Fibonacci growth, no cap, every language, on every keystroke with autocorrect on). 25 `s` give 121,392 strings, about 30 give 1.3 M, and about 35 run the keyboard out of memory. | `WordPredictor.kt:318-338` (`sharpSReadings`), called at `:2604` | re-read |
| B2 | The tag workflow's source-hygiene preflight (`git diff --check <prev v-tag>..HEAD`) fails: 24 whitespace errors in 14 files (F-Droid recipe 9, `SettingsActivity.kt:966`, two tests, docs, two `.patch` files under `docs/eval/`). `scripts/prepare-release.sh:24` lacks `--match 'v*'`, so the local preflight passes where CI fails (CK-150-017 regressed). | `.github/workflows/release.yml:55-71` | re-read |
| B3 | The `langpacks` GitHub release still serves the 2026-09-27 de/es/fr/it/pt/sv packs (de 1,416,484 bytes online against 416,210 in `scripts/dictionaries/`). The repo packs were rebuilt 11-19…11-25 (accent misspellings, ß, no `prefix_boost.bin`). A re-upload is a GitHub publish: maintainer's approval. | release `langpacks` | re-read |

## 2. Should fix before the tag (agent work)

| # | Finding | Where | Tier |
|---|---|---|---|
| S1 | Under the "size" History limit, a newest clip larger than the whole budget deletes every History row, the new one included (`firstOver == 0`). Default limit type is count, so a non-default setting is needed. `ClipboardSizeBudgetTest` has no such case. | `clipboard/ClipboardEntry.kt:231-238`, `ClipboardDatabase.kt:1445` | re-read |
| S2 | Imports run in the Settings activity's `lifecycleScope`, and Settings is recreated on rotation (no `configChanges`). If you rotate during a GIF pack import, the pack can be half-installed (rows with no thumbnails, or an old pack removed and the new one missing), and the import reports "Unknown error". Backup, clipboard and dictionary imports complete but report failure, and skip `loadCurrentSettings()` and the dictionary-reload broadcast. Voice packs got the fix (`VoicePackImportTracker`); the others did not. | `ui/settings/io/SettingsGifHandlers.kt:93-143`, `SettingsBackupHandlers.kt:377-420`, `SettingsLanguagePackHandlers.kt:91-137` | traced (cancellation semantics not run) |
| S3 | Touch typing does not disarm the rotation show guard: `onKeyboardTouched()` clears only the modifier carry, and `_rotationGuard.onUserInput()` is called from hardware `onKeyDown` only. If an app recreated by rotation hides the keyboard within 2 s of the user typing and submitting (a search, for example), the keyboard can be shown again, up to twice. A pane the user closed after a rotation can also reopen (`_rotationPane` is not cleared on a manual close). | `CleverKeysService.kt:1403-1405`, `:1293`, `:1051-1062` | re-read (wiring); timing plausible |
| S4 | The English contraction table inflates 12 keys whose display form wordfreq splits into two tokens: `d'art` 176→205, `y'all` 170→204, `ma'am` 149→196, `o'er` 153→188, `ne'er` 150→182, and the `d'…` loans (`detat`, `detre`, …) to 148–163. `d'art` now competes with `haven't` on a d-a-r-t swipe. | `scripts/extract_apostrophe_words.py:1383`, `contraction_frequencies_en.json` | traced |
| S5 | The French table raises 49 PAIRED keys (`dune` 99→204, `lest`, `laide`, `jaime`, `na`, `sen`, …). The overlay keeps the base word in a paired key's slot and puts the elision at the tail, so the raise boosts `dune`, not `d'une`. The eval's 22 improvements include `l'est`, which the bar shows as `lest`, so the +1.38 pt top-1 overstates the bar for paired targets. | `scripts/build_elision_frequencies.py:97-116`, `CtcContractionKeys.kt:151` | traced (data, code); ranking effect plausible |
| S6 | French elisions seen once or twice calibrate to 75, above the 7,098 real words at 68–74 (5,702 keys sit at exactly 75, including junk like `l'x`, `l'ob`, `d'abc`). The adapter comment and spec still say a pseudo-word never outranks real vocabulary. Proposed: a minimum corpus count for a raise. | `contraction_frequencies_fr.json`, `CtcEngineAdapter.kt:845-857` | traced |
| S7 | Auto-Detect still picks the best of the detector's six languages before filtering to the chosen two. With en+es chosen, Spanish text that scores as pt switches nothing. A secondary language outside en/es/fr/pt/de/sv (it, nl, any pack) shows the toggle and slider but can never be switched to. Nothing pins either case (`LanguageDetectionEvalTest` is opt-in). | `LanguageDetector.kt:269-275`, `MultiLanguageManager.kt:111-114`, `MultiLanguageSection.kt` | traced |
| S8 | Restored clipboard rows take their expiry from the in-memory Config, not the stored setting, which is stale outside the keyboard process (the same class `UserVocabulary` was fixed for). | `ClipboardDatabase.kt:1849`, `ClipboardHistoryService.kt:1470` | traced read path; end to end plausible |
| S9 | A 1.x settings file's dictionary words are applied with no preview and no opt-out, and a failure there is logged while the import reports success. A file whose words are all present still offers an import, and a priorities-only change does not reload the keyboard. | `BackupRestoreManager.kt:1010-1023`, `backup/SettingsImportPlan.kt:102-111`, `SettingsBackupHandlers.kt:292` | traced |
| S10 | Docs that disagree with the code or with each other: `docs/ROADMAP.md` still names 1.6.0 the candidate (CK-150-013); the manual checklist's popover-upgrade and `d'être` rows predate the 11-25 results; `HANDOFF.md` lists done ARC items as open; the ARC ledger and TABLE_OF_CONTENTS list CK-150-027/029 as open; CTC spec German counts (39,598/402 against the pinned 39,588/412); CHANGELOG header "up to 2026-11-23"; README test counts dated 10-31; `per-key-actions-spec.md` says 241 commands (245); language pack count 24 vs 23; the elision eval doc's `qu'il` value (204.5 is `dun`; `quil` ships 188) and its "values 75–176.5" (first arm). | docs | traced |

## 3. Maintainer decisions before the tag

- **D1** Two shipped results were judged after the fact, both disclosed:
  - `de.cklm`: gate G4 failed as written (five cells −0.21 to −0.23 pt) and shipped on a later, restricted measurement;
  - the French second arm was chosen after the first result, on the same 2,291 rows, with no held-out set.
- **D2** German ß autocorrect: a typed `strasse` or `gross` becomes `straße` or `groß`, with no de-CH exemption. It also ignores the secondary language (German primary + English secondary: `fuss` → `fuß`).
- **D3** With the subkey popover on (the 1.5→2.0 migration turns it on for most upgraders), a slow press on Shift, Ctrl, Fn, ABC, 123+, Esc or Tab that has subkeys opens the grid. Released in the neutral zone it does nothing. This is intended, but it needs a release note: holding Shift for caps lock now behaves differently.
- **D4** Re-upload of the six stale language packs (B3).
- Still open from the readiness audit: M1 soak sign-off, M2 string review, M4 tag sequence.

## 4. Minor, can move to 2.1 (recorded so they have an owner)

- **Clipboard and backup:**
  - a bad `media_path` row aborts thumbnail regeneration and orphan cleanup on restore (`BackupRestoreManager.kt:1676, 2301`);
  - a failed full restore does not re-reconcile the private-copy component;
  - `ownEditOnSystemClip` can swallow one later identical copy;
  - an unreadable clip counts as empty;
  - the 2.0 settings export no longer carries the personal dictionary (deliberate; release note);
  - `ClipboardSizeBudgetTest.theDatabaseWalksTheRowsNewestFirst` checks source text only.
- **Settings UI:**
  - Extra Keys "Reset to defaults" loses its Undo on rotation;
  - Extra Keys reads `extra_key_*` raw;
  - Theme Manager listener raw getters (no trigger today);
  - `VoicePackImportTracker.leave()` ordering;
  - Layout Manager XML in instance state with no size bound;
  - `delete_word_url_pairs` has no listener case;
  - shared ZIP intents import with no confirmation or in-progress guard;
  - three drift tests narrower than their names;
  - Theme look editor's unlabelled "Use keyboard setting" checkbox for TalkBack.
- **Typing:**
  - URL pair delete can remove scheme and host when a link has no `?` and `;k=v` before the first `&`;
  - it fires on non-URL text such as `if(a&&b==c)`;
  - a swipe that leaves the keyboard now types its first letter (TODO `Pointers.kt:527`);
  - `UrlQueryDeleteRoutingTest` pins a `finishComposingText() == false` branch that cannot occur on a device.
- **Data and reproducibility:**
  - `de_DE_frami` fetched from LibreOffice `master` (pin a commit);
  - wordfreq version unpinned;
  - `build_elision_frequencies.py` has no `--check` and no asset drift test against its builder;
  - eight table keys with non-ASCII letters (`dévaluation`, `dêtre`, …) are dropped silently by the parser;
  - the frequency tables do not reach geometric mode;
  - `eliseo` blocklisted against the file's own rule;
  - the six rebuilt langpacks still say `version: 2`.
- **Open from earlier audits, not recorded as deferred until now:**
  - the September memory fix never validated on the Samsung phone;
  - CK-150-031 (English rescue index excludes 207 accented entries);
  - learning-system W9 (learning can switch off silently);
  - the swipe-offer bar device check (pending since 09-26);
  - `SwipeDebugActivity` quadratic log buffer;
  - multi-letter custom keys in the geometric projection;
  - Toasts in `ClipboardHistoryView.kt:1641, 1683` (outside `ImeToastBanDriftTest`);
  - TODO `KeyEventHandler.kt:1617`;
  - translation leftovers (fa, in, ro, nl, tr, pt; four strings changed after `8325b953e` unreviewed);
  - `EmojiKeywordIndex` unsynchronised;
  - GIF paging without `ORDER BY`;
  - no API 24–28 run recorded.
- **Fixed with no pinning test:**
  - CK-150-009;
  - ARC-009, 012, 018, 031, 032, 040;
  - GIF E-5 and the UI halves of E-4/E-8;
  - memory finding 3;
  - the translation wording fixes.
- **Owed measurements:**
  - rescue on vs off (CK-150-005);
  - dual-language latency figures (CK-150-026);
  - the Russian re-confirm (ARC-060);
  - a German real-trace replay after ß.

## 5. Remediation status, summary

Every Critical, High and P1 finding of every audit since v1.5 is fixed in code at HEAD, each with a
test, except CK-150-009 (default only, untested). One regression: CK-150-017 (B2). No ARC item is
open or regressed outright. The ARC-048 flat-root count is back to 150 files. The per-audit tables
are in the agent report this section summarises: CK-150-001…036, ARC-001…114 by group, the September
audit's 8 P1 + 24 P2 (all code-verified) and 37 P3 (lighter check), the memory, learning, licensing
and translation audits, and the November review's H1–H4 and M1–M26 (all fixed with tests).

## 6. Suspicions that turned out fine

- **Direct boot:** no new credential-storage read before unlock.
- **Blind fields:** pair delete is guarded for terminal and REMOTE fields.
- **Prefs:** `Config.refresh` and `reloadSanitizationSettings` read everything through `safeGet*`.
- **Translations:** all 10 new strings are in 21 locales, with 0 placeholder mismatches.
- **Slider stops and the `${applicationId}` permission:** both correct.
- **Learned-data import:** cannot strand clipboard rows.
- **DIY-theme rollback:** guarded.
- **Assets:** both contraction tables reproduce byte for byte from their builders, and the langpack bins equal the bundled ones.
- **Frequency tables:** the scales match their tries, raise-only holds, and a missing table gives an empty map with no crash.
- **German list:** 542 ß words and no pre-1996 forms.

## 7. Status 2026-11-25 (late night): remediation

All agent items are fixed with tests, each red before its fix: B1 `aa9b57ba`, B2 `73a51ab7`, S1 and
S8 `8b4bf500`, S2 `4fe3142b`, S3 `9426af0b`, S4–S6 `29e8f45c` (gates `cec0a86f`, results in
`docs/eval/2026-11-25-contraction-table-cleanup.md`), S7 `f90e6ca7` and `aa9b57ba`, S9 `16632023`,
S10 `e59719e7`. B3/D4: the six packs were re-uploaded on the maintainer's approval and verified by
download. D3 has its release note. D2: a word of the second language is no longer corrected (part
of a wider fix: no secondary-language word was safe from autocorrect); the de-CH question stays
open. D1, M1, M2 and M4 remain the maintainer's. Correction to S6 as written: `d'abc` has 3 corpus
hits and sat at 76, so it stays.

D2, later the same day: the de-CH half is a setting, `pref_german_swiss_spelling` ("Swiss spelling
(ss for ß)"), which respells every German lexicon at load and is seeded on for a de-CH / de-LI
system language (`docs/specs/dictionary-and-language-system.md` § Swiss German spelling). Not yet
checked on a phone.

2026-11-26, follow-up audit of the S3/S8 fixes (three should-fix findings, each red before its fix):
(1) S8 covered only the restored rows' expiry; the service still read every other History limit
from the in-memory Config. Limit type, count, total size, item size and the capture TTL now come
from the stored settings through one reader, `ClipboardHistoryLimits.fromSettings` (Config.kt,
also used by `Config.refresh`); `ClipboardStoredLimitsTest` (Config holds the old values, the store
the new). `PrivateCopyProcessTextActivity`'s pre-check stays on Config (TODO, 2.1); the service
re-checks the stored cap. (2) S3 disarmed the rotation show guard on any key touch, which lost the
re-show for typing while rotating. Only a submit (Enter to the app, the action key) or a hardware
key disarms it now (`RotationShowGuard.UserInput`); `RotationShowGuardTest`
`typingWhileRotatingStillGetsTheKeyboardBack` / `typingAndSubmittingDisarms`. Pane close unchanged.
(3) The alias-key floor comment and `docs/specs/ctc-swipe-engine.md` now state the real rule: a key
with an attested-frequency entry (>= 3 corpus hits) may outrank rare real words (fr: 2,670 keys at
76-80 above 7,098 words at 68-74); the floor binds only keys without one. No behaviour change.

**2026-11-26.** D1: both results confirmed on held-out data with gates committed first (German
next-word on Leipzig news 2023, French table on 521 qwerty/qwertz rows); assets unchanged. D2: the
de-CH half is a Swiss spelling setting (`de82d474a`). A Fable audit of this remediation found no
blocker; its three should-fixes are in (`0498aa112`, `e120bb2fd`, `a56d5d4b9`). Device runs on r16/r17
(Saga, Note10+, Pixel) found and fixed `dart` → `d'art` (pre-existing) and capitalised on-the-fly
words; S7's threshold could not be calibrated within its gate (default 0.6 kept, 2.1 scorer).
