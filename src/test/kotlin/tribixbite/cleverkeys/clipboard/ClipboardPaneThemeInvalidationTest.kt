package tribixbite.cleverkeys.clipboard

import android.util.Log
import android.view.ViewGroup
import android.widget.ImageView
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import io.mockk.mockkObject
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.ClipboardManager
import tribixbite.cleverkeys.ClipboardPaneThemePolicy
import tribixbite.cleverkeys.ClipboardTab
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.Theme
import tribixbite.cleverkeys.theme.ThemeProvider

/**
 * gh #130 residual: the clipboard pane is inflated ONCE (lazily) under the theme active at
 * first open, and `applyRuntimeThemeColors()` paints it once at that moment. `setConfig()`
 * then only re-applied tab visibility — so switching themes (or editing the active custom
 * theme's colors) while the IME service lives left the cached pane painted with the OLD
 * theme until a keyboard restart. That is the reporter's persistent symptom: create a custom
 * theme, set it active, open the clipboard → previous theme's colors.
 *
 * Contract pinned here: when a pane is cached and the effective theme signature changes,
 * `setConfig` must drop the cached pane so the next open re-inflates + repaints under the
 * new theme. Signature equality itself is pure logic — see ClipboardPaneThemePolicyTest.
 */
class ClipboardPaneThemeInvalidationTest {

    private val objenesis = ObjenesisStd()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After
    fun teardown() = unmockkAll()

    /** Config with only what the theme-signature path and applyTabVisibility read. */
    private fun configWith(themeName: String): Config {
        val cfg = objenesis.newInstance(Config::class.java)
        cfg.themeName = themeName
        cfg.theme = 0
        cfg.clipboard_pinned_enabled = true
        cfg.clipboard_todo_enabled = true
        return cfg
    }

    /**
     * The service's resource configuration. `fontScale` and `densityDpi` are public FIELDS, so
     * on a relaxed mock they are ordinary storage a test can change in place, the way the
     * platform updates the resources before it calls `onConfigurationChanged` (the android.jar
     * constructor throws "Stub!" in runMockTests; getters such as `locales` return relaxed mocks).
     */
    private val resourceConfig = mockk<android.content.res.Configuration>(relaxed = true).apply {
        fontScale = 1.0f
        densityDpi = 420
    }

    /** Context whose resources serve [resourceConfig]. */
    private fun contextServingResourceConfig(): android.content.Context = mockk(relaxed = true) {
        every { resources } returns mockk(relaxed = true) {
            every { configuration } returns resourceConfig
        }
    }

    /** Manager with a cached (already-inflated) pane, as after a first clipboard open. */
    private fun managerWithCachedPane(themeName: String): Pair<ClipboardManager, ViewGroup> {
        val pane = mockk<ViewGroup>(relaxed = true)
        val mgr = objenesis.newInstance(ClipboardManager::class.java)
        mgr.setField("context", contextServingResourceConfig())
        mgr.setField("clipboardPane", pane)
        mgr.setField("tabPinned", mockk<ImageView>(relaxed = true))
        mgr.setField("tabTodos", mockk<ImageView>(relaxed = true))
        mgr.setField("currentTab", ClipboardTab.HISTORY)
        mgr.setField("config", configWith(themeName))
        return mgr to pane
    }

    @Test
    fun switchingThemesWhileAPaneIsCachedDropsTheCachedPane() {
        val (mgr, _) = managerWithCachedPane("custom_reporters_theme")

        // User picks a different theme; ConfigPropagator pushes the new Config.
        mgr.setConfig(configWith("dark"))

        assertWithMessage(
            "#130: the cached clipboard pane was inflated and painted under the previous " +
                "theme. After a theme change setConfig must drop it so the next open " +
                "re-inflates under the new theme — otherwise the pane keeps the old " +
                "colors until keyboard restart."
        ).that(mgr.getField("clipboardPane")).isNull()
    }

