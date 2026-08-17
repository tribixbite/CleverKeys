package tribixbite.cleverkeys

import android.util.Log

/**
 * Java-heap probe used to ATTRIBUTE the IME's startup memory to individual
 * initialization phases.
 *
 * ## Why this exists
 *
 * CleverKeys crashed repeatedly with `OutOfMemoryError` inside
 * [WordPredictor.loadSecondaryDictionary] (9 fatal crashes between 2026-08-12 and
 * 2026-08-17 on a 256 MB-growth-limit device). The allocation that failed was 16–32
 * bytes: the secondary-dictionary load was merely the LAST straw on an already-full
 * heap, not the consumer. Diagnosing that requires knowing which init phase actually
 * retains the heap, which no crash stack can tell you.
 *
 * [mark] writes one line per phase with the used heap and the delta since the previous
 * mark, so `adb logcat -s CKMemProbe` yields a ranked attribution table for a real
 * device in the user's real configuration.
 *
 * ## Measurement semantics
 *
 * - `used = totalMemory() - freeMemory()` is the ART Java heap, the exact quantity the
 *   growth limit bounds and the quantity `dumpsys meminfo`'s "Dalvik Heap Alloc"
 *   reports. It does NOT include the native heap (ONNX sessions, bitmaps).
 * - An UNSETTLED mark includes not-yet-collected garbage, so its delta is an upper
 *   bound on what the phase RETAINS. Pass `settle = true` at phase boundaries that
 *   matter: it runs two GC passes so the delta approximates retained bytes. Settling
 *   costs ~2 × [SETTLE_PAUSE_MS] and is therefore only ever done in verbose builds.
 * - Concurrency: dictionary loads run on background threads, so marks taken on
 *   different threads can interleave. Every line carries its thread name; attribute
 *   per-thread deltas, not wall-clock ones, when loads overlap.
 *
 * ## Cost when disabled
 *
 * [enabled] is `BuildConfig.ENABLE_VERBOSE_LOGGING`, false for CI/F-Droid release
 * builds. Every entry point returns immediately on that flag before touching
 * [Runtime] or evaluating the `detail` lambda, so a production build pays one static
 * boolean read per call site.
 */
object MemoryProbe {

    private const val TAG = "CKMemProbe"

    /** Pause after each explicit GC so ART's collector actually finishes a cycle. */
    private const val SETTLE_PAUSE_MS = 120L

    /** GC passes per settled measurement (a second pass collects newly-unreachable finalizables). */
    private const val SETTLE_PASSES = 2

    private const val BYTES_PER_MB = 1024.0 * 1024.0

    /** Shared no-detail lambda so disabled call sites never allocate. */
    private val NO_DETAIL: () -> String = { "" }

    /**
     * True when probing is compiled in. Callers that would do non-trivial work to build
     * a detail string should check this first.
     */
    @JvmStatic
    val enabled: Boolean = BuildConfig.ENABLE_VERBOSE_LOGGING

    /** Used Java heap in bytes, without forcing a collection. */
    @JvmStatic
    fun usedBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /** The growth limit this process's Java heap may not exceed, in bytes. */
    @JvmStatic
    fun limitBytes(): Long = Runtime.getRuntime().maxMemory()

    /**
     * Used Java heap after [SETTLE_PASSES] explicit collections — an approximation of
     * RETAINED bytes.
     *
     * Restores the interrupt flag and returns early if the settle sleep is interrupted,
     * so a probe can never swallow a cancellation signal from the dictionary loaders.
     */
    @JvmStatic
    fun settledUsedBytes(): Long {
        val runtime = Runtime.getRuntime()
        repeat(SETTLE_PASSES) {
            runtime.gc()
            try {
                Thread.sleep(SETTLE_PAUSE_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return usedBytes()
            }
        }
        return usedBytes()
    }

    /** Used-heap value at the previous [mark], or -1 before the first one. */
    @Volatile
    private var previousUsedBytes: Long = -1L

    /**
     * Log one attribution line for [phase].
     *
     * @param phase short stable identifier, e.g. `"wordPredictor.primary:en"`.
     * @param settle run [settledUsedBytes] instead of [usedBytes] so the reported delta
     *   approximates retained rather than allocated bytes. Use at phase boundaries only.
     * @param detail lazily-built extra context (entry counts, language codes); evaluated
     *   only when [enabled].
     */
    @JvmStatic
    @JvmOverloads
    fun mark(phase: String, settle: Boolean = false, detail: () -> String = NO_DETAIL) {
        if (!enabled) return
        val used = if (settle) settledUsedBytes() else usedBytes()
        val previous = previousUsedBytes
        previousUsedBytes = used
        val delta = if (previous < 0L) 0L else used - previous
        val extra = detail()
        Log.i(
            TAG,
            "${if (settle) "=" else "~"} $phase " +
                "used=${mb(used)}MB delta=${signedMb(delta)}MB " +
                "limit=${mb(limitBytes())}MB thread=${Thread.currentThread().name}" +
                if (extra.isEmpty()) "" else " | $extra"
        )
    }

    /**
     * Log a structure census row: a named structure, how many entries it holds, and the
     * per-entry cost model's estimate of its retained bytes.
     *
     * The estimates come from [EntryCost]; they exist so a phase delta can be checked
     * against an independent analytic count rather than trusted on its own.
     */
    @JvmStatic
    fun census(structure: String, entries: Long, bytesPerEntry: Int, detail: String = "") {
        if (!enabled) return
        Log.i(
            TAG,
            "# $structure entries=$entries " +
                "est=${mb(entries * bytesPerEntry)}MB (@${bytesPerEntry}B/entry)" +
                if (detail.isEmpty()) "" else " | $detail"
        )
    }

    /**
     * Per-entry retained-byte costs on ART (64-bit device, 4-byte object references —
     * ART uses 32-bit heap references regardless of process bitness).
     *
     * Derived from the ART object layout: 8-byte header (class word + lock word) rounded
     * to 8-byte alignment, plus declared fields, plus the containing table's amortized
     * slot at HashMap's 0.75 load factor (~5.3 B per entry at 4 B/slot).
     */
    object EntryCost {
        /** `HashMap.Node` (32 B) + amortized table slot (~5 B). */
        const val HASH_MAP_ENTRY = 37

        /** `LinkedHashMap.Entry` (40 B: Node + before/after) + amortized table slot. */
        const val LINKED_HASH_MAP_ENTRY = 45

        /** A boxed `Integer`/`Double` value outside the small-value cache. */
        const val BOXED_NUMBER = 16

        /** A short ASCII `String` (ART compresses Latin-1): header + count + ~8 chars. */
        const val SHORT_STRING = 32
    }

    /** Resets the delta baseline (used by tests and by a fresh init sequence). */
    @JvmStatic
    fun reset() {
        previousUsedBytes = -1L
    }

    private fun mb(bytes: Long): String = String.format("%.1f", bytes / BYTES_PER_MB)

    private fun signedMb(bytes: Long): String =
        (if (bytes >= 0) "+" else "") + String.format("%.1f", bytes / BYTES_PER_MB)
}
