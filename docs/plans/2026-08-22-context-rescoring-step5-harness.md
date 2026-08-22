# Step 5 — the context-rescoring evidence harness

**Status**: planned, not started. Steps 1-4 of
`docs/specs/ctc-context-rescoring-and-tunables.md` are landed and the feature is reachable but
**default OFF**. This document is the plan for the one remaining step, plus the corpus research
that preceded it.

**Why this exists**: step 5 is the gate on flipping the default. Without it the feature ships
inert forever, and with a badly-built version of it the feature ships on bad evidence. Both
failure modes are worse than taking the time.

---

## 1. Where things stand

| Step | State | Commit |
|---|---|---|
| 1. Pure rescorer | done — log-linear math, identity-at-empty-stores, rank-1 score-ratio guard **and** the strict `NextWordPredictor` evidence floors | `c6bddd26`, floors in `d09b4b8b` |
| 2. Gated accessor | done — `WordPredictor.getSwipeContextEvidence`, non-loading store peeks, `ContextModel.getContextEvidence`/`boostFor` | `d09b4b8b` |
| 3. Pref plumbing | done — `swipe_context_rescoring`, default `false`, settings toggle | `d7787120` |
| 4. Seam wiring | done — `SuggestionHandler.rescoreWithContext` behind all four §3 gates, provenance note on a promoted rank 1 | `f7123db4` |
| **5. Evidence harness** | **THIS DOCUMENT** | — |
| 6. Default flip | evidence-gated release decision, not a code change | — |

Already built for step 5: **`RescoringMetrics`** (`058a4a8e`) — the paired
FIXED/BROKEN/UNCHANGED/WASH classifier and the two ship-bar numbers. Corpus-independent, 14 tests.

---

## 2. The data situation, measured

### 2.1 The maintainer's own device export — the headline number is 10x optimistic

Exported 2026-08-21 via Settings → Backup & Restore → export dictionaries, which writes
`learned_bigrams_by_language` (the UI calls these "phrases"; the JSON key does not — that
mismatch is why they appeared missing at first).

```
en: 6,589 pairs   fr: 99 pairs   user_vocabulary: 5,000
```

**But the store's confidence floor (`minFrequency = 2`) excludes hapax legomena:**

| frequency | en pairs | usable? |
|---|---|---|
| 1 | 5,947 (90.3%) | **no — contributes zero boost** |
| >= 2 | 642 (9.7%) | yes |
| >= 2 and p >= 0.05 | 603 | also clears the rank-1 promotion floors |

**The effective context model is ~642 pairs, not 6,589.** This is the single most important
number in this document: it means one real user's learned data produces a *thin* signal, and any
plan that assumed 6.5k pairs of evidence was wrong by an order of magnitude.

The surviving pairs are the right ones — `in→the` (27), `i→don't` (19), `its→it's` (18),
`want→to` (15), `can→you` (15). `its → it's` is exactly the near-tie disambiguation the feature
exists to win.

Note: the 99 French pairs include `but→they` and `agent→was` — English typed while French was
active. Per-language stores are not cleanly separated in practice.

### 2.2 Corpus research — two agent sweeps, findings that survived verification

The first sweep concluded "no clean modern real-chat English corpus exists in 2026". **That is
too strong** — it reasoned from the API-lockdown history rather than checking current
availability. The lockdowns killed new collection, not existing mirrors.

**Verified usable, by role:**

| Role | Source | Licence | Gate |
|---|---|---|---|
| Committed fixture (en) | **Ubuntu Dialogue Corpus** — ~1M dialogues of REAL typed IRC chat, real typos | Apache 2.0 *(Kaggle repackage label — SECONDHAND, verify before committing)* | none |
| Committed fixture (multi) | **oasst2** prompter turns — all 7 app languages | Apache 2.0 | none |
| Committed fixture (fallback) | **Tatoeba CC0 subset** | CC0 | none |
| Bulk en | OpenSubtitles v2024 mono / Sentiment140 / Reddit HF mirrors | mixed → local-only | none |
| **fr** | **CoMeRe / ORTOLANG** — 88milSMS + smsAlpes + smsLaRéunion, ~123k **consented, professionally anonymised** SMS | **CC BY 4.0** | **none** |
| **sv** | **Språkbanken Flashback + Familjeliv** | **CC BY 4.0** | **none** |
| es / pt | Stack Exchange dump (es./pt.stackoverflow) | CC BY-SA 4.0 | none |
| de | WikiConv-de (CC BY-SA); MoCoDa2 / What's Up Switzerland are human-gated | mixed | form/email |
| Held-out register check | NUS SMS (55k real SMS) | no LICENSE file; **personal names unmasked** | none |

**Confirmed dead**: Internet Archive Twitter Stream Grab (every item access-restricted),
Edinburgh Twitter corpus, Gitter/Matrix bulk archives, ID-only tweet sets.

**Unverified — re-check before relying on**: the Ubuntu-Dialogue Apache 2.0 label,
Sentiment140's licence (folklore), MoCoDa2's bulk export path, DiDi repository access,
the Spanish UNAM WhatsApp corpus (no data URL exposed).

Note the French SMS set has materially better provenance than NUS SMS (consented + legally
reviewed anonymisation, vs names never masked) at a cleaner licence with no gate.

### 2.3 Discord — viable, bot-token only, eval-only

