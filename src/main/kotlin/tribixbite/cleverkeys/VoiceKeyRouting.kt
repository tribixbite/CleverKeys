package tribixbite.cleverkeys

/**
 * Where the voice keys go: the ONE routing rule for `voice_typing` (the mic key, also the
 * default extra key) and `voice_typing_chooser` (its long press), now that CleverKeys has its
 * own on-device voice input (docs/specs/voice-input.md "Voice keys").
 *
 * Until 2026-10-30 both keys only switched to a system voice IME (an enabled input-method
 * subtype with mode "voice"), so the built-in voice input was reachable only from the separate
 * `voice_input` key: with Google voice typing disabled the mic key showed "No voice typing app
 * installed" although a voice pack was installed (maintainer report).
 *
 * Pure (no Android types): [VoiceImeSwitcher] supplies the facts and performs the route.
 */
object VoiceKeyRouting {

    /** What a voice key press does. */
    enum class Route {
        /** Start/stop CleverKeys' own voice input (its gate explains anything missing). */
        BUILT_IN,
        /** Switch to the remembered system voice IME (or pick one first, as before). */
        SYSTEM_IME,
        /** Show the chooser: the built-in engine (when ready) and every system voice IME. */
        CHOOSER,
    }

    /**
     * @property builtInReady voice input is switched on in Settings AND a usable voice pack is
     *   installed. The microphone permission is not part of it: the built-in path asks for it.
     * @property systemVoiceImes enabled system voice-IME subtypes.
     */
    data class Facts(val builtInReady: Boolean, val systemVoiceImes: Int)

    /**
     * `voice_typing`: the built-in engine whenever it is ready; otherwise a system voice IME as
     * before; with neither, the built-in path too — its message says what is missing (voice
     * input off, or no pack) and points to Settings → Voice input, instead of the old dead-end
     * "No voice typing app installed".
     */
    fun forVoiceTyping(f: Facts): Route = when {
        f.builtInReady -> Route.BUILT_IN
        f.systemVoiceImes > 0 -> Route.SYSTEM_IME
        else -> Route.BUILT_IN
    }

    /**
     * `voice_typing_chooser`: a chooser only when there is a choice — two or more entries. One
     * entry is taken directly; none goes to the built-in path for its Settings message.
     */
    fun forChooser(f: Facts): Route = when {
        chooserEntries(f) >= 2 -> Route.CHOOSER
        f.systemVoiceImes == 1 && !f.builtInReady -> Route.SYSTEM_IME
        else -> Route.BUILT_IN
    }

    /** Whether the chooser lists the built-in engine (first): only when it can start. */
    fun chooserListsBuiltIn(f: Facts): Boolean = f.builtInReady

    private fun chooserEntries(f: Facts): Int = f.systemVoiceImes + if (f.builtInReady) 1 else 0
}
