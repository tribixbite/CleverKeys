package tribixbite.cleverkeys.ui.settings.sections

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * "Auto-Detect is not available for this pair" showed with no pair (todo 2026-11-26): the
 * secondary block was shown for any stored secondary other than "none", and a secondary equal to
 * the primary (the primary changed to the old secondary; the dropdown then shows "None", since it
 * never lists the primary) or blank is no pair at all, so the detector check failed on it.
 *
 * Saga 2026-11-26: deleting the pack of the current secondary (Russian under German) left the
 * dropdown on "None" (it lists only available languages) while the block and its Auto-Detect note
 * stayed. A secondary that is not available is no pair either, as the dropdown shows it.
 */
class MultiLanguagePairTest {

    /** The languages the secondary dropdown can list. */
    private val available = listOf("en", "es", "de", "fr")

    @Test
    fun noneIsNoPair() = assertThat(MultiLanguagePair.chosenSecondary("en", "none", available)).isNull()

    @Test
    fun blankIsNoPair() = assertThat(MultiLanguagePair.chosenSecondary("en", "", available)).isNull()

    @Test
    fun theSecondaryEqualToThePrimaryIsNoPair() =
        assertThat(MultiLanguagePair.chosenSecondary("es", "es", available)).isNull()

    @Test
    fun aDifferentSecondaryIsThePair() =
        assertThat(MultiLanguagePair.chosenSecondary("en", "es", available)).isEqualTo("es")

    @Test
    fun aSecondaryWhosePackIsGoneIsNoPair() =
        assertThat(MultiLanguagePair.chosenSecondary("de", "ru", available)).isNull()
}
