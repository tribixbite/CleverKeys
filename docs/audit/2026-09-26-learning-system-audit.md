# Learning-system audit (2026-09-26)

Triggered by two user reports on the maintainer's phone (v2.0.0 build installed 2026-09-10,
i.e. BEFORE the v4 learning-consent migration `350f11b0`, so that migration is not the cause):

1. Swiping `she'd`, `I'd`, `they'll` … ranks badly.
2. Swiping `git` still yields `got` / `for` although the user types `git` constantly.

Method: three read-only audits in parallel (write paths, read/ranking paths, swipe contraction
ranking), then the load-bearing claims re-read at the cited lines. No code changed by this
audit. Line numbers are against `855ad2e1`.

---

## Root cause of report 2 (`git`): the swipe ranker has no learned input

| Surface | Selection adaptation | Personalization vocab | Learned context LM | User dictionary |
|---|---|---|---|---|
| Tap predictions | yes, ×1..2 (`WordPredictor.kt:2196`) | yes (`:2224`, `SuggestionProvenance.kt:208`) | yes (`:2213`) | yes |
| Autocorrect | exemption only (`WordPredictor.kt:2401`) | no | no | exemption |
| **Swipe CTC** | **no** | **no** | **no** | yes (trie merge, `CtcEngineAdapter.kt:641-735`) |
| **Swipe geometric** | **no** | **no** | **no** | yes (prepended) |
| Post-swipe slate | no | no | only if `swipe_context_rescoring` (default **off**) | casing only |
| Next-word | no | yes | yes | membership filter |

`rg` over `swipe/` finds zero references to `UserAdaptationManager`, `SelectionHistory`,
`PersonalizationEngine` or `UserVocabulary`. CTC final score is
`ctc/len^0.9 + β·len + λ·ln(freq)` with `freq` from the static trie only
(`CtcBeamDecoder.kt:162`).

Lexicon (`en_enhanced.json`, byte scale 134..255, λ = 4.0):

| word | freq | rank | λ·ln(freq) |
|---|---|---|---|
| git | 173 | 17,261 | 20.61 |
| got | 221 | 118 | 21.59 |
| for | 242 | 8 | 21.96 |

`git` starts 0.98 nats behind `got` and 1.34 behind `for`. Because emission evidence is divided
by `3^0.9 ≈ 2.69` for a three-letter word, `git` must out-emit `got` by about 2.6 raw nats. On
adjacent `i`/`o` keys that is a thin margin, and nothing the user does by typing moves it.

**Workaround available today:** add `git` to the personal dictionary (Dictionary Manager, default
frequency 255 → λ·ln = 22.17, beats both competitors; `CtcLexiconMerge.kt:76-84`). The
add-to-dictionary prompt never offers this automatically, because `git` is already in the lexicon
(`SuggestionHandler.kt:2060-2085`).

## Root cause of report 1 (contractions)

All 13 keys checked are lexicon-native, so `CtcContractionKeys.inject()` skips them.

**RC1 (confirmed): PAIRED variants are placed after every engine candidate.**
`swipe/ContractionOverlay.kt:83-88` keeps the base word and defers the variant. Deferred variants
are emitted only after the loop (`:111`). The CTC slate is TOP_K = 8, already padded by fuzzy
rescue (`CtcEngineAdapter.kt:963-973`) before the overlay runs. So `she'd`, `I'd`, `we'll`,
`she'll`, `we'd`, `I'll` and `he'll` land at slot 5–9, usually off-screen, and auto-insert always
commits the base word (`shed`, `id`, `well` …). This was introduced in `b2d7b908` (2026-07-22) to
stop `would`'s variants pushing `world` from #2 to #4. It over-corrected: instead of splicing the
variant next to its base, it now goes after everything.

**RC2 (confirmed): tap/swipe asymmetry.** For tapped `id` the tap path injects `i'd` at
top + 500 (`SuggestionHandler.kt:2304-2311`). Swipe has no equivalent.

