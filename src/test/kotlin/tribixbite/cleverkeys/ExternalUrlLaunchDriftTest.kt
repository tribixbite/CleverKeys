package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Low (review 2026-11-14): "What's new" (Activities card and Settings search) and the language
 * packs link launched `ACTION_VIEW` unguarded, so a device without a browser crashed Settings with
 * ActivityNotFoundException. They go through `openExternalUrl`, which catches it.
 *
 * Source scan: no raw `ACTION_VIEW` launch left in the three files, and the helper catches the
 * exception. Not covered: the launch itself (needs a device).
 */
class ExternalUrlLaunchDriftTest {

    private val files = listOf(
        "src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/ActivitiesSection.kt",
        "src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsSearch.kt",
        "src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/MultiLanguageSection.kt",
    )

    @Test
    fun noUnguardedViewLaunchesInSettings() {
        val offenders = files.filter { SourceScan.read(it).contains("Intent.ACTION_VIEW") }
        assertWithMessage("raw ACTION_VIEW launches (route them through openExternalUrl): $offenders")
            .that(offenders).isEmpty()
    }

    @Test
    fun theHelperCatchesActivityNotFound() {
        val nav = SourceScan.read("src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsNavigation.kt")
        val body = SourceScan.functionBody(nav, "fun Context.openExternalUrl(")
        assertWithMessage("openExternalUrl must catch ActivityNotFoundException")
            .that(body.contains("catch (e: ActivityNotFoundException)")).isTrue()
    }

    /** Low (review 2026-11-14): CTC Settings showed an English "N threads" in every language. */
    @Test
    fun ctcThreadsReadout_hasNoHardcodedEnglish() {
        val src = SourceScan.read("src/main/kotlin/tribixbite/cleverkeys/activities/CtcSettingsActivity.kt")
        assertWithMessage("CtcSettingsActivity: hardcoded \"threads\" readout")
            .that(src.contains(" threads\"")).isFalse()
    }
}
