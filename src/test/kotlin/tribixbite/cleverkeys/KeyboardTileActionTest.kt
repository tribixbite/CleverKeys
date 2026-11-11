package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The Quick Settings tile's decision table ([KeyboardTileDecision]) and the service's deferred
 * show ([TileShowSession]): a tap hides a keyboard that is (or, before the shade opened, was) on
 * screen and shows a hidden one, never falling back to the picker while CleverKeys is selected
 * (maintainer, 2026-11-11); which subtitle and TalkBack description announce it; and the retry
 * state machine that lands a show once an editor has focus. Pure tier: no Android types.
 */
class KeyboardTileActionTest {

    private fun snapshot(
        visible: Boolean = false,
        current: EditorRef? = null,
        lastEditor: EditorRef? = null,
        ageMs: Long? = null,
        byUser: Boolean = false,
    ) = TileWindowSnapshot(visible, current, lastEditor, ageMs, byUser)

    private val recent = KeyboardTileDecision.RECENT_HIDE_MS

    /** A typed text field in a chat app. */
    private val chat = EditorRef("com.example.chat", fieldId = 42, typeNull = false)

    /** The launcher's fallback connection on Android 14: TYPE_NULL, the decor view's id. */
    private val launcherFallback = EditorRef("com.android.launcher3", fieldId = -1, typeNull = true)

    /** A terminal view (TYPE_NULL by design), e.g. Termux. */
    private val terminal = EditorRef("com.termux", fieldId = 7, typeNull = true)

    /** The shade's own fallback binding, if the shade takes input focus. */
    private val shade = EditorRef(KeyboardTileDecision.SYSTEM_UI_PACKAGE, fieldId = -1, typeNull = true)

    // =========================================================================
    // actionFor
    // =========================================================================

    @Test
    fun anotherKeyboardSelected_alwaysOpensThePicker() {
        val windows = listOf(
            null,
            snapshot(visible = true),
            snapshot(current = chat, lastEditor = chat, ageMs = 100),
            snapshot(current = chat),
        )
        for (window in windows) {
            assertWithMessage("not the current IME, window=$window")
                .that(KeyboardTileDecision.actionFor(isCurrentIme = false, window = window))
                .isEqualTo(KeyboardTileAction.PICK_KEYBOARD)
        }
        assertWithMessage("service not running in this process")
            .that(KeyboardTileDecision.actionFor(true, null))
            .isEqualTo(KeyboardTileAction.PICK_KEYBOARD)
    }

    /** Full or minimized (the snapshot does not distinguish them): either way the tap hides it. */
    @Test
    fun aVisibleKeyboard_isHidden() {
        for (current in listOf(chat, terminal, launcherFallback, null)) {
            assertWithMessage("visible, bound to $current -> hide")
                .that(KeyboardTileDecision.actionFor(true, snapshot(visible = true, current = current)))
                .isEqualTo(KeyboardTileAction.HIDE)
        }
        assertWithMessage("visible wins over an older user dismissal")
            .that(KeyboardTileDecision.actionFor(true, snapshot(visible = true, lastEditor = chat, ageMs = 10, byUser = true)))
            .isEqualTo(KeyboardTileAction.HIDE)
    }

    /**
     * The Saga case: the shade hid a keyboard the user saw just before pulling it down. The tap
     * is the hide they asked for, so the keyboard stays hidden instead of coming straight back.
     */
    @Test
    fun aRecentShadeHide_keepsTheKeyboardHidden() {
        // Still bound to the app, unbound, or bound to the shade itself: all count.
        for (current in listOf(chat, null, shade)) {
            assertWithMessage("hidden 2 s ago, current=$current -> hide")
                .that(KeyboardTileDecision.actionFor(true, snapshot(current = current, lastEditor = chat, ageMs = 2_000)))
                .isEqualTo(KeyboardTileAction.HIDE)
        }
        assertWithMessage("the boundary itself still counts as recent")
            .that(KeyboardTileDecision.actionFor(true, snapshot(lastEditor = chat, ageMs = recent)))
            .isEqualTo(KeyboardTileAction.HIDE)
        assertWithMessage("a terminal the keyboard was shown over counts too")
            .that(KeyboardTileDecision.actionFor(true, snapshot(current = terminal, lastEditor = terminal, ageMs = 1_000)))
            .isEqualTo(KeyboardTileAction.HIDE)
    }

