package tribixbite.cleverkeys.swipe.ctc

/**
 * THE dynamic half of [CtcLanguageSupport]'s membership: whether an **imported Latin language
 * pack** may be served by the CTC swipe engine, and the measurement that decides it.
 *
 * Pure (no Android) like the rest of `swipe.ctc` — the device-side half (finding the file,
 * reading it, caching the verdict) lives in `swipe.CtcInstalledPacks`, which registers itself
 * here through [installResolver]. The split is the same one Wave I used for `ru`: the pure table
 * owns the POLICY and the relative path, the adapter layer owns the I/O.
 *
 * ## Why an imported Latin language may be served at all
 *
 * The same three-part argument that put `it`/`pt`/`sv` in [CtcLanguageSupport.PROVISIONAL] on
 * 2026-08-18, and it is not weaker here:
 *
 *  1. **The encoder never sees a language.** `models/ctc_swipe_encoder.onnx` emits a–z posteriors
 *     from key geometry and motor features; `keyEmbed` is a function of `(cx, cy)`. There is no
 *     language-conditioned parameter to be wrong about (see [CtcScriptSupport]'s KDoc for the one
 *     place where that stops being true — a different SCRIPT, which this path refuses).
 *  2. **λ calibrates to the LEXICON'S FREQUENCY SCALE, not the language**
 *     ([CtcScoringParams.presetFor]). An imported pack's `dictionary.bin` is the same CKDT v2
 *     container on the same `freq = max(1, 255 − rank)` scale as the bundled fr/de/es/it/pt/sv
 *     binaries — same reader ([tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader]), same
 *     inverted full-range 1–255 values. So the λ = 2.0 CKDT preset transfers to an imported pack
 *     for exactly the reason it transferred to Italian, and it transfers through the SAME code
 *     path: `presetFor` keys off [CtcLanguageSupport.sourceFor], which returns
 *     [CtcLanguageSupport.LexiconSource.CKDT_LANGPACK] here.
 *  3. **The alternative is not "wait for evidence", it is geometric**, which has no per-language
 *     bar either and which CTC beat by 15–22 points on every language where both were measured
 *     (test-2400: CTC 89.31 vs geometric 67.50; ARC-019's same-inputs head-to-head on 4,526 local
 *     traces: 90.7 vs 63.0 top-1). Withholding CTC from a Dutch user protects an evidence standard
 *     the fallback never met.
 *
 * **Evidence tier — say it exactly this way.** An imported-pack language is
 * [CtcLanguageSupport.PROVISIONAL] BY CONSTRUCTION and can never leave that tier: the pack is a
 * file the user brought, so there is no corpus, no bar, and not even a fixed vocabulary to measure
 * one against. No accuracy number may ever be quoted for one. What is claimed is narrower and
 * checkable: the decode runs at the preset its lexicon's scale was fitted for, and its vocabulary
 * is a–z-typeable on the board being swiped.
 *
 * ## The projectability check IS the script gate
 *
 * `LanguagePackManifest` carries **no script field** (`code`, `name`, `version`, `author`,
 * `wordCount`, `hasPrefixBoost` — that is the whole manifest), so "is this pack Latin?" cannot be
 * asked of the metadata. It does not need to be: the beam walks a trie over the 26 a–z emission
 * columns, so a word that has no a–z spelling ([CtcAzProjection.project] returns null for it) is
 * not merely mis-ranked, it is **absent from the trie and unswipeable**. Measuring that fraction
 * answers the script question and the usability question at once.
 *
 * Measured 2026-08-29 over every `scripts/dictionaries/langpack-*.zip` in the repo, applying
 * [CtcAzProjection.project] to each pack's whole canonical section (pinned by
 * `CtcImportedPackSupportTest.realLangpacksSplitCleanlyAcrossTheThreshold`):
 *
 * | pack | words | a–z-projectable | top-1,000 by rank | verdict |
 * |---|---|---|---|---|
 * | `nl` | 40,000 | **100.00 %** | 100.0 % | eligible |
 * | `id` | 28,637 | **100.00 %** | 100.0 % | eligible |
 * | `ms` | 25,861 | **100.00 %** | 100.0 % | eligible |
 * | `sw` | 20,000 | **100.00 %** | 100.0 % | eligible |
 * | `tl` | 27,922 | **100.00 %** | 100.0 % | eligible |
 * | `tr` | 40,000 | 73.34 % | 81.7 % | **rejected** |
 * | `ru` | 50,000 | 0.00 % | 0.0 % | **rejected** (Cyrillic — served by the script path instead) |
 * | `el` | 39,860 | 0.00 % | 0.0 % | **rejected** (Greek) |
 *
 * Turkish is the case that makes the check load-bearing rather than ceremonial, and it is not a
 * near miss: **ı (U+0131, dotless i) has no NFD decomposition**, so a quarter of the vocabulary —
 * and a sixth of the thousand most frequent words (`nasıl`, `artık`, `mı`, `aynı`) — has no a–z
 * spelling at all. Those words are typeable on the geometric engine, which decodes over the
 * board's real keys, so routing Turkish to CTC would be a REGRESSION on the most common words in
 * the language. The same is true of Polish `ł`, Vietnamese `đ` and Icelandic `þ`/`ð`; none of
 * those packs exists in this repo, and the check does not need to know their names to reject them.
 *
 * ## Thresholds, and why they are where they are
 *
 * [MIN_PROJECTABLE_RATIO] = 0.98 and [MIN_HEAD_PROJECTABLE_RATIO] = 0.99 over the top
 * [HEAD_WORDS]. Every real Latin pack above is at 1.00/1.00 and Turkish is at 0.73/0.82, so the
 * gap the thresholds sit in is 25 points wide (17 in the head) — they are not fitted to the
 * sample and moving either by a few points changes nothing about which pack passes. What the
 * 2 % slack buys is
 * tolerance for the stray non-Latin ENTRY that real word lists carry: the shipped
 * `langpack-en-opensubtitles-50k` holds 49 words spelled with a Greek omicron homoglyph (`yοu`,
 * `tο`), which is dirt in the data and not a statement about the language.
 *
 * The head check is separate because the overall ratio cannot see a gap that is CONCENTRATED in
 * common words: on a 100k-word pack the thousand most frequent words are 1 % of the file, so they
 * could be entirely unswipeable while the overall ratio still read 99 %. The head is what a user
 * actually swipes.
 *
 * [MIN_WORDS] = 1,000 is a POWER floor on the ratio, not a quality bar — with 20 words a 100 %
 * projectable rate means nothing, and a truncated or half-written `dictionary.bin` that happened
 * to parse would sail through. It is 20× below the smallest real pack in the repo (`sw`, 20,000),
 * so it rejects only degenerate files.
 *
 * ## What this check deliberately does NOT measure: word LENGTH
 *
 * A word can also be unswipeable by being too long — the emission head produces
 * [CtcDecodableLength.EMISSION_FRAMES] frames, and a word needing more has no alignment and is
 * silently unemittable. That is a second, independent way for a pack's vocabulary to be
 * unreachable, and it is not part of eligibility because it was MEASURED and found not to
 * happen: across all five serveable packs, **zero** words exceed the budget, and the worst case in
 * the language most likely to produce one — Dutch compounding — is `gemeenteraadsverkiezingen` at
 * 27 of 32 frames. A threshold on a quantity no real word list approaches would be machinery
 * pretending to be a check. `CtcImportedPackSupportTest.every serveable langpack fits the 32-frame
 * emission budget` holds that measurement; if it ever goes red, THAT is the evidence that the
 * length dimension needs its own gate.
 */
