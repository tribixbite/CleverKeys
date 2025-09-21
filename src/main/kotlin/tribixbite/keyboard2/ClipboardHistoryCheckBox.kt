package tribixbite.keyboard2

import android.content.Context
import android.util.AttributeSet
import android.widget.CheckBox
import android.widget.CompoundButton

/**
 * Checkbox for enabling/disabling clipboard history functionality
 *
 * Automatically syncs with global configuration and updates the
 * clipboard history service when toggled by the user.
 */
class ClipboardHistoryCheckBox : CheckBox, CompoundButton.OnCheckedChangeListener {

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
        ClipboardHistoryService.set_history_enabled(isChecked)
    }
}