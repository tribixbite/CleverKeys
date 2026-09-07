package android.graphics

/**
 * Test-classpath replacement for the SDK stub `android.graphics.PointF`.
 *
 * Every constructor in `android.jar`'s stub throws `RuntimeException("Stub!")`, which made
 * the whole gesture layer ([tribixbite.cleverkeys.ImprovedSwipeGestureRecognizer],
 * [tribixbite.cleverkeys.ProbabilisticKeyDetector]) uninstantiable off-device — the reason
 * the B-1 non-Latin key gate (a hard-coded a–z test that killed swipe typing on every
 * Cyrillic/Greek/Hebrew board) survived unexecuted by any test for months.
 *
 * Both custom runners (`runPureTests`, `runMockTests`) put the compiled test classes BEFORE
 * `android.jar` on the classpath, so this real implementation wins at runtime and the
 * production gesture code runs on the JVM unmodified. It mirrors the AOSP data holder as far
 * as the gesture layer consumes it: two public mutable float fields. Do not add behavior the
 * on-device class does not have.
 */
open class PointF(@JvmField var x: Float, @JvmField var y: Float) {
    constructor() : this(0f, 0f)

    override fun toString(): String = "PointF($x, $y)"
}
