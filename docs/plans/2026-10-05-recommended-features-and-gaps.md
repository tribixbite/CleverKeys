# Strategic Roadmap: Recommended Features & Architectural Gaps for CleverKeys

**Date**: 2026-10-05  
**Target Project**: [CleverKeys](file:///data/data/com.termux/files/home/git/swype/cleverkeys) (`tribixbite/CleverKeys`)  
**Context**: Comparative architectural synthesis against [WM Keyboard](file:///data/data/com.termux/files/home/git/swype/wmkeyboard), HeliBoard, and FUTO.

---

## Current execution status (2026-10-06)

This is a proposal queue, not a release checklist. The maintainer wants feature/bug work
and personal device testing before 2.0; tagging/publishing is not authorized.

| Item | Remaining work |
|---|---|
| 1.1 Terminal handling | Shared predicate and custom package setting implemented and tested. External terminal app smoke tests remain. |
| 1.2 Dynamic macros | Clipboard, cursor, UUID and selection expansion not implemented; timestamps already work. |
| 1.3 Clipboard encryption | At-rest encryption/unlock not implemented; backup encryption is a different, existing feature. |
| 2.1 Apostrophes | Literal ASCII/curly flick parity implemented; ASCII device-tested. Explicit suffix transaction/undo/learning and contraction chooser remain. Waypoints deferred. |
| Short-word recognition | `ad`/`wet` still fail with default CTC. Frozen heuristic arms rejected; fresh writer/session data and general calibration/training remain. |
| 2.2 Continuous swipe | Not implemented; needs gesture and model/segment validation. |
| 2.3 Dwell picker | Not implemented; needs latency/conflict measurements before implementation. |
| 3.1 Bangla | National/Provat tap layouts exist. Transliteration, spelling-preserving dictionary/mark support and a validated swipe model remain. |
| 4.1 Theme preview | Live sample exists; actual keyboard/state preview remains. |
| 4.2 Swipe trail | Proposed spline/velocity behavior remains; benchmark before claiming frame-rate or overhead. |
| Clipboard follow-up | Size filtering + confirmed all-page Delete results and #168 system-clear command implemented locally. Assigned-command disposable-clip device check remains. |
| Extra Keys follow-up | Autofill visibility, landscape scrolling and rotation-preserved search implemented and device-tested. |

October 6 expanded validation is complete: 14 added native regressions cover the
new terminal setting, minimize/compact geometry, emoji filtering, oversized updates,
apostrophe selection safety, size-dialog/confirmed batch races, and custom gesture cold
start. Local suites pass 2,741 pure + 953 mock. A native fail-first selected-range
apostrophe regression exposed and fixed removal of the space before a selected range.
The final unfiltered three-shard run `3f0c33a3-c71b-49ff-a87b-da044859eb54` passes
all 1,491 distinct tests, with zero failures/errors/skips/flakes. The earlier 1,488/1,491
run exposed a stale settings assertion and missing benchmark assets; both were corrected
and all tests rerun. Lint passes with 0 errors/216 warnings; minified ARM64 release
build and artifact checks pass. Seeker is currently absent from ADB, so that artifact
has not been installed. The [testing strategy](../specs/testing-strategy.md) records
APK hashes and run evidence. Guides, paired specs and relevant skills now match actual
behavior; 84-page docs build, 428 rendered links and 8 guide routes pass. Final
language-pack swap recovery remains a separate gap.

The next authorized features are continuous multiword swipe, explicit apostrophe
suffix commands, and dynamic templates. Accepted-commit bookkeeping is a prerequisite
for safe segment and suffix ownership. No release publication is authorized.

Full evidence and pending device checks: [`memory/todo.md`](../../memory/todo.md).

## 1. Executive Summary

CleverKeys uses local CTC and geometric swipe decoding and does not request the
Android INTERNET permission. The CTC evaluation reported 89.31% top-1 on its dated
test-2400 corpus; this does not establish current accuracy for every user or language.
The keyboard is drawn on Canvas; settings use Compose.

This queue proposes terminal productivity, explicit gesture intent, and additional
language support. Implementation must preserve offline operation, bounded resources,
existing customization, and dependable everyday typing. Each feature needs its own
editor/routing tests and device checks before a release claim. Priority and scope are
controlled by the current execution table above.

---

## 2. Priority 1: Terminal & Power-User Supremacy *(CleverKeys' Moat)*

### 1.1 Unify Terminal Input Switching via `TerminalUtils` & Add Custom Package Whitelist
* **Current State**: CleverKeys already has a mature [`TerminalUtils.kt`](file:///data/data/com.termux/files/home/git/swype/cleverkeys/src/main/kotlin/tribixbite/cleverkeys/TerminalUtils.kt) containing:
  - Whitelist of known terminal packages: `com.termux`, `com.termux.nix`, `org.connectbot`, `com.sonelli.juicessh`, `com.server.auditor.ssh.client` (Termius), `jackpal.androidterm`, `com.magicandroidapps.bettertermpro`, `com.rbrq.terminal`, `com.android.virtualization.terminal` (AVF), `com.rk.terminal`, `green_green_avk.anotherterm.redist`.
  - Heuristic detection: `contains("termux")`, `contains("anotherterm")`, `.endsWith(".terminal")`, `.contains(".terminal.")`, `.contains(".terminalemulator")`.
  - Terminal-safe clipboard paste via [`TerminalUtils.isTerminalApp(recv.getCurrentEditorInfo())`](file:///data/data/com.termux/files/home/git/swype/cleverkeys/src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt#L683) in `KeyEventHandler.kt` and `CustomShortSwipeExecutor.kt`.
* **The Remaining Gap**:
  1. **Pipeline Unification — implemented in `5f07936e`**: `SuggestionHandler` now uses `TerminalUtils.isTerminalApp(editorInfo)` for correction, undo, deletion and terminal prediction guards, matching terminal paste. Trailing suggestion spaces remain controlled by the existing user preference; they are not automatically suppressed for Termux. External terminal behavior still needs device validation.
  2. **Custom Package Config — implemented October 6**: searchable **"Custom terminal packages"** setting adds exact package IDs to built-in detection, with whole-list validation, canonical deduplication, immutable live Config routing, backup/default/reset support and 22 locales.
* **October 5 implementation**: shared detection now covers SuggestionHandler correction,
  undo, delete-word and terminal prediction guards. Real-handler focused tests pass 8/8:
  ConnectBot/JuiceSSH/Termius/AVF/Termux-Nix use Ctrl+W; suggestion replacement emits native
  backspaces; an ordinary editor retains document deletion. Behavior in each external
  terminal app still needs device validation.
* **October 6 validation**: 2,741 pure + 953 mock tests pass, including actual custom-app
  delete-word and regular/custom paste routing, removal and strict backup types. Eight
  Android UI tests pass (`2cea2c8b-e784-4b50-87da-a350b4c5e2e0`); the new Autofill
  visibility test fails against the prior APK, protecting the category omission.
  Signed/minified release builds with lint; Seeker installed APK SHA-256
  `97c289284237db8c2ea930ed42d1979e9b87a041532b547ba2f4266dfb464ce2`.
  Seeker search, duplicate Save/readback, invalid Save rejection, rotation-preserved
  draft, Cancel and clear all pass. Autofill identifier/title search now exposes the
  System row without changing enabled keys (19/108). Test package list, orientation,
  original IME and launcher/notification-shade focus restored; no clipboard changes.
* **October 6 Extra Keys follow-up — implemented**: a single keyed lazy list lets
  search/info/reset scroll away, making key rows reachable in landscape. Saveable
  search survives rotation/recreation. Seven Android UI tests pass
  (`419bc86b-2a9c-45e1-a5a9-3e2f29158f65`); the behavioral regression fails on the
  previous APK because recreation empties the query
  (`4e1d72bf-0522-4147-8be9-96cbd098a262`). Seeker minified-build landscape scrolling,
  portrait return, retained query and unchanged 19/108 preference count pass.
  Final installed APK SHA-256:
  `ffca616e836b496358638155df9017c3c2b6e7d69246609e715423d6ce743f75`.
  Original IME, 0/0 rotation and launcher/notification-shade focus restored; own dump
  removed, no clipboard changes. Canonical Extra Keys guide/spec now describe the
  actual per-key preferences and preferred-slot placement, replacing fictional
  bottom-row enums, position selectors and size caps.
* **Effort**: shared predicate is small; custom package UI requires preference/search/backup
  integration and explicit validation. No claim that every SSH editor was device-tested.


---

### 1.2 Expand Dynamic Macros Beyond Timestamps ({clipboard}, {cursor}, {uuid})
* **Current State**: CleverKeys **already has robust dynamic timestamp macros**:
  - `ActionType.TIMESTAMP` in `ShortSwipeMapping.kt` and `CustomShortSwipeExecutor.kt` executes arbitrary `SimpleDateFormat` patterns with live pattern previews in `SubkeyAssignActivity.kt` and `CommandPaletteDialog.kt`.
  - Key layout definition syntax `:timestamp symbol='📅':'yyyy-MM-dd HH:mm'` or `📅:timestamp:'yyyy-MM-dd'`.
  - 8 pre-registered timestamp commands in `CommandRegistry.kt` (`timestamp_date`, `timestamp_time`, `timestamp_datetime`, `timestamp_iso`, etc.).
* **The Remaining Gap**: Extending dynamic short-swipe expansion **beyond date/time formatting** to template variables:
  1. `{clipboard}` — Embed current clipboard content within a text template (e.g. `Markdown link: [{clipboard}](...)`).
  2. `{cursor}` — Reposition caret inside brackets or quotes after commit (e.g. `console.log({cursor});` commits text and positions caret inside the parens).
  3. `{uuid}` — Generate a random UUIDv4 string on the fly.
  4. `{selection}` — Wrap active selection.
**Authorized implementation scope:** introduce an explicit template action type while
keeping existing TEXT actions literal. Expand only the four recognized tokens, once,
without interpreting tokens embedded in clipboard or selection content. Resolve UUID
once per invocation, support literal brace escaping, reject duplicate cursor markers,
and bound both template and expanded UTF-16 lengths without truncation.

Read only plain clipboard text; do not coerce media/URI content or perform network
reads. Missing/restricted/oversized token inputs must fail before editing. Selection
and cursor templates require verified editor/session/selection readback. Android caret
offsets use UTF-16; do not count Unicode code points for `setSelection`. A batch edit
is not atomic, and partial/unknown writes must never be blindly retried. Prevent
execution into the hidden app while an inline clipboard/emoji/GIF editor is active.

Integrate the explicit type with both assignment editors, stored mappings, backups,
XML export/import and ordinary key execution. Token help in settings must not expose
the current clipboard/selection. Tests must cover literal TEXT compatibility, escaping,
nonrecursive token content, bounds, supplementary characters, routing, editor failures,
persistence, and actual native caret/selection behavior. This is a multi-file feature,
not merely string substitution in the timestamp executor.

---

### 1.3 At-Rest Clipboard Database Encryption
* **Context**: Plan outlined in [`docs/plans/156-at-rest-clipboard-encryption.md`](file:///data/data/com.termux/files/home/git/swype/cleverkeys/docs/plans/156-at-rest-clipboard-encryption.md).
* **Problem**: CleverKeys' clipboard database stores unlimited history, pinned snippets, todos, and media. On a rooted device, physical device capture, or local ADB backup, unencrypted SQLite files (`clipboard.db`) can expose sensitive tokens, passwords, and 2FA codes.
* **Implementation Plan**:
  1. Integrate SQLCipher or envelope encryption using Android Keystore (`AES/GCM/NoPadding`, 256-bit key master).
  2. Store encrypted blobs in standard SQLite or open a SQLCipher database connection.
  3. Gate clipboard access with optional biometric unlock (fingerprint / device PIN) when opening the clipboard panel.
* **Why it matters**: CleverKeys already has zero network permissions. At-rest encryption could reduce exposure of stored clipboard content; key lifecycle, recovery and backup behavior need a separate design and validation.
* **Effort**: Medium (3–5 days).

---

## 3. Priority 2: Swipe Typing Ergonomics & Ambiguity Resolution

Reported English short-word and apostrophe ambiguities need measured changes. Proposed gesture mechanisms must be reconciled with the current model, hit-testing and editor lifecycle before implementation.

### 2.1 Explicit Apostrophe and Possessive Gestures

**Code reconciliation (2026-10-05; phase 1 implemented, phases 2–5 pending).** The current CTC and
geometric engines decode letter-only surfaces. Apostrophes are supplied afterward by
`swipe/ContractionOverlay.kt`, using language-specific REPLACE and PAIRED mappings.
Consequently, restricting the current beam to “paths containing an apostrophe” cannot
work: those paths do not exist. Existing user joiner preferences and curated language
collision rules must survive any new gesture.

**What already works, and what remains weak:**

- Non-possessive same-letter pairs can be placed beside the decoded word; frequency
  promotion is deliberately conservative. For example, `its` remains first and `it's`
  is offered alongside it. A confident rank-0 `teams` can expose `team's` beside it;
  contested/lower-ranked possessives remain at the tail. These are presentation rules,
  not evidence that the gesture expressed an apostrophe.
- English-only suggestion augmentation also generates `cat's` and `parents'`.
  Its s-ending heuristic is not a grammatical singular/plural classifier: `James`,
  `news`, regular plurals and irregular plurals require different interpretation.
  Plural `cats`, singular possessive `cat's`, and plural possessive `cats'` are
  distinct user intents. Do not fix ambiguity by always preferring a possessive.
- A built-in literal apostrophe tap/flick already reclaims an owned, cursor-stamped
  automatic space when Smart Punctuation is enabled:
  `Bowie ` + `'` → `Bowie'`; typing `s` afterward attaches normally.
  Manual spaces and moved cursors are protected by `SmartAutoSpace.isSwallowEligible`.
- **Concrete route gap, single-apostrophe case now fixed:** previously every custom TEXT mapping ran through
  `Keyboard2View.onCustomShortSwipe` →
  `CustomShortSwipeExecutor.executeTextInput`, which commits directly to the app.
  This bypassed `KeyEventHandler.sendText`'s smart punctuation, inline search/edit
  routing and typed-text bookkeeping. Other custom TEXT macros still take this path.
  A custom `'s` string therefore does not acquire
  possessive semantics; it appends literally after the automatic space.
  Routing that string through `sendText` alone is insufficient: its swallow logic
  intentionally applies only to single characters.

**Minimal implementation sequence:**

1. **Restore literal apostrophe parity — implemented; ASCII device check passed, curly form remains a manual check.** Route a custom TEXT mapping consisting of
   exactly ASCII `'` or typographic `’` through the ordinary key text handler.
   Preserve the literal contents of arbitrary multi-character macros; do not split
   them into keystrokes or silently reclaim spaces before every punctuation-prefixed
   string. Keep the normal inline editor/search routing and Smart Punctuation toggle.
   A custom `'s` macro remains literal until the explicit command below exists.
   **Validation:** `Keyboard2ViewCustomSwipeDispatchTest` failed before the fix in
   exactly three cases (ASCII/curly attachment and inline-search destination), then
   passed all 15 tests with the real view, key handler, executor and editable IC double.
   Kotlin production/test compilation passed. Logs: `build/custom-apostrophe-red.log`
   and `build/custom-apostrophe-green.log`. Existing command dispatch/haptics, manual
   spaces, disabled Smart Punctuation, cursor mismatch and literal macros remain covered.
2. **Add explicit, assignable suffix commands.** Offer “Append 's” and “Append
   apostrophe” through the existing short-swipe/popover/command machinery. The first
   means exactly `'s`, including after an s-final singular; the second supports
   `parents'` without guessing morphology. Their labels state the insertion rather
   than claiming to identify nouns or grammatical possession. No dedicated apostrophe
   key or new apostrophe-to-S trajectory recognizer is required.
   Initially scope attachment to the immediately preceding verified swipe commit.
   Verify the same editor, a collapsed selection, cursor location and the exact
   committed word with either its owned automatic space or no trailing space.
   Never reclaim a manual space or edit a stale word after cursor/field changes.
   A successful edit retains the prior automatic-space policy. When verification
   fails, make no destructive edit and show concise feedback.
3. **Make suffix editing one reversible transaction.** Integrate at
   `SuggestionHandler`, where swipe replacements already roll back rejected-word
   learning and update context. Track the pre-edit word/space and resulting suffix
   separately: immediate Backspace restores the prior text, before the existing
   whole-swipe undo handler can delete the noun. Verify at undo time too; expire the
   transaction after any other edit, cursor move or field change. Refresh correction
   candidates so an old `lastAutoInsertedWord` cannot delete the wrong length.
   Do not automatically persist a one-off suffix choice as a dictionary preference.
4. **Treat contraction choice as a separate action.** An optional “Use apostrophe
   variant” action selects a same-letter projection from the current swipe candidates
   (`its` → `it's`, `were` → `we're`). This is different from appending `'s`.
   Multiple valid projections require an explicit choice; do not invent a global
   English rule or borrow variants from another language.
5. **Defer in-stroke waypoints until measured.** A later waypoint can carry explicit
   apostrophe intent into the display-overlay stage, but detouring through comma,
   period or space changes the trace seen by a model trained on letter paths.
   Define collision behavior with existing subkey flicks/space gestures and validate
   human traces before shipping. Hard beam filtering, huge ranking boosts, or a
   hand-spliced trace are not a demonstrated solution.

**Astra follow-up audit (October 6, read-only): prerequisite before phases 2–3.**
`onSuggestionSelected` currently ignores the editor’s `commitText` Boolean, can
continue to learn after an exception, and returns a word merely because an IC exists.
The swipe caller then falls back to the prediction when recording word/source,
correction state and ML labels. TODO: make accepted auto-insertion success-only,
remove failed/null commit ownership fallbacks, preserve candidate display on failure,
and test false/throwing writes with the real handler before adding suffix commands.
A true return is acknowledgement, not verified text ownership; do not claim arbitrary
editor mutations are atomic or that this alone fixes manual replacement/adaptation.

Suffix ownership additionally needs session/connection identity, both selection ends,
exact readback word/owned-space and language/learning identity. Invalidate on field,
cursor or selection-range changes (the present service callback forwards collapsed
cursor moves only). Prefer editing just the owned space, then verify readback; never
blindly retry or roll back unknown partial mutations. Failed suffix undo must consume
Backspace instead of falling into whole-word deletion. Roll learning back with a
receipt of the gates that actually ran, not whichever gates are enabled at undo time.
Do not record suffix entry as a swipe correction or relabel its original trace.
The follow-up receipt audit found that passing a gate does not prove a write:
ngram stores reject self references and vocabulary recording can reject disabled or
short words after normalization. Exact receipts must originate inside actual store
mutations, carry owner/version/consumption identity, and be validated together before
replacement. Gate snapshots must be immutable because Config is mutable. Count
rollback does not restore historical timestamps or evicted entries; do not describe
it as full store-state reversal. Reset/import/privacy/language changes must expire
handles, including an off→on or away→back cycle.
Unreadable editors retain ordinary literal typing but cannot qualify for initial
verified suffix attachment. No production suffix command or ownership fix was made
in the terminal-settings round.

**Required regressions (test the real commit/routing path):**

- Built-in and custom single apostrophe: identical `Bowie ` → `Bowie'` behavior
  with Smart Punctuation on; unchanged literal behavior when off. ASCII/curly forms,
  manual spaces, cursor movement, selected text and inline search/edit targets.
- Explicit suffixes: `Bowie ` → `Bowie's `, `parents ` → `parents' `,
  and `James ` → `James's ` when “Append 's” was requested; auto-space-off
  retains no trailing space. Immediate Backspace restores exactly the pre-edit text.
- Stale/failed editor reads or writes, duplicate invocation, field switch, password
  fields, and composing-less editors do not delete unrelated text or learn fragments.
- Context learns the resulting whole word once rather than `Bowie → s`; undo
  removes that edit's learning without creating a persistent word preference.
- `its/it's`, `were/we're`, `teams/team's/teams'`, `would/world`,
  user-preferred joiner words, and French/Italian collision cases preserve existing
  ranking unless the user explicitly requests a different form.

**Effort:** literal route parity is a small, testable fix. Suffix transaction/undo and
contraction selection are separate implementation rounds; the earlier combined
  “3–4 days” estimate did not account for these state and gesture conflicts.

---

### 2.2 Continuous Multi-Word Swiping across Spacebar

**Authorized next feature; currently unimplemented.** Add an opt-in setting, disabled
by default, allowing a deliberate spacebar boundary between word segments without
lifting the finger. Use the actual finite space-key geometry. A brief accidental
crossing must not split a word; boundary dwell/hysteresis needs explicit tests and
human device validation.

Decode each bounded, immutable letter-path segment through the existing routed engine.
The adapters currently cancel older requests, so segments need a serialized FIFO:
commit segment N before decoding N+1 with its updated context. Remove the spacebar
excursion from the decoder's letter path and begin the next segment at its first
letter. Final lift flushes the last segment without an empty word or duplicate space.

Capture setting, layout/language, field/session and edit generation at gesture start.
Before every result, verify the same editor and expected collapsed selection/readback.
Cancel pending segments on field/cursor/manual edits, a replacement gesture, multitouch
or cancellation. A rejected commit aborts the queue; do not insert a guessed fallback.
The phrase gesture must not create cross-segment learning or ML trace contamination.

Required coverage: ordinary single-word behavior with the feature off, intentional and
accidental boundaries, repeated/empty space visits, final lift, ordered delayed results,
failed commit, and stale field/selection/manual-edit cancellation. Synthetic segmentation
tests establish mechanics; fresh human device traces are still needed for accuracy and
latency. The prior midline-crossing proposal did not address these routing conflicts.

---

### 2.3 Mid-Stroke Ambiguity Dwell / Mini-Picker
* **The Problem**: When the user swiped a complex or ambiguous trajectory and wants to ensure the right word is picked without lifting and backspacing.
* **The Solution**:
  1. If the trajectory is stationary for $\ge 300\text{ ms}$ and the top-2 beam candidates have a close log-likelihood gap ($\Delta \le \epsilon$):
     - Display a small 3-chip floating overlay directly above the current touch coordinate.
     - The user can either lift to accept the default top candidate, or slide their finger into one of the adjacent candidate chips and release to commit that specific candidate.
* **Effort**: Medium (3–4 days).

---

## 4. Priority 3: Multilingual Reach without Bloat

CleverKeys supports Latin, Cyrillic (ru, uk, bg, mk), Greek (el), and Hebrew (he). The largest untapped user base that aligns with CleverKeys' open-source ethos is South Asian languages.

### 3.1 Avro-Style Phonetic Transliteration Engine

**October 6 feasibility check:** Roman-letter tap transliteration is a separate path
from native-layout swipe prediction; it can be staged before a Bangla CTC model.
The local WM checkout has a 576-line context-sensitive `AvroPhonetic` implementation
and real spelling/conjunct tests in `core/language`/`app/src/test`. Its repository
license is MIT, but the converter says its conjunct table comes from desktop Avro:
TODO: verify upstream table provenance and required notices before adopting code/data.
No converter or word list was imported into CleverKeys.

A longest-match trie alone is insufficient: independent vowels versus vowel signs,
inherent vowels, conjuncts/reph, explicit case and breaker syntax change the result.
The rule layer also does not supply dictionary corrections (`asi` → আছি, or a
lenient `valo` → ভালো); do not advertise the proposal example as rule-only output.
First freeze native-speaker-reviewed input/output fixtures, then build an opt-in
Roman-buffer composer with backspace, commit/cancel, cursor/field-change and unsupported
editor handling. Keep passwords/URLs, literal macros, clipboard inline editors and
English mode literal. Integrate prediction/learning through spelling-preserving Bangla
validation instead of the existing mark-stripping pipeline. Dictionary provenance and
layout-language association remain prerequisites for a supported candidate experience.

* **The Opportunity**: WM Keyboard's most celebrated feature is its **Avro Bengali phonetic transliteration** (typing `ami valo achi` produces `আমি ভালো আছি`).
* **Why it matters**: Hundreds of millions of mobile users in South Asia (Bengali, Hindi, Tamil, Telugu) do not use native InScript layouts; they type phonetically in Latin letters on standard English QWERTY layouts.
* **Implementation Plan**:
  1. Implement a lightweight trie-based phonetic converter (`PhoneticConverter.kt`):
     - Maps Latin character sequences (e.g., `kh`, `dh`, `sh`, `ch`, `ng`) to phonetic Indic syllables and conjuncts.
     - Uses a frequency-ranked unigram dictionary for candidate selection.
  2. Runs as a composer layer in the input pipeline before committing text, completely offline and requiring no machine learning runtime.
* **Effort**: Medium (4–6 days). Expands CleverKeys to millions of international users without bloating APK size.

---

## 5. Priority 4: Canvas UI & Visual Polish

CleverKeys' custom Canvas rendering is one of its greatest assets. It should be enhanced, not replaced:

### 4.1 Interactive Live Preview in DIY Theme Creator
* **Verified current state**: `ThemeSettingsActivity` already has a reactive DIY `ThemePreview(colors)` showing Q/W/E/R sample keys, border/label colors and a trail indicator. The proposed `ThemeCreatorActivity` is not the current implementation. Editing is not blind.
* **Remaining improvement**: Replace the limited sample with a scaled actual keyboard renderer so activated/locked/modifier/special key states, background and all nine editable colors can be assessed together. Preserve the existing live update and theme tokens; avoid introducing a second renderer with different semantics.
* **Effort**: Low (1–2 days).

### 4.2 Velocity-Responsive Bezier Swipe Trail
* **Problem**: The existing swipe trail is functional, but lacks modern fluid aesthetics.
* **Solution**: Replace linear line segments with a Catmull-Rom spline interpolator. Dynamically modulate trail thickness based on fingertip velocity (thick on slow pivots, thin on fast transits) with a decaying alpha gradient. Reuse Canvas rendering and measure frame latency/allocations on devices before claiming a refresh rate or overhead improvement.
* **Effort**: Low (1–2 days).

---

## 6. What NOT to Do: Traps & Anti-Patterns to Avoid

| Anti-Pattern | Why Competitors Did It | Why CleverKeys Must Avoid It |
| :--- | :--- | :--- |
| **Migrating to Jetpack Compose for IME Surface** | WM Keyboard used Compose for rapid declarative development via Claude Code. | Led to severe architectural decay: `WMKeyboardService.kt` grew to **34,113 lines**, cold-start latency degraded, APK size surged to 85–132MB, and Termux builds broke. **Keep Android Canvas.** Direct Canvas rendering is faster, leaner, and native. |
| **Adding Internet Permissions for Utilities** | WM Keyboard added 77 tools including web search, cloud translation, and weather. | Destroys your **zero-permission air-gap guarantee**. The moment `android.permission.INTERNET` enters your manifest, kernel-enforced trust is lost. If users need voice dictation or OCR, route through Android intent protocols or companion apps. |
| **Inflating Language Counts with Empty JSONs** | WM Keyboard claims 1,700+ layouts, but 98% have no bundled dictionary. | CleverKeys' reputation is built on **scientific rigor and honest provenance** (`build_wordlist.py`, AOSP oracles, negative typo filters). Prioritize curated, fully-tested language packs. |
| **Relying on Synthetic-Only Accuracy Benchmarks** | WM Keyboard claims ~95% accuracy measured only against its own synthetic spline simulator (`SwipeCorpus.kt`). | Continue testing on **real human swipe datasets** (`test_hwsfuto.jsonl`). Real fingers have physical corner-cutting and tremor that mathematical splines cannot replicate. |

---

## 7. Recommended Action Plan

```
┌─────────────────────────────────────────────────────────────┐
│               IMMEDIATE: PRE-RELEASE WORK                   │
│  • Finish device verification in checklist                  │
│  • Complete feature/bug work and maintainer manual testing │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                   PHASE 1: QUICK VALUE WINS                 │
│  • TerminalUtils & Custom Package Whitelist                 │
│  • Dynamic Text Macros ({date}, {time}, {clipboard})        │
│  • Theme Creator Live Canvas Preview                        │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                  PHASE 2: SWIPE ERGONOMICS                  │
│  • Glide Apostrophe Waypoint & Possessive Flick ('s)        │
│  • Continuous Multi-Word Swipe across Spacebar              │
│  • Velocity-Responsive Bezier Ribbon Trail                  │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│              PHASE 3: STRATEGIC DIFFERENTIATION             │
│  • At-Rest Clipboard Database Encryption (Keystore/AES)     │
│  • Avro-Style Indic/Bengali Phonetic Transliteration        │
└─────────────────────────────────────────────────────────────┘
```

### October 5 validation and next steps

`5f07936e` implements shared terminal detection and custom single-apostrophe routing.
Kotlin compilation and all 2,737 pure / 933 mock tests pass; minified release build
passes in 5m23s. ARM64 APK signature, alignment and ZIP CRC pass; SHA-256
`00e80393a4d130bc68cf47832ca0e106f9f7c11ac44106e62d9d8d7ce12d22f9`.
Installed on Seeker with matching APK hash. The same custom t/South apostrophe
flick now yields `As'` (no intervening automatic space), and routes into emoji search.
The apostrophe test mapping was removed, then that empty slot was temporarily assigned
`minimize_fab`: portrait and landscape minimize/expand pass, and landscape scrolling
starting in the transparent IME strip moves launcher content while the FAB stays visible.
Rotation restored to 0 (original accelerometer rotation 0); temporary Hebrew app locale
restored to the original empty locale list. That locale did not make the IME RTL, so RTL
placement is unverified. Final reconnection cleanup complete: removed ONLY temporary
t/South `minimize_fab`, confirmed original two mappings and empty launcher test field,
removed scratch UI XML, verified original IME/rotation/locales and restored HOME with
expanded Quick Settings (NotificationShade focus).
External terminal smoke test remains pending; no terminal commands were executed.

The default CTC engine still misrecognizes wet and ad. Endpoint-only rescoring cannot
separate wet from wt, which shares the endpoint. TODO: obtain held-out human traces
and validate a general model/scoring correction with we/as controls and wider words;
per-word engine switching is not a solution. No ranking change was shipped.

### Proposed default-engine short-word correction (Astra, October 5)

Both ad and wet survive in the CTC beam; greedy emissions are already wrong.
The bounded `build/short-word-rescore-probe.log` experiment reuses production
PathScorer on surviving CTC candidates with frequency contribution disabled.
Across 19 distinct canonical synthetic words, current CTC gets 13/19; geometry-only
(weight 4) gets 16/19; geometry plus exploratory timing (weights 4/8) gets 19/19.
Additional wt control: baseline we, both rescoring arms wt. One usable human wet
trace: baseline we (wet rank 6), geometry wt (wet rank 2), timing wet (rank 1).
Protected we/as remain correct in this small probe.

These are diagnostics, not a shipping result. Wet/wt have identical collinear path
templates; geometry alone cannot distinguish them. The equal-duration letter timing
template matches the synthetic generator, so 19/19 overstates useful evidence.
No word exception, model change, second decoder or displayed-score merge was shipped.

Recommended path:
1. Collect labeled human traces separated by writer/session; cover short words, adjacent
   final keys, collinear interior letters, repeated letters, we/as/wt controls and wider
   vocabulary/layouts. Existing reported traces are development cases, not held-out data.
2. Fine-tune the encoder on general failure strata with longer-word/alternate-layout
   replay; retain the current input/32-frame output contract initially. Retune common
   scoring parameters only on development data, preserving contraction overlays.
3. Compare a residual geometry/timing ranking arm calibrated on real traces. If adopted,
   apply before softmax in CtcEngineAdapter.decodeLexicon over bounded surviving CTC
   candidates; do not merge displayed engine scores or weaken suggestion confidence.
4. Require predeclared held-out gains across distinct words/writers, protected controls,
   acceptable wider-vocabulary/layout behavior and on-device latency before shipping.

Prior evidence: CleverKeys-ML/ctc/PHASE_K.md §5.1 reports inconsistent short-word
reranker gains across seeds (+0.30/0/−0.18); its 14 features lack raw path geometry.
PHASE_I.md §6.1 doubling emissions 32→64 did not improve the ≤3 stratum (−0.09).
Neither repeating that ranker nor increasing frame count is an established fix.
Frozen human regression screen completed (`build/short-word-human-screen.log`):
100 distinct traces / 92 distinct words sampled deterministically by SHA (seed 20261005)
from the repeatedly inspected corpus; not held-out. Input pool: 8,607 rows, 4,050
excluded by timestamp-quality gate, 4,557 usable. Baseline matched the shipped replay
on all 100 traces. Current CTC: 93/100 correct; geometry g4/t0: 83/100 (1 gain,
11 losses); geometry+timing g4/t8: 61/100 (1 gain, 33 losses). Short ≤3: 20 traces,
baseline 17 correct → geometry 14 → timing 11; longer words: 80 traces, 76 → 69 → 50.
The sampled set contains no ad/wet/as/we/wt, so target/control screening still needs
separate real traces. Counts measure distinct traces, not unique-word accuracy.

**Decision:** reject both frozen heuristics for production; do not tune further on
this screen. Synthetic success did not generalize, including to other short words.
Prioritize general-strata encoder correction with proper development/held-out splits.
TODO: obtain fresh writer/session-separated data, train and evaluate encoder changes.
Training/runtime implementation remains open; no production ranking/model change.

PAL architecture cross-check used gemini-3.8-flash only. Verified against source:
CtcFeaturizer uses timestamps to produce time-uniform samples, preserving relative
dwell implicitly; total duration is normalized away. CtcBeamDecoder uses Viterbi
MAX-merge and already has length normalization, insertion and frequency terms.
Beam survival does not establish correct encoder calibration, and raw-score dominance
alone does not prove all possible decoder adjustments fail across different lengths.
TODO: compare development-only score-component/shared-parameter diagnostics with the
encoder arm; if training, evaluate frozen-teacher logit anchoring on the existing
32-frame emissions to limit drift. Do not copy PAL's unvalidated numerical constants
or its 64-output-frame assumption; keep the actual model contract.

## Clipboard follow-up (#168, 2026-10-06)

`clear_clipboard` is implemented locally as an opt-in command for Android’s current
clipboard. It preserves saved history, pins, todos and media; it is independent of
size-filtered, confirmed Delete results. Extra-key category visibility, recycled labels and displayed-title search are also
fixed. Validation and device status are recorded in `memory/todo.md`. Apostrophe suffix commands, short-word model work, layout-linked
language and Bangla prediction remain separate work.