**RC3 (confirmed): the pairing frequency is discarded.** `contraction_pairings.json` carries a
per-variant `frequency` (she'd / i'd = 200). `ContractionManager.loadPairedContractions` reads
only `"contraction"` (`ContractionManager.kt:703`). The overlay therefore cannot see that `she'd`
(200) outranks `shed` (189), or `i'd` (200) outranks `id` (196).

**RC4 (plausible, needs traces): the REPLACE group loses the prior.** This covers they'll, they'd,
he'd, you'd, it'll and you'll. Their keys (`theyll` 158, `youd` 163 …) sit in the bottom quartile
against pronouns at 232–243. After the β·len offset that is a 0.9–1.3 nat handicap. A clean trace
decides it by emission, but a sloppy one loses to `they` / `you`.

| Swiped | Key | Bucket | Key freq | Result today |
|---|---|---|---|---|
| she'd / she'll / we'd / we'll / I'd / I'll / he'll | shed / shell / wed / well / id / ill / hell | PAIRED | 178–223 | base auto-inserted, variant at tail |
| they'll / they'd / he'd / you'd / it'll / you'll | theyll / theyd / hed / youd / itll / youll | REPLACE | 152–170 | own slot; wins only if it beats the pronoun |

## Write-side defects (learning records the wrong thing, or nothing)

| # | Defect | Evidence | Status |
|---|---|---|---|
| W1 | Swipe **auto-insert is recorded as a user selection**, so every mis-swipe teaches the wrong word. Correcting by tap only ties the score. | `SuggestionHandler.kt:892` → `:1206` (not gated on `isManualSelection`) | confirmed |
| W2 | **No learning rollback** when a swipe is replaced from the bar (`:1264-1325`) or undone with backspace (`KeyEventHandler.kt:569-605`). The same is true when autocorrect is undone with backspace (`:611-650`). The context LM learns `got→git`, and `prev→got` stays incremented. `prev→git` is never recorded. | only `SuggestionHandler.kt:1855` calls `rollbackCommittedWord` | confirmed |
| W3 | **Bigram per-word cap makes new continuations unlearnable.** Once a word has 20 continuations, a new freq-1 entry sorts last and is truncated in the same call, so it never reaches the ≥2 floor. The trigram cap of 10 behaves the same way. | `BigramStore.kt:255-261`, `TrigramStore.kt:205` | confirmed |
| W4 | **30-day wholesale wipe of selection history.** `checkForPeriodicReset` defaults `last_reset` to *now* when absent, so it only fires once `last_reset` has been written, which any `resetAdaptation()` does. The v4 upgrade reset (`350f11b0`) now writes it for every upgrader, so those users' history is wiped every 30 days from then on. | `UserAdaptationManager.kt:43-49, 93-101, 170-178` | confirmed |
| W5 | **The last word before Enter, send or a field switch is never learned.** Enter is a key event and never reaches the text-typed path. `onFinishInputView` clears the tracker without flushing it. | `KeyEventHandler.kt:99`, `CleverKeysService.kt:801-819` | confirmed |
| W6 | Selection history persists only on every 10th selection, and `UserAdaptationManager.cleanup()` has no callers. Up to 9 selections are lost per process death, which LMK makes frequent on this device. | `SelectionHistory.kt:103`, `UserAdaptationManager.kt:182`, `PredictionCoordinator.kt:350` | confirmed |
| W7 | A tapped contraction learns garbage bigrams: `'` ends "don", then "t" is learned (`don→t`). | `SuggestionHandler.kt:1949` | confirmed by reading; not every `'` route traced |
| W8 | With `swipe_on_password_fields=true`, swiped password text feeds the learn funnel. The tap path has a password early-return; swipe does not. | `SuggestionHandler.kt:1921` vs `:765-770` → `:1544` | confirmed |
| W9 | Silent-off paths (each low probability): a Settings reset lands learning on OFF with no notice; `initializePiiComponents` catch-all leaves no predictor and no learning (`PredictionCoordinator.kt:108`). | | plausible |

