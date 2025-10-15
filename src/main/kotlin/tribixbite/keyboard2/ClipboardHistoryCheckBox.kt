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
 */
class ClipboardHistoryCheckBox : CheckBox, CompoundButton.OnCheckedChangeListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initialize()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        initialize()
    }

    private fun initialize() {
        // Set initial state from global configuration
        isChecked = Config.globalConfig().clipboard_history_enabled
        setOnCheckedChangeListener(this)
    }

    override fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean) {
        // Update clipboard history service when toggled
        scope.launch {
            ClipboardHistoryService.setHistoryEnabled(isChecked)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel() // Cleanup coroutine scope when view is detached
    }
}