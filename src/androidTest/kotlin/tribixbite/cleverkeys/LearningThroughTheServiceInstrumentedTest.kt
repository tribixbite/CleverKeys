package tribixbite.cleverkeys

import android.app.Activity
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Learning through the REAL keyboard service and a real field (test-gap audit 2026-11-28, item 10).
 * The mock-tier learning harness (LearningFunnelBookkeepingTest) models the service's lifecycle by
 * hand; these cases run it: typing, a real rotation (`user_rotation`, [UserRotation]) and the
 * keyboard's own Backspace, with the learned pairs read from the live store.
 *
 *  1. `fix toy `, rotate, Backspace ×4: `fix → toy` is taken back (Pixel r22 R3 / r23 L2b found it
 *     kept after a rotation), in an app recreated by the rotation ([ImeFieldTestActivity]) and in
 *     one that keeps its window ([ImeFieldKeepWindowTestActivity]).
 *  2. `blue car seat `, Backspace until the field is empty, `now `: no pair with any cleared word
 *     (Pixel r24 `blue → now`).
 *
 * Compile-checked on the dev box; run on a test phone or emulator.wtf. Leaves no trace: the
 * keyboard, learning preferences and rotation settings are restored ([RealServiceLearning]).
 */
@RunWith(AndroidJUnit4::class)
class LearningThroughTheServiceInstrumentedTest {

    @get:Rule
    val showImeWithHardKeyboard = ShowImeWithHardKeyboardRule()

    private val live = RealServiceLearning()
    private val rotation by lazy { UserRotation(live::shell, live::orientation, live::within, TAG) }

    @Before
    fun setUp() = live.setUp()

    @After
    fun tearDown() {
        rotation.restore()
        live.tearDown()
    }

    @Test
    fun aWordDeletedAfterARotationThatRecreatesTheAppIsUnlearned() =
        aWordDeletedAfterARotationIsUnlearned(ImeFieldTestActivity::class.java)

    @Test
    fun aWordDeletedAfterARotationInAnAppThatKeepsItsWindowIsUnlearned() =
        aWordDeletedAfterARotationIsUnlearned(ImeFieldKeepWindowTestActivity::class.java)

    private fun <A : Activity> aWordDeletedAfterARotationIsUnlearned(activity: Class<A>) {
        rotation.requirePortrait()
        ActivityScenario.launch(activity).use {
            live.waitFor("the keyboard") { live.shown() }
            val baseline = live.bigram("fix", "toy")
            live.type("fix toy ")
            live.waitFor("fix → toy learned (baseline $baseline)", 5_000L) { live.bigram("fix", "toy") == baseline + 1 }

            rotation.toLandscape { live.state() }
            live.waitFor("the keyboard back after the rotation") { live.shown() }
            live.waitFor("the field's text back after the rotation") { live.editorText().endsWith("toy ", ignoreCase = true) }
            live.backspace(4)
            live.waitFor("the word deleted") { live.editorText().trimEnd().endsWith("fix", ignoreCase = true) }

            val after = live.bigram("fix", "toy")
            Log.i(TAG, "${activity.simpleName}: fix→toy baseline=$baseline after=$after; ${live.state()}")
            assertEquals("fix → toy taken back after the rotation (${activity.simpleName})", baseline, after)
        }
    }

    @Test
    fun aClearedFieldLeavesNoPairWithTheNextWord() {
        ActivityScenario.launch(ImeFieldKeepWindowTestActivity::class.java).use {
            live.waitFor("the keyboard") { live.shown() }
            val cleared = listOf("blue", "car", "seat")
            val baselines = cleared.associateWith { live.bigram(it, "now") }
            live.type("blue car seat ")
            live.backspace("blue car seat ".length)
            live.waitFor("the field empty") { live.editorText().isEmpty() }
            live.type("now ")
            live.waitFor("now typed") { live.editorText().equals("now ", ignoreCase = true) }

            for (word in cleared) {
                assertEquals("no $word → now after the field was cleared", baselines.getValue(word), live.bigram(word, "now"))
            }
        }
    }

    private companion object {
        const val TAG = "LearningServiceTest"
    }
}
