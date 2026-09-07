package android.graphics

/**
 * Test-classpath replacement for the SDK stub `android.graphics.Paint` (the [PointF]
 * pattern: both custom runners put compiled test classes BEFORE `android.jar`, so this
 * functional implementation wins at runtime and `Theme.Computed`'s paint factories run
 * on the JVM unmodified).
 *
 * The colour state follows REAL Paint semantics — the exact behaviour audit H-6 hinges
 * on: [color]'s setter replaces the FULL ARGB (alpha included), while [alpha]'s setter
 * touches only the top byte. Method descriptors match the SDK where production Kotlin
 * calls them (`setTypeface` returns the set Typeface, builder-style). Only members the
 * code under test touches are modelled; do not add behaviour the on-device class does
 * not have.
 */
open class Paint {

    @JvmField var flags: Int = 0

    constructor()

    constructor(flags: Int) {
        this.flags = flags
    }

    /** Full ARGB colour. Real Paint default is opaque black. */
    var color: Int = 0xFF000000.toInt()

    /** Alpha channel only — reads/writes the top byte of [color], like the real Paint. */
    var alpha: Int
        get() = color ushr 24
        set(value) {
            color = (color and 0x00FFFFFF) or ((value and 0xFF) shl 24)
        }

    var style: Style = Style.FILL
    var strokeWidth: Float = 0f
    var textAlign: Align = Align.LEFT
    var textSize: Float = 0f

    private var _typeface: Typeface? = null

    /** SDK descriptor: (Typeface)Typeface — Kotlin property syntax compiles against it. */
    fun setTypeface(typeface: Typeface?): Typeface? {
        _typeface = typeface
        return typeface
    }

    fun getTypeface(): Typeface? = _typeface

    enum class Style { FILL, STROKE, FILL_AND_STROKE }

    enum class Align { LEFT, CENTER, RIGHT }

    companion object {
        const val ANTI_ALIAS_FLAG = 1
    }
}
