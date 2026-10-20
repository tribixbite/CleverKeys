package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.minimize.MinimizedStyle

/**
 * The Quick Settings tile's decision table ([KeyboardTileDecision]) and the service's deferred
 * show ([TileShowSession]): which action a tap takes for each window state, including the
 * "the shade just hid it" case reported on the Saga (2026-10-20), which subtitle and TalkBack
 * description announce it, and the retry state machine that applies a show once the app has
 * focus again. Pure tier: no Android types are touched.
 */
class KeyboardTileActionTest {

    private fun snapshot(
        visible: Boolean = false,
        minimized: Boolean = false,
        bound: Boolean = false,
        last: VisibleForm? = null,
        ageMs: Long? = null,
    ) = TileWindowSnapshot(visible, minimized, bound, last, ageMs)

    private val recent = KeyboardTileDecision.RECENT_HIDE_MS
    private val fab = VisibleForm.Minimized(MinimizedStyle.FAB)

    // =========================================================================
    // actionFor
    // =========================================================================

    @Test
    fun anotherKeyboardSelected_alwaysOpensThePicker() {
        val windows = listOf(
            null,
            snapshot(visible = true),
            snapshot(visible = true, minimized = true),
            snapshot(bound = true, last = VisibleForm.Full, ageMs = 100),
            snapshot(bound = true),
        )
        for (window in windows) {
            assertWithMessage("not the current IME, window=$window")
                .that(KeyboardTileDecision.actionFor(isCurrentIme = false, window = window))
                .isEqualTo(KeyboardTileAction.PICK_KEYBOARD)
        }
    }

    @Test
    fun aVisibleKeyboard_isMinimizedOrRestoredInPlace() {
        assertWithMessage("visible full -> minimize")
            .that(KeyboardTileDecision.actionFor(true, snapshot(visible = true, bound = true)))
            .isEqualTo(KeyboardTileAction.MINIMIZE)
        assertWithMessage("visible minimized -> restore")
            .that(KeyboardTileDecision.actionFor(true, snapshot(visible = true, minimized = true, bound = true)))
            .isEqualTo(KeyboardTileAction.RESTORE)
        // Visibility wins over any hide history.
        assertWithMessage("visible wins over a recent hide")
            .that(KeyboardTileDecision.actionFor(true, snapshot(visible = true, last = fab, ageMs = 10)))
            .isEqualTo(KeyboardTileAction.MINIMIZE)
    }

    /** The Saga case: the shade hid the keyboard just before the tap. */
    @Test
    fun aRecentHide_actsOnTheFormTheKeyboardHadBeforeIt() {
        for (bound in listOf(true, false)) {
            assertWithMessage("full, hidden 2 s ago, bound=$bound -> show minimized")
                .that(KeyboardTileDecision.actionFor(true, snapshot(bound = bound, last = VisibleForm.Full, ageMs = 2_000)))
                .isEqualTo(KeyboardTileAction.SHOW_MINIMIZED)
            assertWithMessage("minimized, hidden 2 s ago, bound=$bound -> show full")
                .that(KeyboardTileDecision.actionFor(true, snapshot(bound = bound, last = fab, ageMs = 2_000)))
                .isEqualTo(KeyboardTileAction.SHOW_RESTORED)
        }
        assertWithMessage("the boundary itself still counts as recent")
            .that(KeyboardTileDecision.actionFor(true, snapshot(last = VisibleForm.Full, ageMs = recent)))
            .isEqualTo(KeyboardTileAction.SHOW_MINIMIZED)
        assertWithMessage("a bar-minimized keyboard restores too")
            .that(KeyboardTileDecision.actionFor(true, snapshot(last = VisibleForm.Minimized(MinimizedStyle.BAR), ageMs = 0)))
            .isEqualTo(KeyboardTileAction.SHOW_RESTORED)
    }

