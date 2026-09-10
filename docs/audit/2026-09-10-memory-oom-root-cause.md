# Memory Audit: App Switching, IME Recreation, and Heap OOM Root Cause

**Date:** 2026-09-10
**Status:** Complete / Verified Root Causes
**Primary Platform Constraint:** ART Java heap growth limit = 256 MiB (`dalvik.vm.heapgrowthlimit = 256m`)
**Target Hardware:** Samsung Galaxy S25 Ultra (SM-S938U1, 16 GB physical RAM), Android 14/15 (API 34/35)
**Corroborating Data Sources:**
- Device logcat: `build/memory-phone-28699.log` (Samsung SM-S938U1, PID 8348 and PID 28699 OOM events)
- Shark retained heap analysis: `build/memory-shark-analysis.txt`, `build/memory-shark-corrected.txt`, `build/memory-shark-fixed.txt`
- Synthetic emulator heap dump: `build/ew-memory-heap/.../retired-services.hprof` analyzed via `tools/hprof_analyzer.py`

---

## 1. Executive Summary

During intensive daily usage—specifically involving rapid app switching, screen-on/off transitions, and IME resets—CleverKeys encountered fatal `java.lang.OutOfMemoryError` crashes on modern flagship devices despite the devices having 16 GB of physical RAM.

The core breakdown is an **asymmetric constraint mismatch**:
1. **The Physical Memory Myth**: Android devices enforce a strict per-process Java Virtual Machine growth limit (`dalvik.vm.heapgrowthlimit`), configured to **256 MiB** (268,435,456 bytes) on modern flagship devices (including Samsung Galaxy S24/S25 series). Physical system RAM size (12 GB–16 GB) has no bearing on this boundary.
2. **Single-Instance Steady-State Weight**: A single bilingual instance (e.g. English + Italian) legitimately retains between **110 MiB and 135 MiB** of active JVM objects across CTC beam-search tries, binary dictionaries, normalized prefix indexes, contraction overlay tables, and Compose/View hierarchies.
3. **The Recreation Collision Window**: When an IME service is destroyed and recreated (such as during system theme change, orientation shift, configuration change, or app switching), Android creates the new `InputMethodService` within **~87 ms** of invoking `onDestroy()` on the old one. ART's concurrent garbage collector cannot sweep and compact ~130 MiB of complex, multi-million-reference object graphs in 87 ms.
4. **Transient Spikes**: When the replacement service immediately enters initialization while the old service's graph remains reachable or uncollected, baseline heap reaches **242.5 MiB**. The subsequent loading of dictionaries and warm-up spikes push allocation past 255.6 MiB, throwing `OutOfMemoryError` at arbitrary low-level allocation sites (e.g. `keyCellIsEmpty`, `HashMap$Node`, `ArrayList.grow`).

---

## 2. Java Heap Object Census & Steady-State Budget

Inspection of heap dumps via `Shark` and `tools/hprof_analyzer.py` reveals the precise retained memory distribution of an active CleverKeys instance configured for bilingual typing (English primary, Italian secondary):

```
+-------------------------------------------------------------------------+
| TOTAL JAVA HEAP LIMIT: 256.0 MiB (268,435,456 bytes)                    |
+=========================================================================+
| SINGLE ACTIVE IME INSTANCE RETAINED BUDGET: ~110 – 135 MiB              |
|                                                                         |
| 1. CTC Lexicon Tries (Pure JVM Beam Search)               ~55.0 MiB     |
|    - English Trie (231,955 CtcTrieNodes + arrays)          ~30.0 MiB    |
|    - Italian Trie (183,974 CtcTrieNodes + arrays)          ~25.0 MiB    |
|                                                                         |
| 2. Binary Dictionaries & Prefix Indexes                   ~45.0 MiB     |
|    - English Dictionary (98,000 words + prefix index)      ~25.0 MiB    |
|    - Italian Dictionary (40,000 words + NormalizedPrefix)  ~20.0 MiB    |
|                                                                         |
| 3. Contraction & Suggestion Overlays                      ~10.0 MiB     |
|    - ContractionManager (22,591 contraction pairings)       ~8.0 MiB    |
|    - Personalization & user dictionary state                ~2.0 MiB    |
|                                                                         |
| 4. UI, Views, Windows, and Graphics                       ~25.0 MiB     |
|    - Keyboard2View, CandidatesView, EmojiKeyboardView      ~10.0 MiB    |
|    - Android Window Decor, Skia RenderNodes, Bitmaps       ~15.0 MiB    |
+-------------------------------------------------------------------------+
| TWO CONCURRENT INSTANCES IN MEMORY (OLD + NEW):  ~220 – 270 MiB         |
| -> FATAL HEAP OVERFLOW (> 256 MiB)                                      |
+-------------------------------------------------------------------------+
```

