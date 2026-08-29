package tribixbite.cleverkeys

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integrity guard for `docs/RELEASE_RECORD.md` — the append-only book that maps every
 * user-facing claim CleverKeys has ever published in a release note onto the code that
 * implements it today and the test that pins it.
 *
 * ## Why the book needs a guard
 *
 * A release note is a promise. Once shipped it is immutable — the users who read it cannot
 * un-read it. But the code behind it moves: a fix from v1.1.75 can be silently reverted by a
 * refactor in v1.6.0 and nothing in the repo notices, because release notes are prose and
 * prose does not compile. This test turns the book into something that does:
 *
 *  1. **Anchors must resolve.** Every `path#Symbol` in the book is checked against the
 *     working tree. Delete the class that implements a shipped fix and this test goes red,
 *     naming the release whose promise you just broke.
 *  2. **The book must be complete.** The set of released versions is derived from
 *     `fastlane/metadata/android/en-US/changelogs/` — the load-bearing changelog channel
 *     (see `.claude/skills/release-process.md`). Ship a new version's changelog without
 *     appending its section here and the suite goes red.
 *  3. **History must be immutable.** Every existing section's markdown is pinned by SHA-256
 *     in [versionBlockSha256]. Appending a release = adding one map entry. *Editing* a
 *     released section = red, deliberately: the note it records was already published and
 *     cannot be retconned. If a recorded item's anchor genuinely moves, the honest fix is a
 *     new row in the *current* release's section that supersedes it, not a rewrite of the
 *     old one.
 *
 * ## Line numbers are deliberately absent
 *
 * Anchors are `path#Symbol` (class / function / object name), never `path:line`. Line
 * numbers rot on the first unrelated edit above them, which would make this test a
 * high-frequency false alarm and train people to update the hashes without reading.
 *
 * ## Block normalization (what exactly is hashed)
 *
 * A version block is the `## vX.Y.Z (...)` header line plus every following line up to (not
 * including) the next `## ` header, or EOF. Each line is `trimEnd()`-ed, trailing blank lines
 * are dropped, the result is joined with `\n`, encoded UTF-8 and SHA-256'd, lowercase hex.
 * So trailing-whitespace churn from a markdown formatter is tolerated; every other byte is
 * not.
 *
 * ## Statuses
 *
 * | status | meaning | code anchor | test anchor |
 * |---|---|---|---|
 * | `GUARDED` | a test pins the behaviour | required | required |
 * | `PRESENT-UNTESTED` | code exists, nothing pins it | required | must be `—` |
 * | `REMOVED (…)` | superseded/deleted, cites the ADR or commit | `—` | `—` |
 * | `UNATTRIBUTABLE` | the note itself was too vague to attribute | `—` | `—` |
 *
 * `PRESENT-UNTESTED` is a legitimate answer and the reason the book is worth keeping: it is
 * the backlog of shipped promises nothing defends.
 */
class ReleaseRecordDriftTest {

    // ---------------------------------------------------------------- parsing

    private data class Row(
        val version: String,
        val item: String,
        val kind: String,
        val status: String,
        val codeAnchors: List<String>,
        val testAnchors: List<String>,
    )

    private data class Section(
        val version: String,
        val versionCode: Int,
        val date: String,
        val rows: List<Row>,
        val blockLines: List<String>,
    )

    private val record = File(RECORD_PATH)

    private val sections: List<Section> by lazy { parseSections(readRecord()) }

    private fun readRecord(): List<String> {
        assertTrue(
            "$RECORD_PATH is missing. It is the append-only release record book this test " +
                "guards; create it (one `## vX.Y.Z (versionCode N, YYYY-MM-DD)` section per " +
                "published release, ascending, newest appended at the bottom).",
            record.isFile,
        )
        return record.readText().split("\n")
    }

