/**
 * SwipeVocabulary - Optimized vocabulary filtering for swipe gesture predictions
 *
 * This class provides ultra-fast word validation and ranking for neural network
 * swipe gesture outputs, using frequency data and intelligent caching.
 *
 * Ranking is a faithful port of the production Android pipeline
 * (OptimizedVocabulary.kt filterPredictions) using the SHIPPED config
 * defaults, so the web demo reranks beam outputs exactly like the APK.
 */

// Production parity constants — values are the v1.5.x SHIPPED defaults:
// Config.kt Defaults + OptimizedVocabulary.kt (see comments per key).
const PROD_FILTER = {
    CONFIDENCE_WEIGHT: 0.8,     // swipe_prediction_source=80 → conf 0.8 / freq 0.2 (Config.kt:198,848-849)
    FREQUENCY_WEIGHT: 0.2,
    NEURAL_FREQ_WEIGHT: 0.57,   // Defaults.NEURAL_FREQUENCY_WEIGHT (Config.kt:151) — multiplies FREQUENCY_WEIGHT
    COMMON_BOOST: 1.0,          // Defaults.SWIPE_COMMON_WORDS_BOOST (Config.kt:199) — shipped 1.0 overrides internal 1.3
    TOP5000_BOOST: 1.0,         // Defaults.SWIPE_TOP5000_BOOST (Config.kt:200)
    RARE_PENALTY: 1.0,          // Defaults.SWIPE_RARE_WORDS_PENALTY (Config.kt:201) — shipped 1.0 overrides internal 0.75
    CONFIG_MIN_FREQ: 100 / 10000.0, // AUTOCORRECT_MIN_FREQUENCY=100 normalized /10000 (OptimizedVocabulary.kt:487-489)
    TIER2_RANK: 100,            // sorted rank < 100  → tier 2 "common"  (OptimizedVocabulary.kt:1011-1012)
    TIER1_RANK: 3000,           // sorted rank < 3000 → tier 1 "top3000" (OptimizedVocabulary.kt:1013-1014)
    // Hardcoded per-length minimum frequency floor (OptimizedVocabulary.kt:1081-1091); 9+ → 1e-9
    MIN_FREQ_BY_LENGTH: { 1: 1e-4, 2: 1e-5, 3: 1e-6, 4: 1e-6, 5: 1e-7, 6: 1e-7, 7: 1e-8, 8: 1e-8 },
    CONTRACTION_ALIAS_FREQ: 0.88, // contraction alias fallback WordInfo(0.88f, tier 2) (OptimizedVocabulary.kt:444-449)
    // Lower clamp of the production frequency scale: OptimizedVocabulary.kt:1004-1006
    // clamps (byteScore-128)/127 to >= 0.001, so every production frequency
    // lives in [0.001, 1.0].
    FREQ_FLOOR: 0.001,
};

// Tap-typing helper pools (F2). These are DEDICATED to the tap
// completion/fuzzy helpers — they must never be conflated with `commonWords` /
// `top5000`, which carry production TIER RANKS (100 / 3000) for the filter.
const TAP_COMPLETION_POOL_SIZE = 20000; // prefix scan, freq-desc
const TAP_FUZZY_POOL_SIZE = 2000;       // Levenshtein scan, freq-desc

// Shared immutable empty set for missing trie prefixes (avoids per-call alloc).
const EMPTY_SET = new Set();

