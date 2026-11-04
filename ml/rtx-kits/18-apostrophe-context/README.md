# Kit 18: apostrophe context chooser, round 3 (listed evidence, `its` only)

This is todo item 17's sibling, item 18. Sources are `docs/eval/2026-10-07-apostrophe-context.md`
§6.3 point 2 and §5.1.

## 1. Answer: this runs on CPU, on the phone. No GPU is involved

The evaluation contains no neural model. It is a pure-JVM JUnit measurement
(`src/test/kotlin/tribixbite/cleverkeys/swipe/ApostropheContextEvalTest.kt`). It replays
sentences through the shipped `ContractionOverlay`, and the chooser asks the shipped static
bigram LM. Measured 2026-11-04:

| quantity | value |
|---|---|
| model | `src/main/assets/lm/en.cklm`, 439,017 B, an n-gram table and not a network |
| round-3 populations | Q1 GUM 11,959 segments; Q2 Ubuntu 125,407 segments (137,366 total) |
| measured speed on this phone | stage 4 over 246,624 segments (stand-in: the spent `heldout_en` + `ewt_train_en`, so no fresh number was read) took **3.05 s** in JUnit and **3 s** wall for the whole JVM |
| expected stage-4 time on round-3 data | about 2 s of evaluation |

The only real cost is Gradle compiling the test sources. On Termux,
`compileDebugUnitTestKotlin` takes minutes to tens of minutes, depending on what is already
compiled, and is bounded by `scripts/gradle-guard.sh`. The "RTX" owner label on item 18 was a
routing label, not a hardware need.

## 2. Pre-registration (committed with this kit, before any round-3 number exists)

**Hypothesis (frozen; no grid and no tuning).** Variant (b) from round 2 is restricted to the
`its` surface. In code it is
`ROUND3_ITS_LISTED = Params(0.0, Evidence.LISTED, promotePossessives = false, surfaces = setOf("its"))`.
It means τ = 0, and the challenger's pair must be a stored continuation of the previous word, so
there are no unigram-only decisions. Possessives are never promoted. The chooser acts only when
slots 0–1 are `its`/`it's`. Every other no-action rule of the eval doc's §0 stands.

**Populations.** Neither population has been read by the LM builder, by any earlier apostrophe
round, or by the 2026-10-25 fresh LM eval:

* **Q1 = UD English-GUM** train+dev+test at commit `1fe635509c649e376dfb449d528424ab78f4eaee`:
  interviews, how-to, fiction, academic, vlogs, speeches and conversation. Licence: CC BY-NC-SA
  4.0, with parts under CC BY. It is used eval-only and locally; it is never committed or
  shipped, the same footing as EWT in rounds 1–2.
* **Q2 = Ubuntu Dialogue Corpus**, `.tsv` members 125,001–150,000 in archive order of
  `ubuntu_dialogs.tgz`. These are disjoint from members 1–40,000 (the context-rescoring replay)
  and 100,001–125,000 (round 2's P2). Utterances must pass `usable_utterance`, the same caveat
  as P2: written-form gold penalises any `it's` chosen after a sloppy `its`.
* **Leakage control.** A segment is dropped if its sentence key occurs in any of these:
  * an English LM build input (Leipzig and Tatoeba, every segment);
  * the standing eval files;
  * EWT train, the Ubuntu P2 file or Common Voice;
  * the 2026-10-25 `static-lm-fresh` `ud`/`web` files;
  * earlier in Q1/Q2 itself.
* **Results of the build.** Q1 kept 11,959 segments (527 overlapping, 123 duplicate), sha256
  `f007922b2ececee8fb890443cdbec028683b465e0c2bb2e11fc5cadfa9af81e8`. Q2 kept 125,407 (15,881
  overlapping, 3,697 duplicate), sha256
  `df6b2c62331ca535ca7ecdc729726c71617ac39adc767d28d38cee18e21bf4bf`. Only sizes were printed; no
  `its`/`it's` count was taken.
* The two round-2 results for (b), +8.89 on P1 and +7.19 on P2, are **not counted** toward this
  round.

**Bar.** The variant ships only if ALL of these hold, counting positions with a previous word
(occurrence and metric definitions as in eval doc §0):

| id | bar |
|---|---|
| R1 | **Q1**, `its` surface: top-1 Δ ≥ +5.0 pt AND wins − losses ≥ 2·√(wins + losses) (round 2's N1, unchanged) |
| R2 | **Q2**, `its` surface: top-1 Δ ≥ 0 (round 2's N2) |
| R3 | structural: across every token of Q1 and Q2, 0 changed slates whose slots 0–1 are not `its`/`it's` (`[stage4] N3 … violations=0`; the test asserts it). Sentence-initial positions are unchanged by construction |
| R4 | every existing contraction/chooser test green |

Scored **once**. A re-run is allowed only after a crash, and any re-run is reported.

* **If it passes:** follow the eval doc's §6.0 "If (a) passes" plan with this variant. The
  chooser moves to `src/main/kotlin/tribixbite/cleverkeys/swipe/`, restricted to `its` with
  LISTED evidence, and is wired as §5.2 describes: after `rescoreWithContext` and before the D1
  augment, skipped for password fields, user words respected, English only. It gets a real-path
  test.
* **If it fails:** nothing is wired, and the result is committed to the eval doc.

## 3. Files

| file | role |
|---|---|
| `build_round3_populations.py` | writes Q1/Q2 to `~/.cache/cleverkeys-corpora/static-lm-eval/round3/`; pins and verifies the GUM files and the Ubuntu archive |
| `../../../src/test/kotlin/tribixbite/cleverkeys/swipe/ApostropheContextEvalTest.kt` | stage 4 added; stages 1–3 unchanged in behaviour (stage 3's block is generalized, same params) |
| `DATA_MANIFEST.md` | inputs, hashes, licences |

## 4. Commands (on the phone, or on any machine with this repo and the local corpora)

```bash
# 1. populations (about 1 min on the phone; needs network once for GUM, ~27 MB)
python3 ml/rtx-kits/18-apostrophe-context/build_round3_populations.py
sha256sum ~/.cache/cleverkeys-corpora/static-lm-eval/round3/*.txt   # must equal §2

# 2. the scored run (once)
APOSTROPHE_EVAL_STAGE=4 GRADLE_GUARD_XMX=1536m GRADLE_GUARD_METASPACE=384m \
  scripts/gradle-guard.sh runPureTests -PtestClass=swipe.ApostropheContextEvalTest -PgeoFull=true \
  2>&1 | tee build/apostrophe-round3.log
rg '\[stage4' build/apostrophe-round3.log

# 3. R4
scripts/gradle-guard.sh runPureTests \
  -PtestClass=swipe.ContractionContextChooserTest,swipe.ContractionOverlayTest
```

The stage-4 harness was smoke-tested on 2026-11-04 without Gradle. The edited test file was
compiled with `kotlinc` against the existing build classes and run under `JUnitCore` on a
synthetic 10-line fixture (`APOSTROPHE_STAGE4_DIR`), and the result was `OK (1 test)`, N3
violations 0. The timing in §1 used the same path. The official command above (step 2) was NOT
run, because that would be the scored read.

## 5. What to send back

`build/apostrophe-round3.log` (the `[stage4]` lines), the two population sha256 lines, and the R4
result. The eval doc then gets a "§8 Round 3 result" with the verdict against the bar above.

## 6. Result (2026-11-04)

Run once on the phone; every bar passed (R1 GUM `its` +30.44 pt, R2 Ubuntu `its` +6.15 pt,
R3 0 violations, R4 59 tests green). The numbers, and what shipped, are in the eval doc §8.
