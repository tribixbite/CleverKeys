package tribixbite.cleverkeys.legal

import tribixbite.cleverkeys.langpack.NoticeTextLayout

/**
 * Model of the in-app "Open-source licenses" screen (Settings → Help & FAQ).
 *
 * The APK strips `META-INF/LICENSE*` / `META-INF/NOTICE*` from its dependencies (build.gradle
 * packaging excludes), so before 2026-10-31 the only in-app attribution was the one-paragraph
 * `help_third_party_data` string. MIT (ONNX Runtime, Moonshine, emojibase), the Unicode licence
 * (CLDR) and LGPL-3.0 (ClearURLs rules) all ask for their notice to travel WITH the copy. The
 * repository's `NOTICE`, its `LICENSE` (GPL-3.0) and the texts in `LICENSES/` are therefore
 * packaged as plain-text assets under [ASSET_DIR] by the Gradle task `generateLicenseAssets` —
 * generated at build time, never committed, so the repository files stay the single source of
 * truth. `OpenSourceLicensesAssetDriftTest` pins that every licence NOTICE cites has a text in
 * [LICENSES] and that the packaged copies equal their sources.
 *
 * Pure Kotlin (no Android types): parsing and the entry list are covered by `runPureTests`;
 * the Activity only reads the assets and renders [Entry] blocks.
 */
object OpenSourceLicenses {

    /** Asset directory the Gradle task writes (`assets/licenses/…` in the APK). */
    const val ASSET_DIR = "licenses"

    /** The repository's NOTICE, packaged under this asset path. */
    const val NOTICE_ASSET = "$ASSET_DIR/NOTICE.txt"

    /**
     * One licence whose full text ships in the APK.
     *
     * @property spdxId SPDX identifier; also the asset file name (`licenses/<spdxId>.txt`).
     * @property name the licence's own name, shown as the entry title (not translated: it is a
     *   proper name, and the legal text below it is in its original language).
     * @property citations patterns of the ways NOTICE (or LICENSE) names this licence. Used by the
     *   drift test to prove every cited licence has a text and every text is cited.
     */
    data class License(
        val spdxId: String,
        val name: String,
        val citations: List<Regex>,
    ) {
        val assetPath: String get() = "$ASSET_DIR/$spdxId.txt"
    }

    /**
     * Every licence NOTICE cites, in display order: the project's own licence first, then the
     * licences of shipped code, then those of data. Patterns are matched case-sensitively with
     * word boundaries so "LGPL-3.0" is not also read as "GPL-3.0" and "MIT" not inside a word.
     */
    val LICENSES: List<License> = listOf(
        License(
            "GPL-3.0-only", "GNU General Public License v3.0",
            listOf(Regex("""GNU General Public License v3\.0"""), Regex("""\bGPL-3\.0"""))
        ),
        License(
            "LGPL-3.0-only", "GNU Lesser General Public License v3.0",
            listOf(Regex("""GNU Lesser General Public License v3\.0"""), Regex("""\bLGPL-3\.0"""))
        ),
        License("MIT", "MIT License", listOf(Regex("""\bMIT\b"""))),
        License("Apache-2.0", "Apache License 2.0", listOf(Regex("""Apache License 2\.0"""), Regex("""\bApache-2\.0"""))),
        License(
            "Unicode-3.0", "Unicode License V3",
            listOf(Regex("""Unicode License V3"""), Regex("""\bUnicode-3\.0"""))
        ),
        License(
            "CC-BY-SA-4.0", "Creative Commons Attribution-ShareAlike 4.0 International",
            listOf(Regex("""\bCC BY-SA 4\.0"""), Regex("""(?i)\bCC-by-sa-4\.0"""))
        ),
        License(
            "CC-BY-SA-3.0", "Creative Commons Attribution-ShareAlike 3.0 Unported",
            listOf(Regex("""\bCC BY-SA 3\.0"""))
        ),
        License("CC-BY-4.0", "Creative Commons Attribution 4.0 International", listOf(Regex("""\bCC BY 4\.0"""))),
        License("CC-BY-2.0-FR", "Creative Commons Attribution 2.0 France", listOf(Regex("""\bCC BY 2\.0 FR"""))),
    )

    /** Licences [text] cites, in [LICENSES] order. */
    fun licensesCitedBy(text: String): List<License> =
        LICENSES.filter { license -> license.citations.any { it.containsMatchIn(text) } }

