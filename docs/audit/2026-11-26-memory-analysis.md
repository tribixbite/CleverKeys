# Memory analysis: debug build on the Saga, 2026-11-26

Status: measured, not fixed. Nothing in the app was changed. Code references are to HEAD
`edc9132d6`. All sizes are MiB (1,048,576 bytes), as `dumpsys meminfo` and the heap tools print
them.

## Setup and method

- Phone: Saga `192.168.1.130`, Android 14, 1080x2400. The debug APK
  (`tribixbite.cleverkeys.debug`, sha256 `29d6da7e…`) was built with `scripts/gradle-guard.sh
  assembleDebug` from the shared tree at `a56d5d4b9` plus other sessions' uncommitted edits
  (`SuggestionHandler.kt`, the new `UserWordCasing.kt`, contraction assets). None of those edits
  touch the owners named below. The debug app was made the current IME and fed a Chrome `data:`
  textarea. Afterwards it was uninstalled and the release default IME was restored.
- Scenarios: cold English (a), 13 synthetic swipes (b), Settings open (g), German turned on as the
  secondary language through Settings (c), the rebuild peak of that toggle and of a custom-word add
  (d), clipboard pane then emoji pane (e), 11 hide/show cycles plus a rotation (f), two very long
  swipes (h), and three whole-service recreations by switching the IME to the release build and back (i).
- Heap dumps: `am dumpheap` (a GC runs first, so the dumps hold the live set). Each dump was
  analysed with a single-file dominator-tree analyzer that computes retained sizes,
  shortest strong root paths (with `Reference.referent` edges excluded) and duplicate arrays by owner.
  `tools/hprof_analyzer.py` only prints a shallow census and incoming references.
  The analyzer source and every text report are in `build/nov26-saga-mem/` (git-ignored). The
  `.hprof` files were kept out of the repo.
  <!-- TODO: move Dom.java into tools/ if this analysis is to be repeatable from the repo. -->
- `dumpsys meminfo` forces an explicit GC in this process (the GC log shows one per sample), so its
  `Heap Alloc` is the live heap. The 0.3 s sampler used during the German toggle therefore measures
  the live peak, not garbage. The custom-word add was sampled through VmRSS and the GC log only.

## Measurements

| Scenario | Java live | Java PSS | Native alloc | Total PSS | RSS |
|---|---:|---:|---:|---:|---:|
| (a) cold, English | 57.8 | 67.7 | 33.3 | 182.4 | 287.7 |
| (b) after 13 swipes | 58.0 | 62.3 | 46.7 | 197.5 | 310.1 |
| (g) Settings open (same process) | 60.9 | 65.1 | 48.9 | 207.9 | 324.3 |
| (c) English + German, settled | 87.5 | 167.4 | 49.8 | 316.5 | 436.5 |
| (d) after a custom-word add, settled | 87.4 | 126.7 | 47.6 | 277.6 | 398.5 |
| (e) clipboard pane / + emoji pane | 87.6 / 88.3 | 136.1 / 92.2 | 48.2 / 57.9 | 285.8 / 252.1 | 408.2 / 375.1 |
| (f) 11 hide/show + rotation | 88.2 | 92.9 | 52.8 | 251.8 | 374.8 |
| (h) after two very long swipes | 88.1 | 90.7 | 71.4 | 269.6 | 392.8 |
| (i) after 3 service recreations | 106.2 | 126.6 | 52.1 | 331.7 | 454.9 |

Transient peaks:

- German toggle (`sampler-de-toggle.txt`): live Java went from 62.5 to **112.1** within 2 s, then
  settled at 87.5. Native alloc rose by 26 MiB for about 1 s at the same time (not attributed).
- One custom-word add (EN+DE): the whole English CTC trie was rebuilt (`CtcEngineAdapter`: 98,083
  words, 0.5–3.2 s). `CKMemProbe` used = 138 → 164 → **195.4** MiB across
  `ctc.baseParse`/`mergeAndOrdinals`/`trie`. The GC log reported **154 MiB still live** after the first young GC (124 MiB after
  the next) against 87.4 settled. RSS went 369.5 → 445.7 → 395.6 MiB and stayed there.
- Recreation (i): the new service's `init.enter` began at used = 127.4 MiB, `primary.dictionary`
  reached 172.0 MiB and the English `ctc.trie` 202.8 MiB, against the 256 MiB limit. Both services
  were alive at that point.
- After a peak, Java PSS stays far above the live set (167 vs 87.5 in (c), 127 vs 87 in (d)) until
  ART trims the heap. This is why the maintainer's phone shows 108–161 MiB Java with LOS frees of
  28 and 47 MiB. The large-object frees are the JSON text, `HashMap` tables and `ArrayList` backing arrays of a rebuild.