    /** A Back press, hide_keyboard or an earlier tile hide is the user's: the next tap shows. */
    @Test
    fun aRecentUserDismissal_isShownAgain() {
        assertWithMessage("Back 2 s ago over a bound field -> show")
            .that(KeyboardTileDecision.actionFor(true, snapshot(current = chat, lastEditor = chat, ageMs = 2_000, byUser = true)))
            .isEqualTo(KeyboardTileAction.SHOW)
        assertWithMessage("Back 2 s ago, nothing bound -> still a show (it waits for an editor)")
            .that(KeyboardTileDecision.actionFor(true, snapshot(lastEditor = chat, ageMs = 2_000, byUser = true)))
            .isEqualTo(KeyboardTileAction.SHOW)
    }

    @Test
    fun anyOtherHiddenKeyboard_isShown_neverThePicker() {
        val windows = listOf(
            snapshot(current = chat, lastEditor = chat, ageMs = recent + 1),
            snapshot(current = chat),
            snapshot(current = terminal, lastEditor = terminal, ageMs = 60_000),
            snapshot(lastEditor = chat, ageMs = recent + 1),
            snapshot(),
            // Home screen: the launcher's fallback connection, recent or old, either kind of hide.
            snapshot(current = launcherFallback, lastEditor = chat, ageMs = 2_000),
            snapshot(current = launcherFallback, lastEditor = chat, ageMs = 12_000, byUser = true),
            snapshot(current = launcherFallback),
            // A recent hide with no editor recorded is not the shade's.
            snapshot(ageMs = 1_000),
            // A negative age (clock oddity) is not recent.
            snapshot(current = chat, lastEditor = chat, ageMs = -5),
        )
        for (window in windows) {
            assertWithMessage("hidden, window=$window -> show")
                .that(KeyboardTileDecision.actionFor(true, window))
                .isEqualTo(KeyboardTileAction.SHOW)
        }
    }

    @Test
    fun aRecentHide_thenAnotherApp_isShownThere() {
        val browser = EditorRef("com.example.browser", fieldId = 3, typeNull = false)
        assertWithMessage("hidden in chat 2 s ago, now a typed field in another app -> show there")
            .that(KeyboardTileDecision.actionFor(true, snapshot(current = browser, lastEditor = chat, ageMs = 2_000)))
            .isEqualTo(KeyboardTileAction.SHOW)
    }

    @Test
    fun editorHelpers() {
        assertThat(KeyboardTileDecision.isRealEditor(chat, null)).isTrue()
        assertThat(KeyboardTileDecision.isRealEditor(null, chat)).isFalse()
        assertThat(KeyboardTileDecision.isRealEditor(launcherFallback, chat)).isFalse()
        assertThat(KeyboardTileDecision.isRealEditor(terminal, terminal)).isTrue()
        assertThat(KeyboardTileDecision.isRealEditor(terminal, terminal.copy(fieldId = 8))).isFalse()
        assertThat(KeyboardTileDecision.isSameAppOrShade(null, chat)).isTrue()
        assertThat(KeyboardTileDecision.isSameAppOrShade(shade, chat)).isTrue()
        assertThat(KeyboardTileDecision.isSameAppOrShade(chat.copy(fieldId = 9), chat)).isTrue()
        assertThat(KeyboardTileDecision.isSameAppOrShade(launcherFallback, chat)).isFalse()
        assertThat(KeyboardTileDecision.isSameAppOrShade(chat, null)).isFalse()
    }

    // =========================================================================
    // TileShowSession
    // =========================================================================

    @Test
    fun session_recordsTheHideAndItsAge_andForgetsTheAgeWhenShown() {
        val s = TileShowSession()
        assertThat(s.hiddenAgeMs(1_000)).isNull()

        s.onWindowHidden(chat, nowMs = 1_000)
        assertThat(s.lastEditor).isEqualTo(chat)
        assertThat(s.dismissedByUser).isFalse()
        assertThat(s.hiddenAgeMs(3_500)).isEqualTo(2_500)

        s.onWindowVisible()
        assertThat(s.hiddenAgeMs(4_000)).isNull()
        assertWithMessage("the editor is kept for the next hide's history").that(s.lastEditor).isEqualTo(chat)
    }

    @Test
    fun session_userDismissal_marksOnlyTheNextHide() {
        val s = TileShowSession()
        s.noteUserDismiss()
        s.onWindowHidden(chat, nowMs = 0)
        assertThat(s.dismissedByUser).isTrue()

        s.onWindowShown()
        s.onWindowHidden(chat, nowMs = 10)
        assertWithMessage("the next hide (e.g. the shade) is not the user's").that(s.dismissedByUser).isFalse()

        s.noteUserDismiss()
        s.onWindowVisible()
        s.onWindowHidden(chat, nowMs = 20)
        assertWithMessage("a mark that never led to a hide expires on the next show").that(s.dismissedByUser).isFalse()
    }

