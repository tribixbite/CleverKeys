package tribixbite.cleverkeys.ui.settings

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.GENERATED_SEARCH_ENTRIES

/**
 * A search for "Swiss spelling" while German is not selected landed on the Multi-Language section
 * and scrolled nowhere (todo 2026-11-25 D2 follow-up). The switch sits in `if (germanSelected)`, a
 * local value no control sets, so the generator found no revealing control. German is chosen in
 * Primary Language, which itself shows only with Multi-Language on, so the result now lands on
 * Primary Language, or on the Multi-Language switch while that is off — the same landing the
 * generated gates give "Primary Language" itself.
 */
class SettingsSearchConditionGatesTest {

    private val swiss = "multilang_swiss_spelling_title"

    @Test
    fun theGeneratorFindsNoGateForTheSwissSwitch() {
        val entry = GENERATED_SEARCH_ENTRIES.single { it.settingId == swiss }
        assertWithMessage("precondition: the hand-kept gates exist because this is empty")
            .that(entry.gateIds).isEmpty()
    }

    @Test
    fun theSwissSwitchLandsOnPrimaryLanguageThenTheMultiLanguageSwitch() {
        val entry = GENERATED_SEARCH_ENTRIES.single { it.settingId == swiss }
        assertThat(SettingsSearchMatch.gatesFor(swiss, entry.gateIds))
            .containsExactly("multilang_primary_title", "multilang_enable_title").inOrder()
    }

    @Test
    fun theHandKeptGatesNameRealSettings() {
        val ids = GENERATED_SEARCH_ENTRIES.map { it.settingId }.toSet()
        for (entry in GENERATED_SEARCH_ENTRIES) {
            for (gate in SettingsSearchMatch.gatesFor(entry.settingId, entry.gateIds)) {
                assertWithMessage("${entry.settingId} gate").that(ids).contains(gate)
            }
        }
    }

    @Test
    fun otherSettingsKeepTheirGeneratedGates() {
        val primary = GENERATED_SEARCH_ENTRIES.single { it.settingId == "multilang_primary_title" }
        assertThat(SettingsSearchMatch.gatesFor(primary.settingId, primary.gateIds)).isEqualTo(primary.gateIds)
    }
}