Release on the same phone, for comparison: English only, 94.5 MiB live after 59 min of earlier
tests that switched IMEs. After its service had just been destroyed, the process still held 38.4 MiB live.
On a fresh service it held 56.6 MiB. Defect 1 below explains the difference.

## Where the English-only 58 MiB goes (dump a)

| Owner | Retained | Note |
|---|---:|---|
| `CtcEngineAdapter.trieMemos` (en) | 28.25 | trie 17.44 (231,140 nodes), `ordinals` HashMap 4.38, `CtcFuzzyRescue` 2.65, word strings ~3.7 |
| `WordPredictor` | 18.06 | `prefixIndex` 9.71 (293,787 HashSet entries), `dictionary` LinkedHashMap 4.62, word strings ~3.7 |
| `EmojiKeywordIndex` (static) | 7.66 | 36,253 map-per-node trie nodes from a 324 KB TSV; loaded with no emoji pane opened |
| two `ContractionManager`s | 0.89 + 0.83 | the service's copy and the CTC adapter's `en` copy hold nearly the same tables |
| `BigramModel` + `StaticContextLm` | 0.69 | compact already |

German adds 26.6: `WordPredictor.secondaryIndex` (`NormalizedPrefixIndex`) 11.79 (`canonicalMap`
5.24, `prefixIndex` 5.02) and the `de` trie memo 14.79 (trie 8.48 for 109,139 nodes, `ordinals`
3.17, fuzzy rescue 2.40). Duplicate-content analysis: about 132,000 distinct word strings are held
both under `InputCoordinator` (the CTC memos) and under `WordPredictor`, so every word is stored
twice. The geometric adapter was not loaded in any dump.

## Ranked candidates

