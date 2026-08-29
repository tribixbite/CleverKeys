package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.SwipeRewarmScheduler
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge
import tribixbite.cleverkeys.swipe.geometric.ArrayBackedDictionary
import tribixbite.cleverkeys.swipe.geometric.GeometricUserWordMerge
import java.io.File

/**
 * ARC-081 / ARC-082 — the platform user dictionary must reach the SWIPE engines, and a
 * dictionary mutation must re-warm rather than stall the next swipe.
 *
 * Two coupled findings from `docs/audit/2026-08-28-archive-verification.md`:
 *
 *  - **ARC-081**: `WordPredictor.loadCustomAndUserWords` merges the Android
 *    `UserDictionary.Words` provider with the `custom_words_<lang>` preference, so a word
 *    added to the SYSTEM user dictionary completes on tap. Both swipe adapters read only
 *    the preference, so that same word can never be swiped on either engine — while their
 *    KDocs read as if user words were covered.
 *  - **ARC-082**: a custom/disabled-words write invalidates the trie memo, and the NEXT
 *    swipe pays the full trie build synchronously on the decode thread. The mutation must
 *    trigger the same background prewarm a language switch does (ARC-014).
 *
 * The wiring assertions are source scans because both adapters are Android classes (Context,
 * Handler, Looper) that cannot be constructed in `runPureTests`; the BEHAVIOUR they wire up
 * is exercised directly against the pure seam in the second half of this class.
 */
class UserDictionaryReachesSwipeTest {

    private val mainKotlin = File("src/main/kotlin")

    private fun source(relative: String): String {
        val f = File(mainKotlin, relative)
        assertWithMessage("expected source file to exist: ${f.path} (run from project root)")
            .that(f.isFile).isTrue()
        return f.readText()
    }

    // ── ARC-081: the provider snapshot reaches BOTH engines ─────────────────────────

    @Test
    fun ctcAdapterFeedsTheProviderSnapshotIntoTheLexiconMerge() {
        val adapter = source("tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt")

        assertWithMessage(
            "ARC-081: CtcEngineAdapter must read the platform UserDictionary snapshot for the " +
                "decode language — without it a word added to the system user dictionary " +
                "completes on tap and can never be swiped."
        ).that(adapter).contains("UserDictionarySnapshot")

        assertWithMessage(
            "the snapshot must be MERGED with the custom_words_<lang> preference through the " +
                "shared pure seam (UserDictionarySnapshot.mergeWithCustom), not through a " +
                "second hand-rolled combination."
        ).that(adapter).contains("UserDictionarySnapshot.mergeWithCustom(")

        assertWithMessage(
            "the merged user-word list — not the raw custom-pref list — must be what reaches " +
                "CtcLexiconMerge.merge, or the provider words are read and then dropped."
        ).that(adapter).contains("CtcLexiconMerge.merge(basePairs, userWordPairs, disabled)")
    }

    @Test
    fun geometricAdapterFeedsTheProviderSnapshotIntoItsDictionaryMerge() {
        val adapter = source("tribixbite/cleverkeys/swipe/GeometricEngineAdapter.kt")

        assertWithMessage(
            "ARC-081: GeometricEngineAdapter must read the platform UserDictionary snapshot " +
                "too — the two engines must not offer different personal vocabularies."
        ).that(adapter).contains("UserDictionarySnapshot")

        assertWithMessage(
            "the snapshot must be merged with the custom_words_<lang> preference through the " +
                "SAME pure seam the CTC adapter uses."
        ).that(adapter).contains("UserDictionarySnapshot.mergeWithCustom(")
    }

    @Test
    fun bothAdaptersFingerprintTheProviderSnapshotIntoTheMemoKey() {
        val ctc = source("tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt")
        val geo = source("tribixbite/cleverkeys/swipe/GeometricEngineAdapter.kt")

        for ((name, text) in listOf("CtcEngineAdapter" to ctc, "GeometricEngineAdapter" to geo)) {
            assertWithMessage(
                "ARC-081: $name must fold the provider snapshot's fingerprint into the lexicon " +
                    "memo key via the shared LexiconContentVersion. Without it the feature works " +
                    "only until the first cache hit: a user-dictionary edit leaves the memoized " +
                    "trie in place and the new word stays unswipeable."
            ).that(text).contains("LexiconContentVersion.of(")
            assertWithMessage(
                "$name must pass the SNAPSHOT FINGERPRINT (not a constant) as the " +
                    "user-dictionary component of the memo key."
            ).that(text).contains("userDictionary.fingerprint")
        }

        assertWithMessage(
            "the two adapters must share ONE content-version implementation — they had " +
                "byte-identical private copies, which is how one of them could silently be " +
                "left without the provider input."
        ).that(ctc + geo).doesNotContain("MessageDigest.getInstance")
    }

    // ── ARC-082: a dictionary mutation re-warms in the background ───────────────────