Verified OK: per-feature defaults are `true` (`Config.kt:162-163`), and the `false` field
initialisers are overwritten on prefs read. The v4 migration logic is correct apart from W4's side
effect. Stores are per-language and thread-safe, and `DebouncedPersister` retries on failure.

## The `git` scenario end to end

1. Swipe → `got` is auto-inserted. `recordSelection("got")`, `recordWordUsage("got")` and bigram
   `prev→got` each get +1.
2. The user taps `git` in the bar. The text is replaced with no rollback. Then
   `recordSelection("git")` +1, `recordWordUsage("git")` +1, bigram **`got→git`** +1.
3. Result: adaptation and vocabulary see `got == git`, and the context LM favours `got` after
   `prev`. None of this reaches the swipe ranker anyway (root cause above).

## Recommended fixes, ordered by user impact

1. **Feed learned frequency into swipe ranking.** Either re-rank the engine slate with the same
   adaptation × personalization multipliers the tap path uses, or merge the top UserVocabulary
   words into the CTC trie through the existing user-word path (`UserWordFrequency.scaleOnto`).
   This needs W1 and W2 fixed first, or it amplifies wrong words.
2. W1 + W2: count only manual selections, and roll back the replaced or undone word in every undo
   path.
3. RC1 + RC3: splice a PAIRED variant directly after its base, and ahead of it when the pairing
   frequency beats the base's lexicon frequency. Add a pure test for the pronoun set.
4. W4: replace the 30-day wipe with decay, or remove it.
5. W3: frequency-aware cap with a grace slot for new entries.
6. W5, W6, W8, W7.
7. RC4: measure with replay traces before touching λ or the lexicon frequencies.

## Resolution: report 2 (`git`) — the swipe-correction offer (2026-09-26)

Automatic re-ranking from learned data failed two offline ship bars: usage priors
(`docs/eval/2026-09-26-learned-unigram-swipe-replay.md`) and correction priors
(`docs/eval/2026-09-26-correction-driven-swipe-prior-replay.md`). In both, a lifted word takes
swipes meant for its neighbours. What shipped instead: repeated swipe corrections produce an
OFFER. Accepting it creates the personal-dictionary entry that already works (default frequency
255, which is the CTC calibrated ceiling and beats `got`/`for`). Nothing is re-ranked unless the
user accepts, and each word needs its own consent.

**Recording** (`SwipeCorrectionTracker`, pure). A correction is X → Y, where X is a word a swipe
auto-inserted and Y is the word the user wanted. It is recorded in two cases:
- **Bar tap.** The user taps an alternate over X. This is the REPLACE branch of
  `onSuggestionSelected`.
- **Undo, then the next word.** The #110 backspace undo or delete-last-word removes X. That
  opens a pending-rejection slot, which the next committed word resolves:
  - **Typed word.** It resolves the slot only when the editor shows it exactly where X was. The
    check compares the text before the cursor at the undo, the new word, and at most one
    separator. This anchor check also handles "cursor moved away".
  - **Re-swiped word.** It becomes a candidate. The next event that does not reject it settles
    it. Undoing it or replacing it from the bar extends the chain: `[got, for] → git`.
  - **The slot is dropped** at a sentence end, Enter, leaving the field, an autocorrected
    commit, or after 30 s.

