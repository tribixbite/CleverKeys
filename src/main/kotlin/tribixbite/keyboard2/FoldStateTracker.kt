package tribixbite.keyboard2

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.util.Consumer
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo

class FoldStateTracker(context: Context) {
    private val windowInfoTracker: WindowInfoTrackerCallbackAdapter
    private val innerListener: Consumer<WindowLayoutInfo>
    private var foldingFeature: FoldingFeature? = null
    private var changedCallback: Runnable? = null

    init {
        windowInfoTracker = WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(context))
        innerListener = LayoutStateChangeCallback()
        windowInfoTracker.addWindowLayoutInfoListener(context, Runnable::run, innerListener)
    }

    fun isUnfolded(): Boolean {
        // FoldableFeature is only present when the device is unfolded. Otherwise, it's removed.
        // A weird decision from Google, but that's how it works:
        // https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:window/window/src/main/java/androidx/window/layout/adapter/sidecar/SidecarAdapter.kt;l=187?q=SidecarAdapter
        return foldingFeature != null
    }

    fun close() {
        windowInfoTracker.removeWindowLayoutInfoListener(innerListener)
    }

    fun setChangedCallback(changedCallback: Runnable?) {
        this.changedCallback = changedCallback
    }

    inner class LayoutStateChangeCallback : Consumer<WindowLayoutInfo> {
        override fun accept(newLayoutInfo: WindowLayoutInfo) {
            val old = foldingFeature
            foldingFeature = null
            for (feature in newLayoutInfo.displayFeatures) {
                if (feature is FoldingFeature) {
                    foldingFeature = feature
                }
            }

            if (old != foldingFeature && changedCallback != null) {
                changedCallback?.run()
            }
        }
    }

    companion object {
        @JvmStatic
        fun isFoldableDevice(context: Context): Boolean {
            return context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)
        }
    }
}
