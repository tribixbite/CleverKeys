# F3 — French hyphen-compound contractions: data policy

**Date**: 2026-08-20 · **Status**: POLICY (no assets changed) · **Base**: HEAD `2d080c7d` ·
**Parent**: `docs/proposals/2026-08-18-contraction-handling.md` §F3

Every claim below was re-derived against the shipped assets and the live code at this HEAD —
not carried over from the parent proposal — because two of the parent's own supporting claims
turned out to be wrong (§1). The classifier verdicts use the SAME oracle stack the `8230333b`
recovery used (`scripts/extract_apostrophe_words.py`: lexicon ordinal < 1200, hunspell fr_FR
lowercase+capitalised probes, ASK f ≥ 2, wordfreq zipf ≥ 2.0), executed against the real
`fr_enhanced.bin`, the real ASK `fr_wordlist.combined.gz`, and the Termux hunspell fr_FR.

## 0. Decision summary

1. **fr only.** No other bundled language has the class: the ASK wordlists carry **0**
   lowercase hyphen tokens for de/es/pt, **1** for nl (`g-mail`), and Italian's 246 are all
   English loanwords (`play-off`, `e-mail`, `on-line`) — and Italian is geometric-only, where
   alias keys are inert anyway (C1). German's real contraction class (`geht's`) is apostrophe,
   already shipped.
2. **Curated allowlist, NOT corpus extraction.** A bulk hyphen extraction (the literal reading
   of F3) yields 16,687 keys of which **73 are native French words with no rank protection**
   — `minuit`←`mi-nuit`, `haha`←`ha-ha`, `dodo`←`do-do`, `amies`←`ami-e-s`, `parla`←`par-là`,
   `nonne`←`non-né` — the exact `lune` damage shape, a third time. The safe mechanism already
   exists and has a shipped precedent: `CURATED_CONTRACTIONS` (German's 17 clitic entries),
   guarded by an exact-content test.
3. **45 curated entries, all REPLACE**, split in two phases by test-pin impact:
   **Phase A** — 17 accent-free values, zero test relaxation needed;
   **Phase B** — 28 accent-carrying values (`peut-être`, `lui-même`, `là-bas`…), which require
   one deliberate relaxation of a `BundledContractionDataTest` pin (§5).
4. **`y a-t-il` and every space-carrying value stay excluded** — confirmed, three independent
   reasons (§6).
5. The verb-inversion class (`a-t-il`, `est-il`, `dit-elle`…) is **deferred** — it contains
   three verified landmines (§7) and its tap value is near zero.

## 1. Corrections to the parent proposal

- **"`labbe → l'abbé` works end to end" is FALSE.** The shipped key is `labbé` (accented,
  one of fr's 2 accent-keyed leftovers) — non-injectable, unreachable on QWERTY. There is
  **no shipped example** of a value that differs from its key beyond joiners: a scan of all
  17,931 fr REPLACE entries finds **0 hyphen-carrying values and 0 accent-divergent values**.
  `aujourdhui → aujourd'hui` proves only the *apostrophe* class. The hyphen/accent value class
  is therefore genuinely untested in shipping data — which is why the test pins matter (§5).
- The parent's F3 sketch ("extend the extractor, regenerate") is what §2 argues against.
- One shipped precedent of the flattening problem exists: `doutremer → d'outremer` (the
  hyphen of `d'outre-mer` silently dropped from the value). None of the 45 proposed keys
  collides with it or any other shipped fr key (verified).

## 2. Why NOT a bulk regeneration (the adversarial case)

All 16,813 lowercase hyphen tokens in the ASK fr wordlist were projected to a–z keys
(16,687 distinct) and cross-checked:

| Hazard | Count | Worst examples |
|---|---|---|
| Key is a native fr word, ordinal ≥ 1200 → REPLACE **destroys** it in-slot | **73** | `minuit`@4132←`mi-nuit`, `haha`@4104, `amies`@5163←`ami-e-s` (écriture-inclusive token), `weekend`@6502, `dodo`@14002, `tata`, `parla` (passé simple of *parler*)←`par-là`, `nonne` (nun)←`non-né` |
| Classifier misfire on the natives: hunspell fr_FR rejects anglicisms and 1990-reform spellings, so the append rescue FAILS | ≥6 verified | `weekend`, `email`, `entretemps` (ord 30334, standard since the 1990 rectifications), `startup`, `offshore`, `flashback` — all would be shipped REPLACE and destroyed |
| Value is not standard orthography (ASK junk) | uncounted, real | `parce-que` (correct: *parce que*), `quelque-chose`, `ami-e-s`, `ha-ha` |
| Key is an en top-3000 word → unguarded tap transform (`SuggestionHandler.kt:1918`) rewrites English typing for any fr+en user | 6 | `weekend`@1365, `email`@1947, `id`@2477, `baseball`@2615, `basketball`@2616, `haha`@2731 |
| Key collides with an existing fr entry (merge-order fights) | 21 | `doutremer`, `questelle`, `lami`, … |