    private fun parseSections(lines: List<String>): List<Section> {
        val out = mutableListOf<Section>()
        var i = 0
        while (i < lines.size) {
            val header = HEADER_RE.matchEntire(lines[i].trimEnd())
            if (header == null) {
                i++
                continue
            }
            val start = i
            i++
            while (i < lines.size && !lines[i].trimEnd().startsWith("## ")) i++
            val block = lines.subList(start, i).map { it.trimEnd() }
            val version = header.groupValues[1]
            out += Section(
                version = version,
                versionCode = header.groupValues[2].toInt(),
                date = header.groupValues[3],
                rows = parseRows(version, block),
                blockLines = block.dropLastWhile { it.isBlank() },
            )
        }
        assertTrue(
            "$RECORD_PATH contains no `## vX.Y.Z (versionCode N, DATE)` sections — either the " +
                "book is empty or the header format drifted from what this test parses",
            out.isNotEmpty(),
        )
        return out
    }

    private fun parseRows(version: String, block: List<String>): List<Row> {
        val rows = mutableListOf<Row>()
        for (line in block) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("|")) continue
            val cells = trimmed.trim('|').split("|").map { it.trim() }
            if (cells.size != COLUMNS.size) {
                throw AssertionError(
                    "$RECORD_PATH $version: table row has ${cells.size} cells, expected " +
                        "${COLUMNS.size} (${COLUMNS.joinToString(" | ")}): $trimmed"
                )
            }
            // Skip the header row and the `|---|` separator.
            if (cells.map { it.lowercase() } == COLUMNS) continue
            if (cells.all { it.isEmpty() || it.all { c -> c == '-' || c == ':' } }) continue
            val status = cells[3]
            assertTrue(
                "$RECORD_PATH $version row '${cells[0]}': status '$status' must start with one " +
                    "of $VALID_STATUSES (an optional ' (…)' citation may follow)",
                VALID_STATUSES.any { status == it || status.startsWith("$it (") },
            )
            assertTrue(
                "$RECORD_PATH $version row '${cells[0]}': kind '${cells[1]}' must be one of $VALID_KINDS",
                cells[1] in VALID_KINDS,
            )
            rows += Row(
                version = version,
                item = cells[0],
                kind = cells[1],
                status = status,
                codeAnchors = splitAnchors(cells[4]),
                testAnchors = splitAnchors(cells[5]),
            )
        }
        return rows
    }

    private fun splitAnchors(cell: String): List<String> {
        if (cell == NONE) return emptyList()
        return cell.split(";").map { it.trim().trim('`') }.filter { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun recordIsWellFormedAndAppendOnlyOrdered() {
        val versions = sections.map { it.version }
        assertEquals(
            "$RECORD_PATH lists a version twice — each release gets exactly one section",
            versions.size, versions.distinct().size,
        )
        for (section in sections) {
            assertEquals(
                "$RECORD_PATH ${section.version}: header versionCode ${section.versionCode} does " +
                    "not match MAJOR*10000 + MINOR*100 + PATCH for that version",
                versionCodeOf(section.version), section.versionCode,
            )
            assertTrue(
                "$RECORD_PATH ${section.version}: date must be YYYY-MM-DD or '$UNRELEASED'",
                section.date == UNRELEASED || DATE_RE.matches(section.date),
            )
            assertTrue(
                "$RECORD_PATH ${section.version}: a section must carry at least one table row",
                section.rows.isNotEmpty(),
            )
        }
        val codes = sections.map { it.versionCode }
        assertEquals(
            "$RECORD_PATH sections must be in ascending version order — the book is append-only, " +
                "new releases go at the BOTTOM",
            codes.sorted(), codes,
        )
        // Only genuinely unreleased versions may carry the `unreleased` marker.
        for (section in sections) {
            assertEquals(
                "$RECORD_PATH ${section.version}: date '${section.date}' disagrees with " +
                    "PENDING_RELEASES membership",
                section.version in PENDING_RELEASES, section.date == UNRELEASED,
            )
        }
    }

    @Test
    fun everyAnchorResolvesInTheCurrentTree() {
        var checked = 0
        for (row in sections.flatMap { it.rows }) {
            val kind = row.status.substringBefore(" (")
            when (kind) {
                "GUARDED" -> {
                    assertTrue(
                        "$RECORD_PATH ${row.version} '${row.item}': GUARDED needs a code anchor",
                        row.codeAnchors.isNotEmpty(),
                    )
                    assertTrue(
                        "$RECORD_PATH ${row.version} '${row.item}': GUARDED needs a test anchor — " +
                            "if nothing pins it, the honest status is PRESENT-UNTESTED",
                        row.testAnchors.isNotEmpty(),
                    )
                }
                "PRESENT-UNTESTED" -> {
                    assertTrue(
                        "$RECORD_PATH ${row.version} '${row.item}': PRESENT-UNTESTED needs a code anchor",
                        row.codeAnchors.isNotEmpty(),
                    )
                    assertTrue(
                        "$RECORD_PATH ${row.version} '${row.item}': PRESENT-UNTESTED must have no " +
                            "test anchor ('$NONE') — cite a test and the status becomes GUARDED",
                        row.testAnchors.isEmpty(),
                    )
                }
                "REMOVED", "UNATTRIBUTABLE" -> {
                    assertTrue(
                        "$RECORD_PATH ${row.version} '${row.item}': $kind rows must use '$NONE' " +
                            "for both anchor columns",
                        row.codeAnchors.isEmpty() && row.testAnchors.isEmpty(),
                    )
                    // A REMOVED row must say what removed it, and any ADR it cites must exist.
                    if (kind == "REMOVED") {
                        assertTrue(
                            "$RECORD_PATH ${row.version} '${row.item}': REMOVED must cite the " +
                                "decision that removed it, e.g. 'REMOVED (ADR-011)'",
                            row.status.contains("("),
                        )
                        for (adr in ADR_RE.findAll(row.status).map { it.value }) {
                            assertTrue(
                                "$RECORD_PATH ${row.version} '${row.item}': cites $adr, which is " +
                                    "not declared in $ADR_PATH",
                                adrText.contains("## $adr:"),
                            )
                        }
                    }
                }
            }
            for (anchor in row.codeAnchors) {
                assertAnchorResolves(row, "code", anchor); checked++
            }
            for (anchor in row.testAnchors) {
                assertAnchorResolves(row, "test", anchor); checked++
                assertTrue(
                    "$RECORD_PATH ${row.version} '${row.item}': test anchor '$anchor' must point " +
                        "into src/test/ or src/androidTest/",
                    anchor.startsWith("src/test/") || anchor.startsWith("src/androidTest/"),
                )
            }
        }
        assertTrue("$RECORD_PATH resolved no anchors at all — the book has lost its value", checked > 0)
    }

    private fun assertAnchorResolves(row: Row, column: String, anchor: String) {
        val at = anchor.lastIndexOf('#')
        assertTrue(
            "$RECORD_PATH ${row.version} '${row.item}': $column anchor '$anchor' must be " +
                "'path#Symbol' (no line numbers — they rot)",
            at > 0 && at < anchor.length - 1,
        )
        val path = anchor.substring(0, at)
        val symbol = anchor.substring(at + 1)
        assertTrue(
            "$RECORD_PATH ${row.version} '${row.item}': $column anchor '$anchor' must name a " +
                "symbol, not a line number",
            symbol.toIntOrNull() == null,
        )
        val file = File(path)
        assertTrue(
            "$RECORD_PATH ${row.version} '${row.item}': $column anchor file '$path' no longer " +
                "exists. A shipped release note points at it; either restore it, or add a " +
                "superseding row in the CURRENT release's section.",
            file.isFile,
        )
        val body = fileText.getOrPut(path) { file.readText() }
        assertTrue(
            "$RECORD_PATH ${row.version} '${row.item}': $column anchor '$anchor' — '$path' no " +
                "longer contains the symbol '$symbol'. Renaming it broke the link between a " +
                "published promise and its implementation.",
            containsSymbol(body, symbol),
        )
    }

    /**
     * Word-boundary containment that only anchors the ends that CAN carry a boundary.
     *
     * Kotlin backtick test names routinely end in a non-word character (`` `101 items = 2
     * pages` `` is fine, but `` `100 items = 1 page (boundary)` `` ends in `)`), and a blind
     * `\b…\b` can never match those — it would report a symbol that is plainly present as
     * missing. Anchor a side only when the symbol's own edge character is a word character.
     */
    private fun containsSymbol(body: String, symbol: String): Boolean {
        val head = if (symbol.firstOrNull()?.isWordChar() == true) "\\b" else ""
        val tail = if (symbol.lastOrNull()?.isWordChar() == true) "\\b" else ""
        return Regex(head + Regex.escape(symbol) + tail).containsMatchIn(body)
    }

    private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '_'

    @Test
    fun everyPublishedReleaseHasASection() {
        val fromChangelogs = versionsFromChangelogs()
        val recorded = sections.map { it.version }.toSet()
        val missing = fromChangelogs - recorded
        assertTrue(
            "$RECORD_PATH is missing a section for ${missing.sorted()}. The book is append-only: " +
                "shipping a release means appending its section at the bottom, one row per " +
                "user-facing claim in its fastlane changelog.",
            missing.isEmpty(),
        )
        val extra = recorded - fromChangelogs
        assertTrue(
            "$RECORD_PATH records ${extra.sorted()}, which has no fastlane changelog. Every " +
                "section must correspond to a real published (or pending) release note.",
            extra.isEmpty(),
        )
    }

    /**
     * Distinct release versions derived from the fastlane changelog directory.
     *
     * Normal files are `{baseCode}{abiSuffix}.txt` with `baseCode = MAJOR*10000 + MINOR*100 +
     * PATCH` and `abiSuffix ∈ {1,2,3}` (armeabi-v7a / arm64-v8a / x86_64), i.e. three
     * byte-identical copies per release. Two files predate that convention and are mapped
     * explicitly by [LEGACY_CHANGELOG_FILES]; anything else irregular fails loudly rather than
     * being silently skipped.
     */
    private fun versionsFromChangelogs(): Set<String> {
        val dir = File(CHANGELOG_DIR)
        assertTrue("$CHANGELOG_DIR is missing — the release-note source of truth moved", dir.isDirectory)
        val names = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".txt") }
            ?.map { it.name }?.sorted().orEmpty()
        assertTrue("$CHANGELOG_DIR holds no changelog files", names.isNotEmpty())
        val versions = sortedSetOf<String>()
        val irregular = mutableListOf<String>()
        for (name in names) {
            val stem = name.removeSuffix(".txt")
            val abiForm = Regex("""^(\d{5})([123])$""").matchEntire(stem)
            if (abiForm != null) {
                versions += versionOf(abiForm.groupValues[1].toInt())
            } else {
                val legacy = LEGACY_CHANGELOG_FILES[name]
                if (legacy == null) irregular += name else versions += legacy
            }
        }
        assertTrue(
            "$CHANGELOG_DIR contains changelog files that are neither `{baseCode}{1,2,3}.txt` nor " +
                "a known legacy name: $irregular. Map them in LEGACY_CHANGELOG_FILES (with a " +
                "note on why they are irregular) so no release can hide from the record.",
            irregular.isEmpty(),
        )
        return versions
    }

    @Test
    fun historyIsImmutable() {
        val actual = sections.associate { it.version to sha256(it.blockLines.joinToString("\n")) }
        val hashed = actual.keys - PENDING_RELEASES
        assertEquals(
            "versionBlockSha256 must pin exactly the released sections. Appending a release = " +
                "one new entry; a released version disappearing from the map is a rewrite of " +
                "history. (Pending, not-yet-tagged releases live in PENDING_RELEASES and are " +
                "exempt until they ship.)",
            hashed.sorted(), versionBlockSha256.keys.sorted(),
        )
        assertTrue(
            "PENDING_RELEASES ${PENDING_RELEASES.intersect(versionBlockSha256.keys)} are both " +
                "pending and hash-pinned — pick one",
            PENDING_RELEASES.intersect(versionBlockSha256.keys).isEmpty(),
        )
        val drifted = versionBlockSha256.filter { (version, pin) -> actual[version] != pin }
        assertTrue(
            "$RECORD_PATH history was EDITED for ${drifted.keys.sorted()}. Those release notes " +
                "are already published and their record blocks are immutable. If an anchor moved, " +
                "add a superseding row under the current release instead. Actual hashes: " +
                drifted.keys.sorted().joinToString { "$it=${actual[it]}" },
            drifted.isEmpty(),
        )
    }

    // ---------------------------------------------------------------- helpers

    private val fileText = mutableMapOf<String, String>()

    private val adrText: String by lazy {
        val f = File(ADR_PATH)
        assertTrue("$ADR_PATH is missing — REMOVED rows cite ADRs declared there", f.isFile)
        f.readText()
    }

    private fun versionCodeOf(version: String): Int {
        val m = Regex("""^v(\d+)\.(\d+)\.(\d+)$""").matchEntire(version)
            ?: throw AssertionError("not a version string: $version")
        return m.groupValues[1].toInt() * 10000 + m.groupValues[2].toInt() * 100 +
            m.groupValues[3].toInt()
    }

    private fun versionOf(baseCode: Int): String =
        "v${baseCode / 10000}.${(baseCode % 10000) / 100}.${baseCode % 100}"

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val RECORD_PATH = "docs/RELEASE_RECORD.md"
        const val CHANGELOG_DIR = "fastlane/metadata/android/en-US/changelogs"
        const val ADR_PATH = "docs/specs/architectural-decisions.md"
        const val UNRELEASED = "unreleased"
        /** The em-dash cell that means "deliberately no anchor". */
        const val NONE = "—"

        val COLUMNS = listOf("item", "kind", "note", "status", "code anchor", "test anchor")
        val VALID_KINDS = setOf("fix", "feature", "chore")
        val VALID_STATUSES = listOf("GUARDED", "PRESENT-UNTESTED", "REMOVED", "UNATTRIBUTABLE")

        val HEADER_RE = Regex("""^## (v\d+\.\d+\.\d+) \(versionCode (\d+), ([\w-]+)\)$""")
        val DATE_RE = Regex("""^\d{4}-\d{2}-\d{2}$""")
        val ADR_RE = Regex("""ADR-\d{3}""")

        /**
         * Changelog files that predate the `{baseCode}{abi}.txt` convention.
         *
         *  - `1.txt` — the v1.0.0 launch notes, written when the versionCode was still a bare
         *    counter rather than the packed MAJOR/MINOR/PATCH code.
         *  - `10209.txt` — an unsuffixed stray committed alongside the real `102091/2/3.txt`
         *    triplet for v1.2.9; byte-identical to them.
         */
        val LEGACY_CHANGELOG_FILES = mapOf(
            "1.txt" to "v1.0.0",
            "10209.txt" to "v1.2.9",
        )

        /**
         * Versions that have fastlane changelogs written but no published tag yet, so their
         * record section is still allowed to change.
         *
         * # TODO(release): when v1.6.0 is tagged, replace its `unreleased` header date with the
         * publish date, move it out of this set, and add its block hash to [versionBlockSha256].
         * From that moment its section is history and stops being editable.
         */
        val PENDING_RELEASES = setOf("v1.6.0")

        /**
         * SHA-256 of every released version's markdown block (see the class KDoc for the exact
         * normalization). Append one entry per release. Never edit an existing one to make a
         * failing build green — a mismatch here means published history was rewritten.
         */
        val versionBlockSha256 = mapOf(
            "v1.0.0" to "fafa68b6867e2595659f525cc872cdbe76acac9369e56be01148d095b4cb5e7e",
            "v1.0.3" to "96c608db52ce476b713ce85c5ad5a589a89725a5a8dbcfd933ee9cf6f4021abf",
            "v1.0.4" to "c8f232ba2047aab9c189a0e8692ba7692f5ad13b59486d393fa53266225cd690",
            "v1.0.5" to "9b0162bd252e52d9e39d17a71233f5fde8464d9bd5482df6d3a1912cfc2faa20",
            "v1.0.6" to "563d7de03505bbfac81a5df19dbea0c3727704049dc7af28e183c111947c4d7b",
            "v1.0.7" to "fc7db087d180255588cc11ad597a36bd52f5ada307e48d0852f9219564f26bc8",
            "v1.1.70" to "e2c5bf10962e93100faf4e202e21353920efa3e6b2daaf700a62c8a4454a2f56",
            "v1.1.71" to "83c27686e53a65f1fc8b42e84f89c591817bf8da57f75d11254213c8bd17a0aa",
            "v1.1.72" to "33ba1e3f0c21609399597305c17db8829fa8943955080c85e28c541de9d4223c",
            "v1.1.73" to "cffcc1bc4f099995023b4fbd90066d094e1b51fa670036215b671226ba6f6068",
            "v1.1.74" to "a929c84178fab50949b523a96d3676fba76710d4ba9c5070d8f562ef55eb8762",
            "v1.1.75" to "c2f24fdd8d50dda37408d0876be7e1455172e33c4946b010eb233252c2bc9724",
            "v1.1.76" to "675e535dd7e983d96602d07310907cd268231ca868dffdde4b4f027675c2e9a8",
            "v1.1.79" to "6b2724d78fcb965ee99f59243ec2e19ef6ab6ab4e77378f879772efc4010c925",
            "v1.1.80" to "3d01a2a31bb21954f39b61c9b88233c88117ee545b80e52493c838b5941166cd",
            "v1.1.81" to "9973c60dd9f7b1b921ff535a3d6638e5655e77fb0abf54ed8aaa7d2ee5af7f26",
            "v1.1.95" to "fcbcea60d2460a7f83e25726cab89a90aa8b326110ae421176bcc9a65dde193e",
            "v1.1.96" to "d987fbe897c7c66825b929387822f408de2894aa1f7423ad492708047caf5c79",
            "v1.1.97" to "b1c3597fdc65e53cc5c87cf787c8e798c550810e2bd3bf21141bd933246f9b4b",
            "v1.1.98" to "da9437b077ff722db023a01b7fd73f4b54e7d7a412c350d3b96033ba0ac35652",
            "v1.1.99" to "2fa4dbc3c204773e48b6218cd5343cac082cacaab908d18265d03dc73f6c17f5",
            "v1.2.0" to "1e36981b8a252b65e6b118c8df28417b9901cb377eecf98ecaa001063a5d1cb7",
            "v1.2.1" to "18f052d47df999c1ab55861daeb5378a0bce6d24141ab069d0efda2d0bcec5a6",
            "v1.2.4" to "edd646207da948e1b1d18e9429fb871ba2c47fc9bce33eb467b9d99e753c6fb1",
            "v1.2.5" to "3c6351212f58906859b63fdcb451424a5d0c25c881ec0a5be3a71008cab77c6e",
            "v1.2.6" to "f85120b88fee58ecd1146d634aacf1d0eff66d2120ea4cd38d725323f9196b0d",
            "v1.2.8" to "6a70381b078cffac470f14e489e8ff2eaac6436fa26f8a46bf3436c76d956fa4",
            "v1.2.9" to "174f24398d37880d0c4c435d434748ae124dbfe28637491d7d202e51ad5eda7e",
            "v1.3.0" to "e549ed731f4e0eb8b0c92e0c6a10bf6491d6fb820d0cc20547bfaa0b391707e3",
            "v1.4.0" to "bd4bc68ab154351cbb94990649dc4c23694cab4fdc838414fc3f1483f3bbaa1b",
            "v1.5.0" to "fed6073520a52373612f02235069dd41a912ec5c93f1485f571c43432f31fa84",
        )
    }
}
