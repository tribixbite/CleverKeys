package tribixbite.cleverkeys

import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * MockK-based JVM tests for [WindowLayoutUtils] — the IME window/insets plumbing behind six
 * published release notes:
 *
 * | version | published note |
 * |---|---|
 * | v1.0.7  | "Fixed status/navigation bar overlay on OEM devices (Samsung, Xiaomi)" |
 * | v1.0.7  | "Fixed keyboard navigation bar transparency" |
 * | v1.1.73 | "Fix keyboard positioning on API 30-34 devices" |
 * | v1.1.73 | "Keyboard no longer overlaps system navigation bar" |
 * | v1.1.73 | "Added insets fallback for API 21-29" |
 * | v1.2.6 / v1.2.8 | "Nav bar overlap on Android 15" |
 *
 * ## How an SDK-branch ladder is testable off-device
 *
 * `Build.VERSION.SDK_INT` is a plain (non-`ConstantValue`) static field in `android.jar`, so it
 * can be written through `sun.misc.Unsafe` and each API branch driven deliberately — the idiom
 * `VibratorCompatTest` already uses, hardened here with an assertion that the write actually
 * landed instead of silently leaving `SDK_INT` at android.jar's 0.
 *
 * ## The one thing this tier cannot reach
 *
 * [WindowLayoutUtils.configureEdgeToEdge] ends by clearing the window background with
 * `ColorDrawable(Color.TRANSPARENT)`, and **every** android.jar constructor body is
 * `throw new RuntimeException("Stub!")` — including `Drawable()`, which rules out shadowing
 * the class with a test double. So the call cannot return here. That is turned into evidence
 * rather than a gap: [edgeToEdge_clearsTheWindowBackgroundOnEveryApiLevel] asserts the throw
 * originates *inside* `ColorDrawable`'s constructor on every API level, which is exactly the
 * claim "the background clear is unconditional" (v1.0.7 nav-bar transparency). The
 * `decorView.setBackgroundColor` line that follows it is out of reach in pure JVM and is
 * covered on-device by `SoftInputWindowLayoutInstrumentedTest`.
 */
class WindowLayoutUtilsTest {

    private companion object {
        /**
         * Not a valid `layoutInDisplayCutoutMode`; seeded before each call so "the branch left
         * the attributes alone" is provable rather than inferred from a 0 default.
         */
        const val CUTOUT_UNSET = -1
    }

    private var originalSdkInt = 0

    @Before
    fun setup() {
        originalSdkInt = Build.VERSION.SDK_INT
    }

    @After
    fun teardown() {
        setSdkInt(originalSdkInt)
        unmockkStatic(WindowLayoutUtils::class)
        unmockkStatic(WindowInsets.Type::class)
    }

    // =========================================================================
    // updateLayoutHeightOf — the "write only when it changed" contract
    // =========================================================================

    @Test
    fun updateLayoutHeightOf_window_writesBackOnlyWhenTheHeightChanges() {
        val window = mockk<Window>(relaxed = true)
        val lp = mockk<WindowManager.LayoutParams>(relaxed = true)
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        every { window.attributes } returns lp

        WindowLayoutUtils.updateLayoutHeightOf(window, ViewGroup.LayoutParams.WRAP_CONTENT)

        assertThat(lp.height).isEqualTo(ViewGroup.LayoutParams.WRAP_CONTENT)
        verify(exactly = 1) { window.attributes = lp }

        // Re-applying the same height must not re-assign: Window.setAttributes triggers a
        // relayout, and the IME calls this on every onStartInputView.
        WindowLayoutUtils.updateLayoutHeightOf(window, ViewGroup.LayoutParams.WRAP_CONTENT)
        verify(exactly = 1) { window.attributes = any() }
    }

    @Test
    fun updateLayoutHeightOf_view_writesBackOnlyWhenTheHeightChanges() {
        val view = mockk<View>(relaxed = true)
        val lp = mockk<ViewGroup.LayoutParams>(relaxed = true)
        lp.height = 0
        every { view.layoutParams } returns lp

        WindowLayoutUtils.updateLayoutHeightOf(view, ViewGroup.LayoutParams.MATCH_PARENT)
        assertThat(lp.height).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
        verify(exactly = 1) { view.layoutParams = lp }

        WindowLayoutUtils.updateLayoutHeightOf(view, ViewGroup.LayoutParams.MATCH_PARENT)
        verify(exactly = 1) { view.layoutParams = any() }
    }

