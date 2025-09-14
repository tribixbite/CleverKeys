package juloo.keyboard2

import android.content.Context

/**
 * Tracks foldable device state
 * Kotlin implementation delegating to complete implementation
 */
class FoldStateTracker(private val context: Context) {
    
    private val impl = FoldStateTrackerImpl(context)
    
    /**
     * Check if device is unfolded
     */
    fun isUnfolded(): Boolean = impl.isUnfolded()
    
    /**
     * Get fold state flow for reactive updates
     */
    fun getFoldStateFlow() = impl.getFoldStateFlow()
    
    /**
     * Cleanup
     */
    fun cleanup() = impl.cleanup()
}