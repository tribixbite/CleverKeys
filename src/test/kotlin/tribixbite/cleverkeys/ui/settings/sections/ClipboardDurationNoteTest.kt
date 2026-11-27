package tribixbite.cleverkeys.ui.settings.sections

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.ClipboardHistoryLimits
import tribixbite.cleverkeys.ui.settings.sections.ClipboardDurationNote.Kind

/**
 * Fable audit D1 (2026-11-26): the Entry Duration note and its "Never expire" auto-link read the
 * entry count even in By Size mode, where the count slider is hidden and no count applies, so the
 * note warned about a count limit the user cannot see and "Never" silently rewrote it.
 */
class ClipboardDurationNoteTest {

    private val count = ClipboardHistoryLimits.LIMIT_TYPE_COUNT
    private val size = ClipboardHistoryLimits.LIMIT_TYPE_SIZE

    @Test
    fun byCountTheNoteFollowsTheCount() {
        assertThat(ClipboardDurationNote.kind(60, count, 0)).isEqualTo(Kind.UNLIMITED_COUNT)
        assertThat(ClipboardDurationNote.kind(-1, count, 50)).isEqualTo(Kind.NEVER_CAPPED)
        assertThat(ClipboardDurationNote.kind(60, count, 50)).isEqualTo(Kind.DEFAULT)
    }

    @Test
    fun bySizeTheHiddenCountDoesNotShapeTheNote() {
        assertThat(ClipboardDurationNote.kind(60, size, 0)).isEqualTo(Kind.DEFAULT)
        assertThat(ClipboardDurationNote.kind(-1, size, 50)).isEqualTo(Kind.DEFAULT)
    }

    @Test
    fun neverExpireLiftsTheCountOnlyWhereTheCountApplies() {
        assertThat(ClipboardDurationNote.liftsCountLimit(-1, count, 50)).isTrue()
        assertThat(ClipboardDurationNote.liftsCountLimit(-1, size, 50)).isFalse()
        assertThat(ClipboardDurationNote.liftsCountLimit(60, count, 50)).isFalse()
    }
}