class SwipeVocabulary {
    constructor() {
        this.wordFreq = new Map();
        this.commonWords = new Set();
        this.wordsByLength = new Map();
        this.top5000 = new Set();
        this.keyboardAdjacency = null;
        this.minFreqByLength = null;
        this.isLoaded = false;
        // Tier map (word → 0|1|2) mirroring OptimizedVocabulary's WordInfo.tier.
        // Words added AFTER load (custom/personal) have no entry and default to
        // tier 1, matching production's user-word handling (no rare-word gate).
        this.tiers = new Map();
        // Contraction alias map (apostrophe-free → contraction), set via
        // setContractions(). Enables the production alias fallback path.
        this.contractions = null;
        // Lazily-built prefix trie mirroring VocabularyTrie.kt — used by the
        // demo's beam search for logit masking (BeamSearchEngine.applyTrieMasking).
        this.trieRoot = null;
        // Which scale `wordFreq` values live on:
        //   'normalized'  — production scale, [0.001, 1.0] (byte score
        //                   (raw-128)/127, or a dict mapped onto it at load)
        //   'probability' — raw corpus probabilities (sum ~= 1 over the dict)
        // The APK's configured min-frequency floor is only meaningful on the
        // normalized scale, so filterPredictions gates on this. Default is the
        // conservative one: callers that populate `wordFreq` directly (the
        // demo's vocabulary_words.txt fallback) get no APK-scale floor applied.
        this.freqScale = 'probability';
        // Diagnostics for the probability → production-scale mapping (or null
        // when no mapping was needed). Exposed for the headless smoke harness.
        this.freqScaleInfo = null;
        // Dedicated tap-typing pools (see ensureTapPools) — built lazily,
        // invalidated whenever the vocabulary changes.
        this.tapCompletionPool = null;
        this.tapFuzzyPool = null;
    }

    /**
     * Provide the contraction alias map ({dont: "don't", ...}). Mirrors the
     * production behavior where contraction alias keys are added to the
     * vocabulary/trie for prediction (OptimizedVocabulary contraction load).
     */
    setContractions(map) {
        this.contractions = map || null;
        if (this.trieRoot && map) {
            for (const alias of Object.keys(map)) {
                if (/^[a-z]+$/.test(alias)) this.insertWordIntoTrie(alias);
            }
        }
    }

    /**
     * Build (once) the prefix trie over all vocabulary words + contraction
     * aliases. Node shape: plain object of child chars, with `$` marking
     * end-of-word — the exact lookups BeamSearchEngine.applyTrieMasking needs.
     */
    ensureTrie() {
        if (this.trieRoot) return this.trieRoot;
        const t0 = (typeof performance !== 'undefined') ? performance.now() : Date.now();
        this.trieRoot = {};
        for (const word of this.wordFreq.keys()) {
            this.insertWordIntoTrie(word);
        }
        if (this.contractions) {
            for (const alias of Object.keys(this.contractions)) {
                if (/^[a-z]+$/.test(alias)) this.insertWordIntoTrie(alias);
            }
        }
        const t1 = (typeof performance !== 'undefined') ? performance.now() : Date.now();
        console.log(`Vocabulary trie built: ${this.wordFreq.size} words in ${Math.round(t1 - t0)}ms`);
        return this.trieRoot;
    }

    /** Insert a single (lowercase) word into the trie if it exists. */
    insertWordIntoTrie(word) {
        if (!this.trieRoot || !word) return;
        let node = this.trieRoot;
        for (const ch of word.toLowerCase()) {
            node = node[ch] || (node[ch] = {});
        }
        node.$ = true;
    }

    /**
     * Remove a word from the masking trie, pruning nodes that become empty.
     *
     * The trie is the union of `wordFreq` ∪ contraction aliases, so a word that
     * is still reachable from either source is NEVER unmapped — that guard
     * lives here rather than at the call sites so a caller cannot get it wrong.
     * Removing a personal word therefore has to delete it from `wordFreq`
     * first (CustomDictionaryManager.unboostWord does exactly that).
     *
     * @param {string} word
     * @returns {boolean} true when the word was actually removed
     */
    removeWordFromTrie(word) {
        if (!this.trieRoot || !word) return false;
        const lower = word.toLowerCase();
        if (this.wordFreq.has(lower)) return false;
        if (this.contractions &&
            Object.prototype.hasOwnProperty.call(this.contractions, lower)) {
            return false;
        }

        // Walk down, recording the node chain so empty nodes can be pruned.
        const chain = [this.trieRoot];
        let node = this.trieRoot;
        for (const ch of lower) {
            node = node[ch];
            if (!node) return false;
            chain.push(node);
        }
        if (node.$ !== true) return false;
        delete node.$;

        // Prune bottom-up while a node has neither children nor an end marker.
        for (let i = chain.length - 1; i >= 1; i--) {
            if (Object.keys(chain[i]).length > 0) break;
            delete chain[i - 1][lower[i - 1]];
        }
        return true;
    }