    @Test
    fun anOlderHide_showsTheFullKeyboard_whenAnEditorIsBound() {
        for (last in listOf(VisibleForm.Full, fab, null)) {
            assertWithMessage("hidden long ago, last=$last, bound -> show full")
                .that(KeyboardTileDecision.actionFor(true, snapshot(bound = true, last = last, ageMs = recent + 1)))
                .isEqualTo(KeyboardTileAction.SHOW)
        }
        assertWithMessage("never shown, bound -> show")
            .that(KeyboardTileDecision.actionFor(true, snapshot(bound = true)))
            .isEqualTo(KeyboardTileAction.SHOW)
        assertWithMessage("a negative age (clock oddity) is not recent")
            .that(KeyboardTileDecision.actionFor(true, snapshot(bound = true, last = VisibleForm.Full, ageMs = -5)))
            .isEqualTo(KeyboardTileAction.SHOW)
    }

    @Test
    fun nothingToShow_fallsBackToThePicker() {
        assertWithMessage("hidden long ago, no editor")
            .that(KeyboardTileDecision.actionFor(true, snapshot(last = VisibleForm.Full, ageMs = recent + 1)))
            .isEqualTo(KeyboardTileAction.PICK_KEYBOARD)
        assertWithMessage("never shown, no editor")
            .that(KeyboardTileDecision.actionFor(true, snapshot()))
            .isEqualTo(KeyboardTileAction.PICK_KEYBOARD)
        assertWithMessage("service not running in this process")
            .that(KeyboardTileDecision.actionFor(true, null))
            .isEqualTo(KeyboardTileAction.PICK_KEYBOARD)
    }

    @Test
    fun showTargets() {
        assertThat(KeyboardTileDecision.showTargetFor(KeyboardTileAction.SHOW_MINIMIZED, MinimizedStyle.BAR))
            .isEqualTo(ShowTarget.Minimized(MinimizedStyle.BAR))
        assertThat(KeyboardTileDecision.showTargetFor(KeyboardTileAction.SHOW_RESTORED, MinimizedStyle.FAB))
            .isEqualTo(ShowTarget.Full)
        assertThat(KeyboardTileDecision.showTargetFor(KeyboardTileAction.SHOW, MinimizedStyle.FAB))
            .isEqualTo(ShowTarget.Full)
        for (a in listOf(KeyboardTileAction.PICK_KEYBOARD, KeyboardTileAction.MINIMIZE, KeyboardTileAction.RESTORE)) {
            assertWithMessage("$a is not a deferred show")
                .that(KeyboardTileDecision.showTargetFor(a, MinimizedStyle.FAB)).isNull()
        }
    }

    // =========================================================================
    // TileShowSession
    // =========================================================================

    @Test
    fun session_recordsTheHiddenFormAndAge_andForgetsTheAgeWhenShown() {
        val s = TileShowSession()
        assertThat(s.lastVisible).isNull()
        assertThat(s.hiddenAgeMs(1_000)).isNull()

        s.onWindowHidden(fab, nowMs = 1_000)
        assertThat(s.lastVisible).isEqualTo(fab)
        assertThat(s.hiddenAgeMs(3_500)).isEqualTo(2_500)

        s.onWindowVisible()
        assertThat(s.hiddenAgeMs(4_000)).isNull()
        assertWithMessage("the form is kept for the next hide's history")
            .that(s.lastVisible).isEqualTo(fab)
    }

    @Test
    fun session_retriesUntilShown_thenAppliesTheTargetOnce() {
        val s = TileShowSession()
        assertThat(s.tick(0, windowVisible = false)).isEqualTo(TileShowSession.Tick.IDLE)

        s.request(ShowTarget.Minimized(MinimizedStyle.FAB), "com.example.chat", nowMs = 0)
        assertThat(s.hasPending).isTrue()
        // Shade still open: every tick asks again.
        for (t in 0L..TileShowSession.RETRY_WINDOW_MS step TileShowSession.RETRY_INTERVAL_MS) {
            assertWithMessage("tick at $t").that(s.tick(t, windowVisible = false))
                .isEqualTo(TileShowSession.Tick.REQUEST_SHOW)
        }
        assertThat(s.onWindowShown()).isEqualTo(ShowTarget.Minimized(MinimizedStyle.FAB))
        assertWithMessage("one-shot: a later show is left alone").that(s.onWindowShown()).isNull()
        assertThat(s.tick(9_000, windowVisible = true)).isEqualTo(TileShowSession.Tick.IDLE)
    }

