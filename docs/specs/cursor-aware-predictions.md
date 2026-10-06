# Cursor-Aware Predictions

Updated: 2026-10-06. This describes the current shared prediction and commit path.
Continuous swipe and verified suffix transactions remain under development.

## Components

| Component | Responsibility |
|-----------|----------------|
| `CleverKeysService.onUpdateSelection` | Forward collapsed cursor movement and selection state |
| `InputCoordinator.onCursorMoved` | Invalidate stale automatic-space ownership immediately; debounce cursor synchronization |
| `PredictionContextTracker.synchronizeWithCursor` | Read the raw prefix/suffix around the cursor and retain deletion lengths |
| `SuggestionHandler.handleCursorSyncPrediction` | Run the same guarded typing prediction pipeline used by ordinary typing |
| `SuggestionHandler.handleCursorParkPrediction` | Request next-word candidates when no partial prefix is present |
| `SuggestionHandler.onSuggestionSelected` | Replace the applicable partial/swipe word, commit the candidate and perform success bookkeeping |

Source files are under `src/main/kotlin/tribixbite/cleverkeys/`. InputCoordinator
has no separate suggestion commit engine or cursor-sync prediction implementation.

## Cursor synchronization

The service currently starts synchronization only when a collapsed cursor changes
position. A selection-range change without that movement does not start this debounce.
The view still receives its selection state. Verified suffix/phrase ownership must
add range-aware lifecycle invalidation rather than relying on this prediction hook.

`onCursorMoved` calls `onCursorPositionChanged` before scheduling the 100 ms debounce.
Moving away from an automatic-space stamp invalidates its punctuation ownership.
The coordinator removes any previous runnable, then reads at most 50 UTF-16 units
before and after the captured cursor connection. Finish/shutdown cancel the runnable.

Synchronization returns immediately for a missing connection, an expected programmatic
selection update, password/URI/email/number/phone fields, or Chinese/Japanese/Korean/Thai
language codes. Detected CJK surrounding text clears the partial/deletion buffers.
InputConnection text order is logical, including in RTL editors.

The tracker stores the **raw**, case-preserving prefix and suffix. Prediction lookup
uses the prefix only; the suffix is retained for replacement. Internal extraction also
computes normalized forms, but they do not replace the raw deletion strings.
Apostrophes, including curly forms, join letters when both sides are letters. Digits,
whitespace and explicit punctuation boundaries break a word. Other nonboundary
characters are retained by the current word-character policy; this is not a universal
Unicode word segmenter. Normalization uses NFD and removes Mn marks, which remains a
known obstacle for Bangla spelling preservation.

## Candidate generation and cursor parking

A nonempty synchronized prefix routes to `handleCursorSyncPrediction`, which shares
SuggestionHandler's typing pipeline: dictionary/context lookup, contraction overlays,
case handling, deduplication, special-prompt protection and exact-word addition.
The pipeline is not a prefix filter on the swipe decoder.

An empty prefix preserves active autocorrect-undo or swipe-correction candidates.
Otherwise it routes to `handleCursorParkPrediction(editorInfo, ic)`. The handler reads
bounded preceding editor text for static next-word context, including text from an
older session; an unreadable editor can fall back to session context. Static and
learned candidate tiers have separate gates. Next-word prediction defaults ON;
turning learning off closes the learned tier while the static tier can remain available.
Password/prompt/terminal and feature/word-prediction controls still apply. See
[Context Learning and Next-Word](context-learning-and-next-word.md).

## Candidate replacement

`onSuggestionSelected` handles special suggestion protocols first, then applies
contraction protection, final autocorrection where eligible, and I-word/case rules.
A manual candidate over an owned swipe word uses the existing replacement branch.
An ordinary manual candidate synchronizes immediately and obtains both prefix and
suffix deletion lengths. A guarded editor scan supplies a partial-word fallback when
cursor synchronization is suppressed or has no usable deletion information. URL and
email fields do not receive a leading automatic space. Terminal routing uses shared
built-ins plus exact configured package additions.

Automatic spacing is decided by `SmartAutoSpace`: user policy, existing following
space and opening punctuation affect leading/trailing spaces. Cursor stamps are
absolute UTF-16 positions from `ExtractedText`, including `startOffset`.

## Accepted commit bookkeeping

The shared engine checks `commitText`'s Boolean result. A false return, exception or
missing connection returns null and clears destructive ownership, automatic-space
and trailing-space watches, stale partial/autocorrect state and pending learning.
Exceptions are logged by type without candidate text. Acknowledged insertion returns
the actual inserted spelling, including capitalization inherited from a typed partial.

Only an acknowledged commit can record manual selection adaptation, update the new
word's context/learning, or return a successful candidate to the swipe caller.
The caller does not substitute an offered prediction for a null result. Failed swipes
retain their candidate slate, discard transient ML data and do not stamp a new swipe
word/source/correction record or emit a success haptic. If manual typing precedes a
swipe, its separator must be acknowledged before completing that typed word and
inserting the decoded word; joiner-stem validation accounts for the accepted separator.

Acknowledgement does not establish exact editor text ownership. Existing partial-word
and old-swipe deletion can occur before the replacement commit, and Android batch edits
are not atomic. A provider can mutate text before returning false or throwing. Failure
handling does not blindly retry or compensate an unknown partial write.

<!-- TODO: Replace legacy manual replacement/deletion and learning rollback with
verified text ownership and exact consumed learning receipts. -->

## Punctuation selections

`currentSelection` reports both absolute endpoints from a single ExtractedText read.
Smart punctuation checks a collapsed selection before removing its owned automatic
space. A range whose start equals the old stamp cannot reclaim the space before it:
`Bowie abc` with `abc` selected becomes `Bowie '` when the apostrophe replaces it.
Unreadable editors retain ordinary punctuation's selected-text/ownership fallback;
they do not thereby qualify for the stronger suffix ownership under development.

## Verification

Tracker extraction tests cover mid-word raw prefix/suffix lengths, contraction
boundaries, casing, input-type exclusions and cursor synchronization. Real-handler
`LearningFunnelBookkeepingTest` covers accepted/rejected writes, missing connections,
separator rejection, selection adaptation, spelling and actual context-store counts.
Native `SmartAutoSpaceTest` uses BaseInputConnection plus rejection/exception wrappers.
Source drift checks protect failure cleanup and prohibit prediction ownership fallbacks.
Current executed totals and artifact/run evidence are in
[Testing Strategy](testing-strategy.md). These tests do not establish compatibility
with every editor or validate human swipe recognition accuracy.