    /**
     * Build the dedicated tap-typing pools: the top-N of `wordFreq` by
     * frequency, freq-descending.
     *
     * These exist so the tap helpers stop borrowing `commonWords` / `top5000`,
     * which are TIER-RANK sets (100 / 3000 entries, production parity) and far
     * too small to complete from.
     */
    ensureTapPools() {
        if (this.tapCompletionPool) return;
        const sorted = Array.from(this.wordFreq.entries())
            .sort((a, b) => b[1] - a[1])
            .map((e) => e[0]);
        this.tapCompletionPool = sorted.slice(0, TAP_COMPLETION_POOL_SIZE);
        this.tapFuzzyPool = sorted.slice(0, TAP_FUZZY_POOL_SIZE);
    }

    /** Drop the tap pools so the next tap rebuilds them (vocabulary changed). */
    invalidateTapPools() {
        this.tapCompletionPool = null;
        this.tapFuzzyPool = null;
    }

    /**
     * VocabularyTrie.getAllowedNextChars — set of child chars of the prefix
     * node (empty set if the prefix is not in the trie).
     */
    getAllowedNextChars(prefix) {
        let node = this.ensureTrie();
        for (const ch of prefix) {
            node = node[ch];
            if (!node) return EMPTY_SET;
        }
        const allowed = new Set();
        for (const k in node) {
            if (k !== '$') allowed.add(k);
        }
        return allowed;
    }

    /** VocabularyTrie.containsWord — exact word membership. */
    containsWord(prefix) {
        let node = this.ensureTrie();
        for (const ch of prefix) {
            node = node[ch];
            if (!node) return false;
        }
        return node.$ === true;
    }

    /**
     * Load a flat frequency dictionary — the APK's en_enhanced.json shape
     * ({word: freq, ...}). Synthesizes common_words / top5000 / wordsByLength
     * from the raw frequencies so the rest of the filter pipeline works
     * identically regardless of which dict is loaded.
     */
    async loadFromFlatFreq(url) {
        try {
            console.log('Loading flat-freq dictionary from:', url);
            const response = await fetch(url, { cache: 'force-cache' });
            if (!response.ok) throw new Error(`Flat-freq fetch failed: HTTP ${response.status}`);
            const data = await response.json();
            const entries = Object.entries(data);
            if (!entries.length) throw new Error('Flat-freq dict is empty');

            // Production parity (OptimizedVocabulary.loadWordFrequencies):
            // sort by raw byte score desc, normalize (raw-128)/127 clamped to
            // ≥0.001, then assign tiers by sorted rank (top100=2, top3000=1).
            entries.sort((a, b) => b[1] - a[1]);

            this.wordFreq = new Map();
            this.tiers = new Map();
            this.wordsByLength = new Map();
            for (let i = 0; i < entries.length; i++) {
                const word = entries[i][0].toLowerCase();
                const rawFreq = entries[i][1];
                // Normalize 128-255 byte score → [0.001, 1.0] (OptimizedVocabulary.kt:1004-1006)
                const frequency = Math.max((rawFreq - 128) / 127.0, 0.001);
                const tier = i < PROD_FILTER.TIER2_RANK ? 2 : (i < PROD_FILTER.TIER1_RANK ? 1 : 0);
                this.wordFreq.set(word, frequency);
                this.tiers.set(word, tier);
                const len = word.length;
                if (!this.wordsByLength.has(len)) this.wordsByLength.set(len, []);
                this.wordsByLength.get(len).push(word);
            }

            // Membership sets kept for the tap-typing helpers; scoring uses
            // the tier map above (same ranks as production).
            this.commonWords = new Set(entries.slice(0, Math.min(PROD_FILTER.TIER2_RANK, entries.length)).map((e) => e[0]));
            this.top5000 = new Set(entries.slice(0, Math.min(PROD_FILTER.TIER1_RANK, entries.length)).map((e) => e[0]));

            // No adjacency graph in the flat dict; the filter tolerates null.
            this.keyboardAdjacency = null;
            // Production hardcoded per-length floor (OptimizedVocabulary.kt:1081-1091).
            this.minFreqByLength = { ...PROD_FILTER.MIN_FREQ_BY_LENGTH };

            // Byte-score normalization above IS the production scale.
            this.freqScale = 'normalized';
            this.freqScaleInfo = null;

            this.trieRoot = null; // rebuild lazily on first beam search
            this.invalidateTapPools();
            this.isLoaded = true;
            console.log(`Loaded ${this.wordFreq.size} words from flat-freq dict (production tiering)`);
            return true;
        } catch (error) {
            console.error('Error loading flat-freq dictionary:', error);
            return false;
        }
    }