| # | Candidate (owner) | Measured | Proposed change | Est. saving | Risk |
|---|---|---:|---|---:|---|
| 1 | **Defect: a retired service keeps the old English dictionary** (below) | 18.05 per retained service | clear the memo at shutdown, drop the predictor's identity caches | 18 (EN), more for larger primaries | low |
| 2 | Full CTC rebuild for one custom word / language toggle (`CtcEngineAdapter.kt:702–910`) | +67 live for one word; peak 195 used; toggle +50 live | insert/remove the word in the existing trie and `ordinals` (the tap predictor already has an incremental path); for full rebuilds, read English from a stream rather than `readBytes()` + `JSONObject` (`:716`: 1.75 MiB `byte[]` + 3.5 MiB `String` + a 98k-entry map mid-build) | removes most of the 50–67 MiB peak and the PSS left behind | medium |
| 3 | Tap predictor structures (`WordPredictor.kt:381–382`, `buildPrefixIndex` `:2366`; `NormalizedPrefixIndex.kt:51–54`) | EN 18.06, DE 11.79 | sorted word array + `IntArray` frequencies, prefix lookup by binary-searched range instead of a `HashSet` per 1–3-letter prefix | EN ~13, DE ~8.5 | medium (iteration-order and tie-break behaviour must be pinned first) |
| 4 | `CtcTrieNode` objects (`CtcLexiconTrie.kt:37`) | EN 17.44 (75 B/node), DE 8.48 | struct-of-arrays trie (parent, first child, char, depth and word index as `IntArray`s) | EN ~12.8, DE ~6.3 | high (the beam walks node objects) |
| 5 | `EmojiKeywordIndex`, prewarmed in `CleverKeysService.kt:479` | 7.66 static | load on the first emoji search; keyword array + binary search, shared emoji strings | 7.66 until first search, ~6.5 after | low |
| 6 | CTC `ordinals` HashMap<String,Int> (`CtcEngineAdapter.kt:768`) | EN 4.38, DE 3.17 | store the rank on the terminal trie node | ~7.5 | low–medium |
| 7 | Word strings held twice (tap predictor + CTC memo) | ~132k shared contents, ~3.4 estimated | build both from one string table, or intern at load | ~3–4 | low |
| 8 | `CtcFuzzyRescue.Entry` objects (`CtcFuzzyRescue.kt:23`) | EN 2.65, DE 2.40 | index into the shared word array + `FloatArray` | ~4 | low |
| 9 | Second English `ContractionManager` in `CtcEngineAdapter.kt:969` | 0.83 | reuse the service's manager for `en` | 0.8 | low |
| 10 | Native high-water after long swipes | +19 alloc (52.8 → 71.4), not released after 20 s | attribute with heapprofd first (the debug build is profileable) | up to 19 | unknown |
| 11 | Scene backdrop graphics (maintainer's 41 MiB GL+EGL) | not measurable here | see Graphics | — | — |

Items 3, 4, 6 and 7 together would cut English from ~46 to ~14–18 MiB and German from ~26.6 to
~7–9. That is the largest steady-state saving available. Items 1 and 2 address the OOM risk, which
comes from peaks and retention, not from the steady state.

### Defect 1 — proven: retired services retain the English dictionary through the autocorrect memo

After three IME switches, dump (i) holds two `CleverKeysService`s and two `WordPredictor`s. The
retired predictor retains **18.05 MiB**. Its strong root path, from a real device:

```text
JNI global → WindowOnBackInvokedDispatcher$OnBackInvokedCallbackWrapper
  → CallbackRef.mCallbackRef → InputMethodService$$ExternalSyntheticLambda3.mStrongRef
  → retired CleverKeysService → _suggestionHandler → SuggestionHandler.autocorrectMemo
  → AutocorrectMemo.entry → Entry.owner → retired WordPredictor
      .fuzzyAlphabetIndexRef → old prefixIndex map (9.71)
      .halfSpaceVariantsDict → old dictionary map (4.62)
```

This is the framework root that `docs/audit/2026-09-10-memory-oom-root-cause.md` proved on the
emulator. The 2026-09-10 fix made a retired service drop its dictionaries, and two later additions
reopen that path:

- `AutocorrectMemo.Entry.owner` (`autocorrect/SpaceCommitPreview.kt:186`) holds the predictor
  strongly, and `SuggestionHandler.shutdown()` (`SuggestionHandler.kt:741`) does not call
  `autocorrectMemo.clear()`.
- `WordPredictor.shutdown()` (`WordPredictor.kt:667`) replaces `dictionary`/`prefixIndex` with
  empty maps but leaves the identity caches `halfSpaceVariantsDict` (`:397`) and
  `fuzzyAlphabetIndexRef` (`:3129`) pointing at the full old maps.

The extra memory survived the forced GC (106.2 vs 88.2 MiB live). Fix: clear the memo in
`SuggestionHandler.shutdown()`; in `WordPredictor.shutdown()`, null `halfSpaceVariants`,
`halfSpaceVariantsDict`, `fuzzyAlphabetCache` and `fuzzyAlphabetIndexRef`; preferably key both
caches without a strong reference (identity hash + size). A regression test belongs in the existing
recreation memory gate: the retired predictor's reachable size should stay under ~1 MiB.

The same identity caches also keep the previous maps alive after a primary-language reload
(`WordPredictor.kt:1849` swaps in new maps) until the next lookup refreshes them. That adds up to
~14 MiB on top of the reload peak (read from the code, not measured).

### Other observations

- No retention from hide/show or rotation: dump (f) holds one service and one `Keyboard2View`, and
  live Java changed by −0.1 MiB.
- Settings runs in the IME process and adds ~3 MiB Java. Opening the clipboard pane (0 rows) and
  the emoji pane added under 1 MiB Java and ~10 MiB native (glyph rendering).
- Native: the CTC encoder input is fixed at T=64 (`CtcFeaturizer.RESAMPLE_LENGTH`), so the growth
  after long swipes is not encoder activations. Java objects with native backing
  (`Path`, `RenderNode`, Cleaner thunks) did not grow either (dumps f vs h). The CTC session uses
  XNNPACK (`onnx/ModelLoader.kt:363`). The voice-input skill records that XNNPACK's allocator is
  one process-wide arena that is never freed. Whether the CTC encoder needs XNNPACK, and what the
  +19 MiB is, should be settled with heapprofd.
- Code PSS is 51–55 MiB in the debug build against 29 MiB in the release on this phone. Debug code
  size is not a target.

### Graphics (maintainer's 41 MiB)

The Saga's driver reports no GL/EGL mtrack ("Graphics" 3–6 MiB here), and the default look has no
scene, so this could not be measured. From `theme/SceneOverlay.kt`, an aurora scene holds two
half-resolution curtain buffers and `STAR_LAYERS = 2` full-resolution star buffers as hardware
`ARGB_8888` buffers. At a keyboard of about 1080x900 px that is ~3.7 MiB per star layer and ~0.9 per curtain, on top
of the window's own triple-buffered surface (~11 MiB). Measure on a phone whose driver reports
mtrack before changing anything. One star layer already applies when twinkle is 0.

## Limits

- The swipes and taps were synthetic (`input motionevent`), and the clipboard history was empty. No
  clips were created, because that would have overwritten the phone's real clipboard.
- The dominator analysis treats the whole dump as one graph. The weak-edge-free run (i) and
  survival of the forced GC are the evidence for "strongly retained".
- Savings for items 3–8 are estimates from the measured per-entry costs, not measurements of an
  implementation.
- The mid-rebuild dump caught the JSON-text stage (95.4 MiB live); the larger later stage was seen
  only through the GC log and `CKMemProbe`.
