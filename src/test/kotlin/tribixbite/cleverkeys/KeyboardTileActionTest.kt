package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The Quick Settings tile's decision table ([KeyboardTileDecision]): which action a tap takes
 * for each IME/window state, which subtitle and TalkBack description announce it, and how the
 * service's raw signals map to a window state. Pure tier: no Android types are touched.
 */
class KeyboardTileActionTest {

    // =========================================================================
    // actionFor
    // =========================================================================

    @Test
    fun anotherKeyboardSelected_alwaysOpensThePicker() {
        // Whatever stale state a CleverKeys service might report, the tile's job here is the
        // switch to CleverKeys.
        for (window in ImeWindowState.values().toList() + null) {
            assertWithMessage("not the current IME, window=$window")
                .that(KeyboardTileDecision.actionFor(isCurrentIme = false, window = window))
                .isEqualTo(KeyboardTileAction.PICK_KEYBOARD)
        }
    }

    @Test
    fun currentIme_togglesTheKeyboard() {
        assertWithMessage("shown -> minimize")
            .that(KeyboardTileDecision.actionFor(true, ImeWindowState.SHOWN))
            .isEqualTo(KeyboardTileAction.MINIMIZE)
        assertWithMessage("minimized -> restore")
            .that(KeyboardTileDecision.actionFor(true, ImeWindowState.MINIMIZED))
            .isEqualTo(KeyboardTileAction.RESTORE)
        assertWithMessage("hidden with a bound editor -> show")
            .that(KeyboardTileDecision.actionFor(true, ImeWindowState.HIDDEN_WITH_EDITOR))
            .isEqualTo(KeyboardTileAction.SHOW)
    }

    @Test
    fun currentIme_withNothingToShow_fallsBackToThePicker() {
        assertWithMessage("no editor: nothing can be shown")
            .that(KeyboardTileDecision.actionFor(true, ImeWindowState.NO_EDITOR))
            .isEqualTo(KeyboardTileAction.PICK_KEYBOARD)
        assertWithMessage("service not running in this process")
            .that(KeyboardTileDecision.actionFor(true, null))
            .isEqualTo(KeyboardTileAction.PICK_KEYBOARD)
    }

    // =========================================================================
    // windowStateFor
    // =========================================================================

    @Test
    fun windowState_shownWinsOverTheEditorSignals() {
        for (started in listOf(false, true)) for (conn in listOf(false, true)) {
            assertWithMessage("shown full, started=$started conn=$conn")
                .that(KeyboardTileDecision.windowStateFor(true, false, started, conn))
                .isEqualTo(ImeWindowState.SHOWN)
            assertWithMessage("shown minimized, started=$started conn=$conn")
                .that(KeyboardTileDecision.windowStateFor(true, true, started, conn))
                .isEqualTo(ImeWindowState.MINIMIZED)
        }
    }

    @Test
    fun windowState_hiddenNeedsBothAStartedInputAndAConnectionToShow() {
        assertWithMessage("started + connection")
            .that(KeyboardTileDecision.windowStateFor(false, false, true, true))
            .isEqualTo(ImeWindowState.HIDDEN_WITH_EDITOR)
        assertWithMessage("started, no connection")
            .that(KeyboardTileDecision.windowStateFor(false, false, true, false))
            .isEqualTo(ImeWindowState.NO_EDITOR)
        assertWithMessage("connection, input not started")
            .that(KeyboardTileDecision.windowStateFor(false, false, false, true))
            .isEqualTo(ImeWindowState.NO_EDITOR)
        assertWithMessage("neither")
            .that(KeyboardTileDecision.windowStateFor(false, false, false, false))
            .isEqualTo(ImeWindowState.NO_EDITOR)
        // A stale minimized flag on a hidden window (reset runs on hide) is not "minimized".
        assertWithMessage("hidden with a stale minimized flag")
            .that(KeyboardTileDecision.windowStateFor(false, true, true, true))
            .isEqualTo(ImeWindowState.HIDDEN_WITH_EDITOR)
    }

