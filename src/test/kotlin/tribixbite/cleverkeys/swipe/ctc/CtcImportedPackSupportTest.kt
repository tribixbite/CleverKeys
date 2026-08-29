package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Test
import tribixbite.cleverkeys.swipe.SwipeEngineRouter
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader
import java.io.File
import java.util.zip.ZipFile

/**
 * Pins the DYNAMIC half of CTC language membership: which imported language packs the engine may
 * serve, and — just as important — which it must refuse.
 *
 * Three things are under test and they fail for different reasons, so they are asserted
 * separately:
 *
 *  1. **Membership plumbing** — the static table always wins, an absent resolver serves nothing,
 *     and `en`/every scripted language is refused whatever the resolver says.
 *  2. **The measurement** — [CtcImportedPackSupport.evaluate]'s thresholds, on synthetic
 *     lexicons at the boundary AND on every real langpack in the repo. The real-pack case is the
 *     evidence the thresholds are not fitted to a story: the Latin packs sit at 100 % and Turkish
 *     at 73 %, so the decision is not close.
 *  3. **The two gates composing** — layout script (the router) and language (this table) are
 *     independent, and only the second one moves when a pack is installed or removed.
 */
class CtcImportedPackSupportTest {

    /**
     * The resolver is PROCESS-GLOBAL state ([CtcImportedPackSupport.installResolver]), so every
     * test must put it back. A leak here would make an unrelated CTC test see a language served
     * by a pack that does not exist.
     */
    @After
    fun clearResolver() {
        CtcImportedPackSupport.installResolver(null)
    }

    private fun serve(vararg codes: String) {
        val served = codes.toSet()
        CtcImportedPackSupport.installResolver { it in served }
    }

    // ── 1. Membership plumbing ────────────────────────────────────────────────────────

    @Test
    fun `without a resolver no language is served from a pack`() {
        assertThat(CtcImportedPackSupport.installedResolver()).isNull()
        assertThat(CtcLanguageSupport.sourceFor("nl")).isNull()
        assertThat(CtcLanguageSupport.isSupported("nl")).isFalse()
        assertThat(CtcLanguageSupport.langpackRelativePath("nl")).isNull()
    }

    @Test
    fun `an eligible pack makes the language served, at the langpack source`() {
        serve("nl")
        assertThat(CtcLanguageSupport.sourceFor("nl"))
            .isEqualTo(CtcLanguageSupport.LexiconSource.CKDT_LANGPACK)
        assertThat(CtcLanguageSupport.isSupported("nl")).isTrue()
        // The adapter reads the pack, never an asset — there is no nl_enhanced.bin in the APK.
        assertThat(CtcLanguageSupport.assetFor("nl")).isNull()
        assertThat(CtcLanguageSupport.langpackRelativePath("nl"))
            .isEqualTo("langpacks/nl/dictionary.bin")
    }

    @Test
    fun `region subtags resolve to the base language`() {
        serve("nl")
        assertThat(CtcLanguageSupport.isSupported("nl-BE")).isTrue()
        assertThat(CtcLanguageSupport.isSupported("nl_NL")).isTrue()
    }

    @Test
    fun `a resolver that says no serves nothing`() {
        CtcImportedPackSupport.installResolver { false }
        assertThat(CtcLanguageSupport.isSupported("nl")).isFalse()
        assertThat(CtcLanguageSupport.sourceFor("nl")).isNull()
    }

    /**
     * The uninstall direction, which is the one that can silently break a user: a language served
     * only by a pack must stop being served the moment the pack goes, or the dispatcher routes to
     * CTC and the trie build finds no file — an empty slate the shared pipeline cannot tell from
     * "no candidates".
     */
    @Test
    fun `removing the pack unserves the language again`() {
        serve("nl")
        assertThat(CtcLanguageSupport.isSupported("nl")).isTrue()
        CtcImportedPackSupport.installResolver { false }
        assertThat(CtcLanguageSupport.isSupported("nl")).isFalse()
        assertThat(CtcLanguageSupport.langpackRelativePath("nl")).isNull()
    }