The maintainer runs Discord servers. Position, verified against current policy documents:

- **User-token export (DiscordChatExporter's default) is prohibited AND actively enforced**
  since ~March 2026 — forced logouts, ToS-abuse warnings, one user blocked 8 times in a week.
  The self-bot article's wording hardened to "forbidden… can result in account termination".
  Being the server admin is **not** a carve-out.
- **Bot token + Message Content intent is the clean mechanism** (self-serve toggle under 10k
  users). DiscordChatExporter v2.47.3 (2026-06-26) supports bot tokens; export JSON and parse
  `messages[].content`, filtering `author.isBot` and non-`Default`/`Reply` types.
- **Developer Policy #21 is the binding constraint**: *"Do not use message content obtained
  through the APIs to train machine learning or AI models… unless express permission is granted
  by Discord."* Deriving aggregate n-gram counts to EVALUATE a decoder is defensible — it is
  statistical analysis, not training. **Those same counts seeding the shipped predictor would be
  squarely prohibited.** If Discord-derived data ever enters this repo, that boundary must be
  documented AT THE CODE, not only in a commit message — it is one refactor from being crossed
  silently.
- Publishing members' raw text has no licence basis (authors license Discord, not the exporter).

**Privacy mitigations if used** — n-gram counts are NOT automatically anonymous; rare n-grams
leak names, numbers and addresses:
min count >= 5 **and** >= 3 distinct authors (k-anonymity style, computed before author hashes
are dropped); drop any n-gram containing a digit; cap per-author contribution; public channels
only, never DMs; delete the raw export after counting; announce it in server rules with an
opt-out.

**Recommendation: not needed to START.** Build the harness on data in hand and find out whether
there is a signal at all. If there is none on real user context, more corpus will not rescue it.
If there is a strong one, that is when broadening the English data earns its cost.

---

## 3. The plan

### Stage A — corpus adapter (no network, no licence argument)

Read `learned_bigrams_by_language` from a dictionary export at a **path supplied by the caller**.
Never a committed fixture: it is personal data and this repo is public.

- Parse `{word1, word2, frequency, probability}` — the same four fields
  `SwipeContextRescorer.Evidence` consumes.
- Seed a `ContextModel` backed by `InMemoryLearnedStorage` (the pure-test pattern from
  `ContextEvidenceLookupTest`), by replaying `recordBigram` `frequency` times so the store's own
  floors apply naturally rather than being bypassed.
- **Report the usable fraction** (freq >= 2) in the harness output. A run that silently seeds
  5,947 inert pairs and reports "6,589 loaded" is lying about its own statistical power.

### Stage B — trace pairing

For each usable bigram `(w1, w2)`, find a swipe trace for `w2` in the FUTO pool
(`~/.cache/cleverkeys-test/futo_swipe5_*.jsonl.gz`, the pattern `scripts/ctc_injection_ab.py`
already uses). That pair simulates "user committed w1, then swiped w2" — the exact situation the
feature is designed for.

Coverage will be partial; **report it**, since it is the denominator of everything downstream.

### Stage C — the A/B

For each paired trace, decode once through the shipping adapter path, then score twice:

1. **control** — engine order, no rescoring
2. **treatment** — `SwipeContextRescorer.rescoreOrder` with the seeded evidence

Classify with `RescoringMetrics.classify(target, controlTop1, treatmentTop1)`.

**Score by CORRECTNESS against the target, never by the shape of the change.** This is not a
style note: `scripts/ctc_injection_ab.py` originally classified by shape and reported a fix as a
regression. `RescoringMetricsTest` pins the rule in both directions.

### Stage D — report and gate

Emit `Tally`: n, fixed, broken, wash, unchanged, `deltaTop1`, `promotionErrorRatio`.

**§7.3's bar needs BOTH**: a net top-1 gain AND breakages under 20% of fixes. A positive delta
alone is insufficient — 10 fixed / 8 broken nets +2 while costing eight users a word they had
swiped correctly. `Tally.meetsShipBar()` encodes this; a test pins that exact case failing.

### Stage E — tune, then confirm

Fit `WEIGHT` (currently 0.5) on a **tune half**, confirm on a **held-out half** — the
tune/confirm discipline the per-language lambda sweep already follows. Do NOT report a number
fitted and evaluated on the same traces.

Publish results under `docs/eval/`.

---

## 4. Honest limits to state in the results

- **One user's context distribution.** ~642 usable pairs from one person answers "does this help
  this user", not "is this safe for everyone" — and §7.3's bar is about the latter. A public
  corpus arm is what generalises; the device arm is what is real.
- **Bigrams only.** Trigrams are deliberately excluded from the export (they re-learn quickly),
  so a device-seeded replay exercises the bigram-backoff path only. Any measured gain is a
  **floor, not a ceiling**.
- **Isolated-word traces.** The FUTO pool is `{word, trace}` with no sentence context; the
  pairing supplies context artificially. That is the intended design, but it means the harness
  measures the rescorer, not the whole typing experience.

---

## 5. What must not happen

- Do not commit the device export or anything derived per-user from it.
- Do not flip the default without stage D clearing both numbers. It is a release decision.
- Do not let Discord-derived counts reach the shipped predictor (Developer Policy #21).
- Do not report a `WEIGHT` fitted and confirmed on the same half.
