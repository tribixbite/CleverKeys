package tribixbite.cleverkeys.theme

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.ceil
import kotlin.math.min
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.DirectBootAwarePreferences
import tribixbite.cleverkeys.Keyboard2View
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.LayoutModifier
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SuggestionBar
import tribixbite.cleverkeys.Theme

/**
 * Colour mapping used by the DIY theme-editor preview (roadmap §4.1).
 *
 * The preview builds its [Theme] through the SAME runtime constructor
 * [ThemeProvider] uses for custom themes, so the preview cannot drift from what the
 * keyboard renders once the theme is applied. The one editable colour that is not a
 * [Theme] field — the swipe trail — is converted exactly as [CustomThemePrefPolicy]
 * syncs it into the `swipe_trail_color` preference the keyboard draws with.
 */
object ThemeEditorPreview {

    /** The renderer theme for [scheme] — identical to an applied custom theme's. */
    fun themeFor(context: Context, scheme: KeyboardColorScheme): Theme = Theme(context, scheme)

    /** The trail colour the keyboard uses for [scheme] once applied (pref-sync format). */
    fun trailColorFor(scheme: KeyboardColorScheme): Int = scheme.swipeTrail.toArgb()
}

/**
 * Read-only, scaled preview of the REAL keyboard renderer for the theme editor (§4.1).
 *
 * Hosts a [SuggestionBar] stacked above a [Keyboard2View] in preview mode, laid out at
 * the device's real keyboard width (so key geometry, label sizes, opacity and border
 * settings match the keyboard) and uniformly scaled down to fit this view. Shows the
 * representative states from [ThemePreviewScene]: Shift latched (activated frame), a
 * second modifier locked (locked frame), modifier/action keys at rest (modifier/special
 * frames), sub-labels, and a static sample swipe trail. All touches are intercepted and
 * accessibility exposes only this view's summary [getContentDescription] — nothing in the
 * preview can type or reach the IME.
 *
 * Requires no IME service: the global [Config] is initialised from the device-protected
 * preferences when the settings process started cold.
 */
@SuppressLint("ViewConstructor")
class ThemeKeyboardPreviewView(context: Context) : ViewGroup(context) {

    private val stage = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val keyboardView: Keyboard2View
    private var suggestionBar: SuggestionBar? = null
    private var scheme: KeyboardColorScheme? = null
    private var sampleWords: List<String> = emptyList()
    private var trailWord: String = ""
    private var scale = 1f

    /** The renderer theme currently shown (exposed for instrumented tests). */
    var currentTheme: Theme? = null
        private set