    /**
     * Load vocabulary from JSON file with frequency data (the optional "full
     * 150k" `swipe_vocabulary.json` mode).
     *
     * SCALE ADAPTATION — demo-only, deliberate divergence from production:
     * this file ships RAW CORPUS PROBABILITIES (`the` = 5.4e-2 … rarest =
     * 1.0e-8, summing to ~0.79 over 150,252 words), whereas production only
     * ever sees the byte-score scale, where frequency = clamp((raw-128)/127,
     * 0.001, 1.0) and en_enhanced's 98k words occupy [0.047, 1.0]. Two pieces
     * of the ported filter are calibrated for that scale and are nonsense on
     * probabilities:
     *
     *   1. the configured rare-word floor CONFIG_MIN_FREQ = 0.01 — only 6 of
     *      150,252 probabilities clear it, so every tier-0 word (~143k) was
     *      dropped and this mode was reduced to its ~12k tier-listed words;
     *   2. the LINEAR frequency term in calculateScore — at p ~ 1e-6 the term
     *      0.114·p is ~1e-7 against a confidence term of ~0.8, i.e. ranking
     *      silently degenerated to confidence-only.
     *
     * So the probabilities are mapped ONCE, here, onto the production scale
     * with a monotone log10 min-max transform: it is rank-preserving, lands in
     * the same [0.001, 1.0] band as the byte-score clamp, and lets the rest of
     * the pipeline (gate, boosts, linear score term, tap-pool ordering) run
     * BYTE-FOR-BYTE the production code path with no per-dict branching. The
     * shipped `min_frequency_by_length` floors are mapped through the same
     * transform, so the dictionary author's per-length intent is preserved
     * (they reject 9.2% of tier-0 words on the raw scale, 10.7% after mapping
     * + the 0.01 config floor — versus 100% before this fix).
     *
     * NOT restored: the pre-a22b76ad mapping `log10(freq + 1e-10) / -10`. It
     * did bring probabilities into ~[0,1], but it is rank-INVERTING (rarer
     * words scored HIGHER), which is a bug, not a calibration.
     */
    async loadFromJSON(url) {
        try {
            console.log('Loading optimized vocabulary from:', url);
            const response = await fetch(url);

            if (!response.ok) {
                throw new Error(`Failed to load vocabulary: ${response.status}`);
            }

            const data = await response.json();
            console.log('Vocabulary data loaded:', data.metadata);

            // Load word frequencies (raw probabilities as shipped)
            const rawFreq = Object.entries(data.word_frequencies);
            let pMin = Infinity;
            let pMax = 0;
            for (const [, p] of rawFreq) {
                if (p > 0 && p < pMin) pMin = p;
                if (p > pMax) pMax = p;
            }

            // A probability dict spans decades below 1.0; anything already in
            // the production band is left untouched (defensive — a future
            // `swipe_vocabulary.json` could ship normalized values).
            const isProbabilityScale = pMax > 0 && pMax < PROD_FILTER.FREQ_FLOOR * 100;
            const log10Min = Math.log10(pMin);
            const decades = Math.log10(pMax) - log10Min;
            const mappable = isProbabilityScale && Number.isFinite(decades) && decades > 0;
            const toProdScale = (p) => (mappable
                ? Math.min(1.0, Math.max((Math.log10(p) - log10Min) / decades, PROD_FILTER.FREQ_FLOOR))
                : p);

            this.wordFreq = new Map();
            for (const [word, p] of rawFreq) {
                this.wordFreq.set(word, toProdScale(p));
            }
            this.freqScale = mappable ? 'normalized' : 'probability';
            this.freqScaleInfo = mappable
                ? { source: 'probability', pMin, pMax, decades, words: rawFreq.length }
                : null;

            // Load common words set for fast path
            this.commonWords = new Set(data.common_words);

            // Load top 5000 for quick filtering
            this.top5000 = new Set(data.top_5000 || data.top5000);

            // Load words by length
            this.wordsByLength = new Map();
            for (const [length, words] of Object.entries(data.words_by_length)) {
                this.wordsByLength.set(parseInt(length), words);
            }

            // Load configuration
            this.keyboardAdjacency = data.keyboard_adjacency;
            // Per-length floors ride the same transform as the frequencies they
            // are compared against — otherwise the gate mixes two scales again.
            this.minFreqByLength = null;
            if (data.min_frequency_by_length) {
                this.minFreqByLength = {};
                for (const [k, v] of Object.entries(data.min_frequency_by_length)) {
                    const floor = parseFloat(v);
                    this.minFreqByLength[k] = Number.isFinite(floor) ? toProdScale(floor) : v;
                }
            }

            // Synthesize tiers from membership sets so the production-parity
            // scoring path works for the extended dict too (tier2 = common,
            // tier1 = top5000, else 0).
            this.tiers = new Map();
            for (const word of this.wordFreq.keys()) {
                this.tiers.set(word, this.commonWords.has(word) ? 2 : (this.top5000.has(word) ? 1 : 0));
            }

            this.trieRoot = null; // rebuild lazily on first beam search
            this.invalidateTapPools();
            this.isLoaded = true;
            console.log(`Loaded ${this.wordFreq.size} words with frequency data` +
                        (mappable
                            ? ` (probabilities ${pMin.toExponential(2)}..${pMax.toExponential(2)} mapped onto the production [${PROD_FILTER.FREQ_FLOOR}, 1.0] scale over ${decades.toFixed(2)} decades)`
                            : ''));

            return true;

        } catch (error) {
            console.error('Error loading vocabulary JSON:', error);
            this.loadFallback();
            return false;
        }
    }

