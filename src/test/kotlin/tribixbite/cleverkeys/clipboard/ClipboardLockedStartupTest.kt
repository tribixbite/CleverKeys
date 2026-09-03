package tribixbite.cleverkeys.clipboard

import android.content.Context
import android.os.UserManager
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.ClipboardHistoryService
import tribixbite.cleverkeys.DirectBootManager

/**
 * v1.1.76: "Clipboard pane blocked while device is locked".
 *
 * Release-record row `clipboard/ClipboardHistoryService.kt#ClipboardHistoryService`,
 * PRESENT-UNTESTED. The claim is Direct Boot correctness: clipboard history is SQLite in
 * Credential-Encrypted storage, which does not exist before first unlock, so the IME's
 * `onCreate` must NOT construct the service on a locked device — it must wait.
 *
 * `ClipboardHistoryService.on_startup` is the decision point, and it is what this file drives.
 * The negative ("nothing is constructed") is the whole promise, so the positive controls
 * matter: on an unlocked device the service IS created immediately, and the deferred callback
 * DOES create it when unlock arrives — otherwise "blocked while locked" would be
 * indistinguishable from "clipboard broken".
 *
 * `Build.VERSION.SDK_INT` is forced with the Unsafe helper the neighbouring clipboard/private
 * copy tests use; under the android.jar stubs it reads 0, which would take the pre-N arm and
 * make the Direct Boot branch unreachable.
 */
class ClipboardLockedStartupTest {

    private lateinit var context: Context
    private lateinit var appContext: Context
    private lateinit var userManager: UserManager
    private lateinit var directBoot: DirectBootManager
    private lateinit var service: ClipboardHistoryService
    private lateinit var pasteCallback: ClipboardHistoryService.ClipboardPasteCallback

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        setSdkInt(34)