    // =========================================================================
    // updateLayoutGravityOf — both supported LayoutParams types, and no others
    // =========================================================================

    @Test
    fun updateLayoutGravityOf_appliesBottomGravityToLinearAndFrameParams() {
        val linearView = mockk<View>(relaxed = true)
        val linearLp = mockk<LinearLayout.LayoutParams>(relaxed = true)
        linearLp.gravity = Gravity.TOP
        every { linearView.layoutParams } returns linearLp

        WindowLayoutUtils.updateLayoutGravityOf(linearView, Gravity.BOTTOM)
        assertThat(linearLp.gravity).isEqualTo(Gravity.BOTTOM)
        verify(exactly = 1) { linearView.layoutParams = linearLp }

        val frameView = mockk<View>(relaxed = true)
        val frameLp = mockk<FrameLayout.LayoutParams>(relaxed = true)
        frameLp.gravity = Gravity.TOP
        every { frameView.layoutParams } returns frameLp

        WindowLayoutUtils.updateLayoutGravityOf(frameView, Gravity.BOTTOM)
        assertThat(frameLp.gravity).isEqualTo(Gravity.BOTTOM)
        verify(exactly = 1) { frameView.layoutParams = frameLp }
    }

    @Test
    fun updateLayoutGravityOf_leavesUnsupportedLayoutParamsUntouched() {
        val view = mockk<View>(relaxed = true)
        // A bare ViewGroup.LayoutParams has no gravity: the `when` must fall through instead
        // of throwing on a host that nests the input area in something unexpected.
        every { view.layoutParams } returns mockk<ViewGroup.LayoutParams>(relaxed = true)

        WindowLayoutUtils.updateLayoutGravityOf(view, Gravity.BOTTOM)
        verify(exactly = 0) { view.layoutParams = any() }
    }

    // =========================================================================
    // configureEdgeToEdge — the API ladder (v1.0.7, v1.1.73, v1.2.6/v1.2.8)
    // =========================================================================


    /**
     * Every member of [WindowLayoutUtils] is `@JvmStatic`, so the object compiles to statics
     * only — `mockkObject` has no instance methods to intercept and the real body runs. Static
     * mocking plus `callOriginal()` is the working shape: [WindowLayoutUtils.configureEdgeToEdge]
     * is neutralised (it cannot return off-device) while every other member, including the
     * self-calls the method under test makes, executes for real.
     */
    private fun stubEdgeToEdgeOnly() {
        mockkStatic(WindowLayoutUtils::class)
        every { WindowLayoutUtils.configureEdgeToEdge(any()) } just Runs
        every {
            WindowLayoutUtils.updateSoftInputWindowLayoutParams(any(), any(), any())
        } answers { callOriginal() }
        every {
            WindowLayoutUtils.updateLayoutHeightOf(any<Window>(), any())
        } answers { callOriginal() }
        every {
            WindowLayoutUtils.updateLayoutHeightOf(any<View>(), any())
        } answers { callOriginal() }
        every {
            WindowLayoutUtils.updateLayoutGravityOf(any(), any())
        } answers { callOriginal() }
    }

    /** One [WindowLayoutUtils.configureEdgeToEdge] run at a forced API level. */
    private class Probe(
        val window: Window,
        val attrs: WindowManager.LayoutParams,
        val error: RuntimeException
    )

    private fun configureAt(sdkInt: Int): Probe {
        setSdkInt(sdkInt)
        val attrs = mockk<WindowManager.LayoutParams>(relaxed = true)
        attrs.layoutInDisplayCutoutMode = CUTOUT_UNSET
        val window = mockk<Window>(relaxed = true)
        every { window.attributes } returns attrs
        val error = assertThrows(RuntimeException::class.java) {
            WindowLayoutUtils.configureEdgeToEdge(window)
        }
        return Probe(window, attrs, error)
    }

    @Test
    fun edgeToEdge_api35_drawsThroughTheCutoutAndOptsOutOfAllFittedInsets() {
        // Android 15: the nav-bar overlap fix. ALWAYS + fitInsetsTypes(0) is what lets the
        // keyboard own the gesture-nav strip instead of being shoved above it.
        val probe = configureAt(35)
        assertThat(probe.attrs.layoutInDisplayCutoutMode)
            .isEqualTo(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS)
        verify(exactly = 1) { probe.attrs.setFitInsetsTypes(0) }
        verify(exactly = 1) { probe.window.setDecorFitsSystemWindows(false) }
    }

