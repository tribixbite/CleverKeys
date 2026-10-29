package tribixbite.cleverkeys

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import tribixbite.cleverkeys.stt.MicPermissionActivity
import tribixbite.cleverkeys.stt.VoiceFieldKind
import tribixbite.cleverkeys.stt.VoiceHost
import tribixbite.cleverkeys.stt.VoiceTextOp

/**
 * [VoiceHost] over the keyboard service: classifies the focused field with the keyboard's own
 * routing rules and applies dictation through the same channels typing uses.
 *
 * Field kinds (docs/specs/voice-input.md FR-5, `.claude/skills/ime-key-routing.md`):
 *  - an active inline field (clipboard search/editor/tag, emoji/GIF search) → INLINE, written
 *    through [InlineTextTarget] (the app behind the panel is never touched);
 *  - a password field → PASSWORD (refused);
 *  - `TerminalUtils.isTerminalApp` (terminals, RustDesk, any TYPE_NULL editor) → BLIND: only
 *    `commitText` of final words, never a composing span or a read;
 *  - otherwise TEXT: composing tail + committed words.
 */
internal class VoiceInputBridge(
    override val context: Context,
    private val prefsProvider: () -> SharedPreferences,
    private val editorInfo: () -> EditorInfo?,
    private val inputConnection: () -> InputConnection?,
    private val inlineTarget: () -> InlineTextTarget?,
    private val suggestionBar: () -> SuggestionBar?,
    private val announcer: () -> View?,
    private val config: () -> Config?,
    private val onExternalEdit: () -> Unit,
) : VoiceHost {

    override val prefs: SharedPreferences get() = prefsProvider()

    override fun fieldKind(): VoiceFieldKind {
        if (inlineTarget() != null) return VoiceFieldKind.INLINE
        val info = editorInfo()
        if (SuggestionBar.isPasswordField(info)) return VoiceFieldKind.PASSWORD
        if (TerminalUtils.isTerminalApp(info, config()?.custom_terminal_packages)) return VoiceFieldKind.BLIND
        return VoiceFieldKind.TEXT
    }

    override fun textBefore(kind: VoiceFieldKind): String? = when (kind) {
        VoiceFieldKind.INLINE -> inlineTarget()?.state()?.let { it.text.substring(0, it.lo) }
        VoiceFieldKind.TEXT -> inputConnection()?.getTextBeforeCursor(TEXT_BEFORE_CHARS, 0)?.toString() ?: ""
        // Never read a blind field: RustDesk's hidden field reads placeholder text.
        VoiceFieldKind.BLIND, VoiceFieldKind.PASSWORD -> null
    }

    override fun trailingSpace(): Boolean = config()?.auto_space_after_suggestion ?: false

    override fun apply(kind: VoiceFieldKind, ops: List<VoiceTextOp>): Boolean {
        if (ops.isEmpty()) return true
        return when (kind) {
            VoiceFieldKind.INLINE -> applyInline(ops)
            VoiceFieldKind.TEXT, VoiceFieldKind.BLIND -> applyToApp(kind, ops)
            VoiceFieldKind.PASSWORD -> false
        }
    }

    private fun applyToApp(kind: VoiceFieldKind, ops: List<VoiceTextOp>): Boolean {
        val ic = inputConnection() ?: return false
        // Mutating IC calls report the IPC, not the editor (CLAUDE.md 2026-10-21), so their
        // results carry no information; only a missing connection means the field is gone.
        ic.beginBatchEdit()
        try {
            for (op in ops) when (op) {
                is VoiceTextOp.Commit -> ic.commitText(op.text, 1)
                // A blind field never gets a composing span (the planner never emits one there;
                // this is the backstop).
                is VoiceTextOp.SetComposing -> if (kind == VoiceFieldKind.TEXT) ic.setComposingText(op.text, 1)
                VoiceTextOp.ClearComposing -> if (kind == VoiceFieldKind.TEXT) {
                    ic.setComposingText("", 1)
                    ic.finishComposingText()
                }
            }
        } finally {
            ic.endBatchEdit()
        }
        return true
    }

    private fun applyInline(ops: List<VoiceTextOp>): Boolean {
        val target = inlineTarget() ?: return false
        for (op in ops) {
            if (op !is VoiceTextOp.Commit) continue // inline fields get final words only
            val state = target.state() ?: return false
            if (!target.replace(state.lo, state.hi, op.text)) return false
        }
        return true
    }

    override fun showListening(loading: Boolean, pendingText: String, onStop: () -> Unit, onCancel: () -> Unit) {
        val label = context.getString(if (loading) R.string.voice_status_loading else R.string.voice_status_listening)
        suggestionBar()?.showVoiceStatus(
            status = label,
            pending = pendingText,
            stopDescription = context.getString(R.string.voice_stop_descr),
            cancelDescription = context.getString(R.string.voice_cancel_descr),
            onStop = onStop,
            onCancel = onCancel,
        )
    }

    override fun hideListening() {
        suggestionBar()?.clearVoiceStatus()
    }

    override fun message(text: String) {
        suggestionBar()?.showTemporaryMessage(text, MESSAGE_MILLIS, clearAfter = true)
    }

    override fun announce(text: String) {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am?.isEnabled == true) announcer()?.announceForAccessibility(text)
    }

    override fun requestMicPermission() = MicPermissionActivity.start(context, enableOnGrant = false)

    override fun openVoiceSettings() {
        context.startActivity(
            Intent(context, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SCROLL_TO, SettingsActivity.SCROLL_TO_VOICE_INPUT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun openAppSystemSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun language(): String? = config()?.activeLanguages()?.primary

    override fun onFieldEditedExternally() = onExternalEdit()

    companion object {
        /** Enough context to decide a leading space and sentence case. */
        private const val TEXT_BEFORE_CHARS = 64
        private const val MESSAGE_MILLIS = 3_500L
    }
}