    @Test
    fun `the static table always wins over an imported pack`() {
        // A resolver that says yes to EVERYTHING — the worst case for lookup order.
        CtcImportedPackSupport.installResolver { true }
        assertThat(CtcLanguageSupport.sourceFor("en"))
            .isEqualTo(CtcLanguageSupport.LexiconSource.EN_JSON)
        for (bundled in listOf("fr", "de", "es", "it", "pt", "sv")) {
            assertWithMessage(
                "an imported $bundled pack must not displace the bundled ${bundled}_enhanced.bin " +
                    "— the tuned decode was validated on that vocabulary"
            )
                .that(CtcLanguageSupport.sourceFor(bundled))
                .isEqualTo(CtcLanguageSupport.LexiconSource.CKDT_BIN)
        }
        assertThat(CtcLanguageSupport.sourceFor("ru"))
            .isEqualTo(CtcLanguageSupport.LexiconSource.CKDT_LANGPACK)
        // …and the asset paths still resolve, i.e. nothing fell through to the langpack branch.
        assertThat(CtcLanguageSupport.assetFor("fr")).isEqualTo("dictionaries/fr_enhanced.bin")
        assertThat(CtcLanguageSupport.assetFor("en")).isEqualTo("dictionaries/en_enhanced.json")
    }

    @Test
    fun `en is never served from a pack — same container, wrong frequency scale`() {
        assertThat(CtcImportedPackSupport.mayServeImportedPack("en")).isFalse()
        CtcImportedPackSupport.installResolver { true }
        // Still the JSON asset, not the pack: λ 4.0 was fitted on the compressed byte scale.
        assertThat(CtcLanguageSupport.sourceFor("en"))
            .isEqualTo(CtcLanguageSupport.LexiconSource.EN_JSON)
        assertThat(CtcLanguageSupport.langpackRelativePath("en")).isNull()
    }

    /**
     * HANDOFF rule 4: a non-Latin script needs its own model, trie and golden fixture. This path
     * hands the Latin a–z encoder a lexicon, so admitting a scripted language here would decode it
     * against the wrong emission head — the silently-wrong-decode failure the rule exists for.
     * `ru` reaches CKDT_LANGPACK through the static table instead, which is why it is unaffected.
     */
    @Test
    fun `a scripted language is never served through the imported-Latin path`() {
        for (scripted in CtcScriptSupport.SCRIPTS.keys) {
            assertWithMessage("$scripted has a CtcScriptSupport row and must use the script path")
                .that(CtcImportedPackSupport.mayServeImportedPack(scripted)).isFalse()
        }
        CtcImportedPackSupport.installResolver { true }
        for (unrouted in listOf("el", "uk", "bg", "mk", "he")) {
            assertWithMessage("$unrouted is INFRASTRUCTURE-only and must stay unserved")
                .that(CtcLanguageSupport.sourceFor(unrouted)).isNull()
        }
    }

    @Test
    fun `a blank language is never a candidate`() {
        assertThat(CtcImportedPackSupport.mayServeImportedPack(null)).isFalse()
        assertThat(CtcImportedPackSupport.mayServeImportedPack("")).isFalse()
        assertThat(CtcImportedPackSupport.mayServeImportedPack("   ")).isFalse()
    }

    /**
     * THE precedent this feature rests on, made executable: λ calibrates to the LEXICON'S
     * FREQUENCY SCALE, not to the language. An imported pack is CKDT v2 on the same
     * `255 − rank` scale as the bundled six, so it must decode at the same λ they do — and it
     * must reach it through the SAME lookup, with no imported-pack branch in the decoder.
     */
    @Test
    fun `an imported pack decodes at the CKDT lambda, exactly like a bundled CKDT language`() {
        serve("nl")
        val imported = CtcScoringParams.presetFor("nl")
        val bundledCkdt = CtcScoringParams.presetFor("fr")
        val enJson = CtcScoringParams.presetFor("en")
        assertThat(imported.lambda).isEqualTo(bundledCkdt.lambda)
        assertWithMessage(
            "en's λ was fitted on the compressed 134–255 byte scale; using it for a CKDT pack " +
                "would be wrong by 2×"
        ).that(imported.lambda).isNotEqualTo(enJson.lambda)
        // Nothing else about the preset may differ — only the λ lookup is language-aware.
        assertThat(imported).isEqualTo(bundledCkdt)
    }

    @Test
    fun `an unserved language falls back to the en-scale preset, unchanged`() {
        // No resolver: 'nl' is not served, so presetFor must behave exactly as it did before
        // dynamic membership existed.
        assertThat(CtcScoringParams.presetFor("nl")).isEqualTo(CtcScoringParams.presetFor("en"))
    }

    @Test
    fun `provisional is the tier of every imported pack, permanently`() {
        serve("nl")
        assertThat(CtcLanguageSupport.isProvisional("nl")).isTrue()
        assertThat(CtcLanguageSupport.isProvisional("it")).isTrue()
        assertWithMessage("a test-validated language must never be reported as provisional")
            .that(CtcLanguageSupport.isProvisional("fr")).isFalse()
        assertThat(CtcLanguageSupport.isProvisional("en")).isFalse()
    }

