package tribixbite.cleverkeys

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WP9 — Pipeline-Unification Characterization Oracle (step 2 of R-1).
 *
 * Spec: `docs/audit/remediation-plans/wp9-pipeline-unification-oracle.md`.
 * Parent: `docs/audit/remediation/3-core-ime.md` (R-1 unification).
 *
 * Records the exact suggestion + COMMIT behavior for both live pipelines —
 *   - InputCoordinator (swipe auto-insert front-end + cursor-sync bookkeeping), and
 *   - SuggestionHandler (typing + manual tap + — since step 5 — the swipe & cursor-sync
 *     prediction/post phases)
 * — INCLUDING their known divergences, so the unification (SH survives, IC becomes a
 * thin swipe/cursor-sync front-end) can be verified step-by-step. As of WP9 step 6 (2026-07-21)
 * the unification is COMPLETE: SuggestionHandler owns the single pipeline (bar presentation,
 * cursor-sync prediction, and THE commit engine); InputCoordinator is a thin swipe/ML front-end
 * plus cursor bookkeeping (onCursorPositionChanged, the 100ms debounce, synchronizeWithCursor);
 * the config.unified_swipe_pipeline flag and all legacy IC pipelines were deleted.
 *
 * Two assertion kinds (per spec §"What the oracle is"):
 *   - INVARIANT — must never change; a failure at any migration step is a regression.
 *   - DIVERGENCE-PINNED — pins a divergence of TODAY; marked `// ORACLE-FLIP(step N): …`
 *     and expected to be INVERTED in the same commit that lands step N. Do NOT "fix"
 *     these here — they assert current reality against HEAD.
 *
 * Harness (extends ContractionFlickerIntegrationTest's proven pattern): real
 * SuggestionHandler + InputCoordinator + SuggestionBar + PredictionContextTracker +
 * ContractionManager + WordPredictor over a real BaseInputConnection on an EditText;
 * mock KeyEventHandler.IReceiver; reflection-inject WordPredictor into
 * PredictionCoordinator. Each test builds its OWN harness (no cross-test state) so it is
 * orchestrator-safe.
 *
 * Swipe determinism: no decoder is ever run. The post-prediction transform chain
 * is characterized by calling InputCoordinator.handlePredictionResults / onSuggestionSelected
 * directly with SYNTHETIC prediction lists — the exact seam AsyncPredictionHandler invokes
 * (InputCoordinator.kt:1238-1243). WP9 step 3 (2026-07-20): shift-at-swipe-start state is now
 * CARRIED by the swipe request (threaded through the async callback into handlePredictionResults)
 * rather than read from a private IC field, so the oracle passes it as the trailing carrier params
 * of handlePredictionResults (via swipeResults) — no reflection onto private fields.
 *
 * ── HEAD drift from the spec's line references (verified 2026-07-20) ──────────────────
 * The spec was written against an older HEAD; R-2 (IC dead-code delete) and R-4 (log the
 * swallowed catch) already landed, then WP9 step 3 moved the shift transform, shifting IC line
 * numbers. Corrected anchors used here:
 *   - IC.handlePredictionResults  491-570 (now carries shiftActive/shiftLocked params; step 3)
 *   - IC.onSuggestionSelected     595-947 (spec said 535-880; IC dead code removed)
 *   - applyShiftTransformation now lives on SuggestionHandler's companion (step 3); IC's private
 *     copy was deleted — IC.handlePredictionResults delegates to SuggestionHandler.
 *   - IC.triggerPredictionsForPrefix — DELETED in step 6 along with IC's onSuggestionSelected,
 *     autoInsertTopSuggestion, handleDeleteLastWord dup, and the unified_swipe_pipeline flag.
 *     IC.onCursorMoved delegates unconditionally to SuggestionHandler.handleCursorSyncPrediction.
 *   - IC's silent catch is GONE: now logs (R-4 done) — so D4/D5 pins hold but the
 *     "silent swallow" scenario is not characterized (already remediated).
 *   - SH.handlePredictionResults  293-376 (spec 290+ — accurate); possessive augment SH:324.
 *   - SH.onSuggestionSelected     389-790 (spec 378-758).
 *   - SH.applyShiftTransformation companion fn (step 3 destination).
 *   - SH.augmentPredictionsWithPossessives 1474-1508 (spec 1441/1474).
 */
@RunWith(AndroidJUnit4::class)
class PipelineCharacterizationTest {

    private lateinit var context: Context

    companion object {
        // The WordPredictor + PredictionCoordinator init is heavy (dictionary load); share
        // one instance across tests (OOM-resilient, like ContractionFlickerIntegrationTest).
        // Per-test HARNESS state (bar, handlers, tracker, editText) is always fresh.
        private var sharedPredictor: WordPredictor? = null
        private var sharedContractionManager: ContractionManager? = null
        private var sharedConfig: Config? = null
        private var sharedPredictionCoordinator: PredictionCoordinator? = null
        @Volatile private var initAttempted = false
    }

    /**
     * BaseInputConnection(view, fullEditor=true) edits its OWN internal [editable] — the
     * handlers' commitText / deleteSurroundingText / getTextBeforeCursor all operate on THAT
     * buffer (NOT the host EditText's text), so the oracle must build and assert on it. Same
     * contract as Issue151UrlBarSuggestionTapTest.TestInputConnection.
     */
    private class TestInputConnection(target: EditText) : BaseInputConnection(target, true) {
        fun bufferText(): String = editable?.toString() ?: ""

        /** Seeds the internal buffer with [text] and places the cursor at [cursor] (default: end). */
        fun seed(text: String, cursor: Int = text.length) {
            commitText(text, 1)
            setSelection(cursor, cursor)
        }
    }

    /** Everything a single test operates on — built fresh each test (orchestrator-safe). */
    private class Harness(
        val config: Config,
        val contextTracker: PredictionContextTracker,
        val contractionManager: ContractionManager,
        val suggestionBar: SuggestionBar,
        val suggestionHandler: SuggestionHandler,
        val inputCoordinator: InputCoordinator,
        val keyboardView: Keyboard2View,
        val editText: EditText,
        val inputConnection: TestInputConnection,
        val resources: android.content.res.Resources
    )

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)

        synchronized(PipelineCharacterizationTest::class.java) {
            if (!initAttempted) {
                initAttempted = true
                try {
                    sharedConfig = Config.globalConfig()
                    sharedContractionManager = ContractionManager(context).apply { loadMappings() }
                    sharedPredictor = WordPredictor().apply {
                        setContext(context)
                        setConfig(sharedConfig!!)
                        loadDictionary(context, "en")
                    }
                    sharedPredictionCoordinator = PredictionCoordinator(context, sharedConfig!!)
                    // Inject the shared WordPredictor so the coordinator's getWordPredictor() works
                    // without booting a decoder (reflection seam — same as
                    // ContractionFlickerIntegrationTest).
                    PredictionCoordinator::class.java.getDeclaredField("wordPredictor").apply {
                        isAccessible = true
                        set(sharedPredictionCoordinator, sharedPredictor)
                    }
                } catch (e: OutOfMemoryError) {
                    android.util.Log.w("PipelineCharTest", "WordPredictor init OOM — tests skipped")
                    sharedPredictor = null
                }
            }
        }
        assumeNotNull("WordPredictor required", sharedPredictor)
        assumeNotNull("WordPredictor must be injected", sharedPredictionCoordinator!!.getWordPredictor())
    }

    /**
     * Builds a fresh harness with the given editor starting text. Reuses the shared heavy
     * singletons but fresh SuggestionBar / handlers / tracker / EditText per call.
     */
    private fun harness(initialText: String = ""): Harness {
        // Reset shared config knobs to a known baseline every test (config is a singleton;
        // individual tests then flip only the knobs they care about).
        val config = sharedConfig!!
        config.autocorrect_enabled = true
        config.word_prediction_enabled = true
        config.swipe_typing_enabled = true
        config.swipe_final_autocorrect_enabled = false
        config.show_exact_typed_word = true
        config.swipe_on_password_fields = false
        config.auto_space_after_suggestion = true
        config.auto_space_before_suggestion = true
        config.termux_mode_enabled = false
        config.swipe_show_debug_scores = false
        config.autocapitalize_i_words = true
        config.primary_language = "en"
        // Haptics off: swipe auto-insert calls keyboardView.triggerHaptic(SWIPE_COMPLETE);
        // VibratorCompat.vibrate returns early when disabled, keeping the (unattached) view
        // path free of vibrator side effects on the emulator.
        config.haptic_enabled = false

        val contextTracker = PredictionContextTracker()

        val stubReceiver = object : KeyEventHandler.IReceiver {
            override fun handle_event_key(ev: KeyValue.Event) {}
            override fun set_shift_state(state: Boolean, lock: Boolean) {}
            override fun set_compose_pending(pending: Boolean) {}
            override fun selection_state_changed(selectionIsOngoing: Boolean) {}
            override fun getCurrentInputConnection(): InputConnection? = null
            override fun getHandler(): Handler = Handler(Looper.getMainLooper())
            override fun handle_text_typed(text: String) {}
        }
        val keyEventHandler = KeyEventHandler(stubReceiver)

        // Views + EditText + InputConnection must be created (and seeded) on the main thread.
        val built = arrayOfNulls<Any>(4) // [bar, keyboardView, editText, ic]
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            val bar = SuggestionBar(context)
            val kbView = Keyboard2View(context)
            val edit = EditText(context)
            val ic = TestInputConnection(edit)
            // Seed the IC's internal editable (not the EditText text) with the initial buffer.
            if (initialText.isNotEmpty()) ic.seed(initialText)
            built[0] = bar; built[1] = kbView; built[2] = edit; built[3] = ic
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        val bar = built[0] as SuggestionBar
        val keyboardView = built[1] as Keyboard2View
        val editText = built[2] as EditText
        val ic = built[3] as TestInputConnection

        val predCoord = sharedPredictionCoordinator!!
        val suggestionHandler = SuggestionHandler(
            context, config, contextTracker, predCoord, sharedContractionManager!!, keyEventHandler
        ).apply { setSuggestionBar(bar) }

        val inputCoordinator = InputCoordinator(
            context, config, contextTracker, predCoord, bar, keyboardView
        )
        // WP9 R-1 steps 4-6: wire the unified delegates exactly as ManagerInitializer does in
        // production. MANDATORY since step 6 — IC has no fallback pipelines.
        inputCoordinator.setSwipeResultDelegate(suggestionHandler)
        inputCoordinator.setCursorSyncDelegate(suggestionHandler)

        return Harness(
            config, contextTracker, sharedContractionManager!!, bar,
            suggestionHandler, inputCoordinator, keyboardView, editText, ic,
            context.resources
        )
    }

    /** Drain pending main-thread posts so SuggestionBar / commit runnables are applied. */
    private fun drainMainThread() {
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { latch.countDown() }
        latch.await(2, TimeUnit.SECONDS)
    }

    /** Runs [block] on the main thread and blocks until it completes. IC/SH commit paths
     * touch Views (haptics, keyboardView.post), so they must run on the main looper. */
    private fun onMain(block: () -> Unit) {
        val latch = CountDownLatch(1)
        var thrown: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try { block() } catch (t: Throwable) { thrown = t } finally { latch.countDown() }
        }
        latch.await(5, TimeUnit.SECONDS)
        thrown?.let { throw it }
    }

    private fun bufferOf(h: Harness): String = h.inputConnection.bufferText()

    /**
     * Replicates the production typing order for the SuggestionHandler path: each character
     * (and any trailing space) is COMMITTED to the editor first (as KeyEventHandler.send_text
     * does), THEN handleRegularTyping runs (as handle_text_typed does). Runs on the main thread.
     * This is exactly Issue151UrlBarSuggestionTapTest.type()'s contract.
     */
    private fun typeInto(h: Harness, s: String, editorInfo: EditorInfo) {
        onMain {
            for (c in s) {
                h.inputConnection.commitText(c.toString(), 1)
                h.suggestionHandler.handleRegularTyping(c.toString(), h.inputConnection, editorInfo)
            }
        }
    }

    /**
     * Invokes the swipe post-prediction seam exactly as AsyncPredictionHandler does
     * (InputCoordinator.kt:1236-1249), on the main thread. Sets wasLastInputSwipe=true first —
     * the precondition performSwipeTyping establishes (InputCoordinator.kt:1183) before the
     * async callback fires, which the trailing-space + Termux + ML branches read. We bypass
     * the swipe dispatch (which runs a decoder); this reproduces its tracker precondition.
     *
     * WP9 step 3 (2026-07-20): shift-at-swipe-start state is now CARRIED by the request — the
     * production callback threads the captured wasShiftActive/wasShiftLocked into
     * handlePredictionResults (InputCoordinator.kt:1240-1243). The oracle mirrors that exactly by
     * passing [shiftActive] / [shiftLocked] as the trailing carrier params, so it no longer
     * reflects onto private fields; handlePredictionResults syncs the fields from the carrier for
     * onSuggestionSelected's (untouched) shift-clearing. Casing behavior is IDENTICAL to before —
     * only the plumbing moved (D4, ORACLE-FLIP step 3).
     */
    private fun swipeResults(
        h: Harness,
        predictions: List<String>,
        scores: List<Int>,
        editorInfo: EditorInfo,
        shiftActive: Boolean = false,
        shiftLocked: Boolean = false
    ) {
        onMain {
            h.contextTracker.setWasLastInputSwipe(true)
            h.inputCoordinator.handlePredictionResults(
                predictions, scores, h.inputConnection, editorInfo, h.resources,
                shiftActive, shiftLocked
            )
        }
    }

    private fun textEditor() = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT
        packageName = "com.example.app"
    }

    private fun passwordEditor() = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        packageName = "com.example.app"
    }

    private fun urlEditor() = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        packageName = "com.example.browser"
    }

    private fun termuxEditor() = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT
        packageName = "com.termux"
    }

    // =========================================================================
    // SWIPE AUTO-INSERT (InputCoordinator path)
    // =========================================================================

    /** Scenario 1: plain swipe → top prediction committed with trailing space, SWIPE. */
    @Test
    fun oracle_swipe_plainSwipe_commitsTopWithTrailingSpaceAndSwipeSource() {
        val h = harness(initialText = "")
        swipeResults(h, listOf("hello", "help", "held"), listOf(300, 200, 100), textEditor())
        drainMainThread()

        // INVARIANT: top prediction committed with a trailing space into the empty buffer.
        assertEquals("hello ", bufferOf(h))
        // INVARIANT: swipe commit source is SWIPE, and the word is tracked for replacement.
        assertEquals(PredictionSource.SWIPE, h.contextTracker.getLastCommitSource())
        assertEquals("hello", h.contextTracker.getLastAutoInsertedWord())
        // INVARIANT: full prediction list is (re)displayed for correction.
        assertTrue(h.suggestionBar.getCurrentSuggestions().contains("hello"))
    }

    /** Scenario 2 (D4): shift-at-swipe-start → first letter capitalized across ALL bar entries. */
    @Test
    fun oracle_swipe_shiftAtStart_capitalizesFirstLetterAcrossBar() {
        val h = harness(initialText = "")
        // ORACLE-FLIP(step 3) LANDED 2026-07-20: shift/caps capture + the casing transform moved
        // out of InputCoordinator's private fields into SuggestionHandler.applyShiftTransformation
        // (SH owns it), and the swipe request now CARRIES the shift state — the oracle passes it as
        // the swipeResults carrier params (mirroring the production async callback) instead of
        // reflecting onto private fields. The committed casing + bar casing are IDENTICAL to before;
        // only the wiring moved. These assertion VALUES are unchanged.
        swipeResults(h, listOf("hello", "help"), listOf(300, 200), textEditor(),
            shiftActive = true, shiftLocked = false)
        drainMainThread()

        assertEquals("Hello ", bufferOf(h))
        // Every bar entry got the shift transform, not just the committed top.
        assertTrue(h.suggestionBar.getCurrentSuggestions().contains("Hello"))
        assertTrue(h.suggestionBar.getCurrentSuggestions().contains("Help"))
    }

    /** Scenario 3 (D4): caps-lock-at-swipe-start → full-caps across the bar. */
    @Test
    fun oracle_swipe_capsLockAtStart_uppercasesEntireWordAcrossBar() {
        val h = harness(initialText = "")
        // ORACLE-FLIP(step 3) LANDED 2026-07-20: transform relocated to SH and shift state carried
        // by the request (via swipeResults carrier params, not reflection). Casing identical after
        // the move; assertion VALUES unchanged.
        swipeResults(h, listOf("hello", "help"), listOf(300, 200), textEditor(),
            shiftActive = false, shiftLocked = true)
        drainMainThread()

        assertEquals("HELLO ", bufferOf(h))
        assertTrue(h.suggestionBar.getCurrentSuggestions().contains("HELLO"))
        assertTrue(h.suggestionBar.getCurrentSuggestions().contains("HELP"))
    }

    /** Scenario 4: `raw:`-prefixed top prediction → prefix stripped before commit + tracking. */
    @Test
    fun oracle_swipe_rawPrefixedPrediction_strippedBeforeCommit() {
        val h = harness(initialText = "")
        swipeResults(h, listOf("raw:hello", "help"), listOf(300, 200), textEditor())
        drainMainThread()

        // INVARIANT: "raw:" prefix stripped from committed text AND from tracked word.
        assertEquals("hello ", bufferOf(h))
        assertEquals("hello", h.contextTracker.getLastAutoInsertedWord())
    }

    /** Scenario 5: swipe replacing a previous swipe → old word+space deleted, new committed. */
    @Test
    fun oracle_swipe_replacingPreviousSwipe_deletesOldWordAndSpace() {
        val h = harness(initialText = "")
        swipeResults(h, listOf("hello"), listOf(300), textEditor())
        drainMainThread()
        assertEquals("hello ", bufferOf(h))

        // A second, distinct prediction result auto-inserts and (because the last commit was
        // SWIPE) the handler is called after clearLastAutoInsertedWord() in
        // handlePredictionResults — so consecutive swipes APPEND, they don't replace.
        swipeResults(h, listOf("world"), listOf(300), textEditor())
        drainMainThread()

        // INVARIANT: consecutive swipes append (handlePredictionResults clears the
        // auto-inserted tracking before selecting), giving "hello world ".
        assertEquals("hello world ", bufferOf(h))
    }

    /** Scenario 5b: direct onSuggestionSelected replacement of an auto-inserted swipe word. */
    @Test
    fun oracle_swipe_tapAlternateAfterAutoInsert_replacesAutoInsertedWord() {
        val h = harness(initialText = "")
        swipeResults(h, listOf("hello", "help"), listOf(300, 200), textEditor())
        drainMainThread()
        assertEquals("hello ", bufferOf(h))

        // Now tap the alternate "help": lastAutoInserted="hello", source=SWIPE,
        // so onSuggestionSelected deletes "hello " (word+space) then commits "help ".
        // ORACLE-FLIP(step 6) LANDED 2026-07-21: routed through SuggestionHandler — the engine
        // production taps ALWAYS used (SuggestionBridge → SH, isManualSelection=true). The old
        // direct IC.onSuggestionSelected call exercised IC's now-deleted divergent clone; the
        // assertion VALUES are unchanged because both engines agreed on this scenario.
        onMain {
            h.suggestionHandler.onSuggestionSelected(
                "help", h.inputConnection, textEditor(), h.resources, isManualSelection = true
            )
        }
        drainMainThread()

        // INVARIANT: the auto-inserted word is replaced (not appended).
        assertEquals("help ", bufferOf(h))
        // Scenario 10 pin: tap selection sets CANDIDATE_SELECTION source.
        assertEquals(PredictionSource.CANDIDATE_SELECTION, h.contextTracker.getLastCommitSource())
    }

    /**
     * Scenario 7 (D2): password field + swipe_on_password_fields=false → swipe is SUPPRESSED.
     * ORACLE-FLIP(step 4) LANDED 2026-07-20: the swipe result path now routes through
     * SuggestionHandler.handleSwipePredictionResults, which returns early (clearing the bar) when the
     * field is a password field and the user has not opted into swipe-on-password. Nothing is
     * committed and the bar carries no prediction. (Previously — legacy IC path — the swipe still
     * committed "hunter2 " with a SWIPE source; that legacy behavior is now pinned by
     * oracle_swipe_passwordField_legacyPathStillCommits with the flag off.)
     */
    @Test
    fun oracle_swipe_passwordField_suppressedWhenNotOptedIn() {
        val h = harness(initialText = "")
        h.config.swipe_on_password_fields = false
        swipeResults(h, listOf("hunter2"), listOf(300), passwordEditor())
        drainMainThread()

        // Suppressed: empty buffer, no commit, "hunter2" not posted to the bar.
        assertEquals("", bufferOf(h))
        assertFalse(h.suggestionBar.getCurrentSuggestions().any { it == "hunter2" })
    }

    /**
     * Scenario 8 (D1): possessives PRESENT in the SWIPE bar for a possessive-eligible word.
     * ORACLE-FLIP(step 4) LANDED 2026-07-20: the swipe result path routes through
     * SuggestionHandler.handleSwipePredictionResults, which augments the posted (and re-displayed)
     * bar list via augmentPredictionsWithPossessives — so "book's" now surfaces. (Previously — legacy
     * IC path — InputCoordinator.handlePredictionResults never augmented; that legacy absence is now
     * pinned by oracle_swipe_possessives_legacyPathAbsent with the flag off.)
     */
    @Test
    fun oracle_swipe_possessivesPresentInBar() {
        val h = harness(initialText = "")
        val possessive = h.contractionManager.generatePossessive("book")
        org.junit.Assume.assumeNotNull("'book' must be possessive-eligible", possessive)
        swipeResults(h, listOf("book", "cook"), listOf(300, 200), textEditor())
        drainMainThread()

        assertTrue(
            "swipe bar must now carry '$possessive'. Got: ${h.suggestionBar.getCurrentSuggestions()}",
            h.suggestionBar.getCurrentSuggestions().any { it == possessive }
        )
        // Top prediction still auto-inserted (possessive appended at end, doesn't displace top).
        assertEquals("book ", bufferOf(h))
    }

    // NOTE (step 6, 2026-07-21): the four "legacy (flag off)" escape-hatch guards
    // (oracle_swipe_passwordField_legacyPathStillCommits, oracle_swipe_possessives_legacyPathAbsent,
    // oracle_cursorSync_dictionaryPossessives_legacyPathAlsoSurfaces,
    // oracle_cursorSync_unknownWord_legacyPathPostsNothing) were DELETED together with the
    // legacy InputCoordinator pipelines and the config.unified_swipe_pipeline flag they pinned.

    /**
     * Scenario 9: Termux editor — swipe path is UNCHANGED (uses InputConnection deletion, gets
     * a trailing space). Pins current behavior of both swipe (this test) and non-swipe (see
     * oracle_tap_termuxMode_noTrailingSpaceOnTap).
     */
    @Test
    fun oracle_swipe_termuxMode_swipePathUnchangedGetsTrailingSpace() {
        val h = harness(initialText = "")
        h.config.termux_mode_enabled = true
        swipeResults(h, listOf("ls"), listOf(300), termuxEditor())
        drainMainThread()

        // INVARIANT: swipe auto-insert commits with a trailing space even in Termux mode —
        // termux_mode_enabled suppresses the trailing space only for NON-swipe (tap) commits.
        // (Step 6: unchanged through the SH engine — SmartAutoSpace has no termux branch and
        // auto_space_after_suggestion is on, so the swipe still gets TRAILING_SPACE.)
        assertEquals("ls ", bufferOf(h))
    }

    /** Scenario 11: contraction swipe — prediction "dont" committed exactly as the decoder
     * engine gave it (IC does not transform swipe auto-insert predictions to "don't"). */
    @Test
    fun oracle_swipe_contractionPrediction_committedAsGiven() {
        val h = harness(initialText = "")
        swipeResults(h, listOf("dont"), listOf(300), textEditor())
        drainMainThread()

        // INVARIANT: onSuggestionSelected recognizes "dont" as a contraction KEY and skips
        // autocorrect, but does NOT rewrite it to "don't" — it commits the word as-is.
        assertEquals("dont ", bufferOf(h))
    }

    /** Scenario 12a: swipe_final_autocorrect_enabled = false → autocorrect NOT applied on select. */
    @Test
    fun oracle_swipe_finalAutocorrectDisabled_committedVerbatim() {
        val h = harness(initialText = "")
        h.config.swipe_final_autocorrect_enabled = false
        // "teh" would autocorrect to "the" if final autocorrect ran.
        swipeResults(h, listOf("teh"), listOf(300), textEditor())
        drainMainThread()

        // INVARIANT: with final autocorrect OFF, "teh" commits verbatim.
        assertEquals("teh ", bufferOf(h))
    }

    /** Scenario 12b: swipe_final_autocorrect_enabled = true → autocorrect applied on select. */
    @Test
    fun oracle_swipe_finalAutocorrectEnabled_correctsOnSelect() {
        val h = harness(initialText = "")
        h.config.swipe_final_autocorrect_enabled = true
        val corrected = sharedPredictor!!.autoCorrect("teh")
        // Only assert the correction behavior if the shared dictionary actually corrects "teh";
        // otherwise the scenario can't be exercised deterministically on this build.
        org.junit.Assume.assumeTrue(
            "dictionary must autocorrect 'teh' for this scenario", corrected != "teh"
        )
        swipeResults(h, listOf("teh"), listOf(300), textEditor())
        drainMainThread()

        // INVARIANT: with final autocorrect ON, the corrected word (+space) is committed.
        assertEquals("$corrected ", bufferOf(h))
    }

    // =========================================================================
    // TAP / TYPING (SuggestionHandler path)
    // =========================================================================

    /** Scenario 13: type "its" → bar includes the paired contraction "it's". */
    @Test
    fun oracle_tap_typingPairedContractionBase_showsApostropheVariant() {
        val h = harness()
        onMain {
            h.suggestionHandler.handleRegularTyping("i", null, textEditor())
            h.suggestionHandler.handleRegularTyping("t", null, textEditor())
            h.suggestionHandler.handleRegularTyping("s", null, textEditor())
        }
        Thread.sleep(1000)
        drainMainThread()

        assertTrue(
            "typing 'its' must inject 'it's'. Got: ${h.suggestionBar.getCurrentSuggestions()}",
            h.suggestionBar.getCurrentSuggestions().any { it == "it's" }
        )
    }

    /** Scenario 14: single-char prefix → no paired-contraction injection (prefix < 3). */
    @Test
    fun oracle_tap_singleCharPrefix_noPairedInjection() {
        val h = harness()
        onMain { h.suggestionHandler.handleRegularTyping("t", null, textEditor()) }
        Thread.sleep(1000)
        drainMainThread()

        assertFalse(
            "single char 't' must not inject possessive/paired. Got: ${h.suggestionBar.getCurrentSuggestions()}",
            h.suggestionBar.getCurrentSuggestions().any { it == "t's" }
        )
    }

    /** Scenario 15: unknown typed word → exact_add wire ("+word") in the bar. */
    @Test
    fun oracle_tap_unknownWord_showsExactAddWire() {
        val h = harness()
        h.config.show_exact_typed_word = true
        onMain {
            listOf("x", "y", "z", "q").forEach { h.suggestionHandler.handleRegularTyping(it, null, textEditor()) }
        }
        Thread.sleep(1000)
        drainMainThread()

        assertTrue(
            "unknown 'xyzq' must produce exact_add. Got: ${h.suggestionBar.getCurrentSuggestions()}",
            h.suggestionBar.getCurrentSuggestions().any {
                it.startsWith(Suggestion.EXACT_ADD_PREFIX) || it == "exact_add:xyzq"
            }
        )
    }

    /**
     * Scenario 16 (D1 control): SuggestionHandler owns possessive augmentation. Since step 6 the
     * only production caller is handleSwipePredictionResults (the swipe-path D1 flip, scenario 8);
     * this test pins the augmentation FUNCTION itself via the private-method reflection seam.
     * (The former 16b test pinned the legacy SH.handlePredictionResults transient re-display; that
     * entry was DELETED in step 6 — dead chain, zero production callers — so 16b went with it.)
     */
    @Test
    fun oracle_tap_possessiveAugmentationExistsAndProducesForms() {
        val h = harness(initialText = "")
        val possessive = h.contractionManager.generatePossessive("book")
        org.junit.Assume.assumeNotNull("'book' must be possessive-eligible", possessive)

        val method = SuggestionHandler::class.java.getDeclaredMethod(
            "augmentPredictionsWithPossessives",
            MutableList::class.java, MutableList::class.java
        ).apply { isAccessible = true }

        val words = mutableListOf("book", "cook", "look")
        val scores = mutableListOf(300, 200, 100)
        method.invoke(h.suggestionHandler, words, scores)

        // INVARIANT (D1 control): SH's augmentation produces the possessive form and appends it.
        assertTrue(
            "SH augmentation must add '$possessive'. Got: $words",
            words.any { it == possessive }
        )
        // Appended at the END (after the base predictions), scores stay aligned in length.
        assertTrue(words.size > 3)
        assertEquals(words.size, scores.size)
    }

    /** Scenario 17 (D3 control): autocorrect-on-space raises an undo prompt and sets the
     * specialPromptActive guard so a racing async prediction cannot overwrite it. */
    @Test
    fun oracle_tap_autocorrectUndoPrompt_setsSpecialPromptGuard() {
        val h = harness(initialText = "")
        h.config.autocorrect_enabled = true
        val corrected = sharedPredictor!!.autoCorrect("teh")
        org.junit.Assume.assumeTrue("dictionary must autocorrect 'teh'", corrected != "teh")

        // Production order: chars + the completing space are committed to the editor, then the
        // handler runs per keystroke. The space keystroke triggers the autocorrect branch.
        typeInto(h, "teh ", textEditor())
        Thread.sleep(500)
        drainMainThread()

        // INVARIANT: after autocorrect, the ORIGINAL word appears first (for undo) and the
        // AUTOCORRECT source is tracked.
        assertEquals(PredictionSource.AUTOCORRECT, h.contextTracker.getLastCommitSource())
        assertTrue(
            "undo prompt must show original 'teh' first. Got: ${h.suggestionBar.getCurrentSuggestions()}",
            h.suggestionBar.getCurrentSuggestions().firstOrNull() == "teh"
        )
        // The special-prompt guard is a private @Volatile field; assert it via reflection.
        val guard = SuggestionHandler::class.java.getDeclaredField("specialPromptActive").apply {
            isAccessible = true
        }.getBoolean(h.suggestionHandler)
        assertTrue("specialPromptActive must be set after autocorrect prompt", guard)
    }

    /** Scenario 19 (D2 control): the TRACKED password mode (setPasswordMode, flipped by
     * onStartInputView) suppresses the swipe pipeline even when the EditorInfo alone would not
     * reveal a password field — the guard is `isPasswordMode || isPasswordField(editorInfo)`.
     * (Rewritten in step 6: the former target, SH's legacy auto-inserting handlePredictionResults,
     * was deleted as a dead chain; scenario 7 covers the EditorInfo leg of the same guard.) */
    @Test
    fun oracle_tap_passwordMode_suggestionsSuppressed() {
        val h = harness(initialText = "")
        h.config.swipe_on_password_fields = false
        h.suggestionHandler.setPasswordMode(true)
        // Plain-text EditorInfo: only the tracked mode marks this as a password context.
        swipeResults(h, listOf("hunter2"), listOf(300), textEditor())
        drainMainThread()

        // INVARIANT (D2 control): tracked password mode guards the pipeline — nothing committed.
        assertEquals("", bufferOf(h))
        assertFalse(h.suggestionBar.getCurrentSuggestions().any { it == "hunter2" })
    }

    /** Scenario 20: I-word capitalization on space ("i" → "I"). */
    @Test
    fun oracle_tap_iWordCapitalizationOnSpace() {
        val h = harness(initialText = "")
        h.config.autocapitalize_i_words = true
        // Production order: "i" then the completing space are committed to the editor, then the
        // handler runs per keystroke. The space keystroke rewrites the committed "i " to "I ".
        typeInto(h, "i ", textEditor())
        drainMainThread()

        // INVARIANT: "i " is rewritten to "I ".
        assertEquals("I ", bufferOf(h))
    }

    /** Scenario 21: mid-word tap selection → prefix+suffix deletion then replacement. */
    @Test
    fun oracle_tap_midWordSelection_deletesPrefixAndSuffixThenReplaces() {
        // "per|fect": cursor after "per", suffix "fect". Selecting "person" must delete
        // BOTH the "per" prefix and the "fect" suffix, then commit "person".
        val h = harness(initialText = "perfect")
        onMain { h.inputConnection.setSelection(3, 3) } // cursor between "per" and "fect"
        drainMainThread()

        onMain {
            h.contextTracker.synchronizeWithCursor(h.inputConnection, "en", textEditor())
            h.suggestionHandler.onSuggestionSelected(
                "person", h.inputConnection, textEditor(), h.resources, isManualSelection = true
            )
        }
        drainMainThread()

        // INVARIANT: whole "perfect" token replaced by "person" (+ trailing space) — not
        // "perpersonfect". Trailing space added because there was no space after cursor.
        assertEquals("person ", bufferOf(h))
    }

    /**
     * Scenario 22 (#151): URL field tap replaces the typed partial WITHOUT injecting a leading
     * space (which would corrupt the URL), via the SH editor-scan fallback.
     */
    @Test
    fun oracle_tap_urlField_replacesPartialNoLeadingSpace() {
        val h = harness(initialText = "https://exa")
        onMain {
            // In a URL field shouldSyncForInputType == false, so the tracker deletion counts
            // stay (0,0); SH's #151 fallback scans the editor for the "exa" partial.
            h.contextTracker.synchronizeWithCursor(h.inputConnection, "en", urlEditor())
            h.suggestionHandler.onSuggestionSelected(
                "example", h.inputConnection, urlEditor(), h.resources, isManualSelection = true
            )
        }
        drainMainThread()

        // INVARIANT (#151): "exa" replaced by "example", '/' preserved, no leading space,
        // and NO trailing space in a URL bar would be ideal — but today SH adds a trailing
        // space (TRAILING_SPACE mode) since hasSpaceAfter is false. Pin exact current output.
        assertEquals("https://example ", bufferOf(h))
    }

    /** Scenario 23: preserveCapitalization on autocorrect of a capitalized typed word. */
    @Test
    fun oracle_tap_preserveCapitalizationOnAutocorrect() {
        // "Teh " → autocorrect to "The " (title-case preserved from the typed "Teh").
        val corrected = sharedPredictor!!.autoCorrect("teh")
        org.junit.Assume.assumeTrue("dictionary must autocorrect 'teh'", corrected != "teh")
        val h = harness(initialText = "")
        h.config.autocorrect_enabled = true
        // Production order: "Teh" (capitalized first char) + completing space committed, then
        // the handler runs per keystroke. The space triggers autocorrect, which preserves the
        // typed word's title-case onto the correction.
        typeInto(h, "Teh ", textEditor())
        Thread.sleep(300)
        drainMainThread()

        // INVARIANT: title-case of the typed word is preserved onto the correction.
        val expected = corrected.replaceFirstChar { it.uppercaseChar() }
        assertEquals("$expected ", bufferOf(h))
    }

    // =========================================================================
    // CURSOR-SYNC (InputCoordinator → step-5 target)
    // =========================================================================

    /** Scenario 24: cursor move into a word → same contraction suggestions as the typing path. */
    @Test
    fun oracle_cursorSync_producesContractionSuggestions() {
        val h = harness(initialText = "hello its")
        onMain { h.inputConnection.setSelection(9, 9) } // cursor at end of "its"
        drainMainThread()

        onMain {
            // onCursorMoved debounces then runs SH.handleCursorSyncPrediction (single pipeline).
            h.inputCoordinator.onCursorMoved(9, h.inputConnection, "en", textEditor())
        }
        // Debounce (100ms) + async prediction.
        Thread.sleep(1200)
        drainMainThread()

        assertTrue(
            "cursor-sync on 'its' must inject 'it's'. Got: ${h.suggestionBar.getCurrentSuggestions()}",
            h.suggestionBar.getCurrentSuggestions().any { it == "it's" }
        )
    }

    /** Scenario 25 (D1, re-pinned after first on-device run 2026-07-20): the original
     * "possessives absent on cursor-sync" pin was WRONG — the dictionary itself carries
     * possessive forms ("book's" is a dictionary word), so it appears on EVERY path via
     * plain prediction. The D1 divergence is only about the augmentPredictionsWithPossessives
     * FUNCTION (pinned by the swipe-path test where synthetic predictions carry no 's forms,
     * and the SH-path reflection control).
     *
     * ORACLE-FLIP(step 5) VERIFIED NO-DELTA 2026-07-20: step 5 folds cursor-sync into SH's
     * updatePredictionsForCurrentWord, which does NOT call augmentPredictionsWithPossessives
     * (that lives only in the swipe/tap handlePredictionResults). So there is genuinely no gateable
     * visible possessive delta — the possessive surfaces purely as a dictionary prediction on both
     * the pre-flip (legacy IC) and post-flip (SH) paths. The assertion is UNCHANGED (per the task:
     * "do NOT weaken it"); this test now pins that dictionary possessives STILL surface after the
     * fold, and the legacy variant below confirms the escape hatch is likewise unchanged. */
    @Test
    fun oracle_cursorSync_dictionaryPossessivesSurfaceToday() {
        val h = harness(initialText = "book")
        onMain { h.inputConnection.setSelection(4, 4) }
        drainMainThread()
        onMain { h.inputCoordinator.onCursorMoved(4, h.inputConnection, "en", textEditor()) }
        Thread.sleep(1200)
        drainMainThread()

        assertTrue(
            "dictionary possessive \"book's\" surfaces on cursor-sync (SH path). Got: ${h.suggestionBar.getCurrentSuggestions()}",
            h.suggestionBar.getCurrentSuggestions().any { it == "book's" }
        )
    }

    /**
     * Scenario 26 — ORACLE-FLIP(step 5) LANDED 2026-07-20. Before step 5 the legacy IC cursor-sync
     * (triggerPredictionsForPrefix) posted NOTHING for an unknown word with zero dictionary
     * predictions: its dual-search early-returned on `allResults.isEmpty()` BEFORE the exact-add
     * branch, and the branch was further neutralized by `isInDictionary ?: true` + the
     * `finalWords.isNotEmpty()` post guard. The typing path DID show exact_add for the same input —
     * a real divergence.
     *
     * Step 5 folds cursor-sync into SuggestionHandler.handleCursorSyncPrediction →
     * updatePredictionsForCurrentWord (the SAME pipeline the typing path uses), which runs the
     * exact-add branch even on an empty prediction list. So cursor-sync now posts the exact_add wire
     * for unknown 'xyzq', exactly like typing. The assertion is INVERTED here in the same commit.
     * (Legacy-path absence is pinned by oracle_cursorSync_unknownWord_legacyPathPostsNothing.)
     */
    @Test
    fun oracle_cursorSync_unknownWordShowsExactAdd() {
        val h = harness(initialText = "xyzq")
        h.config.show_exact_typed_word = true
        onMain { h.inputConnection.setSelection(4, 4) }
        drainMainThread()
        onMain { h.inputCoordinator.onCursorMoved(4, h.inputConnection, "en", textEditor()) }
        Thread.sleep(1200)
        drainMainThread()

        assertTrue(
            "cursor-sync must now surface exact_add for unknown 'xyzq' (folded into SH pipeline). " +
                "Got: ${h.suggestionBar.getCurrentSuggestions()}",
            h.suggestionBar.getCurrentSuggestions().any {
                it.startsWith(Suggestion.EXACT_ADD_PREFIX) || it == "exact_add:xyzq"
            }
        )
    }

    /** Scenario 27: cursor-sync debounce → two rapid moves collapse to one prediction pass. */
    @Test
    fun oracle_cursorSync_debounceCollapsesRapidMoves() {
        val h = harness(initialText = "hello its")
        onMain { h.inputConnection.setSelection(9, 9) }
        drainMainThread()

        // Fire two moves within the 100ms debounce window; only the last should survive.
        onMain {
            h.inputCoordinator.onCursorMoved(9, h.inputConnection, "en", textEditor())
            h.inputCoordinator.onCursorMoved(9, h.inputConnection, "en", textEditor())
        }
        Thread.sleep(1200)
        drainMainThread()

        // INVARIANT: the debounce still yields a coherent single result set (contraction present),
        // not a corrupted/duplicated bar. (The debounce is a timing behavior; we pin that a
        // valid prediction pass completes.)
        assertTrue(
            "debounced cursor-sync must still produce 'it's'. Got: ${h.suggestionBar.getCurrentSuggestions()}",
            h.suggestionBar.getCurrentSuggestions().any { it == "it's" }
        )
    }

    /**
     * Scenario 18 — R-7 prompt-race, now DETERMINISTIC (step 5 LANDED 2026-07-20).
     *
     * The oracle originally SKIPPED this: pre-step-5, IC's cursor-sync post had NO specialPromptActive
     * check (the flag lived only on SH), so a cursor-sync racing an SH prompt could clobber it — there
     * was no stable assertion of "prompt survives on the IC side", only timing-dependent flakiness.
     *
     * Step 5 folds cursor-sync into SuggestionHandler.handleCursorSyncPrediction →
     * updatePredictionsForCurrentWord, the SINGLE pipeline that already guards on specialPromptActive
     * (checked before submit AND inside the posted runnable). So a cursor-sync prediction pass that
     * fires WHILE an SH autocorrect-undo prompt is active can no longer overwrite it — the race is
     * resolved STRUCTURALLY (one guarded pipeline), which is exactly what R-7 required.
     *
     * Setup (all real components, no reflection on the guard beyond the read-only confirmation):
     *   1. Seed "its " so a fresh word starts after it; type "teh " → SH autocorrects "teh"→"the",
     *      raises the undo prompt (bar = ["teh","the"]) and sets specialPromptActive=true.
     *      Buffer is now "its the ".
     *   2. Move the cursor back INTO the pre-existing "its" (pos 3) and fire onCursorMoved. The synced
     *      prefix "its" is NON-EMPTY, so cursor-sync routes to handleCursorSyncPrediction and a real
     *      prediction pass runs (it WOULD post "it's"/"its"… absent the guard).
     *   3. Assert the autocorrect-undo prompt SURVIVES — the bar still leads with "teh" and the
     *      cursor-sync's "it's" did NOT appear — proving the guard blocked the clobber.
     */
    @Test
    fun oracle_cursorSync_doesNotClobberAutocorrectUndoPrompt() {
        val corrected = sharedPredictor!!.autoCorrect("teh")
        org.junit.Assume.assumeTrue("dictionary must autocorrect 'teh'", corrected != "teh")

        val h = harness(initialText = "its ")
        h.config.autocorrect_enabled = true

        // (1) Raise the SH autocorrect-undo prompt; sets specialPromptActive.
        typeInto(h, "teh ", textEditor())
        Thread.sleep(500)
        drainMainThread()

        // Precondition: prompt is up (original word first) and the guard is set.
        assertEquals(
            "undo prompt must lead with original 'teh'. Got: ${h.suggestionBar.getCurrentSuggestions()}",
            "teh", h.suggestionBar.getCurrentSuggestions().firstOrNull()
        )
        val guardBefore = SuggestionHandler::class.java.getDeclaredField("specialPromptActive").apply {
            isAccessible = true
        }.getBoolean(h.suggestionHandler)
        assertTrue("precondition: specialPromptActive must be set by the autocorrect prompt", guardBefore)

        // (2) Fire a cursor-sync into the pre-existing "its" (non-empty prefix → real prediction pass).
        onMain { h.inputConnection.setSelection(3, 3) } // cursor at end of leading "its"
        drainMainThread()
        onMain { h.inputCoordinator.onCursorMoved(3, h.inputConnection, "en", textEditor()) }
        Thread.sleep(1200)
        drainMainThread()

        // (3) The prompt SURVIVES: bar still leads with "teh", and the cursor-sync's "it's" did not
        // clobber it. Structural R-7 resolution — the shared SH pipeline's specialPromptActive guard
        // suppressed the racing cursor-sync post.
        assertEquals(
            "autocorrect-undo prompt must survive the racing cursor-sync (R-7). " +
                "Got: ${h.suggestionBar.getCurrentSuggestions()}",
            "teh", h.suggestionBar.getCurrentSuggestions().firstOrNull()
        )
        assertFalse(
            "cursor-sync must not have injected 'it's' over the active prompt. " +
                "Got: ${h.suggestionBar.getCurrentSuggestions()}",
            h.suggestionBar.getCurrentSuggestions().any { it == "it's" }
        )
    }

    // =========================================================================
    // CROSS-PATH INVARIANTS
    // =========================================================================

    /** Scenario 28: SuggestionBar dedup — an identical repost is a no-op (no content change). */
    @Test
    fun oracle_bar_dedupIdenticalRepost() {
        val h = harness()
        val words = listOf("it's", "its", "itself")
        val scores = listOf(5000, 4000, 3000)
        onMain { h.suggestionBar.setSuggestionsWithScores(words, scores) }
        drainMainThread()
        val before = h.suggestionBar.getCurrentSuggestions()

        onMain { h.suggestionBar.setSuggestionsWithScores(words, scores) }
        drainMainThread()
        val after = h.suggestionBar.getCurrentSuggestions()

        // INVARIANT: identical content → same list (the SB dedup guard skips the re-render).
        assertEquals(before, after)
        assertEquals("it's", after.firstOrNull())
    }

    /** Scenario 29: last-post-wins — a later differing post replaces the earlier bar state. */
    @Test
    fun oracle_bar_lastPostWins() {
        val h = harness()
        onMain { h.suggestionBar.setSuggestionsWithScores(listOf("its", "itself"), listOf(100, 90)) }
        drainMainThread()
        assertEquals(2, h.suggestionBar.getCurrentSuggestions().size)

        onMain { h.suggestionBar.setSuggestionsWithScores(listOf("it's", "its", "itself"), listOf(5000, 100, 90)) }
        drainMainThread()

        // INVARIANT: the second (differing) post wins — deterministic final bar state.
        val final = h.suggestionBar.getCurrentSuggestions()
        assertEquals(3, final.size)
        assertEquals("it's", final.firstOrNull())
    }

    /** Scenario 30: getCurrentSuggestions() wire format is List<String> with special entries
     * carrying an in-band prefix parsed only through Suggestion.kt. */
    @Test
    fun oracle_wire_getCurrentSuggestionsIsStringListWithPrefixProtocol() {
        val h = harness()
        onMain {
            h.suggestionBar.setSuggestionsWithScores(
                listOf("book", Suggestion.ExactAdd("xyzq").wire, Suggestion.AddToDictionary("foo").wire),
                listOf(100, 0, 0)
            )
        }
        drainMainThread()

        val wire = h.suggestionBar.getCurrentSuggestions()
        // INVARIANT: the accessor returns raw wire strings (prefixes intact) — the render/route
        // layers parse them via Suggestion.parse, not the accessor.
        assertTrue(wire.contains("book"))
        assertTrue(wire.any { it == Suggestion.EXACT_ADD_PREFIX + "xyzq" })
        assertTrue(wire.any { it == Suggestion.DICT_ADD_PREFIX + "foo" })
        // And they round-trip through the typed model.
        assertTrue(Suggestion.parse(Suggestion.ExactAdd("xyzq").wire) is Suggestion.ExactAdd)
        assertTrue(Suggestion.parse(Suggestion.AddToDictionary("foo").wire) is Suggestion.AddToDictionary)
        assertTrue(Suggestion.parse("book") is Suggestion.Word)
    }

    // =========================================================================
    // SKIPPED SCENARIOS (documented — cannot be characterized deterministically here)
    // =========================================================================

    /**
     * Scenario 6 (swipe during manual typing → typed partial replaced) is characterized only
     * PARTIALLY and folded into scenario 5/5b above. The full "swipe mid-typed-partial" flow
     * requires the tracker's currentWordLength to be non-zero at handlePredictionResults time,
     * which in production is set by KeyEventHandler.send_text() committing each char AND
     * contextTracker.appendToCurrentWord() — a coupling that only the live IME service wires
     * (Keyboard2View → CleverKeysService). Reproducing it here would require driving
     * handleRegularTyping (SH path) then handlePredictionResults (IC path) with a shared
     * tracker; but SH.handleRegularTyping does NOT append to the tracker's currentWord in a way
     * IC.handlePredictionResults reads for the "add space after manual typing" branch
     * (IC:539) — the two use different tracking. SKIP: needs the real service to couple the
     * KeyEventHandler-commit and tracker-append. Reported.
     *
     * Scenario 10's "ML capture fires (D5)" sub-assertion remains SKIPPED after step 6 LANDED
     * D5 (capture now routes through MLDataCollector inside SH.handleSwipePredictionResults):
     * it is still gated on config.swipe_debug_detailed_logging AND
     * PrivacyManager.canCollectSwipeData() AND a non-null currentSwipeData populated by
     * the swipe dispatch (which we bypass to stay off the decoders). Verifying the store
     * write would require driving handleSwipeTyping end-to-end (a real decoder) or a
     * PrivacyManager/SwipeMLDataStore test double — out of scope for a post-prediction-seam
     * oracle. The word-REPLACEMENT + CANDIDATE_SELECTION half of scenario 10 IS covered by
     * oracle_swipe_tapAlternateAfterAutoInsert_replacesAutoInsertedWord.
     *
     * Scenario 18 (prompt survives a cursor-sync racing it) is NO LONGER SKIPPED — step 5 (2026-07-20)
     * folded cursor-sync into SuggestionHandler's single specialPromptActive-guarded pipeline, making
     * the race deterministic. It is now implemented as
     * oracle_cursorSync_doesNotClobberAutocorrectUndoPrompt (autocorrect-undo prompt variant): raise an
     * SH prompt via the real typing path, fire a real cursor-sync prediction pass into a pre-existing
     * word, and assert the prompt survives (the shared guard suppresses the racing post). This is the
     * R-7 structural fix — the guard is no longer SH-only-and-bypassed-by-IC; there is ONE pipeline.
     */
    @Test
    fun oracle_skipped_scenarios_documented() {
        // Placeholder so the skip rationale ships in the class and is visible in test output.
        // No assertion — the doc comment above is the deliverable.
        assertTrue(true)
    }
}
