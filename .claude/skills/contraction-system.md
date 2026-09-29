# Contraction System Skill

Read this BEFORE touching anything named `contraction*`, `apostrophe`, `elision`, `collision`,
`ContractionManager`, `ContractionOverlay`, or the display of `don't` / `c'est` / `qu'est-ce`.

The system spans ~30 source files, 22 shipped data files, 32 test files and 4 generator scripts.
It has been broken and repaired several times in the same way, so the invariants below are not
style preferences — each one is a shipped regression that reached users.

---

## 1. The core idea

Apostrophe is not a swipe key and rarely a comfortable tap key, so **every dictionary stores
contractions apostrophe-free** (`dont`, `cest`, `questce`). A contraction file is a **display
overlay**: key = the apostrophe-free surface an engine can produce, value = what the user sees.

The overlay is NOT a dictionary. Nothing in it affects decoding, only presentation — except the
CTC trie injection in §6, which is a separate mechanism that exists to make the keys *reachable*.

---

## 2. The three buckets, and why the split IS the data model

| File | Mode | Meaning | Behaviour |
|---|---|---|---|
| `contractions_<lang>.json` | **REPLACE** | key has no reading of its own (`cest`, `jai`, `gehts`) | display form **takes the slot** |
| `contraction_pairs_<lang>.json` | **PAIRED / APPEND** | key **IS** a word of the language (`lune`, `danse`, `lago`) | word **kept**, elision offered alongside |
| `contraction_collisions_<lang>.json` | **demotion data** | key → other languages whose lexicon holds it | REPLACE → PAIRED when such a language is active |

**Why this is not a runtime rank test.** Before 2026-08-17 the bucket was inferred at runtime from
frequency rank (`ContractionOverlay.REAL_WORD_ORDINAL_MAX` = 1200). Rank works for English *by
luck* — its aliases `dont`/`im`/`cant` genuinely are not words — and destroyed common French and
Italian words that rank past the threshold: `lune` (2,054th) → `l'une`, `danse` → `d'anse`,
`lion` → `l'ion`, `signora` → `s'ignora`, `duomo` → `d'uomo`. The discriminator is **corpus
attestation of the bare form, never rank**, resolved at generation time and shipped as *which
file an entry lives in*.

The rank guard still exists as defence in depth for **imported language packs**, which ship only
an uncurated `contractions.json`.

### Shipped inventory (verified 2026-08-21; fr pairs updated 2026-09-01)

```
contractions_fr.json     17,976   contraction_pairs_fr.json   455   collisions_fr   238
contractions_it.json     21,214   contraction_pairs_it.json   148   collisions_it   103
contractions_de.json         21   (no pairs file)                   collisions_de     7
contractions_en.json        119   contraction_pairings.json 1,744   collisions_en    10
contractions_nl.json        118   (import-only pack language)
es / pt / sv / id / ms / sw / tl = 0 entries — EMPTY ON PURPOSE, see §7
```

**French verb inversions (2026-09-01, commit `bd8984fe`)**: the fr pairs file carries 272
subject-pronoun inversions (`est-elle`, `a-t-on`, `va-t-il` …) on top of the 183 elisions.
They are PAIRED-ONLY BY DESIGN — an inversion key can be a native word (`estelle` @16343,
ASK-attested `aton`), so REPLACE is forbidden for the whole family. Generated from the
person-keyed `FRENCH_INVERSION_VERBS` table in `extract_apostrophe_words.py` (closed pronoun
set, Grevisse `-t-` epenthesis for vowel-final 3sg, lexicon attestation per form) and passed
to `classify_mappings` as forced-append so a regeneration can never reclassify them.
`BundledContractionDataTest` pins the exact samples, the closed-family shape over every
hyphenated PAIRED value (`rendez-vous` noun exempted — it is REPLACE + sidecar), and the
agreement negatives (`fauton`, `pleutelle`, `peuxje`). 271/272 keys are non-words reachable
only via trie injection; `estelle` rides its own lexicon frequency.

---

## 3. English is the special case — model it, never read it raw

English does not use `contractions_en.json` as its source of truth. `loadEnglishBase()`:

1. loads `contractions.bin` (fast binary path) or `contractions_non_paired.json` (121 keys),
2. loads `contraction_pairings.json` (1,744 paired bases),
3. **reclassifies**: removes every pairing base from the non-paired map.

