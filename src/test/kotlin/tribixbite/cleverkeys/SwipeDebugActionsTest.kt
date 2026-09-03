package tribixbite.cleverkeys

import android.widget.Toast
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * Pins v1.1.81's "Swipe Debug tool redesigned with copy/save actions" — the toolbar of
 * [SwipeDebugActivity] (the Swipe Playground) and what its two export actions do.
 *
 * What is pinned **behaviourally** ([saveRefusesToOpenAPickerWithNothingToSave],
 * [saveProceedsOnceThereIsSomethingToSave]): the Save action is guarded on log content, so
 * an empty session tells the user "No logs to save" instead of opening an empty document
 * picker, and a non-empty session does not take that branch.
 *
 * What is pinned at **source/resource level** ([toolbarOffersCopyClearAndSave],
 * [copyPutsTheWholeBufferOnTheClipboard], [saveUsesTheStorageAccessFramework]): the button
 * ids and their handlers, the clipboard payload, and the SAF intent shape. Those paths call
 * `getSystemService` / construct an `Intent` on a live Activity, and both are android.jar
 * stubs that throw `RuntimeException("Stub!")` off-device — there is no JVM tier that can
 * execute them. The activity is final, so a MockK proxy would intercept the private methods
 * rather than run them; the object below is therefore constructed WITHOUT MockK (Objenesis,
 * no constructor) so the real method bodies execute.
 *
 * Mock tier: `Activity`/`Toast` must resolve from android.jar. Run with
 * `scripts/gradle-guard.sh runMockTests -PtestClass=SwipeDebugActionsTest`.
 */
class SwipeDebugActionsTest {

    private val activityClass = SwipeDebugActivity::class.java
    private lateinit var activity: SwipeDebugActivity

    @Before
    fun setUp() {
        mockkStatic(Toast::class)
        every { Toast.makeText(any(), any<CharSequence>(), any()) } returns mockk(relaxed = true)
        // No constructor: Activity's android.jar stub ctor throws. Objenesis leaves every
        // field null, so the one field the tested path reads is set explicitly below.
        activity = ObjenesisStd().newInstance(activityClass)
    }

    @After
    fun tearDown() {
        unmockkStatic(Toast::class)
    }

    private fun setLogBuffer(content: String) {
        activityClass.getDeclaredField("logBuffer").apply { isAccessible = true }
            .set(activity, StringBuilder(content))
    }

    private fun invokeSaveLogsToFile(): Throwable? = try {
        activityClass.getDeclaredMethod("saveLogsToFile").apply { isAccessible = true }.invoke(activity)
        null
    } catch (e: java.lang.reflect.InvocationTargetException) {
        e.cause
    }

    // =========================================================================
    // Save action — the content guard
    // =========================================================================

    @Test
    fun saveRefusesToOpenAPickerWithNothingToSave() {
        setLogBuffer("")

        val thrown = invokeSaveLogsToFile()

        assertWithMessage("an empty session must return before touching the framework")
            .that(thrown).isNull()
        verify(exactly = 1) {
            Toast.makeText(any(), "No logs to save", Toast.LENGTH_SHORT)
        }
    }

    @Test
    fun saveProceedsOnceThereIsSomethingToSave() {
        setLogBuffer("=== Swipe Playground Session Started ===\n")

        // The method continues past the guard into `Intent(ACTION_CREATE_DOCUMENT)`, which an
        // android.jar stub cannot construct off-device; whatever that raises is swallowed
        // here, because the assertion is about which BRANCH ran, not about the framework.
        invokeSaveLogsToFile()

        // A non-empty buffer must NOT take the "nothing to save" branch.
        verify(exactly = 0) {
            Toast.makeText(any(), "No logs to save", Toast.LENGTH_SHORT)
        }
    }

    // =========================================================================
    // The redesigned toolbar
    // =========================================================================

    private val layout: String by lazy {
        val file = File("res/layout/swipe_debug_activity.xml")
        check(file.isFile) { "${file.path} not found — run with the project root as CWD." }
        file.readText()
    }

    private val source: String by lazy {
        val file = File("src/main/kotlin/tribixbite/cleverkeys/activities/SwipeDebugActivity.kt")
        check(file.isFile) { "${file.path} not found — run with the project root as CWD." }
        file.readText()
    }

    @Test
    fun toolbarOffersCopyClearAndSave() {
        for (id in listOf("back_button", "copy_button", "clear_button", "save_button")) {
            assertWithMessage("the redesigned toolbar must still declare @id/$id")
                .that(layout).contains("android:id=\"@+id/$id\"")
        }
        // Icon-only buttons: without a contentDescription they are unusable with TalkBack.
        for (description in listOf("debug_back", "debug_save_to_file", "debug_copy_to_clipboard", "debug_clear_logs")) {
            assertWithMessage("the toolbar icon for $description must stay described")
                .that(layout).contains("android:contentDescription=\"@string/$description\"")
        }
        val strings = File("res/values/strings.xml").readText()
        for (name in listOf("debug_back", "debug_save_to_file", "debug_copy_to_clipboard", "debug_clear_logs")) {
            assertWithMessage("the toolbar description string must exist: $name")
                .that(strings).contains("<string name=\"$name\"")
        }

        // Each toolbar affordance must be wired to its action — an unwired button is the
        // exact regression this row's claim is about.
        for ((button, handler) in mapOf(
            "copyButton" to "copyLogsToClipboard()",
            "clearButton" to "clearLogs()",
            "saveButton" to "saveLogsToFile()",
        )) {
            val wiring = Regex(
                """${Regex.escape(button)}\.setOnClickListener\s*\{\s*${Regex.escape(handler)}"""
            )
            assertWithMessage("$button must invoke $handler")
                .that(wiring.containsMatchIn(source)).isTrue()
        }
    }

    @Test
    fun copyPutsTheWholeBufferOnTheClipboard() {
        val body = Regex("""private fun copyLogsToClipboard\(\)\s*\{([\s\S]*?)\n    }""").find(source)
            ?.groupValues?.get(1)
            ?: throw AssertionError("SwipeDebugActivity no longer declares copyLogsToClipboard()")
        assertWithMessage("copy must take the full accumulated log, not the visible TextView")
            .that(body).contains("logBuffer.toString()")
        assertWithMessage("the clip must be labelled so the clipboard panel shows what it is")
            .that(body).contains("ClipData.newPlainText(\"Swipe Debug Logs\"")
        assertWithMessage("the clip must actually reach the system clipboard")
            .that(body).contains("setPrimaryClip(clip)")
    }

    @Test
    fun saveUsesTheStorageAccessFramework() {
        val body = Regex("""private fun saveLogsToFile\(\)\s*\{([\s\S]*?)\n    }""").find(source)
            ?.groupValues?.get(1)
            ?: throw AssertionError("SwipeDebugActivity no longer declares saveLogsToFile()")
        assertWithMessage("save must let the user choose the destination (SAF), not write blind")
            .that(body).contains("Intent.ACTION_CREATE_DOCUMENT")
        assertWithMessage("the picker must only offer openable destinations")
            .that(body).contains("Intent.CATEGORY_OPENABLE")
        assertWithMessage("debug logs are plain text")
            .that(body).contains("\"text/plain\"")
        assertWithMessage("the picker must be pre-named swipe_debug_<timestamp>.txt")
            .that(body).contains("\"swipe_debug_\$timestamp.txt\"")
        assertWithMessage("EXTRA_TITLE is what pre-names the document")
            .that(body).contains("Intent.EXTRA_TITLE")
    }
}
