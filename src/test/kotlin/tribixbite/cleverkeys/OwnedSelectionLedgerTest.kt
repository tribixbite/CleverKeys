package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 2026-10-07 finding 2: own-write callback allowances expire by consumption or a
 * time bound, never by main-loop turn. The ledger only nominates candidates; callers verify
 * the editor readback (pinned through the real handler in SuggestionHandlerOwnedCallbackTest).
 */
class OwnedSelectionLedgerTest {
    @Test fun lateCallbackInsideTheWindowIsConsumedInOrder() {
        val ledger = OwnedSelectionLedger(3)
        assertTrue(ledger.stamp(6, 6, now = 100)); assertTrue(ledger.stamp(5, 6, 100)); assertTrue(ledger.stamp(8, 8, 100))
        // An editor may skip the first one; consuming a later entry drops the earlier ones.
        assertTrue(ledger.consume(5, 6, now = 100 + OwnedSelectionLedger.WINDOW_MS))
        assertEquals(1, ledger.pending)
        assertFalse(ledger.consume(6, 6, 150))
        assertTrue(ledger.consume(8, 8, 150))
        assertTrue(ledger.isEmpty)
    }

    @Test fun expiredAllowanceMatchesNothing() {
        val ledger = OwnedSelectionLedger(3)
        ledger.stamp(8, 8, now = 100)
        assertTrue(ledger.isExpired(101 + OwnedSelectionLedger.WINDOW_MS))
        assertFalse(ledger.contains(8, 8, 101 + OwnedSelectionLedger.WINDOW_MS))
        assertFalse(ledger.consume(8, 8, 101 + OwnedSelectionLedger.WINDOW_MS))
        assertEquals(1, ledger.pending)
    }

    @Test fun capacityIsAHardBound() {
        val ledger = OwnedSelectionLedger(2)
        assertTrue(ledger.stamp(1, 1, 0)); assertTrue(ledger.stamp(2, 2, 0))
        assertFalse(ledger.stamp(3, 3, 0))
        assertFalse(ledger.contains(3, 3, 0))
    }

    @Test fun containsDoesNotConsumeAndUnstampedLedgerIsInert() {
        val ledger = OwnedSelectionLedger(2)
        assertFalse(ledger.contains(0, 0, 0)); assertFalse(ledger.isLive(0))
        ledger.stamp(4, 4, 10)
        assertTrue(ledger.contains(4, 4, 20)); assertEquals(1, ledger.pending)
        ledger.clear(); assertFalse(ledger.contains(4, 4, 20))
    }
}
