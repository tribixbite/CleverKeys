package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Review 2026-11-14 M23 + a low: SettingsActivity is `singleTop`, so the keyboard's "set up voice
 * input" intent reaches a running Settings through `onNewIntent`, which handled only GIF shares —
 * `scroll_to` was read in `onCreate` alone and the user landed wherever Settings was. And
 * `onCreate` re-ran the GIF share import on every recreation (rotation re-imported the pack).
 *
 * Source scan (the activity cannot be launched on the JVM). Covers: one `scroll_to` handler, called
 * from both entry points; the share import guarded by `savedInstanceState == null`. Not covered:
 * the scroll itself, which needs the composed screen.
 */
class SettingsIntentHandlingDriftTest {

    private val src = SourceScan.read("src/main/kotlin/tribixbite/cleverkeys/activities/SettingsActivity.kt")

    @Test
    fun scrollTo_isHandledOnNewIntentAsWellAsOnCreate() {
        val onCreate = SourceScan.functionBody(src, "override fun onCreate")
        val onNewIntent = SourceScan.functionBody(src, "override fun onNewIntent")
        assertWithMessage("onNewIntent must handle the scroll_to extra (singleTop relaunch)")
            .that(onNewIntent.contains("handleScrollToIntent(intent)")).isTrue()
        assertWithMessage("onCreate must use the same scroll_to handler")
            .that(onCreate.contains("handleScrollToIntent(intent)")).isTrue()
        assertWithMessage("onCreate must not keep its own copy of the scroll_to parsing")
            .that(onCreate.contains("getStringExtra(\"scroll_to\")") || onCreate.contains("getStringExtra(EXTRA_SCROLL_TO)"))
            .isFalse()
    }

    @Test
    fun gifShareIntent_isImportedOnceNotOnEveryRecreation() {
        val onCreate = SourceScan.functionBody(src, "override fun onCreate")
        assertWithMessage("onCreate must import a shared GIF pack only when savedInstanceState == null")
            .that(Regex("""if\s*\(\s*savedInstanceState\s*==\s*null\s*\)\s*\{[^}]*handleGifPackShareIntent\(intent\)""")
                .containsMatchIn(onCreate))
            .isTrue()
    }
}