**Plausibility rule** (`SwipeCorrectionPolicy.isPlausible`). This filters changed-mind taps,
which §5a of the eval showed poison correction evidence. A pair counts only when all of these
hold:
1. Y ≠ X.
2. Y is letters-only and at least 2 characters long.
3. Y is a real word: lexicon, user dictionary, or learnable by repetition, and not disabled.
4. Y is one of these:
   - one of the engine candidates for X's swipe, or
   - a word with the same first and last letter as X (X's letters only, so `I'd` compares as
     `id`) and a length within ±1.

A bar tap always passes rule 4, so for taps the consent step is the remaining guard. Residue
the rule cannot catch: a change of mind between gesture neighbours (`soon`/`son`).

**Storage** (`SwipeCorrectionStore`). The store holds, per language:
- c(Y) (+1 per correction however long the chain),
- c(X→Y),
- declined words.

It is its own prefs file (`swipe_corrections`) and writes through. Limits:
- 200 target words, evicting the least recently corrected;
- 8 sources per target;
- 500 declined words.

It is written only when all of these hold: `LearningGate.canLearnSwipeCorrections` (master),
the field allows personalized learning, and the field is not a password field. The same gates
apply to reading, so the offer disappears when the master switch is off. Privacy's "forget
learned data" and the master-off prompt clear it. It is **not backed up**. That matches
`user_adaptation`: the Auto Backup allowlist does not include the file and manual Backup &
Restore does not export it. The durable result is the dictionary word, which is exported.

**Offer.** It appears when c(Y) ≥ 2 (`OFFER_MIN_CORRECTIONS`), Y is not already a user word,
and Y was never declined. It uses the add-to-dictionary prompt surface: bar chips with
`specialPromptActive` protection.
- **Chips.** "Prefer “git” when swiping?" and "Don't ask". The three strings are in all 22
  locales.
- **Accept.** Calls `DictionaryManager.addUserWord` → `refreshCustomWords`. The CTC memo keys on
  `custom_words_<lang>` (`LexiconContentVersion`), so the next swipe uses the new frequency, and
  `SwipeRewarmScheduler` rebuilds the lexicon in the background.
- **Decline.** Remembered permanently.
- **Undo.** The accept confirmation ("Swiping now prefers “git”") is tappable like every other
  dictionary-add confirmation (see "Undoable dictionary adds" below). Undo removes the word; the
  correction counts stay at 0 (accept already cleared them) and the word is NOT marked declined.
  It can be offered again, but only after two new corrections. Re-offering on the next slip would
  nag.
- **When it is shown.** Right after a bar-tap correction, a typed resolution, or a sentence end,
  when the bar is free. A correction that settles where the bar is busy or gone is **deferred**
  (2026-09-26):
  - a re-swipe settled by the next swipe (the bar holds that swipe's alternates);
  - Enter / the IME action;
  - leaving the field;
  - an autocorrected commit (the bar shows the autocorrect undo).

  The deferred offer appears at the bar's next idle moment in the **same language**:
  - the next space or `.?!` after a typed word;
  - the next bar tap;
  - the next cursor park. This is the idle bar right after Enter, or in the next focused field.

  It is shown once, in any field that passes the learning gates. It is re-checked against the
  store when shown, and dropped if the word was added or declined meanwhile, or the counts were
  erased. It stays in memory only. If the process dies first, the persisted count (still ≥ 2)
  offers the word at its next correction, which was the old behaviour. When a bar message
  (e.g. "Added …") is showing, the offer waits so it is not lost behind it.

**Contraction case.** Suppose `I'd` is promoted over `id` and the user corrects it to `id`.
That records `i'd → id` and offers `id`. Adding `id` to the user dictionary stops the
promotion, because `CtcEngineAdapter` looks up base frequencies in the merged lexicon
(`ContractionPromotionUserWordTest`).

**Feature B: ML-row relabel.** `MLDataCollector` reports the trace id of the row it stored. When
a correction is recorded, each rejected swipe's row gets `target_word = Y` and
`metadata.corrected_from = X` (`SwipeMLDataStore.relabelSwipe`), and `is_exported` is cleared.
The change is additive inside the JSON blob, so no SQLite migration is needed. This makes
on-device exports usable as a per-user replay pool, which §6 of the eval asked for.

**Apostrophe and hyphen targets are never offered — investigated 2026-09-26, kept out.**
The offer works only if a personal-dictionary entry makes swipes produce the word. For a joiner
word on the default EN CTC path it does the opposite (`swipe.SwipePreferJoinerWordTest` composes
the real pieces as `CtcEngineAdapter` wires them):
1. `CtcLexiconTrie.loadStrippingNonAlphabet` files the user word `she'd` under the a–z surface
   `shed`. The trie keeps the maximum frequency per surface, so the entry raises `shed` to the
   user ceiling. That is the word the user was correcting away from.
2. `ContractionOverlay` decides how a decoded `shed` is shown. `she'd` goes ahead only if its
   pairing frequency beats `shed`'s merged-lexicon frequency by `PROMOTION_MARGIN`. A user word
   `she'd` changes neither number, so `shed` stays the auto-insert.
3. A hyphen word has no overlay entry, and the EN branch keeps no display map. `co-op` can only
   surface as `coop`.

The CKDT languages do keep a display map, and a user word would win its surface's display slot.
But it would also make the apostrophe-free homograph unswipeable (fr `lune` for `l'une`). The
geometric engine skips joiner forms altogether. So one rule applies to every engine: joiner
words are excluded (`SwipeCorrectionPolicy` rule 2, KDoc updated). To make "prefer" work for
them, the decoder needs two changes (follow-ups, outside this offer):
- a user-word term in `ContractionOverlay`'s promotion rule, for example the variant's
  merged-lexicon frequency when it is a user word;
- an EN display map for joiner user words in `CtcEngineAdapter`/`CtcLexiconTrie`.

### Undoable dictionary adds (2026-09-26, user request)

Every IME path that adds a word to the personal dictionary now shows a **tappable**
confirmation. The paths are the "Add to dictionary?" prompt, the "+word" chip, the autocorrect
undo, and accepting the swipe offer. The confirmation was already a suggestion-bar message, not
a Toast (Toasts are invisible under the IME; see `ime-visual-feedback` skill). It used hardcoded
English "Added 'x' to dictionary"; it is now `suggestion_added_to_dictionary`, in all 22 locales.
- **First tap** changes it to "Tap again to undo" and restarts the timer.
- **Second tap** removes the word (`DictionaryManager.removeUserWord`) and runs
  `refreshCustomWords`. The swipe lexicon memo keys on the `custom_words_<lang>` content, so it
  follows. Then "Removed “x” from dictionary" shows. The text in the field is never touched.
- **Two taps, not one,** because the message sits where the next suggestion tap lands.
- **It disappears** after 3 s, or 3 s after the first tap. It is also dismissed without undoing
  by typing, backspace, a swipe, delete-last-word, another suggestion tap, or leaving the field.
- **Only a real insert is undoable.** `addUserWord` now returns whether the word was new to the
  store, checked exactly against a fresh read. An add that inserted nothing shows the plain
  message, so an undo can never delete a word the user already had.
- **An undo after the dictionary language changed does nothing,** because the store is per
  language.

The state machine is `UndoableBarMessage` (pure). The bar owns the timers
(`SuggestionBar.showUndoableMessage` / `dismissUndoableMessage`).

**Tests.**
- `SwipeCorrectionTrackerTest`, `SwipeCorrectionPolicyTest`, `SwipeCorrectionStoreTest`,
  `ContractionPromotionUserWordTest`, `SwipeMLDataRelabelTest`, `UndoableBarMessageTest`,
  `SwipePreferJoinerWordTest` (pure).
- `SwipeCorrectionOfferTest` (+ accept-undo, deferred offers), `DictionaryAddUndoTest`,
  `DictionaryManagerTest` (`addUserWord` return), `SwipeMLRelabelStoreTest` (mock).

**Not done.**
- Counter-evidence (X kept while Y was in the beam) is not recorded.
- Y is added in lowercase.
- A deferred offer is lost if the process dies. The persisted count re-offers at the word's next
  correction.
- After Enter that follows a SWIPED word, InputCoordinator keeps the swipe alternates instead of
  running the cursor park, so the deferred offer waits for the next typed completion or tap.
- The bar's view wiring (click listener, timers) has no JVM test; the state machine and the
  handler side do. Device check pending.
- Apostrophe and hyphen targets are never offered (see above).