The ordinal guard protects exactly **1** of the 73 natives (`avis` ← `à-vis`). The runtime
rank guard is NOT a safety net here — the same measured fact (`lune`@2055 > 1200) that caused
the original damage. A bulk add is unshippable without a per-entry human pass, which is what
the curated table *is*.

## 3. Key derivation, reachability, frame budget

**Key = `CtcAzProjection.project(value)`**: NFD-decompose, drop combining marks, drop the
joiners `'` `’` `-`, require pure a–z (`CtcAzProjection.kt`; mirrored by `project_az` in the
generator). So `qu'est-ce → questce`, `peut-être → peutetre`, `c'est-à-dire → cestadire`.
`ContractionManager` lowercases keys at load and lookup; `CtcContractionKeys.isInjectable`
accepts any pure-a–z key. Notable non-derivables: `chef-d'œuvre`, `belle-sœur` — `œ` is a
ligature, not a combining-mark carrier, so `project` returns null. **Excluded.**

Reachability per path, for a key that is NOT a lexicon word (all 45 are not):

- **CTC (the default fr swipe path)** — reachable: `CtcEngineAdapter` injects every alias key
  at the floor frequency (`CtcContractionKeys.inject`), the beam can spell it, and
  `ContractionOverlay` rule 2b replaces it in-slot (ordinal lookup returns null → not
  guarded → replace). Canonical-display runs BEFORE the overlay and cannot interfere: none of
  the 45 keys is a projected surface of any lexicon word (verified). Same floor caveat as the
  existing 17.8k tail: zero log-frequency bonus, wins on emission evidence only — by design.
- **Geometric** — **NOT reachable** (no alias injection on that path, and no key is a
  dictionary word). This matters only for users who force `geometric` mode with fr, or
  non-a–z layouts; identical to the status of the existing productive-elision tail. This fix
  must not be booked as progress on C1.
- **Tap** — reachable when fr is primary or secondary (`loadTypingMappings` scopes correctly
  since F1): exact-partial hit (`SuggestionHandler.kt:1893`, +1000), prefix completion via
  `WordPredictor`'s alias ingestion, and the whole-prediction transform (`:1918`).
  `capitalizeIWord` only touches the English I-words list — hyphen values pass through.
  Committed values are opaque strings; `aujourd'hui` proves the commit path. After commit the
  context tracker re-tokenises on the hyphen (`PredictionContextTracker.kt:59-64`), so
  next-word context sees `ce` after `qu'est-ce` — identical to a manually typed hyphen
  compound, cosmetic, no action.

**Frame budget** (`CtcDecodableLength`, 32 frames): longest proposed key is
`grandsparents` = 13 letters, 0 adjacent duplicates → 13 frames. Every candidate was checked;
max is 13, worst duplicate case (`ellesmemes`) needs 11. **No candidate is budget-inert.**
The policy still requires the regeneration test to assert `isDecodable` per key (§8) so a
future addition cannot silently ship an inert entry.

## 4. The candidate list (ordered, with per-entry evidence)

Classifier columns: `hunspell` = fr_FR attests key (lower or capitalised); `askF(k)` = ASK
frequency of the bare key; `zipf` = wordfreq zipf of the key. APPEND requires
ordinal < 1200 OR all three oracles; **every entry below fails all four → REPLACE**, i.e. the
bucket is derived, not assumed. `native` = key is a decodable fr lexicon surface (all False).
No key collides with any existing fr entry, the English base, or the it/de/es lexicons except
where flagged.

### Phase A — accent-free values (17; no test relaxation needed)

