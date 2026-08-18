# On-device memory phase table (2026-08-18)

Captured from `MemoryProbe` on the Saga (Android 16, `S938U1UESBCZF5`), APK installed
2026-08-17 11:16:59, process `11470` started 2026-08-17 22:17. Read with
`adb logcat -d -b all | rg CKMemProbe`.

This is the table the probe was added to produce. It is recorded here so the probe's
`settle = true` passes can be removed — see "Status of the probe" below, which is **not** a
clean "delete it now".

## The table

All rows are **settled** measurements (`= ` prefix): 2×GC + 2×120 ms before reading, so each
`used` is a post-collection figure and each `delta` approximates what the phase actually
RETAINS rather than what it allocated.

| phase | used | delta | thread | detail |
|---|---|---|---|---|
| `primary.dictionary` | 121.0 MB | **−48.0 MB** | main | `lang=en words=98408 prefixes=6026 setEntries=294591` |
| `ctc.baseParse` | 126.9 MB | +5.9 MB | pool-10-thread-1 | `lang=en entries=98140` |
| `ctc.mergeAndOrdinals` | 133.8 MB | +6.9 MB | pool-10-thread-1 | `merged=98402` |
| `ctc.trie` | 143.7 MB | **+9.9 MB** | pool-10-thread-1 | `lang=en words=98719 nodes=231779 injectedAliases=387 display=0` |

Limit throughout: 256.0 MB.

### What the numbers say

* **The trie rewrite is validated on device.** The English CTC trie is **9.9 MB at 231,779
  nodes**. The pre-fix `LinkedHashMap`-per-node layout was computed at **42.2 MB for 230,787
  nodes** — so the two lazily-allocated parallel arrays are a measured **~4.3× reduction** on
  essentially the same node count. This is the single largest item in `8230333b` and it holds up.
* **The −48.0 MB on `primary.dictionary` is not a saving**, it is an artifact of settling: the
  2×GC collected 48 MB more garbage than that phase retained, meaning the phases *before* it
  left a large volume of short-lived allocation behind. It says nothing about the dictionary's
  own cost.
* **CTC's whole share of startup is ~22.7 MB** (5.9 + 6.9 + 9.9) and all of it is off the main
  thread (`pool-10-thread-1`).

### What the table does NOT cover — read this before deleting the probe

**The capture is incomplete.** Only four marks fired. There is no row for the secondary
dictionary, for the neural stack, for the contraction load, or for any non-English language —
and the secondary (`it`) is configured on this device. The single largest open question from
the OOM work (how much the eagerly-loaded neural stack actually held: the estimate was a
tag-and-timeline inference of ~30–45 MB, never isolated) is **still unmeasured**.

## Crash-buffer state, same capture

Both the "recent crash" question and the ANR hypothesis resolve here.

* **No ANR.** Zero `ANR in tribixbite.cleverkeys` in `-b main` across the whole buffer. The
  `MemoryProbe` settle passes (~2.4 s of main-thread blocking in `onCreate`) have **not**
  produced one, though the risk was real and correctly identified.
* **The startup OOM is fixed.** No post-install `OutOfMemoryError` at `onCreate` /
  `loadSecondaryDictionary` / `loadV2IntoNormalizedIndex`. The last one of those is
  **08-17 11:13:25**, which is **3.5 minutes BEFORE** the current APK was installed (11:16:59).
* **Most of the crash buffer is not ours.** Entries at 04:55, 06:36, 08:10, 13:45, 17:28 and
  21:56 are `xfwm4` (Termux X11 / zink assertion), `com.termux.api`, `com.samsung.android.lool`
  and Edge. Only two entries in the buffer are CleverKeys.
* **⚠ One CleverKeys OOM DID occur after the fix: 08-17 16:09:14.** Process uptime 17,535 s
  places its start at **11:17:00** — i.e. it *is* the fixed build, and it ran 4.87 hours before
  dying. The abort message is
  `Uncaught exception in CornerRadiiCallback. java.lang.OutOfMemoryError: Failed to allocate a
  32 byte allocation with 35616 free bytes … <1% of heap free after GC`, and the backtrace is
  **entirely system frames** — `nativeSetCornerRadiiCallback` → `BLASTBufferQueue::
  transactionCallback` → `TransactionCompletedListener::onTransactionCompleted` → binder. **Zero
  CleverKeys frames.** A 32-byte failure with no app frames means the heap was already exhausted
  and the crash site is incidental: whoever allocated next took the fall. This is a *different
  failure mode* from the original (which died inside dictionary loading during `onCreate`).

### Against that, the current process looks healthy

| | value |
|---|---|
| process uptime | **6 h 02 m** |
| Dalvik Heap Alloc | **133,086 KB (130.0 MiB)** |
| Dalvik Heap Size | 182,238 KB |
| headroom to the 256 MiB limit | **126 MiB (49%)** |
| Native Heap Alloc | 93,240 KB |

Startup finished at 143.7 MB (probe) and six hours of use later the allocation is **130.0 MB** —
flat to slightly down, not climbing. So whatever exhausted the 11:17 process is **not
reproducing** in the current one.

## Open question

One post-fix OOM at 4.87 h against one 6 h process that is stable. Two readings fit:

1. The 11:17 process was subjected to atypical load — that window is exactly when the agent was
   iterating on device, repeatedly rebuilding tries and switching languages. Repeated language
   switching is the `MultiLanguageManager.modelCache` path (~29 MB per language, unbounded before
   `8230333b`, bounded to 1 by it). If the installed APK predates that particular bound, this is
   explained and already fixed in source.
2. There is a residual leak on a path the current session has not exercised.

**These are distinguishable**, and cheaply: confirm whether the installed APK contains all four
fixes or is an intermediate build (`lastUpdateTime` records only the last install, and the agent
iterated). Until that is settled, do not treat "the crash buffer is empty" as established — it
was not, and the agent's report said otherwise.

## Status of the probe

**Do not delete it yet.** The table above is partial, and there is one unexplained OOM. The
correct next step is to **strip the `settle = true` passes** — that removes the ~2.4 s of
main-thread blocking and the ANR risk it carries, at the cost of turning the deltas into
allocation figures rather than retention figures, while keeping the instrumentation available to
answer the two questions above. The probe is `BuildConfig.ENABLE_VERBOSE_LOGGING`-gated and
compiles to a static-boolean guard in release, so it costs shipped users nothing either way.