Step 3 is the 2026-07-23 fix. Without it, typing `well` produced `we'll` and the word "well" was
destroyed in its own slot.

**The effective English REPLACE set is `(base ∪ contractions_en) − pairings` = 107 keys** (106 + `etoo`, 2026-09-26).

Anything that models English — the sidecar generator, the runtime scanner, a data test — must
subtract the pairings. Two of the three once disagreed, and that disagreement is exactly how a
live bug was found (§5).

---

## 4. Two load paths. Only ONE merges languages.

| Entry point | Scope | Used by |
|---|---|---|
| `loadTypingMappings(primary, secondary)` | **merges** primary → secondary → English base | tap typing |
| `loadSwipeDisplayMappings(lang)` | **exactly one** language, per adapter instance | swipe (CTC + geometric) |

**Cross-language collisions can only exist on the typing path.** The swipe adapters each own a
`ContractionManager` holding one language, and the decode lexicon is per-language, so there is no
merge to guard. Do not add collision demotion to the swipe loader.

Precedence within `loadTypingMappings` is **first-wins**, and `loadContractionsFromStream` skips a
key that is already in `nonPairedContractions` **or** in `pairedContractions`. Both halves matter —
see §5.

An installed **language pack's** contraction file wins **outright** over the bundled file for that
language (`loadLanguageContractions`); the bundled one is skipped entirely, not merged.

---

## 5. The four guards, in the order a word meets them

1. **Generation time** — `scripts/extract_apostrophe_words.py` classifies REPLACE vs PAIRED from
   hunspell + ASK wordlist + wordfreq agreement. Curated entries live in `CURATED_CONTRACTIONS`.
2. **Load time, cross-language** — `ContractionCollisionDemotion.demote()` moves a REPLACE key
   that is a real word of another **active** language into the PAIRED bucket.
3. **Selection time, imported packs** — `ContractionCollisionScanner.scan()` (§8).
4. **Per lookup, user words** — `SuggestionHandler.replaceModeContractionFor()` refuses to REPLACE
   a word in the personal dictionary. **Case-TOTAL since 2026-08-29**: it asks
   `DictionaryManager.isUserWordIgnoringCase`, a `Locale.ROOT` fold derived from the word set and
   invalidated on every mutation of it. The fold is READ-SIDE only — `userWords` still stores,
   dedups and removes case-sensitively, so `Foo` and `foo` remain two user-owned entries.

### The measured casualties each guard prevents

| User's languages | Typed | Was produced | Source of the mapping |
|---|---|---|---|
| fr + en | French `dont` (relative pronoun) | `don't` | English REPLACE key |
| de + en | German `im` (in dem) | `I'm` | English REPLACE key |
| de + en | English `hats` | `hat's` | German curated clitic table |
| en only | English `well`, `shell`, `hell`, `were`, `girls`, `states` (+8) | `we'll`, `she'll`, … | the re-add bug below |

`im` was destroyed in **every** non-English bundled language.

### The re-add bug — the trap most likely to recur

`loadEnglishBase` reclassifies 14 pairing bases OUT of the non-paired map. `loadTypingMappings`
then calls `loadLanguageContractions("en")`, and `contractions_en.json` **repeats all 14**. When
`loadContractionsFromStream` tested only "already non-paired?", it found them absent (they had
just been removed) and put them straight back as REPLACE — silently undoing the 2026-07-23 fix on
the typing path.

**The paired map is authoritative.** A later REPLACE-mode file may never override a key that is
already a paired base, whichever language supplied it.

---

## 6. CTC trie injection — separate mechanism, easily confused with the overlay

`CtcContractionKeys.inject()` adds alias keys to the CTC lexicon trie so the beam can *decode*
them at all. Without it the overlay has nothing to rewrite: `dabaissement` is not a French
dictionary word, so the beam would never return it.

The scoring formula is `ctc/len^0.9 + β·len + λ·ln(freq)` — emission evidence is **divided** by
`len^0.9`, the frequency bonus is **not**. That asymmetry means injecting at the bottom of the
scale (1.0) left ~49% of the French alias table unreachable: the gap to fr's rarest real word
(freq 69) demanded ~75 nats of emission evidence against the 7–10 the model produces.

`CtcContractionKeys.derivedFloor(freqs)` = `max(1.0, minRealFrequency − 1.0)`, per lexicon. The
invariant — every real word strictly outranks every pseudo-word on frequency — holds **by
construction**, but the margin becomes ~0.03–2.5 nats instead of ~8.5, which emission evidence can
actually decide.

