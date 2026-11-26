package tribixbite.cleverkeys.autocorrect

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.lang.ref.WeakReference

/**
 * [AutocorrectMemo] must never keep its owner (the serving `WordPredictor`) alive
 * (docs/audit/2026-11-26-memory-analysis.md, defect 1).
 *
 * A retired `CleverKeysService` stays reachable from a framework JNI global (the IME's
 * back-callback, docs/audit/2026-09-10-memory-oom-root-cause.md), and through it the service's
 * `SuggestionHandler.autocorrectMemo`. The memo's entry used to hold the predictor strongly, so a
 * retired predictor and everything it still referenced (18 MiB of English dictionary on the Saga)
 * survived every GC. The owner is only an identity key; holding it weakly costs a recompute at
 * worst, after the owner is already gone.
 *
 * Pure tier. Each owner is created inside a helper that returns only a [WeakReference], so no
 * test-frame local roots it (CLAUDE.md "Heap-probe Java-local roots").
 */
class AutocorrectMemoOwnershipTest {

    @Test
    fun `a memoized correction does not keep its owner reachable`() {
        val memo = AutocorrectMemo()
        val owner = memoizeForThrowawayOwner(memo)
        assertWithMessage("the memo holds an entry").that(memo.isEmpty).isFalse()
        assertWithMessage("the owner is collectable while its correction is memoized")
            .that(collected(owner)).isTrue()
    }

    @Test
    fun `the memo still serves a hit for a live owner`() {
        val memo = AutocorrectMemo()
        val owner = Any()
        var computations = 0
        val first = memo.get("teh", owner, "fp") { computations++; "the" }
        val second = memo.get("teh", owner, "fp") { computations++; "the" }
        assertWithMessage("both answers").that(listOf(first, second)).containsExactly("the", "the")
        assertWithMessage("the second call is a hit").that(computations).isEqualTo(1)
    }

    @Test
    fun `a different owner misses`() {
        val memo = AutocorrectMemo()
        memo.get("teh", Any(), "fp") { "the" }
        var computations = 0
        memo.get("teh", Any(), "fp") { computations++; "tech" }
        assertWithMessage("a new owner recomputes").that(computations).isEqualTo(1)
    }

    private fun memoizeForThrowawayOwner(memo: AutocorrectMemo): WeakReference<Any> {
        val owner = Any()
        memo.get("teh", owner, "fp") { "the" }
        return WeakReference(owner)
    }

    private fun collected(ref: WeakReference<*>): Boolean {
        repeat(20) {
            System.gc()
            if (ref.get() == null) return true
            @Suppress("UNUSED_VARIABLE") val pressure = ByteArray(1 shl 20)
            Thread.sleep(10)
        }
        return ref.get() == null
    }
}
