package tribixbite.keyboard2

import android.content.Context
import android.util.AttributeSet
import android.widget.CheckBox
import android.widget.CompoundButton
import kotlinx.coroutines.*

/**
 * Checkbox for enabling/disabling clipboard history functionality
 *
 * Automatically syncs with global configuration and updates the
 * clipboard history service when toggled by the user.
 *
 * Bug #131 fix: Replaced GlobalScope with view-scoped coroutine to prevent leaks
 * Bug #122 fix: Added updateData() to refresh state from config
 * Bug #123 fix: Added onAttachedToWindow() lifecycle hook
 */
class ClipboardHistoryCheckBox : CheckBox, CompoundButton.OnCheckedChangeListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var isUpdatingFromConfig = false

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initialize()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        initialize()
    }

    private fun initialize() {
        // Set initial state from global configuration
        updateData()
        setOnCheckedChangeListener(this)
    }

    /**
     * Bug #122 fix: Refresh checkbox state from global configuration
     *
     * This method updates the UI to reflect the current config state,
     * useful when the setting is changed externally (e.g., from SettingsActivity)
     */
    fun updateData() {
        isUpdatingFromConfig = true
        isChecked = Config.globalConfig().clipboard_history_enabled
        isUpdatingFromConfig = false
    }

    override fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean) {
        // Ignore programmatic updates to prevent infinite loops
        if (isUpdatingFromConfig) return

        // Update clipboard history service when toggled by user
        scope.launch {
            ClipboardHistoryService.setHistoryEnabled(isChecked)
        }
    }

    /**
     * Bug #123 fix: Refresh state when view is attached to window
     *
     * This ensures the checkbox reflects the current config state
     * when the view becomes visible, handling cases where the config
     * changed while the view was detached.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateData()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel() // Cleanup coroutine scope when view is detached
    }
}