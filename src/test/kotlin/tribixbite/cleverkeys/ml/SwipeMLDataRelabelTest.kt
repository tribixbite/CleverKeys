package tribixbite.cleverkeys.ml

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONObject
import org.junit.Test

/**
 * Feature B of the swipe-correction resolution (learning-system audit 2026-09-26): a stored
 * swipe-ML row labelled with the word the swipe AUTO-INSERTED is relabelled with the word the
 * user corrected it to, keeping the original under `metadata.corrected_from`.
 */
class SwipeMLDataRelabelTest {

    private fun storedRow(word: String): JSONObject {
        val data = SwipeMLData(word, "user_selection", 1080, 2400, 900, "qwerty", SwipeMLData.ENGINE_CTC)
        data.addRawPoint(100f, 2000f, data.timestampUtc)
        data.addRawPoint(300f, 2010f, data.timestampUtc + 40)
        return data.toJSON()
    }

    @Test
    fun relabelSetsTheLabelAndRecordsTheOriginal() {
        val json = SwipeMLData.relabelJson(storedRow("got"), "Git")

        assertThat(json.getString("target_word")).isEqualTo("git")
        assertThat(json.getJSONObject("metadata").getString(SwipeMLData.KEY_CORRECTED_FROM)).isEqualTo("got")

        val reloaded = SwipeMLData(JSONObject(json.toString()))
        assertThat(reloaded.targetWord).isEqualTo("git")
        assertThat(reloaded.getCorrectedFrom()).isEqualTo("got")
        assertWithMessage("provenance untouched").that(reloaded.engine).isEqualTo(SwipeMLData.ENGINE_CTC)
        assertWithMessage("source untouched").that(reloaded.collectionSource).isEqualTo("user_selection")
        assertWithMessage("trace untouched").that(reloaded.getTracePoints()).hasSize(2)
    }

    @Test
    fun aSecondRelabelKeepsTheDecodersOriginalWord() {
        val json = SwipeMLData.relabelJson(SwipeMLData.relabelJson(storedRow("got"), "for"), "git")
        assertThat(json.getString("target_word")).isEqualTo("git")
        assertThat(json.getJSONObject("metadata").getString(SwipeMLData.KEY_CORRECTED_FROM)).isEqualTo("got")
    }

    @Test
    fun relabelToTheSameOrABlankWordIsANoOp() {
        val same = SwipeMLData.relabelJson(storedRow("git"), "GIT")
        assertThat(same.getJSONObject("metadata").has(SwipeMLData.KEY_CORRECTED_FROM)).isFalse()
        val blank = SwipeMLData.relabelJson(storedRow("git"), " ")
        assertThat(blank.getString("target_word")).isEqualTo("git")
    }

    @Test
    fun anUncorrectedRowRoundTripsWithoutTheKey() {
        val row = storedRow("hello")
        assertThat(row.getJSONObject("metadata").has(SwipeMLData.KEY_CORRECTED_FROM)).isFalse()
        assertThat(SwipeMLData(row).getCorrectedFrom()).isNull()
    }

    @Test
    fun aCorrectedRowReserializesTheKey() {
        val loaded = SwipeMLData(SwipeMLData.relabelJson(storedRow("got"), "git"))
        assertThat(loaded.toJSON().getJSONObject("metadata").getString(SwipeMLData.KEY_CORRECTED_FROM))
            .isEqualTo("got")
    }
}
