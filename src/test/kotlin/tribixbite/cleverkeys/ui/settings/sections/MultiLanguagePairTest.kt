package tribixbite.cleverkeys.ui.settings.sections

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * "Auto-Detect is not available for this pair" showed with no pair (todo 2026-11-26): the
 * secondary block was shown for any stored secondary other than "none", and a secondary equal to
 * the primary (the primary changed to the old secondary; the dropdown then shows "None", since it
 * never lists the primary) or blank is no pair at all, so the detector check failed on it.
 */
class MultiLanguagePairTest {

    @Test
    fun noneIsNoPair() = assertThat(MultiLanguagePair.chosenSecondary("en", "none")).isNull()

    @Test
    fun blankIsNoPair() = assertThat(MultiLanguagePair.chosenSecondary("en", "")).isNull()

    @Test
    fun theSecondaryEqualToThePrimaryIsNoPair() =
        assertThat(MultiLanguagePair.chosenSecondary("es", "es")).isNull()

    @Test
    fun aDifferentSecondaryIsThePair() =
        assertThat(MultiLanguagePair.chosenSecondary("en", "es")).isEqualTo("es")
}
