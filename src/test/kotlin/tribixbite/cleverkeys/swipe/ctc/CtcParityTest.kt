package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Test
import tribixbite.cleverkeys.Defaults
import tribixbite.cleverkeys.swipe.CtcEngineAdapter

/**
 * Golden-trace parity tests for the pure-JVM FUTO-style CTC swipe module.
 *
 * The authoritative expectations live in `src/test/resources/ctc/ctc_golden.json`, frozen
 * from the SAME Python port this module is a Kotlin port of
 * (`scripts/futo_decoder_eval.py` featurizer + greedy CTC, `futo_decoder_ceiling.py`'s
 * `futo_viterbi_beam`). Each fixture case feeds a hand-crafted input through both ports and
 * this test asserts the Kotlin decode matches:
 *  - **featurize** cases: the exact `[2,64]` path tensor (bit-identical float32).
 *  - **beam** cases: identical greedy-CTC string, identical top-k WORDS (the core ranking
 *    parity), and top-k final SCORES within a tight float tolerance (`Math.pow`/`ln` differ
 *    from the C-libm the port uses by at most ~1 ULP, so exact score equality is not
 *    asserted — word order is).
 *
 * The fixture's generator lived in a session-ephemeral `scratchpad/` and no longer exists, so
 * this file is the authority on the JSON shape. The input matrices are
 * committed so this half of the gate is self-contained and runs with no model and no
 * device. The other half — that the SHIPPED encoder actually produces those emissions
 * through ORT — is the instrumented `swipe/CtcEmissionModelParityTest`.
 */
class CtcParityTest {

    /**
     * One fixture ↔ model ↔ preset triple: the unit this test iterates.
     *
     * A wired script is a ROW here, never a new mechanism (checklist §2.4.3). Every fixture has
     * the same shape — 5 pure-featurizer branch probes, 1 word-path featurizer case, 4
     * model-backed beam cases — so the three assertions below are alphabet-agnostic already:
     * the beam cases carry their own `alphabet`, `lexicon` and `params`, and the featurizer is
     * pure geometry with no alphabet at all.
     *
     * @property language the language whose ship preset this fixture must equal.
     * @property goldenPath the pure-JVM copy (read as a file from the project root).
     * @property goldenAssetPath the instrumented copy, which must be byte-identical.
     * @property modelAssetPath the ONNX the fixture's `source_onnx_sha256` must match, DERIVED
     *   from the adapter's own resolution so a rename breaks this test rather than slipping past.
     */
    private class FixtureRow(
        val language: String,
        val goldenPath: String,
        val goldenAssetPath: String,
        val modelAssetPath: String,
    ) {
        override fun toString(): String = language
    }

    private companion object {
        /** Score parity: word order is exact; scores tolerate libm pow/log drift. */
        const val SCORE_TOL = 1e-4

        /**
         * The rows, built from the SAME tables the app dispatches through.
         *
         * en is the Latin family's row: the shipped `ctc_swipe_encoder.onnx` serves every a–z
         * layout in all seven Latin languages, so one row covers them. Each wired script
         * contributes its own row automatically — adding a script to [CtcScriptSupport] with a
         * fixture is all it takes to be parity-checked here, and a ROUTED script that forgot its
         * fixture fails `CtcScriptSupportTest` first.
         */
        val ROWS: List<FixtureRow> = buildList {
            add(
                FixtureRow(
                    language = "en",
                    goldenPath = "src/test/resources/ctc/ctc_golden.json",
                    goldenAssetPath = "src/androidTest/assets/ctc/ctc_golden.json",
                    modelAssetPath = "src/main/assets/" + CtcEngineAdapter.MODEL_ASSET,
                )
            )
            for ((language, wiring) in CtcScriptSupport.SCRIPTS) {
                val fixture = wiring.goldenFixture ?: continue
                add(
                    FixtureRow(
                        language = language,
                        goldenPath = "src/test/resources/ctc/$fixture",
                        goldenAssetPath = "src/androidTest/assets/ctc/$fixture",
                        modelAssetPath = "src/main/assets/" +
                            CtcEngineAdapter.modelAssetFor(language),
                    )
                )
            }
        }
    }

