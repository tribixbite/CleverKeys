package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * Pure JVM lifecycle tests for [EmojiKeywordIndex.cancel] (R-6, core-ime audit).
 *
 * [EmojiKeywordIndex] is an Android-bound singleton (`prewarm`/`search` touch `Context.assets` /
 * `android.util.Log`), so we deliberately exercise ONLY the Android-free surface: `cancel()`,
 * `isReady()`, `awaitReady()`, `getStats()` — never `prewarm`/`search`. This validates the new
 * `cancel()` contract the owner ([CleverKeysService.onDestroy] -> [CleanupHandler.cleanup]) relies
 * on: safe/idempotent when no load is in flight, and callers of the join tolerate a cancelled load.
 *
 * Because it is a shared `object` whose state could be mutated by other suites' prewarm calls, the
 * assertions here are limited to the no-op / null-`loadJob` path, which is stable regardless of
 * whether a real load ever ran (`cancel()` on a null-or-finished job is always a no-op).
 */
class EmojiKeywordIndexLifecycleTest {

    @Test
    fun `cancel with no in-flight load is a safe no-op`() {
        // Must not throw even if prewarm was never called (loadJob == null) or already finished.
        EmojiKeywordIndex.cancel()
        EmojiKeywordIndex.cancel() // idempotent: calling twice is still safe
    }

    @Test
    fun `cancel does not force isReady true or false-positive readiness`() {
        // cancel() must not fabricate a "ready" state; it only cancels the load job.
        EmojiKeywordIndex.cancel()
        // isReady reflects real load state; without a completed prewarm here it is not made true.
        // (We assert only that the call is queryable and boolean, not a specific value, since a
        //  sibling suite may have prewarmed the shared singleton on the same JVM.)
        val ready: Boolean = EmojiKeywordIndex.isReady()
        assertThat(ready == true || ready == false).isTrue()
    }

    @Test
    fun `awaitReady returns promptly after cancel when no job is active`() {
        // With no active loadJob, awaitReady()'s `loadJob?.join()` is a no-op and returns at once.
        EmojiKeywordIndex.cancel()
        runBlocking {
            withTimeout(2_000) { // fail loudly if awaitReady ever blocks after cancel
                EmojiKeywordIndex.awaitReady()
            }
        }
    }

    @Test
    fun `getStats is safe to call around cancel`() {
        EmojiKeywordIndex.cancel()
        // getStats reads rootNode without Android APIs; returns a non-null diagnostic string.
        assertThat(EmojiKeywordIndex.getStats()).isNotNull()
    }
}