    init {
        ensureGlobalConfig(context)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        keyboardView = Keyboard2View(context).apply {
            enterThemePreviewMode()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        resolveLayout(context)?.let { keyboardView.setKeyboard(it) }
        applyRepresentativeStates()
        stage.addView(keyboardView)
        addView(stage)
    }

    /** The hosted renderer (exposed for instrumented tests). */
    fun keyboardViewForTest(): Keyboard2View = keyboardView

    /**
     * Show [colors]. Re-renders immediately; an unchanged scheme and sample set is a no-op.
     * [rawSampleWords] is the localized comma-separated sample list
     * ([ThemePreviewScene.sampleWords]); the first word also shapes the sample trail.
     */
    fun bind(colors: KeyboardColorScheme, rawSampleWords: String) {
        val words = ThemePreviewScene.sampleWords(rawSampleWords)
        if (colors == scheme && words == sampleWords) return
        scheme = colors
        sampleWords = words
        trailWord = words.firstOrNull().orEmpty()

        val theme = ThemeEditorPreview.themeFor(context, colors)
        currentTheme = theme
        keyboardView.applyPreviewTheme(theme, ThemeEditorPreview.trailColorFor(colors))

        // SuggestionBar takes its theme at construction (as in the IME), so a colour edit
        // swaps in a fresh bar; it is a handful of TextViews.
        suggestionBar?.let { stage.removeView(it) }
        val bar = SuggestionBar(context, theme).apply {
            setOpacity(Config.globalConfig().suggestion_bar_opacity)
            setSuggestions(words)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, SUGGESTION_BAR_HEIGHT_DP, resources.displayMetrics
                ).toInt()
            )
        }
        suggestionBar = bar
        stage.addView(bar, 0)
        updateTrail()
        requestLayout()
        invalidate()
    }

    /** Shift latched (ACTIVATED frame) and the first available other modifier LOCKED. */
    private fun applyRepresentativeStates() {
        val layout = keyboardView.getKeyboard() ?: return
        keyboardView.set_shift_state(true, false)
        for (name in ThemePreviewScene.LOCKED_MODIFIER_CANDIDATES) {
            val kv = KeyValue.getKeyByName(name)
            val key = layout.findKeyWithValue(kv) ?: continue
            keyboardView.set_fake_ptr_latched(key, kv, true, true)
            break
        }
    }

    /** Recompute the sample trail from the laid-out key centres. */
    private fun updateTrail() {
        val centers = keyboardView.getRealKeyPositions()
        if (centers.isEmpty()) return // not measured yet; onLayout retries
        val points = ThemePreviewScene.trailPoints(
            trailWord, centers.mapValues { (_, p) -> p.x to p.y }
        ).map { (x, y) -> PointF(x, y) }
        keyboardView.setPreviewTrail(points)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Lay the stage out at the real keyboard width so every config-derived dimension
        // (key width, label size, margins) is what the keyboard itself uses.
        val naturalWidth = resources.displayMetrics.widthPixels
        stage.measure(
            MeasureSpec.makeMeasureSpec(naturalWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val naturalHeight = stage.measuredHeight.coerceAtLeast(1)
        val availableWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            naturalWidth
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val maxHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            MeasureSpec.getSize(heightMeasureSpec)
        }
        scale = min(
            1f,
            min(availableWidth.toFloat() / naturalWidth, maxHeight.toFloat() / naturalHeight)
        )
        setMeasuredDimension(availableWidth, ceil(naturalHeight * scale).toInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val naturalWidth = stage.measuredWidth
        // Centre the scaled stage horizontally (landscape caps by height first).
        val left = ((width - naturalWidth * scale) / 2f).toInt()
        stage.pivotX = 0f
        stage.pivotY = 0f
        stage.scaleX = scale
        stage.scaleY = scale
        stage.layout(left, 0, left + naturalWidth, stage.measuredHeight)
        updateTrail()
    }

    /** Read-only: the preview swallows every touch so nothing reaches the renderer. */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    /** Not consumed, so a drag on the preview still scrolls the surrounding editor. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun getAccessibilityClassName(): CharSequence = View::class.java.name

    companion object {
        private const val TAG = "ThemeKeyboardPreview"

        /** Matches the IME's suggestion-bar height (KeyboardComponentGraph.initialize). */
        private const val SUGGESTION_BAR_HEIGHT_DP = 40f

        /** Settings can be the process's first component: initialise Config like the IME. */
        private fun ensureGlobalConfig(context: Context) {
            if (Config.globalConfigOrNull() == null) {
                val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
                Config.initGlobalConfig(prefs, context.resources, null, null)
            }
        }

        /**
         * The user's first enabled layout, modified exactly as the keyboard shows it
         * (bottom row, extra keys, number row); QWERTY when none resolves.
         */
        private fun resolveLayout(context: Context): KeyboardData? {
            val base = Config.globalConfig().layouts.firstOrNull { it != null }
                ?: KeyboardData.load(context.resources, R.raw.latn_qwerty_us)
                ?: return null
            return try {
                LayoutModifier.modify_layout(base)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Layout modification failed; previewing the unmodified layout", e)
                base
            }
        }
    }
}

/**
 * Compose wrapper for [ThemeKeyboardPreviewView]: re-renders on every [colors] change.
 * The height cap keeps the scaled keyboard compact in landscape and on short screens.
 */
@Composable
fun ThemeKeyboardPreview(colors: KeyboardColorScheme, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.theme_keyboard_preview_desc)
    val sampleWords = stringResource(R.string.theme_preview_sample_words)
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val maxHeight = (screenHeightDp * MAX_PREVIEW_HEIGHT_FRACTION).dp.coerceAtMost(MAX_PREVIEW_HEIGHT)
    AndroidView(
        factory = { ctx -> ThemeKeyboardPreviewView(ctx) },
        update = { view ->
            view.contentDescription = description
            view.bind(colors, sampleWords)
        },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
    )
}

/** Fraction of the screen height the preview may take (landscape is the binding case). */
private const val MAX_PREVIEW_HEIGHT_FRACTION = 0.4f

/** Absolute height cap of the preview. */
private val MAX_PREVIEW_HEIGHT = 260.dp
