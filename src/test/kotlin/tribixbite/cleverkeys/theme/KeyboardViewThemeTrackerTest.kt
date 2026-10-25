package tribixbite.cleverkeys.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Pixel 2026-10-25 (release from ac86bda5): editing the ACTIVE custom theme while the keyboard
 * was HIDDEN left the keyboard in its old colours until "Restart Keyboard". The theme-changed
 * receiver rebuilt only `if (isInputViewShown)`, and the stale-view check in
 * `onStartInputView` compared only the style id and the theme NAME — an edit changes neither,
 * so nothing ever rebuilt the view.
 *
 * [Ime] reproduces the service's theme lifecycle one call for one call: the receiver
 * (config refresh, then [KeyboardViewThemeTracker.shouldRebuildOnThemeBroadcast]),
 * `ConfigurationManager`'s `onThemeChanged` (always re-inflates), `onStartInputView`
 * ([KeyboardViewThemeTracker.shouldRebuildOnShow]) and `inflate_view` (stamps the view).
 * The drift pins at the bottom hold `CleverKeysService` to exactly those calls, because the
 * service itself cannot be constructed off-device.
 */
class KeyboardViewThemeTrackerTest {

    private companion object {
        const val DARK_STYLE = 0x7f0e0001 // any non-zero style id
        const val LIGHT_STYLE = 0x7f0e0002
        const val CUSTOM = "custom_1234"
        const val BOUND = "custom_bound"
    }

    private fun scheme(label: Color) = KeyboardColorScheme(
        keyDefault = Color.DarkGray, keyActivated = Color.Gray, keyLocked = Color.Blue,
        keyModifier = Color.DarkGray, keySpecial = Color.DarkGray,
        keyLabel = label, keySubLabel = Color.LightGray, keySecondaryLabel = Color.LightGray,
        keyBorder = Color.Black, keyBorderActivated = Color.White,
        swipeTrail = Color.Cyan, ripple = Color.White,
        suggestionText = Color.White, suggestionBackground = Color.Black,
        suggestionHighConfidence = Color.Green,
        keyboardBackground = Color.Black, keyboardSurface = Color.DarkGray,
    )

    /** The service's theme lifecycle; [inflations] counts keyboard-view re-inflations. */
    private class Ime(var styleId: Int, var themeName: String) {
        /** ThemeProvider's custom-theme store (the editor mutates this same instance). */
        val store = mutableMapOf<String, KeyboardColorScheme>()
        var shown = false
        var inflations = 0
        val schemeLookups = mutableListOf<String>()

        val tracker = KeyboardViewThemeTracker {
            KeyboardViewThemeStamp.of(styleId, themeName) { id -> schemeLookups += id; store[id] }
        }

        /** `inflate_view(R.layout.keyboard)`. */
        fun inflate() {
            inflations++
            tracker.onKeyboardViewInflated()
        }

        /** ACTION_THEME_CHANGED receiver (the config refresh already happened). */
        fun themeBroadcast() {
            if (tracker.shouldRebuildOnThemeBroadcast(shown)) inflate()
        }

        /** ConfigurationManager saw the style id or the theme name change. */
        fun configThemeChanged(styleId: Int, themeName: String) {
            this.styleId = styleId
            this.themeName = themeName
            inflate()
        }

        fun startInputView() {
            if (tracker.shouldRebuildOnShow()) inflate()
            shown = true
        }

        fun finishInputView() {
            shown = false
        }
    }

    /** A keyboard that was shown once with [theme] and then hidden. */
    private fun hiddenKeyboard(theme: String = CUSTOM, label: Color = Color.White): Ime =
        Ime(DARK_STYLE, theme).apply {
            store[theme] = scheme(label)
            startInputView()
            finishInputView()
            inflations = 0
        }

    @Test
    fun editingTheActiveCustomThemeWhileHiddenRebuildsOnTheNextShow() {
        val ime = hiddenKeyboard()

        ime.store[CUSTOM] = scheme(Color.Red) // Theme editor: Key Label → red, Save
        ime.themeBroadcast()
        assertWithMessage("a hidden keyboard is not rebuilt by the broadcast itself")
            .that(ime.inflations).isEqualTo(0)

        ime.startInputView()
        assertWithMessage(
            "the next onStartInputView must rebuild the view: the edit changed the colours, " +
                "not the style id or the theme name (Pixel 2026-10-25)"
        ).that(ime.inflations).isEqualTo(1)

        ime.finishInputView()
        ime.startInputView()
        assertWithMessage("once rebuilt, a later show must not rebuild again")
            .that(ime.inflations).isEqualTo(1)
    }

    @Test
    fun editingTheActiveCustomThemeWhileShownRebuildsAtOnceAndOnlyOnce() {
        val ime = hiddenKeyboard()
        ime.startInputView()

        ime.store[CUSTOM] = scheme(Color.Red)
        ime.themeBroadcast()
        assertWithMessage("a visible keyboard restyles live").that(ime.inflations).isEqualTo(1)

        ime.finishInputView()
        ime.startInputView()
        assertWithMessage("the live rebuild already applied the edit").that(ime.inflations).isEqualTo(1)
    }

    @Test
    fun aBroadcastThatChangedNothingDoesNotRebuild() {
        val ime = hiddenKeyboard()
        ime.startInputView()

        ime.themeBroadcast() // e.g. a save of an unchanged theme, or of a theme not displayed
        ime.store["custom_other"] = scheme(Color.Red)
        ime.themeBroadcast()
        assertWithMessage("no rebuild when the displayed colours did not change")
            .that(ime.inflations).isEqualTo(0)
    }

