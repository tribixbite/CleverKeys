package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * ARC-086 — the swipe-engine fallback card's predicate, on BOTH axes.
 *
 * ## The bug this pins
 *
 * The card ("Geometric engine will be used") explains why a LANGUAGE falls back to the geometric
 * engine, and nothing else. Routing has two more gates after the language one, both of which are
 * about the BOARD, and both of which were silent:
 *
 *  1. the layout's `script` has no [tribixbite.cleverkeys.swipe.ctc.CtcScriptSupport] row at
 *     `ROUTED` — the router sends the swipe to geometric on layout metadata alone;
 *  2. the board does not expose every letter of the language's emission alphabet as a CENTRE key
 *     value — `CtcEngineAdapter.supportsLayout` refuses it at dispatch.
 *
 * (2) has a live shipped example: `latn_qwerty_az.xml` declares `script="latin"` and carries `w`
 * only as a CORNER value (`key4`), so an English user on the Azerbaijani QWERTY gets the geometric
 * engine while the Settings dropdown says CTC and the card says nothing at all — the exact
 * "silently wrong engine" the card was added to prevent on the language axis.
 *
 * ## Why the predicate is pure and separate from the composable
 *
 * The decision is three table lookups and a set difference; the Compose card is a renderer. Split
 * this way, the decision is testable on the JVM with the SAME
 * [tribixbite.cleverkeys.swipe.ctc.CtcScriptSupport] /
 * [tribixbite.cleverkeys.swipe.ctc.CtcLanguageSupport] the router and the adapter ask — the card
 * can never drift into a second, friendlier opinion about which engine will run.
 */
class SwipeEngineFallbackTest {

    private val ctc = SwipeEngineRouter.Mode.CTC

    /** A board with every a–z letter as a centre value: nothing to warn about. */
    private fun completeLatin(name: String = "QWERTY (US)") =
        SwipeEngineFallback.LayoutFacts(
            displayName = name,
            script = "latin",
            missingCentreLetters = "",
            cornerOnlyLetters = "",
        )

    /**
     * `latn_qwerty_az` as the adapter measures it: Latin, `w` missing from the centre values and
     * present as a corner. Hand-built here so the predicate is exercised without an Android
     * XML parser; [shippedAzerbaijaniBoardIsStillTheCornerOnlyExample] pins that the real file
     * still looks like this.
     */
    private fun cornerOnlyLatin(name: String = "QWERTY (Azərbaycanca)") =
        SwipeEngineFallback.LayoutFacts(
            displayName = name,
            script = "latin",
            missingCentreLetters = "w",
            cornerOnlyLetters = "w",
        )

    /** A Latin board that simply has no `w` anywhere — not even in a corner. */
    private fun trulyIncompleteLatin(name: String = "Homebrew 24-key") =
        SwipeEngineFallback.LayoutFacts(
            displayName = name,
            script = "latin",
            missingCentreLetters = "qw",
            cornerOnlyLetters = "",
        )

    /** A board whose script has no `ROUTED` row — gate 1 refuses it on metadata alone. */
    private fun hebrewBoard(name: String = "Hebrew") =
        SwipeEngineFallback.LayoutFacts(
            displayName = name,
            script = "hebrew",
            // The en alphabet is entirely absent from a Greek board, but the SCRIPT gate fires
            // first and is the honest reason: no Greek model ships at all.
            missingCentreLetters = ('a'..'z').joinToString(""),
            cornerOnlyLetters = "",
        )

    @Test
    fun servedLanguageOnACornerOnlyLatinBoardExplainsTheLayoutAxis() {
        val d = SwipeEngineFallback.diagnose(ctc, "en", listOf(cornerOnlyLatin()))

        assertWithMessage(
            "en IS served by CTC, so the language axis has nothing to say — and that is exactly " +
                "why the card was silent before ARC-086 while the board forced geometric."
        ).that(d.languageFallback).isFalse()
        assertThat(d.hasAny).isTrue()
        assertThat(d.layoutFindings).hasSize(1)

        val finding = d.layoutFindings.single()
        assertThat(finding.reason).isEqualTo(SwipeEngineFallback.LayoutReason.LETTERS_CORNER_ONLY)
        assertThat(finding.layout.displayName).isEqualTo("QWERTY (Azərbaycanca)")
        assertWithMessage(
            "the message must name the letters the user can see on the board but CTC cannot read"
        ).that(finding.lettersForDisplay).isEqualTo("w")
    }