    /**
     * Load fallback vocabulary if JSON fails
     */
    loadFallback() {
        console.log('Loading fallback vocabulary...');
        
        const fallbackWords = [
            'the', 'of', 'and', 'to', 'a', 'in', 'for', 'is', 'on', 'that',
            'by', 'this', 'with', 'i', 'you', 'it', 'not', 'or', 'be', 'are',
            'from', 'at', 'as', 'your', 'all', 'would', 'will', 'there', 'their',
            'what', 'so', 'if', 'about', 'which', 'when', 'one', 'can', 'had'
        ];
        
        this.wordFreq = new Map();
        fallbackWords.forEach((word, index) => {
            // Assign decreasing frequency based on order
            this.wordFreq.set(word, 1e-4 * Math.pow(0.9, index));
        });
        
        this.commonWords = new Set(fallbackWords.slice(0, 10));
        this.top5000 = new Set(fallbackWords);
        this.tiers = new Map(fallbackWords.map((w, i) => [w, i < 10 ? 2 : 1]));
        this.trieRoot = null;
        // Hand-assigned 1e-4-scale values — NOT the production byte-score
        // scale, so the APK config floor must not be applied to them.
        this.freqScale = 'probability';
        this.freqScaleInfo = null;
        this.invalidateTapPools();
        this.isLoaded = true;
    }