    @Test
    fun edgeToEdge_api30To34_usesShortEdgesAndStillOptsOutOfDecorFitting() {
        // v1.1.73 "Fix keyboard positioning on API 30-34 devices": the OEM scrim shows up
        // unless decor fitting is off, but SHORT_EDGES (not ALWAYS) keeps a notch usable.
        for (sdk in listOf(Build.VERSION_CODES.R, 31, 34)) {
            val probe = configureAt(sdk)
            assertThat(probe.attrs.layoutInDisplayCutoutMode)
                .isEqualTo(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES)
            verify(exactly = 1) { probe.window.setDecorFitsSystemWindows(false) }
            // setFitInsetsTypes is API 35-only here: applying it on 30-34 regressed the
            // keyboard behind the nav bar on OEM builds.
            verify(exactly = 0) { probe.attrs.setFitInsetsTypes(any()) }
        }
    }

    @Test
    fun edgeToEdge_api29_setsTheCutoutModeButNeverCallsTheApi30OnlyDecorApi() {
        // v1.1.73 "Added insets fallback for API 21-29" — API 29 is the top half of that
        // fallback: it has layoutInDisplayCutoutMode but not setDecorFitsSystemWindows.
        val probe = configureAt(Build.VERSION_CODES.Q)
        assertThat(probe.attrs.layoutInDisplayCutoutMode)
            .isEqualTo(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES)
        verify(exactly = 0) { probe.window.setDecorFitsSystemWindows(any()) }
        verify(exactly = 0) { probe.attrs.setFitInsetsTypes(any()) }
    }

    @Test
    fun edgeToEdge_below29_touchesNoWindowAttributesAtAll() {
        // The bottom half of the API 21-29 fallback: nothing but the background clear, because
        // neither API exists. Reading window.attributes at all would be a NewApi trap.
        for (sdk in listOf(24, 26, Build.VERSION_CODES.P)) {
            val probe = configureAt(sdk)
            assertThat(probe.attrs.layoutInDisplayCutoutMode).isEqualTo(CUTOUT_UNSET)
            verify(exactly = 0) { probe.window.attributes }
            verify(exactly = 0) { probe.window.setDecorFitsSystemWindows(any()) }
        }
    }

    // =========================================================================
    // configureEdgeToEdge — re-application must PROPAGATE (issue #167)
    // =========================================================================
    //
    // Window.getAttributes() hands back the window's live LayoutParams object;
    // mutating it in place only reaches the WindowManager when the window is
    // first added. On an already-showing IME window, propagation requires
    // Window.setAttributes(), which dispatches onWindowAttributesChanged →
    // ViewRootImpl relayout. Without the write-back, the per-onStartInputView
    // "re-application" of cutout mode + fitInsetsTypes(0) is a silent no-op on
    // a live window — a system-side reset (nav-mode change, window state churn
    // on Android 15) is then never repaired until the window is recreated,
    // matching #167's "back gesture fixes it until next time".

    @Test
    fun edgeToEdge_api35_writesTheMutatedAttributesBackToTheWindow() {
        val probe = configureAt(35)
        verify(exactly = 1) { probe.window.attributes = probe.attrs }
    }

    @Test
    fun edgeToEdge_api30To34_writesTheMutatedAttributesBackToTheWindow() {
        for (sdk in listOf(Build.VERSION_CODES.R, 31, 34)) {
            val probe = configureAt(sdk)
            verify(exactly = 1) { probe.window.attributes = probe.attrs }
        }
    }

    @Test
    fun edgeToEdge_api29_writesTheMutatedAttributesBackToTheWindow() {
        val probe = configureAt(Build.VERSION_CODES.Q)
        verify(exactly = 1) { probe.window.attributes = probe.attrs }
    }

    @Test
    fun edgeToEdge_below29_neverWritesWindowAttributes() {
        // The pre-29 branch touches no attributes, so it must not trigger a
        // relayout either (companion to edgeToEdge_below29_touchesNoWindowAttributesAtAll).
        for (sdk in listOf(24, 26, Build.VERSION_CODES.P)) {
            val probe = configureAt(sdk)
            verify(exactly = 0) { probe.window.attributes = any() }
        }
    }