    @Test
    fun unchangedThemeKeepsTheCachedPane() {
        val (mgr, pane) = managerWithCachedPane("dark")
        // Signature recorded at pane-build time for an XML-style theme.
        recordSignatureAsAtInflation(mgr)

        // Any unrelated pref change re-propagates the config with the same theme.
        mgr.setConfig(configWith("dark"))

        assertWithMessage(
            "setConfig fires on every config propagation — an unchanged theme must not " +
                "cost a pane re-inflation (it would reset scroll/search state for nothing)"
        ).that(mgr.getField("clipboardPane")).isSameInstanceAs(pane)
    }

    /**
     * Records the signature the pane would get at inflation time: the same private reader
     * [ClipboardManager.setConfig] compares against, so the test needs no knowledge of which
     * fields the signature holds.
     */
    private fun recordSignatureAsAtInflation(mgr: ClipboardManager) {
        val read = ClipboardManager::class.java.getDeclaredMethod("currentThemeSignature")
        read.isAccessible = true
        mgr.setField("appliedThemeSignature", read.invoke(mgr))
    }

    /**
     * Item 4 finding (Saga, 2026-10-28): after the user changed the system font size, the
     * clipboard pane kept the old text size until the IME process restarted. The pane is
     * inflated once and its sp sizes are resolved to pixels at that moment; the theme-only
     * signature never changed, so `setConfig` (which `onConfigurationChanged` reaches through
     * `refresh_config`) kept the stale pane.
     */
    @Test
    fun aFontScaleChangeWhileAPaneIsCachedDropsTheCachedPane() {
        val (mgr, _) = managerWithCachedPane("dark")
        recordSignatureAsAtInflation(mgr)

        resourceConfig.fontScale = 2.0f  // Settings > Display > Font size, the IME stays alive
        mgr.setConfig(configWith("dark"))

        assertWithMessage(
            "a font-scale change must drop the cached clipboard pane so the next open " +
                "re-inflates at the new text size (it stayed 1.0-sized until an IME restart)"
        ).that(mgr.getField("clipboardPane")).isNull()
    }

    /** Display size (Settings > Display > Display size) changes densityDpi: same staleness. */
    @Test
    fun aDisplaySizeChangeWhileAPaneIsCachedDropsTheCachedPane() {
        val (mgr, _) = managerWithCachedPane("dark")
        recordSignatureAsAtInflation(mgr)

        resourceConfig.densityDpi = 480
        mgr.setConfig(configWith("dark"))

        assertWithMessage("a density change must drop the cached clipboard pane")
            .that(mgr.getField("clipboardPane")).isNull()
    }

    /** The guard on the other side: an unchanged scale costs no re-inflation. */
    @Test
    fun unchangedFontScaleKeepsTheCachedPane() {
        val (mgr, pane) = managerWithCachedPane("dark")
        recordSignatureAsAtInflation(mgr)

        mgr.setConfig(configWith("dark"))

        assertWithMessage("an unchanged theme and resource configuration must keep the pane")
            .that(mgr.getField("clipboardPane")).isSameInstanceAs(pane)
    }

    @Test
    fun editingTheActiveCustomThemesColorsDropsThePaneEvenThoughTheNameIsUnchanged() {
        val (mgr, _) = managerWithCachedPane("custom_abc")
        // Same resource configuration as now: only the colors may differ in this test.
        val inflation = ClipboardManager::class.java.getDeclaredMethod("currentInflationConfig")
            .apply { isAccessible = true }.invoke(mgr) as ClipboardPaneThemePolicy.InflationConfig
        mgr.setField(
            "appliedThemeSignature",
            ClipboardPaneThemePolicy.ThemeSignature(
                "custom_abc", 0,
                ClipboardPaneThemePolicy.RuntimeColors(0xFF102030.toInt(), 1, 2, 3, 4),
                inflation,
            )
        )
        // The user edited the theme's colors; ThemeProvider now serves different values.
        // Theme's color fields are @JvmField (field reads — unstubable by MockK), so use
        // an Objenesis instance: every color is 0, which differs from the applied 1..4.
        val edited = objenesis.newInstance(Theme::class.java)
        mockkObject(ThemeProvider.Companion)
        every { ThemeProvider.getInstance(any()) } returns mockk {
            every { getTheme("custom_abc") } returns edited
        }

        mgr.setConfig(configWith("custom_abc"))

        assertWithMessage(
            "#130: editing the active custom theme keeps its name — the changed color " +
                "VALUES must still drop the cached pane"
        ).that(mgr.getField("clipboardPane")).isNull()
    }

