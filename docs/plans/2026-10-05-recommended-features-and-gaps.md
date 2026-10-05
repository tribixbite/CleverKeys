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
  1. **Pipeline Unification**: While terminal paste uses `TerminalUtils`, [`SuggestionHandler.kt:348`](file:///data/data/com.termux/files/home/git/swype/cleverkeys/src/main/kotlin/tribixbite/cleverkeys/SuggestionHandler.kt#L348) (`isTermuxEditor`) still checks `editorInfo?.packageName == TERMUX_PACKAGE` (`"com.termux"`) directly for `Ctrl+W` kill-word deletion and trailing space suppression. Connecting `SuggestionHandler`'s deletion logic to `TerminalUtils.isTerminalApp(editorInfo)` extends full terminal input behaviors to JuiceSSH, Termius, AVF, and ConnectBot.
  2. **Custom Package Config**: Add a user-facing setting: **"Custom Terminal Packages"** allowing users to enter custom package names (e.g. specialized NeoVim wrappers or remote desktop apps) to trigger terminal mode.
* **Effort**: Low (1 day). Extends first-class terminal handling across the entire SSH/terminal ecosystem.

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

### 2.1 Glide Apostrophe Waypoint & Possessive Flick (`'s`)
* **The Problem**: Ambiguous contraction pairs (`its` vs `it's`, `were` vs `we're`, `lets` vs `let's`, `cant` vs `can't`, `hell` vs `he'll`) share identical or near-identical swipe geometries. Relying purely on unigram frequencies often misidentifies the intended word.
* **The Solution**:
  1. **Apostrophe Key Waypoint**: Allow users to designate a key (e.g. comma `,`, period `.`, or spacebar) as the apostrophe waypoint.
     - When the swipe trajectory passes within threshold radius of the designated key, the beam search decoder explicitly restricts or massively boosts candidate paths containing an apostrophe (`its` $\to$ `it's`).
  2. **Possessive Flick (`'s`)**:
     - Immediately after swiping a noun (e.g. `Bowie`), a quick outward flick from the apostrophe key to `s` appends `'s` directly to the committed word without requiring backspace/spacebar fidgeting.
     - Reclaims the trailing auto-space and binds `'s` as a single unit for backspace reversal.
* **Effort**: Medium (3–4 days). Solves the primary cause of contraction mis-predictions.

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
* **Problem**: Editing a theme currently requires adjusting hex values blind, saving, and testing in an external app.
* **Solution**: Embed a live, scaled-down Canvas preview of the keyboard directly inside `ThemeCreatorActivity`. As color pickers, borders, or alpha sliders change, invalidate the preview Canvas in real time.
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