    /** A titled NOTICE section: the text between two `=====` title bars. */
    data class NoticeSection(val title: String, val body: String)

    /** NOTICE split into its preamble (project copyright) and its titled sections. */
    data class NoticeDocument(val preamble: String, val sections: List<NoticeSection>)

    /** A NOTICE title bar: a line of five or more `=`. */
    private val TITLE_BAR = Regex("^={5,}\\s*$")

    /**
     * Splits NOTICE into sections. A section starts at a title bar, a single non-blank title
     * line and a second title bar; its body runs to the next such triple or the end. Text before
     * the first section is the preamble. Bodies are trimmed of blank lines at both ends only, so
     * their words and line layout are what the file says.
     */
    fun parseNotice(text: String): NoticeDocument {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val headerAt = (0 until lines.size - 2).filter { i ->
            TITLE_BAR.matches(lines[i]) && lines[i + 1].isNotBlank() &&
                !TITLE_BAR.matches(lines[i + 1]) && TITLE_BAR.matches(lines[i + 2])
        }
        fun block(from: Int, to: Int): String =
            lines.subList(from, to).joinToString("\n").trim('\n').trimEnd()

        val preambleEnd = headerAt.firstOrNull() ?: lines.size
        val sections = headerAt.mapIndexed { n, start ->
            val end = headerAt.getOrNull(n + 1) ?: lines.size
            NoticeSection(title = lines[start + 1].trim(), body = block(start + 3, end))
        }
        return NoticeDocument(preamble = block(0, preambleEnd), sections = sections)
    }

    /** What an entry on the screen holds. */
    enum class Kind { NOTICE_SECTION, LICENSE_TEXT }

    /**
     * One expandable card on the screen.
     *
     * @property key stable LazyColumn key.
     * @property blocks the body laid out for a phone ([NoticeTextLayout]); empty when [missing].
     * @property missing the body could not be read from the APK (a packaging defect, shown as such
     *   instead of an empty card).
     */
    data class Entry(
        val key: String,
        val kind: Kind,
        val title: String,
        val blocks: List<NoticeTextLayout.Block>,
        val missing: Boolean = false,
    )

    /** Everything the screen shows, built from the packaged files. */
    data class Screen(val preamble: String?, val entries: List<Entry>)

    /**
     * Builds the screen from the packaged texts. [readAsset] returns an asset's text, or null when
     * it is absent; the Activity passes an AssetManager reader, tests pass the repository files.
     * One entry per NOTICE section, in file order, then one per [LICENSES] text.
     */
    fun buildScreen(readAsset: (String) -> String?): Screen {
        val notice = readAsset(NOTICE_ASSET)
        val document = notice?.let(::parseNotice)
        val noticeEntries = document?.sections.orEmpty().mapIndexed { index, section ->
            Entry(
                key = "notice:$index",
                kind = Kind.NOTICE_SECTION,
                title = section.title,
                blocks = NoticeTextLayout.parse(section.body),
            )
        }
        val licenseEntries = LICENSES.map { license ->
            val text = readAsset(license.assetPath)
            Entry(
                key = "license:${license.spdxId}",
                kind = Kind.LICENSE_TEXT,
                title = "${license.name} (${license.spdxId})",
                blocks = text?.let(NoticeTextLayout::parse).orEmpty(),
                missing = text == null,
            )
        }
        return Screen(preamble = document?.preamble, entries = noticeEntries + licenseEntries)
    }

    /**
     * Longest line a column layout in these files uses. SPDX's Creative Commons texts put each
     * paragraph on ONE indented line (up to ~2,000 characters); NoticeTextLayout keeps indented
     * lines verbatim as [NoticeTextLayout.Block.Preformatted], which suits NOTICE's tables but
     * would set whole licence paragraphs in monospace. A block with a line longer than this is an
     * unwrapped paragraph, so the screen sets it in the body font (soft-wrapped, words unchanged).
     */
    const val COLUMN_LAYOUT_MAX_LINE = 100

    /** True when a preformatted block is really unwrapped prose (see [COLUMN_LAYOUT_MAX_LINE]). */
    fun isUnwrappedProse(block: NoticeTextLayout.Block.Preformatted): Boolean =
        block.text.lineSequence().any { it.length > COLUMN_LAYOUT_MAX_LINE }
}
