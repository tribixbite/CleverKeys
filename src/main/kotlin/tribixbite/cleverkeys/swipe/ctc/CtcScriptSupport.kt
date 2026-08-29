package tribixbite.cleverkeys.swipe.ctc

import java.util.Locale

/**
 * THE per-script wiring table for the CTC swipe engine — one row per non-Latin script the
 * campaign produced artifacts for, and the single place that decides whether a script may be
 * routed to CTC at all.
 *
 * Pure (no Android) so `runPureTests`, the router and the adapter all read the same source of
 * truth. [CtcLanguageSupport] stays the LANGUAGE table (which lexicon, which λ scale); this is
 * the SCRIPT table (which alphabet, which model, which fixture, and what is still missing).
 *
 * ## The rule this table exists to enforce
 *
 * `memory/HANDOFF.md` rule 4, restated in the guide §7.4:
 *
 * > Never route a non-Latin script to CTC without all three of a per-script model, a per-script
 * > trie on the app's own lexicon at the app's own frequency scale, and a golden fixture at the
 * > preset that will actually ship. **Two of three is a silently wrong decode, not a partial
 * > feature.**
 *
 * So a row exists for all six scripts — the alphabet, the layout and the artifact names are
 * knowledge worth recording — but only rows at [Status.ROUTED] widen the router. Everything
 * else is [Status.INFRASTRUCTURE]: wired far enough that landing the script is a table edit plus
 * assets, and no further.
 *
 * ## The sharpest footgun in the whole plan
 *
 * **The model's emission slot order IS [ScriptWiring.alphabet].** Every layout JSON the models
 * were trained against lists its letters in **codepoint-sorted** order, and emission column `c`
 * is `letters[c]`. `keyEmbed` is a function of `(cx, cy)` and never of the slot index, so a
 * permuted alphabet is geometrically self-consistent: it does not throw, it **silently permutes
 * every decode**. The strings below are copied character-for-character from
 * `CleverKeys-ML/ctc/APP_WIRING_CHECKLIST.md` §2.2 and are pinned against the shipped golden
 * fixtures' own `layout.letters` field by `CtcScriptAlphabetTest` for every script whose fixture
 * ships.
 *
 * ## Evidence tier — read before quoting any number for these scripts
 *
 * Only **ru** has a real-swipe probe, it is **val-only permanently** (Yandex valid-10k is
 * eval-only by licence — HANDOFF rule 1), and its number may never be called "test-validated".
 * el/uk/bg/mk/he have synthesis-holdout numbers ONLY, which measure fit to the generator's own
 * distribution; the campaign showed three separate times that this probe does not rank what real
 * swipes rank. Quote margins against the fixed control, never levels — "el 92.12" is not "Greek
 * at 92.12" (guide §7.5, checklist §4.7).
 */
object CtcScriptSupport {

    /** How far a script's wiring has got. Only [ROUTED] reaches the router. */
    enum class Status {
        /**
         * All three of rule 4 are present in the APK or on the device, and the script's layouts
         * route to CTC (subject to the language, layout-alphabet and model gates downstream).
         */
        ROUTED,

        /**
         * The alphabet, layout and artifact names are recorded and the generic machinery
         * (projection, per-script layout build, per-language model asset, preset) handles the
         * script — but at least one of rule 4's three is missing, so the script stays on the
         * geometric engine. [ScriptWiring.gap] says exactly what is missing.
         */
        INFRASTRUCTURE,
    }

    /**
     * One script's complete wiring record.
     *
     * @property language the language code the row is keyed by ([CtcLanguageSupport] normalizes
     *   region subtags away before lookup).
     * @property script the `script="…"` attribute value on the layout XML — the router's gate 1.
     * @property layoutXml the shipped layout under `src/main/layouts/` (NOT `srcs/layouts/`,
     *   which no build task reads — guide §7.8).
     * @property alphabet emission slot order, codepoint-sorted. See the class KDoc.
     * @property modelAsset the per-language ONNX under `src/main/assets/`, or null when the
     *   bytes are not shipped. All six graphs are 589,406 B fp16w and byte-size-identical, so
     *   only the sha256 in the golden fixture can tell them apart.
     * @property goldenFixture the golden fixture basename under `src/test/resources/ctc/` and
     *   `src/androidTest/assets/ctc/` (two byte-identical copies), or null when not shipped.
     * @property status see [Status].
     * @property gap null when [status] is [Status.ROUTED]; otherwise exactly what is missing and
     *   what would unblock it.
     */
    class ScriptWiring(
        val language: String,
        val script: String,
        val layoutXml: String,
        val alphabet: String,
        val modelAsset: String?,
        val goldenFixture: String?,
        val status: Status,
        val gap: String?,
    ) {
        init {
            require(alphabet.isNotEmpty()) { "$language: alphabet must not be empty" }
            require(alphabet.toSortedSet().size == alphabet.length) {
                "$language: alphabet has duplicate characters — emission columns are one-to-one"
            }
            require(alphabet.toList() == alphabet.toList().sorted()) {
                "$language: alphabet must be CODEPOINT-SORTED; the model's slot order is this " +
                    "string and a permutation silently permutes every decode"
            }
            require((status == Status.ROUTED) == (gap == null)) {
                "$language: a ROUTED script has no gap, and a gap means it is not ROUTED"
            }
            require(status != Status.ROUTED || (modelAsset != null && goldenFixture != null)) {
                "$language: rule 4 — a ROUTED script needs both its model and its fixture"
            }
        }

        /** Emission slot order as the array [CtcLayout]/[CtcLexiconTrie] take. */
        fun alphabetChars(): CharArray = alphabet.toCharArray()
    }

