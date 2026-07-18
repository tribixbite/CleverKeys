package tribixbite.cleverkeys.clipboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #156 pure-JVM tests for [PrivateCopyRateLimiter] (design §6.5): sliding-window 10 accepts per
 * calling package per minute, 30 total per minute. Anti-annoyance defense-in-depth, not a security
 * boundary. Clock is injected so the window is driven deterministically.
 */
class PrivateCopyRateLimiterTest {

    /** Mutable fake clock. */
    private class FakeClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun allowsExactlyPerKeyLimit_thenBlocks() {
        val clock = FakeClock()
        val limiter = PrivateCopyRateLimiter(clock = clock)
        // First 10 from the same key within the window are allowed.
        repeat(PrivateCopyRateLimiter.PER_KEY_LIMIT) {
            assertThat(limiter.tryAcquire("com.attacker")).isTrue()
            clock.now += 100  // advance well within the 60 s window
        }
        // 11th is blocked.
        assertThat(limiter.tryAcquire("com.attacker")).isFalse()
    }

    @Test
    fun windowSlides_allowsAgainAfterExpiry() {
        val clock = FakeClock()
        val limiter = PrivateCopyRateLimiter(clock = clock)
        repeat(PrivateCopyRateLimiter.PER_KEY_LIMIT) { assertThat(limiter.tryAcquire("app")).isTrue() }
        assertThat(limiter.tryAcquire("app")).isFalse()
        // Advance past the window — all prior timestamps expire.
        clock.now += PrivateCopyRateLimiter.WINDOW_MS + 1
        assertThat(limiter.tryAcquire("app")).isTrue()
    }

    @Test
    fun deniedAttemptDoesNotConsumeBudget() {
        val clock = FakeClock()
        val limiter = PrivateCopyRateLimiter(perKeyLimit = 2, clock = clock)
        assertThat(limiter.tryAcquire("k")).isTrue()
        assertThat(limiter.tryAcquire("k")).isTrue()
        // Two denials while at cap — these must NOT be recorded (would push out a future accept).
        assertThat(limiter.tryAcquire("k")).isFalse()
        assertThat(limiter.tryAcquire("k")).isFalse()
        // Slide the window just past the FIRST accept; exactly one slot frees up.
        clock.now += PrivateCopyRateLimiter.WINDOW_MS + 1
        assertThat(limiter.tryAcquire("k")).isTrue()
    }

    @Test
    fun separateKeysAreIndependent() {
        val clock = FakeClock()
        val limiter = PrivateCopyRateLimiter(clock = clock)
        repeat(PrivateCopyRateLimiter.PER_KEY_LIMIT) { assertThat(limiter.tryAcquire("keyA")).isTrue() }
        assertThat(limiter.tryAcquire("keyA")).isFalse()
        // A different caller still has its full per-key budget.
        assertThat(limiter.tryAcquire("keyB")).isTrue()
    }

    @Test
    fun globalCapBlocksAcrossKeys_evenWhenPerKeyBudgetRemains() {
        val clock = FakeClock()
        // per-key 100 (effectively unlimited here), global 5 → global is the binding constraint.
        val limiter = PrivateCopyRateLimiter(perKeyLimit = 100, globalLimit = 5, clock = clock)
        for (i in 0 until 5) {
            assertThat(limiter.tryAcquire("caller-$i")).isTrue()
        }
        // 6th caller is blocked by the GLOBAL cap despite having a fresh per-key budget.
        assertThat(limiter.tryAcquire("caller-6")).isFalse()
    }

    @Test
    fun globalWindowSlides() {
        val clock = FakeClock()
        val limiter = PrivateCopyRateLimiter(perKeyLimit = 100, globalLimit = 2, clock = clock)
        assertThat(limiter.tryAcquire("a")).isTrue()
        assertThat(limiter.tryAcquire("b")).isTrue()
        assertThat(limiter.tryAcquire("c")).isFalse()  // global cap hit
        clock.now += PrivateCopyRateLimiter.WINDOW_MS + 1
        assertThat(limiter.tryAcquire("c")).isTrue()  // window slid, global freed
    }

    @Test
    fun backwardClockStep_selfHeals_acceptsAgain() {
        // Regression for the non-monotonic-clock hazard: the injected wall clock can step BACKWARDS
        // (NTP correction, manual change, DST). Before the fix, timestamps recorded at the higher
        // "now" sat in the future relative to the new lower "now", were never evicted, and blocked
        // every subsequent accept for up to the size of the jump. The window must self-heal.
        val clock = FakeClock(now = 10_000_000L)
        val limiter = PrivateCopyRateLimiter(clock = clock)
        // Fill the per-key budget at the current (high) clock value.
        repeat(PrivateCopyRateLimiter.PER_KEY_LIMIT) { assertThat(limiter.tryAcquire("app")).isTrue() }
        assertThat(limiter.tryAcquire("app")).isFalse()

        // Clock steps BACKWARDS by more than the window — the recorded timestamps are now in the
        // future. They must be treated as stale and evicted so accepts resume immediately.
        clock.now -= PrivateCopyRateLimiter.WINDOW_MS * 2
        assertThat(limiter.tryAcquire("app")).isTrue()

        // And the global window recovers the same way (independent caller after a backward step).
        assertThat(limiter.tryAcquire("other")).isTrue()
    }

    @Test
    fun backwardClockStep_recoversGlobalWindow() {
        // Same non-monotonic hazard for the GLOBAL cap: fill it, step the clock back past the window,
        // and confirm the global window self-heals so new callers are accepted again.
        val clock = FakeClock(now = 5_000_000L)
        val limiter = PrivateCopyRateLimiter(perKeyLimit = 100, globalLimit = 3, clock = clock)
        assertThat(limiter.tryAcquire("a")).isTrue()
        assertThat(limiter.tryAcquire("b")).isTrue()
        assertThat(limiter.tryAcquire("c")).isTrue()
        assertThat(limiter.tryAcquire("d")).isFalse()  // global cap hit

        clock.now -= PrivateCopyRateLimiter.WINDOW_MS * 2  // clock jumps backwards
        assertThat(limiter.tryAcquire("d")).isTrue()  // future timestamps evicted → global freed
    }

    @Test
    fun freshLimiterHasFullBudget_processRestartResetSemantics() {
        // State is in-memory (process-lifetime); a new instance models a process restart resetting
        // the limiter — deliberate and acceptable (design §6.5).
        val limiter1 = PrivateCopyRateLimiter(clock = FakeClock())
        repeat(PrivateCopyRateLimiter.PER_KEY_LIMIT) { limiter1.tryAcquire("k") }
        assertThat(limiter1.tryAcquire("k")).isFalse()
        val limiter2 = PrivateCopyRateLimiter(clock = FakeClock())
        assertThat(limiter2.tryAcquire("k")).isTrue()
    }
}
