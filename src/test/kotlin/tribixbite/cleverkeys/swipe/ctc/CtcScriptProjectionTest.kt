package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.text.Normalizer
import org.junit.Test

/**
 * The per-script projection rules (`CleverKeys-ML/ctc/PHASE_O.md` §3.4, from
 * `script_registry.py`), asserted one rule at a time.
 *
 * Every case here is a way a lexicon can be silently ruined: a fold that is missing drops words
 * as untypeable, a fold that is wrong merges two words, and — the one that motivated writing the
 * module rather than reusing [CtcAzProjection] — an NFD applied to Cyrillic **destroys the
 * alphabet**, because `й` decomposes into `и` + a combining breve that no key emits.
 */
class CtcScriptProjectionTest {

    private fun projectorFor(language: String) =
        CtcScriptProjection.projectorFor(language, CtcScriptSupport.alphabetFor(language))

    // ── ru / bg / mk: folds, and emphatically NOT NFD ─────────────────────────────

    @Test
    fun `russian keeps short i, which NFD would destroy`() {
        // THE reason ru/bg/mk are excluded from the mark-stripping branch. `й` is U+0439 and
        // decomposes to U+0438 + U+0306; the breve has no emission slot, so a mark-stripping
        // projection would silently rewrite every `й` to `и` — merging мой/мои, дай/дай… and
        // making a whole class of words unreachable at their real spelling.
        val decomposed = Normalizer.normalize("й", Normalizer.Form.NFD)
        assertWithMessage("premise: й really does decompose").that(decomposed.length).isEqualTo(2)

        val ru = projectorFor("ru")
        assertThat(ru("мой")).isEqualTo("мой")
        assertThat(ru("йогурт")).isEqualTo("йогурт")
        // And the letter survives as its own slot, distinct from и.
        assertThat(CtcScriptSupport.SCRIPTS["ru"]!!.alphabet).contains("й")
        assertThat(ru("мой")).isNotEqualTo(ru("мои"))
    }

    @Test
    fun `russian folds the two corner letters onto their centre keys`() {
        val ru = projectorFor("ru")
        // ё is key1 on е, ъ is key1 on ь — corner values are never emission slots, so a word
        // carrying either would otherwise be dropped as untypeable.
        assertThat(ru("ёлка")).isEqualTo("елка")
        assertThat(ru("объект")).isEqualTo("обьект")
        assertThat(ru("ЁЖИК")).isEqualTo("ежик")
        assertWithMessage("neither folded character may remain in the alphabet")
            .that(CtcScriptSupport.SCRIPTS["ru"]!!.alphabet.none { it == 'ё' || it == 'ъ' })
            .isTrue()
    }

    @Test
    fun `bulgarian keeps the hard sign and folds only the grave i`() {
        val bg = projectorFor("bg")
        // bg's alphabet KEEPS ъ (it is a centre key on cyrl_ueishsht), so the ru fold must not
        // leak across languages.
        assertThat(CtcScriptSupport.SCRIPTS["bg"]!!.alphabet).contains("ъ")
        assertThat(bg("българия")).isEqualTo("българия")
        assertThat(bg("ѝ")).isEqualTo("и")
    }

    @Test
    fun `macedonian folds both grave vowels`() {
        val mk = projectorFor("mk")
        assertThat(mk("ѐ")).isEqualTo("е")
        assertThat(mk("ѝ")).isEqualTo("и")
        assertThat(mk("ѓавол")).isEqualTo("ѓавол")
    }

    @Test
    fun `a cyrillic word outside the alphabet is rejected, not mangled`() {
        val ru = projectorFor("ru")
        // Latin letters, digits and punctuation have no slot on a Cyrillic board.
        assertThat(ru("hello")).isNull()
        assertThat(ru("42")).isNull()
        assertThat(ru("привет!")).isNull()
        assertThat(ru("")).isNull()
    }

    // ── uk: no folds, and two characters make a word untypeable ───────────────────

    @Test
    fun `ukrainian rejects the two corner-only letters`() {
        val uk = projectorFor("uk")
        // 4.03 % of the vocabulary. Serving them needs the corner-alias path, which is a
        // different input mode (flick), not a swipe — so the honest projection drops them
        // rather than pretending some other key spells them.
        assertThat(uk("їжак")).isNull()
        assertThat(uk("ґанок")).isNull()
        assertThat(uk("ДЯКУЮ")).isEqualTo("дякую")
        assertThat(uk("привіт")).isEqualTo("привіт")
    }

    @Test
    fun `ukrainian applies no folds`() {
        val uk = projectorFor("uk")
        // ru's ё→е / ъ→ь must not leak: uk's alphabet has neither letter, so a word containing
        // one is untypeable rather than folded.
        assertThat(uk("ёлка")).isNull()
        assertThat(uk("объект")).isNull()
    }

    // ── el: BOTH halves, or neither ───────────────────────────────────────────────

    @Test
    fun `greek strips accents, which have no emission slot at all`() {
        val el = projectorFor("el")
        // The el model's 25 slots contain no accented vowel. Unprojected, `λόγος` carries `ό` —
        // a character with no column — so the word is unrepresentable, not merely mis-scored.
        assertThat(CtcScriptSupport.SCRIPTS["el"]!!.alphabet.none { it == 'ό' }).isTrue()
        assertThat(el("λόγος")).isEqualTo("λογος")
        assertThat(el("Ἑλλάς")).isEqualTo("ελλας")
        assertThat(el("προϊόν")).isEqualTo("προιον")
    }

