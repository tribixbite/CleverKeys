package tribixbite.cleverkeys.theme

/**
 * Moving themes between layout XML and the custom-theme store (2026-10-28, maintainer: "save /
 * export theme from one [layout] … it will show up in theme editor as a custom theme"):
 *
 *  - Layout Manager → "Save theme as custom theme": a layout's embedded `<theme>` becomes a
 *    `custom_<uuid>` theme in THE provider's [CustomThemeManager] (theme-system skill rule 4),
 *    named after the layout; it then appears in Theme Manager and opens in the Theme Creator.
 *  - Theme Manager → "Export as layout XML": any custom theme or look as a `<theme>` element.
 *  - Layout Manager → Export XML: the layout's effective custom theme embedded in the file.
 *
 * Lossless both ways: the saved custom theme keeps every field the `<theme>` defines (unset ones
 * are filled from the global theme it inherited from, as the keyboard drew it), and exporting
 * it writes them all back (`CustomThemeSpecTest`).
 */
object LayoutThemeTransfer {

    /**
     * Save [spec] as a new custom theme named [name] through [provider]'s store. Unset DIY colours
     * come from [globalThemeId] in the current system mode (what the layout drew). Returns the
     * saved theme, or null when the store is full ([CustomThemeManager.MAX_CUSTOM_THEMES]).
     */
    fun saveAsCustomTheme(provider: ThemeProvider, spec: ThemeSpec, name: String, globalThemeId: String): CustomTheme? {
        val base = ThemeSpecs.render(provider.baseAppearance(globalThemeId).first).scheme
        val theme = CustomTheme.fromSpec(spec, name, base)
        return theme.takeIf { provider.customThemeManager.saveCustomTheme(it) }
    }

    /**
     * The appearance of [themeId] as a spec for export: a custom theme's full spec (both modes),
     * a look's two-mode spec, otherwise what the theme draws in the current system mode. Null for
     * a theme that is not on this device.
     */
    fun specOf(provider: ThemeProvider, themeId: String, name: String?): ThemeSpec? = when {
        themeId.startsWith("custom_") -> provider.customThemeManager.getCustomTheme(themeId)?.fullSpec()
        themeId.startsWith(KeyboardLooks.ID_PREFIX) -> KeyboardLooks.byId(themeId)?.let { ThemeSpecs.ofLook(it, name) }
        !provider.isAvailable(themeId) -> null
        else -> provider.baseAppearance(themeId).first.copy(name = name)
    }

    /** [specOf] as a `<theme>` element ([ThemeSpecXml.write]), ready to paste into a layout. */
    fun themeXml(provider: ThemeProvider, themeId: String, name: String?): String? =
        specOf(provider, themeId, name)?.let { ThemeSpecXml.write(it) }

    /**
     * The layout XML to export with the layout's effective theme [boundThemeId] embedded, when
     * that is a custom theme on this device; [xml] unchanged otherwise. The `theme=` binding stays:
     * on a device without that custom theme the binding is missing and the embedded copy renders
     * instead (LayoutThemeBinding.resolve), so the file carries its appearance anywhere.
     */
    fun layoutXmlWithCustomTheme(provider: ThemeProvider, xml: String, boundThemeId: String?): String {
        if (boundThemeId == null || !boundThemeId.startsWith("custom_")) return xml
        val spec = provider.customThemeManager.getCustomTheme(boundThemeId)?.fullSpec() ?: return xml
        return ThemeSpecXml.replaceInLayout(xml, spec)
    }
}
