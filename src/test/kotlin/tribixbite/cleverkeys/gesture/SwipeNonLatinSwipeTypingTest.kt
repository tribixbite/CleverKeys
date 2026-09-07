package tribixbite.cleverkeys

import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * B-1 (2026-09-06 comprehensive audit, P1): swipe typing could never trigger on any
 * non-Latin board.
 *
 * The recognizer's key gate ([ImprovedSwipeGestureRecognizer]'s `isValidAlphabeticKey`) and
 * its twin in [ProbabilisticKeyDetector] were hard-coded `'a'..'z' || 'A'..'Z'`, so on a
 * Cyrillic/Greek/Hebrew layout (cyrl_jcuken_ru, grek_qwerty, hebr_1_il, …) NO letter key
 * ever joined `_touchedKeys`, `isSwipeTyping()` stayed false for the whole gesture, and a
 * word-shaped swipe was dispatched to NEITHER engine — the six ROUTED script languages
 * (ru/el/uk/bg/mk/he), their shipped CTC models and langpacks, and geometric's non-Latin
 * coverage were all downstream of a gate that never opened.
 *
 * ## Why this test drives the REAL gesture path
 *
 * Every prior test injected BELOW the gate (registrar level with generic keys, or straight
 * at the adapter/engine with pre-built traces), which is exactly how the bug stayed
 * invisible. This test constructs the real [ImprovedSwipeGestureRecognizer] and drives
 * `startSwipe`/`addPoint`/`promoteWordCandidacy`/`endSwipe` with real [KeyValue] keys —
 * the same call sequence [Pointers]/[Keyboard2View] make from a touch stream. It runs
 * off-device because the test classpath replaces the `android.jar` PointF stub with a real
 * data holder (see `src/test/kotlin/android/graphics/PointF.kt`); the wall clock is real,
 * so samples are spaced with short sleeps to keep every inter-point delta positive and far
 * below the recognizer's 500 ms pause cutoff.
 *
 * Red before the fix: the ru/el/he cases fail (no candidacy, `endSwipe().keys == null`).
 * The Latin case and both non-letter cases pin that the widened predicate
 * ([tribixbite.cleverkeys.swipe.KeyLetter]) is behavior-identical where the old one worked.
 */
class SwipeNonLatinSwipeTypingTest {

    private lateinit var config: Config

    @Before
    fun setUp() {
        // Debug classes may be compiled with ENABLE_VERBOSE_LOGGING=true, in which case the
        // production paths call android.util.Log, whose android.jar stub throws.
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        // The recognizer reads its thresholds from Config.globalConfig() on every sample.
        // The values below neutralise the gates that are NOT under test (dwell/velocity,
        // noise, smoothing) so the run is deterministic under wall-clock timing, while
        // keeping the candidacy gates (2 keys + minimum travel) real.
        mockkObject(Config.Companion)
        config = mockk(relaxed = true)
        config.swipe_smoothing_window = 1
        config.swipe_min_distance = 30f          // candidacy: total travel required
        config.swipe_min_key_distance = 20f      // registrar gate 4: travel between keys
        config.swipe_min_dwell_time = 0L         // gate 2 (dwell/velocity) can never fire
        config.swipe_noise_threshold = 1f
        config.swipe_high_velocity_threshold = 1_000_000f
        every { Config.globalConfig() } returns config
    }

    @After
    fun tearDown() = unmockkAll()

    // ------------------------------------------------------------------------- fixtures

    private fun letterKey(c: Char): KeyboardData.Key =
        KeyboardData.Key.EMPTY.withKeyValue(0, KeyValue.makeCharKey(c))

    /**
     * Drives the real touch-stream call sequence: touch-down on [first], six samples
     * marching 30 px per step, the second half over [second]. Total travel 180 px; the
     * finger enters [second]'s territory 120 px from the start, far past the 20 px
     * key-travel gate.
     */
    private fun driveTwoKeySwipe(
        first: KeyboardData.Key,
        second: KeyboardData.Key,
    ): ImprovedSwipeGestureRecognizer {
        val recognizer = ImprovedSwipeGestureRecognizer()
        recognizer.startSwipe(0f, 50f, first)
        var x = 0f
        repeat(6) { i ->
            // Real wall clock: each sample must arrive with delta > 0 (equal-millisecond
            // samples are dropped) and < 500 ms (the pause cutoff re-anchors and skips).
            Thread.sleep(6)
            x += 30f
            recognizer.addPoint(x, 50f, if (i < 3) first else second)
        }
        return recognizer
    }