    /** The Latin emission alphabet — a–z, the shipped English encoder's training order. */
    val LATIN_ALPHABET: CharArray = CharArray(26) { ('a' + it) }

    /** Layout `script` attribute value for the Latin family. */
    const val LATIN_SCRIPT = "latin"

    /**
     * Per-language script wiring, keyed by language code.
     *
     * Generation 4 artifacts (`*_synth_v3_ch80*`, uniform across all six scripts) are the ONLY
     * deployable ones — every `*_synth_ch80*`, `*_synth_v2_ch80*` and `*_synth_v2full_ch80*` in
     * `CleverKeys-ML/ctc/artifacts/` is superseded and kept only because published numbers were
     * measured on it. **If a file is not in `ctc/artifacts/`, it is not wirable** (checklist §4:
     * the sealed `RESEARCH_ONLY` track produced one number and no bytes).
     */
    val SCRIPTS: Map<String, ScriptWiring> = linkedMapOf(
        "ru" to ScriptWiring(
            language = "ru",
            script = "cyrillic",
            layoutXml = "cyrl_jcuken_ru.xml",
            // K = 31. The layout's 31 `key0` letters exactly: ё and ъ are CORNER values
            // (`key1` on е and ь) and `KeyboardGeometry.computeKeyRects` only emits `keys[0]`,
            // so they never become emission slots. The projection folds them away instead.
            alphabet = "абвгдежзийклмнопрстуфхцчшщыьэюя",
            // Generation 4, sha 8fffa75c722eb61e9e8c80d919fbca3e73eb698ebe3e3909cb766b3b8489962c,
            // 589,406 B. Fixture sha 2e8de3c5a15e5874366f44f725aeec2eb72befd89b503d4b24b8b4a8d82fdde5.
            modelAsset = "models/ru_synth_v3_ch80_fp16w.onnx",
            goldenFixture = "ru_synth_v3_ch80_fp16w_golden.json",
            status = Status.ROUTED,
            gap = null,
        ),
        "el" to ScriptWiring(
            language = "el",
            script = "greek",
            layoutXml = "grek_qwerty.xml",
            // K = 25, and ς (U+03C2) is its OWN slot, in a different row from σ (U+03C3).
            alphabet = "αβγδεζηθικλμνξοπρςστυφχψω",
            modelAsset = null,
            goldenFixture = null,
            status = Status.INFRASTRUCTURE,
            gap = "el_synth_v3_ch80_fp16w.onnx (sha 7083794c…) and its golden fixture " +
                "(sha d08d5501…) are not shipped. Everything else is ready: grek_qwerty.xml " +
                "exposes all 25 letters as centre keys, langpack-el.zip exists on the same " +
                "CKDT 255−rank scale, and BOTH halves of the el projection are implemented " +
                "and unit-tested (CtcScriptProjection: NFD → drop Mn → NFC, then word-final " +
                "σ→ς via CtcGreekOrthography). Unblocking condition: ship the two artifacts, " +
                "flip this row to ROUTED, add el to CtcLanguageSupport.SUPPORTED, and run the " +
                "ew-cli parity + latency gate — Greek has NO real-swipe probe at any tier, so " +
                "the on-device run is the only evidence that will ever exist for it.",
        ),
        "uk" to ScriptWiring(
            language = "uk",
            script = "cyrillic",
            layoutXml = "cyrl_jcuken_uk.xml",
            alphabet = "абвгдежзийклмнопрстуфхцчшщьюяєі",
            modelAsset = null,
            goldenFixture = null,
            status = Status.INFRASTRUCTURE,
            gap = "no lexicon exists. uk must be built ML-side (`build_wordlist.py --lang uk`; " +
                "the `cyrillic` script gate already exists) and packaged as a CKDT v2 langpack " +
                "on the app's 255−rank scale. uk_synth_v3_ch80_fp16w.onnx (sha af9959a8…) and " +
                "its fixture (sha 93602db1…) are also unshipped. The projection is implemented " +
                "(no folds; ї/ґ words rejected as untypeable — 4.03 % of the vocabulary; " +
                "serving them needs the corner-alias path, a different input mode).",
        ),
        "bg" to ScriptWiring(
            language = "bg",
            script = "cyrillic",
            layoutXml = "cyrl_ueishsht.xml",
            alphabet = "абвгдежзийклмнопрстуфхцчшщъьюя",
            modelAsset = null,
            goldenFixture = null,
            status = Status.INFRASTRUCTURE,
            gap = "no lexicon exists — must be built ML-side. " +
                "bg_synth_v3_ch80_fp16w.onnx (sha 119d42f7…) and its fixture (sha f776ea03…) " +
                "are unshipped. The projection is implemented (no NFD; ѝ→и).",
        ),
        "mk" to ScriptWiring(
            language = "mk",
            script = "cyrillic",
            layoutXml = "cyrl_lynyertdz_mk.xml",
            alphabet = "абвгдежзиклмнопрстуфхцчшѓѕјљњќџ",
            modelAsset = null,
            goldenFixture = null,
            status = Status.INFRASTRUCTURE,
            gap = "no lexicon exists — must be built ML-side. " +
                "mk_synth_v3_ch80_fp16w.onnx (sha 4e371d96…) and its fixture (sha 015c9bae…) " +
                "are unshipped. The projection is implemented (no NFD; ѐ→е, ѝ→и).",
        ),
        "he" to ScriptWiring(
            language = "he",
            script = "hebrew",
            layoutXml = "hebr_1_il.xml",
            alphabet = "אבגדהוזחטיךכלםמןנסעףפץצקרשת",
            modelAsset = null,
            goldenFixture = null,
            status = Status.INFRASTRUCTURE,
            gap = "no lexicon exists, and `build_wordlist._is_script_word` needs a new " +
                "`hebrew` branch (0x0590–0x05FF) — it currently raises on any script but " +
                "latin/greek/cyrillic. he_synth_v3_ch80_fp16w.onnx (sha a3823713…) and its " +
                "fixture (sha b29a99f4…) are unshipped. The projection is implemented (NFD → " +
                "drop Mn → NFC; niqqud are not keys). he's old parity flag is a GENERATION-2 " +
                "fact and is not a reason to hold he back: generation 4 exports clean at the " +
                "default tolerance (3.57e-04, argmax 100/100).",
        ),
    )

