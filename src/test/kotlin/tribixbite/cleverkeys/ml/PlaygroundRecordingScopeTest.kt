package tribixbite.cleverkeys.ml

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.inputmethod.EditorInfo
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * I-1 / I-5 (comprehensive audit 2026-09-06): the Swipe Playground's recording session
 * boundary.
 *
 * The playground's privacy rationale ("explicit user session, so no LearningGate") rests
 * entirely on recording being scoped to the screen actually being IN FRONT of the user.
 * Two fences enforce that:
 *
 *  1. **The recorder fails closed on foreign editors** ([recorder gate tests]): even if
 *     the IME's debug flag is stale (activity backgrounded, hostile broadcast — I-8),
 *     [PlaygroundTraceRecorder.recordAndBroadcast] verifies the BOUND EDITOR belongs to
 *     this app before persisting or broadcasting anything. A swipe typed into a
 *     messenger can never land in swipe_ml_data.db via the playground path.
 *
 *  2. **The activity binds the flag to visibility** ([source pins]): debug mode is
 *     enabled in `onStart` and disabled in `onStop` — Home-backgrounding the activity
 *     (stopped, not destroyed) turns recording OFF. The old onCreate/onDestroy binding
 *     left it ON for every subsequent swipe in every app.
 *
 * Also pinned here (I-5): the playground's Export button must stream the export through
 * the cursor-based OutputStream path — the whole-table-in-memory export
 * (`loadAllData()` + `JSONArray` + `toString(2)`) materialized the table three times
 * over and is exactly what the streaming overload was written to avoid on the 256 MB
 * heap-limit devices.
 *
 * Mock tier: `InputMethodService`/`EditorInfo` come from android.jar stubs; MockK
 * intercepts. Run with `scripts/gradle-guard.sh runMockTests -PtestClass=ml.PlaygroundRecordingScopeTest`.
 */
class PlaygroundRecordingScopeTest {

    private val ownPackage = "tribixbite.cleverkeys"