    @Test
    fun edgeToEdge_clearsTheWindowBackgroundOnEveryApiLevel() {
        // v1.0.7 "Fixed keyboard navigation bar transparency": the white bar came from the
        // window's own background, so the clear must run on every branch — including the
        // pre-29 one that changes nothing else.
        for (sdk in listOf(24, Build.VERSION_CODES.Q, 34, 35)) {
            val probe = configureAt(sdk)
            assertThat(probe.error).hasMessageThat().isEqualTo("Stub!")
            // android.jar's stub bodies throw from the constructor itself (Drawable's, via
            // ColorDrawable's super call), so these two frames ARE the
            // `ColorDrawable(Color.TRANSPARENT)` allocation inside configureEdgeToEdge.
            val frames = probe.error.stackTrace.map { it.className + "." + it.methodName }
            assertThat(frames).contains("android.graphics.drawable.ColorDrawable.<init>")
            assertThat(frames).contains(
                "tribixbite.cleverkeys.WindowLayoutUtils.configureEdgeToEdge"
            )
        }
    }

    // =========================================================================
    // updateSoftInputWindowLayoutParams — v1.0.7 OEM overlay, v1.1.73 positioning
    // =========================================================================

    /**
     * Runs the real [WindowLayoutUtils.updateSoftInputWindowLayoutParams] with only
     * [WindowLayoutUtils.configureEdgeToEdge] stubbed out (it cannot return in pure JVM — see
     * the class doc).
     */
    private fun softInputProbe(isFullscreen: Boolean): Triple<Window, WindowManager.LayoutParams, FrameLayout.LayoutParams> {
        stubEdgeToEdgeOnly()

        val windowAttrs = mockk<WindowManager.LayoutParams>(relaxed = true)
        // MATCH_PARENT is the pre-fix value that produced the full-screen window and the white
        // bar during the show animation.
        windowAttrs.height = ViewGroup.LayoutParams.MATCH_PARENT
        val window = mockk<Window>(relaxed = true)
        every { window.attributes } returns windowAttrs

        val parentAttrs = mockk<FrameLayout.LayoutParams>(relaxed = true)
        parentAttrs.height = 0
        parentAttrs.gravity = Gravity.TOP
        val parent = mockk<FrameLayout>(relaxed = true)
        every { parent.layoutParams } returns parentAttrs

        val inputArea = mockk<View>(relaxed = true)
        every { inputArea.parent } returns parent

        WindowLayoutUtils.updateSoftInputWindowLayoutParams(window, inputArea, isFullscreen)

        verify(exactly = 1) { WindowLayoutUtils.configureEdgeToEdge(window) }
        verify(exactly = 1) { parent.setBackgroundColor(Color.TRANSPARENT) }
        verify(exactly = 1) { inputArea.setBackgroundColor(Color.TRANSPARENT) }
        return Triple(window, windowAttrs, parentAttrs)
    }

    @Test
    fun softInputWindow_isWrapContentAndBottomAligned_whenNotFullscreen() {
        val (_, windowAttrs, parentAttrs) = softInputProbe(isFullscreen = false)

        // WRAP_CONTENT, not MATCH_PARENT: a full-height IME window is what exposed the empty
        // area OEM skins painted white over the status/nav bars.
        assertThat(windowAttrs.height).isEqualTo(ViewGroup.LayoutParams.WRAP_CONTENT)
        assertThat(parentAttrs.height).isEqualTo(ViewGroup.LayoutParams.WRAP_CONTENT)
        assertThat(parentAttrs.gravity).isEqualTo(Gravity.BOTTOM)
    }