German stays mostly inert: de's rarest real word is freq 12, so its floor is 11. Scale-specific,
not a bug.

---

## 6b. The TAP path has its own injection floor — and the data being present proves nothing

`ContractionManager` decides *what a key maps to*. `ContractionInjectionPolicy` decides *whether
the tap path offers it at all*, and the two disagree on purpose:

- **`length >= 3`** for PAIRED bases. `contraction_pairings.json` is 1,178 possessives out of
  1,744 bases, and a possessive's apostrophe-free key is often one or two letters (`t → t's`,
  `as → a's`, `cd → cd's`). Injected at `top score + 500` those outrank `the`.
- The floor also blocks the two-letter PRONOUN bases (`it`, `we`, `he`, `do`) — not accidental
  fallout. Those literals are far more likely than their contractions, and three or four injected
  variants would bury them. Unblocking them is a ranking decision that needs a measurement.
- **One exception**: a first-person contraction at two characters. The I-contractions are a closed
  set (`i'm`, `i'll`, `i've`, `i'd`); three have three-letter bases and always injected, `id` is
  the only two-letter one and was silently absent from the bar for the whole life of the floor
  (measured 2026-08-28). The predicate excludes `i's` specifically, or typing `is` would surface
  the plural of the letter I.

**The trap this closes**: `id → i'd` was in the shipped data the entire time. A grep of the data
files "proves" a mapping exists while the user never sees it, because reachability is decided two
layers away — in the CTC trie (§6) for swipe, and here for tap. When a mapping is reported
missing, check the injection layer BEFORE touching data; a data change that was never needed
costs a regeneration plus a collision-sidecar rebuild (§10) for nothing.

**Second trap, same area**: `loadPairedContractions` (English) and `loadLanguagePairedContractions`
(per-language) merge into the SAME map, and only the second had a membership check. English loads
`contraction_pairings.json` on top of the pairs `loadBinaryContractions` already derived from
`contractions.bin`, and the two overlap on 599 of 2,258 bases — so `getPairedContractions("ill")`
returned `["i'll", "i'll"]` and the bar showed `I'll` at ranks 0 AND 1. Both loaders are now
earlier-wins with a membership check. The swipe path never showed it: `ContractionOverlay.apply`
dedups on emit.

## 6c. Swipe placement of PAIRED variants (2026-09-26) — where a variant lands on the slate

`ContractionOverlay` decides WHERE a paired variant goes; getting it wrong hides a correct
mapping exactly like §6b, because slots 5+ are off-screen and auto-insert commits rank 0.

History: `b2d7b908` (2026-07-22) spliced **every** variant after its base, and "would"'s two
variants pushed "world" from #2 to #4. It then moved **all** variants to the slate tail — which
made swiping `she'd` auto-insert `shed`, with `she'd` at slot 5–9, for the whole paired pronoun
set (`shed/id/ill/wed/shell/well/hell`). Reported 2026-09-26 (learning-system audit RC1 + RC3).

The rule now:

1. **Projection variant** = its apostrophe-free form IS the decoded surface (`shed`→`she'd`).
   At most **one** per base is **spliced** beside it: the NON-possessive projection with the
   highest known pairing frequency, at whatever rank the base sits.
2. **Possessive projection** (`teams`→`team's`, 593 shipped bases) — spliced beside its base
   **only when the base is the decoder's confident pick** (2026-09-26, maintainer: "bump team's
   when the decoder is confident in teams"): input **rank 0** AND runner-up score **< top / 2**
   (`ContractionOverlay.isConfidentTop`, `POSSESSIVE_SPLICE_RUNNER_UP_DIVISOR = 2`). Why the
   margin, not rank 0 alone: both engines score a within-slate softmax × 1000, so the ratio IS
   confidence; with the runner-up within 2x (`teams` 900 / `trams` 600) the trace is unsettled
   and the possessive would push a live competitor down — the would/world displacement. Half is
   the pipeline's existing "contestable rank 0" line (`SwipeContextRescorer.R_MIN = 0.5`;
   `CtcFuzzyRescue` caps rescued words strictly below it), kept as a separate constant so a
   rescorer retune cannot move placement. Measured CTC runner-up/top median 0.254 (CK-150-025),
   so splicing is the common case. The possessive's frequency may be **unknown** (the 510
   bin-derived ones, `alzheimers`→`alzheimer's`) — it only picks among the base's own
   possessives (known beats unknown, then higher, then list order). The swipe shape of `team's`
   IS `teams`, so confidence says nothing about the reading: **never ahead** (rule 4).
   Engine-agnostic (no base frequency involved), so CTC and geometric behave identically.
