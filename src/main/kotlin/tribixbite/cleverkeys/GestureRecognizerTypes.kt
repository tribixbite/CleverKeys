package tribixbite.cleverkeys

import android.graphics.PointF
import kotlin.collections.List 

/**
 * Result class for swipe data
 */
data class SwipeResult(
    @JvmField val keys: List<KeyboardData.Key>?, // Can be null
    @JvmField val path: List<PointF>?, // Can be null
    @JvmField val timestamps: List<Long>?, // Can be null
    @JvmField val totalDistance: Float,
    @JvmField val isSwipeTyping: Boolean
)
