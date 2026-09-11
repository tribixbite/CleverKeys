# Pinyin IME (zh-Hans / zh-Hant) — Feature Specification

**Feature Name**: Pinyin composing IME
**Priority**: P3 (contribution-track; maintainer priority call)
**Status**: In progress — Phases 0–2 implemented (container format, tap engine, swipe feed);
device validation and pack data outstanding
**Target Version**: TBD
**Issue**: [tribixbite/CleverKeys#177](https://github.com/tribixbite/CleverKeys/issues/177)
**Owner**: Macho0x
**Reviewers**: maintainer

## Feature Overview

### Summary

Add a **Pinyin composing IME** for Simplified (`zh-Hans`) and Traditional (`zh-Hant`)
Chinese: the user types or swipes toneless pinyin on the existing QWERTY letters, the
keyboard shows a pinyin preedit, the suggestion bar shows ranked 汉字/漢字 candidates, and
tapping a candidate (or space) commits it. Committed text is Hanzi; keys stay Latin; no
JNI, no new ONNX model, no network.

### Motivation

CleverKeys language packs assume **committed text = the letters on the keys**. A `zh`
wordfreq dump of `你/好` cannot be swiped on QWERTY, and a prefix dictionary over Hanzi
cannot be typed. Chinese needs two things the current formats do not model:

1. a way to declare that a pack's content is *not* a wordfreq lexicon
   ("compose Latin keys into other text"), and
2. a **1:N pinyin key → ranked candidate text** table — `CKDT` stores exactly one display
   word per lookup key, so it cannot express `nihao → 你好, 妳好, …`.

There is no FOSS daily-driver path for Pinyin swipe on this keyboard today. This spec pins
the pack-level contract and the container format first, so the engine work (taps, swipe,
composing region) lands against a fixed schema instead of inventing one mid-implementation.

### Goals

- A sideloaded `langpack-zh-Hans.zip` / `langpack-zh-Hant.zip` (SAF, same flow as every
  other pack) that declares `"inputMethod": "pinyin"` and carries a `phrases.bin`.
- On-device lookup only: pinyin key → ranked Hanzi candidates, prefix-queryable.
- Reuse of the shipped swipe engines: geometric already decodes Latin surfaces on QWERTY;
  CTC decodes the same once `zh` has a lexicon. Neither needs retraining.
- Password/PIN fields never compose, never convert, never learn.
- English lexicon isolation while Chinese is active (the existing language-switch rules).

### Non-Goals

- JNI, libpinyin/fcitx5, or any new native dependency.
- Retraining CTC or adding a per-script ONNX encoder for Hanzi (the CTC emission head is
  Latin/script slots, not a 20k-class Hanzi softmax).
- Cloud input, telemetry, or network fetches. Ever.
- A new keyboard layout or a second chrome; QWERTY + the existing suggestion bar.
- Full sentence segmentation / smart phrase decoding in the first engine cut. The phrase
  table may contain multi-syllable keys (`nihao`, `beijingdaxue`) so common phrases work;
  general lattice segmentation is a follow-up.
- Learning Hanzi choices into the n-gram model in the first cut.

## Requirements

### Functional Requirements

1. **FR-1 Pack declaration**: a pack may declare `"inputMethod": "pinyin"` in
   `manifest.json`. Absent means `"wordfreq"` (the legacy contract). Unknown values are
   refused at import with a reason; they never silently degrade to wordfreq.
2. **FR-2 Phrase table required**: a pinyin pack must carry `phrases.bin` (V1 `CKPY`).
   Missing or malformed → import error. A `phrases.bin` present without the pinyin mode is
   refused (nothing would read it; a silent drop turns a manifest typo into a broken
   keyboard).
3. **FR-3 Lookup**: the engine can answer exact (`nihao`) and prefix (`ni` → `ni`, `nian`,
   `niang`, `nihao`) queries against the parsed table, in rank order.
4. **FR-4 Tap composing**: typing `a–z`/`'` while pinyin mode is active appends to a session
   buffer instead of committing letters. The preedit is visible to the user.
5. **FR-5 Candidate selection**: the suggestion bar shows ranked Hanzi candidates for the
   current buffer. Tapping one commits it; space commits the top candidate (configurable
   later); enter commits the top candidate and runs the editor action.
6. **FR-6 Backspace edits the buffer**: backspace deletes one pinyin character while the
   session is non-empty; only when the buffer is empty does it reach the editor.
7. **FR-7 Swipe does not auto-commit**: a swipe decodes to a pinyin surface, which enters
   the session as preedit and refreshes candidates. The existing auto-insert of the top
   swipe prediction is suppressed for pinyin. Tap/space commit, matching FR-5.
8. **FR-8 `v` = `ü`, apostrophe disambiguates**: `nv` looks up `女` (the builder maps
   `ü` → `v`); `xi'an` and `xian` are distinct keys. No tone digits are required; source
   tone marks are stripped by the builder.
9. **FR-9 Password/PIN bypass**: in a password/PIN/number field, pinyin mode is inert —
   letters commit directly, no preedit, no candidates, no learning. `SuggestionBar`
   password mode already owns this classification (`SuggestionBar.isPasswordField`).
10. **FR-10 Isolation**: while the pinyin pack is the active primary language, English
    candidates are not merged into the slate (same rule as the secondary-language merge),
    and pinyin/Hanzi text never enters the English n-gram context.
11. **FR-11 Failure behavior**: if the phrase table is absent/corrupt at runtime, the
    keyboard falls back to committing the typed Latin letters (never a dead end) and the
    settings support surface reports the pack as broken.

### Non-Functional Requirements

1. **NFR-1 Latency**: candidate lookup ≤ ~5 ms for a 500k-key table on mid-range hardware;
   lookup is a binary search + contiguous scan, no per-keystroke table rebuild.
2. **NFR-2 Memory**: the parsed table is bounded (≤ 64 MiB file, ≤ 1M keys, ≤ 512
   candidates/key, ≤ 120 B key, ≤ 64 B candidate text) and is parsed once per pack
   generation, not per keystroke.
3. **NFR-3 Determinism**: key order (strictly ascending), candidate rank order
   (non-decreasing), and the Python/Kotlin byte layouts agree by construction; a round-trip
   test pins them.
4. **NFR-4 Privacy**: 100 % on-device, no telemetry; pack import stays streaming (no whole
   entry materialised) per the v1.1.96/v1.1.97 OOM fix.
5. **NFR-5 No regression**: existing packs, dictionaries, and tests are untouched by the
   new field; a pack without `inputMethod` parses and imports exactly as before.

### User Stories

- **As a** Simplified-Chinese typist, **I want** to type `nihao` and tap 你好, **so that** I
  can write Chinese without leaving CleverKeys.
- **As a** swipe typist, **I want** to swipe the pinyin spelling and pick from candidates,
  **so that** I get the same speed as English swipe typing.
- **As a** privacy-conscious user, **I want** password fields to bypass composition
  entirely, **so that** nothing about my credentials is buffered or learned.

## Pack Format (implemented in this PR)

### Manifest

`manifest.json` gains one optional field. Everything else is unchanged
([`LanguagePackManager.parseManifest`](../../src/main/kotlin/tribixbite/cleverkeys/langpack/LanguagePackManager.kt)):

```json
{
  "code": "zh",
  "name": "中文（拼音）",
  "version": 1,
  "author": "",
  "wordCount": 40000,
  "hasPrefixBoost": false,
  "inputMethod": "pinyin"
}
```

| Field | Type | Default | Meaning |
|---|---|---|---|
| `inputMethod` | string | `"wordfreq"` | `"wordfreq"` = dictionary entries are the committed words (legacy, all existing packs). `"pinyin"` = composing pack; requires `phrases.bin`. |

Key order is fixed by the builder (`scripts/build_langpack.py` writes `inputMethod` only
for non-wordfreq packs) so existing packs stay byte-identical. The installed manifest
round-trips: `LanguagePackManager.getInstalledPacks()` reports the mode.

### Pack members

| Member | Required | Notes |
|---|---|---|
| `manifest.json` | yes | as above |
| `dictionary.bin` | yes | V2 `CKDT`; for pinyin packs its words are **pinyin spellings** (`nihao`, `xi'an`) so the swipe decoders can produce them. Ranks are pinyin frequency. |
| `phrases.bin` | for pinyin | V1 `CKPY`; the conversion table. |
| `unigrams.txt`, `contractions.json`, `prefix_boost.bin`, `model.onnx` | no | unchanged |

The dictionary is still required so the pack remains a normal pack (and because the swipe
decoders need a lexicon); `phrases.bin` is what makes the committed text Chinese.

### CKPY v1 — pinyin phrase table

Little-endian throughout, matching `CKDT`. Reader:
[`pinyin/CkpyPhraseTable.kt`](../../src/main/kotlin/tribixbite/cleverkeys/pinyin/CkpyPhraseTable.kt).
Writer: [`scripts/build_phrase_table.py`](../../scripts/build_phrase_table.py).

```
Header (48 bytes):
  offset 0   magic       uint32   0x59504B43 = "CKPY"
  offset 4   version     uint32   1
  offset 8   language    char[4]  UTF-8 NUL-padded, e.g. "zh\0\0"
  offset 12  keyCount    uint32   number of pinyin keys (>= 1)
  offset 16  dataOffset  uint32   offset of the data section (48 in v1)
  offset 20  reserved    byte[28] zeros

Data section (at dataOffset), keyCount records in STRICTLY ASCENDING key order:
  uint16 keyLength                       (1..120 UTF-8 bytes)
  byte[keyLength] key                    (normalized: ^[a-z']+$)
  uint16 candidateCount                  (1..512)
  candidateCount candidate records:
    uint16 textLength                    (1..64 UTF-8 bytes)
    byte[textLength] text                (UTF-8; 汉字/漢字/phrase; no NUL/U+FFFD)
    uint8 rank                           (0 = most preferred; non-decreasing)
```

Rules the reader enforces (all tested):

- magic + version exact; `dataOffset` within the file and ≥ 48; no trailing bytes;
- keys strictly ascending (byte order), so exact lookup and prefix runs are binary-searchable;
- keys match `^[a-z']+$`; candidate ranks non-decreasing within a key;
- every length checked before slicing; counts capped (anti-OOM for untrusted packs);
- the reader streams with a 64 MiB ceiling rather than trusting a stream.

`CkpyPhraseTable.Table` offers `lookup(key)` (exact) and `withPrefix(prefix)` (lower-bound
binary search + contiguous forward scan). Ranks are engine-relative, never probabilities —
the same convention as `CKDT`'s rank byte and `PredictionResult.scores`.

### Key normalization (fixed at both ends)

| Rule | Example |
|---|---|
| lowercase ASCII; only `a-z` and `'` | `NiHao` → `nihao` |
| `ü` and the toned ü forms (`ǖǘǚǜ`) → `v` | `lü` → `lv`, 女 = `nv` |
| other tone marks stripped (NFD, drop combining) | `nǐ` → `ni` |
| digit tone numbers stripped | `ni3` → `ni` |
| curly/backtick apostrophes → `'` | `xi’an` → `xi'an` |
| `xi'an` stays distinct from `xian` | both are valid, different keys |

### Builder pipeline

```bash
# 1. Phrase table: TSV `pinyin<TAB>text[<TAB>weight]`, grouped + rank-sorted by weight.
python3 scripts/build_phrase_table.py --lang zh --input phrases.tsv --output phrases.bin

# 2. Pack: dictionary.bin holds pinyin spellings (wordfreq words file of pinyin),
#    phrases.bin holds the Hanzi candidates.
python3 scripts/build_langpack.py --lang zh --name "中文（拼音）" \
    --dict zh_pinyin.bin --input-method pinyin --phrases phrases.bin \
    --output langpack-zh-Hans.zip
```

`build_langpack.py` refuses the same couplings the importer refuses: pinyin without a
`phrases.bin`, a phrase table without the mode, a non-CKPY-v1 table, or a table over the
64 MiB reader cap.

### As-built test pins (Phases 0–2)

| Behavior | Test |
|---|---|
| CKPY header bytes, record framing | `src/test/kotlin/tribixbite/cleverkeys/pinyin/CkpyPhraseTableTest.kt` (`headerFieldsPinTheCkpyV1Layout`, `recordFramingPinsLengthPrefixedUtf8AndRankBytes`) |
| exact + prefix lookup, normalization | same file (`lookupIsExact`, `withPrefixReturnsTheContiguousRunInKeyOrder`, `lookupsRequireANormalizedQuery`) |
| malformed tables refused | same file (magic/version/truncation/ordering/ranks/trailing/empty) |
| manifest field, member carry, couplings, per-code manifest read | `src/test/kotlin/tribixbite/cleverkeys/langpack/LanguagePackImportTest.kt` (6 tests) |
| buffer normalization, candidate assembly, segmentation, edit rules | `src/test/kotlin/tribixbite/cleverkeys/pinyin/PinyinSessionTest.kt` (15 tests) |
| activation, password bypass, missing-table fallback, composing region, committed fallback, candidate taps, swipe feed | `src/test/kotlin/tribixbite/cleverkeys/pinyin/PinyinControllerTest.kt` (10 tests) |
| the new `IReceiver` hooks are delegated by the bridge | `KeyEventReceiverBridgeDelegationTest` (existing ratchet) |

## Runtime Design (implemented for Phases 1–2)

### Architecture

```
 tap a–z/' ─► KeyEventHandler.key_up ─► IReceiver hook ─► PinyinController
                                                              │  PinyinSession buffer "nihao"
                                                              ├─► CkpyPhraseTable.withPrefix/lookup
                                                              │       → ranked 汉字 candidates
                                                              ▼
                                                         SuggestionBar (reused; Unicode-capable)
                                                              │ tap / space
                                                              ▼
                                            InputConnection.commitText("你好", 1)

 swipe ─► SwipeEngineRouter (geometric today; CTC optional once zh is served)
               └─ decoded pinyin surface ─► SuggestionHandler pinyin hook
                                              └─► same session (preedit only, NO auto-commit)
```

### Components

1. **`PinyinSession`** (`src/main/kotlin/tribixbite/cleverkeys/pinyin/PinyinSession.kt`,
   implemented): pure state machine over a parsed table. Actual API:
   `appendLetter(ch): Boolean`, `appendSurface(surface): String`, `backspace(): Boolean`,
   `candidates(limit = 8): List<Candidate>`, `topCandidate()`, `clear()`. Normalizes case,
   `ü`→`v`, curly apostrophes; caps the buffer at 48 chars.

2. **Phrase engine** (implemented in `PinyinSession.candidates`): exact key match first,
   then completion keys the buffer prefixes, de-duplicated by text; when neither matches,
   greedy longest-match segmentation joins known syllables (`woaini` → 我+爱+你) with bounded
   second-candidate variants. The full lattice decoder remains deferred.

3. **Preedit / composing region — Q1 resolved by implementing both paths**:
   `PinyinController` publishes the run with `InputConnection.setComposingText(preedit, 1)`
   and commits with `commitText(hanzi, 1)`. If the editor refuses the FIRST
   `setComposingText` of a field (returns false), the controller flips that field to the
   committed-text fallback: the same buffer is mirrored as real text and updates use
   `commitText`/`deleteSurroundingText` — the pattern the English replace path already uses.
   Both paths are pinned by `PinyinControllerTest`; **device validation on real editors is
   still required before release** and may flip the default.

4. **Key interception** (implemented):
   - `KeyEventHandler.key_up` `Kind.Char`/`Kind.String` calls `IReceiver.pinyinHandleText`
     before `sendText`, so autocap/TSR/context tracking never see composing letters;
   - `KEYCODE_DEL` calls `pinyinHandleBackspace` (buffer first, editor once empty);
   - other key events call `pinyinHandleKeyevent` (Enter commits the top candidate and is
     NOT consumed; Escape cancels);
   - suggestion taps route through `SuggestionBridge.onSuggestionSelected` →
     `PinyinController.onCandidateSelected` (only a word the session offered is consumed);
   - `KeyEventReceiverBridge` gates the text hook on all modal panes (clipboard
     tag/edit/search, emoji, GIF) so they keep owning the keyboard.

5. **Suggestion bar**: reused as-is. Pinyin candidates carry
   `SuggestionOrigin.PINYIN` (`SuggestionMeta`), a marker color, and the
   `provenance_origin_pinyin` label, so the long-press provenance sheet names the source.

6. **Swipe serving**: `SuggestionHandler.handleSwipePredictionResults` calls the
   `PinyinComposingHook` BEFORE the password/empty guards and before every English step
   (rescore, possessives, auto-insert), so a pinyin decode buffers the spelling and never
   auto-commits. Geometric serves pinyin spellings today (QWERTY projection); the optional
   CTC `zh` row remains deferred until a corpus evaluation exists.

7. **Mode selection / language plumbing** (implemented): `PinyinController` evaluates the
   active primary language per field via `LanguagePackManager.getInstalledPack(code)` +
   `getPhrasesPath(code)`, re-checks on language change during typing, and stays inactive
   when the table is missing/corrupt (keys commit Latin). `LanguageDetector` still has no
   `zh` profile and `PredictionContextTracker` still bails on CJK cursor sync — explicit
   selection only.


### Privacy and security

- No network permission is involved; everything is on-device.
- Password/PIN: classification already exists (`SuggestionBar.isPasswordField`,
  `CleverKeysService.onStartInputView:705-720`, `SuggestionHandler.setPasswordMode`); pinyin
  mode must check it in the same places the English path does, and must clear the session on
  field change.
- Learning: pinyin commits must respect `LearningGate.fieldAllowsPersonalizedLearning` and
  `PrivacyManager` consent; P1 does not add user-selection learning at all.
- Pack import: the new member flows through the existing staging + path-safety + streaming
  machinery; the format reader bounds every untrusted length before use.

## Testing Strategy

- **Pure JVM** (`runPureTests`): `CkpyPhraseTableTest` (14) — layout bytes, lookup, prefix,
  malformed inputs, bounds; `PinyinSessionTest` (15) — normalization, candidate assembly,
  segmentation, edit rules. Run green on 2026-09-11.
- **Mock tier** (`runMockTests`): `LanguagePackImportTest` (35) — mode parsing, member
  carry, the two couplings, unknown modes, rejection cleanup, per-code manifest read;
  `PinyinControllerTest` (10) — activation, password bypass, missing-table fallback,
  composing region, committed-text fallback, candidate taps, swipe feed. Both green on
  2026-09-11.
- **Full pure suite**: 2,400 tests green except a pre-existing timing microbenchmark
  (`GeoBenchmarkTest`, environment-sensitive), on 2026-09-11.
- **Python**: `build_phrase_table.py` verifies its own output by re-parsing it before
  writing; `build_langpack.py` refuses the invalid combinations. A generated table is parsed
  by the Kotlin reader during development (cross-tool round-trip exercised 2026-09-11:
  Python writer → Kotlin reader, including `lü`→`lv` and `ni3`→`ni`).
- **Still required**: on-device validation (composing-region behavior in real editors,
  geometric swipe quality, multi-field switching), and instrumented pipeline coverage in
  the style of `PipelineCharacterizationTest`.

## Implementation Plan

### Phase 0 — schema + container format (DONE)

- [x] `docs/specs/pinyin-ime.md` (this document)
- [x] `inputMethod` manifest field, `phrases.bin` member, validation + storage accessor
- [x] `CKPY` v1 reader + unit tests; Python writer; `build_langpack.py` integration
- [x] Index rows in `docs/specs/README.md` / `docs/TABLE_OF_CONTENTS.md`
- [x] Composing-region decision (Q1): implemented BOTH — composing region primary, per-field
  committed-text fallback when the editor refuses it (see Component 3). Maintainer may flip
  the default after device validation.

### Phase 1 — tap-first engine (CODE DONE; device validation + pack data outstanding)

- [x] `PinyinSession` + candidate assembly, wired to `KeyEventHandler.key_up`
- [x] Suggestion bar candidate display; tap/space/enter commit; backspace editing
- [x] Password bypass; fallback-to-Latin on missing table
- [ ] First real pack built (pinyin wordfreq spellings + open phrase source), device-tested
  — blocked on Q4 (data source/licence) and on hardware access

### Phase 2 — swipe (CODE DONE; device validation outstanding)

- [x] Suppress auto-insert for pinyin; feed decoded surface into the session
- [ ] Validate geometric pinyin swipe quality on device
- [ ] Optional: `zh` row in `CtcLanguageSupport` (PROVISIONAL) once a corpus evaluation exists

### Phase 3 — polish (PARTIAL)

- [ ] `zh-Hans` / `zh-Hant` pack variants (shared pinyin keys; separate phrase tables) —
  pack-data work, no code change required
- [ ] Candidate ranking with context; user-selection learning behind the privacy gates —
  deliberately deferred: needs a Hanzi context signal that does not exist yet, and a
  storage/privacy design of its own
- [x] Greedy longest-match segmentation for unbounded input
- [ ] Full lattice decoding; settings surface for space behavior — deferred to their own
  change (a new Config key + settings UI + backup registry + drift-test updates)

## Deferred (explicitly not in this change)

| Item | Why |
|---|---|
| Device testing of the composing region / swipe quality | requires the maintainer's test phones |
| Distributable `zh-Hans` / `zh-Hant` packs | data source/licence decision (Q4) |
| Context-aware candidate ranking, selection learning | needs Hanzi context + privacy/storage design |
| Space-behavior setting | fixed to Chinese convention (commit, no trailing space) for now |
| CTC `zh` row | no pinyin swipe corpus to justify the PROVISIONAL tier |
| Lattice segmentation | greedy longest-match ships first; lattice is an accuracy upgrade |


## Dependencies

- No new libraries. Kotlin stdlib only for the reader; Python 3 stdlib for the writer.
- Existing pieces reused: pack import/staging (`LanguagePackManager`), suggestion bar,
  geometric/CTC adapters, password mode, preference reload plumbing.

## Security Considerations

- `phrases.bin` is untrusted input: every field is length-checked and capped before use;
  the parse fails loudly rather than returning a truncated table.
- Import remains header-only validation + streaming copy; the new member does not weaken the
  v1.1.96 OOM fix (`LanguagePackImportTest.theImportPathNeverReadsAWholeEntryIntoMemory`
  still scans `importFromStream`).
- Unknown `inputMethod` values and orphan phrase tables are refused, so a pack cannot claim
  a mode the app will misinterpret.

## Error Handling

| Scenario | Behavior |
|---|---|
| Missing/invalid `phrases.bin` on import | `ImportResult.Error` with a specific message; nothing installed |
| Unknown `inputMethod` | refused at import with the value named |
| Corrupt/unreadable table at runtime | `PinyinController` stays inactive; keys commit raw Latin; no user-facing report yet (settings surface deferred) |
| Table absent but pack selected | same degradation; English/normal behavior remains available |
| Password/PIN field | session disabled before any buffer exists |

## Open Questions

1. ~~**Composing region vs commit-and-replace**~~ — RESOLVED 2026-09-11 by implementing
   both: composing region primary, per-field committed-text fallback when the editor
   refuses `setComposingText`. The maintainer's device validation decides whether the
   default changes.
2. **Space behavior**: fixed to commit-top-without-trailing-space (Chinese convention,
   Q2). A user-facing setting remains future work.
3. **Pack code shape**: `zh` + name variants vs `zh-hans`/`zh-hant`; the import regex
   accepts both (`^[a-z]{2,3}(?:[_-][a-z0-9]{1,16}){0,4}$`), but settings/SubtypeManager
   display has no `zh` row yet. Not decided by this change (pack naming is data-side).
4. **Phrase data source/licence** for a distributable pack (e.g. Rime/librime dictionaries,
   CC-CEDICT-derived pinyin data) — pack publication is a separate decision from the format.
   OPEN — blocks the first real pack.

## Future Enhancements

- Fuzzy pinyin (zh/z, ch/c, sh/s, n/l, front/back nasal) as a table-level variant key.
- Simplified/Traditional toggle at runtime (two phrase tables; shared pinyin keys).
- User phrase learning (selection counts) stored per language behind the learning gate.

## References

- Issue: tribixbite/CleverKeys#177
- `src/main/kotlin/tribixbite/cleverkeys/pinyin/CkpyPhraseTable.kt` — reader, layout KDoc
- `src/main/kotlin/tribixbite/cleverkeys/pinyin/PinyinSession.kt` — pure composing state machine
- `src/main/kotlin/tribixbite/cleverkeys/pinyin/PinyinController.kt` — IME glue (activation,
  composing region + fallback, candidates, commit)
- `src/main/kotlin/tribixbite/cleverkeys/pinyin/PinyinComposingHook.kt` — swipe seam
- `src/main/kotlin/tribixbite/cleverkeys/langpack/LanguagePackManager.kt` — manifest + import
- `scripts/build_phrase_table.py`, `scripts/build_langpack.py` — writers
- `docs/specs/ctc-architecture-and-multiscript-guide.md`,
  `docs/specs/geometric-swipe-engine.md`, `docs/specs/secondary-language-integration.md`,
  `docs/specs/password-field-mode.md`, `docs/specs/suggestion-bar-content-pane.md`

---

**Created**: 2026-09-11
**Last Updated**: 2026-09-11
**Owner**: Macho0x