    @Test
    fun softInputWindow_inputAreaFillsTheWindow_whenFullscreen() {
        val (_, windowAttrs, parentAttrs) = softInputProbe(isFullscreen = true)

        // Only the input-area parent switches to MATCH_PARENT in fullscreen (extract) mode;
        // the window itself stays WRAP_CONTENT either way.
        assertThat(parentAttrs.height).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
        assertThat(parentAttrs.gravity).isEqualTo(Gravity.BOTTOM)
        assertThat(windowAttrs.height).isEqualTo(ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Test
    fun softInputWindow_survivesAnInputAreaWithNoViewParent() {
        stubEdgeToEdgeOnly()

        val windowAttrs = mockk<WindowManager.LayoutParams>(relaxed = true)
        windowAttrs.height = ViewGroup.LayoutParams.MATCH_PARENT
        val window = mockk<Window>(relaxed = true)
        every { window.attributes } returns windowAttrs

        val inputArea = mockk<View>(relaxed = true)
        every { inputArea.parent } returns null

        // The window height still has to be fixed even when the parent lookup fails, or the
        // white bar comes back on hosts that re-parent the input area.
        WindowLayoutUtils.updateSoftInputWindowLayoutParams(window, inputArea, false)
        assertThat(windowAttrs.height).isEqualTo(ViewGroup.LayoutParams.WRAP_CONTENT)
        verify(exactly = 1) { inputArea.setBackgroundColor(Color.TRANSPARENT) }
    }

    // =========================================================================
    // readSystemBarInsets — ONE shared API ladder for every inset read (#167 residual)
    // =========================================================================
    //
    // Keyboard2View kept two hand-rolled copies of this ladder (onApplyWindowInsets and
    // the onMeasure `== 0` recovery) that DISAGREED on API 30+: the recovery read
    // systemBars() only, the primary path systemBars()|displayCutout(). A value recovered
    // by one path could therefore differ from the value the real dispatch later served —
    // a one-frame jump at best, a persistent mismatch at worst. These tests pin the single
    // shared reader both sites now delegate to.

    /** WindowInsets.Type's statics are android.jar stubs; give the masks known bits. */
    private fun stubInsetTypeMasks() {
        mockkStatic(WindowInsets.Type::class)
        every { WindowInsets.Type.systemBars() } returns 0b001
        every { WindowInsets.Type.displayCutout() } returns 0b100
    }

    @Test
    fun readSystemBarInsets_api30Plus_readsSystemBarsUnionDisplayCutout() {
        stubInsetTypeMasks()
        for (sdk in listOf(Build.VERSION_CODES.R, 34, 35)) {
            setSdkInt(sdk)
            val wi = mockk<WindowInsets>(relaxed = true)
            // Only the union mask is stubbed with real values: if the implementation asks
            // for the wrong mask it gets a relaxed all-zero Insets and the assert fails.
            every { wi.getInsets(0b101) } returns insetsOf(left = 11, top = 99, right = 22, bottom = 33)
            assertThat(WindowLayoutUtils.readSystemBarInsets(wi))
                .isEqualTo(SystemBarInsets(left = 11, right = 22, bottom = 33))
        }
    }

    @Test
    fun readSystemBarInsets_api29_usesSystemWindowInsets() {
        setSdkInt(Build.VERSION_CODES.Q)
        val wi = mockk<WindowInsets>(relaxed = true)
        every { wi.systemWindowInsets } returns insetsOf(left = 5, top = 0, right = 7, bottom = 9)
        assertThat(WindowLayoutUtils.readSystemBarInsets(wi))
            .isEqualTo(SystemBarInsets(left = 5, right = 7, bottom = 9))
        // getInsets(int) is API 30+ — calling it here would be a NewApi trap.
        verify(exactly = 0) { wi.getInsets(any<Int>()) }
    }

    @Test
    fun readSystemBarInsets_api21To28_usesThePerSideGetters() {
        for (sdk in listOf(21, 24, Build.VERSION_CODES.P)) {
            setSdkInt(sdk)
            val wi = mockk<WindowInsets>(relaxed = true)
            every { wi.systemWindowInsetLeft } returns 1
            every { wi.systemWindowInsetRight } returns 2
            every { wi.systemWindowInsetBottom } returns 3
            assertThat(WindowLayoutUtils.readSystemBarInsets(wi))
                .isEqualTo(SystemBarInsets(left = 1, right = 2, bottom = 3))
            verify(exactly = 0) { wi.systemWindowInsets }
            verify(exactly = 0) { wi.getInsets(any<Int>()) }
        }
    }

    // =========================================================================
    // refreshSystemBarInsets — configuration/nav-mode change re-derivation (#167 residual)
    // =========================================================================
    //
    // The staleness half of #167: Keyboard2View cached _insets_bottom with only
    // `== 0`-guarded recoveries and never re-requested on a configuration change, so a
    // navigation-mode switch (3-button ~48dp bar → gesture pill) while the IME window
    // survived could serve the stale bottom inset — last row melded into / floating above
    // the nav bar — until full window recreation. Contract pinned here: a configuration
    // change RE-DERIVES from the live root window (the helper takes no cached value at
    // all, so a stale cache structurally cannot be served) and ALWAYS requests a fresh
    // framework dispatch as the authoritative follow-up.

    @Test
    fun configChangeRefresh_reDerivesFromTheLiveRootWindow_andRequestsAFreshDispatch() {
        stubInsetTypeMasks()
        setSdkInt(35) // the reporter's device class: Android 15, gesture nav
        val wi = mockk<WindowInsets>(relaxed = true)
        // Post-switch gesture pill: far smaller than any stale 3-button value.
        every { wi.getInsets(0b101) } returns insetsOf(left = 0, top = 0, right = 0, bottom = 24)
        val view = mockk<View>(relaxed = true)
        every { view.rootWindowInsets } returns wi

        val fresh = WindowLayoutUtils.refreshSystemBarInsets(view)

        assertThat(fresh).isEqualTo(SystemBarInsets(left = 0, right = 0, bottom = 24))
        verify(exactly = 1) { view.requestApplyInsets() }
    }

    @Test
    fun configChangeRefresh_withNoRootInsetsYet_reportsNothingButStillRequestsADispatch() {
        setSdkInt(Build.VERSION_CODES.R)
        val view = mockk<View>(relaxed = true)
        every { view.rootWindowInsets } returns null

        assertThat(WindowLayoutUtils.refreshSystemBarInsets(view)).isNull()
        // The dispatch request is the whole point when nothing can be read synchronously:
        // onApplyWindowInsets is the authoritative corrector.
        verify(exactly = 1) { view.requestApplyInsets() }
    }

    @Test
    fun configChangeRefresh_below23_skipsRootWindowInsetsButStillRequestsADispatch() {
        // View.getRootWindowInsets is API 23+; requestApplyInsets (API 20) still works.
        for (sdk in listOf(21, 22)) {
            setSdkInt(sdk)
            val view = mockk<View>(relaxed = true)
            assertThat(WindowLayoutUtils.refreshSystemBarInsets(view)).isNull()
            verify(exactly = 0) { view.rootWindowInsets }
            verify(exactly = 1) { view.requestApplyInsets() }
        }
    }

    // =========================================================================
    // Helper: build android.graphics.Insets with real field values
    // =========================================================================

    /**
     * `Insets.of()` is a stub (`throw new RuntimeException("Stub!")`) and its fields are
     * public final ints — unstubable through MockK. Same route as [setSdkInt]:
     * `Unsafe.allocateInstance` skips the stub constructor, `putInt` writes the finals,
     * and the trailing assert proves the writes landed.
     */
    private fun insetsOf(left: Int, top: Int, right: Int, bottom: Int): android.graphics.Insets {
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val unsafeClass = unsafe.javaClass
        val insetsClass = android.graphics.Insets::class.java
        val insets = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, insetsClass) as android.graphics.Insets
        val objectFieldOffset =
            unsafeClass.getMethod("objectFieldOffset", java.lang.reflect.Field::class.java)
        val putInt = unsafeClass.getMethod(
            "putInt",
            Object::class.java,
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        for ((name, value) in listOf("left" to left, "top" to top, "right" to right, "bottom" to bottom)) {
            val offset = objectFieldOffset.invoke(unsafe, insetsClass.getField(name)) as Long
            putInt.invoke(unsafe, insets, offset, value)
        }
        assertThat(insets.left).isEqualTo(left)
        assertThat(insets.bottom).isEqualTo(bottom)
        return insets
    }

    // =========================================================================
    // Helper: force Build.VERSION.SDK_INT
    // =========================================================================

    /**
     * Writes `Build.VERSION.SDK_INT` through `sun.misc.Unsafe` (Java 17+ removed the
     * `Field.modifiers` route). Unlike the older copy of this helper in `VibratorCompatTest`
     * this one **fails the test** if the write did not land, so a JDK change cannot silently
     * turn every branch assertion into an assertion about API 0.
     */
    private fun setSdkInt(sdkInt: Int) {
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val unsafeClass = unsafe.javaClass

        val field = Build.VERSION::class.java.getField("SDK_INT")
        val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field)
        val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field) as Long
        unsafeClass.getMethod(
            "putInt",
            Object::class.java,
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ).invoke(unsafe, base, offset, sdkInt)

        assertThat(Build.VERSION.SDK_INT).isEqualTo(sdkInt)
    }
}