        userManager = mockk()
        appContext = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.applicationContext } returns appContext
        every { context.getSystemService(Context.USER_SERVICE) } returns userManager

        directBoot = mockk(relaxed = true)
        mockkObject(DirectBootManager.Companion)
        every { DirectBootManager.getInstance(any()) } returns directBoot

        service = mockk(relaxed = true)
        mockkObject(ClipboardHistoryService.Companion)
        every { ClipboardHistoryService.get_service(any()) } returns service

        pasteCallback = mockk(relaxed = true)
        clearPendingStartupState()
    }

    @After
    fun teardown() {
        clearPendingStartupState()
        setSdkInt(0)
        unmockkAll()
    }

    // -------------------------------------------------------------- locked device

    @Test
    fun onALockedDeviceTheServiceIsNotConstructedAtAll() {
        every { userManager.isUserUnlocked } returns false

        ClipboardHistoryService.on_startup(context, pasteCallback)

        // Clipboard history lives in Credential-Encrypted storage; touching it before first
        // unlock is the crash v1.1.76 fixed, so get_service must not be called at all.
        verify(exactly = 0) { ClipboardHistoryService.get_service(any()) }
        verify(exactly = 0) { service.registerClipboardListener() }
    }

    @Test
    fun aLockedStartupRegistersExactlyOneUnlockCallback() {
        every { userManager.isUserUnlocked } returns false

        ClipboardHistoryService.on_startup(context, pasteCallback)

        verify(exactly = 1) { DirectBootManager.getInstance(context) }
        verify(exactly = 1) { directBoot.registerUnlockCallback(any()) }
    }

    @Test
    fun theDeferredCallbackInitialisesTheServiceWhenUnlockArrives() {
        every { userManager.isUserUnlocked } returns false
        val deferred = slot<() -> Unit>()
        every { directBoot.registerUnlockCallback(capture(deferred)) } returns Unit

        ClipboardHistoryService.on_startup(context, pasteCallback)
        verify(exactly = 0) { ClipboardHistoryService.get_service(any()) }

        // The device is unlocked; DirectBootManager fires what was registered.
        deferred.captured.invoke()

        // "Blocked while locked" must mean DEFERRED, not dropped — the clipboard has to start
        // working after unlock without restarting the keyboard.
        verify(exactly = 1) { ClipboardHistoryService.get_service(appContext) }
        verify(exactly = 1) { service.registerClipboardListener() }
    }

    @Test
    fun theDeferredContextAndCallbackAreReleasedOnceUsed() {
        every { userManager.isUserUnlocked } returns false
        val deferred = slot<() -> Unit>()
        every { directBoot.registerUnlockCallback(capture(deferred)) } returns Unit

        ClipboardHistoryService.on_startup(context, pasteCallback)
        assertWithMessage("while waiting, the app context is parked in a static")
            .that(pendingContext()).isSameInstanceAs(appContext)

        deferred.captured.invoke()

        assertWithMessage(
            "the parked static must be cleared after use — it is a process-lifetime field and " +
                "the file's @SuppressLint asserts it only ever holds an applicationContext"
        ).that(pendingContext()).isNull()
        assertThat(pendingCallback()).isNull()
    }

    @Test
    fun onlyTheApplicationContextIsParkedNotTheImeService() {
        every { userManager.isUserUnlocked } returns false

        ClipboardHistoryService.on_startup(context, pasteCallback)

        assertWithMessage(
            "parking the IME service context for an unbounded wait would leak it until unlock"
        ).that(pendingContext()).isNotSameInstanceAs(context)
        assertThat(pendingContext()).isSameInstanceAs(appContext)
    }

    // ------------------------------------------------------------ unlocked device

    @Test
    fun onAnUnlockedDeviceTheServiceStartsImmediatelyAndNothingIsDeferred() {
        every { userManager.isUserUnlocked } returns true

        ClipboardHistoryService.on_startup(context, pasteCallback)

        verify(exactly = 1) { ClipboardHistoryService.get_service(context) }
        verify(exactly = 1) { service.registerClipboardListener() }
        verify(exactly = 0) { directBoot.registerUnlockCallback(any()) }
        assertWithMessage("no wait means nothing is parked")
            .that(pendingContext()).isNull()
    }

    @Test
    fun aDeviceWithoutDirectBootIsTreatedAsUnlocked() {
        // Pre-N has no Direct Boot at all: `isUserUnlocked` is unanswerable there, and the
        // pre-N arm must NOT defer forever (nothing would ever fire the unlock callback).
        setSdkInt(23)
        every { userManager.isUserUnlocked } returns false

        ClipboardHistoryService.on_startup(context, pasteCallback)

        verify(exactly = 1) { ClipboardHistoryService.get_service(context) }
        verify(exactly = 0) { directBoot.registerUnlockCallback(any()) }
    }

    @Test
    fun aRomThatCannotAnswerTheUnlockQuestionFailsOpen() {
        // No UserManager (some minimal ROMs / test harnesses): the service must still start,
        // because refusing to start would disable clipboard history permanently on that device.
        every { context.getSystemService(Context.USER_SERVICE) } returns null

        ClipboardHistoryService.on_startup(context, pasteCallback)

        verify(exactly = 1) { ClipboardHistoryService.get_service(context) }
        verify(exactly = 0) { directBoot.registerUnlockCallback(any()) }
    }

    // ------------------------------------------------------------------ reflection

    private fun companionField(name: String): java.lang.reflect.Field {
        val companion = ClipboardHistoryService.Companion
        val field = companion.javaClass.declaredFields.firstOrNull { it.name == name }
            ?: ClipboardHistoryService::class.java.declaredFields.firstOrNull { it.name == name }
        assertWithMessage(
            "static '$name' not found on ClipboardHistoryService — it was renamed or removed; " +
                "companion: ${companion.javaClass.declaredFields.map { it.name }}, " +
                "class: ${ClipboardHistoryService::class.java.declaredFields.map { it.name }}"
        ).that(field).isNotNull()
        return field!!.apply { isAccessible = true }
    }

    private fun pendingContext(): Any? = companionField("_pendingContext").get(null)

    private fun pendingCallback(): Any? = companionField("_pendingCallback").get(null)

    private fun clearPendingStartupState() {
        companionField("_pendingContext").set(null, null)
        companionField("_pendingCallback").set(null, null)
    }

    /**
     * Set `Build.VERSION.SDK_INT` via sun.misc.Unsafe (Java 17+ removed Field.modifiers access).
     * The `initialize = true` forName is load-bearing — see the note in
     * [ClipboardCaptureExclusionTest].
     */
    private fun setSdkInt(sdkInt: Int) {
        Class.forName("android.os.Build\$VERSION", true, javaClass.classLoader)
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val unsafeClass = unsafe.javaClass
        val field = android.os.Build.VERSION::class.java.getField("SDK_INT")
        val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field)
        val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field) as Long
        unsafeClass.getMethod(
            "putInt", Object::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType
        ).invoke(unsafe, base, offset, sdkInt)
        assertWithMessage("SDK_INT must actually be forced, or the API-gated arms are unreachable")
            .that(android.os.Build.VERSION.SDK_INT).isEqualTo(sdkInt)
    }
}
