package tribixbite.cleverkeys

/**
 * gh #130: pure decision logic for how the clipboard pane follows the active theme.
 *
 * The pane is inflated lazily and painted once (`applyRuntimeThemeColors`), so two
 * decisions govern whether the user ever sees the right colors:
 *
 *  1. [effectiveColor] — which color a programmatic read should use: for runtime themes
 *     (custom_/decorative_) the Theme object's color wins over the `?attr/` value the
 *     base XML style resolves to (runtime themes have no XML style, so the attr always
 *     resolves to the CleverKeysDark base — the "purple" of the report).
 *  2. [needsRebuild] — whether a cached pane, inflated + painted under a previous theme
 *     signature, must be dropped so the next open re-inflates under the current one.
 *     Without this the pane keeps the old theme until keyboard restart.
 *
 * Pure JVM on purpose: `ClipboardManager` is Android-bound (View inflation), so the
 * decision half lives here where `ClipboardPaneThemePolicyTest` can execute it — the
 * same seam split CustomSubLabelRenderingTest documents for the key-render paths.
 */
object ClipboardPaneThemePolicy {

    /**
     * The colors [tribixbite.cleverkeys.ClipboardManager.applyRuntimeThemeColors] paints —
     * a runtime theme's rendered identity for the pane. 0 means "theme doesn't define it".
     */
    data class RuntimeColors(
        val keyboardBackground: Int,
        val key: Int,
        val label: Int,
        val subLabel: Int,
        val activated: Int,
    )

    /**
     * Everything that affects an inflated pane's colors: the theme's name, the XML style
     * resource the pane was inflated under, and — for runtime themes — the actual color
     * values (so editing the active custom theme's colors changes the signature even
     * though the name stays the same). [runtimeColors] is null for XML-style themes.
     */
    data class ThemeSignature(
        val themeName: String,
        val themeResId: Int,
        val runtimeColors: RuntimeColors?,
    )

    /**
     * Whether a cached pane painted under [applied] must be rebuilt for [next].
     * Callers only ask while a pane is cached; an unknown (null) applied signature is
     * treated conservatively as "rebuild" — a wrongly-kept pane shows wrong colors,
     * a wrongly-dropped one only costs one re-inflation on next open.
     */
    fun needsRebuild(applied: ThemeSignature?, next: ThemeSignature): Boolean =
        applied != next

    /**
     * The color a programmatic read should use. For runtime themes a defined (non-zero)
     * runtime color wins; otherwise the XML-resolved attr value; otherwise [fallback].
     */
    fun effectiveColor(isRuntimeTheme: Boolean, runtimeColor: Int, xmlResolved: Int?, fallback: Int): Int =
        if (isRuntimeTheme && runtimeColor != 0) runtimeColor else (xmlResolved ?: fallback)
}
