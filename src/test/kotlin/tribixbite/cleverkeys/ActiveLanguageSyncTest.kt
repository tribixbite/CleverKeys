package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * GH #186 / GH #61 — the ONE language-change path.
 *
 * [ActiveLanguageSync] receives Config's active languages after every refresh (a layout
 * switch, a Settings selector, a language toggle, a backup import) and reloads only what
 * changed. These tests drive it with a recording sink, the same calls the composition root
 * wires to PredictionCoordinator / ContractionManager / the swipe re-warm.
 */
class ActiveLanguageSyncTest {

    private class RecordingSink : ActiveLanguageSync.Sink {
        val calls = mutableListOf<String>()
        override fun reloadPrimary(language: String) { calls += "primary:$language" }
        override fun reloadSecondary(language: String?) { calls += "secondary:${language ?: "-"}" }
        override fun reloadContractions(primary: String, secondary: String?) {
            calls += "contractions:$primary+${secondary ?: "-"}"
        }
        override fun rewarmSwipe() { calls += "rewarm" }
        override fun announce(active: ActiveLanguages) { calls += "announce:${active.primary}" }
    }

    private val enFa = ActiveLanguages(primary = "en", secondary = "fa", boundLayoutLanguage = null)

    @Test
    fun switchingToALayoutBoundToFaMakesFaTheServingLanguage() {
        val sink = RecordingSink()
        val sync = ActiveLanguageSync(enFa, sink)

        val changed = sync.apply(ActiveLanguages("fa", null, "fa"))

        assertThat(changed).isTrue()
        assertWithMessage(
            "#186: the primary dictionary — the one WordPredictor.autoCorrect consults — must " +
                "become fa; the secondary is dropped (single-language while bound); contractions " +
                "follow; the swipe engine re-warms LAST so it warms the new language"
        ).that(sink.calls).containsExactly(
            "primary:fa", "secondary:-", "contractions:fa+-", "rewarm", "announce:fa"
        ).inOrder()
        assertThat(sync.applied.primary).isEqualTo("fa")
    }

    @Test
    fun anUnboundSwitchKeepsTheCurrentLanguageAndReloadsNothing() {
        val sink = RecordingSink()
        val sync = ActiveLanguageSync(enFa, sink)

        // switch_forward between two unbound layouts: Config resolves the same languages.
        assertThat(sync.apply(enFa.copy())).isFalse()
        assertThat(sink.calls).isEmpty()
    }

    @Test
    fun leavingABoundLayoutRestoresThePreferenceLanguages() {
        val sink = RecordingSink()
        val sync = ActiveLanguageSync(ActiveLanguages("fa", null, "fa"), sink)

        sync.apply(enFa)

        assertThat(sink.calls).containsExactly(
            "primary:en", "secondary:fa", "contractions:en+fa", "rewarm"
        ).inOrder()
    }

    @Test
    fun aSecondaryOnlyChangeDoesNotReloadThePrimaryDictionary() {
        val sink = RecordingSink()
        val sync = ActiveLanguageSync(ActiveLanguages("en", null, null), sink)

        sync.apply(enFa) // Multi-Language turned on with fa as secondary

        assertThat(sink.calls).containsExactly("secondary:fa", "contractions:en+fa", "rewarm").inOrder()
    }

    @Test
    fun aBoundLayoutWithTheSameLanguagesReloadsNothing() {
        val sink = RecordingSink()
        val sync = ActiveLanguageSync(ActiveLanguages("en", null, null), sink)

        // Layout bound to en while the preference primary is already en (no secondary).
        assertThat(sync.apply(ActiveLanguages("en", null, "en"))).isTrue()
        assertWithMessage("nothing to load and nothing new to announce").that(sink.calls).isEmpty()
    }

    @Test
    fun threeBoundLayoutsCycleThroughThreeDictionaries() {
        val sink = RecordingSink()
        val sync = ActiveLanguageSync(ActiveLanguages("en", null, "en"), sink)

        for (lang in listOf("de", "fa", "en")) sync.apply(ActiveLanguages(lang, null, lang))

        assertThat(sink.calls.filter { it.startsWith("primary:") })
            .containsExactly("primary:de", "primary:fa", "primary:en").inOrder()
        assertWithMessage("no secondary is ever loaded while every layout is bound")
            .that(sink.calls.filter { it.startsWith("secondary:") }).isEmpty()
    }
}