    @Test
    fun invalidationPreservesTheCloseCallbackAndTabChoice() {
        val (mgr, _) = managerWithCachedPane("custom_reporters_theme")
        mgr.setField("currentTab", ClipboardTab.PINNED)
        val cfg = configWith("custom_reporters_theme")
        mgr.setField("config", cfg)
        var closes = 0
        mgr.setOnCloseCallback { closes++ }

        mgr.setConfig(configWith("dark"))

        assertWithMessage("pane must be dropped").that(mgr.getField("clipboardPane")).isNull()
        // KeyboardReceiver wires the close callback ONCE at service init — invalidation
        // (unlike full cleanup) must not sever it, or close taps after a theme change no-op.
        assertWithMessage("close callback severed by theme invalidation")
            .that(mgr.getField("onCloseCallback")).isNotNull()
        assertWithMessage("the user's tab choice must survive a theme change")
            .that(mgr.getField("currentTab")).isEqualTo(ClipboardTab.PINNED)
    }

    /**
     * 2026-10-08 audit: a confirmed bulk action finishes asynchronously. When the pane is
     * rebuilt meanwhile (theme change → invalidatePane; keyboard hide → cleanup) its result
     * line ("Deleted X of N") used to land on a null view and was lost. The manager keeps it
     * and getClipboardPane re-renders it on the next pane.
     */
    @Test
    fun aBulkResultArrivingAfterThePaneWasDroppedIsKept() {
        val (mgr, _) = managerWithCachedPane("custom_reporters_theme")
        val oldLine = mockk<android.widget.TextView>(relaxed = true)
        mgr.setField("bulkFeedback", oldLine)

        mgr.setConfig(configWith("dark"))  // pane rebuilt mid-action: the views are gone
        assertWithMessage("pane must be dropped").that(mgr.getField("bulkFeedback")).isNull()
        val show = ClipboardManager::class.java.getDeclaredMethod("showBulkFeedback", CharSequence::class.java)
        show.isAccessible = true
        show.invoke(mgr, "Deleted 3 of 4")

        assertWithMessage("the result must survive the rebuild for the next pane to render")
            .that(mgr.bulkFeedbackText()?.toString()).isEqualTo("Deleted 3 of 4")
        // And the source-level contract: a newly built pane renders the kept line.
        val source = java.io.File("src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardManager.kt").readText()
        val build = source.substringAfter("fun getClipboardPane(").substringBefore("\n    fun ")
        assertWithMessage("getClipboardPane must re-render the kept result line")
            .that(build).contains("lastBulkFeedback?.let { renderBulkFeedback(it) }")
    }

    private fun Any.setField(name: String, value: Any?) {
        val field = javaClass.declaredFields.firstOrNull { it.name == name }
        assertWithMessage(
            "field '$name' not found on ${javaClass.simpleName} — it was renamed or removed; " +
                "declared: ${javaClass.declaredFields.map { it.name }}"
        ).that(field).isNotNull()
        field!!.isAccessible = true
        field.set(this, value)
    }

    private fun Any.getField(name: String): Any? {
        val field = javaClass.declaredFields.firstOrNull { it.name == name }
        assertWithMessage("field '$name' not found on ${javaClass.simpleName}")
            .that(field).isNotNull()
        field!!.isAccessible = true
        return field.get(this)
    }
}