    // =========================================================================
    // Subtitle and TalkBack description
    // =========================================================================

    @Test
    fun eachActionHasItsOwnSubtitleAndDescription() {
        val subtitles = KeyboardTileAction.values().map(KeyboardTileDecision::subtitleFor)
        assertWithMessage("a subtitle per action, all distinct")
            .that(subtitles.toSet()).hasSize(KeyboardTileAction.values().size)

        assertWithMessage("subtitle resources")
            .that(subtitles).containsExactly(
                R.string.tile_subtitle_switch, R.string.tile_subtitle_minimize,
                R.string.tile_subtitle_restore, R.string.tile_subtitle_show,
            ).inOrder()

        assertWithMessage("picker, CleverKeys current: the original active description")
            .that(KeyboardTileDecision.contentDescriptionFor(true, KeyboardTileAction.PICK_KEYBOARD))
            .isEqualTo(R.string.tile_descr_active)
        assertWithMessage("picker, another keyboard: the original inactive description")
            .that(KeyboardTileDecision.contentDescriptionFor(false, KeyboardTileAction.PICK_KEYBOARD))
            .isEqualTo(R.string.tile_descr_inactive)
        assertWithMessage("minimize")
            .that(KeyboardTileDecision.contentDescriptionFor(true, KeyboardTileAction.MINIMIZE))
            .isEqualTo(R.string.tile_descr_minimize)
        assertWithMessage("restore")
            .that(KeyboardTileDecision.contentDescriptionFor(true, KeyboardTileAction.RESTORE))
            .isEqualTo(R.string.tile_descr_restore)
        assertWithMessage("show")
            .that(KeyboardTileDecision.contentDescriptionFor(true, KeyboardTileAction.SHOW))
            .isEqualTo(R.string.tile_descr_show)
    }

    /** The English wording TalkBack announces and the shade shows, as resolved by the tile. */
    @Test
    fun englishWording() {
        val en = TranslationResources.strings(TranslationResources.defaultDir)
        fun descr(key: String) = en.getValue(key).replace("%1\$s", "CleverKeys")

        assertWithMessage("subtitles").that(
            listOf("tile_subtitle_switch", "tile_subtitle_minimize", "tile_subtitle_restore", "tile_subtitle_show")
                .map(en::getValue)
        ).containsExactly("Switch keyboard", "Minimize", "Restore", "Show").inOrder()
        assertWithMessage("minimize").that(descr("tile_descr_minimize"))
            .isEqualTo("CleverKeys is open. Tap to minimize it.")
        assertWithMessage("restore").that(descr("tile_descr_restore"))
            .isEqualTo("CleverKeys is minimized. Tap to restore it.")
        assertWithMessage("show").that(descr("tile_descr_show"))
            .isEqualTo("CleverKeys is hidden. Tap to show it.")
    }

    /** Every locale carries all seven strings, and each description keeps the app-name slot. */
    @Test
    fun everyLocaleTranslatesTheNewStrings() {
        val keys = listOf(
            "tile_subtitle_switch", "tile_subtitle_minimize", "tile_subtitle_restore", "tile_subtitle_show",
            "tile_descr_minimize", "tile_descr_restore", "tile_descr_show",
        )
        val en = TranslationResources.strings(TranslationResources.defaultDir)
        for (dir in TranslationResources.localeDirs) {
            val strings = TranslationResources.strings(dir)
            for (key in keys) {
                val value = strings[key]
                assertWithMessage("${dir.name}/$key is missing").that(value).isNotNull()
                if (key.startsWith("tile_descr_")) {
                    assertWithMessage("${dir.name}/$key must keep %1\$s").that(value).contains("%1\$s")
                    assertWithMessage("${dir.name}/$key must be translated, not English")
                        .that(value).isNotEqualTo(en.getValue(key))
                }
            }
        }
    }
}
