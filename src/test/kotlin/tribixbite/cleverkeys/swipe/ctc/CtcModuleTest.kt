package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Unit tests for the CTC swipe module's building blocks and wiring — complements the
 * golden-trace parity suite ([CtcParityTest]) with structural/behavioral coverage:
 * emission slicing, trie loaders + `ITrie` accessors, `scoring.json` presets, featurizer
 * resampler branches + layout tensors, and the [CtcEmissionModel] facade seam that makes
 * the retrain-fork boundary concrete.
 */
class CtcModuleTest {

    private val az = ('a'..'z').toList().toCharArray()

    // ── Emission slicing (engine.cpp predict_segment) ─────────────────────────────

    @Test
    fun emissions_sliceFromHead_movesBlankFromMaxKeysToNumLetters() {
        val maxKeys = 5
        val numLetters = 3
        val frames = 2
        val headWidth = maxKeys + 1
        // Distinct per-cell values: head[t][c] = 100*t + c.
        val head = FloatArray(frames * headWidth) { it.toFloat() }
        // Overwrite to make columns unambiguous per (t,c).
        for (t in 0 until frames) for (c in 0 until headWidth) head[t * headWidth + c] = (100 * t + c).toFloat()

        val sliced = CtcEmissions.sliceFromHead(head, frames, maxKeys, numLetters)
        assertThat(sliced.frames).isEqualTo(frames)
        assertThat(sliced.numClasses).isEqualTo(numLetters + 1)
        assertThat(sliced.blankIndex).isEqualTo(numLetters)
        for (t in 0 until frames) {
            for (c in 0 until numLetters) {
                assertWithMessage("letter col ($t,$c)").that(sliced.at(t, c)).isEqualTo((100 * t + c).toFloat())
            }
            // Blank column came from the head's last slot (index maxKeys), NOT numLetters.
            assertWithMessage("blank col ($t)").that(sliced.at(t, numLetters)).isEqualTo((100 * t + maxKeys).toFloat())
        }
    }

