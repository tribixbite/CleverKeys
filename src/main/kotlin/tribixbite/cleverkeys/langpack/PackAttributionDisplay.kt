package tribixbite.cleverkeys.langpack

/**
 * What the language-pack manager shows in a pack's "Source & license" section.
 *
 * Data-licensing audit 2026-09-26 follow-up: the pack word data is CC BY-SA, whose attribution
 * has to travel with the data, and users should be able to see it. [LanguagePackManager] parses
 * the manifest keys; this model makes every display decision so the composable only renders it
 * (and so the decisions are pure-JVM tested — `PackAttributionDisplayTest`).
 *
 * @property hasAttribution false for packs built before 2026-09-27, which carry none of the
 *   keys; the UI then shows a "not provided" note instead of empty rows.
 * @property sources the `source` value split for display — see [from].
 */
data class PackAttributionDisplay(
    val license: String?,
    val attribution: String?,
    val sources: List<Source>,
) {
    val hasAttribution: Boolean
        get() = license != null || attribution != null || sources.isNotEmpty()

    /**
     * One displayed source line.
     *
     * @property isLink true only for an `http`/`https` URL. The UI turns a link into an
     *   `ACTION_VIEW` intent on tap, and the text comes from an untrusted pack, so any other
     *   scheme (`intent:`, `file:`, `content:` …) is shown as plain text and is never launchable.
     */
    data class Source(val text: String, val isLink: Boolean)

    companion object {
        /** A whole token that is an http(s) URL with at least one character after the `//`. */
        private val HTTP_URL = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
        private val WHITESPACE = Regex("\\s+")

        /**
         * Build the display model for [manifest].
         *
         * `source` is written by `build_langpack.py` as one URL or several separated by spaces;
         * when EVERY token is an http(s) URL each becomes its own link line. Anything else (free
         * text, a non-web scheme among the tokens) stays one verbatim, unlinked line — splitting
         * prose on spaces would scatter it into one-word rows.
         */
        fun from(manifest: LanguagePackManifest): PackAttributionDisplay {
            val source = manifest.source?.trim()?.takeIf { it.isNotEmpty() }
            val sources = when {
                source == null -> emptyList()
                else -> {
                    val tokens = source.split(WHITESPACE)
                    if (tokens.all { HTTP_URL.matches(it) }) {
                        tokens.map { Source(it, isLink = true) }
                    } else {
                        listOf(Source(source, isLink = false))
                    }
                }
            }
            return PackAttributionDisplay(
                license = manifest.license,
                attribution = manifest.attribution,
                sources = sources,
            )
        }
    }
}
