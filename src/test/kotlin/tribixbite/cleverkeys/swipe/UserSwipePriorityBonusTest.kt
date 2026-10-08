package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.SwipePriority
import tribixbite.cleverkeys.swipe.ctc.CtcAzProjection
import tribixbite.cleverkeys.swipe.ctc.CtcPriorityBonus
import java.io.File

/**
 * How stored swipe priorities become engine inputs (user swipe priority, 2026-10-08): the level
 * table, CTC surface keying, the geometric per-word table, the lexicon-memo version, and the
 * adapter wiring (source scans — both adapters need an Android `Context`).
 */
class UserSwipePriorityBonusTest {

    private val az = "abcdefghijklmnopqrstuvwxyz".toSet()
    private val strip: (String) -> String? = { UserJoinerPreference.stripToAlphabet(it, az) }

    @Test
    fun levelsAreOrderedBoundedAndNormalIsZero() {
        assertThat(UserSwipePriorityBonus.ctcBonus(SwipePriority.NORMAL)).isEqualTo(0.0)
        assertThat(UserSwipePriorityBonus.geometricBonus(SwipePriority.NORMAL)).isEqualTo(0f)
        assertThat(UserSwipePriorityBonus.CTC_HIGH).isGreaterThan(0.0)
        assertThat(UserSwipePriorityBonus.CTC_HIGHEST).isGreaterThan(UserSwipePriorityBonus.CTC_HIGH)
        assertThat(UserSwipePriorityBonus.CTC_HIGHEST).isAtMost(CtcPriorityBonus.MAX_BONUS)
        assertThat(UserSwipePriorityBonus.GEO_HIGH).isGreaterThan(0f)
        assertThat(UserSwipePriorityBonus.GEO_HIGHEST).isGreaterThan(UserSwipePriorityBonus.GEO_HIGH)
    }

    @Test
    fun ctcTableIsKeyedOnTheTrieSurfaceOfUserWordsOnly() {
        val userWords = listOf("ADB" to 255, "she'd" to 255, "wet" to 200, "plain" to 255)
        val levels = mapOf(
            "ADB" to SwipePriority.HIGHEST,
            "she'd" to SwipePriority.HIGH,
            "wet" to SwipePriority.HIGH,
            "removed" to SwipePriority.HIGHEST, // no longer a user word: must not act
        )
        val table = UserSwipePriorityBonus.ctcBySurface(userWords, levels, strip)
        assertThat(table.bonusFor("adb")).isEqualTo(UserSwipePriorityBonus.CTC_HIGHEST)
        assertWithMessage("a joiner word acts on the surface the trie filed it under")
            .that(table.bonusFor("shed")).isEqualTo(UserSwipePriorityBonus.CTC_HIGH)
        assertThat(table.bonusFor("wet")).isEqualTo(UserSwipePriorityBonus.CTC_HIGH)
        assertThat(table.bonusFor("plain")).isEqualTo(0.0)
        assertThat(table.bonusFor("removed")).isEqualTo(0.0)
        assertThat(table.size).isEqualTo(3)
    }

    @Test
    fun twoUserWordsOnOneSurfaceKeepTheLargerBonus() {
        val table = UserSwipePriorityBonus.ctcBySurface(
            listOf("shed" to 255, "she'd" to 255),
            mapOf("shed" to SwipePriority.HIGH, "she'd" to SwipePriority.HIGHEST),
            strip,
        )
        assertThat(table.bonusFor("shed")).isEqualTo(UserSwipePriorityBonus.CTC_HIGHEST)
    }

    @Test
    fun ckdtSurfacesUseTheProjection() {
        val table = UserSwipePriorityBonus.ctcBySurface(
            listOf("ouais" to 255, "été" to 255), mapOf("été" to SwipePriority.HIGH), CtcAzProjection::project)
        assertThat(table.bonusFor("ete")).isEqualTo(UserSwipePriorityBonus.CTC_HIGH)
    }

    @Test
    fun nothingRaisedIsTheNoneInstance() {
        assertThat(UserSwipePriorityBonus.ctcBySurface(listOf("adb" to 255), emptyMap(), strip))
            .isSameInstanceAs(CtcPriorityBonus.NONE)
        assertThat(UserSwipePriorityBonus.ctcBySurface(listOf("adb" to 255), mapOf("adb" to SwipePriority.NORMAL), strip))
            .isSameInstanceAs(CtcPriorityBonus.NONE)
        assertThat(UserSwipePriorityBonus.geometricBonusByWord(listOf("adb" to 255), emptyMap())).isEmpty()
    }

    @Test
    fun geometricTableIsKeyedOnTheUserWordAsListed() {
        val bonuses = UserSwipePriorityBonus.geometricBonusByWord(
            listOf("Adb" to 255, "wet" to 255),
            mapOf("adb" to SwipePriority.HIGH, "gone" to SwipePriority.HIGHEST),
        )
        assertThat(bonuses).containsExactly("Adb", UserSwipePriorityBonus.GEO_HIGH)
    }

    // ── memo version ────────────────────────────────────────────────────────────────

    @Test
    fun anEmptyPriorityStoreKeepsThePrePriorityVersion() {
        val before = LexiconContentVersion.of("asset:x", "{\"adb\":255}", setOf("a"), "fp")
        assertThat(LexiconContentVersion.of("asset:x", "{\"adb\":255}", setOf("a"), "fp", "")).isEqualTo(before)
        val raised = LexiconContentVersion.of("asset:x", "{\"adb\":255}", setOf("a"), "fp", "{\"adb\":1}")
        val higher = LexiconContentVersion.of("asset:x", "{\"adb\":255}", setOf("a"), "fp", "{\"adb\":2}")
        assertThat(raised).isNotEqualTo(before)
        assertThat(higher).isNotEqualTo(raised)
    }

    // ── adapter wiring (source scans) ───────────────────────────────────────────────

    private fun source(relative: String): String = File("src/main/kotlin/$relative").readText()

    @Test
    fun bothAdaptersFoldThePriorityStoreIntoTheVersionAndTheDecode() {
        val ctc = source("tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt")
        assertThat(ctc).contains("LanguagePreferenceKeys.swipePriorityKey(lang)")
        assertThat(ctc).contains("userDictionary.fingerprint, priorityJson")
        assertWithMessage("both CTC source branches build the surface table")
            .that(Regex("UserSwipePriorityBonus\\.ctcBySurface\\(").findAll(ctc).count()).isEqualTo(2)
        assertThat(ctc).contains("priority = lexicon.priority")

        val geo = source("tribixbite/cleverkeys/swipe/GeometricEngineAdapter.kt")
        assertThat(geo).contains("LanguagePreferenceKeys.swipePriorityKey(lang)")
        assertThat(geo).contains("userDictionary.fingerprint, priorityJson")
        assertThat(geo).contains("UserSwipePriorityBonus.geometricBonusByWord(")

        val engine = source("tribixbite/cleverkeys/swipe/geometric/GeometricSwipeEngine.kt")
        assertWithMessage("the geometric bonus is applied to pruner survivors only")
            .that(engine).contains("scorer.score(gesture, template, layout, ordinal) + dictionary.swipeBonus(ordinal)")
    }

    @Test
    fun tapPredictionDoesNotReadThePriorityStore() {
        // Deliberate (eval note §5): a raised swipe word would otherwise take prefix completions
        // (`a` → `adb` over `and`) where the user never asked for a change.
        assertThat(source("tribixbite/cleverkeys/WordPredictor.kt")).doesNotContain("swipePriority")
    }
}
