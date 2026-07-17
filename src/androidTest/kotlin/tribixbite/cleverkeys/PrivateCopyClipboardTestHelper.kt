package tribixbite.cleverkeys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry

/**
 * #156 shared helper that makes the private-copy OS-clipboard assertions RELIABLE.
 *
 * WHY THIS EXISTS: the naive approach — registering an [ClipboardManager.OnPrimaryClipChangedListener]
 * and asserting it (never) fires — is BROKEN on API 29+. A non-focused instrumented test is neither
 * the focused app nor the active IME, so its clipboard-change listener NEVER fires (the same focus
 * restriction that blocks [ClipboardManager.getPrimaryClip]). That makes "private copy → listener
 * doesn't fire" pass VACUOUSLY (it would pass even if the private copy DID leak to the OS clipboard)
 * and makes "normal copy → listener fires" a flaky/false failure.
 *
 * THE RELIABLE MECHANISM: adopt the shell permission identity for the duration of the test. With the
 * shell identity adopted, `ClipboardManager` reads/writes succeed regardless of focus, so we can set
 * a known baseline and READ the primary clip back to compare actual VALUES — a real, non-vacuous
 * security assertion. All clipboard get/set is marshalled onto the main thread via
 * [Instrumentation.runOnMainSync] (ClipboardManager is a main-thread-affine service).
 *
 * Usage:
 * ```
 * private val clip = PrivateCopyClipboardTestHelper()
 * @Before fun setUp() { clip.adopt() }
 * @After  fun tearDown() { clip.drop() }
 * ```
 */
class PrivateCopyClipboardTestHelper {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val clipboardManager: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /**
     * Grant this test process shell-level clipboard access so [readPrimaryClipText]/[setPrimaryClip]
     * work on API 29+. Call in `@Before`. Idempotent from the framework's perspective.
     */
    fun adopt() {
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
    }

    /** Release the adopted shell identity. Call in `@After`. */
    fun drop() {
        instrumentation.uiAutomation.dropShellPermissionIdentity()
    }

    /**
     * Place a known [text] baseline on the OS primary clip (main-thread, blocking). Requires [adopt].
     */
    fun setPrimaryClip(label: String, text: String) {
        instrumentation.runOnMainSync {
            clipboardManager.setPrimaryClip(ClipData.newPlainText(label, text))
        }
    }

    /**
     * Read the current OS primary clip's first-item text (main-thread, blocking). Returns null when
     * the clipboard is empty. Requires [adopt] so the read is not blocked by the focus restriction.
     */
    fun readPrimaryClipText(): String? {
        var value: String? = null
        instrumentation.runOnMainSync {
            val clip = clipboardManager.primaryClip
            value = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(context)?.toString()
            } else {
                null
            }
        }
        return value
    }

    /** Drain the main looper so any pending clipboard write is applied before we read it back. */
    fun waitForIdle() {
        instrumentation.waitForIdleSync()
    }
}