    // ── 2. The measurement ────────────────────────────────────────────────────────────

    /**
     * A distinct a–z word per index. Digits are NOT a–z-projectable (`CtcAzProjection.project`
     * rejects them), so a synthetic lexicon may not number its words — `word0` measures as
     * unswipeable and would make every threshold test vacuously true.
     */
    private fun azWord(i: Int): String {
        val sb = StringBuilder("w")
        var n = i
        repeat(4) {
            sb.append('a' + (n % 26))
            n /= 26
        }
        return sb.toString()
    }

    /** [n] words, of which [unprojectable] carry a letter with no a–z form (Turkish dotless ı). */
    private fun lexicon(n: Int, unprojectable: Int): List<String> =
        List(n) { if (it < unprojectable) "kırık${azWord(it)}" else azWord(it) }

    @Test
    fun `a fully projectable pack of adequate size is eligible`() {
        val report = CtcImportedPackSupport.evaluate("nl", lexicon(5_000, 0))
        assertThat(report.verdict).isEqualTo(CtcImportedPackSupport.Verdict.ELIGIBLE)
        assertThat(report.eligible).isTrue()
        assertThat(report.projectableRatio).isEqualTo(1.0)
        assertThat(report.projectablePercent).isEqualTo(100)
    }

    @Test
    fun `the whole-lexicon threshold rejects just below and admits just at the line`() {
        // 5,000 words, threshold 0.98 → 100 unprojectable is exactly at the line.
        val atThreshold = CtcImportedPackSupport.evaluate("nl", tailGap(5_000, 100))
        assertThat(atThreshold.projectableRatio)
            .isEqualTo(CtcImportedPackSupport.MIN_PROJECTABLE_RATIO)
        assertThat(atThreshold.verdict).isEqualTo(CtcImportedPackSupport.Verdict.ELIGIBLE)
        val belowThreshold = CtcImportedPackSupport.evaluate("nl", tailGap(5_000, 101))
        assertThat(belowThreshold.verdict)
            .isEqualTo(CtcImportedPackSupport.Verdict.NOT_AZ_PROJECTABLE)
        assertThat(belowThreshold.eligible).isFalse()
    }

    /**
     * Puts every unswipeable word in the TAIL, past the frequency head, so this exercises the
     * whole-lexicon ratio and nothing else — a gap spread across the head would trip the head
     * check first and the boundary being tested would never be reached.
     */
    private fun tailGap(n: Int, unprojectable: Int): List<String> =
        List(n) { if (it >= n - unprojectable) "kırık${azWord(it)}" else azWord(it) }

    /**
     * The case the overall ratio cannot see: a gap CONCENTRATED in the words people actually
     * type. On a large pack the frequency head is a rounding error in the total, so without a
     * separate head check a pack whose thousand commonest words were all unswipeable would pass.
     */
    @Test
    fun `a gap concentrated in the frequency head is caught even when the tail is clean`() {
        val head = CtcImportedPackSupport.HEAD_WORDS
        val words = List(100_000) { if (it < head / 2) "kırık${azWord(it)}" else azWord(it) }
        val report = CtcImportedPackSupport.evaluate("nl", words)
        assertWithMessage("the whole-lexicon ratio alone would have passed this pack")
            .that(report.projectableRatio).isGreaterThan(CtcImportedPackSupport.MIN_PROJECTABLE_RATIO)
        assertThat(report.verdict)
            .isEqualTo(CtcImportedPackSupport.Verdict.HEAD_NOT_AZ_PROJECTABLE)
        assertThat(report.headProjectable).isEqualTo(head / 2)
        assertThat(report.headWords).isEqualTo(head)
    }

    @Test
    fun `too small to measure is its own verdict, not a projectability claim`() {
        val report = CtcImportedPackSupport.evaluate("nl", lexicon(20, 0))
        assertWithMessage("20 words at 100% is not evidence that a pack is typeable")
            .that(report.verdict).isEqualTo(CtcImportedPackSupport.Verdict.TOO_FEW_WORDS)
        assertThat(report.projectableRatio).isEqualTo(1.0)
        assertThat(CtcImportedPackSupport.evaluate("nl", emptyList()).verdict)
            .isEqualTo(CtcImportedPackSupport.Verdict.TOO_FEW_WORDS)
    }

    @Test
    fun `evaluating a non-candidate language says so rather than measuring it`() {
        for (code in listOf("en", "ru", "el")) {
            assertThat(CtcImportedPackSupport.evaluate(code, lexicon(5_000, 0)).verdict)
                .isEqualTo(CtcImportedPackSupport.Verdict.NOT_AN_IMPORT_CANDIDATE)
        }
    }