    /** Tap while the shade has the keyboard hidden: hidden it stays, and the next tap shows it. */
    @Test
    fun session_aTileHideOfAShadeHiddenKeyboard_makesTheNextTapAShow() {
        val s = TileShowSession()
        s.markHiddenByUser()
        assertWithMessage("nothing hidden yet: nothing to re-attribute").that(s.dismissedByUser).isFalse()

        s.onWindowHidden(chat, nowMs = 1_000)
        fun window() = TileWindowSnapshot(false, chat, s.lastEditor, s.hiddenAgeMs(2_000), s.dismissedByUser)
        assertThat(KeyboardTileDecision.actionFor(true, window())).isEqualTo(KeyboardTileAction.HIDE)

        s.markHiddenByUser()
        assertThat(s.dismissedByUser).isTrue()
        assertThat(KeyboardTileDecision.actionFor(true, window())).isEqualTo(KeyboardTileAction.SHOW)

        s.onWindowVisible()
        s.markHiddenByUser()
        s.onWindowHidden(chat, nowMs = 3_000)
        assertWithMessage("a mark made while visible does not carry to the next (shade) hide")
            .that(s.dismissedByUser).isFalse()
    }

    @Test
    fun session_retriesUntilShown_thenReportsTheRequestedShowOnce() {
        val s = TileShowSession()
        assertThat(s.tick(0, windowVisible = false, editorReady = true)).isEqualTo(TileShowSession.Tick.IDLE)

        s.request("com.example.chat", nowMs = 0)
        assertThat(s.hasPending).isTrue()
        // An app hiding its own keyboard around the shade: every tick asks again.
        for (t in 0L..TileShowSession.RETRY_WINDOW_MS step TileShowSession.RETRY_INTERVAL_MS) {
            assertWithMessage("tick at $t").that(s.tick(t, windowVisible = false, editorReady = true))
                .isEqualTo(TileShowSession.Tick.REQUEST_SHOW)
        }
        assertThat(s.onWindowShown()).isTrue()
        assertWithMessage("one-shot: a later show is left alone").that(s.onWindowShown()).isFalse()
        assertThat(s.tick(9_000, windowVisible = true, editorReady = true)).isEqualTo(TileShowSession.Tick.IDLE)
    }

    /** Home screen: no request is sent until an editor has focus, and the wait has a deadline. */
    @Test
    fun session_waitsForAnEditor_neverRequestingOverNothing() {
        val s = TileShowSession()
        s.request(null, nowMs = 0)
        for (t in 0L..2_000L step TileShowSession.RETRY_INTERVAL_MS) {
            assertWithMessage("no editor at $t").that(s.tick(t, windowVisible = false, editorReady = false))
                .isEqualTo(TileShowSession.Tick.WAIT)
        }
        assertWithMessage("an editor gains focus").that(s.tick(2_250, windowVisible = false, editorReady = true))
            .isEqualTo(TileShowSession.Tick.REQUEST_SHOW)
        assertThat(s.tick(TileShowSession.RETRY_WINDOW_MS + 1, windowVisible = false, editorReady = false))
            .isEqualTo(TileShowSession.Tick.EXPIRED)
        assertThat(s.hasPending).isFalse()
    }

    @Test
    fun session_stopsTicking_whileTheWindowIsVisible() {
        val s = TileShowSession()
        s.request(null, nowMs = 0)
        assertThat(s.tick(250, windowVisible = true, editorReady = true)).isEqualTo(TileShowSession.Tick.IDLE)
        assertWithMessage("the request waits for onWindowShown").that(s.hasPending).isTrue()
    }

    @Test
    fun session_expiresAtItsDeadline() {
        val s = TileShowSession()
        s.request("com.example.chat", nowMs = 1_000)
        assertThat(s.tick(1_000 + TileShowSession.RETRY_WINDOW_MS + 1, windowVisible = false, editorReady = true))
            .isEqualTo(TileShowSession.Tick.EXPIRED)
        assertThat(s.hasPending).isFalse()
        assertWithMessage("a show after expiry is not the tile's").that(s.onWindowShown()).isFalse()
    }

