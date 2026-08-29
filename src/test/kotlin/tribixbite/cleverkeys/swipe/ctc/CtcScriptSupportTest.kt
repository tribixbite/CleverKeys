package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.json.JSONObject
import org.junit.Test

/**
 * The per-script wiring table's own invariants — the cheap half of the gate that stops a
 * silently-permuted decode.
 *
 * `CleverKeys-ML/ctc/APP_WIRING_CHECKLIST.md` §2.4 lists three per-script gates before a wiring
 * may be trusted. Two of them are pure and live here:
 *
 *  1. **Slot-order equality** — the app's alphabet string for a script must equal, character for
 *     character, the `layout.letters` field of the golden fixture that script's model was
 *     exported with. A permutation is silent: `keyEmbed` is a function of the key centre and
 *     never of the slot index, so a permuted alphabet is geometrically self-consistent and
 *     simply decodes the wrong letters.
 *  2. **The 32-frame budget** — the encoder emits a fixed `[1, 32, 65]`, so a word is decodable
 *     iff `length + adjacent-duplicate-pairs ≤ 32` ([CtcDecodableLength]). A word over budget is
 *     unemittable with no error at all.
 *
 * The third (the fixture row itself: emissions, greedy, top-k) is [CtcParityTest].
 */
class CtcScriptSupportTest {

    private companion object {
        /** Project-root-relative; `runPureTests` runs with cwd = project root. */
        const val FIXTURE_DIR = "src/test/resources/ctc"
        const val LAYOUT_DIR = "src/main/layouts"
        const val ASSET_DIR = "src/main/assets"
    }

    // ── Table invariants ──────────────────────────────────────────────────────────

    @Test
    fun `every script alphabet is codepoint-sorted, unique and within the emission head`() {
        // The constructor enforces sortedness and uniqueness, so constructing the table at all
        // proves those two — this asserts the third bound and states the first two explicitly so
        // a future relaxation of the `init` block has to break a test, not just a require().
        for ((language, wiring) in CtcScriptSupport.SCRIPTS) {
            val alphabet = wiring.alphabetChars()
            assertWithMessage("$language: alphabet must be codepoint-sorted")
                .that(alphabet.toList()).isInStrictOrder()
            assertWithMessage("$language: emission columns are one-to-one")
                .that(alphabet.toSet()).hasSize(alphabet.size)
            assertWithMessage(
                "$language: an alphabet wider than the emission head (${alphabet.size} vs " +
                    "${CtcFeaturizer.MAX_KEYS}) needs a re-exported model, and CtcLexiconTrie's " +
                    "constructor would reject it at runtime"
            ).that(alphabet.size).isAtMost(CtcFeaturizer.MAX_KEYS)
        }
    }

    @Test
    fun `the recorded alphabets are the ones the campaign measured`() {
        // Copied character-for-character from APP_WIRING_CHECKLIST §2.2. Restated here as
        // literals so that an edit to the table has to be made TWICE, deliberately — the pin
        // against the shipped fixtures below only covers scripts whose fixture ships, and four
        // of the six do not yet.
        assertThat(CtcScriptSupport.SCRIPTS["ru"]!!.alphabet)
            .isEqualTo("абвгдежзийклмнопрстуфхцчшщыьэюя")
        assertThat(CtcScriptSupport.SCRIPTS["el"]!!.alphabet)
            .isEqualTo("αβγδεζηθικλμνξοπρςστυφχψω")
        assertThat(CtcScriptSupport.SCRIPTS["uk"]!!.alphabet)
            .isEqualTo("абвгдежзийклмнопрстуфхцчшщьюяєі")
        assertThat(CtcScriptSupport.SCRIPTS["bg"]!!.alphabet)
            .isEqualTo("абвгдежзийклмнопрстуфхцчшщъьюя")
        assertThat(CtcScriptSupport.SCRIPTS["mk"]!!.alphabet)
            .isEqualTo("абвгдежзиклмнопрстуфхцчшѓѕјљњќџ")
        assertThat(CtcScriptSupport.SCRIPTS["he"]!!.alphabet)
            .isEqualTo("אבגדהוזחטיךכלםמןנסעףפץצקרשת")
        // K, stated as a separate fact because the checklist states it separately.
        assertThat(CtcScriptSupport.SCRIPTS.mapValues { it.value.alphabet.length })
            .containsExactlyEntriesIn(
                mapOf("ru" to 31, "el" to 25, "uk" to 31, "bg" to 30, "mk" to 31, "he" to 27)
            )
    }