3. Everything else stays at the **tail**: non-projection variants (`would`→`wouldn't`,
   `she`→`she'd` — a different trace), a non-possessive variant with **no known frequency**,
   and the possessive of a **lower-ranked or contested** base. That is why would/world cannot
   regress, and why **fr/it are unchanged** — their pairs files carry no frequency and no
   possessive-shaped value, so no elision (`l'une`) can climb over a real word (`lune`, §2).
   **D1 interplay**: `SuggestionHandler`'s possessive augment (`possessiveAdditions`: top-3
   window, English-gated by `shouldAugmentPossessives`) runs on the overlaid slate and only
   APPENDS forms absent from it (case-insensitive) — `[teams, team's, team]` gains `teams'`
   but never a second `team's`, and the spliced copy cannot be moved back to the tail.
4. Spliced variant goes **ahead** of its base only when its pairing frequency beats the base's
   lexicon frequency by at least `ContractionOverlay.PROMOTION_MARGIN` = **6 bytes** (≈0.3 zipf,
   ≈2x): `i'd` 211 vs `id` 196, `i'll` 212 vs `ill` 198, `we'd` 193 vs `wed` 178, `he's`/`she's`
   over `hes`/`shes`, `c'mon` over `cmon`. Base stays first for `well` (223 vs 202), `hell`,
   `were`, `shell` (192 vs 188), `whore` (182 vs 163), `shed` (189 vs 188 — near tie) and
   **`its` (225 vs `it's` 229)**: a real lead of 1.55x, but inside the margin — the classic
   grammatical confusable only syntax resolves, so the traced literal keeps the auto-insert and
   `it's` sits at slot 1. Smallest real promotion gap is 13 (`c'mon`), so the margin decides
   exactly one pair today; `ContractionOverlayTest` pins its boundary.
   - **Possessives never go ahead** (`isPossessive`; pronoun `'s` clitics `she's`/`he's` are
     not possessives). Measured: 24 of the 69 raw-frequency promotions disagree with wordfreq,
     e.g. `teams`→`team's`, `ones`→`one's`, `sons`→`son's` — spliced after (confident base, rule 2) or tailed.
   - **Both English engines compare, on one scale** (geometric parity 2026-09-26).
     `ContractionManager.getPairedVariantFrequency` is on the `en_enhanced.json` 0..255 byte
     scale (pairings 128..255, lexicon 134..255), so a base frequency is only comparable when read
     FROM `en_enhanced.json` and merged with user words by `CtcLexiconMerge.merge` (custom-word
     calibration). `PairingBaseFrequencies` is the one derivation: CTC calls `select(merged,
     bases)` on its own lexicon; the geometric adapter, which decodes against the CKDT, reads the
     SAME asset (`CtcLanguageSupport.assetFor("en")`) and calls `fromEnLexiconJson(json,
     userWords, disabled, bases)` once per dictionary-memo version (bundled `en` only, not an en
     language pack). The CKDT rank could NOT serve: it is a monotone but data-dependent step
     function of the JSON byte (98,140 words in both; 219 ranks over 115 bytes; `id` 196 ↔ rank
     118, `its` 225 ↔ rank 60) — inverting it means shipping a second copy of the JSON's data.
     Measured: the geometric derivation equals CTC's for every base (with and without user words)
     and the overlay places every shipped projection pair identically — so geometric now shows
     `i'd`/`i'll`/`we'd`/`he's`/`she's` at rank 0 like CTC. CKDT sources (fr/it/…) and packs
     still pass none: splice, never promote. Any read failure degrades to that too.
5. Scores stay non-increasing: a spliced pair shares the base's score; tail variants are clamped
   to the last emitted score.

**The frequency is BASE-scoped in storage** (`base → variant → freq`) — that is the runtime
contract — but since 2026-09-26 every non-possessive variant carries **one value file-wide**,
because a variant's corpus frequency does not depend on which trace reached it. Only the copy
under a *projection* base is read; the `she → she'd` copy is a completion of a different trace.

