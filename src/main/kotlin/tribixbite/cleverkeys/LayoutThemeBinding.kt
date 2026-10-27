package tribixbite.cleverkeys

/**
 * The theme the keyboard renders RIGHT NOW for the current layout.
 *
 * @property themeName the effective theme id (`Config.themeName`): the current layout's bound
 *   theme when it has one that exists on this device, otherwise the user's global theme.
 * @property boundTheme non-null while the current layout's binding is what is rendered
 *   (then == [themeName]).
 * @property missingTheme non-null when the current layout is bound to a theme that is not on
 *   this device (a deleted DIY theme, or one from a backup of another device); the global theme
 *   is rendered instead and [boundTheme] is null.
 */
data class ActiveTheme(
    val themeName: String,
    val boundTheme: String?,
    val missingTheme: String?,
    /** Non-null while the current layout's EMBEDDED `<theme>` is rendered (then == [themeName]). */
    val embeddedTheme: String? = null,
)

/**
 * Pure rules for the per-layout THEME binding — the sibling of [LayoutLanguageBinding], with
 * the same three-state entry model. Spec: docs/specs/layout-system.md "Per-layout theme binding".
 *
 * A layout entry of the `layouts` preference stores an optional ENTRY value:
 *  - `null`            — no user choice; the layout XML's `theme` attribute (if valid) applies;
 *  - [UNBOUND]         — explicitly follows the global theme, overriding an XML `theme` default;
 *  - a theme id        — bound to that theme (built-in `rosepine`, `decorative_…`, `custom_<uuid>`).
 *
 * The binding is an OVERRIDE: the user's global `theme` preference is never written by it.
 * Pure JVM (no Android types) so the rules run in `runPureTests`; Config, the layouts
 * serializer, Layout Manager, the layout XML export and the backup preview all call into it.
 */
object LayoutThemeBinding {

    /** Entry value meaning "follow the global theme", even if the XML declares one. */
    const val UNBOUND = "none"

    /** Name of the optional layout-XML root attribute carrying the default binding. */
    const val XML_ATTRIBUTE = "theme"

    /**
     * Accepted id shape: every id the Theme Manager can produce — built-in names, `decorative_`
     * + a predefined id, `custom_` + a UUID (case kept: custom ids are store keys). Nothing with
     * quotes, spaces, slashes or markup, so an id can be written into an XML attribute verbatim.
     */
    private val VALID_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$")

    /** The `<keyboard …>` start tag of a layout XML document (attributes in group 1). */
    private val ROOT_TAG = Regex("<keyboard(\\s[^>]*)?>")

    /** A `theme="…"` / `theme='…'` attribute inside a start tag, with its leading whitespace. */
    private val THEME_ATTRIBUTE = Regex("\\s$XML_ATTRIBUTE\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")

    /** Trimmed [raw] when it is a valid theme id (and not [UNBOUND]); otherwise null. */
    fun normalizeId(raw: String?): String? {
        val id = raw?.trim() ?: return null
        if (id.equals(UNBOUND, ignoreCase = true)) return null
        return id.takeIf { VALID_ID.matches(it) }
    }

    /**
     * Normalises a stored entry value (from the preference, a backup import or the UI): null
     * stays null, any casing of [UNBOUND] becomes [UNBOUND], a valid id is kept, and anything
     * else is dropped to null — an invalid value never binds and never hides its layout.
     */
    fun normalizeEntry(raw: String?): String? {
        if (raw == null) return null
        if (raw.trim().equals(UNBOUND, ignoreCase = true)) return UNBOUND
        return normalizeId(raw)
    }

    /**
     * The binding a layout entry actually has: the user's [entry] choice when there is one,
     * otherwise the layout XML's [declared] `theme` attribute when it is valid.
     */
    fun effective(entry: String?, declared: String?): String? {
        return when (val e = normalizeEntry(entry)) {
            UNBOUND -> null
            null -> normalizeId(declared)
            else -> e
        }
    }

