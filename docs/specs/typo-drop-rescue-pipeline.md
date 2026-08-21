# Feature Specification: Typo-Drop Rescue Pipeline

## Feature Overview
**Feature Name**: Typo-Drop Rescue Pipeline (multi-oracle triage of dropped dictionary candidates)
**Priority**: P1
**Status**: SUPERSEDED (2026-07-02) — by `scripts/build_wordlist.py` (né
`build_en_wordlist.py`, renamed 2026-07-20 when the classifier went
multi-language — see the "As-Built (2026-07-20): multi-language
generalization" section at the end of this spec), which folds the
rescue oracles into the dictionary-expansion filter as a ONE-PASS evidence classifier
(this spec's own "Future Enhancements" §1). Implemented lanes: spellchecker triple-case
oracle (lower/Cap/UPPER — the UPPER−Cap−lower difference isolates true initialisms),
AOSP LatinIME wordlist (promoted from corroborating signal to first-class keep-oracle),
curated allowlist/blocklist data files (`scripts/dictionaries/en/en_{allow,block}list.txt`),
elongation exemption, foreign-language *dominance* filter, and this spec's regression
seeds as build-time hard gates. NOT implemented (deliberately): the gazetteer / Wikidata /
Wiktionary lanes — obscure proper nouns are undesirable autocorrect targets for the
bundled dictionary, and the strong-name class is already covered by hunspell-Cap + NLTK
names + AOSP. The annotated REVIEW artifacts replace the manual-curation lane.
**Target Version**: (dictionary build tooling — no app version gate)

### Summary
A reproducible offline-first triage classifier that re-examines the words the
English dictionary-expansion filter dropped as "typos" and rescues the valid
**proper nouns, abbreviations, and slang** among them, without re-admitting the
genuine corpus noise. It re-partitions each dropped word into **KEEP** (auto-add
back), **DROP** (confirmed junk), or **REVIEW** (annotated for human curation).

### Motivation
The 2026-06-25 dict-expansion session (see `english-dictionary-pipeline.md` and
`memory/todo.md`) raises the bundled English dictionary from ~52k toward ~100k
wordfreq candidates. Its typo filter — *"drop low-freq tokens that are edit-distance-1
of a high-freq real word and not in reference"* — produced
`cleverkeys-typo-drops.txt` (6,483 words). That list is dominated by genuine junk
but threads in valid words we do **not** want to lose. Three root causes were
confirmed by measurement:

1. **Frequency cannot separate the classes.** Measured wordfreq zipf: junk `bbg`=2.12
   and `aae`=1.90 *outrank* valid `travelin`=2.11, `jokin`=2.00, `yeahh`=1.96,
   `hkt`=1.94. Any frequency floor that excludes the noise also kills the valid words.
2. **The edit-distance rule mistakes *intentional* edits for typos.** Slang is defined
   by a systematic edit of a real word (`jokin`=joking−g, `yeahh`=yeah+h). The "ed1 of
   a real word" test flags exactly these.
3. **No positive oracle for the three valid classes.** All 6,483 drops are absent from
   en_US/en_GB/en_CA spell dictionaries *by construction* (verified: 0 of 6,483 pass any
   spellchecker), because dictionary-membership was the keep gate. Proper nouns,
   abbreviations, and slang live *outside* spell dictionaries and need different oracles.

### The hard constraint (sets expectations)
Four independent research lanes converged on one wall: **short 2–4 letter initialisms
are irreducibly ambiguous.** Every large corpus contains them as legitimate entries —
Wiktionary lists `aad`/`bbg`/`bcd` as real initialisms (Azure-AD / baby-girl /
binary-coded-decimal); Wikipedia has `Aae` and `Bbg` as live article titles; gazetteers
contain `aas` as a place. `qwxz` was the *only* token rejected by every source. There is
**no oracle that yields high recall and zero junk for the short-token class.** This is
why a REVIEW bucket is mandatory: the pipeline auto-decides only where precision is high
and routes the genuinely ambiguous tail to human curation with evidence attached.

## Requirements

### Functional Requirements
1. **FR-1**: Re-partition every word in `cleverkeys-typo-drops.txt` into exactly one of
   KEEP / DROP / REVIEW, with a category label (`slang` / `abbrev` / `proper` / `junk`)
   and the evidence that drove the decision.
2. **FR-2**: Rescue **slang** via (a) static slang→standard mapping lists and (b) a
   morphological rule set (dropped-g, z-plural, strict run-collapse) whose reconstructed
   base is verified against an on-device speller.
3. **FR-3**: Rescue **abbreviations** via a *narrow* curated allowlist only; route the
   broad initialism tail to REVIEW with gloss + frequency attached (no auto-keep).
4. **FR-4**: Rescue **proper nouns** via a bundled name+place gazetteer with a structural
   guard; auto-KEEP only strong-evidence matches; route single-source matches to REVIEW.
   Cover brand/fiction/foreign entities via a filtered Wikidata subset.
5. **FR-5**: Auto-DROP only what no oracle rescued *and* a junk gate (phonotactic +
   structural) confirms as noise.
6. **FR-6**: Emit the REVIEW list in the existing `additions-review.txt` annotated format
   (`word \t # CATEGORY zipf=N source=…  gloss=…`), so a human can delete-to-exclude.
7. **FR-7**: Be **re-runnable** on every future dictionary expansion, not a one-shot
   script. Online steps (Wiktionary, Wikidata) cache to static assets so reruns are
   offline and deterministic.

### Non-Functional Requirements
1. **NFR-1 Reproducibility**: Same inputs → same outputs. All network-derived data is
   snapshotted into `scripts/data/` and version-controlled; the classifier reads only
   local assets at run time.
2. **NFR-2 Footprint**: No multi-GB downloads. Resource bundle is a few hundred KB–MB
   gzipped. No NER model dependency (confirmed not installable on Termux/bionic and the
   wrong tool for lowercased context-free tokens).
3. **NFR-3 Precision over recall on auto-KEEP**: The bundled dictionary must stay clean;
   ambiguity resolves to REVIEW, never to a silent KEEP. Target **0 junk** in auto-KEEP
   on the regression seeds.
4. **NFR-4 Auditability**: Every KEEP/DROP/REVIEW line records which oracle(s) fired.

### User Stories
- **As the dictionary maintainer**, I want valid proper nouns/abbreviations/slang the
  typo filter dropped to be rescued automatically where it's safe, **so that** the
  bundled dictionary doesn't lose real words my users type.
- **As the dictionary maintainer**, I want the genuinely-ambiguous remainder presented as
  a short, pre-categorized, gloss-annotated review list, **so that** I can curate the
  tail quickly instead of eyeballing 6,483 raw tokens.

## Technical Design

### Architecture
```
                 cleverkeys-typo-drops.txt (6,483)
                              │
                  ┌───────────▼────────────┐
                  │  triage_drops.py        │  reads only scripts/data/* + wordfreq
                  │  (ordered cascade)      │
                  └───────────┬────────────┘
   ┌──────────────┬───────────┼───────────┬───────────────┐
   ▼              ▼           ▼            ▼               ▼
 SLANG         ABBREV      PROPER       JUNK GATE        (none fired)
 rescue        rescue      rescue       phonotactic+      → REVIEW
 (static+      (narrow     (gazetteer   structural
  morphology)   curated)    +Wikidata)  → DROP
   │              │           │            │               │
   └─────KEEP─────┴────KEEP───┴───KEEP─────┘               │
                                                            │
   weak/single-source/broad-initialism/mid-phonotactic ────┘→ REVIEW (annotated)
                              │
              ┌───────────────┼────────────────┐
              ▼               ▼                 ▼
  typo-rescue-keep.txt  typo-confirmed-    typo-review.txt
   (+category)          drops.txt          (annotated, delete-to-exclude)
              │
              ▼
   merged into build_dictionary.py candidate set
```

### Component Breakdown

> **⚠️ None of the three scripts below was ever written** (verified 2026-08-21: zero git
> history for `build_rescue_resources.py`, `triage_drops.py`, `enrich_wiktionary.py`).
> They are design artifacts of this SUPERSEDED spec — do not go looking for them in
> `scripts/`. The rescue oracles that survived were folded directly into
> `scripts/build_wordlist.py` (see Status at the top).

1. **Resource bundle builder** (`scripts/build_rescue_resources.py`, run once / on refresh):
   fetches and snapshots the offline oracle assets into `scripts/data/` with a
   `PROVENANCE.md` recording source URL, license, fetch date, and row count per asset.
2. **Triage classifier** (`scripts/triage_drops.py`): the ordered cascade. Pure-Python,
   reads only local assets + `wordfreq` + `hunspell` CLI. Deterministic.
3. **Wiktionary enrichment** (`scripts/enrich_wiktionary.py`): one-time online pass that
   batches candidate words through the Wiktionary action-API (50 titles/req × 3 case
   forms), parsing `==English==` L2 + POS L3 + the first gloss, caching verdicts to
   `scripts/data/wiktionary_cache.json`. Provides category labels + review annotations.
4. **Phonotactic model** (built at runtime from the bundled `en_words.txt`): char-bigram
   log-probability scorer; the junk gate's continuous signal.

### The cascade (exact decision logic)

Run **in this order**; first rule that fires assigns the bucket. Positive oracles
precede the junk gate so deliberate slang like `yeahh` is rescued before the junk gate
(which would otherwise score it −4.0 and drop it) ever sees it.

```
for w in drops:
  # 1. SLANG → KEEP (label=slang)
  if w in SLANG_STATIC: keep("slang", src="static")            # emnlp_dict ∪ LexNorm2015 ∪ kaikki slang/informal/Internet/intj
  elif w.endswith("in")  and hunspell_ok(w[:-2]+"ing"): keep("slang", "morph:g-drop")
  elif w.endswith("z")   and hunspell_ok(w[:-1]+"s"):   keep("slang", "morph:z-plural")
  elif hunspell_ok(run_collapse_strict(w)):             keep("slang", "morph:run-collapse")  # run≥3, base-len≥3

  # 2. ABBREVIATION → KEEP (label=abbrev), narrow set only
  elif w in ABBREV_CORE: keep("abbrev", "curated")              # Wiktionary Cat:English_abbreviations ∪ tz ∪ PL-BERT ∪ micro-list

  # 3. PROPER NOUN → KEEP only on strong evidence
  elif gazetteer_strong(w): keep("proper", "gazetteer-strong")  # guard AND (≥2 sources OR len≥5 OR gazetteer∧AOSP)
  elif w in WIKIDATA_ENTITY and guard(w): keep("proper", "wikidata")

  # 4. JUNK GATE → DROP (only if no oracle fired)
  elif is_junk(w): drop("junk")                                 # phonotactic<thr OR no-vowel OR (len≤3 ∧ no-oracle) OR keyboard-run

  # 5. Default → REVIEW (annotated)
  else: review(reasons=[broad_initialism?, single_source_gazetteer?, aosp_only?, mid_phonotactic?, wiktionary_gloss])
```

Guards (from measured tuning):
- `guard(w)` = `len(w) ≥ 4 AND has_vowel(w)`
- `gazetteer_strong(w)` = `guard(w) AND (len(w) ≥ 5 OR sources(w) ≥ 2 OR (in_gazetteer(w) AND in_aosp(w)))`
  — measured: 0 junk false-positives on the seed, full seed recall; the high-precision
  tier. The `gazetteer ∧ AOSP` clause adds corroborated len-4 names (a second team ships it).
- `run_collapse_strict`: only collapse runs of length ≥3 (or word-final vowel runs) and
  require reconstructed base length ≥3. The loose variant re-admits double-letter typos
  (`arround`, `targetted`) and is **not** shipped.
- `is_junk`: phonotactic per-char log-prob below threshold (≈ −3.4, ~bottom 10% of the
  drop file) **or** zero vowels **or** length ≤3 with no oracle hit **or** a keyboard-run
  / pure-repetition pattern.

### Resource bundle (`scripts/data/`)

| Asset | Source | License | Rows | Role |
|---|---|---|---|---|
| `slang_static.txt` | `emnlp_dict.txt` (chuchun8/PStance) ∪ LexNorm2015 (noisy-text) LHS columns | MIT-repo / research | ~41k | slang allowlist (7/7 seed, 0 junk) |
| `slang_lexical.txt` | kaikki tags `slang,informal,colloquial,Internet,vulgar` + `pos=intj` | CC-BY-SA | ~30k | lexicalized slang (clean; junk does not intrude) |
| `abbrev_core.txt` | Wiktionary `Category:English_abbreviations` (~8.2k) ∪ tz-codes gist ∪ PL-BERT plain.json ∪ manual micro-list (`mts`,`osl`,…) | CC-BY-SA / MIT / manual | ~8.5k | abbreviation auto-keep (0 junk on seed) |
| `abbrev_broad.txt` | kaikki tag `initialism` / `Category:English_initialisms` (~13–19k) | CC-BY-SA | ~15k | **review-flag only** (9/10 junk — never a gate) |
| `gazetteer.txt` (tagged by source) | US Census 2010 surnames (162k) ∪ SSA given names ∪ GeoNames cities500 single-token (~137k) [∪ smashew/NameDatabases] | PD / PD / CC-BY-4.0 [/ Unlicense] | ~322k | proper-noun oracle (0 junk w/ guard, ~65% precision balanced) |
| `wikidata_entity.txt` | Wikidata SPARQL: instance-of human(Q5)/place/org/brand/work, single-token labels, structurally guarded | CC0 | (subset) | brand/fiction/foreign (`pictionary`,`kree`,`academie`,`uwe`) |
| `aosp_words.txt` | AOSP `en_{US,GB}_wordlist.combined.gz` headwords | Apache-2.0 | ~161k | corroborating signal (never a sole gate) |
| `wiktionary_cache.json` | Wiktionary action-API (3 case-forms), POS + first gloss | CC-BY-SA | per-word | category label + review annotation |
| (runtime) phonotactic model | built from bundled `scripts/dictionaries/en/en_words.txt` | (internal) | 52k | junk-gate scorer |

Frequency signals (`wordfreq` zipf, optionally Datamuse `md=fp`) are used only to **rank**
and to annotate REVIEW lines — never as a sole gate (proven non-discriminating).

### Output artifacts
- `cleverkeys-typo-rescue-keep.txt` — auto-KEEP words, one per line, `# category src` comment.
- `cleverkeys-typo-confirmed-drops.txt` — auto-DROP (confirmed junk).
- `cleverkeys-typo-review.txt` — REVIEW, annotated `word \t # CATEGORY-guess zipf=N src=… gloss="…"`,
  delete-to-exclude (matches `cleverkeys-additions-review.txt` convention).

The KEEP list feeds back into `build_dictionary.py`'s candidate set; the review file, once
curated, is concatenated into the KEEP stream on the next build.

## Implementation Plan (never executed)

> The unchecked deliverables below were never built — this plan was abandoned when the
> spec was superseded by the one-pass classifier in `scripts/build_wordlist.py`
> (2026-07-02). Kept as a record of the proposed shape only.

### Phase 1: Resource bundle
**Deliverables**:
- [ ] `scripts/build_rescue_resources.py` fetching + snapshotting every asset above into
      `scripts/data/` with `scripts/data/PROVENANCE.md` (URL, license, date, row count).
- [ ] SSA given-names sourced via mirror or manual browser download (host 403s scripts);
      document the manual step.
- [ ] Wikidata SPARQL query committed (`scripts/data/wikidata_entity.rq`) + its output snapshot.

### Phase 2: Classifier core
**Deliverables**:
- [ ] `scripts/triage_drops.py` implementing the ordered cascade, pure-Python, reading
      only local assets + `wordfreq` + `hunspell -d en_US -l`.
- [ ] Phonotactic scorer built from `en_words.txt`; junk-gate thresholds wired to the
      measured distribution.
- [ ] Morphology rules (g-drop / z-plural / strict run-collapse) with hunspell base check.
- [ ] Emits the three output artifacts.

### Phase 3: Wiktionary enrichment + review annotation
**Deliverables**:
- [ ] `scripts/enrich_wiktionary.py` (batched action-API, 3 case-forms, cached) parsing
      `==English==` + POS + gloss; descriptive User-Agent for Wikimedia.
- [ ] REVIEW lines annotated with category-guess, zipf, sources, and Wiktionary gloss.

### Phase 4: Integration + attribution
**Deliverables**:
- [ ] Hook KEEP output into `build_dictionary.py`'s candidate merge.
- [ ] `NOTICE`/attribution updated: GeoNames (CC-BY), Wiktionary/kaikki derived lists
      (CC-BY-SA, ShareAlike the derived wordlist), AOSP (Apache-2.0), Wikidata (CC0).
- [ ] Update `english-dictionary-pipeline.md` to reference this rescue stage.

## Testing Strategy

### Unit Tests
- **Morphology**: `travelin→traveling`, `jokin→joking`, `boyz→boys`, `yeahh→yeah`
  rescue as slang; `arround`/`targetted` do **not** (strict run-collapse).
- **Guards**: `aas` rejected by `len≥4`; `bbg`/`bcd`/`bfg` rejected (no vowel / phonotactic).
- **Gazetteer-strong**: `skene`,`rodas`,`stoll`,`quartier` → KEEP; single-source len-4
  obscure-surname collisions (`arnt`,`noice`) → REVIEW, not KEEP.

### Regression Seeds (CI gate)
- **VALID seed** (must be KEEP or REVIEW, never DROP): `academie pictionary skene rodas
  quartier abt fmr hkt mgmt govt yeahh jokin travelin tbh ngl loong aboot`.
- **JUNK seed** (must be DROP or REVIEW, never KEEP): `aae aad aal aao bbg bcd bfg aas aat qwxz`.
- **Hard gate**: **0 junk in auto-KEEP**; **0 valid in auto-DROP**.

### Integration Tests
- Full run over the 6,483 produces three partitions that sum to 6,483 with no overlaps.
- Re-run with cached assets is byte-identical (determinism) and fully offline.

## Dependencies

### External Dependencies
- `wordfreq` (already importable on device).
- `hunspell` CLI + `en_US` dictionary (already installed).
- Network **only** for the one-time resource build / Wiktionary + Wikidata fetch; the
  classifier itself is offline.
- **Rejected** (documented dead-ends): spaCy/stanza/flair NER (not installable on bionic;
  wrong tool for lowercased tokens), Urban Dictionary API (accepts 10/10 junk), Free
  Dictionary API (1/14 recall), full IATA airport lists (5–7/8 junk), Python abbreviation
  libraries (Schwartz–Hearst needs running text), `names-dataset` pip package (56 MB,
  Facebook-leak provenance).

## Security / Privacy Considerations
- No user data involved; operates on a static candidate wordlist.
- `names-dataset` rejected partly on personal-data provenance grounds.
- Network fetches are to public, reputable sources; all snapshotted and reviewed before bundling.

## Error Handling
- Network failure during resource build: fail loudly, keep the previous snapshot; the
  classifier never runs against a partially-fetched asset (atomic replace).
- Wiktionary enrichment failure: degrade gracefully — REVIEW lines simply omit the gloss;
  KEEP/DROP/REVIEW partitioning does not depend on the online step.
- Unknown/empty token: routed to REVIEW, never silently dropped.

## Licensing & Attribution Summary
- **Public domain**: US Census surnames, SSA names, smashew/NameDatabases.
- **CC-BY-4.0**: GeoNames (requires attribution notice).
- **CC0**: Wikidata.
- **Apache-2.0**: AOSP wordlist.
- **MIT-repo**: emnlp_dict, PL-BERT plain.json.
- **CC-BY-SA-4.0**: Wiktionary/kaikki-derived lists → attribute + ShareAlike the derived
  wordlist; ship a notice: *"Word classifications derived from English Wiktionary
  (CC BY-SA 4.0) via kaikki.org Wiktextract."*

## Success Metrics
- 0 junk-seed words in auto-KEEP; 0 valid-seed words in auto-DROP (CI hard gate).
- Measurable rescue of valid words from the 6,483 (rough expected order, from research):
  a few hundred high-precision slang/abbrev auto-keeps, a strong-evidence proper-noun
  tier auto-kept, the bulk of single-source/initialism hits routed to an annotated REVIEW
  list of manageable size, and the clear junk dropped.
- Acceptance: maintainer can curate the REVIEW list in one sitting using the annotations.

## Open Questions
1. Exact phonotactic threshold and REVIEW-list size cap — tune against the measured
   distribution during Phase 2 (start at the ~−3.4 / bottom-10% mark).
2. Whether to also run the same triage retroactively over `cleverkeys-band2-drops.txt`
   (extended onomatopoeia) — out of scope for v1; revisit if needed.
3. Whether to persist the Wiktionary/Wikidata snapshots in-repo or as a release asset
   (size vs. reproducibility) — lean in-repo gzipped.

## Future Enhancements
- Fold the rescue stage directly into the dict-expansion filter so candidates are never
  "dropped then rescued" but classified in one pass.
- Optional LLM pre-labeling of the REVIEW pile only (deterministic core unchanged) to
  further shrink manual effort.

## As-Built (2026-07-20): multi-language generalization

`build_en_wordlist.py` was renamed (`git mv`) to **`scripts/build_wordlist.py`**
and parametrized over a `LANG_CONFIG` table (14 languages; `--lang en` is
bit-identical to the old script — verified keep-count 98,140 + identical
reason-Counter before/after the refactor). The EN artifacts were NOT
regenerated.

**Shared verbatim** (parametrized only by config): band architecture (band 1
conservative / band 2 oracle-required), carryover no-silent-regression
guarantee, 1-/2-char rules, extras universe, elongation exemption, tiered ed1
typo gaps (2.0/2.5/3.0), foreign-dominance +1.0 margin, review-artifact
curation loop, Stage-H CKDT verify (magic 0x54444B43, version 2, bin==src
word set).

**Per-language additions**:
- Script-aware candidate gate (`latin` / `greek` / `cyrillic`) replacing the
  EN-only `_is_latin_word` call; ed1 alphabet per language (accented Latin,
  Cyrillic incl. ё, Greek incl. tonos vowels + ς).
- Case policies: `en` (original), `de_nouns` (cap-acceptance IS
  spell-validity — German nouns are capitalized; probed aspell de:
  Straße ok / straße flagged), `plain` (Cap−lower = name rescue evidence).
- Oracle tiers: A = en fr de es nl ru (spellcheckers + AOSP); B = it pt
  (pyspellchecker + AOSP); C = sv el tr (AOSP is the SOLE band-2 oracle);
  D = id ms tl (no oracles → band == top, negatives-only; the typo detector's
  known-good set degrades spell → AOSP∩universe → zipf≥3.5 corpus words).
  A configured-but-missing oracle is a hard `sys.exit(1)`.
- `--limit N` size cap: keep the N best-ranked survivors (func/allow/
  MUST_INCLUDE protected); cut words carry the explicit `limit-cut` reason in
  the review artifacts.
- Per-language curation files `scripts/dictionaries/<lang>/<lang>_{allow,block}list.txt`
  and MUST_INCLUDE guard sets (one representative per keep-class).

**Deliberate omissions (EN-only, no non-EN analog)**: BRITISH_RULES (per-language
variant maps like pt-BR/PT are future work), NLTK words/names, VALID_SEED_KEEP,
the held-out `--eval` user-export coverage set, and the flat-JSON asset
fallback (non-EN assets ship bin-only).

**Corpus gotchas encoded in the script** (all measured 2026-07-20):
- wordfreq casefolds ß→ss: the de dict ships strasse/grösse forms (as the old
  25k build did).
- wordfreq casefolds Greek final sigma ς→σ: the whole el stream AND the
  shipped el pack are σ-final; the AOSP el oracle is remapped word-final ς→σ
  (`load_aosp`) — without it 4,670 shipped words were mis-dropped as
  band2-no-oracle. σ-final display forms remain a documented status-quo caveat.
- fr/it `contractions_<lang>.json` are ~26k/22k elision-expansion tables, not
  functional-key sets → demoted to positive-evidence-only
  (`FUNC_FORCEKEEP_MAX = 1000`); en/nl/de small maps still force-keep.
- `build_dictionary.py`'s English junk blocklist is gated on `--lang en`
  ("hav" = Swedish 'sea', "teh" = Indonesian 'tea').
- wordfreq foreign-language fallbacks: `sr`→`sh`, `tl`→`fil` (accepted).

**Shipped sizes (2026-07-20 regeneration)**: es 50,000 · fr/de/it/pt/sv/nl/tr
40,000 · ru 50,000 · el 39,860 (survivors of the full 46,306 stream,
AOSP-banded) · id 28,637 · ms 25,861 · tl 27,922 (Tier-D survivor counts;
corpus ceilings ~28–31k) · sw 20,000 (unchanged corpus pipeline). Orchestrated
by `build_all_languages.py` (classifier → unigrams → prefix boosts → langpack,
manifest v2, deterministic zips).

---

**Created**: 2026-06-29
**Last Updated**: 2026-07-20 (multi-language As-Built)
**Owner**: Dictionary tooling
**Reviewers**: (pending maintainer review)