### Detailed Class Census (Android 1.0.3 HPROF Extract)

From synthetic recreation diagnostic `retired-services.hprof`:

| Class Name | Instance Count | Shallow Bytes | Total Retained Group |
|---|---|---|---|
| `tribixbite.cleverkeys.swipe.ctc.CtcTrieNode` | 415,929 | ~13.3 MiB | ~55 MiB (including child arrays & ordinals) |
| `tribixbite.cleverkeys.swipe.ctc.CtcTrieNode[]` | 415,929 | ~16.6 MiB | Part of CTC Lexicon Trie |
| `java.lang.String` | 362,840 | ~8.7 MiB | ~28 MiB (wordlists, prefixes, keys) |
| `java.util.HashMap$Node` | 148,392 | ~4.7 MiB | Normalized prefix indexes & contraction maps |
| `int[]` (Primitive Arrays) | 68,412 | ~18.2 MiB | CTC ordinals, bitmap pixels, trie metadata |
| `byte[]` (Primitive Arrays) | 12,890 | ~14.5 MiB | Binary dictionary blobs & buffers |
| `char[]` (Primitive Arrays) | 85,210 | ~6.8 MiB | String backing characters |
| `java.util.HashSet` | 6,102 | ~0.3 MiB | 5,974 prefix sets holding 294,791 words |

---

## 3. The Four Core Root Causes

### Root Cause 1: Rapid Service Recreation Overlap without GC Quiescence
- **Mechanism**:
  On Samsung One UI and stock Android, switching between full-screen apps, rotating the device, or cycling the screen often leads the system to unbind and rebind the IME service.
  The logcat records:
  ```text
  13:18:54.890 CleverKeysService: onDestroy
  13:18:54.977 CleverKeysService: onCreate
  13:18:55.012 CleverKeysService: init.enter, totalMemory=256MB, freeMemory=13.5MB (Allocated: 242.5MB)
  ```
- **The Failure**:
  Only **87 ms** elapsed between `onDestroy` and `onCreate`. Because the old service's ~130 MiB graph was unreferenced but not yet collected, `freeMemory` was down to 13.5 MiB upon entry. The new service immediately started allocating its own dictionary buffers, triggering an unrecoverable `OutOfMemoryError`.

### Root Cause 2: Uncancelled Background Worker Threads & In-Flight Loaders
- **Mechanism**:
  When `CleverKeysService.onDestroy()` called `PredictionCoordinator.shutdown()`, it set its local reference to null, but did not cancel the active background threads inside `AsyncDictionaryLoader` or abort running coroutines.
- **The Failure**:
  In `BinaryDictionaryLoader.kt` and `CtcLexiconTrie.kt`:
  ```kotlin
  // Loop inserting 231,000 words into the trie
  for (word in wordList) {
      // Missing: if (Thread.interrupted() || !isActive) break
      insert(word.text, word.frequency)
  }
  ```
  Neither loader checked for interruption or job cancellation. If a service was destroyed while dictionary loading was still in flight (e.g. rapid keyboard open/close), the obsolete background thread continued allocating millions of objects into the heap, racing directly against the new service's loader.

### Root Cause 3: Transient Allocation Spikes on Keyboard Load / Show
- **Mechanism**:
  Every call to `onStartInputView()` invokes `InputCoordinator.prewarmGeometricEngine()` -> `CtcEngineAdapter.warmUpAsync()`.
- **The Failure**:
  Building the CTC beam-search trie parses and processes wordlists, creates temporary `JSONObject` instances, allocates intermediate `ArrayList<Pair<String, Double>>`, and runs `CtcLexiconMerge`. This transient process generates an additional **35 to 50 MiB** of short-lived objects. If the baseline heap is already at 210 MiB, this transient allocation spike immediately bursts through the 256 MiB ceiling before the young-generation GC can sweep it.