    @Test
    fun `latin is the a-z default and script languages override it`() {
        assertThat(String(CtcScriptSupport.alphabetFor("en"))).isEqualTo("abcdefghijklmnopqrstuvwxyz")
        assertThat(String(CtcScriptSupport.alphabetFor("fr"))).isEqualTo("abcdefghijklmnopqrstuvwxyz")
        assertThat(String(CtcScriptSupport.alphabetFor(null))).isEqualTo("abcdefghijklmnopqrstuvwxyz")
        assertThat(String(CtcScriptSupport.alphabetFor("zz"))).isEqualTo("abcdefghijklmnopqrstuvwxyz")
        assertThat(String(CtcScriptSupport.alphabetFor("ru")))
            .isEqualTo(CtcScriptSupport.SCRIPTS["ru"]!!.alphabet)
        // Region subtags and case must not defeat the lookup — a `ru-RU` primary language would
        // otherwise silently decode against a–z on a Cyrillic board.
        assertThat(String(CtcScriptSupport.alphabetFor("RU_ru")))
            .isEqualTo(CtcScriptSupport.SCRIPTS["ru"]!!.alphabet)
    }

    @Test
    fun `alphabetFor hands back a copy, never the shared latin array`() {
        // The adapter stores the alphabet in a CtcLayout and a CtcLexiconTrie; a shared mutable
        // array would let one language's layout build corrupt every other's.
        val first = CtcScriptSupport.alphabetFor("en")
        first[0] = 'Z'
        assertThat(String(CtcScriptSupport.alphabetFor("en")))
            .isEqualTo("abcdefghijklmnopqrstuvwxyz")
    }

    // ── Rule 4: a ROUTED script has all three ─────────────────────────────────────

    @Test
    fun `a routed script names its model and its fixture, and both ship`() {
        for ((language, wiring) in CtcScriptSupport.SCRIPTS) {
            if (wiring.status != CtcScriptSupport.Status.ROUTED) continue
            val model = wiring.modelAsset
            val fixture = wiring.goldenFixture
            assertWithMessage("$language: ROUTED requires a model asset").that(model).isNotNull()
            assertWithMessage("$language: ROUTED requires a golden fixture").that(fixture).isNotNull()
            assertWithMessage(
                "$language: the model asset must actually be in the APK — rule 4's first of " +
                    "three. A ROUTED row pointing at a missing file routes swipes to an engine " +
                    "that cannot load."
            ).that(File("$ASSET_DIR/$model").isFile).isTrue()
            assertWithMessage(
                "$language: the golden fixture must ship in BOTH copies (runPureTests reads " +
                    "resources, the device gate reads test assets)"
            ).that(File("$FIXTURE_DIR/$fixture").isFile).isTrue()
            assertWithMessage("$language: instrumented fixture copy")
                .that(File("src/androidTest/assets/ctc/$fixture").isFile).isTrue()
        }
    }

    @Test
    fun `an infrastructure script states its gap and is not routed`() {
        for ((language, wiring) in CtcScriptSupport.SCRIPTS) {
            if (wiring.status == CtcScriptSupport.Status.ROUTED) continue
            assertWithMessage(
                "$language: an unrouted script must say what is missing. A blank gap is how a " +
                    "script stays unrouted for a year with nobody able to tell why."
            ).that(wiring.gap).isNotEmpty()
            assertWithMessage(
                "$language: an unrouted script's own script must not be in ROUTABLE_SCRIPTS " +
                    "unless a DIFFERENT routed language shares it (uk/bg/mk share `cyrillic` " +
                    "with ru, which is fine — the language gate stops them at dispatch)"
            ).that(
                wiring.script !in CtcScriptSupport.ROUTABLE_SCRIPTS ||
                    CtcScriptSupport.SCRIPTS.values.any {
                        it.script == wiring.script && it.status == CtcScriptSupport.Status.ROUTED
                    }
            ).isTrue()
        }
    }

    @Test
    fun `the script table and the language table agree on who is served`() {
        // Two tables, one truth. A ROUTED script whose language is not in SUPPORTED routes
        // swipes to CTC that then fall straight back to geometric (wasteful but safe); an
        // INFRASTRUCTURE script whose language IS in SUPPORTED is the dangerous direction —
        // the language gate would let it through with no model.
        for ((language, wiring) in CtcScriptSupport.SCRIPTS) {
            val supported = CtcLanguageSupport.isSupported(language)
            assertWithMessage(
                "$language: CtcScriptSupport.Status and CtcLanguageSupport.SUPPORTED must agree " +
                    "— status=${wiring.status}, supported=$supported"
            ).that(supported).isEqualTo(wiring.status == CtcScriptSupport.Status.ROUTED)
        }
    }