    private lateinit var store: SwipeMLDataStore

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        store = mockk(relaxed = true)
        mockkObject(SwipeMLDataStore.Companion)
        every { SwipeMLDataStore.getInstance(any()) } returns store
    }

    @After
    fun teardown() = unmockkAll()

    // ------------------------------------------------------------------ fixtures

    /**
     * An IME-service context whose bound editor reports [editorPackage]. The service's
     * own package is always [ownPackage] — exactly the production shape, where
     * SuggestionHandler passes the CleverKeysService itself as the context.
     */
    private fun imeContext(editorPackage: String?): InputMethodService {
        val info = ObjenesisStd().newInstance(EditorInfo::class.java)
        info.packageName = editorPackage
        val ims = mockk<InputMethodService>(relaxed = true)
        every { ims.packageName } returns ownPackage
        every { ims.currentInputEditorInfo } returns info
        return ims
    }

    /** A valid captured swipe (2 points, 2 keys) that the store would accept. */
    private fun capturedSwipe(): SwipeMLData {
        val data = SwipeMLData("", "swipe_capture", 1080, 2400, 640, "qwerty", "ctc")
        val t0 = System.currentTimeMillis()
        data.addRawPoint(108f, 240f, t0)
        data.addRawPoint(540f, 1200f, t0 + 50)
        data.addRegisteredKey("h")
        data.addRegisteredKey("i")
        return data
    }

    // ------------------------------------------------- I-1: the recorder's package fence

    @Test
    fun aSwipeTypedInAForeignAppIsNeverPersisted() {
        val context = imeContext("com.example.messenger")

        PlaygroundTraceRecorder.recordAndBroadcast(
            context, capturedSwipe(), "secret", engineWordCount = 3, storedGlobally = false
        )

        verify(exactly = 0) { store.storeSwipeData(any()) }
        // The live-panel payload carries the committed word too — a stale flag must not
        // broadcast foreign-app typing either, even inside the app's own package fence.
        verify(exactly = 0) { context.sendBroadcast(any()) }
    }

    @Test
    fun anEditorWithNoPackageIsTreatedAsForeign() {
        // Fail closed: recording requires POSITIVE confirmation the editor is ours.
        val context = imeContext(null)

        PlaygroundTraceRecorder.recordAndBroadcast(
            context, capturedSwipe(), "secret", engineWordCount = 3, storedGlobally = false
        )

        verify(exactly = 0) { store.storeSwipeData(any()) }
    }

    @Test
    fun aSwipeInThePlaygroundsOwnEditorIsStillRecorded() {
        val context = imeContext(ownPackage)
        val row = slot<SwipeMLData>()
        every { store.storeSwipeData(capture(row)) } just Runs

        PlaygroundTraceRecorder.recordAndBroadcast(
            context, capturedSwipe(), "hello", engineWordCount = 3, storedGlobally = false
        )

        verify(exactly = 1) { store.storeSwipeData(any()) }
        assertThat(row.captured.targetWord).isEqualTo("hello")
        assertThat(row.captured.collectionSource)
            .isEqualTo(PlaygroundTraceRecorder.SOURCE_PLAYGROUND)
    }

    @Test
    fun aGloballyStoredSwipeIsNotDoubleRecordedEvenInOurOwnEditor() {
        val context = imeContext(ownPackage)

        PlaygroundTraceRecorder.recordAndBroadcast(
            context, capturedSwipe(), "hello", engineWordCount = 3, storedGlobally = true
        )

        verify(exactly = 0) { store.storeSwipeData(any()) }
    }

    // --------------------------------------- I-1: the activity's visibility binding

    private val activitySource: String by lazy {
        val file = File("src/main/kotlin/tribixbite/cleverkeys/activities/SwipeDebugActivity.kt")
        check(file.isFile) { "${file.path} not found — run with the project root as CWD." }
        file.readText()
    }

    /** Extract one member function's body from the activity source. */
    private fun activityFun(name: String): String? =
        Regex("""fun $name\([^)]*\)\s*\{([\s\S]*?)\n    }""").find(activitySource)
            ?.groupValues?.get(1)

    @Test
    fun debugModeIsEnabledOnStartNotOnCreate() {
        val onStart = activityFun("onStart")
        assertWithMessage(
            "SwipeDebugActivity must override onStart and enable debug mode there — " +
                "onCreate-scoped enabling leaves recording ON while the activity is " +
                "backgrounded (the I-1 privacy hole)"
        ).that(onStart).isNotNull()
        assertThat(onStart).contains("setDebugMode(true)")

        val onCreate = activityFun("onCreate")
        assertWithMessage("onCreate must no longer enable debug mode")
            .that(onCreate ?: "").doesNotContain("setDebugMode(true)")
    }

    @Test
    fun debugModeIsDisabledOnStopSoBackgroundingStopsRecording() {
        val onStop = activityFun("onStop")
        assertWithMessage(
            "SwipeDebugActivity must override onStop and disable debug mode there — " +
                "Home-backgrounding stops the activity without destroying it"
        ).that(onStop).isNotNull()
        assertThat(onStop).contains("setDebugMode(false)")
    }

    // ------------------------------------------------- I-5: the export must stream

    @Test
    fun theFileExportStreamsInsteadOfMaterializingTheWholeTable() {
        val file = File("src/main/kotlin/tribixbite/cleverkeys/ml/SwipeMLDataStore.kt")
        check(file.isFile) { "${file.path} not found — run with the project root as CWD." }
        val source = file.readText()

        val body = source.substringAfter("fun exportToJSON(): File")
            .substringBefore("fun exportToNDJSON(): File")
        assertWithMessage("exportToJSON(): File was renamed or moved — re-point this guard")
            .that(body).isNotEmpty()

        assertWithMessage(
            "the File export (the playground's Export button) must not load the whole " +
                "table into memory — that is the OOM the streaming overload exists to avoid"
        ).that(body).doesNotContain("loadAllData()")
        assertWithMessage("the File export must delegate to the streaming OutputStream overload")
            .that(body).contains("exportToJSON(")
    }
}
