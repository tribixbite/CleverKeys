package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.swipe.SwipeEngineRouter.Engine
import tribixbite.cleverkeys.swipe.SwipeEngineRouter.Mode
import tribixbite.cleverkeys.swipe.ctc.CtcLanguageSupport

/**
 * WP9 R-1 step 9 (JVM leg) — routing table for the mode-based [SwipeEngineRouter] (v1.2:
 * the neural engine and its `neural`/`hybrid` modes were removed 2026-08-18).
 *
 * Routing is (mode, layout)-based: CTC (default) = CTC trie-beam on any layout whose SCRIPT
 * has a complete wiring (`CtcScriptSupport` — Latin, plus every script whose row has reached
 * ROUTED; Cyrillic since 2026-08-29) + geometric on every other and on unknown scripts;
 * GEOMETRIC = geometric on ALL layouts. The router is TOTAL — there is no "no engine"
 * outcome, so no layout can lose swipe.
 */
class SwipeEngineRouterTest {

    // ── ctc mode: any Latin layout is CTC-eligible ─────────────────────────────────
    // Gate widened 2026-08-15: the CTC encoder is layout-agnostic (key geometry is a
    // model input), validated on dvorak 91.82 / dvorak-app-geometry 91.10 top-1, so
    // Latin non-QWERTY layouts route CTC. Non-Latin/unknown scripts stay geometric (the
    // adapter can't build an a–z CtcLayout from them).

    @Test
    fun `qwerty routes ctc in ctc mode`() {
        assertThat(SwipeEngineRouter.route("QWERTY (US)", "latin", Mode.CTC))
            .isEqualTo(Engine.CTC)
    }

    @Test
    fun `dvorak and azerty route ctc in ctc mode — the encoder is layout-agnostic`() {
        assertThat(SwipeEngineRouter.route("Dvorak", "latin", Mode.CTC))
            .isEqualTo(Engine.CTC)
        assertThat(SwipeEngineRouter.route("AZERTY (FR)", "latin", Mode.CTC))
            .isEqualTo(Engine.CTC)
    }

    @Test
    fun `colemak routes ctc in ctc mode`() {
        assertThat(SwipeEngineRouter.route("Colemak", "latin", Mode.CTC))
            .isEqualTo(Engine.CTC)
    }

    /**
     * Per-script routing (2026-08-29). Gate 1 is no longer "is this Latin" but "does this
     * script have a COMPLETE wiring" — a per-script model, a per-script trie at the app's own
     * frequency scale, and a golden fixture at the shipping preset (HANDOFF rule 4).
     *
     * Cyrillic flipped when ru's artifacts landed (2026-08-29), Greek when el's did
     * (2026-08-30), and `hebrew` — this test's live negative until then — when he's landed
     * (2026-09-03, wave M-LANG). Asserting BOTH directions is still the point: the router is
     * driven by a table, so a script cannot be widened by editing the router, and a script
     * without a ROUTED row cannot drift into CTC by accident — the row-less scripts below are
     * now the negative that guards that.
     */
    @Test
    fun `a wired script routes ctc and an unwired one stays geometric`() {
        assertThat(SwipeEngineRouter.route("ЙЦУКЕН", "cyrillic", Mode.CTC))
            .isEqualTo(Engine.CTC)
        // The Greek QWERTY trap resolves the other way now: script still wins over the
        // QWERTY-shaped name, and `greek` finally HAS a ROUTED row, so the board that used to be
        // the canonical "name says QWERTY, script says otherwise" case routes CTC — on the Greek
        // encoder and the Greek trie, never the Latin ones.
        assertThat(SwipeEngineRouter.route("QWERTY (Ελληνικά)", "greek", Mode.CTC))
            .isEqualTo(Engine.CTC)
        // `hebrew` flipped 2026-09-03: model + fixture + langpack lexicon all ship (rule 4's
        // three), so the board that was the canonical "row exists, artifacts don't" negative
        // now routes CTC on the Hebrew encoder.
        assertThat(SwipeEngineRouter.route("עברית", "hebrew", Mode.CTC))
            .isEqualTo(Engine.CTC)
        // Scripts with no table row at all can never reach CTC — with every tabled script now
        // ROUTED, these are what keeps the negative direction a live assertion rather than a
        // memory.
        for (script in listOf("arabic", "devanagari", "hangul", "georgian")) {
            assertThat(SwipeEngineRouter.route("board", script, Mode.CTC))
                .isEqualTo(Engine.GEOMETRIC)
        }
    }