**Where the numbers come from (2026-09-26).** `contraction_pairings.json` was imported from
Unexpected-Keyboard in `f7f77d85` with no generator here: upstream (`migration2/
process_contractions.py`) copied each apostrophe word's frequency from the ORIGINAL UK English
dictionary, and nine pronoun bases (`well wed id hell ill shed shell whore` + `it`) were appended
by hand with a flat `200`. Neither was on today's lexicon scale (`we → we'll` was **252**, a zipf
~7.5 word; the flat 200 promoted `she'll` over `shell` and `who're` over `whore`). Now:

```sh
python3 scripts/extract_apostrophe_words.py --en-pairing-frequencies           # rewrite
python3 scripts/extract_apostrophe_words.py --en-pairing-frequencies --check   # exit 1 on drift
```

fits an **isotonic zipf→byte map on `en_enhanced.json` itself** (98,069 words with a wordfreq
zipf; Spearman 0.9997, **residual 0 on every word** — the lexicon IS a monotone function of
wordfreq 3.1 zipf, ~19 bytes per zipf unit over 4–6.5) and writes each non-possessive variant's
own zipf (apostrophe form, `en`) through it — 80 variants / 93 entries. **Possessives keep the
upstream values** (never promoted, so the value only orders several projections of one base;
10 of their 42 above-margin leads disagree with wordfreq — `ones/one's`, `kings/king's` — which
is why the possessive rule stays). `EXTRA_EN_PAIRINGS` adds `its → it's`, which previously lived
only in `contractions.bin` (no frequency → tail, off-screen). `contractions.bin` does not carry
frequencies and regenerates byte-identical; collision sidecars are unaffected.

**The bin-only gap, swept (2026-09-26).** `loadBinaryContractions` DERIVES a paired base for every
paired display form in the binary (`base = form minus apostrophes`), so a pair can exist at
runtime with no frequency whenever the pairing file lists the variant only under a *different*
base. Sweep of every derived pair: 513 lack a frequency — 510 possessives (out of scope: never
promoted; since 2026-09-26 spliced beside a CONFIDENT rank-0 base without needing a frequency, §6c
rule 2) and three non-possessive
projections, now pinned by `BundledContractionDataTest`:

| pair | listed only under | measured (variant vs base byte) | outcome |
|---|---|---|---|
| `whys → why's` | `why` | 166 vs 157 (zipf 3.01 vs 2.52, lead 9) | **added**; `why's` rank 0 over `whys` |
| `natl → nat'l` | `nat` | 159 vs 158 (near tie) | **added**; `natl` keeps rank 0, `nat'l` at #1 |
| `etoo → eto'o` | `eto` | base not in the lexicon | **moved to REPLACE** — see below |

`etoo` is not an `en_enhanced.json` word, only an injected pseudo-word, so it has no base frequency
and a frequency could only splice `eto'o` BEHIND the raw `etoo` (which would auto-insert). It is now
a REPLACE mapping (2026-09-26): hand-curated `"etoo": "eto'o"` in `contractions_non_paired.json` (no
script generates that file), `contractions.bin` rebuilt with

```sh
D=src/main/assets/dictionaries
python3 scripts/generate_binary_contractions.py $D/contractions_non_paired.json \
    $D/contraction_pairings.json $D/contractions.bin
```

(byte-identical on unchanged inputs — verify with `cmp` before trusting a diff), and the sidecars
re-run (`build_contraction_collisions.py`: unchanged, `etoo` is in no other lexicon; en now counts
107 REPLACE keys). Since `eto'o` is a non-paired VALUE, `loadBinaryContractions` no longer derives an
`etoo` paired base at all; `eto → eto'o` stays a non-projection completion. Swiping `etoo` now shows
`eto'o` in the slot. The pairing file is 1,790 entries.

### 6d. User preference for a joiner word (2026-09-29) — rule 0 of the overlay

Everything in §6c decides from SHIPPED data for everyone. A personal-dictionary word spelled
with an apostrophe or hyphen (`she'd`, `l'une`, `co-op`) is a per-user claim, and it is read as
a **display preference, not a frequency lift** (`swipe/UserJoinerPreference.kt`):

- No engine can spell a joiner, so the entry only ever decodes as its joiner-free SURFACE
  (`shed`, `lune`, `coop`). `ContractionOverlay.apply(userPreferredForm = …)` runs **before
  every other rule**: the user's form takes the surface's rank and score (rank 0 → it is the
  auto-insert), the surface stays right behind it when it is a real word, and is dropped when it
  exists only through the user word (`xray` for `x-ray`, `replacesSurface`). The surface's other
  mapped forms then follow the normal rules (deduped), or go to the tail when it was replaced.
