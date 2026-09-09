package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * The "Privacy & Data settings section does not describe the runtime" class
 * (maintainer report 2026-09-09: *"it lacks context/bigram/ngram stuff and
 * carries a broken collection toggle"*).
 *
 * Two distinct defects, pinned here because both are CALL-SITE / SURFACE facts
 * that no behavioural unit test can see:
 *
 *  - **P-1 (broken collection toggle).** `MLDataCollectionToggleTest` is green and
 *    stays green: it drives `MLDataCollector.collectAndStoreSwipeData` directly and
 *    proves the collector honours `privacy_collect_swipe`. What it cannot see is
 *    whether the production caller ever REACHES the collector. The auto-insert
 *    capture in `SuggestionHandler` was additionally gated on
 *    `config.swipe_debug_detailed_logging` — a developer flag that is default-off
 *    and only VISIBLE after enabling Swipe Debug mode in the Advanced section. A
 *    user who turned the documented privacy toggle on and swiped normally stored
 *    nothing, while `privacy_no_swipe_data` told them to "Enable collection above
 *    to start storing patterns". The tap-a-suggestion path (`SuggestionBridge`)
 *    was gated correctly, which is why the toggle looked half-alive.
 *
 *  - **P-2 (missing context/n-gram surface).** The section clears the context-LM
 *    stores from its master-gate dialog but never SHOWS them, so the largest body
 *    of learned text data on the device — bigrams, trigrams and the personalization
 *    vocabulary — had no counts and no independent control in the one section whose
 *    job is data transparency. The three learning-source toggles that decide what
 *    goes INTO those stores lived only in Input Behaviour, unrepresented here.
 *
 * Both are source-scan pins: they assert the wiring, not the copy, so they survive
 * rewording and fail the moment either regression returns.
 */
class PrivacyDataSurfaceDriftTest {

    private val srcRoot = File("src/main/kotlin/tribixbite/cleverkeys")
    private fun read(rel: String) = File(srcRoot, rel).readText()

    /**
     * The `if (…)` guard immediately preceding [callAnchor] in [text]. Slicing back
     * to the nearest `if (` is exact for both capture sites, which are each a single
     * guarded statement.
     */
    private fun guardBefore(text: String, callAnchor: String, where: String): String {
        val call = text.indexOf(callAnchor)
        check(call > 0) {
            "'$callAnchor' not found in $where — the capture site moved; re-point this " +
                "test, do not delete it."
        }
        val ifStart = text.lastIndexOf("if (", call)
        check(ifStart in 0 until call) { "No `if (` guard found before the capture in $where." }
        return text.substring(ifStart, call)
    }

    // ── P-1: the privacy toggle must be the thing that governs capture ──────────

    /**
     * Preference keys that must never appear in a swipe-ML capture guard. These are
     * developer-diagnostics switches; conditioning a user-facing privacy promise on
     * one makes the promise unkeepable without also finding the debug switch.
     */
    private val debugOnlyKeys = listOf(
        "swipe_debug_detailed_logging",
        "swipe_debug_enabled",
    )

    @Test
    fun theAutoInsertCaptureIsNotGatedOnADeveloperDebugFlag() {
        val guard = guardBefore(
            read("SuggestionHandler.kt"),
            "mlDataCollector.collectAndStoreSwipeData(",
            "SuggestionHandler.kt"
        )
        for (key in debugOnlyKeys) {
            assertWithMessage(
                "The auto-insert swipe capture is guarded by `$key`, a default-off developer " +
                    "flag hidden behind Swipe Debug mode. `privacy_collect_swipe` is the " +
                    "documented control; with the debug flag off, turning the privacy toggle " +
                    "on stores nothing on the dominant swipe path. Guard on data availability " +
                    "only and let MLDataCollector's privacy check decide.\nGuard was: $guard"
            ).that(guard).doesNotContain(key)
        }
    }

    /**
     * Ratchet over BOTH production capture sites, so the class cannot grow back on
     * whichever path happens to be edited next.
     */
    @Test
    fun noSwipeMlCaptureSiteIsGatedOnADeveloperDebugFlag() {
        val sites = mapOf(
            "SuggestionHandler.kt" to read("SuggestionHandler.kt"),
            "wiring/SuggestionBridge.kt" to read("wiring/SuggestionBridge.kt"),
        )
        val offenders = sites.mapNotNull { (where, text) ->
            val guard = guardBefore(text, "mlDataCollector.collectAndStoreSwipeData(", where)
            debugOnlyKeys.firstOrNull { it in guard }?.let { "$where guards capture on $it" }
        }
        assertWithMessage(
            "A swipe-ML capture site conditioned on a developer flag silently voids the " +
                "privacy_collect_swipe toggle for users who never enable Swipe Debug"
        ).that(offenders).isEmpty()
    }

    // ── P-2: the section must SHOW the context-learning stores it can erase ─────

    /**
     * The section's master-gate dialog already calls `clearAll()` on both n-gram
     * stores, so it destroys data it never displays. Surfacing means READING the
     * counts — a section that can only erase gives the user no way to know whether
     * erasing did anything, or how much is held.
     */
    @Test
    fun thePrivacySectionShowsWhatTheContextLearningStoresHold() {
        val section = read("ui/settings/sections/PrivacySection.kt")

        // Sanity: the section really does clear these stores today. If this ever
        // stops being true the pin below must be re-argued, not silently dropped.
        check("BigramStore" in section && "TrigramStore" in section) {
            "PrivacySection no longer references the n-gram stores — re-point this test."
        }

        val reads = mapOf(
            "learned phrase pairs (bigrams)" to "getTotalBigramCount(",
            "learned phrase triples (trigrams)" to "getTotalTrigramCount(",
            "personalization vocabulary" to "getStats(",
        )
        val unsurfaced = reads.filterValues { it !in section }.keys.toSortedSet()
        assertWithMessage(
            "Privacy & Data erases these stores but never shows them. Read and display the " +
                "counts so the user can see what is held and confirm that forgetting worked."
        ).that(unsurfaced).isEmpty()
    }

    /**
     * The three preferences that decide what ENTERS the learned stores are pure
     * privacy controls, but they live only in Input Behaviour → Advanced Prediction.
     * Privacy & Data must at minimum name their current state, so the section is a
     * truthful account of what is being learned.
     */
    @Test
    fun thePrivacySectionRepresentsTheLearningSourceToggles() {
        val section = read("ui/settings/sections/PrivacySection.kt")
        val sources = setOf(
            "contextAwarePredictionsEnabled",
            "nextWordPredictionEnabled",
            "personalizedLearningEnabled",
        )
        val absent = sources.filterNot { it in section }.toSortedSet()
        assertWithMessage(
            "Privacy & Data does not represent the learning-source toggles, so the section " +
                "cannot tell the user what is currently being recorded"
        ).that(absent).isEmpty()
    }

    /**
     * The stats block is computed in a keyless `remember { }`, so the collected-swipe
     * count is frozen for the whole composition: deleting every row leaves the old
     * count and the export buttons on screen until the activity is recreated. A
     * refresh key is the minimum honest wiring for a viewer that also owns a Delete
     * button.
     */
    @Test
    fun theCollectedDataViewerRefreshesAfterItsOwnDeleteAction() {
        val section = read("ui/settings/sections/PrivacySection.kt")
        val statsRemember = Regex(
            """remember\s*(\([^)]*\))?\s*\{[^}]*SwipeMLDataStore[\s\S]{0,200}?getStatistics\("""
        ).find(section)
        checkNotNull(statsRemember) {
            "Swipe-stats remember block not found in PrivacySection.kt — re-point this test."
        }
        assertWithMessage(
            "getStatistics() is cached in a keyless remember{}, so the count and the " +
                "export/delete buttons never change after Delete empties the store"
        ).that(statsRemember.groupValues[1]).isNotEmpty()
    }

    // ── Copy honesty: the empty state must not promise what the toggle can't do ──

    @Test
    fun theSectionIntroDescribesARealDataPath() {
        val xml = File("res/values/strings.xml").readText()
        val intro = Regex("""<string name="privacy_section_intro">([^<]*)</string>""")
            .find(xml)?.groupValues?.get(1)
        checkNotNull(intro) { "privacy_section_intro missing — re-point this pin." }
        assertWithMessage(
            "The intro claims stored traces feed 'on-device model fine-tuning'. There is no " +
                "on-device training capability in the app or in onnxruntime-android, so the " +
                "only real path is the manual export. Describe the export, not training."
        ).that(intro.lowercase()).doesNotContain("on-device model fine-tuning")
        assertThat(intro.lowercase()).contains("export")
    }
}
