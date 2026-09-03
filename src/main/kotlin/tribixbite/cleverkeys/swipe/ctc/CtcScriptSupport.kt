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
 * knowledge worth recording — but only rows at [Status.ROUTED] widen the router. As of
 * 2026-09-03 all six rows are ROUTED (ru 2026-08-29, el 2026-08-30, uk/bg/mk/he 2026-09-03 once
 * ARC-056 shipped their langpacks); [Status.INFRASTRUCTURE] remains the entry state any future
 * script starts at — wired far enough that landing it is a table edit plus assets, and no
 * further.
 *
 * ## Turkish is deliberately NOT a row here (decision recorded 2026-09-03, final)
 *
 * **tr stays on tap + geometric permanently.** It is a LATIN-script language, so it could only
 * ever reach CTC through the imported-pack path, and that path measures it out: dotless ı
 * (U+0131) has no NFD decomposition, so only **73.34 %** of the lexicon — and 81.7 % of the
 * thousand most frequent words (`nasıl`, `artık`, `mı`, `aynı`) — is a–z-projectable, far below
 * the 98 %/99 % eligibility thresholds ([CtcImportedPackSupport]'s measured table). A
 * tr-specific ı→i fold would be a SERVING-SEMANTICS change with no measured holdout evidence
 * behind it, so it is not an app-side option. No row exists because no tr model exists — a
 * [ScriptWiring.alphabet] IS a model's emission slot order, and a row without a model would be
 * fabricated data that also flipped [alphabetFor]/`presetFor`/the imported-pack refusal reason
 * for a language the table does not serve. Reopening condition: an ML-side tr-fold experiment
 * with holdout numbers, which would arrive as a per-script model + fixture and therefore as a
 * normal row.
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
            // Generation 4, sha 7083794c501566f411b1f81495ba1f7f3df273c3eb58f6ee635caf168a4f8c3d,
            // 589,406 B. Fixture sha d08d5501961e971db2ca120f6ee868b7b67ed37e34b6412dddbc7f7116de5753.
            // Greek has NO real-swipe probe at any tier; never quote the synthesis-holdout
            // fixture level as accuracy. The device parity/latency run is its only runtime bar.
            modelAsset = "models/el_synth_v3_ch80_fp16w.onnx",
            goldenFixture = "el_synth_v3_ch80_fp16w_golden.json",
            status = Status.ROUTED,
            gap = null,
        ),
        "uk" to ScriptWiring(
            language = "uk",
            script = "cyrillic",
            layoutXml = "cyrl_jcuken_uk.xml",
            alphabet = "абвгдежзийклмнопрстуфхцчшщьюяєі",
            // Generation 4, sha af9959a8954961eec117808371937cb26152c82a82cad0fc6a0ac06fd695db76,
            // 589,406 B. Fixture sha 93602db1200a3b37ef11570d4f4ee3afdad2a45b0ca4f857a784728cdbb5cc98.
            // Lexicon: `langpack-uk` (CKDT v2, 255−rank scale — shipped 2026-09-01, ARC-056).
            // The projection applies no folds; ї/ґ words are rejected as untypeable (4.03 % of
            // the vocabulary) — those live in corner slots, and serving them is a different
            // input mode (flick), not a projection change. uk has NO real-swipe probe at any
            // tier; never quote the synthesis-holdout fixture level as accuracy.
            modelAsset = "models/uk_synth_v3_ch80_fp16w.onnx",
            goldenFixture = "uk_synth_v3_ch80_fp16w_golden.json",
            status = Status.ROUTED,
            gap = null,
        ),
        "bg" to ScriptWiring(
            language = "bg",
            script = "cyrillic",
            layoutXml = "cyrl_ueishsht.xml",
            alphabet = "абвгдежзийклмнопрстуфхцчшщъьюя",
            // Generation 4, sha 119d42f70cc763336f9a86efdc5ae4f562ba4a28179c2d386026bef674c039a7,
            // 589,406 B. Fixture sha f776ea03ab675ff6b741a3297c4f88b11f7af2cb183ce7b2604f082ed8420b9d.
            // Lexicon: `langpack-bg` (CKDT v2, 255−rank scale — shipped 2026-09-01, ARC-056).
            // Projection: no NFD; ѝ→и. bg has NO real-swipe probe at any tier; never quote the
            // synthesis-holdout fixture level as accuracy.
            modelAsset = "models/bg_synth_v3_ch80_fp16w.onnx",
            goldenFixture = "bg_synth_v3_ch80_fp16w_golden.json",
            status = Status.ROUTED,
            gap = null,
        ),
        "mk" to ScriptWiring(
            language = "mk",
            script = "cyrillic",
            layoutXml = "cyrl_lynyertdz_mk.xml",
            alphabet = "абвгдежзиклмнопрстуфхцчшѓѕјљњќџ",
            // Generation 4, sha 4e371d967bf24f260eb539848ead7860f56dc904f6bfc74235879b76e81ae022,
            // 589,406 B. Fixture sha 015c9bae7e25a97b0ac8bd6062bb58376caaa3aca99c138d0d531ff1887e0ccf.
            // Lexicon: `langpack-mk` (CKDT v2, 255−rank scale — shipped 2026-09-01, ARC-056).
            // Projection: no NFD; ѐ→е, ѝ→и. mk has NO real-swipe probe at any tier; never quote
            // the synthesis-holdout fixture level as accuracy.
            modelAsset = "models/mk_synth_v3_ch80_fp16w.onnx",
            goldenFixture = "mk_synth_v3_ch80_fp16w_golden.json",
            status = Status.ROUTED,
            gap = null,
        ),
        "he" to ScriptWiring(
            language = "he",
            script = "hebrew",
            layoutXml = "hebr_1_il.xml",
            alphabet = "אבגדהוזחטיךכלםמןנסעףפץצקרשת",
            // Generation 4, sha a382371363653fbe7c806482035aa9e27968b9c098591910d24f9f1ba43212c7,
            // 589,406 B. Fixture sha b29a99f4ac2c4f82547d040131ea48771f2791817287de6e3f9ec52fc9758ad9.
            // Lexicon: `langpack-he` (CKDT v2, 255−rank scale — shipped 2026-09-01, ARC-056;
            // `build_wordlist._is_script_word`'s `hebrew` branch landed with it). Projection:
            // NFD → drop Mn → NFC; niqqud are not keys. he's old parity flag was a GENERATION-2
            // fact: generation 4 exports clean at the default tolerance (3.57e-04, argmax
            // 100/100). he has NO real-swipe probe at any tier; never quote the
            // synthesis-holdout fixture level as accuracy.
            modelAsset = "models/he_synth_v3_ch80_fp16w.onnx",
            goldenFixture = "he_synth_v3_ch80_fp16w_golden.json",
            status = Status.ROUTED,
            gap = null,
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