| # | key | value | frames | hunspell / askF(k) / zipf | verdict | notes |
|---|---|---|---|---|---|---|
| 1 | `questce` | `qu'est-ce` | 7 | F / 0 / 1.51 | REPLACE | the headline fix; `quest → qu'est` stays and still fires for partial swipes |
| 2 | `estce` | `est-ce` | 5 | F / 0 / 2.49 | REPLACE | zipf > 2 is apostrophe-stripped-corpus debris; hunspell+ASK both reject — the 3-oracle AND is what keeps this REPLACE |
| 3 | `nestce` | `n'est-ce` | 6 | F / 0 / 0.00 | REPLACE | |
| 4 | `celuici` | `celui-ci` | 7 | F / 0 / 1.55 | REPLACE | |
| 5 | `celleci` | `celle-ci` | 8 | F / 0 / 1.60 | REPLACE | |
| 6 | `ceuxci` | `ceux-ci` | 6 | F / 0 / 1.15 | REPLACE | |
| 7 | `cellesci` | `celles-ci` | 9 | F / 0 / 0.00 | REPLACE | |
| 8 | `audessus` | `au-dessus` | 9 | F / 0 / 1.87 | REPLACE | |
| 9 | `audessous` | `au-dessous` | 10 | F / 0 / 0.00 | REPLACE | |
| 10 | `cidessus` | `ci-dessus` | 9 | F / 0 / 1.53 | REPLACE | |
| 11 | `cidessous` | `ci-dessous` | 10 | F / 0 / 1.72 | REPLACE | |
| 12 | `quelquesuns` | `quelques-uns` | 11 | F / 0 / 0.00 | REPLACE | |
| 13 | `quelquesunes` | `quelques-unes` | 12 | F / 0 / 0.00 | REPLACE | |
| 14 | `grandsparents` | `grands-parents` | 13 | F / 0 / 0.00 | REPLACE | |
| 15 | `avanthier` | `avant-hier` | 9 | F / 0 / 0.00 | REPLACE | |
| 16 | `demiheure` | `demi-heure` | 9 | F / 0 / 0.00 | REPLACE | |
| 17 | `rendezvous` | `rendez-vous` | 10 | F / 0 / 2.35 | REPLACE | **flag**: en lexicon @18993 and a de lexicon word. A fr+en tap user typing English *rendezvous* gets it rewritten to the hyphenated French spelling via the unguarded `:1918` transform. Spelling-only drift of a rare word; the first entry to cut if the list must shrink |

### Phase B — accent-carrying values (28; requires the §5 pin relaxation)

| # | key | value | frames | oracles | verdict | notes |
|---|---|---|---|---|---|---|
| 18 | `peutetre` | `peut-être` | 8 | F / 0 / 1.86 | REPLACE | highest-value entry in the whole task after `qu'est-ce` |
| 19 | `cestadire` | `c'est-à-dire` | 9 | F / 0 / 0.00 | REPLACE | |
| 20 | `audela` | `au-delà` | 6 | F / 0 / 1.05 | REPLACE | |
| 21 | `labas` | `là-bas` | 5 | F / 0 / 2.19 | REPLACE | |
| 22 | `lahaut` | `là-haut` | 6 | F / 0 / 1.65 | REPLACE | |
| 23 | `ladedans` | `là-dedans` | 8 | F / 0 / 0.00 | REPLACE | |
| 24 | `ladessus` | `là-dessus` | 9 | F / 0 / 0.00 | REPLACE | |
| 25 | `apresmidi` | `après-midi` | 9 | F / 0 / 0.00 | REPLACE | |
| 26 | `celuila` | `celui-là` | 7 | F / 0 / 0.00 | REPLACE | |
| 27 | `cellela` | `celle-là` | 8 | F / 0 / 0.00 | REPLACE | |
| 28 | `ceuxla` | `ceux-là` | 6 | F / 0 / 0.00 | REPLACE | |
| 29 | `cellesla` | `celles-là` | 9 | F / 0 / 0.00 | REPLACE | |
| 30 | `moimeme` | `moi-même` | 7 | F / 0 / 0.00 | REPLACE | |
| 31 | `toimeme` | `toi-même` | 7 | F / 0 / 0.00 | REPLACE | |
| 32 | `luimeme` | `lui-même` | 7 | F / 0 / 0.00 | REPLACE | highest ASK f (147) of all hyphen tokens |
| 33 | `ellememe` | `elle-même` | 9 | F / 0 / 0.00 | REPLACE | |
| 34 | `soimeme` | `soi-même` | 7 | F / 0 / 0.00 | REPLACE | |
| 35 | `nousmemes` | `nous-mêmes` | 9 | F / 0 / 0.00 | REPLACE | |
| 36 | `vousmeme` | `vous-même` | 8 | F / 0 / 0.00 | REPLACE | |
| 37 | `vousmemes` | `vous-mêmes` | 9 | F / 0 / 0.00 | REPLACE | |
| 38 | `euxmemes` | `eux-mêmes` | 8 | F / 0 / 0.00 | REPLACE | |
| 39 | `ellesmemes` | `elles-mêmes` | 11 | F / 0 / 0.00 | REPLACE | |
| 40 | `grandmere` | `grand-mère` | 9 | F / 0 / 0.00 | REPLACE | |
| 41 | `grandpere` | `grand-père` | 9 | F / 0 / 0.00 | REPLACE | |
| 42 | `bellemere` | `belle-mère` | 10 | F / 0 / 0.00 | REPLACE | (`belle-sœur` excluded — `œ` non-projectable) |
| 43 | `beaupere` | `beau-père` | 8 | F / 0 / 0.00 | REPLACE | |
| 44 | `beaufrere` | `beau-frère` | 9 | F / 0 / 0.00 | REPLACE | |
| 45 | `visavis` | `vis-à-vis` | 7 | F / 0 / 0.00 | REPLACE | |

