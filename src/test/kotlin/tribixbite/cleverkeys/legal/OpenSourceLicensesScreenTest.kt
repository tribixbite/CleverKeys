package tribixbite.cleverkeys.legal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test
import tribixbite.cleverkeys.langpack.NoticeTextLayout

/**
 * The entries the Open-source licenses screen renders ([OpenSourceLicenses.buildScreen]): one card
 * per NOTICE section in file order, then one per licence text, with nothing of NOTICE dropped.
 * The Activity draws exactly these entries (one header row each, its blocks when expanded), so
 * this is the "screen lists every section" check without a device.
 *
 * Reads the repository files directly (project root as CWD) — the packaged copies are pinned
 * equal to them by [OpenSourceLicensesAssetDriftTest].
 */
class OpenSourceLicensesScreenTest {

    /** Asset path → repository file, the same mapping `generateLicenseAssets` applies. */
    private fun repoAsset(path: String): String? {
        val name = path.removePrefix("${OpenSourceLicenses.ASSET_DIR}/")
        val file = when (name) {
            "NOTICE.txt" -> File("NOTICE")
            "GPL-3.0-only.txt" -> File("LICENSE")
            else -> File("LICENSES", name)
        }
        return file.takeIf { it.isFile }?.readText()
    }

    private val notice = File("NOTICE").readText()

    /** Section titles found independently of the parser: the line between two `=====` bars. */
    private val noticeTitles: List<String> = notice.lines().let { lines ->
        (1 until lines.size - 1).filter { i ->
            lines[i - 1].startsWith("=====") && lines[i + 1].startsWith("=====") && lines[i].isNotBlank()
        }.map { lines[it].trim() }
    }

    @Test
    fun screenListsEveryNoticeSectionThenEveryLicenceText() {
        val screen = OpenSourceLicenses.buildScreen(::repoAsset)

        // 14 titled sections on 2026-10-31; guard against a title scan that matches nothing.
        assertThat(noticeTitles.size).isAtLeast(10)
        val sections = screen.entries.filter { it.kind == OpenSourceLicenses.Kind.NOTICE_SECTION }
        assertWithMessage("one card per NOTICE section, in file order")
            .that(sections.map { it.title }).containsExactlyElementsIn(noticeTitles).inOrder()
        sections.forEach { assertWithMessage("${it.title} has a body").that(it.blocks).isNotEmpty() }

        val texts = screen.entries.filter { it.kind == OpenSourceLicenses.Kind.LICENSE_TEXT }
        assertWithMessage("one card per catalogued licence")
            .that(texts.map { it.key }).containsExactlyElementsIn(OpenSourceLicenses.LICENSES.map { "license:${it.spdxId}" }).inOrder()
        texts.forEach {
            assertWithMessage("${it.title} is read").that(it.missing).isFalse()
            assertWithMessage("${it.title} has text").that(it.blocks.size).isGreaterThan(1)
        }
        // Sections first, then texts — the order the screen's two headings promise.
        assertThat(screen.entries.map { it.kind }.indexOfFirst { it == OpenSourceLicenses.Kind.LICENSE_TEXT })
            .isEqualTo(sections.size)
        assertWithMessage("entry keys are unique LazyColumn keys")
            .that(screen.entries.map { it.key }.toSet().size).isEqualTo(screen.entries.size)
        assertThat(screen.preamble).startsWith("CleverKeys\nCopyright (C) tribixbite and contributors")
    }

    @Test
    fun noWordOfNoticeIsDropped() {
        val document = OpenSourceLicenses.parseNotice(notice)
        val shown = (listOf(document.preamble) + document.sections.flatMap { listOf(it.title, it.body) })
            .joinToString(" ").split(WHITESPACE).filter { it.isNotEmpty() }
        val source = notice.split(WHITESPACE).filter { it.isNotEmpty() && !it.startsWith("=====") }
        assertWithMessage("the screen shows NOTICE's words in NOTICE's order").that(shown).isEqualTo(source)

        // And the per-block layout keeps them too (rules are the only thing it turns into dividers).
        val blockWords = document.sections.flatMap { section ->
            listOf(section.title) + NoticeTextLayout.parse(section.body).mapNotNull {
                when (it) {
                    is NoticeTextLayout.Block.Paragraph -> it.text
                    is NoticeTextLayout.Block.Preformatted -> it.text
                    NoticeTextLayout.Block.Rule -> null
                }
            }
        }.joinToString(" ").split(WHITESPACE).filter { it.isNotEmpty() && !Regex("^[=\\-]{5,}$").matches(it) }
        val sectionSource = source.drop(document.preamble.split(WHITESPACE).count { it.isNotEmpty() })
            .filter { !Regex("^[=\\-]{5,}$").matches(it) }
        assertThat(blockWords).isEqualTo(sectionSource)
    }

