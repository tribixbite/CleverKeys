package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.persist.InMemoryLearnedStorage

/** Per-language swipe-correction counts, declines, bounds and persistence ([SwipeCorrectionStore]). */
class SwipeCorrectionStoreTest {

    private var now = 10L
    private val storage = InMemoryLearnedStorage()
    private val store = SwipeCorrectionStore(storage) { now++ }

    @Test
    fun countsTargetsOncePerCorrectionAndPairsPerRejectedWord() {
        assertThat(store.recordCorrection("en", "git", listOf("got"))).isEqualTo(1)
        assertThat(store.recordCorrection("en", "Git", listOf("got", "for"))).isEqualTo(2)

        assertThat(store.correctionCount("en", "git")).isEqualTo(2)
        assertThat(store.pairCount("en", "got", "git")).isEqualTo(2)
        assertThat(store.pairCount("en", "for", "git")).isEqualTo(1)
        assertThat(store.pairCount("en", "git", "got")).isEqualTo(0)
    }

    @Test
    fun languagesAreSeparate() {
        store.recordCorrection("en", "git", listOf("got"))
        assertThat(store.correctionCount("fr", "git")).isEqualTo(0)
    }

    @Test
    fun survivesARestart() {
        store.recordCorrection("en", "git", listOf("got"))
        store.decline("en", "son")

        val reloaded = SwipeCorrectionStore(storage)
        assertThat(reloaded.correctionCount("en", "git")).isEqualTo(1)
        assertThat(reloaded.pairCount("en", "got", "git")).isEqualTo(1)
        assertThat(reloaded.isDeclined("en", "son")).isTrue()
    }

    @Test
    fun declineIsRememberedAndDropsTheCounts() {
        store.recordCorrection("en", "son", listOf("soon"))
        store.decline("en", "Son")

        assertThat(store.isDeclined("en", "son")).isTrue()
        assertThat(store.correctionCount("en", "son")).isEqualTo(0)
    }

    @Test
    fun forgetWordDropsOnlyThatWord() {
        store.recordCorrection("en", "git", listOf("got"))
        store.recordCorrection("en", "fox", listOf("fix"))
        store.forgetWord("en", "git")
        assertThat(store.correctionCount("en", "git")).isEqualTo(0)
        assertThat(store.correctionCount("en", "fox")).isEqualTo(1)
    }

    @Test
    fun clearAllErasesEveryLanguageAndDeclines() {
        store.recordCorrection("en", "git", listOf("got"))
        store.recordCorrection("de", "gut", listOf("hut"))
        store.decline("en", "son")

        store.clearAll()

        assertThat(storage.keys()).isEmpty()
        assertThat(store.correctionCount("en", "git")).isEqualTo(0)
        assertThat(store.isDeclined("en", "son")).isFalse()
        assertThat(SwipeCorrectionStore(storage).correctionCount("de", "gut")).isEqualTo(0)
    }

    @Test
    fun targetsAreBoundedLeastRecentlyCorrectedFirst() {
        val max = SwipeCorrectionStore.MAX_WORDS_PER_LANGUAGE
        for (i in 0..max) store.recordCorrection("en", "word$i", listOf("x$i"))
        assertThat(store.trackedWordCount("en")).isEqualTo(max)
        assertWithMessage("oldest evicted").that(store.correctionCount("en", "word0")).isEqualTo(0)
        assertThat(store.correctionCount("en", "word$max")).isEqualTo(1)

        // Re-correcting an old word refreshes it: word1 survives the next eviction, word2 does not.
        store.recordCorrection("en", "word1", listOf("x"))
        store.recordCorrection("en", "fresh", listOf("x"))
        assertThat(store.correctionCount("en", "word1")).isEqualTo(2)
        assertThat(store.correctionCount("en", "word2")).isEqualTo(0)

        // Order and bound survive a reload.
        assertThat(SwipeCorrectionStore(storage).trackedWordCount("en")).isEqualTo(max)
    }

    @Test
    fun rejectedFormsPerTargetAreBounded() {
        store.recordCorrection("en", "git", listOf("got"))
        store.recordCorrection("en", "git", listOf("got"))
        for (i in 0 until SwipeCorrectionStore.MAX_SOURCES_PER_WORD + 3) {
            store.recordCorrection("en", "git", listOf("r$i"))
        }
        assertWithMessage("the strongest pair survives").that(store.pairCount("en", "got", "git")).isEqualTo(2)
    }

    @Test
    fun aMalformedValueDegradesToEmpty() {
        storage.seed(SwipeCorrectionStore.KEY_PREFIX + "en", "{not json")
        val fresh = SwipeCorrectionStore(storage)
        assertThat(fresh.correctionCount("en", "git")).isEqualTo(0)
        assertThat(fresh.recordCorrection("en", "git", listOf("got"))).isEqualTo(1)
    }
}