- **Why not the frequency.** Before this, EN CTC filed `she'd` under `shed` in the trie
  (max-per-surface — lifting `shed`) while placement compared `she'd`'s PAIRING frequency with
  `shed`'s LEXICON frequency, neither touched by the user word: `shed` stayed the auto-insert, so
  the swipe offer could not include joiner words (commit `8a62400c`). The trie lift is KEPT —
  it makes the trace the user keeps swiping decode to that surface more readily.
- **No preference when the surface is itself a letters-only user word** (both `shed` and
  `she'd` claimed): the traced literal keeps its slot, preserving the older reverse contract
  (`ContractionPromotionUserWordTest`: a user `id` beats a promoted `i'd`). The swipe offer is
  suppressed for a joiner word in that state (`SwipeCorrectionPolicy.joinerSurface`). Two joiner
  words on one surface: higher user frequency, then merge order.
- **fr/it cannot regress for anyone without such a word** — the map is empty, rule 0 never
  fires. WITH a user `l'une`, `lune` is kept at #1, never destroyed. The CKDT branch used to be
  worse than that: `CtcAzProjection.projectLexicon` gave the surface's accent-display slot to the
  highest-frequency form, i.e. the user word at 255, so every decoded `lune` was REPLACED. Joiner
  user words are now projected separately (`projectWithoutJoinerDisplay`): they raise the
  surface frequency (max) but own no display entry.
- **Surface key per engine** (it must equal what the overlay sees): CTC en = a–z strip
  (`stripToAlphabet`, same as `loadStrippingNonAlphabet`); CTC CKDT = the projection resolved
  through the accent-display map; geometric = `joinerFree` (lowercase, accents kept — its words
  are canonical). "Is a real word" = the engine's own ordinals.
- **Geometric residue.** Its templates skip joiner forms and nothing adds the stripped surface,
  so a surface that exists ONLY through the user word (`xray`) is undecodable there; the
  preference serves real-word surfaces (`shed`, `lune`, `coop`) identically to CTC.
- **Hyphens** needed no dictionary data (the lexicons hold 0 hyphenated EN entries): the trie
  already files `co-op` under `coop`; rule 0 supplies the display. So hyphen words are offered too.
- Lifetime = the dictionary entry. Undo of "Added …" / Dictionary Manager removal changes
  `custom_words_<lang>`, the lexicon memo key, so the next build has no preference.
- **Signal breadth, accepted**: platform `UserDictionary.Words` rows are user words too
  (ARC-081), so a `we'll` row synced from another keyboard now shows `we'll` ahead of `well` for
  that user. An explicit user-dictionary entry is treated as the user's act everywhere else.

## 7. Empty files are CORRECT, not unfinished

`es`, `pt`, `sv` ship zero contractions, and the tests assert the positive linguistic evidence:

- **Spanish**: `al` (a+el) and `del` (de+el) are the only contractions, both written **solid** —
  RAE never inserts an apostrophe.
- **Portuguese**: genuine apostrophe forms are a handful of frozen "de + vowel" expressions; the
  swipeable spellings are apostrophe-free (`Douro`, `Dalva`).
- **Swedish**: the genitive takes a bare `-s`, never `'s`.

Do not "fix" these by generating entries.

---

## 8. Selection-time scan (imported packs)

A pack **cannot** have a shipped sidecar — its contraction file and dictionary arrive long after
the build. Three placements were considered; only one works:

- **At import** — wrong moment. A pack collides with whatever is active *now*, and people import
  packs they do not immediately enable, so the answer goes stale at the next language change.
- **At every keystroke** — wrong cost, and impossible: `DictionaryManager` holds one predictor for
  the current language, so the other language's lexicon is not resident while typing.
- **At language selection** — correct. It is the event that decides which languages are active, it
  happens in Settings where reading a lexicon is affordable, and it is the one moment the user is
  present to be warned.

**All four selectors must rescan** — primary, secondary, and both quick-toggle alternates. The
alternates matter as much: a toggle key swaps the active language at runtime with no trip through
Settings, so a combination only reached by toggling would otherwise never be scanned.
`CoreImeHygieneDriftTest` pins this.