object CtcImportedPackSupport {

    /** Minimum entries before [evaluate]'s ratios are treated as evidence. See the class KDoc. */
    const val MIN_WORDS: Int = 1_000

    /** How many of the most frequent words the head check reads. See the class KDoc. */
    const val HEAD_WORDS: Int = 1_000

    /** Fraction of the WHOLE lexicon that must have an a–z spelling. See the class KDoc. */
    const val MIN_PROJECTABLE_RATIO: Double = 0.98

    /** Fraction of the [HEAD_WORDS] most frequent words that must have one. See the class KDoc. */
    const val MIN_HEAD_PROJECTABLE_RATIO: Double = 0.99

    /** Why a pack is, or is not, served. */
    enum class Verdict {
        /** Serveable: enough words, and enough of them — head and tail — project onto a–z. */
        ELIGIBLE,

        /**
         * The language is not a candidate for this path at all: it is `en` (whose λ was fitted on
         * the JSON asset's compressed byte scale, not the pack's inverted one), or it has a
         * [CtcScriptSupport] row and must be served through the SCRIPT path with its own encoder,
         * or the code is blank. Decided by [mayServeImportedPack] without reading anything.
         */
        NOT_AN_IMPORT_CANDIDATE,

        /** Fewer than [MIN_WORDS] entries — the ratios below would not be evidence. */
        TOO_FEW_WORDS,

        /** Below [MIN_PROJECTABLE_RATIO]: too much of the vocabulary has no a–z spelling. */
        NOT_AZ_PROJECTABLE,

        /**
         * Below [MIN_HEAD_PROJECTABLE_RATIO] on the [HEAD_WORDS] most frequent words even though
         * the whole-lexicon ratio passed — the gap is concentrated where it hurts most.
         */
        HEAD_NOT_AZ_PROJECTABLE,
    }