    @Test
    fun `only latin and routed scripts reach the router's ctc branch`() {
        assertThat(CtcScriptSupport.isRoutableScript("latin")).isTrue()
        assertThat(CtcScriptSupport.isRoutableScript("Latin")).isTrue()
        assertThat(CtcScriptSupport.isRoutableScript(null)).isFalse()
        assertThat(CtcScriptSupport.isRoutableScript("")).isFalse()
        // Scripts present in the tree but with no wiring at all stay geometric forever until a
        // row exists for them.
        for (script in listOf("arabic", "devanagari", "thai", "hangul", "armenian")) {
            assertWithMessage(script).that(CtcScriptSupport.isRoutableScript(script)).isFalse()
        }
        val expected = CtcScriptSupport.SCRIPTS.values
            .filter { it.status == CtcScriptSupport.Status.ROUTED }
            .map { it.script }
            .toSet() + "latin"
        assertThat(CtcScriptSupport.ROUTABLE_SCRIPTS).isEqualTo(expected)
    }

    @Test
    fun `every script names a layout that actually ships`() {
        // `srcs/layouts/` is NOT shipped — `copyLayoutDefinitions` ships `src/main/layouts/`,
        // and the Greek script-attribute fix once landed in the wrong tree and looked done for
        // months (guide §7.8).
        for ((language, wiring) in CtcScriptSupport.SCRIPTS) {
            assertWithMessage("$language: ${wiring.layoutXml} must exist under $LAYOUT_DIR")
                .that(File(LAYOUT_DIR, wiring.layoutXml).isFile).isTrue()
        }
    }

    // ── Gate 1: slot order IS the alphabet ────────────────────────────────────────

    @Test
    fun `each shipped fixture's letters are exactly the app's alphabet for that script`() {
        var checked = 0
        for ((language, wiring) in CtcScriptSupport.SCRIPTS) {
            val fixture = wiring.goldenFixture ?: continue
            val file = File(FIXTURE_DIR, fixture)
            assertWithMessage("$language: $fixture must ship").that(file.isFile).isTrue()
            val letters = JSONObject(file.readText()).getJSONObject("layout").getString("letters")
            assertWithMessage(
                "$language: THE footgun. The model's emission slot order IS this string — " +
                    "column c is letters[c] — and a permutation does not throw, it silently " +
                    "permutes every decode. app='${wiring.alphabet}' fixture='$letters'"
            ).that(wiring.alphabet).isEqualTo(letters)
            checked++
        }
        assertWithMessage(
            "no script fixture ships, so this gate asserted nothing. If that is deliberate " +
                "(all scripts are INFRASTRUCTURE), delete the assertion deliberately rather " +
                "than leaving a vacuous green."
        ).that(checked).isGreaterThan(0)
    }

    @Test
    fun `each shipped fixture decodes at the preset its language will actually ship at`() {
        for ((language, wiring) in CtcScriptSupport.SCRIPTS) {
            val fixture = wiring.goldenFixture ?: continue
            val preset = JSONObject(File(FIXTURE_DIR, fixture).readText()).getJSONArray("preset")
            val ship = CtcScoringParams.presetFor(language)
            assertWithMessage("$language: fixture stores the 5 scoring terms")
                .that(preset.length()).isEqualTo(5)
            assertWithMessage("$language: fixture gamma").that(preset.getDouble(0)).isEqualTo(ship.gamma)
            assertWithMessage("$language: fixture lambda").that(preset.getDouble(1)).isEqualTo(ship.lambda)
            assertWithMessage("$language: fixture beta").that(preset.getDouble(2)).isEqualTo(ship.beta)
            assertWithMessage("$language: fixture gammaPrune")
                .that(preset.getDouble(3)).isEqualTo(ship.gammaPrune)
            assertWithMessage("$language: fixture betaPrune")
                .that(preset.getDouble(4)).isEqualTo(ship.betaPrune)
        }
    }

    // ── Gate 2: the 32-frame budget ───────────────────────────────────────────────

    @Test
    fun `every alphabet letter is individually emittable`() {
        // Trivially true for a single character, but it pins the direction: the budget is
        // spent per CHARACTER, so a script whose alphabet somehow contained a multi-char
        // grapheme would be a wiring error, not a long-word problem.
        for ((language, wiring) in CtcScriptSupport.SCRIPTS) {
            for (ch in wiring.alphabet) {
                assertWithMessage("$language: '$ch'")
                    .that(CtcDecodableLength.isDecodable(ch.toString())).isTrue()
            }
        }
    }

    @Test
    fun `the frame budget bounds every script the same way`() {
        // The budget is a property of the EXPORT, not of the script: all six graphs emit
        // [1, 32, 65]. Stated here so the per-lexicon sweep (which needs the lexicon, and
        // therefore a device or an imported pack) has a documented constant to check against.
        assertThat(CtcDecodableLength.EMISSION_FRAMES).isEqualTo(32)
        // A 31-letter Cyrillic word with no doubles fits; the same word with 2 doubled pairs
        // needs 2 more frames than its length.
        assertThat(CtcDecodableLength.framesRequired("клавиатура")).isEqualTo(10)
        assertThat(CtcDecodableLength.framesRequired("класс")).isEqualTo(6)
    }
}