**The cache is scoped to the language set it was computed for.** If languages change by a route
that does not rescan (restored backup, imported settings), the cached table describes a different
combination and is **ignored**. Worst case is then the old missing protection — never a new wrong
demotion suppressing correct contractions.

The warning dialog fires **only** for imported-pack collisions. Bundled ones are already handled
by the sidecars, and a dialog that usually says nothing actionable is one people dismiss unread.

---

## 9. Landmine lists — two of them, and they are NOT interchangeable

`BundledContractionDataTest` keeps two, and merging them would be a mistake:

- **Unconditionally wrong** (`minuit`←`mi-nuit`, `parla`←`par-là`, `nonne`←`non-né`, `weekend`,
  `email`, `entretemps`, `haha`, `dodo`, `tata`, `amies`, `estelle`, `aton`) — wrong for *every*
  user. These must never be REPLACE keys. A bulk hyphen extraction yields 16,687 keys of which
  **73** are native French words with no rank protection, which is why the extraction is curated.
- **Conditionally wrong** (`rendezvous`) — correct French, wrong only alongside English. Belongs in
  the **collision sidecar**, not a landmine list.

---

## 10. Regeneration

```sh
# REPLACE/PAIRED files. Needs the ASK checkout, wordfreq, hunspell dicts.
python3 scripts/extract_apostrophe_words.py --lang fr

# Collision sidecars. MUST be re-run after ANY change to a contraction file or a lexicon.
python3 scripts/build_contraction_collisions.py
python3 scripts/build_contraction_collisions.py --check   # verify, exit 1 on drift
```

`ContractionCollisionDataTest` **recomputes** every sidecar from the shipped lexicons and asserts
equality, so forgetting the second command fails the suite rather than silently narrowing the
guard.

---

## 11. Invariants and the test that pins each

