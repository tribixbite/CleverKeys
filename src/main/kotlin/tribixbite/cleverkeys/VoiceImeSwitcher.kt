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
     * CleverKeys' own voice input as the voice keys see it: [ready] = switched on with a usable
     * pack installed, [label] = its chooser entry, [start] = the `voice_input` key action.
     */
    class BuiltInVoice(val ready: Boolean, val label: String, val start: () -> Unit)

    /** The `voice_typing` key ([VoiceKeyRouting.forVoiceTyping]). */
    @JvmStatic
    fun onVoiceTypingKey(
        ims: InputMethodService,
        imm: InputMethodManager,
        prefs: SharedPreferences,
        builtIn: BuiltInVoice,
    ) {
        val imes = getVoiceImeList(imm)
        when (VoiceKeyRouting.forVoiceTyping(VoiceKeyRouting.Facts(builtIn.ready, imes.size))) {
            VoiceKeyRouting.Route.BUILT_IN -> builtIn.start()
            VoiceKeyRouting.Route.SYSTEM_IME -> switchToVoiceIme(ims, prefs, imes, builtIn)
            VoiceKeyRouting.Route.CHOOSER -> chooseVoiceImeAndUpdatePrefs(ims, prefs, imes, builtIn)
        }
    }

    /** The `voice_typing_chooser` key (the mic key's long press; [VoiceKeyRouting.forChooser]). */
    @JvmStatic
    fun onVoiceChooserKey(
        ims: InputMethodService,
        imm: InputMethodManager,
        prefs: SharedPreferences,
        builtIn: BuiltInVoice,
    ) {
        val imes = getVoiceImeList(imm)
        when (VoiceKeyRouting.forChooser(VoiceKeyRouting.Facts(builtIn.ready, imes.size))) {
            VoiceKeyRouting.Route.BUILT_IN -> builtIn.start()
            VoiceKeyRouting.Route.SYSTEM_IME -> switchToVoiceIme(ims, prefs, imes, builtIn)
            VoiceKeyRouting.Route.CHOOSER -> chooseVoiceImeAndUpdatePrefs(ims, prefs, imes, builtIn)
        }
    }

    /**
     * Switch to the remembered system voice IME. The chooser opens instead when nothing is
     * remembered yet or the set of voice IMEs changed since the last choice.
     */
    private fun switchToVoiceIme(
        ims: InputMethodService,
        prefs: SharedPreferences,
        imes: List<IME>,
        builtIn: BuiltInVoice,
    ) {
        val lastUsed = prefs.getString(PREF_LAST_USED, null)
        val lastKnownImes = prefs.getString(PREF_KNOWN_IMES, null)
        val lastUsedIme = getImeById(imes, lastUsed)
        if (lastUsed == null || lastKnownImes == null || lastUsedIme == null ||
            lastKnownImes != serializeImeIds(imes)) {
            chooseVoiceImeAndUpdatePrefs(ims, prefs, imes, builtIn)
        } else {
            switchInputMethod(ims, lastUsedIme)
        }
    }

    /**
     * Show the voice chooser — the built-in engine first when it is ready
     * ([VoiceKeyRouting.chooserListsBuiltIn]), then every system voice IME — and act on the
     * pick. Picking a system IME is remembered for [switchToVoiceIme].
     */
    private fun chooseVoiceImeAndUpdatePrefs(
        ims: InputMethodService,
        prefs: SharedPreferences,
        imes: List<IME>,
        builtIn: BuiltInVoice,
    ) {
        val listsBuiltIn = VoiceKeyRouting.chooserListsBuiltIn(VoiceKeyRouting.Facts(builtIn.ready, imes.size))
        val names = (if (listsBuiltIn) listOf(builtIn.label) else emptyList()) + getImeDisplayNames(ims, imes)
        val layouts = ArrayAdapter(ims, android.R.layout.simple_list_item_1, names)
        val dialog = AlertDialog.Builder(ims)
            .setAdapter(layouts) { _, which ->
                val index = if (listsBuiltIn) which - 1 else which
                if (index < 0) {
                    builtIn.start()
                    return@setAdapter
                }
                val selected = imes[index]
                prefs.edit()
                    .putString(PREF_LAST_USED, selected.getId())
                    .putString(PREF_KNOWN_IMES, serializeImeIds(imes))
                    .apply()
                switchInputMethod(ims, selected)
            }
            .create()

        // Defensive: the routing never opens an empty chooser (no entry goes to the built-in
        // path, whose message points to Settings → Voice input).
        if (names.isEmpty()) {
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
            // minSdk 24: InputMethodSubtype.getDisplayName (API 14) is always available.
            var subtypeName = subtype.getDisplayName(ctx, im.packageName, null).toString()
            if (subtypeName.isNotEmpty()) {
                subtypeName = " - $subtypeName"
            }
            return im.loadLabel(ctx.packageManager).toString() + subtypeName
        }
    }
}