    @Test
    fun aCustomOrDisabledWordChangeRequestsASwipeRewarm() {
        val handler = source("tribixbite/cleverkeys/PreferenceUIUpdateHandler.kt")

        assertWithMessage(
            "ARC-082: writing custom_words_<lang> / disabled_words_<lang> invalidates the " +
                "swipe lexicon memo, so without a re-warm the NEXT swipe rebuilds the trie " +
                "synchronously on the decode thread. Hook the pref-change seam, like ARC-014 " +
                "does for a language switch."
        ).that(handler).contains("SwipeRewarmScheduler.requestRewarm()")

        val guard = handler.substringAfter("// ARC-082").substringBefore("} catch (")
        for (token in listOf("languageFromCustomWordsKey", "languageFromDisabledWordsKey")) {
            assertWithMessage(
                "the ARC-082 re-warm guard must cover '$token' — both preference families " +
                    "change what the next swipe decodes against."
            ).that(guard).contains(token)
        }
    }

    @Test
    fun aPlatformUserDictionaryChangeRequestsASwipeRewarm() {
        val predictor = source("tribixbite/cleverkeys/WordPredictor.kt")

        assertWithMessage(
            "ARC-082 + ARC-081: the platform user dictionary is observed by " +
                "UserDictionaryObserver, whose only listener lives here. A provider change now " +
                "invalidates the swipe lexicon fingerprint too, so it must ALSO schedule the " +
                "background re-warm — otherwise the first swipe after adding a system word " +
                "pays the whole trie build."
        ).that(predictor).contains("SwipeRewarmScheduler.requestRewarm()")
    }

    @Test
    fun theRewarmSchedulerCoalescesRapidMutations() {
        val scheduler = source("tribixbite/cleverkeys/SwipeRewarmScheduler.kt")

        assertWithMessage(
            "ARC-082 debounce sanity: adding five words in quick succession writes the " +
                "preference five times. The scheduler must drop the pending request before " +
                "posting a new one (latest-wins), mirroring GeometricSettingsActivity's " +
                "slider-tick coalescing."
        ).that(scheduler).contains("cancelPending()")

        assertWithMessage(
            "the scheduler must delegate to the ONE existing entry point " +
                "(CleverKeysService.requestGeometricRewarm), which warms the SERVING engine in " +
                "the adapter's BACKGROUND task slot. A direct warmUpAsync call would bypass both."
        ).that(scheduler).contains("CleverKeysService.requestGeometricRewarm()")
    }

    // ══════════════════ BEHAVIOUR: the pure seam the adapters wire up ══════════════════

    /** A provider row the app's own `custom_words_<lang>` preference does not know about. */
    private val providerOnly = UserDictionarySnapshot.of(listOf("kubernetes" to 40))

    @Test
    fun aProviderOnlyWordReachesTheCtcMergedLexiconAtItsObservedFrequency() {
        val base = listOf("the" to 255.0, "keyboard" to 200.0)
        val custom = listOf("tribixbite" to 1000)

        val withoutProvider = CtcLexiconMerge.merge(base, custom, emptySet())
        assertWithMessage("baseline: the pref alone cannot know about a system-dictionary word")
            .that(withoutProvider).doesNotContainKey("kubernetes")

        val userWords = UserDictionarySnapshot.mergeWithCustom(custom, providerOnly)
        val merged = CtcLexiconMerge.merge(base, userWords, emptySet())

        assertWithMessage(
            "ARC-081: a word present only in the platform user dictionary must reach the CTC " +
                "trie input, or it completes on tap and can never be swiped."
        ).that(merged).containsKey("kubernetes")
        assertWithMessage(
            "the provider's OBSERVED frequency must survive onto the lexicon's 1..255 scale — " +
                "blanket-clamping every provider row to 255 would throw away the only ranking " +
                "signal the provider offers."
        ).that(merged["kubernetes"]).isEqualTo(40.0)
        assertWithMessage(
            "a pref custom word keeps the treatment it already had: 1000 saturates at the " +
                "scale's top, which is what CtcLexiconMerge has always done."
        ).that(merged["tribixbite"]).isEqualTo(255.0)
    }

    @Test
    fun aProviderOnlyWordReachesTheGeometricMergedDictionary() {
        val base = ArrayBackedDictionary("en", 1L, arrayOf("the", "keyboard"))
        val custom = listOf("tribixbite" to 1000)

        val withoutProvider =
            GeometricUserWordMerge.merge(base, custom, emptySet(), "en", 1L)
        val baselineWords = (0 until withoutProvider.size).map { withoutProvider.word(it) }
        assertWithMessage("baseline: the pref alone cannot know about a system-dictionary word")
            .that(baselineWords).doesNotContain("kubernetes")

        val userWords = UserDictionarySnapshot.mergeWithCustom(custom, providerOnly)
        val merged = GeometricUserWordMerge.merge(base, userWords, emptySet(), "en", 2L)
        val words = (0 until merged.size).map { merged.word(it) }

        assertWithMessage(
            "ARC-081: the geometric engine must see the same personal vocabulary the CTC " +
                "engine does — one user dictionary, not one per engine."
        ).that(words).contains("kubernetes")
        assertWithMessage(
            "user words are PREPENDED in (frequency desc, word asc) order, so the pref word at " +
                "1000 outranks the provider row at 40 and both outrank the base dictionary."
        ).that(words).containsExactly("tribixbite", "kubernetes", "the", "keyboard").inOrder()
    }