### Root Cause 4: External Framework and Internal Utility Retaining Roots
- **Mechanism**:
  Heap graph tracing using `tools/hprof_analyzer.py` and Shark identified two persistent retaining paths keeping retired `CleverKeysService` instances alive across GCs:
  1. **AOSP/Samsung Native Callback Retention**:
     ```text
     Native Global Reference
       -> WindowOnBackInvokedDispatcher$OnBackInvokedCallbackWrapper
       -> CallbackRef.mStrongRef
       -> InputMethodService$InputMethodImpl
       -> CleverKeysService (Destroyed)
     ```
     Even after `onDestroy()`, the framework's back dispatcher retained a strong reference to the destroyed service until the window context was fully detached. If the service still held references to `InputCoordinator`, `WordPredictor`, and CTC adapters, all 130 MiB remained transitively rooted.
  2. **`KeyboardDimensionsHelper` Leaking Window Listeners**:
     In `KeyboardDimensionsHelper.kt`:
     ```kotlin
     fun calculateDynamicKeyboardHeight(...): Int {
         val foldTracker = FoldStateTracker(_context)
         // Registers callback with WindowInfoTrackerCallbackAdapter
         // foldTracker is NEVER closed or unregistered!
     }
     ```
     `calculateDynamicKeyboardHeight()` instantiated a new `FoldStateTracker` on every layout measurement without calling `close()`. Each tracker registered an observer with `androidx.window`'s `MulticastConsumer`, accumulating permanent strong references to the `CleverKeysService` context.

---

## 4. Audit of Mitigations: Implemented vs. Required

### A. Implemented in Commit `358cd54b` (Verified Effective)
1. **Teardown Decoupling**:
   - Updated `InputCoordinator.shutdown()` to terminal status: immediately detaches both CTC and geometric adapters, clears swipe capture state, and guards delayed warm-up and fallback callbacks.
   - Removed unused `Predictor` reference in `Keyboard2View`.
2. **Impact**:
   - In emulator heap tests, retained memory per retired service dropped from **~41.0 MiB** to **~552 kB** (a 98.6% reduction in zombie service retention).
   - Even though the native `WindowOnBackInvokedDispatcher` still holds the empty `CleverKeysService` shell, its heavy ~130 MiB dictionary payloads are cleanly severed and eligible for GC.

### B. Required Follow-Up Architectural Mitigations

1. **Process-Scoped / Static Dictionary Cache**:
   - **Rationale**: The English and Italian binary dictionaries and CTC beam search tries are immutable assets packaged inside the APK or downloaded langpacks. Tying their lifecycle to `InputMethodService` is fundamentally flawed; recreation forces completely redundant re-allocation of 100+ MiB.
   - **Fix**: Move the loaded `CtcLexiconTrie` and `OptimizedVocabulary` instances to a singleton/process-scoped repository (`DictionaryRepository` / `ApplicationScope`). Recreated services simply bind to existing in-memory structures with zero allocation overhead.

2. **Cooperative Interruption in Loaders**:
   - **Rationale**: When a reload is triggered or a service is destroyed, active loaders must abort immediately.
   - **Fix**: Check `Thread.currentThread().isInterrupted()` and `coroutineContext.isActive` inside `BinaryDictionaryLoader.load()` and `CtcLexiconTrie.insert()` loops.

3. **Lifecycle-Aware `AsyncDictionaryLoader`**:
   - **Rationale**: In-flight secondary load tasks are currently untracked and can publish results to destroyed coordinators.
   - **Fix**: Tie `AsyncDictionaryLoader` tasks to a structured `CoroutineScope` cancelled in `onDestroy()`.

4. **Fix `KeyboardDimensionsHelper` Listener Accumulation**:
   - **Rationale**: `FoldStateTracker` must not be instantiated ephemerally during height calculations.
   - **Fix**: Retain a single lifecycle-managed `FoldStateTracker` at the service level, or query static window metrics directly without registering indefinite stream listeners.

---

## 5. Verification Commands

To analyze heap dumps generated from devices or test runs without external dependencies:

```bash
# Run shallow memory census of top classes
python3 tools/hprof_analyzer.py <path-to-hprof> --top 30

# Filter census for CleverKeys package objects
python3 tools/hprof_analyzer.py <path-to-hprof> --filter tribixbite

# Trace incoming reference paths to CleverKeysService
python3 tools/hprof_analyzer.py <path-to-hprof> --trace CleverKeysService
```
