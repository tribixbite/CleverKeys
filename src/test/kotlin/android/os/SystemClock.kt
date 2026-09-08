package android.os

/**
 * Test-classpath replacement for the SDK stub `android.os.SystemClock` (the
 * `android.util.LruCache` pattern: both custom runners put compiled test classes
 * BEFORE `android.jar`, so this functional shadow wins at runtime).
 *
 * Needed because `elapsedRealtime()` is a NATIVE method in the SDK stub jar —
 * calling it on the JVM dies with `UnsatisfiedLinkError`, and MockK's
 * `mockkStatic` cannot instrument native methods either. `IMEStatusHelper`'s
 * once-per-boot guard (I-7) derives the boot instant from it, so tests pin the
 * uptime via [elapsed] instead.
 *
 * `open` per the shadow rule (a final shadow breaks android.jar subclass
 * loading — the TextPaint:Paint lesson). Do not add behaviour the on-device
 * class does not have.
 */
open class SystemClock {
    companion object {
        /** The fake uptime returned by [elapsedRealtime]; tests set this directly. */
        @JvmStatic
        var elapsed: Long = 0L

        @JvmStatic
        fun elapsedRealtime(): Long = elapsed
    }
}