    private fun assertSwipeTypingEngages(first: Char, second: Char, board: String) {
        val recognizer = driveTwoKeySwipe(letterKey(first), letterKey(second))

        assertWithMessage(
            "$board: a two-letter word swipe ($first -> $second) must promote to word " +
                "candidacy — this is the gate every engine dispatch sits behind"
        ).that(recognizer.promoteWordCandidacy()).isTrue()
        assertThat(recognizer.isSwipeTyping()).isTrue()

        val result = recognizer.endSwipe()
        assertWithMessage("$board: endSwipe() must hand the touched keys to the decoders")
            .that(result.keys).isNotNull()
        assertThat(result.keys!!.map { it.keys[0]!!.getChar() })
            .containsExactly(first, second).inOrder()
        assertThat(result.isSwipeTyping).isTrue()
    }

    // ----------------------------------------------------- the routed script languages

    @Test
    fun `Cyrillic swipe engages swipe typing`() =
        assertSwipeTypingEngages('й', 'в', board = "cyrl_jcuken_ru")

    @Test
    fun `Greek swipe engages swipe typing`() =
        assertSwipeTypingEngages('α', 'ξ', board = "grek_qwerty")

    @Test
    fun `Hebrew swipe engages swipe typing`() =
        assertSwipeTypingEngages('ש', 'ל', board = "hebr_1_il")

    // ------------------------------------------------- Latin stays behavior-identical

    @Test
    fun `Latin swipe still engages swipe typing`() =
        assertSwipeTypingEngages('a', 's', board = "latn_qwerty_us")

    @Test
    fun `digit and non-Char keys still never register`() {
        // The widened predicate must stay a LETTER gate: a swipe across a digit key and a
        // special key registers at most the letter under the start point — never candidacy.
        val digit = letterKey('7')
        val backspace = KeyboardData.Key.EMPTY.withKeyValue(0, KeyValue.getKeyByName("backspace"))
        val recognizer = ImprovedSwipeGestureRecognizer()
        recognizer.startSwipe(0f, 50f, digit)
        var x = 0f
        repeat(6) { i ->
            Thread.sleep(6)
            x += 30f
            recognizer.addPoint(x, 50f, if (i < 3) digit else backspace)
        }
        assertWithMessage("digits/action keys are not letters; no word candidacy")
            .that(recognizer.promoteWordCandidacy()).isFalse()
        assertThat(recognizer.endSwipe().keys).isNull()
    }

    @Test
    fun `a combining-mark key still never registers`() {
        // Kind.Char but not a letter (U+0301 COMBINING ACUTE) — Char.isLetter is false, so
        // the widened gate must keep rejecting it.
        val combining = letterKey('\u0301')
        val recognizer = driveTwoKeySwipe(letterKey('е'), combining)
        assertThat(recognizer.promoteWordCandidacy()).isFalse()
    }

    // --------------------------------------- the twin gate in ProbabilisticKeyDetector

    /** A 3-key row board: key i occupies x in [i, i+1) of a 3-unit-wide, 1-unit-tall grid. */
    private fun threeKeyBoard(chars: List<Char>): KeyboardData {
        val keys = chars.map { c ->
            KeyboardData.Key(List(9) { i -> if (i == 0) KeyValue.makeCharKey(c) else null },
                null, 0, 1f, 0f, null)
        }
        val kb = mockk<KeyboardData>()
        every { kb.rows } returns listOf(KeyboardData.Row(keys, 1f, 0f))
        every { kb.keysWidth } returns 3f
        every { kb.keysHeight } returns 1f
        return kb
    }

    private fun assertDetectorSeesAllKeys(chars: List<Char>, board: String) {
        val kb = threeKeyBoard(chars)
        // 300x100 px view: key centres at x = 50/150/250, y = 50.
        val detector = ProbabilisticKeyDetector(kb, 300f, 100f)
        val path = listOf(
            android.graphics.PointF(50f, 50f),
            android.graphics.PointF(150f, 50f),
            android.graphics.PointF(250f, 50f),
        )
        val detected = detector.detectKeys(path).map { it.keys[0]!!.getChar() }
        assertWithMessage(
            "$board: the probabilistic detector's own letter gate must accept these keys"
        ).that(detected).containsExactlyElementsIn(chars)
    }

    @Test
    fun `probabilistic detector sees Cyrillic keys`() =
        assertDetectorSeesAllKeys(listOf('й', 'ц', 'у'), board = "cyrl_jcuken_ru")

    @Test
    fun `probabilistic detector sees Hebrew keys`() =
        assertDetectorSeesAllKeys(listOf('ש', 'ד', 'ג'), board = "hebr_1_il")

    @Test
    fun `probabilistic detector still sees Latin keys`() =
        assertDetectorSeesAllKeys(listOf('a', 's', 'd'), board = "latn_qwerty_us")
}
