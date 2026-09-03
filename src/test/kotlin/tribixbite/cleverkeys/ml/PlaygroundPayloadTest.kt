package tribixbite.cleverkeys.ml

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-JVM pins for the Swipe Playground live-panel payload (2026-09-03):
 * [PlaygroundPayload.build] wire shape and the ranking/meta text formatting the
 * playground activity renders. Runs in `runPureTests` (org.json only).
 */
class PlaygroundPayloadTest {

    private fun sampleData(): SwipeMLData = SwipeMLData(
        "hello", "playground", 1080, 1920, 480, "latn_qwerty_us", SwipeMLData.ENGINE_CTC
    ).apply {
        addRawPoint(100f, 1500f, 1_000L)
        addRawPoint(200f, 1520f, 1_040L)
        addRegisteredKey("h")
        addRegisteredKey("o")
        setKeyGeometry(listOf(SwipeMLData.KeyGeom("q", 0f, 0f, 108f, 120f)))
        setCandidates(listOf("hello", "hells", "hello's"), listOf(950, 400, 120))
        setDecodeLatencyMs(87L)
    }

    @Test
    fun buildCarriesRankingAndProvenance() {
        val payload = PlaygroundPayload.build(
            sampleData(), "hello", engineWordCount = 2, storedAs = PlaygroundPayload.STORED_PLAYGROUND
        )

        assertThat(payload.getString("committed_word")).isEqualTo("hello")
        assertThat(payload.getString("engine")).isEqualTo(SwipeMLData.ENGINE_CTC)
        assertThat(payload.getString("layout")).isEqualTo("latn_qwerty_us")
        assertThat(payload.getInt("engine_word_count")).isEqualTo(2)
        assertThat(payload.getString("stored_as")).isEqualTo("playground")
        assertThat(payload.getLong("latency_ms")).isEqualTo(87L)
        assertThat(payload.getInt("point_count")).isEqualTo(2)
        assertThat(payload.getInt("key_geometry_count")).isEqualTo(1)

        val cands = payload.getJSONArray("candidates")
        assertThat(cands.length()).isEqualTo(3)
        assertThat(cands.getJSONObject(0).getString("word")).isEqualTo("hello")
        assertThat(cands.getJSONObject(0).getInt("score")).isEqualTo(950)
    }

    /** A missing capture must still produce a renderable payload — never a blank panel. */
    @Test
    fun buildWithoutCaptureIsRenderable() {
        val payload = PlaygroundPayload.build(
            null, "hi", engineWordCount = 0, storedAs = PlaygroundPayload.STORED_NONE
        )

        assertThat(payload.getString("committed_word")).isEqualTo("hi")
        assertThat(payload.getString("engine")).isEqualTo(SwipeMLData.UNKNOWN)
        assertThat(payload.has("latency_ms")).isFalse()
        assertThat(payload.getJSONArray("candidates").length()).isEqualTo(0)
        assertThat(PlaygroundPayload.formatCandidates(payload)).isEqualTo("(no candidates)")
    }

    @Test
    fun formatCandidatesRanksAndMarksAugmentedEntries() {
        val payload = PlaygroundPayload.build(
            sampleData(), "hello", engineWordCount = 2, storedAs = PlaygroundPayload.STORED_PLAYGROUND
        )

        val lines = PlaygroundPayload.formatCandidates(payload).split("\n")
        assertThat(lines).hasSize(3)
        assertThat(lines[0]).isEqualTo("1. hello  (950)")
        assertThat(lines[1]).isEqualTo("2. hells  (400)")
        // Entry index 2 >= engineWordCount 2 → an augmentation (e.g. possessive), marked.
        assertThat(lines[2]).isEqualTo("3. hello's  (120)  [augmented]")
    }

    @Test
    fun formatMetaNamesTheStoreThatTookTheRow() {
        fun metaFor(storedAs: String): String = PlaygroundPayload.formatMeta(
            PlaygroundPayload.build(sampleData(), "hello", 3, storedAs)
        )

        assertThat(metaFor(PlaygroundPayload.STORED_PLAYGROUND)).contains("[recorded: playground]")
        assertThat(metaFor(PlaygroundPayload.STORED_GLOBAL)).contains("[recorded: global ML store]")
        assertThat(metaFor(PlaygroundPayload.STORED_NONE)).contains("[not recorded]")
        // Engine + latency + capture sizes are all present for the panel's meta line.
        val meta = metaFor(PlaygroundPayload.STORED_PLAYGROUND)
        assertThat(meta).contains("engine=ctc")
        assertThat(meta).contains("layout=latn_qwerty_us")
        assertThat(meta).contains("87 ms")
        assertThat(meta).contains("pts=2")
        assertThat(meta).contains("keys=1")
    }

    @Test
    fun formatLogBlockLeadsWithTheCommittedWord() {
        val payload = PlaygroundPayload.build(
            sampleData(), "hello", engineWordCount = 3, storedAs = PlaygroundPayload.STORED_PLAYGROUND
        )
        val block = PlaygroundPayload.formatLogBlock(payload)
        assertThat(block).startsWith("── SWIPE → \"hello\" ──\n")
        assertThat(block).contains("1. hello  (950)")
        assertThat(block).endsWith("\n")
    }
}
