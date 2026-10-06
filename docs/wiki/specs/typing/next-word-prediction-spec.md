---
title: Next-Word Prediction — Technical Specification
description: Two-tier next-word generation (shipped static model + learned n-grams), tier gating, provenance, and the four suggestion-bar call-sites
user_guide: /wiki/typing/next-word-prediction/
status: implemented
version: v2.0.0 development
---

# Next-Word Prediction Technical Specification

## Overview

Context-only word suggestions surfaced in the suggestion bar at moments it would otherwise
be empty, from two tiers:

- **Static tier** — shipped, identical on every install, nothing personal. English: the
  corpus-built context model `assets/lm/en.cklm` (`StaticContextLm`, CKLM v1; 15,265 words,
  14,469 previous words, 74,797 pairs; built by `scripts/build_static_lm.py` from the Leipzig
  Corpora Collection `eng-com_web-public_2018_300K` (CC BY 4.0) + Tatoeba English sentences
  (CC BY 2.0 FR) at weight 0.1; commit 2144770e). de/es/fr/it/pt: the curated
  `assets/bigrams/<lang>_bigrams.json` seeds (ARC-020, 2026-08-28), which for English only
  fill slots the LM leaves empty.
- **Learned tier** — the on-device n-gram store (`BigramStore` + `TrigramStore` via
  `ContextModel`), plus the personalization boost and learned-vocabulary allow-list.

**Default ON since 2026-09-26** (maintainer decision; an explicit stored `false` is kept).
The static tier works with on-device learning off, context-aware predictions off, and in
incognito fields; only the learned tier needs those gates. Landed 2026-08-06 alongside the
persistent context LM, the master on-device-learning privacy gate, and suggestion
provenance. Internal engineering spec: `docs/specs/context-learning-and-next-word.md`
(repo source tree).

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| NextWordPredictor | `src/main/kotlin/tribixbite/cleverkeys/NextWordPredictor.kt` | Pure JVM two-tier gating (`decideTiers` → `TierGate`), the single gated read path (`candidatesFor`), candidate generation (`generate`) + provenance note |
| ContextModel | `src/main/kotlin/tribixbite/cleverkeys/contextaware/ContextModel.kt` | `getNextWordCandidates` — trigram-preferred with bigram backoff |
| BigramStore / TrigramStore | `src/main/kotlin/tribixbite/cleverkeys/contextaware/` | Persistent, language-keyed, process-singleton learned n-gram stores |
| LearningGate | `src/main/kotlin/tribixbite/cleverkeys/LearningGate.kt` | Master privacy gate; incognito-field flag handling |
| SuggestionHandler | `src/main/kotlin/tribixbite/cleverkeys/SuggestionHandler.kt` | Impure wiring: the four call-sites, executor, bar posting |
| SuggestionProvenance | `src/main/kotlin/tribixbite/cleverkeys/SuggestionProvenance.kt` | `SuggestionOrigin.NEXT_WORD` metas + long-press sheet formatting |
| StaticContextLm | `src/main/kotlin/tribixbite/cleverkeys/StaticContextLm.kt` | CKLM v1 loader for the shipped English context model `assets/lm/en.cklm` |
| StaticBigramSeed | `src/main/kotlin/tribixbite/cleverkeys/StaticBigramSeed.kt` | Pure parse/merge/rank over the shipped `assets/bigrams/<lang>_bigrams.json` cold-start pairs |
| BigramModel | `src/main/kotlin/tribixbite/cleverkeys/BigramModel.kt` | Loads those assets (async) and serves `getPredictions(prevWord)` — LM continuations first for English, curated pairs filling the rest |

## Architecture