    @Test
    fun thePreferenceWinsWhenBothStoresDefineTheSameWord() {
        val provider = UserDictionarySnapshot.of(listOf("Kubernetes" to 12))
        val userWords =
            UserDictionarySnapshot.mergeWithCustom(listOf("kubernetes" to 200), provider)

        assertWithMessage(
            "the app-managed preference is the store the user can edit from inside CleverKeys " +
                "(and the one backup/restore round-trips), so it must win a collision — " +
                "case-insensitively, or a case variant would sneak the provider row in beside it."
        ).that(userWords).containsExactly("kubernetes" to 200)
    }

    @Test
    fun aProviderChangeChangesTheLexiconMemoVersion() {
        val before = UserDictionarySnapshot.of(listOf("kubernetes" to 40))
        val added = UserDictionarySnapshot.of(listOf("kubernetes" to 40, "istio" to 40))
        val reranked = UserDictionarySnapshot.of(listOf("kubernetes" to 250))

        fun version(snapshot: UserDictionarySnapshot) = LexiconContentVersion.of(
            "asset:dictionaries/en_enhanced.bin", "{}", emptySet(), snapshot.fingerprint
        )

        assertWithMessage(
            "ARC-081: without the provider snapshot in the memo key the feature works only " +
                "until the first cache hit — the memoized trie survives the edit and the new " +
                "word stays unswipeable for the rest of the session."
        ).that(version(added)).isNotEqualTo(version(before))
        assertWithMessage(
            "a FREQUENCY change is a content change too: it moves the word's rank in the " +
                "merged lexicon, which is what the geometric prior reads."
        ).that(version(reranked)).isNotEqualTo(version(before))
        assertWithMessage("an unchanged snapshot must NOT churn the memo")
            .that(version(UserDictionarySnapshot.of(listOf("kubernetes" to 40))))
            .isEqualTo(version(before))
        assertWithMessage("an empty provider must be its own stable state")
            .that(UserDictionarySnapshot.EMPTY.fingerprint)
            .isEqualTo(UserDictionarySnapshot.of(emptyList<Pair<String, Int>>()).fingerprint)
    }

    @Test
    fun fiveRapidMutationsCoalesceIntoOneRewarm() {
        val poster = RecordingPoster()
        var rewarms = 0
        val savedPoster = SwipeRewarmScheduler.poster
        val savedAction = SwipeRewarmScheduler.rewarmAction
        try {
            SwipeRewarmScheduler.poster = poster
            SwipeRewarmScheduler.rewarmAction = Runnable { rewarms++ }

            // DictionaryManager.saveUserWords rewrites the whole preference per add, so five
            // adds in a row fire five preference-change callbacks.
            repeat(5) { SwipeRewarmScheduler.requestRewarm() }
            assertWithMessage("nothing may run before the coalescing window elapses")
                .that(rewarms).isEqualTo(0)

            poster.runPending()

            assertWithMessage(
                "ARC-082: five rapid mutations must produce ONE rebuild against the final " +
                    "dictionary state, not five."
            ).that(rewarms).isEqualTo(1)
            assertThat(poster.cancels).isEqualTo(5)
            assertThat(poster.delays).containsExactly(
                SwipeRewarmScheduler.DEBOUNCE_MS, SwipeRewarmScheduler.DEBOUNCE_MS,
                SwipeRewarmScheduler.DEBOUNCE_MS, SwipeRewarmScheduler.DEBOUNCE_MS,
                SwipeRewarmScheduler.DEBOUNCE_MS,
            )

            // A later, separate mutation is a new request — coalescing must not latch off.
            SwipeRewarmScheduler.requestRewarm()
            poster.runPending()
            assertThat(rewarms).isEqualTo(2)
        } finally {
            SwipeRewarmScheduler.poster = savedPoster
            SwipeRewarmScheduler.rewarmAction = savedAction
        }
    }

    /** Looper-free [SwipeRewarmScheduler.DelayedPoster]: latest posted action wins. */
    private class RecordingPoster : SwipeRewarmScheduler.DelayedPoster {
        var cancels = 0
        val delays = ArrayList<Long>()
        private var pending: Runnable? = null

        override fun cancelPending() {
            cancels++
            pending = null
        }

        override fun postDelayed(delayMs: Long, action: Runnable) {
            delays.add(delayMs)
            pending = action
        }

        /** Fires whatever survived the burst, as the real Handler would at the deadline. */
        fun runPending() {
            val action = pending
            pending = null
            action?.run()
        }
    }
}