    /**
     * The measurement behind the thresholds, re-run on every build against the real packs in
     * `scripts/dictionaries/`. This is the test that would catch a projection change (a new
     * expansion in [CtcAzProjection], say) quietly making Dutch unserveable — or Turkish
     * serveable.
     *
     * The numbers in [CtcImportedPackSupport]'s KDoc come from exactly this data.
     */
    @Test
    fun `real langpacks split cleanly across the threshold`() {
        val eligible = listOf("nl", "id", "ms", "sw", "tl")
        for (code in eligible) {
            val report = CtcImportedPackSupport.evaluate(code, packWords(code))
            assertWithMessage("$code: ${report}")
                .that(report.verdict).isEqualTo(CtcImportedPackSupport.Verdict.ELIGIBLE)
            assertWithMessage("$code should be at or very near 100% a–z-projectable")
                .that(report.projectableRatio).isAtLeast(0.999)
        }

        // Turkish is the case that makes the check load-bearing: ı (U+0131) has no NFD
        // decomposition, so a quarter of the vocabulary — and a sixth of the thousand most
        // frequent words — cannot be spelled on an a–z board at all. Those words ARE typeable on
        // the geometric engine, so serving Turkish here would be a regression, not a gap.
        val tr = CtcImportedPackSupport.evaluate("tr", packWords("tr"))
        assertThat(tr.verdict).isEqualTo(CtcImportedPackSupport.Verdict.NOT_AZ_PROJECTABLE)
        // Measured 2026-08-29: 29,338/40,000 = 73.34 % overall, 817/1,000 = 81.7 % in the
        // frequency head. Banded rather than pinned exactly — the point is the distance from the
        // threshold (25 points overall, 17 in the head), not the third decimal.
        assertThat(tr.projectableRatio).isAtLeast(0.70)
        assertThat(tr.projectableRatio).isAtMost(0.77)
        assertThat(tr.headProjectableRatio).isAtLeast(0.78)
        assertThat(tr.headProjectableRatio).isAtMost(0.85)

        // And the projectability check IS the script gate: no manifest field says "Cyrillic", but
        // a Cyrillic word list has no a–z spelling for ANY of its words. (Both of these are
        // refused earlier by mayServeImportedPack too — this asserts the measurement would have
        // refused them on its own.)
        for (code in listOf("ru", "el")) {
            val words = packWords(code)
            val projectable = words.count { CtcAzProjection.project(it) != null }
            assertWithMessage("$code is a non-Latin script and must not project onto a–z")
                .that(projectable).isEqualTo(0)
        }
    }

    /** Canonical section of `scripts/dictionaries/langpack-<code>.zip`, most frequent first. */
    private fun packWords(code: String): List<String> {
        val zip = File("scripts/dictionaries/langpack-$code.zip")
        assertWithMessage("missing ${zip.path} — pure tests run from the project root")
            .that(zip.isFile).isTrue()
        return ZipFile(zip).use { zf ->
            val entry = zf.getEntry("dictionary.bin")
            assertWithMessage("${zip.name} has no dictionary.bin").that(entry).isNotNull()
            zf.getInputStream(entry).use { CkdtDictionaryReader.readEntries(it) }.map { it.word }
        }
    }

    // ── Verdict cache serialization ───────────────────────────────────────────────────

    @Test
    fun `a pack's identity changes when either its length or its mtime does`() {
        val base = CtcImportedPackSupport.packFingerprint(2_088_865L, 1_700_000_000_000L)
        assertThat(base)
            .isNotEqualTo(CtcImportedPackSupport.packFingerprint(2_088_866L, 1_700_000_000_000L))
        assertThat(base)
            .isNotEqualTo(CtcImportedPackSupport.packFingerprint(2_088_865L, 1_700_000_000_001L))
        assertThat(base)
            .isEqualTo(CtcImportedPackSupport.packFingerprint(2_088_865L, 1_700_000_000_000L))
    }

    /**
     * A REIMPORT must rebuild everything. The verdict cache and `CtcEngineAdapter`'s trie
     * content-hash both key on this one fingerprint, so a pack replaced on disk can never be
     * served by a trie built from the previous file, nor admitted by the previous measurement.
     */
    @Test
    fun `a reimported pack does not inherit the previous file's verdict`() {
        val before = CtcImportedPackSupport.CachedVerdict(
            CtcImportedPackSupport.packFingerprint(2_088_865L, 1_700_000_000_000L),
            CtcImportedPackSupport.evaluate("nl", lexicon(5_000, 0)),
        )
        val encoded = CtcImportedPackSupport.encodeVerdicts(mapOf("nl" to before))
        val restored = CtcImportedPackSupport.decodeVerdicts(encoded)["nl"]!!
        assertThat(restored.report.eligible).isTrue()
        // Same length, new mtime — LanguagePackManager rewrites the pack dir on every import.
        val afterReimport = CtcImportedPackSupport.packFingerprint(2_088_865L, 1_700_000_999_000L)
        assertWithMessage("the stored verdict must no longer describe the file on disk")
            .that(restored.fingerprint).isNotEqualTo(afterReimport)
    }