    @Test
    fun aThemeSwitchWhileHiddenIsRebuiltOnceByTheConfigListener() {
        val ime = hiddenKeyboard()
        ime.store["custom_new"] = scheme(Color.Yellow)

        // Theme Manager writes the `theme` pref (ConfigurationManager → onThemeChanged), then
        // sends the broadcast, which refreshes the config again.
        ime.configThemeChanged(DARK_STYLE, "custom_new")
        ime.themeBroadcast()
        ime.startInputView()
        assertWithMessage("one inflation, from onThemeChanged; neither the broadcast nor the show adds one")
            .that(ime.inflations).isEqualTo(1)
    }

    @Test
    fun editingAThemeBoundToTheCurrentLayoutWhileHiddenRebuildsOnTheNextShow() {
        // Per-layout binding (e237fa95): the EFFECTIVE themeName is the bound theme, while the
        // global `theme` preference names another one, so the editor's "active" check is false.
        val ime = hiddenKeyboard(theme = BOUND)

        ime.store[BOUND] = scheme(Color.Magenta)
        ime.themeBroadcast()
        ime.startInputView()
        assertWithMessage("the bound theme's edit must reach the keyboard too")
            .that(ime.inflations).isEqualTo(1)
    }

    @Test
    fun deletingTheDisplayedCustomThemeWhileHiddenRebuildsOnTheNextShow() {
        val ime = hiddenKeyboard(theme = BOUND)

        ime.store.remove(BOUND) // the view now falls back (ThemeProvider.FALLBACK_THEME_ID)
        ime.themeBroadcast()
        ime.startInputView()
        assertWithMessage("a deleted displayed theme must re-inflate into the fallback")
            .that(ime.inflations).isEqualTo(1)
    }

    @Test
    fun aSystemLightDarkFlipOfAnXmlThemeIsAStyleIdChange() {
        val ime = Ime(DARK_STYLE, "monet")
        ime.startInputView()
        ime.finishInputView()
        ime.inflations = 0

        // onConfigurationChanged → refresh → the style id flips → onThemeChanged
        ime.configThemeChanged(LIGHT_STYLE, "monet")
        ime.startInputView()
        assertWithMessage("re-inflated once, by onThemeChanged").that(ime.inflations).isEqualTo(1)

        // Without the listener (a refresh the listener missed) the show still catches it.
        ime.finishInputView()
        ime.styleId = DARK_STYLE
        ime.startInputView()
        assertWithMessage("a style-id change alone makes the view stale").that(ime.inflations).isEqualTo(2)
        assertWithMessage("XML themes take their colours from styles: no runtime-scheme lookup")
            .that(ime.schemeLookups).isEmpty()
    }

    @Test
    fun beforeTheConfigLoadsNothingIsStale() {
        val ime = Ime(0, "")
        assertWithMessage("style id 0 = config not loaded: no rebuild")
            .that(ime.tracker.shouldRebuildOnShow()).isFalse()
    }

    // ------------------------------------------------------------------ drift pins

    private fun source(path: String) = File(path).readText()

    private val service by lazy { source("src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt") }

    @Test
    fun theReceiverAsksTheTrackerInsteadOfRebuildingOnlyWhenShown() {
        val receiver = service.substringAfter("_themeChangeReceiver = object : BroadcastReceiver()")
            .substringBefore("val filter = IntentFilter(ACTION_THEME_CHANGED)")
        assertWithMessage("the receiver refreshes the config first")
            .that(receiver).contains("_configManager.refresh(resources)")
        assertWithMessage("…then lets the tracker decide (hidden → the next show rebuilds)")
            .that(receiver).contains("shouldRebuildOnThemeBroadcast(isInputViewShown)")
    }

    @Test
    fun onStartInputViewAndInflateViewUseTheTracker() {
        val onStart = service.substringAfter("override fun onStartInputView(")
            .substringBefore("\n    override fun ")
        assertWithMessage("onStartInputView must rebuild a view the tracker calls stale")
            .that(onStart).contains("shouldRebuildOnShow()")
        assertWithMessage(
            "…through the same teardown as onThemeChanged (emoji and clipboard pane views too): " +
                "a hidden edit has no onThemeChanged, so this branch is its only rebuild"
        ).that(onStart).contains("recreateThemedViews()")
        val onThemeChanged = service.substringAfter("override fun onThemeChanged(")
            .substringBefore("\n    }\n")
        assertWithMessage("onThemeChanged shares that teardown")
            .that(onThemeChanged).contains("recreateThemedViews()")
        val inflate = service.substringAfter("fun inflate_view(layout: Int): View")
            .substringBefore("\n    }\n")
        assertWithMessage("inflate_view must stamp every keyboard view it builds")
            .that(inflate).contains("onKeyboardViewInflated()")
    }

    @Test
    fun theEditorNotifiesTheKeyboardOnEverySave() {
        val activity = source("src/main/kotlin/tribixbite/cleverkeys/activities/ThemeSettingsActivity.kt")
        val editSave = activity.substringAfter("// Edit Theme Dialog").substringBefore("// Delete Confirmation")
        assertWithMessage("the edit dialog's save must notify the IME")
            .that(editSave).contains("notifyKeyboardThemeChanged(context)")
        assertWithMessage(
            "…on every save: the theme may be bound to the current layout (the editor's " +
                "'active' check sees only the global pref); the service skips the rebuild when " +
                "the displayed colours did not change"
        ).that(editSave).doesNotContain("if (savedActive)")
    }
}