    @Test
    fun `greek repairs word-final sigma after stripping, and only word-final`() {
        val el = projectorFor("el")
        // ς and σ are distinct codepoints AND distinct keys in different rows. 25.7 % of the
        // bundled pack stores a word-final σ; unrepaired, one Greek word in four is scored
        // against the wrong key in the wrong row while the user swipes to where orthography
        // puts it.
        assertThat(el("λογος")).isEqualTo("λογος".dropLast(1) + CtcGreekOrthography.FINAL_SIGMA)
        assertThat(el("ανθρωπος")!!.last()).isEqualTo(CtcGreekOrthography.FINAL_SIGMA)
        // Medial doubling is legitimate and must survive untouched (θάλασσα).
        assertThat(el("θάλασσα")).isEqualTo("θαλασσα")
        // Idempotent on an already-correct pack, so the repair and a regenerated pack compose.
        assertThat(el(el("λογος")!!)).isEqualTo(el("λογος"))
    }

    @Test
    fun `greek accent stripping and sigma repair compose in that order`() {
        val el = projectorFor("el")
        // A word that is BOTH accented and sigma-final exercises the ordering: strip first,
        // then repair the character that is final in the finished surface.
        assertThat(el("θεός")).isEqualTo("θεο" + CtcGreekOrthography.FINAL_SIGMA)
        assertThat(el("πόλις")).isEqualTo("πολι" + CtcGreekOrthography.FINAL_SIGMA)
    }

    // ── he: marks are niqqud, not letters ─────────────────────────────────────────

    @Test
    fun `hebrew strips niqqud and keeps final forms as their own slots`() {
        val he = projectorFor("he")
        // Niqqud are combining marks and are not keys. Final forms (ך ם ן ף ץ) ARE separate
        // letters with their own slots, so they must survive.
        assertThat(he("שָׁלוֹם")).isEqualTo("שלום")
        assertThat(he("ארץ")).isEqualTo("ארץ")
        for (finalForm in listOf('ך', 'ם', 'ן', 'ף', 'ץ')) {
            assertWithMessage("final form '$finalForm' must have its own slot")
                .that(CtcScriptSupport.SCRIPTS["he"]!!.alphabet).contains(finalForm.toString())
        }
    }

    // ── Shared: joiners, case, and the collision rule ─────────────────────────────

    @Test
    fun `joiners are stripped for every script, not rejected`() {
        // PHASE_O §3.4's "all scripts" rule: `- ' ’ ʼ ‘ \``. The STRIP convention makes a
        // hyphenated or apostrophised form reachable as its joined surface rather than dropping
        // it, exactly as the Latin path does.
        assertThat(projectorFor("ru")("кто-то")).isEqualTo("ктото")
        assertThat(projectorFor("uk")("будь-ласка")).isEqualTo("будьласка")
        assertThat(projectorFor("el")("μια’λλη")).isEqualTo("μιαλλη")
    }

    @Test
    fun `every script projection lowercases`() {
        assertThat(projectorFor("ru")("МОСКВА")).isEqualTo("москва")
        assertThat(projectorFor("bg")("СОФИЯ")).isEqualTo("софия")
        assertThat(projectorFor("mk")("СКОПЈЕ")).isEqualTo("скопје")
    }

    @Test
    fun `the collision rule keeps the higher frequency and its display form`() {
        // The single shared loop, exercised through the script projector. ёлка and елка project
        // onto the same surface; the higher-frequency canonical form must own both the
        // frequency and the display slot, so the map can never disagree with the frequency it
        // was chosen for.
        val projected = CtcScriptProjection.projectLexicon(
            linkedMapOf("ёлка" to 100.0, "елка" to 200.0, "hello" to 50.0),
            projectorFor("ru"),
        )
        assertThat(projected.freqs["елка"]).isEqualTo(200.0)
        assertThat(projected.display).doesNotContainKey("елка")
        assertThat(projected.records).isEqualTo(3)
        assertThat(projected.untypeable).isEqualTo(1)   // "hello" has no Cyrillic surface
        assertThat(projected.collisions).isEqualTo(1)
    }

    @Test
    fun `a lower-frequency variant loses but is still counted`() {
        val projected = CtcScriptProjection.projectLexicon(
            linkedMapOf("елка" to 200.0, "ёлка" to 100.0),
            projectorFor("ru"),
        )
        assertThat(projected.freqs["елка"]).isEqualTo(200.0)
        assertThat(projected.collisions).isEqualTo(1)
        assertThat(projected.freqs).hasSize(1)
    }

    @Test
    fun `the accented canonical form is kept for display when it wins`() {
        val projected = CtcScriptProjection.projectLexicon(
            linkedMapOf("ёлка" to 200.0),
            projectorFor("ru"),
        )
        assertThat(projected.freqs.keys).containsExactly("елка")
        assertThat(projected.display["елка"]).isEqualTo("ёлка")
    }

    // ── The Latin path must be unchanged by the shared loop ───────────────────────

    @Test
    fun `the latin projection is byte-identical after delegating its loop`() {
        // CtcAzProjection.projectLexicon now delegates to the shared loop. The delegation is a
        // MOVE, not a rewrite: same collision rule, same display semantics, same counters. If
        // this drifts, every fr/de/es/it/pt/sv decode vocabulary drifts with it.
        val canonical = linkedMapOf(
            "café" to 200.0, "cafe" to 100.0, "don't" to 150.0, "straße" to 90.0, "42" to 10.0,
        )
        val projected = CtcAzProjection.projectLexicon(canonical)
        assertThat(projected.freqs.keys).containsExactly("cafe", "dont", "strasse").inOrder()
        assertThat(projected.freqs["cafe"]).isEqualTo(200.0)
        assertThat(projected.display["cafe"]).isEqualTo("café")
        assertThat(projected.records).isEqualTo(5)
        assertThat(projected.untypeable).isEqualTo(1)
        assertThat(projected.collisions).isEqualTo(1)
    }
}
