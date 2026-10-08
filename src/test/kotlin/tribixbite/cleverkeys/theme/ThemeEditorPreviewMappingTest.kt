package tribixbite.cleverkeys.theme

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.Theme

/**
 * Roadmap §4.1: the theme-editor preview maps the editor's [KeyboardColorScheme] onto the
 * renderer through [ThemeEditorPreview]. Every one of the 17 editable colours must reach
 * the renderer field (or trail colour) the keyboard itself uses — and ONLY that field, so
 * an edit in the creator changes exactly the part of the preview it names.
 *
 * Mock tier for the same reason as ThemeCreatorFieldWiringTest: Theme's runtime
 * constructor goes through android.graphics.Color statics and the cached key font.
 */
class ThemeEditorPreviewMappingTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        mockkStatic(Color::class)
        every { Color.argb(any<Int>(), any<Int>(), any<Int>(), any<Int>()) } answers {
            (arg<Int>(0) shl 24) or (arg<Int>(1) shl 16) or (arg<Int>(2) shl 8) or arg<Int>(3)
        }
        every { Color.red(any<Int>()) } answers { (firstArg<Int>() shr 16) and 0xFF }
        every { Color.green(any<Int>()) } answers { (firstArg<Int>() shr 8) and 0xFF }
        every { Color.blue(any<Int>()) } answers { firstArg<Int>() and 0xFF }
        every { Color.colorToHSV(any<Int>(), any<FloatArray>()) } just runs
        every { Color.HSVToColor(any<FloatArray>()) } returns 0
        Theme::class.java.getDeclaredField("_key_font")
            .apply { isAccessible = true }
            .set(null, mockk<Typeface>())
        context = mockk(relaxed = true)
    }

    @After
    fun teardown() {
        Theme::class.java.getDeclaredField("_key_font")
            .apply { isAccessible = true }
            .set(null, null)
        unmockkAll()
    }

    /** Opaque, pairwise-distinct sentinel in every editable field (composite = identity). */
    private fun sentinelScheme(): KeyboardColorScheme = KeyboardColorScheme(
        keyDefault = ComposeColor(0xFF010203),
        keyActivated = ComposeColor(0xFF040506),
        keyLocked = ComposeColor(0xFF070809),
        keyModifier = ComposeColor(0xFF0A0B0C),
        keySpecial = ComposeColor(0xFF0D0E0F),
        keyLabel = ComposeColor(0xFF101112),
        keySubLabel = ComposeColor(0xFF131415),
        keySecondaryLabel = ComposeColor(0xFF161718),
        keyBorder = ComposeColor(0xFF191A1B),
        keyBorderActivated = ComposeColor(0xFF1C1D1E),
        swipeTrail = ComposeColor(0xFF1F2021),
        ripple = ComposeColor(0xFF222324),
        suggestionText = ComposeColor(0xFF252627),
        suggestionBackground = ComposeColor(0xFF28292A),
        suggestionHighConfidence = ComposeColor(0xFF2B2C2D),
        keyboardBackground = ComposeColor(0xFF2E2F30),
        keyboardSurface = ComposeColor(0xFF313233),
    )

    /** Every renderer-visible value the preview derives from a scheme, by name. */
    private fun rendered(scheme: KeyboardColorScheme): Map<String, Int> {
        val theme = ThemeEditorPreview.themeFor(context, scheme)
        return linkedMapOf(
            "colorKey" to theme.colorKey,
            "colorKeyActivated" to theme.colorKeyActivated,
            "colorKeyLocked" to theme.colorKeyLocked,
            "colorKeyModifier" to theme.colorKeyModifier,
            "colorKeySpecial" to theme.colorKeySpecial,
            "labelColor" to theme.labelColor,
            "activatedColor" to theme.activatedColor,
            "lockedColor" to theme.lockedColor,
            "subLabelColor" to theme.subLabelColor,
            "secondaryLabelColor" to theme.secondaryLabelColor,
            "keyBorderColorLeft" to theme.keyBorderColorLeft,
            "keyBorderColorTop" to theme.keyBorderColorTop,
            "keyBorderColorRight" to theme.keyBorderColorRight,
            "keyBorderColorBottom" to theme.keyBorderColorBottom,
            "keyBorderColorActivated" to theme.keyBorderColorActivated,
            "trail" to ThemeEditorPreview.trailColorFor(scheme),
            "rippleColor" to theme.rippleColor,
            "suggestionTextColor" to theme.suggestionTextColor,
            "suggestionBackgroundColor" to theme.suggestionBackgroundColor,
            "suggestionHighConfidenceColor" to theme.suggestionHighConfidenceColor,
            "colorKeyboardBackground" to theme.colorKeyboardBackground,
            "colorNavBar" to theme.colorNavBar,
            "colorKeyboardSurface" to theme.colorKeyboardSurface,
        )
    }

    /** Editable field → (how to change it, the rendered values it must own). */
    private val ownership: List<Triple<String, (KeyboardColorScheme, ComposeColor) -> KeyboardColorScheme, Set<String>>> =
        listOf(
            // keyDefault is also the composite base of the three role tints, but with opaque
            // tints the blend is identity, so it owns only the plain key colour here.
            Triple("keyDefault", { s, c -> s.copy(keyDefault = c) }, setOf("colorKey")),
            Triple("keyActivated", { s, c -> s.copy(keyActivated = c) }, setOf("colorKeyActivated")),
            Triple("keyLocked", { s, c -> s.copy(keyLocked = c) }, setOf("colorKeyLocked")),
            Triple("keyModifier", { s, c -> s.copy(keyModifier = c) }, setOf("colorKeyModifier")),
            Triple("keySpecial", { s, c -> s.copy(keySpecial = c) }, setOf("colorKeySpecial")),
            Triple("keyLabel", { s, c -> s.copy(keyLabel = c) }, setOf("labelColor", "activatedColor", "lockedColor")),
            Triple("keySubLabel", { s, c -> s.copy(keySubLabel = c) }, setOf("subLabelColor")),
            Triple("keySecondaryLabel", { s, c -> s.copy(keySecondaryLabel = c) }, setOf("secondaryLabelColor")),
            Triple(
                "keyBorder", { s, c -> s.copy(keyBorder = c) },
                setOf("keyBorderColorLeft", "keyBorderColorTop", "keyBorderColorRight", "keyBorderColorBottom")
            ),
            Triple("keyBorderActivated", { s, c -> s.copy(keyBorderActivated = c) }, setOf("keyBorderColorActivated")),
            Triple("swipeTrail", { s, c -> s.copy(swipeTrail = c) }, setOf("trail")),
            Triple("ripple", { s, c -> s.copy(ripple = c) }, setOf("rippleColor")),
            Triple("suggestionText", { s, c -> s.copy(suggestionText = c) }, setOf("suggestionTextColor")),
            Triple("suggestionBackground", { s, c -> s.copy(suggestionBackground = c) }, setOf("suggestionBackgroundColor")),
            Triple(
                "suggestionHighConfidence", { s, c -> s.copy(suggestionHighConfidence = c) },
                setOf("suggestionHighConfidenceColor")
            ),
            Triple(
                "keyboardBackground", { s, c -> s.copy(keyboardBackground = c) },
                setOf("colorKeyboardBackground", "colorNavBar")
            ),
            Triple("keyboardSurface", { s, c -> s.copy(keyboardSurface = c) }, setOf("colorKeyboardSurface")),
        )

    @Test
    fun everyEditableColourIsCovered() {
        // KeyboardColorScheme's constructor parameters are exactly the editor's fields.
        val schemeFields = KeyboardColorScheme::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }.toSet()
        assertThat(ownership.map { it.first }.toSet()).isEqualTo(schemeFields)
        assertThat(ownership).hasSize(17)
    }

    @Test
    fun sentinelSchemeReachesTheExpectedRendererFields() {
        val r = rendered(sentinelScheme())
        assertThat(r["colorKey"]).isEqualTo(0xFF010203.toInt())
        assertThat(r["colorKeyActivated"]).isEqualTo(0xFF040506.toInt())
        assertThat(r["colorKeyLocked"]).isEqualTo(0xFF070809.toInt())
        assertThat(r["colorKeyModifier"]).isEqualTo(0xFF0A0B0C.toInt())
        assertThat(r["colorKeySpecial"]).isEqualTo(0xFF0D0E0F.toInt())
        assertThat(r["labelColor"]).isEqualTo(0xFF101112.toInt())
        assertThat(r["activatedColor"]).isEqualTo(0xFF101112.toInt())
        assertThat(r["lockedColor"]).isEqualTo(0xFF101112.toInt())
        assertThat(r["subLabelColor"]).isEqualTo(0xFF131415.toInt())
        assertThat(r["secondaryLabelColor"]).isEqualTo(0xFF161718.toInt())
        assertThat(r["keyBorderColorTop"]).isEqualTo(0xFF191A1B.toInt())
        assertThat(r["keyBorderColorActivated"]).isEqualTo(0xFF1C1D1E.toInt())
        assertThat(r["trail"]).isEqualTo(0xFF1F2021.toInt())
        assertThat(r["rippleColor"]).isEqualTo(0xFF222324.toInt())
        assertThat(r["suggestionTextColor"]).isEqualTo(0xFF252627.toInt())
        assertThat(r["suggestionBackgroundColor"]).isEqualTo(0xFF28292A.toInt())
        assertThat(r["suggestionHighConfidenceColor"]).isEqualTo(0xFF2B2C2D.toInt())
        assertThat(r["colorKeyboardBackground"]).isEqualTo(0xFF2E2F30.toInt())
        assertThat(r["colorKeyboardSurface"]).isEqualTo(0xFF313233.toInt())
    }

    @Test
    fun eachEditChangesExactlyTheRendererFieldsItOwns() {
        val base = sentinelScheme()
        val before = rendered(base)
        val edit = ComposeColor(0xFFFEDCBA)
        for ((field, apply, owned) in ownership) {
            val after = rendered(apply(base, edit))
            val changed = after.keys.filter { after[it] != before[it] }.toSet()
            assertWithMessage("editing $field must change exactly $owned").that(changed).isEqualTo(owned)
            for (name in owned) {
                assertWithMessage("$field -> $name").that(after[name]).isEqualTo(0xFFFEDCBA.toInt())
            }
        }
    }

    @Test
    fun trailColourMatchesTheActiveThemePrefSync() {
        // The keyboard draws the trail from `swipe_trail_color`, which CustomThemePrefPolicy
        // syncs from scheme.swipeTrail; the preview must use the very same conversion,
        // translucent alpha included.
        val scheme = sentinelScheme().copy(swipeTrail = ComposeColor(0x809B59B6))
        assertThat(ThemeEditorPreview.trailColorFor(scheme))
            .isEqualTo(scheme.swipeTrail.toArgb())
        assertThat((ThemeEditorPreview.trailColorFor(scheme) ushr 24) and 0xFF).isEqualTo(0x80)
    }

    @Test
    fun previewFramesUseTheEditedRoleColoursAndBorders() {
        val theme = ThemeEditorPreview.themeFor(context, sentinelScheme())
        val config = ObjenesisStd().newInstance(Config::class.java)
        val layout = ObjenesisStd().newInstance(KeyboardData::class.java)
        val tc = Theme.Computed(theme, config, 100f, layout)
        val rgb = { c: Int -> c and 0xFFFFFF }
        assertThat(rgb(tc.keyForRole(Theme.Computed.KeyRole.NORMAL).bg_paint.color)).isEqualTo(0x010203)
        assertThat(rgb(tc.keyForRole(Theme.Computed.KeyRole.ACTIVATED).bg_paint.color)).isEqualTo(0x040506)
        assertThat(rgb(tc.keyForRole(Theme.Computed.KeyRole.LOCKED).bg_paint.color)).isEqualTo(0x070809)
        assertThat(rgb(tc.keyForRole(Theme.Computed.KeyRole.MODIFIER).bg_paint.color)).isEqualTo(0x0A0B0C)
        assertThat(rgb(tc.keyForRole(Theme.Computed.KeyRole.SPECIAL).bg_paint.color)).isEqualTo(0x0D0E0F)
        assertThat(rgb(tc.keyForRole(Theme.Computed.KeyRole.NORMAL).border_paint.color)).isEqualTo(0x191A1B)
        assertThat(rgb(tc.keyForRole(Theme.Computed.KeyRole.ACTIVATED).border_paint.color)).isEqualTo(0x1C1D1E)
        assertThat(rgb(tc.keyForRole(Theme.Computed.KeyRole.LOCKED).border_paint.color)).isEqualTo(0x1C1D1E)
        assertThat(rgb(tc.indication_paint.color)).isEqualTo(0x131415)
    }
}