| Invariant | Pinned by |
|---|---|
| every shipped key is reachable (lexicon **or** trie injection) | `BundledContractionDataTest` |
| the two files are disjoint; REPLACE holds no common word | `BundledContractionDataTest` |
| value differs from key by apostrophes, hyphens and **accents** only — never a letter | `BundledContractionDataTest` |
| curated table pinned to exact values + landmines absent | `BundledContractionDataTest` |
| verb inversions PAIRED-only, closed pronoun family, agreement negatives | `BundledContractionDataTest` |
| entry-count ratchets (fr 17,976 / 18,431) | `BundledContractionDataTest` |
| sidecars equal a full recomputation from the lexicons | `ContractionCollisionDataTest` |
| demotion rule: intersect collisions against ACTIVE languages | `ContractionCollisionDemotionTest` |
| demotion is actually wired into `loadTypingMappings` | `ContractionManagerTest` (instrumented) |
| paired bases stay out of the REPLACE map after the full load | `ContractionManagerTest` (instrumented) |
| a monolingual user is unaffected | `ContractionManagerTest` (instrumented) |
| cache scope rejects a different language set | `ContractionCollisionScannerTest` (instrumented) |
| every language selector rescans | `CoreImeHygieneDriftTest` |
| no REPLACE lookup bypasses the user-word guard, and the guard reads the FOLDED accessor | `CoreImeHygieneDriftTest` |
| the guard is case-total; the stored user-word set is still case-sensitive | `ContractionUserWordGuardTest` (mock) |
| tap-path paired injection: floor + the one first-person exception, and the merged variant list holds no repeat | `ContractionInjectionPolicyTest` (pure) + `ContractionFlickerTest` (instrumented) |
| `i'd` reaches the bar for typed `id`, `id` survives beside it, no duplicate surface for `ill` | `ContractionSentenceStartMeasureTest` (instrumented) |
| injected key surfaces but never outranks a real word | `CtcContractionRankingTest` |
| paired placement: splice ≤1 projection variant, ahead only on higher known freq, possessives never ahead, tail otherwise, monotone scores | `ContractionOverlayTest` (pure) |
| possessive splice only beside a CONFIDENT rank-0 base (runner-up < top/2, boundary pinned), one per base, unknown frequency allowed, lower-ranked/contested → tail, would/world holds | `ContractionOverlayTest` (pure) |
| shipped possessives: `teams`→[teams, team's, …] when confident; contested/lower-ranked → tail; all 593 possessive-projection bases keep rank 0 with the possessive at slot 1 | `CtcContractionDisplayTest` (pure) |
| D1 augment (`SuggestionHandler.possessiveAdditions`) adds no second copy of a spliced possessive (case-insensitive) and only appends | `ContractionOverlayTest` (pure) |
| shipped pronoun set over MEASURED data: I'd/I'll/we'd/he's/she's rank 0; shed/shell/whore/well/hell/were/its/natl keep rank 0 with the variant at #1; why's rank 0 over whys; its/it's inside PROMOTION_MARGIN; would/world keeps world at #2; REPLACE six keep their slot | `CtcContractionDisplayTest` (pure) |
| promotion needs lead ≥ PROMOTION_MARGIN (boundary), possessive never ahead even above the margin | `ContractionOverlayTest` (pure) |
| geometric ↔ CTC promotion parity: identical base frequencies for every base (user words incl.), identical placement for every shipped pair, both adapters wired through `PairingBaseFrequencies` (source pin) | `PairingBaseFrequenciesTest` (pure) |
| pairing `frequency` survives parsing, base-scoped; the 19 projection values pinned; one value per non-possessive variant; no flat 200 on a promotable pair | `BundledContractionDataTest` (pure) |
| every non-possessive pair DERIVED from `contractions.bin` has a pairing frequency (none excepted since `etoo` went REPLACE) | `BundledContractionDataTest` (pure) |
| `etoo → eto'o` is REPLACE in the JSON and the binary, not a pairing base, and `etoo` is no lexicon word | `BundledContractionDataTest` (pure) |
| rule 0 (user joiner word): preferred form at the surface's rank + score, real surface kept behind, non-word surface replaced (mapped forms → tail), junk alias yields one entry, empty map = byte-identical output | `ContractionOverlayTest` (pure) |
| prefer `she'd` → next `shed` swipe has `she'd` at rank 0; any rank; user `id` still beats promoted `i'd`; both claimed → base keeps slot; undo restores; `co-op` ahead of `coop`; `x-ray` replaces `xray`; fr `lune` unchanged without a user word and kept at #1 behind a user `l'une`; CKDT display slot never taken by a joiner user word; adapters wired (source pin) | `SwipePreferJoinerWordTest` (pure) |
| shipped pronoun near-ties (`shed`/`shell`/`well`/`hell`): preferred variant rank 0, base #1; unchanged without the user word | `CtcContractionDisplayTest` (pure) |
| joiner words pass the offer's plausibility rule on their letters; contraction table counts as a real word; surface-claimed joiner words are not offered; accept/undo add and remove exactly the joiner word | `SwipeCorrectionPolicyTest` (pure) + `SwipeCorrectionOfferTest` (mock) |
| language isolation (no code-switched output) | `SwipeContractionLanguageIsolationTest` |

---

## 12. Hard-won rules

1. **The accent fold traded strictness for Phase B.** The projection invariant now folds accents so
   `peutetre` → `peut-être` can ship. That is exactly what used to refuse `nonne` → `non-né`, so
   it is **paid for** by an exact-value pin plus an explicit absent-landmines pin. All three move
   together; deleting one thinking another covers it reopens the hole.
2. **Score by correctness, never by shape.** The first version of `scripts/ctc_injection_ab.py`
   flagged `laurait` as a regression because the top-1 changed shape — the user had swiped the
   elision `l'aurait` and the new floor had *fixed* it.
3. **`es`/`pt`/`sv` emptiness is a linguistic claim with evidence.** Read §7 before generating.
4. **Two managers, two lifetimes.** Swipe adapters own their own `ContractionManager` instance;
   the typing path uses the service's. A change to one does not affect the other.
5. **Verify a source pin by breaking it.** Both `CoreImeHygieneDriftTest` contraction pins were
   validated by injecting a violation and confirming the failure message, then reverting. A source
   guard that cannot fail reads as coverage while providing none.

---

## 13. Known dead data — RESOLVED

`contraction_pairings_cleaned.json` (32 entries, 5,177 bytes) had **zero** code references
(verified 2026-08-21 across `src/`, `scripts/`, `tools/`, including the one dynamic route —
`detectAvailableV2Dictionaries` enumerates `assets/dictionaries/` but filters on
`endsWith("_enhanced.bin")`). **DELETED** in `030265ee`. Re-confirmed absent 2026-09-01, and
both contraction gates green after the deletion: `swipe.BundledContractionDataTest` 18/18,
`swipe.ContractionCollisionDataTest` 6/6.

No known dead data remains in `assets/dictionaries/`.
