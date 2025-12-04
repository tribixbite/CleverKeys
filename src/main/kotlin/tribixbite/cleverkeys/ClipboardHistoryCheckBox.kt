package tribixbite.cleverkeys

import android.content.Context
import android.util.AttributeSet
import android.widget.CheckBox
import android.widget.CompoundButton

class ClipboardHistoryCheckBox(
    ctx: Context,
    attrs: AttributeSet
) : CheckBox(ctx, attrs), CompoundButton.OnCheckedChangeListener {

    init {
        isChecked = Config.globalConfig().clipboard_history_enabled
        setOnCheckedChangeListener(this)
    }

    override fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean) {
        ClipboardHistoryService.set_history_enabled(isChecked)
    }
}