    @Test
    fun session_isCancelledByAnotherApp_byTyping_andByTeardown() {
        val s = TileShowSession()

        s.request("com.example.chat", nowMs = 0)
        s.onStartInput("com.example.chat")
        assertWithMessage("the same app restarting input keeps it").that(s.hasPending).isTrue()
        s.onStartInput(null)
        assertWithMessage("an unknown package keeps it").that(s.hasPending).isTrue()
        s.onStartInput("com.example.browser")
        assertWithMessage("another app's editor drops it").that(s.hasPending).isFalse()

        s.request(null, nowMs = 0)
        s.onStartInput("com.example.browser")
        assertWithMessage("armed with no editor: any app may take it").that(s.hasPending).isTrue()

        s.onUserInput()
        assertWithMessage("typing drops it").that(s.hasPending).isFalse()

        s.request(null, nowMs = 0)
        s.cancel()
        assertThat(s.hasPending).isFalse()
        assertThat(s.tick(10, windowVisible = false, editorReady = true)).isEqualTo(TileShowSession.Tick.IDLE)
    }

    @Test
    fun session_aNewRequestRestartsTheDeadline() {
        val s = TileShowSession()
        s.request(null, nowMs = 0)
        s.request(null, nowMs = 5_000)
        assertThat(s.tick(5_000 + TileShowSession.RETRY_WINDOW_MS, windowVisible = false, editorReady = true))
            .isEqualTo(TileShowSession.Tick.REQUEST_SHOW)
        assertThat(s.onWindowShown()).isTrue()
    }

    // =========================================================================
    // Subtitle and TalkBack description
    // =========================================================================

    @Test
    fun subtitlesAndDescriptions_nameWhatTheTapDoes() {
        val expected = mapOf(
            KeyboardTileAction.PICK_KEYBOARD to R.string.tile_subtitle_switch,
            KeyboardTileAction.HIDE to R.string.tile_subtitle_hide,
            KeyboardTileAction.SHOW to R.string.tile_subtitle_show,
        )
        assertThat(expected.keys).containsExactlyElementsIn(KeyboardTileAction.values().toList())
        for ((action, res) in expected) {
            assertWithMessage("subtitle for $action").that(KeyboardTileDecision.subtitleFor(action)).isEqualTo(res)
        }

        assertWithMessage("picker, CleverKeys current: the original active description")
            .that(KeyboardTileDecision.contentDescriptionFor(true, KeyboardTileAction.PICK_KEYBOARD))
            .isEqualTo(R.string.tile_descr_active)
        assertWithMessage("picker, another keyboard: the original inactive description")
            .that(KeyboardTileDecision.contentDescriptionFor(false, KeyboardTileAction.PICK_KEYBOARD))
            .isEqualTo(R.string.tile_descr_inactive)
        assertThat(KeyboardTileDecision.contentDescriptionFor(true, KeyboardTileAction.HIDE))
            .isEqualTo(R.string.tile_descr_hide)
        assertThat(KeyboardTileDecision.contentDescriptionFor(true, KeyboardTileAction.SHOW))
            .isEqualTo(R.string.tile_descr_show)
    }

    /** The English wording TalkBack announces and the shade shows, as resolved by the tile. */
    @Test
    fun englishWording() {
        val en = TranslationResources.strings(TranslationResources.defaultDir)
        fun descr(key: String) = en.getValue(key).replace("%1\$s", "CleverKeys")

        assertWithMessage("subtitles").that(
            listOf("tile_subtitle_switch", "tile_subtitle_hide", "tile_subtitle_show").map(en::getValue)
        ).containsExactly("Switch keyboard", "Hide", "Show").inOrder()
        assertWithMessage("hide").that(descr("tile_descr_hide")).isEqualTo("CleverKeys is open. Tap to hide it.")
        assertWithMessage("show").that(descr("tile_descr_show")).isEqualTo("CleverKeys is hidden. Tap to show it.")
        for (gone in listOf("tile_subtitle_minimize", "tile_subtitle_restore", "tile_descr_minimize", "tile_descr_restore")) {
            assertWithMessage("$gone belongs to the old minimize tile").that(en).doesNotContainKey(gone)
        }
    }

    /** Every locale carries all five strings, and each description keeps the app-name slot. */
    @Test
    fun everyLocaleTranslatesTheNewStrings() {
        val keys = listOf(
            "tile_subtitle_switch", "tile_subtitle_hide", "tile_subtitle_show",
            "tile_descr_hide", "tile_descr_show",
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
