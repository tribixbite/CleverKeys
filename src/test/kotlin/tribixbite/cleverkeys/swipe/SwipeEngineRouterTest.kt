package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
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
     * Cyrillic flipped when ru's artifacts landed; Greek has not, and asserting BOTH directions
     * here is the point: the router is driven by a table, so a script cannot be widened by
     * editing the router, and an unwired script cannot drift into CTC by accident.
     */
    @Test
    fun `a wired script routes ctc and an unwired one stays geometric`() {
        assertThat(SwipeEngineRouter.route("ЙЦУКЕН", "cyrillic", Mode.CTC))
            .isEqualTo(Engine.CTC)
        // The Greek QWERTY trap still holds, now for a different reason: script wins over the
        // QWERTY-shaped name, and `greek` has no ROUTED row (its model and fixture are not
        // shipped), so it stays on the geometric engine.
        assertThat(SwipeEngineRouter.route("QWERTY (Ελληνικά)", "greek", Mode.CTC))
            .isEqualTo(Engine.GEOMETRIC)
        // Scripts with no table row at all can never reach CTC.
        for (script in listOf("arabic", "hebrew", "devanagari", "hangul", "georgian")) {
            assertThat(SwipeEngineRouter.route("board", script, Mode.CTC))
                .isEqualTo(Engine.GEOMETRIC)
        }
    }

    /**
     * The router is layout-metadata-only, so a Cyrillic board routes CTC for EVERY language —
     * including uk/bg/mk, whose lexicons do not exist. That is correct and is not a leak: the
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
        // … but only ru is actually served.
        assertThat(CtcLanguageSupport.isSupported("ru")).isTrue()
        for (language in listOf("uk", "bg", "mk", "sr", "kk")) {
            assertThat(CtcLanguageSupport.isSupported(language)).isFalse()
        }
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
