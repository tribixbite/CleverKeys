package tribixbite.cleverkeys

/**
 * The languages that serve typing RIGHT NOW (GH #186 / GH #61).
 *
 * @property primary the language whose dictionary WordPredictor loads as its main dictionary
 *   (prediction, autocorrect, learning, n-gram/static LM) and whose swipe lexicon serves swipes.
 * @property secondary the bilingual secondary dictionary, or null for single-language typing.
 * @property boundLayoutLanguage non-null while the current layout carries a language binding;
 *   the bound language is then [primary] and [secondary] is always null.
 */
data class ActiveLanguages(
    val primary: String,
    val secondary: String?,
    val boundLayoutLanguage: String?,
)

/**
 * Pure rules for the per-layout language binding. Spec:
 * docs/specs/dictionary-and-language-system.md "Per-layout language binding".
 *
 * A layout entry of the `layouts` preference stores an optional ENTRY value:
 *  - `null`        — no user choice; the layout XML's `language` attribute (if valid) applies;
 *  - [UNBOUND]     — explicitly unbound, overriding an XML `language` default;
 *  - a code (`fa`) — bound to that language.
 *
 * Pure JVM (no Android types) so the rules run in `runPureTests`; Config, the layouts
 * serializer, the layout editor and Layout Manager all call into it.
 */
object LayoutLanguageBinding {

    /** Entry value meaning "follow the Multi-Language settings", even if the XML declares one. */
    const val UNBOUND = "none"

    /**
     * Accepted code shape — the same shape [tribixbite.cleverkeys.langpack.LanguagePackManager]
     * requires of an imported pack's `manifest.code` (a 2-3 letter base plus up to four
     * `-`/`_` segments), so every installable language can be bound and nothing path-like can.
     */
    private val VALID_CODE = Regex("^[a-z]{2,3}(?:[_-][a-z0-9]{1,16}){0,4}$")

    /** Trimmed, lower-cased [raw] when it is a valid language code; otherwise null. */
    fun normalizeCode(raw: String?): String? {
        val code = raw?.trim()?.lowercase(java.util.Locale.ROOT) ?: return null
        if (code == UNBOUND) return null
        return code.takeIf { VALID_CODE.matches(it) }
    }

    /**
     * Normalises a stored entry value (from the preference, a backup import or the UI):
     * null stays null, any casing of [UNBOUND] becomes [UNBOUND], a valid code is normalised,
     * and anything else is dropped to null — an invalid value never binds and never hides
     * the layout it rides on.
     */
    fun normalizeEntry(raw: String?): String? {
        if (raw == null) return null
        if (raw.trim().equals(UNBOUND, ignoreCase = true)) return UNBOUND
        return normalizeCode(raw)
    }

    /**
     * The binding a layout entry actually has: the user's [entry] choice when there is one,
     * otherwise the layout XML's [declared] `language` attribute when it is valid.
     */
    fun effective(entry: String?, declared: String?): String? {
        return when (val e = normalizeEntry(entry)) {
            UNBOUND -> null
            null -> normalizeCode(declared)
            else -> e
        }
    }

    /**
     * The entry value to store when the user picks [choice] in Layout Manager (null =
     * "Follow Multi-Language settings"). Choosing the XML default stores nothing, so the
     * layout keeps following its XML; following Multi-Language over an XML default must be
     * stored explicitly as [UNBOUND]. With no binding at all the entry stays null, which keeps
     * the stored preference byte-identical to a build without this feature.
     */
    fun entryValueFor(choice: String?, declared: String?): String? {
        val xmlDefault = normalizeCode(declared)
        val picked = normalizeCode(choice)
        return when {
            picked == null -> if (xmlDefault != null) UNBOUND else null
            picked == xmlDefault -> null
            else -> picked
        }
    }

    /**
     * True when a layout XML [declared] a `language` attribute that is not a valid code
     * (pass `KeyboardData.declared_language`). The layout editor refuses to save such XML;
     * loading stays lenient so a stored layout never disappears over its attribute.
     */
    fun xmlLanguageProblem(declared: String?): Boolean =
        declared != null && normalizeCode(declared) == null

    /**
     * The active languages for the current layout.
     *
     * @param bindings the effective binding of every layout, index-aligned with `Config.layouts`.
     * @param currentIndex the selected layout; past the end resolves to layout 0, exactly as
     *   `LayoutManager.current_layout_unmodified` does, so the language follows the layout
     *   actually shown.
     * @param userPrimary `pref_primary_language`.
     * @param multilangEnabled `pref_enable_multilang`.
     * @param userSecondary `pref_secondary_language` ("none" = no secondary).
     */
    fun resolve(
        bindings: List<String?>,
        currentIndex: Int,
        userPrimary: String,
        multilangEnabled: Boolean,
        userSecondary: String?,
    ): ActiveLanguages {
        val index = if (currentIndex >= bindings.size) 0 else currentIndex
        val bound = bindings.getOrNull(index)
        if (bound != null) {
            // #61: a bound layout is single-language — no words mixed in from a secondary.
            return ActiveLanguages(primary = bound, secondary = null, boundLayoutLanguage = bound)
        }
        // Unbound: exactly the pre-binding behaviour (the secondary loads whenever
        // Multi-Language is on and a secondary is chosen).
        val secondary = userSecondary?.takeIf {
            multilangEnabled && it.isNotEmpty() && it != LanguageDisplayNames.NONE
        }
        return ActiveLanguages(primary = userPrimary, secondary = secondary, boundLayoutLanguage = null)
    }
}