    @Test
    fun emissions_constructionValidation() {
        // values length must equal frames*numClasses.
        try {
            CtcEmissions(FloatArray(5), frames = 2, numClasses = 3)
            assertWithMessage("expected IllegalArgumentException").fail()
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ── Trie loaders + accessors ──────────────────────────────────────────────────

    @Test
    fun trie_insertContainsAndDepthAndCharIdx() {
        val trie = CtcLexiconTrie(az)
        trie.insert("cat", 200.0)
        trie.insert("car", 180.0)
        assertThat(trie.contains("cat")).isTrue()
        assertThat(trie.contains("car")).isTrue()
        assertThat(trie.contains("ca")).isFalse()   // prefix, not a word
        assertThat(trie.contains("dog")).isFalse()
        assertThat(trie.wordCount).isEqualTo(2)

        // Walk c -> a -> t and check the ITrie-style accessors.
        val c = trie.root.children.first { it.incomingChar == 'c' }
        assertThat(c.charIdx).isEqualTo(2)   // 'c' is column 2
        assertThat(c.depth).isEqualTo(1)
        val a = c.children.first { it.incomingChar == 'a' }
        assertThat(a.childCount).isEqualTo(2) // 't' (cat) then 'r' (car), in insertion order
        assertThat(a.children.map { it.incomingChar }).containsExactly('t', 'r').inOrder()
        val t = a.children.first { it.incomingChar == 't' }
        assertThat(t.isWord).isTrue()
        assertThat(t.depth).isEqualTo(3)
        assertThat(t.word()).isEqualTo("cat")
    }

    @Test
    fun trie_loadFromFrequencyMap_skipsNonAlphabetWords() {
        // "don't" contains an apostrophe → SKIPPED entirely (load_flat_json_vocab policy).
        val words = linkedMapOf("cat" to 200.0, "don't" to 150.0, "dog" to 90.0)
        val trie = CtcLexiconTrie.loadFromFrequencyMap(az, words)
        assertThat(trie.contains("cat")).isTrue()
        assertThat(trie.contains("dog")).isTrue()
        assertThat(trie.contains("dont")).isFalse()  // NOT stripped-in
        assertThat(trie.wordCount).isEqualTo(2)
    }

    @Test
    fun trie_loadStrippingNonAlphabet_stripsApostrophes() {
        // "don't" -> "dont" (load_combined_vocab policy — a-z-reachable for a CTC model).
        val words = linkedMapOf("cat" to 200.0, "don't" to 150.0)
        val trie = CtcLexiconTrie.loadStrippingNonAlphabet(az, words)
        assertThat(trie.contains("cat")).isTrue()
        assertThat(trie.contains("dont")).isTrue()
        assertThat(trie.wordCount).isEqualTo(2)
    }

    // ── scoring.json presets ──────────────────────────────────────────────────────

    @Test
    fun scoringParams_presets_matchScoringJson() {
        val enc = CtcScoringParams.encoderOnly()
        assertThat(enc.gamma).isEqualTo(0.4056)
        assertThat(enc.lambda).isEqualTo(0.0176)
        assertThat(enc.beta).isEqualTo(0.9866)
        assertThat(enc.alpha).isEqualTo(0.0)
        assertThat(enc.gammaPrune).isEqualTo(0.4234)
        assertThat(enc.betaPrune).isEqualTo(1.0382)

        val dec = CtcScoringParams.encoderDecoder()
        assertThat(dec.gamma).isEqualTo(0.5949)
        assertThat(dec.lambda).isEqualTo(0.0134)
        assertThat(dec.beta).isEqualTo(0.7271)
        assertThat(dec.gammaPrune).isEqualTo(0.1902)
        assertThat(dec.betaPrune).isEqualTo(1.2727)

        // FUTO commit-phase defaults: beam 300, top-4.
        assertThat(enc.beamWidth).isEqualTo(300)
        assertThat(enc.topK).isEqualTo(4)
    }

    // ── Featurizer resampler branches + layout tensors ────────────────────────────

    @Test
    fun featurizer_output_hasFixedShapeAndClampedRange() {
        val px = doubleArrayOf(0.1, 0.5, 0.9)
        val py = doubleArrayOf(0.2, 0.6, 0.8)
        val pt = doubleArrayOf(0.0, 100.0, 250.0)
        val out = CtcFeaturizer.featurize(px, py, pt)
        assertThat(out.size).isEqualTo(2 * CtcFeaturizer.RESAMPLE_LENGTH)
        for (v in out) assertThat(v in 0f..1f).isTrue()
        // First x and y equal the path start; last equal the path end (endpoints preserved).
        assertThat(out[0]).isWithin(1e-6f).of(0.1f)
        assertThat(out[CtcFeaturizer.RESAMPLE_LENGTH]).isWithin(1e-6f).of(0.2f)
        assertThat(out[CtcFeaturizer.RESAMPLE_LENGTH - 1]).isWithin(1e-6f).of(0.9f)
        assertThat(out[2 * CtcFeaturizer.RESAMPLE_LENGTH - 1]).isWithin(1e-6f).of(0.8f)
    }

    @Test
    fun featurizer_singlePoint_and_zeroDuration_degenerateBranches() {
        // n == 1 → constant path.
        val single = CtcFeaturizer.featurize(doubleArrayOf(0.42), doubleArrayOf(0.58), doubleArrayOf(3.0))
        assertThat(single.size).isEqualTo(2 * CtcFeaturizer.RESAMPLE_LENGTH)
        for (i in 0 until CtcFeaturizer.RESAMPLE_LENGTH) assertThat(single[i]).isWithin(1e-6f).of(0.42f)
        for (i in CtcFeaturizer.RESAMPLE_LENGTH until 2 * CtcFeaturizer.RESAMPLE_LENGTH) {
            assertThat(single[i]).isWithin(1e-6f).of(0.58f)
        }

        // Zero duration (all timestamps equal) → endpoints branch, no NaN.
        val zero = CtcFeaturizer.featurize(
            doubleArrayOf(0.2, 0.4, 0.6), doubleArrayOf(0.3, 0.3, 0.3), doubleArrayOf(5.0, 5.0, 5.0))
        for (v in zero) assertThat(v.isFinite()).isTrue()
    }

    @Test
    fun featurizer_normalizeRawY_appliesFourThirdsAspectAndClamps() {
        // y at the exact bottom of the key area maps to 1.0 after the 4/3 correction is clamped.
        val yMid = CtcFeaturizer.normalizeRawY(rawY = 300.0, keyboardHeight = 600.0) // 0.5 * 4/3 = 0.6667
        assertThat(yMid).isWithin(1e-9).of(0.5 * (4.0 / 3.0))
        val yBottom = CtcFeaturizer.normalizeRawY(rawY = 600.0, keyboardHeight = 600.0) // 1.0*4/3 -> clamp 1
        assertThat(yBottom).isEqualTo(1.0)
        // x is a plain affine, no aspect.
        assertThat(CtcFeaturizer.normalizeRawX(rawX = 150.0, keyboardWidth = 600.0)).isWithin(1e-9).of(0.25)
    }

    @Test
    fun featurizer_buildPaddedLayout_padsAndMasks() {
        val layout = CtcLayout.of(
            letters = listOf('a', 'b', 'c'),
            cx = listOf(0.1f, 0.5f, 0.9f),
            cy = listOf(0.2f, 0.4f, 0.6f),
        )
        val padded = CtcFeaturizer.buildPaddedLayout(layout)
        assertThat(padded.keys.size).isEqualTo(CtcFeaturizer.MAX_KEYS * 2)
        assertThat(padded.mask.size).isEqualTo(CtcFeaturizer.MAX_KEYS)
        // Real keys interleaved (cx,cy) in slots 0..2; mask true there, false after.
        assertThat(padded.keys[0]).isEqualTo(0.1f); assertThat(padded.keys[1]).isEqualTo(0.2f)
        assertThat(padded.keys[4]).isEqualTo(0.9f); assertThat(padded.keys[5]).isEqualTo(0.6f)
        assertThat(padded.mask[0]).isTrue(); assertThat(padded.mask[2]).isTrue()
        assertThat(padded.mask[3]).isFalse()
        // Padding slots are zeroed.
        assertThat(padded.keys[6]).isEqualTo(0f); assertThat(padded.keys[7]).isEqualTo(0f)
    }

    // ── Beam decoder basics ───────────────────────────────────────────────────────

    @Test
    fun beamDecoder_respectsTopKAndReturnsLexiconWords() {
        val trie = CtcLexiconTrie(az)
        listOf("cat" to 200.0, "car" to 180.0, "cab" to 90.0).forEach { trie.insert(it.first, it.second) }
        // Emissions clearly spelling "cat": c, a, a, blank, t, blank.
        val blank = az.size
        fun frame(peak: Int): FloatArray = FloatArray(az.size + 1) { if (it == peak) -0.05f else -8f }
        val rows = listOf(frame(2), frame(0), frame(0), frame(blank), frame(19), frame(blank))
        val values = FloatArray(rows.size * (az.size + 1))
        rows.forEachIndexed { t, r -> System.arraycopy(r, 0, values, t * r.size, r.size) }
        val emissions = CtcEmissions(values, rows.size, az.size + 1)

        val params = CtcScoringParams.encoderOnly(beamWidth = 50, topK = 2)
        val result = CtcBeamDecoder.decode(emissions, trie, params)
        assertThat(result.size).isAtMost(2)
        assertThat(result.first().word).isEqualTo("cat")
        // Every returned word is in the lexicon and carries finite scores.
        for (cand in result) {
            assertThat(trie.contains(cand.word)).isTrue()
            assertThat(cand.finalScore.isFinite()).isTrue()
            assertThat(cand.length).isEqualTo(cand.word.length)
        }
    }

    // ── Facade seam (the retrain-fork boundary made concrete) ─────────────────────

    @Test
    fun ctcSwipeDecoder_wiresFeaturizerModelBeam_viaFakeEmissionModel() {
        // A fake emission model stands in for the (absent) CTC encoder, proving the whole
        // facade wiring is complete and only the model itself is missing.
        val layout = CtcLayout.of(
            letters = ('a'..'z').toList(),
            cx = List(26) { 0.02f + 0.96f * it / 25f },
            cy = List(26) { 0.5f },
        )
        val trie = CtcLexiconTrie(layout.alphabet)
        listOf("the" to 255.0, "he" to 200.0, "them" to 90.0).forEach { trie.insert(it.first, it.second) }

        val blank = layout.alphabet.size
        fun frame(peak: Int): FloatArray = FloatArray(blank + 1) { if (it == peak) -0.05f else -8f }
        val fakeModel = object : CtcEmissionModel {
            override fun emit(features: FloatArray, layoutTensors: CtcFeaturizer.PaddedLayout): CtcEmissions {
                // Prove the featurizer ran (correct-length tensor) then return "the" emissions.
                require(features.size == 2 * CtcFeaturizer.RESAMPLE_LENGTH)
                val rows = listOf(frame(19), frame(blank), frame(7), frame(7), frame(4), frame(blank))
                val values = FloatArray(rows.size * (blank + 1))
                rows.forEachIndexed { t, r -> System.arraycopy(r, 0, values, t * r.size, r.size) }
                return CtcEmissions(values, rows.size, blank + 1)
            }
        }

        val decoder = CtcSwipeDecoder(fakeModel, layout, trie, CtcScoringParams.encoderOnly(beamWidth = 50, topK = 3))
        val result = decoder.decode(
            px = doubleArrayOf(0.75, 0.6, 0.25),
            py = doubleArrayOf(0.5, 0.5, 0.5),
            pt = doubleArrayOf(0.0, 60.0, 120.0),
        )
        assertThat(result.map { it.word }).contains("the")
        assertThat(result.first().word).isEqualTo("the")
    }
}