    /**
     * Filter and rank neural network predictions.
     *
     * Faithful port of OptimizedVocabulary.filterPredictions (English path,
     * shipped defaults): format gate → vocabulary lookup (with contraction
     * alias fallback) → tier boost / rare-word frequency gate → combined
     * score (confW·conf + freqW·neuralFreqW·freq)·boost → sort desc →
     * expected-length filter (±2, fallback top-5) or top-10 cap.
     *
     * Shipped-default no-ops intentionally omitted: first-char prefix gate
     * (AUTOCORRECT_PREFIX_LENGTH=0) and disabled-words (none in demo).
     *
     * @param {Array} predictions - Array of {word: string, confidence: number}
     *   where confidence is the length-normalized exp(-score/normFactor) that
     *   BeamSearchEngine.convertToCandidate produces.
     * @param {Object} swipeStats - {expectedLength, pathLength} like SwipeStats.kt
     * @returns {Array} Filtered and ranked {word, score, confidence, frequency, source}
     */
    filterPredictions(predictions, swipeStats = {}) {
        if (!this.isLoaded) {
            console.warn('Vocabulary not loaded, returning raw predictions');
            return predictions;
        }

        const confW = PROD_FILTER.CONFIDENCE_WEIGHT;
        // neural_frequency_weight multiplies the frequency term (OptimizedVocabulary.kt:509)
        const effFreqW = PROD_FILTER.FREQUENCY_WEIGHT * PROD_FILTER.NEURAL_FREQ_WEIGHT;
        const validPredictions = [];

        for (const { word, confidence } of predictions) {
            const wordClean = (word || '').toLowerCase().trim();

            // Skip if not a valid word format (^[a-z]+$, OptimizedVocabulary.kt:382)
            if (!wordClean.match(/^[a-z]+$/)) {
                continue;
            }

            // Vocabulary lookup; contraction alias fallback mirrors
            // OptimizedVocabulary.kt:444-449 (WordInfo(0.88, tier 2)).
            let freq;
            let tier;
            if (this.wordFreq.has(wordClean)) {
                freq = this.wordFreq.get(wordClean);
                // Words added post-load (custom/personal) have no tier entry →
                // tier 1: no rare-word gate, matching production user words.
                tier = this.tiers.has(wordClean) ? this.tiers.get(wordClean) : 1;
            } else if (this.contractions &&
                       Object.prototype.hasOwnProperty.call(this.contractions, wordClean)) {
                freq = PROD_FILTER.CONTRACTION_ALIAS_FREQ;
                tier = 2;
            } else {
                continue; // Not in vocabulary (OptimizedVocabulary.kt:452-455)
            }

            let boost;
            let source;
            if (tier === 2) {
                boost = PROD_FILTER.COMMON_BOOST;
                source = 'common';
            } else if (tier === 1) {
                boost = PROD_FILTER.TOP5000_BOOST;
                source = 'top5000';
            } else {
                // Rare-word frequency gate: max(hardcoded floor, config floor)
                // (OptimizedVocabulary.kt:484-499).
                // CONFIG_MIN_FREQ encodes AUTOCORRECT_MIN_FREQUENCY on the
                // production byte-score scale; applying it to a dictionary that
                // was never mapped onto that scale rejects the whole tail (see
                // loadFromJSON). Dicts that ARE on it (flat-freq, and the full
                // dict after its load-time mapping) get the production gate
                // verbatim — on en_enhanced it rejects 0 of 98,140 words.
                const configMinFreq = this.freqScale === 'normalized'
                    ? PROD_FILTER.CONFIG_MIN_FREQ
                    : 0;
                const effectiveMinFreq = Math.max(this.getMinFrequency(wordClean.length), configMinFreq);
                if (freq < effectiveMinFreq) {
                    continue;
                }
                boost = PROD_FILTER.RARE_PENALTY;
                source = 'vocabulary';
            }

            const score = this.calculateScore(confidence, freq, boost, confW, effFreqW);
            validPredictions.push({
                word: wordClean,
                score,
                confidence,
                frequency: freq,
                source
            });
        }

        // Sort by combined score desc (OptimizedVocabulary.kt:537)
        validPredictions.sort((a, b) => b.score - a.score);

        // Expected-length filter (±2 tolerance, fallback top-5) or top-10 cap
        // (OptimizedVocabulary.kt:941-946, 1102-1114)
        if (swipeStats.expectedLength > 0) {
            return this.filterByExpectedLength(validPredictions, swipeStats.expectedLength);
        }

        return validPredictions.slice(0, 10);
    }

    /**
     * Combined score from NN confidence and word frequency.
     * VocabularyUtils.calculateCombinedScore parity: frequency is used
     * linearly (already normalized to [0,1] at load) — no log scaling.
     *
     * The linear term only carries signal when `frequency` is on the
     * production [0.001, 1.0] scale; that is why loadFromJSON maps raw
     * probabilities onto it at load rather than log-scaling here.
     */
    calculateScore(confidence, frequency, boost = 1.0,
                   confidenceWeight = PROD_FILTER.CONFIDENCE_WEIGHT,
                   frequencyWeight = PROD_FILTER.FREQUENCY_WEIGHT * PROD_FILTER.NEURAL_FREQ_WEIGHT) {
        return (confidenceWeight * confidence + frequencyWeight * frequency) * boost;
    }

