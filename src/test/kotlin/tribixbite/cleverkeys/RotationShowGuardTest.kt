package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.RotationShowGuard.Check

/**
 * [RotationShowGuard]: the keyboard that was on screen before a rotation is asked back when the
 * app's relaunch hides it (Pixel 8 Pro, Google app: hidden 8 of 8 rotations, 2026-11-19), and
 * never otherwise: not after the user's own hide, not in another app, not over a window without
 * an editor, not after the window closes. Pure tier.
 */
class RotationShowGuardTest {

    private val search = EditorRef("com.google.android.googlequicksearchbox", fieldId = 2131435733, typeNull = false)
    private val fallback = EditorRef("com.google.android.googlequicksearchbox", fieldId = -1, typeNull = true)
    private val terminal = EditorRef("com.termux", fieldId = 7, typeNull = true)
    private val other = EditorRef("com.example.other", fieldId = 3, typeNull = false)

    private fun armed(editor: EditorRef = search) =
        RotationShowGuard().apply { onOrientationChanged(windowVisible = true, editor = editor, nowMs = 0) }

    @Test
    fun aHideRightAfterTheRotationIsUndoneOnceTheAppsEditorIsBound() {
        val guard = armed()
        assertThat(guard.onWindowHidden(byUser = false, nowMs = 150)).isTrue()
        assertWithMessage("the relaunched window has not started input yet")
            .that(guard.check(200, windowVisible = false, current = null)).isEqualTo(Check.WAIT)
        guard.onStartInput(search.packageName, restarting = false)
        assertThat(guard.check(300, windowVisible = false, current = search)).isEqualTo(Check.REQUEST_SHOW)
        assertWithMessage("shown: the loop ends")
            .that(guard.check(600, windowVisible = true, current = search)).isEqualTo(Check.DONE)
    }

    @Test
    fun aRotationWithTheKeyboardHiddenNeverBringsItUp() {
        val guard = RotationShowGuard()
        guard.onOrientationChanged(windowVisible = false, editor = search, nowMs = 0)
        assertThat(guard.isArmed).isFalse()
        assertThat(guard.onWindowHidden(byUser = false, nowMs = 100)).isFalse()
        assertThat(guard.check(200, windowVisible = false, current = search)).isEqualTo(Check.DONE)
    }

    @Test
    fun aRotationWithNoEditorBoundArmsNothing() {
        val guard = RotationShowGuard()
        guard.onOrientationChanged(windowVisible = true, editor = null, nowMs = 0)
        assertThat(guard.isArmed).isFalse()
    }

    @Test
    fun theUsersOwnHideStandsAndDisarms() {
        val guard = armed()
        assertThat(guard.onWindowHidden(byUser = true, nowMs = 300)).isFalse()
        assertThat(guard.isArmed).isFalse()
        assertThat(guard.check(400, windowVisible = false, current = search)).isEqualTo(Check.DONE)
    }

    @Test
    fun aHideAfterTheWindowIsNotTheRotations() {
        val guard = armed()
        assertThat(guard.onWindowHidden(byUser = false, nowMs = RotationShowGuard.WINDOW_MS + 1)).isFalse()
        assertThat(guard.isArmed).isFalse()
    }

    @Test
    fun theLoopGivesUpAtTheDeadlineWhileNoEditorComes() {
        val guard = armed()
        guard.onWindowHidden(byUser = false, nowMs = 100)
        assertThat(guard.check(500, windowVisible = false, current = null)).isEqualTo(Check.WAIT)
        assertThat(guard.check(RotationShowGuard.WINDOW_MS + 1, windowVisible = false, current = null))
            .isEqualTo(Check.DONE)
        assertThat(guard.isArmed).isFalse()
    }

    @Test
    fun inputInAnotherAppCancels() {
        val guard = armed()
        guard.onWindowHidden(byUser = false, nowMs = 100)
        assertThat(guard.check(200, windowVisible = false, current = other)).isEqualTo(Check.DONE)
        assertThat(guard.isArmed).isFalse()
    }

    @Test
    fun theFallbackConnectionOfAWindowWithoutAnEditorIsNotShownOver() {
        // Discord on the Pixel returns to its channel list in portrait: no editor, only the
        // platform's TYPE_NULL fallback of the same package.
        val guard = armed()
        guard.onWindowHidden(byUser = false, nowMs = 100)
        assertThat(guard.check(200, windowVisible = false, current = fallback)).isEqualTo(Check.WAIT)
        assertThat(guard.check(RotationShowGuard.WINDOW_MS + 1, windowVisible = false, current = fallback))
            .isEqualTo(Check.DONE)
    }

    @Test
    fun aTerminalIsATypeNullEditorAndIsRestored() {
        val guard = armed(terminal)
        guard.onWindowHidden(byUser = false, nowMs = 100)
        guard.onStartInput(terminal.packageName, restarting = false)
        assertThat(guard.check(200, windowVisible = false, current = terminal)).isEqualTo(Check.REQUEST_SHOW)
    }

    @Test
    fun atMostTwoRequestsPerRotationSoTheAppsThirdHideStands() {
        val guard = armed()
        guard.onStartInput(search.packageName, restarting = false)
        var requests = 0
        var t = 100L
        repeat(3) {
            if (guard.onWindowHidden(byUser = false, nowMs = t)) {
                while (true) {
                    t += 50
                    when (guard.check(t, windowVisible = false, current = search)) {
                        Check.REQUEST_SHOW -> { requests++; break }
                        Check.WAIT -> continue
                        Check.DONE -> break
                    }
                }
                // The request landed; the app hides it again.
                guard.check(t + 10, windowVisible = true, current = search)
            }
            t += 100
        }
        assertThat(requests).isEqualTo(RotationShowGuard.MAX_RESTORES)
    }

    @Test
    fun anAppThatKeepsItsWindowKeepsItsOwnHide() {
        // Discord on the Pixel 8 Pro: no new input on rotation, its own hide(ime()); in portrait
        // its channel list covers the still-focused chat box. Only the IME's own reset
        // (restarting) and a restartInput arrive.
        val guard = armed(EditorRef("com.discord", fieldId = 2131362051, typeNull = false))
        guard.onStartInput("com.discord", restarting = true)
        assertThat(guard.onWindowHidden(byUser = false, nowMs = 400)).isTrue()
        val chat = EditorRef("com.discord", fieldId = 2131362051, typeNull = false)
        var t = 500L
        while (t <= RotationShowGuard.WINDOW_MS) {
            assertWithMessage("at $t ms").that(guard.check(t, windowVisible = false, current = chat))
                .isEqualTo(Check.WAIT)
            t += RotationShowGuard.RETRY_INTERVAL_MS
        }
        assertThat(guard.check(t, windowVisible = false, current = chat)).isEqualTo(Check.DONE)
    }

    @Test
    fun aFreshStartInAnotherAppCancels() {
        val guard = armed()
        guard.onStartInput("com.example.other", restarting = false)
        assertThat(guard.isArmed).isFalse()
    }

    @Test
    fun userInputDisarms() {
        val guard = armed()
        guard.onUserInput()
        assertThat(guard.onWindowHidden(byUser = false, nowMs = 100)).isFalse()
    }
}