    @Test
    fun session_stopsTicking_whileTheWindowIsVisible() {
        val s = TileShowSession()
        s.request(ShowTarget.Full, null, nowMs = 0)
        assertThat(s.tick(250, windowVisible = true)).isEqualTo(TileShowSession.Tick.IDLE)
        assertWithMessage("the target waits for onWindowShown").that(s.hasPending).isTrue()
    }

    @Test
    fun session_expiresAtItsDeadline() {
        val s = TileShowSession()
        s.request(ShowTarget.Full, "com.example.chat", nowMs = 1_000)
        assertThat(s.tick(1_000 + TileShowSession.RETRY_WINDOW_MS + 1, windowVisible = false))
            .isEqualTo(TileShowSession.Tick.EXPIRED)
        assertThat(s.hasPending).isFalse()
        assertWithMessage("a show after expiry is not overridden").that(s.onWindowShown()).isNull()
    }

    @Test
    fun session_isCancelledByAnotherApp_byTyping_andByTeardown() {
        val s = TileShowSession()

        s.request(ShowTarget.Full, "com.example.chat", nowMs = 0)
        s.onStartInput("com.example.chat")
        assertWithMessage("the same app restarting input keeps it").that(s.hasPending).isTrue()
        s.onStartInput(null)
        assertWithMessage("an unknown package keeps it").that(s.hasPending).isTrue()
        s.onStartInput("com.example.browser")
        assertWithMessage("another app's editor drops it").that(s.hasPending).isFalse()

        s.request(ShowTarget.Full, null, nowMs = 0)
        s.onStartInput("com.example.browser")
        assertWithMessage("armed with no editor: any app may take it").that(s.hasPending).isTrue()

        s.onUserInput()
        assertWithMessage("typing drops it").that(s.hasPending).isFalse()

        s.request(ShowTarget.Full, null, nowMs = 0)
        s.cancel()
        assertThat(s.hasPending).isFalse()
        assertThat(s.tick(10, windowVisible = false)).isEqualTo(TileShowSession.Tick.IDLE)
    }

    @Test
    fun session_aNewRequestReplacesTheOldOne_andRestartsTheDeadline() {
        val s = TileShowSession()
        s.request(ShowTarget.Full, null, nowMs = 0)
        s.request(ShowTarget.Minimized(MinimizedStyle.BAR), null, nowMs = 5_000)
        assertThat(s.tick(5_000 + TileShowSession.RETRY_WINDOW_MS, windowVisible = false))
            .isEqualTo(TileShowSession.Tick.REQUEST_SHOW)
        assertThat(s.onWindowShown()).isEqualTo(ShowTarget.Minimized(MinimizedStyle.BAR))
    }

    // =========================================================================
    // Subtitle and TalkBack description
    // =========================================================================

    @Test
    fun subtitlesAndDescriptions_nameWhatTheTapDoesInTheUsersTerms() {
        val expected = mapOf(
            KeyboardTileAction.PICK_KEYBOARD to R.string.tile_subtitle_switch,
            KeyboardTileAction.MINIMIZE to R.string.tile_subtitle_minimize,
            KeyboardTileAction.SHOW_MINIMIZED to R.string.tile_subtitle_minimize,
            KeyboardTileAction.RESTORE to R.string.tile_subtitle_restore,
            KeyboardTileAction.SHOW_RESTORED to R.string.tile_subtitle_restore,
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
        for (a in listOf(KeyboardTileAction.MINIMIZE, KeyboardTileAction.SHOW_MINIMIZED)) {
            assertThat(KeyboardTileDecision.contentDescriptionFor(true, a)).isEqualTo(R.string.tile_descr_minimize)
        }
        for (a in listOf(KeyboardTileAction.RESTORE, KeyboardTileAction.SHOW_RESTORED)) {
            assertThat(KeyboardTileDecision.contentDescriptionFor(true, a)).isEqualTo(R.string.tile_descr_restore)
        }
        assertThat(KeyboardTileDecision.contentDescriptionFor(true, KeyboardTileAction.SHOW))
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