    /** The script wiring for [language], or null when it is Latin/unknown. */
    fun wiringFor(language: String?): ScriptWiring? = SCRIPTS[CtcLanguageSupport.normalize(language)]

    /**
     * The emission alphabet for [language] — the per-script string when one exists, a–z
     * otherwise. This is what [CtcLayout], [CtcLexiconTrie] and the projection are all built
     * over; see the class KDoc for why its ORDER is load-bearing.
     */
    fun alphabetFor(language: String?): CharArray =
        wiringFor(language)?.alphabetChars() ?: LATIN_ALPHABET.copyOf()

    /**
     * The per-language ONNX encoder asset. Latin languages share the shipped English encoder
     * (it is layout-agnostic — key geometry is a model input); a script language loads its own.
     *
     * @param defaultAsset the shipped Latin encoder, passed in so the asset constant keeps
     *   living next to the adapter that loads it.
     */
    fun modelAssetFor(language: String?, defaultAsset: String): String =
        wiringFor(language)?.modelAsset ?: defaultAsset

    /** Layout `script` values the router may send to CTC: Latin plus every [Status.ROUTED] row. */
    val ROUTABLE_SCRIPTS: Set<String> =
        SCRIPTS.values.filter { it.status == Status.ROUTED }.map { it.script }.toSet() +
            LATIN_SCRIPT

    /**
     * Router gate 1: may a layout declaring [script] reach the CTC engine at all?
     *
     * True for Latin and for every script with a [Status.ROUTED] row. This is layout METADATA
     * only — the language gate ([CtcLanguageSupport.isSupported]), the layout-alphabet gate
     * (`CtcEngineAdapter.supportsLayout`) and the model gate all still apply at dispatch, and a
     * script layout whose language CTC does not serve (a Ukrainian ЙЦУКЕН, say) falls through to
     * the geometric engine there.
     */
    fun isRoutableScript(script: String?): Boolean =
        script != null && script.trim().lowercase(Locale.ROOT) in ROUTABLE_SCRIPTS
}