    private fun loadGolden(row: FixtureRow): JSONObject {
        val f = File(row.goldenPath)
        assertWithMessage("${row.language}: golden fixture must exist at ${row.goldenPath} — it " +
            "is COMMITTED, so a miss means the file was deleted, not that it needs regenerating. " +
            "The en generator was a throwaway in an ephemeral scratchpad and is gone; the script " +
            "fixtures come from CleverKeys-ML `ctc/make_golden.py`. Recover from git history " +
            "rather than trying to rebuild.").that(f.exists()).isTrue()
        return JSONObject(f.readText())
    }

    // ── Featurizer parity ─────────────────────────────────────────────────────────

    @Test
    fun featurizer_matchesPythonPort_bitIdentical() {
        for (row in ROWS) featurizerRow(row)
    }

    private fun featurizerRow(row: FixtureRow) {
        val cases = loadGolden(row).getJSONArray("cases")
        var checked = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            if (c.getString("kind") != "featurize") continue
            val name = "${row.language}/" + c.getString("name")
            val pts = c.getJSONObject("points")
            val px = pts.getJSONArray("x").toDoubleArray()
            val py = pts.getJSONArray("y").toDoubleArray()
            val pt = pts.getJSONArray("t").toDoubleArray()

            val out = CtcFeaturizer.featurize(px, py, pt)
            val expected = c.getJSONArray("features")
            assertWithMessage("$name: feature length").that(out.size).isEqualTo(expected.length())
            for (k in out.indices) {
                // The stored JSON value is a float32 widened to double; .toFloat() recovers
                // the exact float32, so an EXACT match proves bit-identical featurization.
                assertWithMessage("$name: feature[$k] (bit-identical to port)")
                    .that(out[k]).isEqualTo(expected.getDouble(k).toFloat())
            }
            checked++
        }
        assertWithMessage("${row.language}: must have exercised featurizer cases")
            .that(checked).isGreaterThan(0)
    }

    // ── Beam + greedy parity ──────────────────────────────────────────────────────

    @Test
    fun beam_matchesPythonPort_greedyAndTopK() {
        for (row in ROWS) beamRow(row)
    }

    private fun beamRow(row: FixtureRow) {
        val cases = loadGolden(row).getJSONArray("cases")
        var checked = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            if (c.getString("kind") != "beam") continue
            val name = "${row.language}/" + c.getString("name")

            val alphabet = c.getString("alphabet").toCharArray()
            val frames = c.getInt("frames")
            val numClasses = c.getInt("numClasses")
            val emissions = readEmissions(c.getJSONArray("emissions"), frames, numClasses)
            val trie = buildTrie(alphabet, c.getJSONArray("lexicon"))
            val params = readParams(c.getJSONObject("params"))

            // Greedy CTC parity.
            val greedy = CtcBeamDecoder.greedy(emissions, alphabet)
            assertWithMessage("$name: greedy").that(greedy).isEqualTo(c.getString("greedy"))

            // Beam top-k parity.
            val result = CtcBeamDecoder.decode(emissions, trie, params)
            val expected = c.getJSONArray("topk")
            val expectedWords = (0 until expected.length()).map { expected.getJSONArray(it).getString(0) }
            val gotWords = result.map { it.word }
            assertWithMessage("$name: top-k words (got $gotWords, want $expectedWords)")
                .that(gotWords).isEqualTo(expectedWords)
            for (k in result.indices) {
                assertWithMessage("$name: final score[$k] (${result[k].word})")
                    .that(result[k].finalScore).isWithin(SCORE_TOL)
                    .of(expected.getJSONArray(k).getDouble(1))
            }
            checked++
        }
        assertWithMessage("${row.language}: must have exercised beam cases")
            .that(checked).isGreaterThan(0)
    }

    // ── The fixture-and-preset rule (CleverKeys-ML MODEL_COMPARISON.md §5.1) ──────

    /**
     * **Model, preset and fixture move together — always.**
     *
     * The fixture records the artifact it was generated from (`source_onnx_sha256`) and
     * the preset it was generated at (`preset`). Shipping the model at one preset and
     * the fixture at another means the parity gate asserts against a configuration
     * nothing runs — the exact failure mode `MODEL_COMPARISON.md` §5.1 exists to
     * prevent, and the one that put an E1-generated fixture next to an app-preset model
     * mid-campaign (`PHASE_M.md` §11.1, "Fixture correction").
     *
     * This is the pure-JVM half of the gate and it needs no device: it pins all three
     * corners of the triangle. The instrumented `CtcEmissionModelParityTest` covers the
     * fourth thing only a device can check — that the artifact actually *produces* the
     * fixture's emissions through ORT.
     *
     * Current ship state:
     *  - **Latin** (en/fr/de/es/it/pt/sv, CleverKeys-ML `ctc/UNSEALING_4.md`, `PHASE_M.md`
     *    §11.1): `phaseM_kd_fresh_w1_s1234_fp16w.onnx` sha `84718e6e…`, fixture
     *    `ctc_golden.json`, preset `0.9 / 4.0 / 0.25 / 0.25 / 0.9882` = [CtcScoringParams.tunedV2].
     *  - **ru** (generation 4, `PHASE_Q.md` §7.3): `ru_synth_v3_ch80_fp16w.onnx` sha
     *    `8fffa75c…`, fixture `ru_synth_v3_ch80_fp16w_golden.json` sha `8951d7a3…` (ARC-060 geometry), preset
     *    `1.05 / 2.0 / 0.2 / 0.3734 / 0.9882` = [CtcScoringParams.tunedRuCkdt].
     *
     * The preset is read through [CtcScoringParams.presetFor] rather than named per row, which
     * is what makes this a rule-4 check and not a restatement of the fixture: it asserts the
     * fixture matches the preset the DISPATCHER will actually select for that language.
     */
    @Test
    fun fixture_model_and_shipPreset_travelTogether() {
        assertWithMessage(
            "the Latin row must always be present — a table edit that dropped it would leave " +
                "the shipped English encoder unchecked"
        ).that(ROWS.map { it.language }).contains("en")
        for (row in ROWS) tripleRow(row)
    }

    private fun tripleRow(row: FixtureRow) {
        val golden = loadGolden(row)

        // 1. The fixture's preset IS the preset the dispatcher selects for this language, term
        //    by term. The fixture stores the five scoring terms alpha omits (it is unused by
        //    the CTC core): [gamma, lambda, beta, gammaPrune, betaPrune].
        val preset = golden.getJSONArray("preset")
        val ship = CtcScoringParams.presetFor(row.language)
        assertWithMessage("${row.language}: fixture preset must have the 5 scoring terms")
            .that(preset.length()).isEqualTo(5)
        assertWithMessage("${row.language}: fixture gamma vs presetFor")
            .that(preset.getDouble(0)).isEqualTo(ship.gamma)
        assertWithMessage("${row.language}: fixture lambda vs presetFor")
            .that(preset.getDouble(1)).isEqualTo(ship.lambda)
        assertWithMessage("${row.language}: fixture beta vs presetFor")
            .that(preset.getDouble(2)).isEqualTo(ship.beta)
        assertWithMessage("${row.language}: fixture gammaPrune vs presetFor")
            .that(preset.getDouble(3)).isEqualTo(ship.gammaPrune)
        assertWithMessage("${row.language}: fixture betaPrune vs presetFor")
            .that(preset.getDouble(4)).isEqualTo(ship.betaPrune)

        // 1b. beamWidth is NOT one of the five scoring terms, and until 2026-08-20 nothing
        //     pinned it — so the fixture could be generated at a different width from the one
        //     the app decodes at and this test would still pass. That gap matters because
        //     width changes which candidates survive pruning, i.e. exactly what parity is
        //     supposed to be checking. The fixtures are deliberately narrow (cheap to generate
        //     over a 7-word lexicon); what must hold is that the SHIP width is the validated
        //     one, since every campaign accuracy number was decoded at it.
        assertWithMessage(
            "${row.language}: the ship preset's beamWidth must equal Defaults.CTC_BEAM_WIDTH — " +
                "that is the width every published accuracy number was decoded at, and the " +
                "settings default must not drift away from it"
        ).that(ship.beamWidth).isEqualTo(Defaults.CTC_BEAM_WIDTH)

        // 2. Every beam case decodes at that same preset (a case generated at another
        //    preset would silently weaken the parity assertion above it).
        val cases = golden.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            if (c.getString("kind") != "beam") continue
            val p = readParams(c.getJSONObject("params"))
            assertWithMessage(
                "${row.language}/${c.getString("name")}: beam case must use the ship preset"
            ).that(listOf(p.gamma, p.lambda, p.beta, p.gammaPrune, p.betaPrune))
                .isEqualTo(listOf(ship.gamma, ship.lambda, ship.beta, ship.gammaPrune, ship.betaPrune))
        }

        // 3. The bundled ONNX asset IS the artifact the fixture was generated from. All six
        //    script graphs are 589,406 B and byte-size-identical to each other, so the sha is
        //    the ONLY thing that can tell a Russian encoder from a Greek one.
        val model = File(row.modelAssetPath)
        assertWithMessage("${row.language}: shipped encoder must exist at ${row.modelAssetPath}")
            .that(model.exists()).isTrue()
        val expectedSha = golden.getJSONArray("source_onnx_sha256").getString(0).lowercase()
        assertWithMessage(
            "${row.language}: sha256(${row.modelAssetPath}) must equal the fixture's " +
                "source_onnx_sha256 — the shipped model and the golden fixture must be the " +
                "same artifact"
        ).that(sha256(model)).isEqualTo(expectedSha)

        // 4. The instrumented copy is byte-identical, so the device gate asserts the
        //    same contract this one does.
        val asset = File(row.goldenAssetPath)
        assertWithMessage(
            "${row.language}: instrumented fixture copy must exist at ${row.goldenAssetPath}"
        ).that(asset.exists()).isTrue()
        assertWithMessage(
            "${row.goldenAssetPath} must be byte-identical to ${row.goldenPath} (one fixture, " +
                "two consumers: runPureTests reads resources, the device gate reads test assets)"
        ).that(sha256(asset)).isEqualTo(sha256(File(row.goldenPath)))
    }

    /**
     * Every ROUTED script contributes a row — the rule-4 tie between the two tables and this
     * gate. A script that reached ROUTED without a fixture would otherwise be parity-unchecked,
     * which is precisely "two of three" and a silently wrong decode.
     */
    @Test
    fun everyRoutedScriptHasAParityRow() {
        val rowLanguages = ROWS.map { it.language }.toSet()
        for ((language, wiring) in CtcScriptSupport.SCRIPTS) {
            if (wiring.status != CtcScriptSupport.Status.ROUTED) continue
            assertWithMessage(
                "$language is ROUTED but contributes no CtcParityTest row — its fixture is " +
                    "missing from the table, so nothing checks that the shipped bytes are the " +
                    "ones the fixture was generated from"
            ).that(rowLanguages).contains(language)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private fun sha256(f: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun org.json.JSONArray.toDoubleArray(): DoubleArray =
        DoubleArray(length()) { getDouble(it) }

    private fun readEmissions(rows: org.json.JSONArray, frames: Int, numClasses: Int): CtcEmissions {
        val values = FloatArray(frames * numClasses)
        for (t in 0 until frames) {
            val row = rows.getJSONArray(t)
            for (c in 0 until numClasses) {
                values[t * numClasses + c] = row.getDouble(c).toFloat()
            }
        }
        return CtcEmissions(values, frames, numClasses)
    }

    private fun buildTrie(alphabet: CharArray, lexicon: org.json.JSONArray): CtcLexiconTrie {
        val trie = CtcLexiconTrie(alphabet)
        // Insert in list order so child-edge ordering matches the port exactly.
        for (i in 0 until lexicon.length()) {
            val entry = lexicon.getJSONArray(i)
            trie.insert(entry.getString(0), entry.getDouble(1))
        }
        return trie
    }

    private fun readParams(p: JSONObject): CtcScoringParams =
        CtcScoringParams(
            gamma = p.getDouble("gamma"),
            lambda = p.getDouble("lambda"),
            beta = p.getDouble("beta"),
            alpha = p.getDouble("alpha"),
            gammaPrune = p.getDouble("gammaPrune"),
            betaPrune = p.getDouble("betaPrune"),
            beamWidth = p.getInt("beamWidth"),
            topK = p.getInt("topK"),
        )
}