**Counts after regeneration**: `contractions_fr.json` 17,931 → **17,948** (Phase A) →
**17,976** (A+B). `contraction_pairs_fr.json` stays **183** (all verdicts REPLACE — verified
the classifier will not divert any curated key into the pairs file). Total-entries pin
18,114 → 18,131 → 18,159.

**Mechanism**: add a `"fr"` table to `CURATED_CONTRACTIONS` in
`scripts/extract_apostrophe_words.py` (the German precedent — curated wins over extraction
and shipped, survives re-runs, flows through the classifier, passes the `is_ctc_injectable`
live filter) and re-run `--lang fr`. Do NOT run other languages in the same pass.

## 5. The test pin that blocks Phase B — and the deliberate relaxation

`BundledContractionDataTest.every contraction value differs from its key by apostrophes and
hyphens only` compares key and value stripped of `'` `’` `-` — it does **not** fold accents,
so `peutetre → peut-être` fails it today (`peutêtre` ≠ `peutetre`). This strictness is
load-bearing: it is what would refuse `nonne → non-né` (§2). The relaxation must be minimal:

- Change the comparison to NFD-fold (strip combining marks) **both** sides after stripping
  joiners — accents may be *restored*, letters may never change. Add a negative unit case
  proving a letter-changing value still fails.
- Compensate for the lost strictness with an **exact-content pin** for the curated hyphen
  set (the German-file pattern): every curated key → its exact value, and negative
  assertions that the §2 landmine keys (`weekend`, `email`, `haha`, `minuit`, `parla`,
  `nonne`, `amies`, `entretemps`, `estelle`, `aton`) are NOT present in either fr file.

The other pins already accommodate the additions unchanged:
`SwipeContractionLanguageIsolationTest`'s projection invariant folds accents
(`project(value) == key` — passes for every candidate), the replace/append disjointness pin
holds (nothing added to pairs), and `no replace-mode key is a common word` holds (no key has
an ordinal at all).

## 6. Space-carrying values (`y a-t-il`) — exclusion CONFIRMED

1. **Two test pins reject them structurally**: the strip-comparison (a space is not a
   joiner) and the projection invariant (`CtcAzProjection.project` returns null on a space).
2. **The word machinery is single-token**: context tracking, backspace-undo, autospace and
   the learn funnel all assume one committed token; a space inside a "word" is a multi-word
   suggestion, i.e. next-word-prediction territory (parent §6 stands).
3. **The corpus doesn't even attest it**: ASK has no `y a-t-il` token (f = 0); the extractor
   could not produce it. `a-t-il` itself is deferred with the inversion class (§7).

## 7. Deferred: the verb-inversion class (and the other near-misses)

`a-t-il`/`a-t-on`/`est-il`/`dit-elle`/`semble-t-il`… were fully evaluated and are NOT in the
list. Reasons, each verified:

- **`estelle` (est-elle) is a native fr lexicon word @16343** (the name *Estelle*) and the
  classifier returns APPEND — the only candidate that is not REPLACE. Appending `est-elle`
  to every swipe of a name is noise with a real owner.
