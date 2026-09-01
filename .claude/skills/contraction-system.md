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

### Shipped inventory (verified 2026-08-21)

```
contractions_fr.json     17,976   contraction_pairs_fr.json   183   collisions_fr   238
contractions_it.json     21,214   contraction_pairs_it.json   148   collisions_it   103
contractions_de.json         21   (no pairs file)                   collisions_de     7
contractions_en.json        119   contraction_pairings.json 1,744   collisions_en    10
contractions_nl.json        118   (import-only pack language)
es / pt / sv / id / ms / sw / tl = 0 entries — EMPTY ON PURPOSE, see §7
```

---

## 3. English is the special case — model it, never read it raw

English does not use `contractions_en.json` as its source of truth. `loadEnglishBase()`:

1. loads `contractions.bin` (fast binary path) or `contractions_non_paired.json` (120 keys),
2. loads `contraction_pairings.json` (1,744 paired bases),
3. **reclassifies**: removes every pairing base from the non-paired map.

Step 3 is the 2026-07-23 fix. Without it, typing `well` produced `we'll` and the word "well" was
destroyed in its own slot.

**The effective English REPLACE set is `(base ∪ contractions_en) − pairings` = 106 keys.**

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
| entry-count ratchets (fr 17,976 / 18,159) | `BundledContractionDataTest` |
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