    /**
     * The entry value to store when the user picks [choice] in Layout Manager (null = "Follow
     * global theme"). Choosing the XML default stores nothing, so the layout keeps following its
     * XML; following the global theme over an XML default is stored explicitly as [UNBOUND].
     * With no binding at all the entry stays null, keeping the stored preference byte-identical
     * to a build without this feature.
     */
    fun entryValueFor(choice: String?, declared: String?): String? {
        val xmlDefault = normalizeId(declared)
        val picked = normalizeId(choice)
        return when {
            picked == null -> if (xmlDefault != null) UNBOUND else null
            picked == xmlDefault -> null
            else -> picked
        }
    }

    /**
     * True when a layout XML [declared] a `theme` attribute that is not a valid id (pass
     * `KeyboardData.declared_theme`). The layout editor refuses to save such XML; loading stays
     * lenient. A well-formed id of a theme that is not on this device is NOT a problem — it may
     * be a DIY theme from another device, and resolution falls back to the global theme.
     */
    fun xmlThemeProblem(declared: String?): Boolean =
        declared != null && normalizeId(declared) == null

    /**
     * The theme to render for the current layout.
     *
     * @param bindings the effective binding of every layout, index-aligned with `Config.layouts`.
     * @param currentIndex the selected layout; past the end resolves to layout 0, exactly as
     *   `LayoutManager.current_layout_unmodified` does, so the theme follows the layout shown.
     * @param globalTheme the user's `theme` preference (never modified here).
     * @param isAvailable whether a theme id exists on this device (ThemeProvider.isAvailable).
     * @param embedded each layout's EMBEDDED theme id (`EmbeddedThemes.idFor`, its `<theme>` over
     *   the global theme), index-aligned with [bindings]; null where the layout has none or its
     *   entry explicitly follows the global theme ([UNBOUND]).
     *
     * Precedence (docs/specs/layout-system.md "Layout themes"): the user's binding > the XML
     * `theme=` id (both in [bindings]) > the layout's embedded `<theme>` > the global theme. A
     * binding to a theme that is not on this device falls through to the embedded theme, else
     * to the global one, and is reported as [ActiveTheme.missingTheme].
     */
    fun resolve(
        bindings: List<String?>,
        currentIndex: Int,
        globalTheme: String,
        isAvailable: (String) -> Boolean,
        embedded: List<String?> = emptyList(),
    ): ActiveTheme {
        val index = if (currentIndex >= bindings.size) 0 else currentIndex
        val bound = bindings.getOrNull(index)
        if (bound != null && isAvailable(bound)) {
            return ActiveTheme(themeName = bound, boundTheme = bound, missingTheme = null)
        }
        val own = embedded.getOrNull(index)
        return if (own != null) {
            ActiveTheme(themeName = own, boundTheme = null, missingTheme = bound, embeddedTheme = own)
        } else {
            ActiveTheme(themeName = globalTheme, boundTheme = null, missingTheme = bound)
        }
    }

    /**
     * The raw `theme` attribute of a layout XML document's `<keyboard>` root, or null. A cheap
     * textual read for code that cannot run the XML pull parser (the pure backup preview);
     * `KeyboardData.declared_theme` is the authoritative parse.
     */
    fun declaredThemeOfXml(xml: String): String? {
        val attributes = ROOT_TAG.find(xml)?.groupValues?.get(1) ?: return null
        val match = THEME_ATTRIBUTE.find(attributes) ?: return null
        return match.groups[2]?.value ?: match.groups[3]?.value
    }

    /**
     * The layout XML to EXPORT for an entry whose stored binding choice is [entry]:
     *  - null (no choice): the XML exactly as written — its own `theme` attribute, if any, is
     *    already the binding;
     *  - [UNBOUND]: the root's `theme` attribute removed (the layout follows the global theme);
     *  - a theme id: `theme="<id>"` on the root, replacing any existing value.
     * Only the root start tag is touched. Input without a `<keyboard>` root is returned as is.
     */
    fun applyToXml(xml: String, entry: String?): String {
        val choice = normalizeEntry(entry) ?: return xml
        val root = ROOT_TAG.find(xml) ?: return xml
        val attributes = root.groupValues[1]
        val stripped = attributes.replace(THEME_ATTRIBUTE, "")
        val rewritten = if (choice == UNBOUND) stripped else " $XML_ATTRIBUTE=\"$choice\"$stripped"
        return xml.replaceRange(root.range, "<keyboard$rewritten>")
    }
}
