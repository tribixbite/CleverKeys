---
title: Next-Word Prediction — Technical Specification
description: Learned n-gram next-word generation, privacy gating, provenance, and the four suggestion-bar call-sites
user_guide: ../../typing/next-word-prediction.md
status: implemented
version: v1.5.x
---

# Next-Word Prediction Technical Specification

## Overview

Opt-in (default OFF) context-only word suggestions generated from the on-device learned
n-gram store (`BigramStore` + `TrigramStore` via `ContextModel`), surfaced in the
suggestion bar at moments it would otherwise be empty. Landed 2026-08-06 alongside the
persistent context LM, the master on-device-learning privacy gate, and suggestion
provenance. Internal engineering spec:
`docs/specs/context-learning-and-next-word.md` (repo source tree).

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| NextWordPredictor | `src/main/kotlin/tribixbite/cleverkeys/NextWordPredictor.kt` | Pure JVM gating (`shouldShow`) + candidate generation (`generate`) + provenance note |
| ContextModel | `src/main/kotlin/tribixbite/cleverkeys/contextaware/ContextModel.kt` | `getNextWordCandidates` — trigram-preferred with bigram backoff |
| BigramStore / TrigramStore | `src/main/kotlin/tribixbite/cleverkeys/contextaware/` | Persistent, language-keyed, process-singleton learned n-gram stores |
| LearningGate | `src/main/kotlin/tribixbite/cleverkeys/LearningGate.kt` | Master privacy gate; incognito-field flag handling |
| SuggestionHandler | `src/main/kotlin/tribixbite/cleverkeys/SuggestionHandler.kt` | Impure wiring: the four call-sites, executor, bar posting |
| SuggestionProvenance | `src/main/kotlin/tribixbite/cleverkeys/SuggestionProvenance.kt` | `SuggestionOrigin.NEXT_WORD` metas + long-press sheet formatting |

## Architecture

```
committed word
   │  (LearningGate.learnCommittedWord — master gate + per-feature gates + incognito flag)
   ▼
ContextModel.recordCommit ──▶ BigramStore / TrigramStore   (RAM + debounced persist)
                                        │
        NextWordPredictor.shouldShow    │ ContextModel.getNextWordCandidates(maxResults=10)
        (7-condition gate)              ▼   trigram (w1,w2) first, bigram backoff, dedup
                └──────────▶ NextWordPredictor.generate
                              floors: freq ≥ 2 AND prob ≥ 0.05
                              filters: self-repetition, dictionary/user-vocab membership,
                                       not disabled, dedup
                              score = prob × (1 + personalizationBoost/4) × 1000
                                        │  (≤3 whole-bar; ≤2 appended after swipe alternates)
                                        ▼
                          SuggestionBar (NEXT_WORD metas, generation-guarded post)
```

## Gating (`NextWordPredictor.shouldShow`)

ALL must hold: `next_word_prediction_enabled` ∧ `on_device_learning_enabled` (master) ∧
field allows personalized learning (`EditorInfo.imeOptions` lacks
`IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000`) ∧ `word_prediction_enabled` ∧
not password mode ∧ no special prompt active ∧ not Termux ∧ non-empty committed context.

## The four call-sites (SuggestionHandler)

| # | Trigger | Behavior |
|---|---------|----------|
| 1 | Word completed with space (`text == " "` only) | Show up to 3 candidates in the otherwise-empty bar |
| 2 | Manual suggestion tap (`isManualSelection` only) | Chain: regenerate from the grown context |
| 3 | Swipe auto-insert results | Keep alternates, APPEND ≤2 `NEXT_WORD`-tagged candidates; tap on those APPENDS instead of replacing the swipe word; generation runs on the shared prediction executor |
| 4 | Cursor parked with empty prefix (`handleCursorParkPrediction`) | Reads the text actually before the parked cursor (`readEditorParkContext` → `NextWordPredictor.contextFromEditorText`, sentence-boundary aware), so parking into an older paragraph predicts from it; the editor read is gated on the feature pref, master learning gate, context-LM pref and the per-field incognito flag, and falls back to session context if the editor cannot be read |

Staleness: async posts abort when `SuggestionBar.contentGeneration()` changed since submit.
Dismissal: any selection consumes the state; backspace with no partial word clears the
candidates; typing a letter switches to prefix predictions; sentence-final punctuation
resets the learned-context window (`WordPredictor.onSentenceBoundary()`).

## Configuration

| Setting | Key | Default | Values | Source |
|---------|-----|---------|--------|--------|
| **Next-Word Prediction** | `next_word_prediction_enabled` | `false` | bool | `Config.kt:555` |
| **Learn From My Typing** (master) | `on_device_learning_enabled` | `true` | bool | `Config.kt:554` |
| **Context Source** | `context_source` | `"both"` | `both` \| `learned_only` \| `static_only` | `Config.kt:556` |
| **Personalization Strength** | `personalization_weight` | `1.0` | 0.0–2.0 | `Config.kt:557` |
| **Suggestion Origin Markers** | `suggestion_provenance_markers` | `false` | bool | `Config.kt:544` |

Constants: `MAX_SUGGESTIONS = 3`, `MAX_SWIPE_APPEND = 2`, `MIN_LEARNED_FREQUENCY = 2`,
`MIN_LEARNED_PROBABILITY = 0.05f` (`NextWordPredictor.kt`).

## Provenance

Each candidate carries `SuggestionMeta(SuggestionOrigin.NEXT_WORD, note = provenanceNote)`
where the note renders the learned statistics, e.g. `After "want to": seen 14×, 63%`
(trigram context shows two words, bigram one). Long-press opens the provenance sheet
(`ProvenanceFormatter.format`); the opt-in origin-marker dot uses a per-origin color
(`SuggestionBar.originMarkerColor`).

## Test Coverage

| Suite | File | Focus |
|-------|------|-------|
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/NextWordPredictorTest.kt` | Gate matrix, floors, filters, ranking |
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/OnDeviceLearningPrivacyTest.kt` | Master-gate-off ⇒ nothing recorded/persisted |
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/contextaware/ContextModelTrigramTest.kt` | Trigram→bigram backoff |
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/LearningWiringDriftTest.kt` | Forbids ungated learn-path regrowth |

## Related Specifications

- [Input Behavior Spec](../settings/input-behavior-spec.md) - Word-prediction section settings
- [Swipe Typing Spec](./swipe-typing-spec.md) - The swipe pipeline the appended candidates compose with
- [Autocorrect Spec](./autocorrect-spec.md) - Commit/undo interactions
