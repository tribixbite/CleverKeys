package tribixbite.cleverkeys.pinyin

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Test

/**
 * Pins the `CKPY` v1 phrase-table layout byte-for-byte: the fixtures below are assembled
 * from raw little-endian fields (never through the production reader), so a change to the
 * magic, the header offsets, the record framing, or the ordering rules fails here before it
 * can ship in a pack that other tools build against.
 *
 * Layout source of truth: [CkpyPhraseTable] KDoc + `docs/specs/pinyin-ime.md`.
 */
class CkpyPhraseTableTest {

    // ------------------------------------------------------------------ raw fixture builder

    private data class FixtureEntry(val key: String, val candidates: List<Pair<String, Int>>)

    private fun u16(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
    )

    private fun u32(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    /**
     * Hand-assembled CKPY v1 bytes in the exact on-disk order: 48-byte header, then one
     * record per entry (key framing + candidate framing). Bypasses every production write
     * path on purpose.
     */
    private fun tableBytes(
        language: String = "zh",
        entries: List<FixtureEntry>,
        trailing: ByteArray = ByteArray(0),
    ): ByteArray {
        val body = ByteArrayOutputStream()
        for (entry in entries) {
            val key = entry.key.toByteArray(Charsets.UTF_8)
            body.write(u16(key.size))
            body.write(key)
            body.write(u16(entry.candidates.size))
            for ((text, rank) in entry.candidates) {
                val textBytes = text.toByteArray(Charsets.UTF_8)
                body.write(u16(textBytes.size))
                body.write(textBytes)
                body.write(byteArrayOf(rank.toByte()))
            }
        }
        val bodyBytes = body.toByteArray()

        val header = ByteArrayOutputStream()
        header.write("CKPY".toByteArray(Charsets.US_ASCII)) // 0: magic
        header.write(u32(1))                                // 4: version
        header.write(language.toByteArray(Charsets.UTF_8).copyOf(4)) // 8: language, NUL-padded
        header.write(u32(entries.size))                     // 12: keyCount
        header.write(u32(48))                               // 16: dataOffset
        header.write(ByteArray(28))                         // 20: reserved (zero)
        return header.toByteArray() + bodyBytes + trailing
    }

    /** The canonical two-key fixture: `ni` with two candidates, `nihao` with one. */
    private fun niHaoTable() = tableBytes(
        entries = listOf(
            FixtureEntry("ni", listOf("\u4F60" to 0, "\u5C3C" to 1)),      // 你, 尼
            FixtureEntry("nihao", listOf("\u4F60\u597D" to 0)),             // 你好
        )
    )

    // --------------------------------------------------------------------- header pins

    @Test
    fun headerFieldsPinTheCkpyV1Layout() {
        val bytes = niHaoTable()
        val hex = bytes.map { it.toInt() and 0xFF }

        assertWithMessage("CKPY magic must be the ASCII bytes C K P Y")
            .that(hex.subList(0, 4))
            .isEqualTo(listOf(0x43, 0x4B, 0x50, 0x59))
        assertWithMessage("version 1, little-endian")
            .that(hex.subList(4, 8))
            .isEqualTo(listOf(0x01, 0x00, 0x00, 0x00))
        assertWithMessage("4-byte UTF-8 language tag, NUL-padded")
            .that(hex.subList(8, 12))
            .isEqualTo(listOf('z'.code, 'h'.code, 0x00, 0x00))
        assertWithMessage("keyCount at offset 12, little-endian")
            .that(hex.subList(12, 16))
            .isEqualTo(listOf(0x02, 0x00, 0x00, 0x00))
        assertWithMessage("dataOffset at offset 16 points just past the 48-byte header")
            .that(hex.subList(16, 20))
            .isEqualTo(listOf(0x30, 0x00, 0x00, 0x00))
        assertWithMessage("reserved bytes 20..47 must be zero")
            .that(hex.subList(20, 48))
            .isEqualTo(List(28) { 0x00 })
    }

    @Test
    fun recordFramingPinsLengthPrefixedUtf8AndRankBytes() {
        val bytes = niHaoTable()
        val body = bytes.copyOfRange(48, bytes.size)

        // ni: u16 2 | "ni" | u16 2 | u16 3 | 你(BE4BD A0) | 0 | u16 3 | 尼(E5B0BC) | 1
        // nihao: u16 5 | "nihao" | u16 1 | u16 6 | 你好(E4BDA0E5A5BD) | 0
        val expected = listOf(
            0x02, 0x00, 0x6E, 0x69, 0x02, 0x00,
            0x03, 0x00, 0xE4, 0xBD, 0xA0, 0x00,
            0x03, 0x00, 0xE5, 0xB0, 0xBC, 0x01,
            0x05, 0x00, 0x6E, 0x69, 0x68, 0x61, 0x6F, 0x01, 0x00,
            0x06, 0x00, 0xE4, 0xBD, 0xA0, 0xE5, 0xA5, 0xBD, 0x00,
        ).map { it.toByte() }

        assertThat(body.toList()).isEqualTo(expected)
    }

    // --------------------------------------------------------------------------- parsing

    @Test
    fun aParsedTableExposesItsLanguageAndOrderedEntries() {
        val table = CkpyPhraseTable.read(niHaoTable())

        assertThat(table.language).isEqualTo("zh")
        assertThat(table.entries.map { it.key }).containsExactly("ni", "nihao").inOrder()
        assertThat(table.entries[0].candidates.map { it.text })
            .containsExactly("\u4F60", "\u5C3C").inOrder()
        assertThat(table.entries[0].candidates.map { it.rank }).containsExactly(0, 1).inOrder()
        assertThat(table.entries[1].candidates.single())
            .isEqualTo(CkpyPhraseTable.Candidate("\u4F60\u597D", 0))
    }

    @Test
    fun readFromAFileMatchesReadFromBytes() {
        val bytes = niHaoTable()
        val file = Files.createTempFile("ckpy-test", ".bin").toFile()
        try {
            file.writeBytes(bytes)
            assertThat(CkpyPhraseTable.read(file).entries).isEqualTo(CkpyPhraseTable.read(bytes).entries)
        } finally {
            file.delete()
        }
    }

    @Test
    fun lookupIsExact() {
        val table = CkpyPhraseTable.read(niHaoTable())

        assertThat(table.lookup("ni")?.candidates?.first()?.text).isEqualTo("\u4F60")
        assertThat(table.lookup("nihao")?.candidates?.single()?.text).isEqualTo("\u4F60\u597D")
        assertWithMessage("a pinyin spelling that is not a key returns null, never a fallback")
            .that(table.lookup("nihaoma")).isNull()
    }

    @Test
    fun withPrefixReturnsTheContiguousRunInKeyOrder() {
        val table = CkpyPhraseTable.read(
            tableBytes(
                entries = listOf(
                    FixtureEntry("hao", listOf("\u597D" to 0)),
                    FixtureEntry("ni", listOf("\u4F60" to 0)),
                    FixtureEntry("nian", listOf("\u5E74" to 0)),
                    FixtureEntry("niang", listOf("\u5A18" to 0)),
                    FixtureEntry("nihao", listOf("\u4F60\u597D" to 0)),
                )
            )
        )

        assertThat(table.withPrefix("ni").map { it.key })
            .containsExactly("ni", "nian", "niang", "nihao").inOrder()
        assertThat(table.withPrefix("nia").map { it.key })
            .containsExactly("nian", "niang").inOrder()
        assertThat(table.withPrefix("n").map { it.key })
            .containsExactly("ni", "nian", "niang", "nihao").inOrder()
        assertThat(table.withPrefix("h").map { it.key }).containsExactly("hao")
        assertThat(table.withPrefix("zzz")).isEmpty()
        assertWithMessage("an empty prefix must not scan the whole table")
            .that(table.withPrefix("")).isEmpty()
    }

    @Test
    fun lookupsRequireANormalizedQuery() {
        val table = CkpyPhraseTable.read(niHaoTable())
        for (bad in listOf("Ni", "ni3", "ni hao", "\u4F60")) {
            assertWithMessage("lookup(\"$bad\") must be rejected, not fuzzy-matched")
                .that(runCatching { table.lookup(bad) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(runCatching { table.withPrefix("NI") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ------------------------------------------------------------------------ rejections

    private fun rejection(bytes: ByteArray): Throwable? =
        runCatching { CkpyPhraseTable.read(bytes) }.exceptionOrNull()

    @Test
    fun badMagicIsRejected() {
        val bytes = niHaoTable().also { it[0] = 'X'.code.toByte() }
        assertThat(rejection(bytes)).hasMessageThat().contains("not a CKPY phrase table")
    }

    @Test
    fun wrongVersionIsRejected() {
        val bytes = niHaoTable().also { it[4] = 2 }
        assertThat(rejection(bytes)).hasMessageThat().contains("unsupported CKPY version 2")
    }

    @Test
    fun truncatedTablesAreRejectedWithTheFailingField() {
        assertWithMessage("a 40-byte file is shorter than the fixed header")
            .that(rejection(niHaoTable().copyOfRange(0, 40)))
            .hasMessageThat().contains("too short")

        assertWithMessage("a header whose body is cut off mid-record must say which field")
            .that(rejection(niHaoTable().copyOfRange(0, 52)))
            .hasMessageThat().contains("truncated")
    }

    @Test
    fun unsortedOrDuplicateKeysAreRejected() {
        val duplicate = tableBytes(
            entries = listOf(
                FixtureEntry("ni", listOf("\u4F60" to 0)),
                FixtureEntry("ni", listOf("\u5C3C" to 0)),
            )
        )
        assertThat(rejection(duplicate))
            .hasMessageThat().contains("not strictly ascending")

        val descending = tableBytes(
            entries = listOf(
                FixtureEntry("nihao", listOf("\u4F60\u597D" to 0)),
                FixtureEntry("ni", listOf("\u4F60" to 0)),
            )
        )
        assertThat(rejection(descending))
            .hasMessageThat().contains("not strictly ascending")
    }

    @Test
    fun nonNormalizedKeysAndOutOfOrderRanksAreRejected() {
        val uppercase = tableBytes(entries = listOf(FixtureEntry("Ni", listOf("\u4F60" to 0))))
        assertThat(rejection(uppercase)).hasMessageThat().contains("not normalized")

        val badRanks = tableBytes(
            entries = listOf(FixtureEntry("ni", listOf("\u4F60" to 1, "\u5C3C" to 0)))
        )
        assertThat(rejection(badRanks)).hasMessageThat().contains("not rank-ordered")
    }

    @Test
    fun trailingBytesAreRejected() {
        val bytes = tableBytes(
            entries = listOf(FixtureEntry("ni", listOf("\u4F60" to 0))),
            trailing = byteArrayOf(0x00, 0x01, 0x02),
        )
        assertThat(rejection(bytes)).hasMessageThat().contains("trailing bytes")
    }

    @Test
    fun anEmptyTableIsRejected() {
        assertWithMessage("a pack with zero phrase keys is corrupt, not a valid table")
            .that(rejection(tableBytes(entries = emptyList())))
            .hasMessageThat().contains("keyCount 0")
    }
}