    /**
     * One pack's measurement. Counts are kept rather than just the ratios so a rejection can be
     * reported to the user in the units they can act on ("73 % of this pack's words") and so a
     * changed threshold can be re-applied to a stored verdict without re-reading the file.
     *
     * @property code normalized language code.
     * @property words entries in the pack's canonical section.
     * @property projectable how many of them [CtcAzProjection.project] gives an a–z surface.
     * @property headWords `min(`[HEAD_WORDS]`, words)` — the frequency head actually examined.
     * @property headProjectable how many of those project.
     */
    class Report(
        val code: String,
        val words: Int,
        val projectable: Int,
        val headWords: Int,
        val headProjectable: Int,
        val verdict: Verdict,
    ) {
        /** True when the CTC engine may serve this pack. */
        val eligible: Boolean get() = verdict == Verdict.ELIGIBLE

        /** Fraction of the whole lexicon with an a–z spelling; 0.0 for an empty pack. */
        val projectableRatio: Double
            get() = if (words <= 0) 0.0 else projectable.toDouble() / words

        /** Fraction of the frequency head with an a–z spelling; 0.0 when the head is empty. */
        val headProjectableRatio: Double
            get() = if (headWords <= 0) 0.0 else headProjectable.toDouble() / headWords

        /**
         * [projectableRatio] as a whole percent, rounded DOWN so a pack that is 99.6 % typeable is
         * never reported to the user as "100 %" while it is being refused.
         */
        val projectablePercent: Int get() = (projectableRatio * 100).toInt()

        /** Field-separated form for the device-side cache — see [encodeVerdicts]. */
        fun encode(): String = "${verdict.name}|$words|$projectable|$headWords|$headProjectable"

        override fun toString(): String =
            "$code: $verdict ($projectable/$words a–z-projectable, head $headProjectable/$headWords)"
    }

    /**
     * A [Report] together with the identity of the file it was measured on, so a REIMPORT
     * invalidates it. See [packFingerprint].
     */
    class CachedVerdict(val fingerprint: String, val report: Report)

    /**
     * The device-side seam. Implemented by `swipe.CtcInstalledPacks` and registered through
     * [installResolver]; absent (and therefore always false) in pure tests that do not install
     * one, which is what keeps `swipe.ctc` free of Android.
     */
    fun interface InstalledPackResolver {
        /**
         * True when [code]'s language pack is installed AND its stored verdict for the pack file
         * currently on disk is [Verdict.ELIGIBLE].
         *
         * Called on the swipe dispatch path, so it must be cheap: a cache read plus a `stat`.
         * An unmeasured pack answers FALSE (the swipe goes to geometric) and the measurement is
         * taken in the background — never on the caller's thread.
         */
        fun servesImportedPack(code: String): Boolean
    }

    @Volatile
    private var resolver: InstalledPackResolver? = null

    /**
     * Registers (or with null, clears) the device-side resolver. Called from the app layer —
     * `CleverKeysService.onCreate`, `SettingsActivity.onCreate` and `CtcEngineAdapter`'s
     * constructor, all pinned by `CoreImeHygieneDriftTest` — and by pure tests, which MUST clear
     * it again in `@After` because this is process-global state.
     */
    fun installResolver(resolver: InstalledPackResolver?) {
        this.resolver = resolver
    }

    /** The registered resolver, or null. Exposed so a test can assert it was cleared. */
    fun installedResolver(): InstalledPackResolver? = resolver

    /**
     * Static exclusions, decided without reading anything:
     *
     *  * a blank code matches nothing;
     *  * **`en` is never served from a pack.** An installed en pack stores the inverted
     *    `255 − rank` scale while en's tuned λ = 4.0 was fitted on `en_enhanced.json`'s compressed
     *    134–255 byte scores — same container, wrong scale (see
     *    [CtcLanguageSupport.LexiconSource.CKDT_LANGPACK]);
     *  * **a language with a [CtcScriptSupport] row is never served from this path.** Those need
     *    their own per-script encoder, trie and golden fixture (HANDOFF rule 4); admitting one
     *    here would decode it against the LATIN emission head. `ru` reaches
     *    [CtcLanguageSupport.LexiconSource.CKDT_LANGPACK] through the static table instead, which
     *    is why this exclusion changes nothing for it.
     *
     * Languages already in [CtcLanguageSupport.SUPPORTED] are excluded by LOOKUP ORDER rather
     * than here: [CtcLanguageSupport.sourceFor] returns the static row first, so an imported
     * `fr` pack never displaces the bundled `fr_enhanced.bin` the tuned decode was validated on.
     */
    fun mayServeImportedPack(language: String?): Boolean {
        val code = CtcLanguageSupport.normalize(language)
        if (code.isEmpty()) return false
        if (code == "en") return false
        return CtcScriptSupport.wiringFor(code) == null
    }

