package tribixbite.cleverkeys

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.ArrayAdapter

internal object VoiceImeSwitcher {
    const val PREF_LAST_USED = "voice_ime_last_used"
    const val PREF_KNOWN_IMES = "voice_ime_known"

    /**
     * Switch to the voice ime. This might open a chooser popup. Preferences are
     * used to store the last selected voice ime and to detect whether the
     * chooser popup must be shown. Returns [false] if the detection failed and
     * is unlikely to succeed.
     */
    @JvmStatic
    fun switch_to_voice_ime(
        ims: InputMethodService,
        imm: InputMethodManager,
        prefs: SharedPreferences
    ): Boolean {
        val imes = getVoiceImeList(imm)
        val lastUsed = prefs.getString(PREF_LAST_USED, null)
        val lastKnownImes = prefs.getString(PREF_KNOWN_IMES, null)
        val lastUsedIme = getImeById(imes, lastUsed)

        if (imes.isEmpty()) {
            return false
        }

        if (lastUsed == null || lastKnownImes == null || lastUsedIme == null ||
            lastKnownImes != serializeImeIds(imes)) {
            chooseVoiceImeAndUpdatePrefs(ims, prefs, imes)
        } else {
            switchInputMethod(ims, lastUsedIme)
        }
        return true
    }

    @JvmStatic
    fun choose_voice_ime(
        ims: InputMethodService,
        imm: InputMethodManager,
        prefs: SharedPreferences
    ): Boolean {
        val imes = getVoiceImeList(imm)
        chooseVoiceImeAndUpdatePrefs(ims, prefs, imes)
        return true
    }

    /**
     * Show the voice IME chooser popup and switch to the selected IME.
     * Preferences are updated so that future calls to [switch_to_voice_ime]
     * switch to the newly selected IME.
     */
    private fun chooseVoiceImeAndUpdatePrefs(
        ims: InputMethodService,
        prefs: SharedPreferences,
        imes: List<IME>
    ) {
        val imeDisplayNames = getImeDisplayNames(ims, imes)
        val layouts = ArrayAdapter(ims, android.R.layout.simple_list_item_1, imeDisplayNames)
        val dialog = AlertDialog.Builder(ims)
            .setAdapter(layouts) { _, which ->
                val selected = imes[which]
                prefs.edit()
                    .putString(PREF_LAST_USED, selected.getId())
                    .putString(PREF_KNOWN_IMES, serializeImeIds(imes))
                    .apply()
                switchInputMethod(ims, selected)
            }
            .create()

        if (imeDisplayNames.isEmpty()) {
            dialog.setMessage(ims.resources.getString(R.string.toast_no_voice_input))
        }
        ims.window?.window?.decorView?.windowToken?.let {
            Utils.show_dialog_on_ime(dialog, it)
        }
    }

    private fun switchInputMethod(ims: InputMethodService, ime: IME) {
        if (Build.VERSION.SDK_INT < 28) {
            ims.switchInputMethod(ime.getId())
        } else {
            ims.switchInputMethod(ime.getId(), ime.subtype)
        }
    }

    private fun getImeById(imes: List<IME>, id: String?): IME? {
        if (id != null) {
            for (ime in imes) {
                if (ime.getId() == id) {
                    return ime
                }
            }
        }
        return null
    }

    private fun getImeDisplayNames(ims: InputMethodService, imes: List<IME>): List<String> {
        return imes.map { it.getDisplayName(ims) }
    }

    private fun getVoiceImeList(imm: InputMethodManager): List<IME> {
        val imes = mutableListOf<IME>()
        for (im in imm.enabledInputMethodList) {
            for (imst in imm.getEnabledInputMethodSubtypeList(im, true)) {
                if (imst.mode == "voice") {
                    imes.add(IME(im, imst))
                }
            }
        }
        return imes
    }

    /**
     * The chooser popup is shown whether this string changes.
     */
    private fun serializeImeIds(imes: List<IME>): String {
        return buildString {
            for (ime in imes) {
                append(ime.getId())
                append(',')
            }
        }
    }

    internal class IME(
        val im: InputMethodInfo,
        val subtype: InputMethodSubtype
    ) {
        fun getId(): String = im.id

        /**
         * Localised display name.
         */
        fun getDisplayName(ctx: Context): String {
            var subtypeName = ""
            if (Build.VERSION.SDK_INT >= 14) {
                subtypeName = subtype.getDisplayName(ctx, im.packageName, null).toString()
                if (subtypeName.isNotEmpty()) {
                    subtypeName = " - $subtypeName"
                }
            }
            return im.loadLabel(ctx.packageManager).toString() + subtypeName
        }
    }
}
