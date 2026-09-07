package android.graphics

/**
 * Test-classpath replacement for the SDK stub `android.graphics.RectF` (the [PointF]
 * pattern: both custom runners put compiled test classes BEFORE `android.jar`, so this
 * functional data holder wins at runtime).
 *
 * Needed because `Keyboard2View`'s companion constructs a RectF at class-init time —
 * with the throwing android.jar stub the CLASS was unloadable on the JVM, so nothing
 * view-level (e.g. the custom short-swipe dispatch chain, audit H-1/H-7) could be
 * driven off-device. Mirrors the AOSP data holder as consumed by the drawing code:
 * four public mutable float fields plus the trivial accessors. Do not add behaviour
 * the on-device class does not have.
 */
open class RectF {
    @JvmField var left: Float = 0f
    @JvmField var top: Float = 0f
    @JvmField var right: Float = 0f
    @JvmField var bottom: Float = 0f

    constructor()

    constructor(left: Float, top: Float, right: Float, bottom: Float) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    fun set(left: Float, top: Float, right: Float, bottom: Float) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    fun width(): Float = right - left

    fun height(): Float = bottom - top

    override fun toString(): String = "RectF($left, $top, $right, $bottom)"
}