```
committed word
   │  (LearningGate.learnCommittedWord — master gate + per-feature gates + incognito flag)
   ▼
ContextModel.recordCommit ──▶ BigramStore / TrigramStore   (RAM + debounced persist)
                                        │
        NextWordPredictor.decideTiers   │ ContextModel.getNextWordCandidates(maxResults=10)
        → TierGate(showStatic,          ▼   (only when useLearned) trigram (w1,w2) first,
                   useLearned, field)        bigram backoff, dedup
                └──▶ NextWordPredictor.candidatesFor ──▶ NextWordPredictor.generate
                              floors: freq ≥ 2 AND prob ≥ 0.05
                              filters: self-repetition, dictionary membership (+ user-vocab
                                       only when useLearned), not disabled, dedup
                              score = prob × (1 + personalizationBoost/4) × 1000
                                        │
                              THEN, only for slots still empty:
                              BigramModel.getPredictions(last context word)
                              (en.cklm / curated seeds; same filters, no floors, no personalization,
                               scores capped below the learned floor so learned always wins)
                                        │  (≤3 whole-bar; ≤2 appended after swipe alternates)
                                        ▼
                          SuggestionBar (NEXT_WORD metas, generation-guarded post)
```

## Gating — two tiers (`NextWordPredictor.decideTiers`, 2026-09-26)

| Tier | Requires |
|------|----------|
| **Static** (`TierGate.showStatic`) | `next_word_prediction_enabled` ∧ `word_prediction_enabled` ∧ not password mode ∧ no special prompt active (autocorrect-undo / add-to-dictionary / "Prefer … when swiping?") ∧ not Termux ∧ non-empty committed context |
| **Learned** (`TierGate.useLearned`) | static ∧ `LearningGate.canUseLearnedNextWord` = `on_device_learning_enabled` (master) ∧ `context_aware_predictions_enabled` ∧ field allows personalized learning (`EditorInfo.imeOptions` lacks `IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000`) |

`useLearned ⇒ showStatic` is enforced by `TierGate`'s constructor. The static tier ignores
the learning controls on purpose: it records nothing and its data is not personal (the
master gate's contract is about typing-derived data; `context_aware_predictions_enabled` is
"Learn from typing patterns"; the incognito flag forbids learning and personalization, not
generic suggestions — prefix completions there already use the same static model).

`NextWordPredictor.candidatesFor` is the single read path for every call-site. With
`useLearned` false it never calls `getNextWordCandidates`, `getPersonalizationBoostFor` or
`isInUserVocabulary`. `TierGate` also carries `fieldAllowsPersonalizedLearning`, passed to
`Predictor.isInDictionary(word, fieldAllowsPersonalizedLearning)` so the master-gated
selection-adaptation history cannot widen the static tier's dictionary filter in an
incognito field (fixed 2026-09-26). Next-word writes nothing: accepting a candidate is an
ordinary bar selection that goes through the normal, gated learn funnel.

## The four call-sites (SuggestionHandler)

| # | Trigger | Behavior |
|---|---------|----------|
| 1 | Word completed with space (`text == " "` only) | Show up to 3 candidates in the otherwise-empty bar |
| 2 | Manual suggestion tap (`isManualSelection` only) | Chain: regenerate from the grown context |
| 3 | Swipe auto-insert results | Keep alternates, APPEND ≤2 `NEXT_WORD`-tagged candidates; tap on those APPENDS instead of replacing the swipe word; generation runs on the shared prediction executor |
| 4 | Cursor parked with empty prefix (`handleCursorParkPrediction`) | Reads the text actually before the parked cursor (`readEditorParkContext` → `NextWordPredictor.contextFromEditorText`, sentence-boundary aware), so parking into an older paragraph predicts from it; the editor read is gated on the static-tier prerequisites (feature pref, word prediction, password / prompt / Termux) — not on the learning prefs, since the static tier needs the previous word with learning off; the words are used for the lookup and never recorded; falls back to session context if the editor cannot be read |

Staleness: async posts abort when `SuggestionBar.contentGeneration()` changed since submit.
Dismissal: any selection consumes the state; backspace with no partial word clears the
candidates; typing a letter switches to prefix predictions; sentence-final punctuation
resets the learned-context window (`WordPredictor.onSentenceBoundary()`).

## Configuration

