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
    fun aSinkThatThrowsLeavesTheOldStateSoTheNextApplyRetries() {
        // Review 2026-11-14 (low): `applied` was set before the sinks ran, so a reload that threw
        // left the new language recorded as served and every later apply() a no-op.
        var failNext = true
        val sink = object : ActiveLanguageSync.Sink {
            val calls = mutableListOf<String>()
            override fun reloadPrimary(language: String) {
                if (failNext) { failNext = false; throw IllegalStateException("dictionary not ready") }
                calls += "primary:$language"
            }
            override fun reloadSecondary(language: String?) {}
            override fun reloadContractions(primary: String, secondary: String?) {}
            override fun rewarmSwipe() {}
            override fun announce(active: ActiveLanguages) {}
        }
        val sync = ActiveLanguageSync(enFa, sink)
        val fa = ActiveLanguages("fa", null, "fa")

        runCatching { sync.apply(fa) }
        assertThat(sync.applied).isEqualTo(enFa)
        assertThat(sync.apply(fa)).isTrue()
        assertThat(sink.calls).containsExactly("primary:fa")
    }

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

    // ── D2: Swiss German spelling (pref_german_swiss_spelling) ──────────────────────────────

    @Test
    fun flippingSwissSpellingReloadsAGermanPrimaryAndRewarmsTheSwipeEngine() {
        val sink = RecordingSink()
        val sync = ActiveLanguageSync(ActiveLanguages("de", null, null), sink)

        assertThat(sync.applySwissSpelling(true)).isTrue()

        assertWithMessage(
            "the German dictionary is reloaded with the new spelling and the swipe lexicon re-warmed; " +
                "contractions do not depend on the spelling"
        ).that(sink.calls).containsExactly("primary:de", "rewarm").inOrder()
        assertThat(sync.appliedSwissSpelling).isTrue()

        sink.calls.clear()
        assertWithMessage("the same value again reloads nothing").that(sync.applySwissSpelling(true)).isFalse()
        assertThat(sink.calls).isEmpty()

        assertThat(sync.applySwissSpelling(false)).isTrue()
        assertThat(sink.calls).containsExactly("primary:de", "rewarm").inOrder()
    }

    @Test
    fun flippingSwissSpellingReloadsOnlyTheGermanSlot() {
        val sink = RecordingSink()
        val sync = ActiveLanguageSync(ActiveLanguages("en", "de", null), sink)

        sync.applySwissSpelling(true)

        assertThat(sink.calls).containsExactly("secondary:de", "rewarm").inOrder()
    }

    @Test
    fun flippingSwissSpellingWithoutGermanReloadsNothingAndIsRemembered() {
        val sink = RecordingSink()
        val sync = ActiveLanguageSync(enFa, sink)

        assertThat(sync.applySwissSpelling(true)).isTrue()
        assertThat(sink.calls).isEmpty()

        // German arrives later: the language diff loads it, with the stored spelling.
        sync.apply(ActiveLanguages("de", null, null))
        sink.calls.clear()
        assertWithMessage("already applied: no second reload").that(sync.applySwissSpelling(true)).isFalse()
        assertThat(sink.calls).isEmpty()
    }

    @Test
    fun theSyncStartsFromTheSpellingTheComponentsWereLoadedWith() {
        val sink = RecordingSink()
        val sync = ActiveLanguageSync(ActiveLanguages("de", null, null), sink, initialSwissSpelling = true)

        assertWithMessage("startup must not reload a dictionary it already loaded")
            .that(sync.applySwissSpelling(true)).isFalse()
        assertThat(sink.calls).isEmpty()
    }
}