    @Test
    fun servedLanguageOnAnAlphabetIncompleteBoardIsADifferentReason() {
        val d = SwipeEngineFallback.diagnose(ctc, "en", listOf(trulyIncompleteLatin()))

        val finding = d.layoutFindings.single()
        assertWithMessage(
            "a letter that is nowhere on the board is not the same problem as one hiding in a " +
                "corner: the first needs a new key, the second needs the key promoted to key0"
        ).that(finding.reason).isEqualTo(SwipeEngineFallback.LayoutReason.ALPHABET_INCOMPLETE)
        assertThat(finding.lettersForDisplay).isEqualTo("q, w")
    }

    @Test
    fun servedLanguageOnANonRoutedScriptBoardIsExplainedByTheScriptGate() {
        val d = SwipeEngineFallback.diagnose(ctc, "en", listOf(hebrewBoard()))

        val finding = d.layoutFindings.single()
        assertWithMessage(
            "greek has a CtcScriptSupport row but it is INFRASTRUCTURE, not ROUTED — no model " +
                "ships — so the script gate is the reason, not the (also true) missing alphabet"
        ).that(finding.reason).isEqualTo(SwipeEngineFallback.LayoutReason.SCRIPT_NOT_ROUTED)
        assertWithMessage("no letter list for a script-gate refusal — the whole board is wrong")
            .that(finding.lettersForDisplay).isEmpty()
    }

    @Test
    fun routedNonLatinScriptOnItsOwnLanguageIsNotWarnedAbout() {
        // ru IS routed (a model, a trie and a golden fixture all ship), and the ЙЦУКЕН board
        // exposes the full 31-slot alphabet as centre keys. Nothing to say.
        val d = SwipeEngineFallback.diagnose(
            ctc,
            "ru",
            listOf(
                SwipeEngineFallback.LayoutFacts(
                    displayName = "ЙЦУКЕН (Русский)",
                    script = "cyrillic",
                    missingCentreLetters = "",
                    cornerOnlyLetters = "",
                )
            )
        )
        assertThat(d.hasAny).isFalse()
    }

    @Test
    fun fullyEligibleBoardYieldsNoLayoutWarning() {
        val d = SwipeEngineFallback.diagnose(ctc, "en", listOf(completeLatin()))
        assertThat(d.languageFallback).isFalse()
        assertThat(d.layoutFindings).isEmpty()
        assertThat(d.hasAny).isFalse()
    }

    @Test
    fun geometricModeExplainsNothingBecauseNothingIsSurprising() {
        val d = SwipeEngineFallback.diagnose(
            SwipeEngineRouter.Mode.GEOMETRIC,
            "tr",
            listOf(cornerOnlyLatin(), hebrewBoard())
        )
        assertWithMessage(
            "the user picked geometric; telling them geometric will run is noise, not information"
        ).that(d.hasAny).isFalse()
    }

    @Test
    fun unservedLanguageKeepsTheLanguageAxisAndSuppressesLayoutNoise() {
        val d = SwipeEngineFallback.diagnose(ctc, "tr", listOf(cornerOnlyLatin(), hebrewBoard()))

        assertThat(d.languageFallback).isTrue()
        assertWithMessage(
            "when CTC cannot serve the language at all, the board is irrelevant — listing layout " +
                "reasons underneath would imply fixing the board would help. It would not."
        ).that(d.layoutFindings).isEmpty()
    }

    @Test
    fun onlyTheLayoutsThatActuallyFallBackAreReported() {
        val d = SwipeEngineFallback.diagnose(
            ctc,
            "en",
            listOf(completeLatin(), cornerOnlyLatin(), completeLatin("Dvorak"), hebrewBoard())
        )
        assertThat(d.layoutFindings.map { it.layout.displayName })
            .containsExactly("QWERTY (Azərbaycanca)", "Hebrew").inOrder()
    }