| Setting | Key | Default | Values | Source |
|---------|-----|---------|--------|--------|
| **Next-Word Prediction** | `next_word_prediction_enabled` | `true` (`Defaults.NEXT_WORD_PREDICTION_ENABLED`; was `false` until 2026-09-26, an explicit stored `false` is kept) | bool — no longer disabled in Settings while Context-Aware is off | `Config.kt` |
| **Learn From My Typing** (master) | `on_device_learning_enabled` | `false` on fresh installs (v2.0; upgrades seeded `true` by `LearningMigration`) | bool — learned tier only | `Config.kt` |
| **Context-Aware Predictions** | `context_aware_predictions_enabled` | `true` | bool — learned tier only | `Config.kt` |
| **Context Source** | `context_source` | `"both"` | `both` \| `learned_only` \| `static_only` | `Config.kt` |
| **Personalization Strength** | `personalization_weight` | `1.0` | 0.0–2.0 | `Config.kt` |
| **Suggestion Origin Markers** | `suggestion_provenance_markers` | `false` | bool | `Config.kt` |

Constants: `MAX_SUGGESTIONS = 3`, `MAX_SWIPE_APPEND = 2`, `MIN_LEARNED_FREQUENCY = 2`,
`MIN_LEARNED_PROBABILITY = 0.05f` (`NextWordPredictor.kt`).

## Provenance

Each candidate carries `SuggestionMeta(SuggestionOrigin.NEXT_WORD, note = provenanceNote)`.
The note is a structured `ProvenanceNote.NextWord` value containing the effective context,
frequency, percentage, and static-seed flag; no display-language sentence travels through the
prediction pipeline. At long-press time, `SuggestionHandler` resolves Android resources and
`ProvenanceFormatter` renders the learned-statistics or built-in-continuation template. The
origin stays `NEXT_WORD` for both tiers. The opt-in marker dot uses the same origin metadata
through `SuggestionBar.originMarkerColor` (`OriginMarkerPalette`), and a marked entry's content
description reads the word followed by the localized origin label
(`SuggestionOrigin.labelRes()` / `SuggestionOriginA11y`), so TalkBack announces the origin
instead of the "●" glyph.

## Test Coverage

| Suite | File | Focus |
|-------|------|-------|
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/NextWordPredictorTest.kt` | Two-tier gate matrix (exhaustive 2^9), floors, filters, ranking, static cold-start tier |
| MockK | `src/test/kotlin/tribixbite/cleverkeys/NextWordStaticTierTest.kt` | Real `SuggestionHandler` + `WordPredictor` over the shipped `en.cklm`: static tier with learning off / context-aware off / incognito, learned tier never read there, incognito keeps selection history out of the filter, default ON |
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/StaticContextLmTest.kt`, `StaticLmAssetDriftTest.kt` | CKLM v1 loader contract; drift pins for the shipped `lm/en.cklm` |
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/StaticBigramSeedTest.kt` | Shipped asset schema, merge policy, fallback index |
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/OnDeviceLearningPrivacyTest.kt` | Master-gate-off ⇒ nothing recorded/persisted |
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/contextaware/ContextModelTrigramTest.kt` | Trigram→bigram backoff |
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/LearningWiringDriftTest.kt` | Forbids ungated learn-path regrowth |

## Related Specifications

- [Input Behavior Spec](../settings/input-behavior-spec.md) - Word-prediction section settings
- [Swipe Typing Spec](./swipe-typing-spec.md) - The swipe pipeline the appended candidates compose with
- [Autocorrect Spec](./autocorrect-spec.md) - Commit/undo interactions

## Verified suffix learning replacement (October 6 development)

Explicit “Append 's” and “Append apostrophe” use an opaque `LearningCommit`
from the accepted word. A replacement consumes only that commit's owned bigram,
trigram and user-vocabulary increments, restores its prior context window, then
records the full suffixed spelling. Undo receives a fresh handle for the restored
word. No word-only rollback or concrete-predictor cast is used for this feature.

Receipts bind predictor/store identity, mutation versions, language and privacy
gates. Another commit, context reset, clear/import, language or learning-gate change
expires them. Validation and mutation hold locks in bigram → trigram → vocabulary
order; editor calls occur outside these locks. With learning disabled, replacement
updates ephemeral context without reading or writing learned stores.

This is ownership of exact increments, not a durable database transaction. Recency
timestamps, capacity evictions and asynchronous persistence cannot be fully restored.
Suffix editing requires exact editor readback; unsupported editors refuse the command.
Current native and host evidence is maintained in the engineering testing strategy;
pre-feature full-suite counts do not validate this implementation.
