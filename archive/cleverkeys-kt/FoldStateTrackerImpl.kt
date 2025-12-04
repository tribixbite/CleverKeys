package tribixbite.cleverkeys

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Real foldable device state detection
 * Kotlin implementation with WindowManager API and device-specific detection
 */
class FoldStateTrackerImpl(private val context: Context) {
    
    companion object {
        private const val TAG = "FoldStateTracker"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var isUnfoldedState = false
    private val foldStateFlow = MutableStateFlow(false)
    
    // Window info tracker for modern fold detection
    private val windowInfoTracker by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                WindowInfoTracker.getOrCreate(context)
            } catch (e: Exception) {
                logE("WindowInfoTracker not available", e)
                null
            }
        } else null
    }
    
    init {
        initializeFoldDetection()
    }
    
    /**
     * Initialize fold detection system
     */
    private fun initializeFoldDetection() {
        scope.launch {
            try {
                // Modern API approach (Android R+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowInfoTracker != null) {
                    detectFoldWithWindowInfo()
                } else {
                    // Fallback approach using display metrics
                    detectFoldWithDisplayMetrics()
                }
            } catch (e: Exception) {
                logE("Fold detection initialization failed", e)
                // Fallback to unfold state detection
                fallbackFoldDetection()
            }
        }
    }
    
    /**
     * Modern fold detection using WindowInfoTracker
     */
    private suspend fun detectFoldWithWindowInfo() {
        if (context is android.app.Activity) {
            windowInfoTracker?.windowLayoutInfo(context)
                ?.collect { layoutInfo ->
                    val isFolded = analyzeFoldState(layoutInfo)
                    updateFoldState(!isFolded)
                }
        }
    }
    
    /**
     * Analyze window layout info for fold state
     */
    private fun analyzeFoldState(layoutInfo: WindowLayoutInfo): Boolean {
        // Check for fold features
        return layoutInfo.displayFeatures.any { feature ->
            // Check if feature indicates folded state
            feature.bounds.width() == 0 || feature.bounds.height() == 0
        }
    }
    
    /**
     * Fallback fold detection using display metrics
     */
    private suspend fun detectFoldWithDisplayMetrics() {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        
        while (scope.isActive) {
            try {
                val metrics = android.util.DisplayMetrics()
                display.getRealMetrics(metrics)
                
                // Heuristic: Very wide screens in landscape might indicate unfolded state
                val aspectRatio = maxOf(metrics.widthPixels, metrics.heightPixels).toFloat() / 
                                 minOf(metrics.widthPixels, metrics.heightPixels).toFloat()
                
                val isLikelyUnfolded = when {
                    aspectRatio > 2.5f -> true // Very wide aspect ratio
                    metrics.widthPixels > 2000 && metrics.heightPixels > 1000 -> true // Large resolution
                    else -> detectDeviceSpecificFoldState()
                }
                
                updateFoldState(isLikelyUnfolded)
                
            } catch (e: Exception) {
                logE("Display metrics fold detection failed", e)
            }
            
            delay(5000) // Check every 5 seconds
        }
    }
    
    /**
     * Device-specific fold state detection
     */
    private fun detectDeviceSpecificFoldState(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase()
        
        return when {
            // Samsung Galaxy Fold series
            manufacturer == "samsung" && (
                model.contains("fold") || 
                model.contains("flip") ||
                model.contains("z fold") ||
                model.contains("z flip")
            ) -> detectSamsungFoldState()
            
            // Google Pixel Fold
            manufacturer == "google" && model.contains("fold") -> detectPixelFoldState()
            
            // Huawei Mate X series
            manufacturer == "huawei" && model.contains("mate x") -> detectHuaweiFoldState()
            
            // Surface Duo
            manufacturer == "microsoft" && model.contains("surface duo") -> detectSurfaceDuoState()
            
            else -> false // Assume non-foldable
        }
    }
    
    /**
     * Samsung-specific fold detection
     */
    private fun detectSamsungFoldState(): Boolean {
        return try {
            // Try Samsung-specific APIs if available
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val displays = displayManager.displays
            displays.size > 1 // Multiple displays might indicate unfolded state
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Pixel Fold detection
     */
    private fun detectPixelFoldState(): Boolean {
        // Google Pixel Fold specific detection
        return try {
            val metrics = context.resources.displayMetrics
            val screenSizeInches = kotlin.math.sqrt(
                (metrics.widthPixels / metrics.xdpi).toDouble().pow(2) +
                (metrics.heightPixels / metrics.ydpi).toDouble().pow(2)
            )
            screenSizeInches > 7.0 // Large screen suggests unfolded
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Huawei Mate X detection
     */
    private fun detectHuaweiFoldState(): Boolean {
        // Huawei-specific detection would go here
        return false
    }
    
    /**
     * Surface Duo detection
     */
    private fun detectSurfaceDuoState(): Boolean {
        // Surface Duo specific detection
        return try {
            val metrics = context.resources.displayMetrics
            val aspectRatio = metrics.widthPixels.toFloat() / metrics.heightPixels.toFloat()
            aspectRatio > 1.8f // Wide aspect ratio suggests dual screen
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Fallback fold detection
     */
    private fun fallbackFoldDetection() {
        scope.launch {
            // Simple heuristic based on screen size
            val metrics = context.resources.displayMetrics
            val screenSizeInches = kotlin.math.sqrt(
                (metrics.widthPixels / metrics.xdpi).toDouble().pow(2) +
                (metrics.heightPixels / metrics.ydpi).toDouble().pow(2)
            )
            
            // Assume large screens are unfolded foldables or tablets
            val isLikelyUnfolded = screenSizeInches > 6.5
            updateFoldState(isLikelyUnfolded)
        }
    }
    
    /**
     * Update fold state
     */
    private fun updateFoldState(unfolded: Boolean) {
        if (isUnfoldedState != unfolded) {
            isUnfoldedState = unfolded
            foldStateFlow.value = unfolded
            logD("Fold state changed: ${if (unfolded) "UNFOLDED" else "FOLDED"}")
        }
    }
    
    /**
     * Get current fold state
     */
    fun isUnfolded(): Boolean = isUnfoldedState
    
    /**
     * Get fold state as Flow for reactive updates
     */
    fun getFoldStateFlow(): StateFlow<Boolean> = foldStateFlow.asStateFlow()
    
    /**
     * Cleanup
     */
    fun cleanup() {
        scope.cancel()
    }
}