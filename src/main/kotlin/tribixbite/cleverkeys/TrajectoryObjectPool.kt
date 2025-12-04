package tribixbite.cleverkeys

import android.graphics.PointF
import java.util.ArrayDeque

/**
 * Object pool for trajectory processing to reduce GC pressure.
 *
 * This pool manages reusable objects commonly allocated during swipe processing:
 * - PointF objects for coordinates
 * - TrajectoryPoint objects for features
 * - ArrayList instances for collections
 *
 * Thread safety: NOT thread-safe. Use from single thread only (UI thread).
 */
object TrajectoryObjectPool {
    private const val INITIAL_POOL_SIZE = 200
    private const val MAX_POOL_SIZE = 500

    // Pools for different object types
    private val pointFPool = ArrayDeque<PointF>(INITIAL_POOL_SIZE)
    private val trajectoryPointPool = ArrayDeque<SwipeTrajectoryProcessor.TrajectoryPoint>(INITIAL_POOL_SIZE)
    private val intListPool = ArrayDeque<ArrayList<Int>>(20)
    private val pointFListPool = ArrayDeque<ArrayList<PointF>>(20)
    private val longListPool = ArrayDeque<ArrayList<Long>>(20)

    init {
        // Pre-populate pools with common sizes
        repeat(INITIAL_POOL_SIZE) {
            pointFPool.add(PointF())
            trajectoryPointPool.add(SwipeTrajectoryProcessor.TrajectoryPoint())
        }

        repeat(10) {
            intListPool.add(ArrayList(150))
            pointFListPool.add(ArrayList(150))
            longListPool.add(ArrayList(150))
        }
    }

    /**
     * Obtain a PointF from the pool. Caller must call recyclePointF() when done.
     */
    fun obtainPointF(): PointF {
        return pointFPool.pollFirst() ?: PointF()
    }

    /**
     * Obtain a PointF with coordinates. Caller must call recyclePointF() when done.
     */
    fun obtainPointF(x: Float, y: Float): PointF {
        val point = obtainPointF()
        point.set(x, y)
        return point
    }

    /**
     * Recycle a PointF back to the pool.
     */
    fun recyclePointF(point: PointF) {
        if (pointFPool.size < MAX_POOL_SIZE) {
            point.set(0f, 0f) // Reset for next use
            pointFPool.addLast(point)
        }
    }

    /**
     * Recycle a list of PointF objects.
     */
    fun recyclePointFList(points: List<PointF>) {
        for (point in points) {
            recyclePointF(point)
        }
    }

    /**
     * Obtain a TrajectoryPoint from the pool. Caller must call recycleTrajectoryPoint() when done.
     */
    fun obtainTrajectoryPoint(): SwipeTrajectoryProcessor.TrajectoryPoint {
        return trajectoryPointPool.pollFirst() ?: SwipeTrajectoryProcessor.TrajectoryPoint()
    }

    /**
     * Recycle a TrajectoryPoint back to the pool.
     */
    fun recycleTrajectoryPoint(point: SwipeTrajectoryProcessor.TrajectoryPoint) {
        if (trajectoryPointPool.size < MAX_POOL_SIZE) {
            // Reset to zero
            point.x = 0f
            point.y = 0f
            point.vx = 0f
            point.vy = 0f
            point.ax = 0f
            point.ay = 0f
            trajectoryPointPool.addLast(point)
        }
    }

    /**
     * Recycle a list of TrajectoryPoint objects.
     */
    fun recycleTrajectoryPointList(points: List<SwipeTrajectoryProcessor.TrajectoryPoint>) {
        for (point in points) {
            recycleTrajectoryPoint(point)
        }
    }

    /**
     * Obtain an ArrayList<Int> from the pool. Caller must call recycleIntList() when done.
     * The list is cleared before being returned.
     */
    fun obtainIntList(initialCapacity: Int = 150): ArrayList<Int> {
        val list = intListPool.pollFirst() ?: ArrayList(initialCapacity)
        list.clear()
        if (list.size < initialCapacity) {
            list.ensureCapacity(initialCapacity)
        }
        return list
    }

    /**
     * Recycle an ArrayList<Int> back to the pool.
     */
    fun recycleIntList(list: ArrayList<Int>) {
        if (intListPool.size < 20) {
            list.clear()
            intListPool.addLast(list)
        }
    }

    /**
     * Obtain an ArrayList<PointF> from the pool. Caller must call recyclePointFArrayList() when done.
     * The list is cleared before being returned.
     */
    fun obtainPointFArrayList(initialCapacity: Int = 150): ArrayList<PointF> {
        val list = pointFListPool.pollFirst() ?: ArrayList(initialCapacity)
        list.clear()
        if (list.size < initialCapacity) {
            list.ensureCapacity(initialCapacity)
        }
        return list
    }

    /**
     * Recycle an ArrayList<PointF> back to the pool.
     * Note: This does NOT recycle the PointF objects themselves - call recyclePointFList() separately if needed.
     */
    fun recyclePointFArrayList(list: ArrayList<PointF>) {
        if (pointFListPool.size < 20) {
            list.clear()
            pointFListPool.addLast(list)
        }
    }

    /**
     * Obtain an ArrayList<Long> from the pool. Caller must call recycleLongList() when done.
     * The list is cleared before being returned.
     */
    fun obtainLongList(initialCapacity: Int = 150): ArrayList<Long> {
        val list = longListPool.pollFirst() ?: ArrayList(initialCapacity)
        list.clear()
        if (list.size < initialCapacity) {
            list.ensureCapacity(initialCapacity)
        }
        return list
    }

    /**
     * Recycle an ArrayList<Long> back to the pool.
     */
    fun recycleLongList(list: ArrayList<Long>) {
        if (longListPool.size < 20) {
            list.clear()
            longListPool.addLast(list)
        }
    }

    /**
     * Clear all pools (useful for testing or memory pressure situations).
     */
    fun clearAll() {
        pointFPool.clear()
        trajectoryPointPool.clear()
        intListPool.clear()
        pointFListPool.clear()
        longListPool.clear()
    }

    /**
     * Get pool statistics for debugging.
     */
    fun getStats(): String {
        return "TrajectoryObjectPool: PointF=${pointFPool.size}, " +
                "TrajectoryPoint=${trajectoryPointPool.size}, " +
                "IntList=${intListPool.size}, " +
                "PointFList=${pointFListPool.size}, " +
                "LongList=${longListPool.size}"
    }
}