    /**
     * Get minimum frequency threshold for word length.
     * Supports both numeric keys (production table) and the extended dict's
     * legacy '10+' key. Default 1e-9 (OptimizedVocabulary.kt:1095-1097).
     */
    getMinFrequency(length) {
        if (!this.minFreqByLength) {
            return 1e-9;
        }
        const v = this.minFreqByLength[length] ??
                  this.minFreqByLength[String(length)] ??
                  (length >= 10 ? this.minFreqByLength['10+'] : undefined);
        return v !== undefined ? parseFloat(v) : 1e-9;
    }

    /**
     * Filter predictions by expected word length
     */
    filterByExpectedLength(predictions, expectedLength, tolerance = 2) {
        const filtered = predictions.filter(p => {
            const lengthDiff = Math.abs(p.word.length - expectedLength);
            return lengthDiff <= tolerance;
        });
        
        return filtered.length > 0 ? filtered : predictions.slice(0, 5);
    }

    /**
     * Get words similar to a swipe pattern (for error correction)
     */
    getSimilarWords(targetWord, maxResults = 5) {
        if (!this.isLoaded || !targetWord) return [];
        
        const targetLength = targetWord.length;
        const similar = [];
        
        // Check words of similar length
        for (let len = Math.max(2, targetLength - 1); len <= targetLength + 1; len++) {
            if (this.wordsByLength.has(len)) {
                const words = this.wordsByLength.get(len);
                
                for (const wordData of words.slice(0, 100)) { // Check top 100 of each length
                    const word = wordData.word || wordData;
                    const distance = this.levenshteinDistance(targetWord, word);
                    
                    if (distance <= 2) {
                        similar.push({
                            word,
                            distance,
                            frequency: this.wordFreq.get(word) || 0
                        });
                    }
                }
            }
        }
        
        // Sort by distance then frequency
        similar.sort((a, b) => {
            if (a.distance !== b.distance) return a.distance - b.distance;
            return b.frequency - a.frequency;
        });
        
        return similar.slice(0, maxResults);
    }

    /**
     * Calculate Levenshtein distance between two strings
     */
    levenshteinDistance(s1, s2) {
        const len1 = s1.length;
        const len2 = s2.length;
        const matrix = [];

        for (let i = 0; i <= len1; i++) {
            matrix[i] = [i];
        }

        for (let j = 0; j <= len2; j++) {
            matrix[0][j] = j;
        }

        for (let i = 1; i <= len1; i++) {
            for (let j = 1; j <= len2; j++) {
                const cost = s1[i - 1] === s2[j - 1] ? 0 : 1;
                matrix[i][j] = Math.min(
                    matrix[i - 1][j] + 1,      // deletion
                    matrix[i][j - 1] + 1,      // insertion
                    matrix[i - 1][j - 1] + cost // substitution
                );
            }
        }

        return matrix[len1][len2];
    }

    /**
     * Get word frequency (returns 0 if not found)
     */
    getWordFrequency(word) {
        return this.wordFreq.get(word.toLowerCase()) || 0;
    }

    /**
     * Check if word exists in vocabulary
     */
    hasWord(word) {
        return this.wordFreq.has(word.toLowerCase());
    }

    /**
     * Get vocabulary statistics
     */
    getStats() {
        return {
            totalWords: this.wordFreq.size,
            commonWords: this.commonWords.size,
            top5000: this.top5000.size,
            isLoaded: this.isLoaded,
            lengthDistribution: Array.from(this.wordsByLength.entries())
                .map(([len, words]) => ({length: len, count: words.length}))
                .sort((a, b) => a.length - b.length)
        };
    }
}

// Export for use in browser
if (typeof window !== 'undefined') {
    window.SwipeVocabulary = SwipeVocabulary;
}

// Export for Node.js/module systems
if (typeof module !== 'undefined' && module.exports) {
    module.exports = SwipeVocabulary;
}