    /**
     * The router is layout-metadata-only, so a Cyrillic board routes CTC for EVERY language —
     * including sr/kk, whose lexicons do not exist. That is correct and is not a leak: the
     * language gate in `InputCoordinator.performCtcSwipeTyping` reads
     * `CtcLanguageSupport.SUPPORTED` before dispatch and hands those swipes to the geometric
     * engine. Pinned so the division of labour stays explicit — the router must NOT grow a
     * language parameter to compensate.
     */
    @Test
    fun `routing is per script while serving is per language`() {
        // All eleven bundled Cyrillic boards route CTC at gate 1 …
        assertThat(SwipeEngineRouter.route("ЙЦУКЕН (Українська)", "cyrillic", Mode.CTC))
            .isEqualTo(Engine.CTC)
        // … but only the SERVED Cyrillic languages get a decode. uk/bg/mk sat in the unserved
        // list until 2026-09-03 (wave M-LANG wired them); sr/kk still exemplify the division
        // of labour — routed at gate 1 by their script, unserved at the language gate.
        for (language in listOf("ru", "uk", "bg", "mk")) {
            assertWithMessage(language).that(CtcLanguageSupport.isSupported(language)).isTrue()
        }
        for (language in listOf("sr", "kk")) {
            assertWithMessage(language).that(CtcLanguageSupport.isSupported(language)).isFalse()
        }
        // Greek is the clean case rather than the counter-example — `greek` is one layout and
        // one language — but the same division of labour applies, so it is asserted the same
        // way rather than by inspection.
        assertThat(CtcLanguageSupport.isSupported("el")).isTrue()
    }

    @Test
    fun `unknown script routes geometric in ctc mode — never a dead end`() {
        assertThat(SwipeEngineRouter.route("Dvorak", null, Mode.CTC)).isEqualTo(Engine.GEOMETRIC)
        assertThat(SwipeEngineRouter.route(null, null, Mode.CTC)).isEqualTo(Engine.GEOMETRIC)
    }

    @Test
    fun `unknown name with latin script routes ctc in ctc mode — script decides`() {
        // The name only ever mattered for the removed neural engine's QWERTY predicate;
        // any known-Latin layout is CTC-eligible (dispatch-time supportsLayout guards a
        // letter-incomplete layout back to geometric).
        assertThat(SwipeEngineRouter.route(null, "latin", Mode.CTC)).isEqualTo(Engine.CTC)
    }

    // ── geometric mode: every layout, unconditionally ───────────────────────────────

    @Test
    fun `geometric mode routes geometric on every layout including qwerty`() {
        assertThat(SwipeEngineRouter.route("QWERTY (US)", "latin", Mode.GEOMETRIC))
            .isEqualTo(Engine.GEOMETRIC)
        assertThat(SwipeEngineRouter.route("Dvorak", "latin", Mode.GEOMETRIC))
            .isEqualTo(Engine.GEOMETRIC)
        assertThat(SwipeEngineRouter.route("ЙЦУКЕН", "cyrillic", Mode.GEOMETRIC))
            .isEqualTo(Engine.GEOMETRIC)
        assertThat(SwipeEngineRouter.route(null, null, Mode.GEOMETRIC))
            .isEqualTo(Engine.GEOMETRIC)
    }

    // ── Mode.fromPref parsing (the pref → enum seam the IME uses) ───────────────────

    @Test
    fun `fromPref parses the two surviving modes case-insensitively`() {
        assertThat(Mode.fromPref("geometric")).isEqualTo(Mode.GEOMETRIC)
        assertThat(Mode.fromPref("Geometric")).isEqualTo(Mode.GEOMETRIC)
        assertThat(Mode.fromPref("ctc")).isEqualTo(Mode.CTC)
        assertThat(Mode.fromPref("CTC")).isEqualTo(Mode.CTC)
    }

    @Test
    fun `fromPref maps the removed neural and hybrid modes onto the ctc default`() {
        // A settings backup taken before 2026-08-18 (or hand-edited JSON — import accepts
        // arbitrary strings, backup/SettingsValidation) can still carry these. They must
        // land on the default, never on a dead enum constant and never on an exception.
        assertThat(Mode.fromPref("neural")).isEqualTo(Mode.CTC)
        assertThat(Mode.fromPref("NEURAL")).isEqualTo(Mode.CTC)
        assertThat(Mode.fromPref("hybrid")).isEqualTo(Mode.CTC)
    }

    @Test
    fun `fromPref falls back to ctc on unknown or null values`() {
        assertThat(Mode.fromPref(null)).isEqualTo(Mode.CTC)
        assertThat(Mode.fromPref("")).isEqualTo(Mode.CTC)
        assertThat(Mode.fromPref("true")).isEqualTo(Mode.CTC) // legacy boolean-ish junk
    }
}
