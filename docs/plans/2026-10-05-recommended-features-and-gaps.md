# Strategic Roadmap: Recommended Features & Architectural Gaps for CleverKeys

**Date**: 2026-10-05  
**Target Project**: [CleverKeys](file:///data/data/com.termux/files/home/git/swype/cleverkeys) (`tribixbite/CleverKeys`)  
**Context**: Comparative architectural synthesis against [WM Keyboard](file:///data/data/com.termux/files/home/git/swype/wmkeyboard), HeliBoard, and FUTO.

---

## 1. Executive Summary

CleverKeys occupies a unique and commanding position in the open-source Android ecosystem:
- **Trained Neural CTC Model (2.91 MB ONNX)** with an independently measured **89.31% Top-1 accuracy** on real human swipe data (beating FUTO).
- **100% Kernel-Enforced Air-Gap**: Literal zero network permissions (`android.permission.INTERNET` is absent).
- **Canvas-Driven Performance**: Sub-millisecond draw calls, instant cold start, zero Compose runtime overhead, and full Termux buildability.
- **Terminal-First Ergonomics**: Reliable gesture typing, selection-delete, and cursor navigation within Termux.

To extend this lead without falling into the bloat, maintenance traps, or permission sprawl seen in other keyboards (such as WM Keyboard's 34k-line service files or network dependencies), development should focus on **three high-leverage areas**:
1. **Doubling down on terminal and hacker productivity** (expanding terminal whitelists, dynamic macros, at-rest database encryption).
2. **Adopting zero-bloat gesture innovations** (glide apostrophe waypoints, possessive flick, continuous multi-word swipe).
3. **Broadening multilingual reach via lightweight phonetic transliteration** (Avro/Indic transliteration engine).

---

## 2. Priority 1: Terminal & Power-User Supremacy *(CleverKeys' Moat)*

### 1.1 Unify Terminal Input Switching via `TerminalUtils` & Add Custom Package Whitelist
* **Current State**: CleverKeys already has a mature [`TerminalUtils.kt`](file:///data/data/com.termux/files/home/git/swype/cleverkeys/src/main/kotlin/tribixbite/cleverkeys/TerminalUtils.kt) containing:
  - Whitelist of known terminal packages: `com.termux`, `com.termux.nix`, `org.connectbot`, `com.sonelli.juicessh`, `com.server.auditor.ssh.client` (Termius), `jackpal.androidterm`, `com.magicandroidapps.bettertermpro`, `com.rbrq.terminal`, `com.android.virtualization.terminal` (AVF), `com.rk.terminal`, `green_green_avk.anotherterm.redist`.
  - Heuristic detection: `contains("termux")`, `contains("anotherterm")`, `.endsWith(".terminal")`, `.contains(".terminal.")`, `.contains(".terminalemulator")`.
  - Terminal-safe clipboard paste via [`TerminalUtils.isTerminalApp(recv.getCurrentEditorInfo())`](file:///data/data/com.termux/files/home/git/swype/cleverkeys/src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt#L683) in `KeyEventHandler.kt` and `CustomShortSwipeExecutor.kt`.
* **The Remaining Gap**:
  1. **Pipeline Unification — implemented in `5f07936e`**: `SuggestionHandler` now uses `TerminalUtils.isTerminalApp(editorInfo)` for correction, undo, deletion and terminal prediction guards, matching terminal paste. Trailing suggestion spaces remain controlled by the existing user preference; they are not automatically suppressed for Termux. External terminal behavior still needs device validation.
  2. **Custom Package Config**: Add a user-facing setting: **"Custom Terminal Packages"** allowing users to enter custom package names (e.g. specialized NeoVim wrappers or remote desktop apps) to trigger terminal mode.
* **October 5 implementation**: shared detection now covers SuggestionHandler correction,
  undo, delete-word and terminal prediction guards. Real-handler focused tests pass 8/8:
  ConnectBot/JuiceSSH/Termius/AVF/Termux-Nix use Ctrl+W; suggestion replacement emits native
  backspaces; an ordinary editor retains document deletion. Custom package configuration
  remains TODO; behavior in each external terminal app still needs device validation.
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
* **Effort**: Low (1–2 days). Complements existing timestamp macros to make CleverKeys a complete text expansion tool.

---

### 1.3 At-Rest Clipboard Database Encryption
* **Context**: Plan outlined in [`docs/plans/156-at-rest-clipboard-encryption.md`](file:///data/data/com.termux/files/home/git/swype/cleverkeys/docs/plans/156-at-rest-clipboard-encryption.md).
* **Problem**: CleverKeys' clipboard database stores unlimited history, pinned snippets, todos, and media. On a rooted device, physical device capture, or local ADB backup, unencrypted SQLite files (`clipboard.db`) can expose sensitive tokens, passwords, and 2FA codes.
* **Implementation Plan**:
  1. Integrate SQLCipher or envelope encryption using Android Keystore (`AES/GCM/NoPadding`, 256-bit key master).
  2. Store encrypted blobs in standard SQLite or open a SQLCipher database connection.
  3. Gate clipboard access with optional biometric unlock (fingerprint / device PIN) when opening the clipboard panel.
* **Why it matters**: CleverKeys already has zero network permissions. Adding at-rest encryption creates an **uncompromised hardware-backed privacy vault** unmatched by any keyboard on Android.
* **Effort**: Medium (3–5 days).

---

## 3. Priority 2: Swipe Typing Ergonomics & Ambiguity Resolution

While CleverKeys' ONNX CTC model achieves outstanding accuracy (89.31% Top-1), certain edge cases in English and Latin-script swipe typing cause friction. WM Keyboard implemented clever, zero-weight heuristic mechanics that CleverKeys can adopt directly:

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

1. **Restore literal apostrophe parity — implemented, device check pending.** Route a custom TEXT mapping consisting of
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
* **The Problem**: Lifting the finger between every single word introduces latency during rapid message composition.
* **The Solution**:
  1. Detect when a swipe trajectory crosses the horizontal midline of the spacebar key.
  2. When crossing occurs:
     - Finalize and commit the best candidate for the trajectory up to the spacebar.
     - Emit an automatic space.
     - Feed the newly committed word into the `BigramStore` / `ContextModel`.
     - Reset the swipe trajectory buffer with the point exiting the spacebar as the origin for the subsequent word.
* **Effort**: Medium (3–4 days). Enables uninterrupted flow typing.

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
* **Solution**: Replace linear line segments with a Catmull-Rom spline interpolator. Dynamically modulate trail thickness based on fingertip velocity (thick on slow pivots, thin on fast transits) with a decaying alpha gradient. Because it draws directly to Canvas, this maintains a 120 FPS refresh rate with near-zero overhead.
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
│                    IMMEDIATE: v2.0.0 RELEASE                │
│  • Finish device verification in checklist                  │
│  • Tag and release v2.0.0 (F-Droid & GitHub)                │
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
Device installation/retest is pending: `.170` returns No route to host. The previous
APK reproduced the custom-apostrophe spacing bug. TODO: retest the same t/South
apostrophe mapping on the new APK, remove only that temporary mapping, clear only
launcher test text and restore the original expanded Quick Settings over the launcher.

The default CTC engine still misrecognizes wet and ad. Endpoint-only rescoring cannot
separate wet from wt, which shares the endpoint. TODO: obtain held-out human traces
and validate a general model/scoring correction with we/as controls and wider words;
per-word engine switching is not a solution. No ranking change was shipped.