- **`aton` (a-t-on) is ASK-attested (f=64: the deity *Aton*)**; only the hunspell gap keeps
  it REPLACE. Rank-unprotected proper nouns are exactly the `estelle` problem one misfire
  later.
- **`entretemps` (entre-temps) is a native word @30334** — the standard post-1990 spelling —
  and hunspell fr_FR rejects it, so the classifier would wrongly REPLACE-destroy it. Adding
  it requires a `FORCED_APPEND` entry; fine, but it belongs with a deliberate
  inversion-class pass, not this one.
- The class is open-ended (any verb × 6 clitics), its tap value is ~nil (users type the
  hyphens), and its swipe keys are very short (`atil` = 4 letters), where a floor-frequency
  injected path competes closest with real-word beam paths. If ever wanted: interrogatives
  only, `FORCED_APPEND` for `estelle`/`entretemps`, explicit `aton` exclusion.

Also deliberately excluded: `week-end`, `e-mail`, `basket-ball`, `base-ball` (native fr
solid spellings + en top-3000 collisions — REPLACE would damage both languages), compound
numbers (`dix-sept`… — open-ended, low value), `sur-le-champ`/`porte-parole`/noun tail
(unbounded; nothing distinguishes them from the 16.6k tail except rank), and everything
`œ`-carrying (non-projectable).

## 8. Verification checklist for the regeneration commit

Run in order; a regression cannot ship silently if all of these hold.

1. **Pre-flight**: clean `git status`; snapshot `contractions_fr.json` +
   `contraction_pairs_fr.json` key sets. `git log` for foreign commits (shared tree).
2. **Dry-run first**: `python3 scripts/extract_apostrophe_words.py --lang fr --dry-run` —
   expected counts exactly 17,948/183 (A) or 17,976/183 (A+B).
3. **Diff discipline**: `git diff --stat` touches only `contractions_fr.json` (+ tests, +
   the generator). Assert with a script, not eyes: old keys ⊆ new keys (zero deletions);
   added keys == exactly the curated set; every pre-existing key's value byte-identical;
   pairs file byte-identical. (The generator's `load_shipped` non-destructive merge is the
   mechanism the 49.6k trim went around — verify it, don't trust it.)
4. **Pure suite** (`sh gradlew runPureTests` on ARM64), with these updates in the SAME
   commit:
   - `BundledContractionDataTest`: size pins updated to the exact numbers above; the new
     exact-content curated-hyphen test (§5) including the landmine negatives, a
     no-space-in-any-value assertion, and per-key `CtcContractionKeys.isInjectable` +
     `CtcDecodableLength.isDecodable`;
   - Phase B only: the NFD-fold relaxation + its letter-change negative case;
   - `ContractionOverlayTest`: hyphen value replaces in-slot (rule 2b) — `questce` →
     `qu'est-ce`; and an accented one (`peutetre` → `peut-être`) for Phase B;
   - `CtcContractionKeysTest`: `questce` injectable, trie-reachable after inject, log-freq
     ≈ 0 (mirrors the existing `dabaissement` assertions);
   - unchanged-green: replace/append disjointness, `no replace-mode key is a common word`,
     `SwipeContractionLanguageIsolationTest` (both the projection invariant and typing
     scoping), `ContractionManagerTest`.
5. **Spot checks** on the shipped file: `questce`/`estce`/`peutetre` map to the exact
   values; `aujourdhui → aujourd'hui` unchanged; `lune` still pairs-only; `weekend`/`email`
   absent from both files.
6. **Instrumented (optional, ew-cli)**: `CtcMultiLanguageInstrumentedTest` smoke — fr decode
   still free of English morphology.

## 9. Residual risks / honest caveats

- **Floor-key surfacing is still unproven at decoder level** (parent F6 remains open): the
  new keys are trie-reachable but nothing asserts a real beam surfaces a 0-bonus path for
  them. They inherit the same "reachable, never preferred" contract as the existing 17.8k
  tail — a user may swipe `peutetre` cleanly and still not see `peut-être` if emission
  evidence is weak. Do not promise more than the mechanism delivers.
- **Geometric-mode fr users get nothing** from this change (no alias injection there — C1).
- **`rendezvous`** is the one cross-language key (en@18993, de lexicon); cut it if any doubt.
- The unguarded tap transform (`SuggestionHandler.kt:1918`) is the standing amplifier that
  turns any future mis-bucketed REPLACE key into typing damage; the curated-only policy is
  the mitigation, not a fix for that amplifier.

— Opus 5
