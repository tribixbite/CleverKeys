package tribixbite.cleverkeys.langpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.langpack.NoticeTextLayout.Block

/**
 * [NoticeTextLayout] turns an 80-column NOTICE.txt into dialog blocks (device finding
 * 2026-09-29: `=====` rules and hard-wrapped prose wrapped raggedly in the pack notice
 * dialog). The fixture mirrors `scripts/build_langpack.py` `render_notice` output.
 */
class NoticeTextLayoutTest {

    private val rule = "=".repeat(72)

    private val notice = listOf(
        "CleverKeys language pack: French (fr) -- langpack-fr.zip",
        "https://github.com/tribixbite/CleverKeys",
        "",
        "Pack licence: CC BY-SA 4.0",
        "  https://creativecommons.org/licenses/by-sa/4.0/",
        "",
        "The word list in this pack (dictionary.bin, unigrams.txt and, where present,",
        "prefix_boost.bin, which is computed from the same list) is an adaptation of",
        "the third-party data credited below.",
        "",
        rule,
        "Third-party data",
        rule,
        "",
        "- Lexique 3.83 (New et al.)",
        "- OpenSubtitles frequency list",
        "",
        rule,
    ).joinToString("\r\n")

    @Test fun rulesBecomeDividersAndNeverTextLines() {
        val blocks = NoticeTextLayout.parse(notice)
        assertEquals(2, blocks.count { it == Block.Rule })
        blocks.filterNot { it == Block.Rule }.forEach { b ->
            val text = (b as? Block.Paragraph)?.text ?: (b as Block.Preformatted).text
            assertFalse("a rule leaked into text: $text", text.contains("====="))
        }
        // The trailing rule closes nothing and is dropped.
        assertTrue(blocks.last() != Block.Rule)
    }

    @Test fun hardWrappedProseIsReflowedButDeliberateBreaksStay() {
        val blocks = NoticeTextLayout.parse(notice)
        // Header: the title line is short (< WRAPPED_LINE_MIN_LENGTH), so the URL keeps its line.
        assertEquals(
            Block.Paragraph(
                "CleverKeys language pack: French (fr) -- langpack-fr.zip\n" +
                    "https://github.com/tribixbite/CleverKeys"
            ),
            blocks[0]
        )
        // The 78-column wrapped sentence becomes one flowing paragraph, words unchanged.
        assertTrue(
            Block.Paragraph(
                "The word list in this pack (dictionary.bin, unigrams.txt and, where present, " +
                    "prefix_boost.bin, which is computed from the same list) is an adaptation of " +
                    "the third-party data credited below."
            ) in blocks
        )
        assertTrue(Block.Paragraph("Third-party data") in blocks)
    }

    @Test fun indentedAndBulletedLinesStayVerbatim() {
        val blocks = NoticeTextLayout.parse(notice)
        assertTrue(Block.Preformatted("  https://creativecommons.org/licenses/by-sa/4.0/") in blocks)
        assertTrue(
            Block.Preformatted("- Lexique 3.83 (New et al.)\n- OpenSubtitles frequency list") in blocks
        )
    }

    @Test fun noWordIsLostOrReordered() {
        val words = { s: String -> s.split(Regex("\\s+")).filter { it.isNotEmpty() && !it.startsWith("=====") } }
        val rendered = NoticeTextLayout.parse(notice).joinToString(" ") {
            when (it) {
                is Block.Paragraph -> it.text
                is Block.Preformatted -> it.text
                Block.Rule -> ""
            }
        }
        assertEquals(words(notice), words(rendered))
    }

    @Test fun edgeCasesAreTotal() {
        assertEquals(emptyList<Block>(), NoticeTextLayout.parse(""))
        assertEquals(emptyList<Block>(), NoticeTextLayout.parse("$rule\n$rule\n"))
        assertEquals(listOf(Block.Paragraph("a"), Block.Rule, Block.Paragraph("b")),
            NoticeTextLayout.parse("a\n-----\n=====\nb"))
    }
}
