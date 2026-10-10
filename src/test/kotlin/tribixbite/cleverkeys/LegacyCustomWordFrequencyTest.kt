package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The legacy-default-frequency offer (maintainer decision A, 2026-10-10) and the Add/Edit dialog's
 * priority-driven frequency lift (decision B). Pure JVM — the Dictionary Manager only wires these.
 */
class LegacyCustomWordFrequencyTest {

    // ── A: which entries are legacy ────────────────────────────────────────────────

    @Test
    fun onlyTheExactOldDefaultIsLegacy() {
        val stored = linkedMapOf(
            "adb" to 100, "Kotlin" to 100, "wet" to 255, "low" to 1, "ninetynine" to 99,
            "hundredone" to 101, "mid" to 150, "oldscale" to 10000,
        )
        assertThat(LegacyCustomWordFrequency.legacyWords(stored)).containsExactly("adb", "Kotlin").inOrder()
        assertThat(LegacyCustomWordFrequency.LEGACY_DEFAULT).isEqualTo(100)
        assertThat(LegacyCustomWordFrequency.legacyWords(emptyMap())).isEmpty()
    }

    @Test
    fun legacyListOrderIsCaseInsensitiveAndStable() {
        val stored = linkedMapOf("zed" to 100, "Bee" to 100, "bee" to 100, "apple" to 100)
        assertThat(LegacyCustomWordFrequency.legacyWords(stored))
            .containsExactly("apple", "Bee", "bee", "zed").inOrder()
    }

    // ── A: raise ───────────────────────────────────────────────────────────────────

    @Test
    fun raiseLiftsOnlyListedWordsStillAtTheOldDefault() {
        val stored = linkedMapOf("adb" to 100, "keep" to 100, "edited" to 180, "wet" to 255)
        // "edited" was in the list the user saw but was changed to 180 before confirming.
        val raised = LegacyCustomWordFrequency.raise(stored, listOf("adb", "edited", "gone"))
        assertThat(raised).containsExactly("adb", 255, "keep", 100, "edited", 180, "wet", 255).inOrder()
        assertThat(LegacyCustomWordFrequency.raisableCount(stored, listOf("adb", "edited", "gone"))).isEqualTo(1)
        // The input map is not mutated.
        assertThat(stored["adb"]).isEqualTo(100)
    }

    @Test
    fun raiseTargetsTheStoredScaleCeiling() {
        val raised = LegacyCustomWordFrequency.raise(mapOf("adb" to 100), listOf("adb"))
        assertThat(raised["adb"]).isEqualTo(UserWordFrequency.DEFAULT)
        assertThat(raised["adb"]).isEqualTo(UserWordFrequency.MAX)
    }

    // ── A: dismissal ───────────────────────────────────────────────────────────────

    @Test
    fun offerShowsUntilDismissedAndReturnsOnlyForANewLegacyWord() {
        val legacy = listOf("adb", "kotlin")
        assertThat(LegacyCustomWordFrequency.shouldOffer(legacy, emptySet())).isTrue()

        val json = LegacyCustomWordFrequency.withDismissed(null, "en", legacy)
        val dismissed = LegacyCustomWordFrequency.dismissedFor(json, "en")
        assertThat(LegacyCustomWordFrequency.shouldOffer(legacy, dismissed)).isFalse()
        // Raising one of them (it leaves the legacy set) does not bring the notice back.
        assertThat(LegacyCustomWordFrequency.shouldOffer(listOf("kotlin"), dismissed)).isFalse()
        // A NEW legacy entry (e.g. from a backup import without frequencies) does.
        assertThat(LegacyCustomWordFrequency.shouldOffer(listOf("adb", "kotlin", "imported"), dismissed)).isTrue()
        assertThat(LegacyCustomWordFrequency.shouldOffer(emptyList(), dismissed)).isFalse()
    }

    @Test
    fun dismissalIsPerLanguageAndReplacesThatLanguagesSet() {
        var json = LegacyCustomWordFrequency.withDismissed(null, "en", listOf("adb"))
        json = LegacyCustomWordFrequency.withDismissed(json, "DE", listOf("Wort"))
        assertThat(LegacyCustomWordFrequency.dismissedFor(json, "en")).containsExactly("adb")
        assertThat(LegacyCustomWordFrequency.dismissedFor(json, "de")).containsExactly("Wort")
        json = LegacyCustomWordFrequency.withDismissed(json, "en", listOf("x", "y"))
        assertThat(LegacyCustomWordFrequency.dismissedFor(json, "en")).containsExactly("x", "y")
        assertThat(LegacyCustomWordFrequency.dismissedFor(json, "de")).containsExactly("Wort")
        json = LegacyCustomWordFrequency.withDismissed(json, "en", emptyList())
        assertThat(LegacyCustomWordFrequency.parseDismissed(json).keys).containsExactly("de")
    }

    @Test
    fun dismissalRecordIsNeverExportedInABackup() {
        // Per-device UI state: a restore on another device must re-offer imported legacy entries.
        assertThat(tribixbite.cleverkeys.backup.SettingsValidation.INTERNAL_KEYS)
            .contains(LegacyCustomWordFrequency.DISMISSED_PREF_KEY)
    }

    @Test
    fun damagedDismissalRecordReadsAsNothingDismissed() {
        for (bad in listOf(null, "", "   ", "not json", "[1,2]", "{\"en\":5}", "{\"en\":[1,true]}")) {
            assertWithMessage("record $bad").that(LegacyCustomWordFrequency.dismissedFor(bad, "en")).isEmpty()
        }
        // A damaged record is overwritten cleanly by the next dismissal.
        val json = LegacyCustomWordFrequency.withDismissed("not json", "en", listOf("adb"))
        assertThat(LegacyCustomWordFrequency.dismissedFor(json, "en")).containsExactly("adb")
    }

    // ── B: priority selection lifts the frequency ──────────────────────────────────

    @Test
    fun raisedPriorityLiftsALowerOrEmptyFrequencyToTheTop() {
        for (p in listOf(SwipePriority.HIGH, SwipePriority.HIGHEST)) {
            for (f in listOf(1, 100, 150, 254)) {
                assertWithMessage("$p at $f").that(UserWordFrequency.liftedForPriority(p, f)).isEqualTo(255)
            }
            assertWithMessage("$p at empty").that(UserWordFrequency.liftedForPriority(p, null)).isEqualTo(255)
            assertWithMessage("$p at 255").that(UserWordFrequency.liftedForPriority(p, 255)).isNull()
            // An out-of-scale stored value is already saturated by scaleOnto; leave the field alone.
            assertWithMessage("$p at 10000").that(UserWordFrequency.liftedForPriority(p, 10000)).isNull()
        }
    }

    @Test
    fun normalPriorityNeverTouchesTheFrequency() {
        for (f in listOf(null, 1, 100, 254, 255)) {
            assertWithMessage("NORMAL at $f").that(UserWordFrequency.liftedForPriority(SwipePriority.NORMAL, f)).isNull()
        }
    }
}
