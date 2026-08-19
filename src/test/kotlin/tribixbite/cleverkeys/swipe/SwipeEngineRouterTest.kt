package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.swipe.SwipeEngineRouter.Engine
import tribixbite.cleverkeys.swipe.SwipeEngineRouter.Mode

/**
 * WP9 R-1 step 9 (JVM leg) — routing table for the mode-based [SwipeEngineRouter] (v1.2:
 * the neural engine and its `neural`/`hybrid` modes were removed 2026-08-18).
 *
 * Routing is (mode, layout)-based: CTC (default) = CTC trie-beam on ANY Latin-script layout
 * + geometric on non-Latin/unknown scripts; GEOMETRIC = geometric on ALL layouts. The
 * router is TOTAL — there is no "no engine" outcome, so no layout can lose swipe.
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

    @Test
    fun `cyrillic and greek route geometric in ctc mode`() {
        assertThat(SwipeEngineRouter.route("ЙЦУКЕН", "cyrillic", Mode.CTC))
            .isEqualTo(Engine.GEOMETRIC)
        // The Greek QWERTY trap: script wins over the QWERTY-shaped name.
        assertThat(SwipeEngineRouter.route("QWERTY (Ελληνικά)", "greek", Mode.CTC))
            .isEqualTo(Engine.GEOMETRIC)
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