    /**
     * True when [language] is served by an installed, measured-eligible imported pack.
     *
     * [CtcLanguageSupport.sourceFor] calls this AFTER missing the static table, so this is the
     * only place dynamic membership is decided and every gate that already goes through
     * `sourceFor`/`isSupported` — the dispatcher, the prewarm, `presetFor`, the settings card —
     * inherits it without a second code path.
     */
    fun servesImportedPack(language: String?): Boolean {
        if (!mayServeImportedPack(language)) return false
        val code = CtcLanguageSupport.normalize(language)
        return resolver?.servesImportedPack(code) == true
    }

    /**
     * Identity of a pack file on disk: length plus last-modified, the same convention
     * `GeometricEngineAdapter` uses and the same one `CtcEngineAdapter.lexiconFor` folds into its
     * trie content-hash. A reimport rewrites the file (`LanguagePackManager` deletes the pack dir
     * and copies fresh), so its mtime moves even when an identical dictionary is reimported —
     * which invalidates BOTH the eligibility verdict and the built trie, as it must.
     */
    fun packFingerprint(lengthBytes: Long, lastModifiedMs: Long): String =
        "$lengthBytes:$lastModifiedMs"

    /**
     * Measures [wordsMostFrequentFirst] — a pack's canonical section in RANK ORDER, which is what
     * `CkdtDictionaryReader.readEntries` returns — against the thresholds in the class KDoc.
     *
     * Pure and O(total characters): the expensive part is [CtcAzProjection.project] per word, so
     * callers run it off the main thread and cache the [Report].
     */
    fun evaluate(language: String?, wordsMostFrequentFirst: List<String>): Report {
        val code = CtcLanguageSupport.normalize(language)
        val words = wordsMostFrequentFirst.size
        val headWords = minOf(HEAD_WORDS, words)
        var projectable = 0
        var headProjectable = 0
        for (i in 0 until words) {
            if (CtcAzProjection.project(wordsMostFrequentFirst[i]) == null) continue
            projectable++
            if (i < headWords) headProjectable++
        }
        val report = { verdict: Verdict ->
            Report(code, words, projectable, headWords, headProjectable, verdict)
        }
        if (!mayServeImportedPack(code)) return report(Verdict.NOT_AN_IMPORT_CANDIDATE)
        if (words < MIN_WORDS) return report(Verdict.TOO_FEW_WORDS)
        if (projectable.toDouble() / words < MIN_PROJECTABLE_RATIO) {
            return report(Verdict.NOT_AZ_PROJECTABLE)
        }
        if (headProjectable.toDouble() / headWords < MIN_HEAD_PROJECTABLE_RATIO) {
            return report(Verdict.HEAD_NOT_AZ_PROJECTABLE)
        }
        return report(Verdict.ELIGIBLE)
    }

    // ── Verdict cache serialization (pure, so the format is unit-pinned) ────────────────
    //
    // One preference key holds every pack's verdict, rather than a key per language: the
    // export/import filter (`SettingsValidation.INTERNAL_KEYS`) matches keys EXACTLY, and a
    // per-language key family would leak device-local derived state into every settings backup.

    /** Line-per-pack, `code|fingerprint|verdict|words|projectable|headWords|headProjectable`. */
    fun encodeVerdicts(verdicts: Map<String, CachedVerdict>): String =
        verdicts.entries
            .sortedBy { it.key }
            .joinToString("\n") { (code, cached) ->
                "$code|${cached.fingerprint}|${cached.report.encode()}"
            }

    /**
     * Inverse of [encodeVerdicts]. Malformed or unknown-verdict lines are DROPPED rather than
     * throwing: the value is a derived cache, so the only correct response to a version skew or a
     * truncated write is to re-measure the pack.
     */
    fun decodeVerdicts(encoded: String?): Map<String, CachedVerdict> {
        if (encoded.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, CachedVerdict>()
        for (line in encoded.split('\n')) {
            if (line.isBlank()) continue
            val f = line.split('|')
            if (f.size != 7) continue
            val code = CtcLanguageSupport.normalize(f[0])
            if (code.isEmpty()) continue
            val verdict = Verdict.entries.firstOrNull { it.name == f[2] } ?: continue
            val words = f[3].toIntOrNull() ?: continue
            val projectable = f[4].toIntOrNull() ?: continue
            val headWords = f[5].toIntOrNull() ?: continue
            val headProjectable = f[6].toIntOrNull() ?: continue
            if (words < 0 || projectable < 0 || headWords < 0 || headProjectable < 0) continue
            out[code] = CachedVerdict(
                f[1],
                Report(code, words, projectable, headWords, headProjectable, verdict),
            )
        }
        return out
    }
}