    @Test
    fun parseNoticeSplitsTitledSectionsOnly() {
        val fixture = """
            Product
            Copyright line

            =====
            First title
            =====
            Body one.
              indented

            -----
            =====
            Second title
            =====

            Body two.
        """.trimIndent()
        val document = OpenSourceLicenses.parseNotice(fixture)
        assertThat(document.preamble).isEqualTo("Product\nCopyright line")
        assertThat(document.sections.map { it.title }).containsExactly("First title", "Second title").inOrder()
        // A rule that is not part of a title triple stays in the body.
        assertThat(document.sections[0].body).isEqualTo("Body one.\n  indented\n\n-----")
        assertThat(document.sections[1].body).isEqualTo("Body two.")
        // No title bars at all: everything is preamble, no sections.
        assertThat(OpenSourceLicenses.parseNotice("Just text\n").sections).isEmpty()
    }

    @Test
    fun missingAssetsAreReportedNotDropped() {
        val screen = OpenSourceLicenses.buildScreen { path ->
            if (path == OpenSourceLicenses.LICENSES.first().assetPath) null else repoAsset(path)
        }
        val gpl = screen.entries.single { it.key == "license:${OpenSourceLicenses.LICENSES.first().spdxId}" }
        assertThat(gpl.missing).isTrue()
        assertThat(gpl.blocks).isEmpty()

        // Without NOTICE the licence texts are still listed.
        val noNotice = OpenSourceLicenses.buildScreen { path ->
            if (path == OpenSourceLicenses.NOTICE_ASSET) null else repoAsset(path)
        }
        assertThat(noNotice.preamble).isNull()
        assertThat(noNotice.entries.map { it.kind }.distinct()).containsExactly(OpenSourceLicenses.Kind.LICENSE_TEXT)
    }

    @Test
    fun unwrappedLicenceParagraphsReadAsProseButNoticeTablesStayMonospace() {
        val ccBlocks = NoticeTextLayout.parse(File("LICENSES/CC-BY-4.0.txt").readText())
            .filterIsInstance<NoticeTextLayout.Block.Preformatted>()
        assertWithMessage("SPDX CC text has indented one-line paragraphs").that(ccBlocks).isNotEmpty()
        assertThat(ccBlocks.count(OpenSourceLicenses::isUnwrappedProse)).isGreaterThan(ccBlocks.size / 2)

        val noticeTable = OpenSourceLicenses.parseNotice(notice).sections.first().body
        val tables = NoticeTextLayout.parse(noticeTable).filterIsInstance<NoticeTextLayout.Block.Preformatted>()
        assertThat(tables).isNotEmpty()
        tables.forEach { assertWithMessage(it.text).that(OpenSourceLicenses.isUnwrappedProse(it)).isFalse() }
    }

    @Test
    fun citationPatternsKeepLgplApartFromGpl() {
        assertThat(OpenSourceLicenses.licensesCitedBy("License: LGPL-3.0").map { it.spdxId })
            .containsExactly("LGPL-3.0-only")
        assertThat(OpenSourceLicenses.licensesCitedBy("GPL-3.0-only").map { it.spdxId })
            .containsExactly("GPL-3.0-only")
        assertThat(OpenSourceLicenses.licensesCitedBy("SUBMITTED")).isEmpty()
        assertThat(OpenSourceLicenses.licensesCitedBy("CC BY-SA 4.0").map { it.spdxId }).containsExactly("CC-BY-SA-4.0")
        assertThat(OpenSourceLicenses.licensesCitedBy("CC BY 4.0").map { it.spdxId }).containsExactly("CC-BY-4.0")
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