    @Test
    fun `verdicts round-trip through the preference encoding`() {
        val verdicts = mapOf(
            "nl" to CtcImportedPackSupport.CachedVerdict(
                "40000:1700000000000",
                CtcImportedPackSupport.evaluate("nl", lexicon(5_000, 0)),
            ),
            "tr" to CtcImportedPackSupport.CachedVerdict(
                "12345:1700000000001",
                CtcImportedPackSupport.evaluate("tr", lexicon(5_000, 2_000)),
            ),
        )
        val decoded = CtcImportedPackSupport.decodeVerdicts(
            CtcImportedPackSupport.encodeVerdicts(verdicts)
        )
        assertThat(decoded.keys).containsExactly("nl", "tr")
        assertThat(decoded["nl"]!!.fingerprint).isEqualTo("40000:1700000000000")
        assertThat(decoded["nl"]!!.report.eligible).isTrue()
        assertThat(decoded["tr"]!!.report.verdict)
            .isEqualTo(CtcImportedPackSupport.Verdict.NOT_AZ_PROJECTABLE)
        assertThat(decoded["tr"]!!.report.words).isEqualTo(5_000)
        assertThat(decoded["tr"]!!.report.projectable).isEqualTo(3_000)
    }

    @Test
    fun `malformed cache lines are dropped, not thrown on`() {
        val good = "nl|40000:1|ELIGIBLE|5000|5000|1000|1000"
        val encoded = listOf(
            good,
            "",
            "truncated|40000:1|ELIGIBLE",
            "xx|40000:1|NO_SUCH_VERDICT|5000|5000|1000|1000",
            "yy|40000:1|ELIGIBLE|not-a-number|5000|1000|1000",
            "|40000:1|ELIGIBLE|5000|5000|1000|1000",
        ).joinToString("\n")
        val decoded = CtcImportedPackSupport.decodeVerdicts(encoded)
        assertThat(decoded.keys).containsExactly("nl")
        assertThat(CtcImportedPackSupport.decodeVerdicts(null)).isEmpty()
        assertThat(CtcImportedPackSupport.decodeVerdicts("")).isEmpty()
    }

    // ── 3. The two gates composing ────────────────────────────────────────────────────

    /**
     * Routing is per SCRIPT and serving is per LANGUAGE, and a language pack moves only the
     * second one. The same board and the same mode therefore reach CTC or geometric purely on
     * whether the pack is installed and eligible — which is exactly the cell a Dutch user was
     * stuck in before 2026-08-29.
     */
    @Test
    fun `an imported latin language reaches CTC on a latin board and geometric without the pack`() {
        val board = { SwipeEngineRouter.route("QWERTY (US)", "latin", SwipeEngineRouter.Mode.CTC) }
        // Gate 1 never moves: it is layout metadata and knows nothing about languages.
        assertThat(board()).isEqualTo(SwipeEngineRouter.Engine.CTC)

        serve("nl")
        assertWithMessage("gate 2 with the pack installed")
            .that(CtcLanguageSupport.isSupported("nl")).isTrue()
        assertThat(board()).isEqualTo(SwipeEngineRouter.Engine.CTC)

        CtcImportedPackSupport.installResolver(null)
        assertWithMessage(
            "with the pack gone the language gate must hand the swipe back to geometric — " +
                "InputCoordinator.performCtcSwipeTyping reads exactly this"
        ).that(CtcLanguageSupport.isSupported("nl")).isFalse()
        assertWithMessage("…and gate 1 is unchanged by any of it")
            .that(board()).isEqualTo(SwipeEngineRouter.Engine.CTC)
    }

    /**
     * Geometric mode is a user choice and must stay one: an eligible pack widens what CTC MAY
     * serve, never what mode is selected.
     */
    @Test
    fun `an eligible pack does not override geometric mode`() {
        serve("nl")
        assertThat(SwipeEngineRouter.route("QWERTY (US)", "latin", SwipeEngineRouter.Mode.GEOMETRIC))
            .isEqualTo(SwipeEngineRouter.Engine.GEOMETRIC)
    }
}
