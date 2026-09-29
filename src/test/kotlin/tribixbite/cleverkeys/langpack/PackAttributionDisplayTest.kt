package tribixbite.cleverkeys.langpack

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The display decisions behind the "Source & license" row of the language-pack manager
 * (data-licensing audit 2026-09-26 follow-up). Pure JVM: [PackAttributionDisplay.from] owns
 * every choice the composable makes, so the composable is a straight render of this model.
 *
 * The one decision with a security edge is which source tokens become tappable. A pack is
 * untrusted input, and a tap turns the token into an `ACTION_VIEW` intent, so only `http(s)`
 * URLs may be links — an `intent:`/`file:`/`content:` token must never be launchable.
 */
class PackAttributionDisplayTest {

    private fun manifest(
        license: String? = null,
        attribution: String? = null,
        source: String? = null,
    ) = LanguagePackManifest(
        code = "de", name = "German",
        license = license, attribution = attribution, source = source,
    )

    @Test
    fun aPackWithNoAttributionReportsNothingToShow() {
        val display = PackAttributionDisplay.from(manifest())
        assertWithMessage("a pre-2026-09-27 pack must render the 'not provided' note, not empty rows")
            .that(display.hasAttribution).isFalse()
        assertThat(display.license).isNull()
        assertThat(display.attribution).isNull()
        assertThat(display.sources).isEmpty()
    }

    @Test
    fun anyOneKeyIsEnoughToShowTheDetails() {
        assertThat(PackAttributionDisplay.from(manifest(license = "GPL-3.0-only")).hasAttribution).isTrue()
        assertThat(PackAttributionDisplay.from(manifest(attribution = "wordfreq")).hasAttribution).isTrue()
        assertThat(PackAttributionDisplay.from(manifest(source = "https://a.example")).hasAttribution).isTrue()
    }

    @Test
    fun aSpaceSeparatedUrlListBecomesOneLinkPerUrl() {
        // The exact shape build_langpack.py writes for the AOSP-oracle packs.
        val display = PackAttributionDisplay.from(manifest(
            source = "https://github.com/rspeer/wordfreq https://android.googlesource.com/platform/packages/inputmethods/LatinIME/"
        ))
        assertThat(display.sources).containsExactly(
            PackAttributionDisplay.Source("https://github.com/rspeer/wordfreq", isLink = true),
            PackAttributionDisplay.Source(
                "https://android.googlesource.com/platform/packages/inputmethods/LatinIME/", isLink = true
            ),
        ).inOrder()
    }

    @Test
    fun freeTextSourceStaysOneUnlinkedLine() {
        val text = "Swahili Wikipedia via https://kevindonnelly.org.uk/swahili/swwiki/"
        assertWithMessage("splitting prose on spaces would scatter it into one-word rows")
            .that(PackAttributionDisplay.from(manifest(source = text)).sources)
            .containsExactly(PackAttributionDisplay.Source(text, isLink = false))
    }

    @Test
    fun onlyHttpAndHttpsTokensAreEverLinks() {
        for (hostile in listOf(
            "intent://scan/#Intent;scheme=zxing;end",
            "file:///data/data/tribixbite.cleverkeys/shared_prefs/x.xml",
            "content://com.android.contacts/contacts",
            "javascript:alert(1)",
            "https://ok.example intent://evil",
        )) {
            val sources = PackAttributionDisplay.from(manifest(source = hostile)).sources
            assertWithMessage("\"$hostile\" must not produce a launchable link")
                .that(sources.none { it.isLink }).isTrue()
            assertWithMessage("…but is still shown, verbatim, as text")
                .that(sources.map { it.text }).containsExactly(hostile)
        }
    }

    @Test
    fun schemeMatchingIsCaseInsensitive() {
        assertThat(PackAttributionDisplay.from(manifest(source = "HTTPS://Example.org/x")).sources)
            .containsExactly(PackAttributionDisplay.Source("HTTPS://Example.org/x", isLink = true))
    }

    @Test
    fun aBareSchemeIsNotALink() {
        assertThat(PackAttributionDisplay.from(manifest(source = "https://")).sources.single().isLink)
            .isFalse()
    }
}
