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
