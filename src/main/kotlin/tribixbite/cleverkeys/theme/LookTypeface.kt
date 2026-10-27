package tribixbite.cleverkeys.theme

import android.graphics.Typeface
import android.os.Build

/**
 * The main-label typeface of a keyboard look's [LookGeometry.labelWeight]: the system sans-serif
 * at that weight. No product font is bundled (SF Pro, Google Sans, Samsung One and SwiftKey's
 * faces are their owners'), so a look reproduces weight and size, not the face.
 */
object LookTypeface {
    private val cache = HashMap<Int, Typeface>()

    /** null (the label paint's default face) when [weight] is null or regular (400). */
    fun forWeight(weight: Int?): Typeface? {
        if (weight == null || weight == REGULAR) return null
        return synchronized(cache) {
            cache.getOrPut(weight) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Typeface.create(Typeface.SANS_SERIF, weight, false)
                } else {
                    // Before Android 9 only regular and bold exist.
                    if (weight >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }
            }
        }
    }

    private const val REGULAR = 400
}