    @Test
    fun aWholeMissingAlphabetIsCappedSoTheCardStaysReadable() {
        val d = SwipeEngineFallback.diagnose(
            ctc,
            "en",
            listOf(
                SwipeEngineFallback.LayoutFacts(
                    displayName = "ЙЦУКЕН (Русский)",
                    // cyrillic IS routed, so this board passes gate 1 and is refused by the
                    // ALPHABET gate for an English user — all 26 letters missing.
                    script = "cyrillic",
                    missingCentreLetters = ('a'..'z').joinToString(""),
                    cornerOnlyLetters = "",
                )
            )
        )
        val finding = d.layoutFindings.single()
        assertThat(finding.reason).isEqualTo(SwipeEngineFallback.LayoutReason.ALPHABET_INCOMPLETE)
        assertWithMessage("26 comma-separated letters would run off a phone-width card")
            .that(finding.lettersForDisplay).isEqualTo("a, b, c, d, e, f, g, h…")
    }

    @Test
    fun aNullOrBlankScriptIsNotRoutable() {
        val d = SwipeEngineFallback.diagnose(
            ctc,
            "en",
            listOf(
                SwipeEngineFallback.LayoutFacts(
                    displayName = "Hand-written",
                    script = null,
                    missingCentreLetters = "",
                    cornerOnlyLetters = "",
                )
            )
        )
        assertWithMessage(
            "a custom layout with no script attribute never reaches CTC (the router's gate 1 " +
                "reads that attribute and nothing else), and the user has no other way to learn it"
        ).that(d.layoutFindings.single().reason)
            .isEqualTo(SwipeEngineFallback.LayoutReason.SCRIPT_NOT_ROUTED)
    }

    /**
     * The shipped board this whole finding was written around. Read straight off the XML — if
     * someone promotes `w` to `key0` on the Azerbaijani QWERTY the layout becomes CTC-eligible
     * and [cornerOnlyLatin] stops describing anything real, which this test would catch.
     *
     * Deliberately a characterization pin on the FILE, not a second implementation of the
     * layout gate. (The 2026-09-01 audit corrected this comment: it used to cite a
     * `CtcEngineAdapter.coveredSlots` that never existed while the card in fact re-derived
     * centre letters privately. The shared implementation is now `KeyLetter.centreLetterOf`,
     * consumed by BOTH `CtcEngineAdapter.buildMappedLayout` and `SwipeEngineFallback`, and
     * [theCentreLetterDefinitionHasExactlyOneImplementation] pins that.)
     */
    @Test
    fun shippedAzerbaijaniBoardIsStillTheCornerOnlyExample() {
        val xml = File("src/main/layouts/latn_qwerty_az.xml").readText()
        assertWithMessage("latn_qwerty_az must still declare script=\"latin\"")
            .that(xml).contains("script=\"latin\"")
        assertWithMessage("w must NOT be a centre value (c=/key0=) — that is the whole point")
            .that(Regex("""<key\b[^>]*?\b(?:c|key0)="w"""").containsMatchIn(xml)).isFalse()
        assertWithMessage("w must still be reachable as a CORNER value")
            .that(Regex("""<key\b[^>]*?\bkey[1-8]="w"""").containsMatchIn(xml)).isTrue()
    }

    /**
     * One-implementation pin (2026-09-01 audit fix): the routing gate
     * (`CtcEngineAdapter.buildMappedLayout`) and this card's predicate briefly held two
     * private copies of the centre-letter definition that agreed on every practical input
     * but had nothing tying them together. Both must consume `KeyLetter.centreLetterOf` and
     * neither may re-grow a private variant — a card that disagrees with the gate about
     * which keys count would explain a fallback that isn't happening (or miss one that is).
     */
    @Test
    fun theCentreLetterDefinitionHasExactlyOneImplementation() {
        val adapter = File("src/main/kotlin/tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt").readText()
        val card = File("src/main/kotlin/tribixbite/cleverkeys/swipe/SwipeEngineFallback.kt").readText()
        val helper = File("src/main/kotlin/tribixbite/cleverkeys/swipe/KeyLetter.kt").readText()

        assertWithMessage("the shared helper must exist and own the definition")
            .that(helper).contains("fun centreLetterOf(")
        for ((name, src) in mapOf("CtcEngineAdapter" to adapter, "SwipeEngineFallback" to card)) {
            assertWithMessage("$name must consume the shared KeyLetter.centreLetterOf")
                .that(src).contains("KeyLetter.centreLetterOf(")
            assertWithMessage("$name must not re-declare a private centre-letter extractor")
                .that(Regex("""private fun letterOf\(""").containsMatchIn(src)).isFalse()
        }
    }
}
