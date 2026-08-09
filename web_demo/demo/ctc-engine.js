/*
 * ctc-engine.js — browser port of the CleverKeys-ML CTC swipe decoder.
 *
 * This is a line-for-line port of three Python references that live in
 * `CleverKeys-ML/ctc/`; any behavioural change here is a parity bug:
 *
 *   featurize()          <- futo_decoder_eval.py::featurize
 *                           (resample_to_60hz -> resample_fixed, both ports of
 *                            FUTO swipe-library/src/resampler.cpp)
 *   sliceEmissions()     <- futo_decoder_ceiling.py::slice_emissions
 *   futoViterbiBeam()    <- futo_decoder_ceiling.py::futo_viterbi_beam
 *                           (itself a port of swipe-library/src/beam_search.cpp)
 *
 * Loaded as a classic script (like its siblings swipe-vocabulary.js and
 * custom-dictionary.js) so the demo's inline script can reach `window.CTC`
 * synchronously; the vendored onnxruntime-web build is a classic script too.
 *
 * @module CTC
 */
(function (global) {
    'use strict';

    // ── constants (futo_decoder_eval.py module header) ──────────────────────
    const RESAMPLE_LENGTH = 64;              // T — fixed encoder input length
    const HZ_INTERVAL_MS = 1000.0 / 60.0;    // resampler.cpp 60 Hz stage
    const MAX_KEYS = 64;                     // export-time layout bound
    const NUM_LETTERS = 26;                  // a..z
    const BLANK_IDX = NUM_LETTERS;           // blank slot after slicing -> 26
    const CLASSES = NUM_LETTERS + 1;         // 27 active classes per timestep
    const FULL_CLASSES = 65;                 // raw head width (64 keys + blank)

    /**
     * Scoring preset for the from-scratch CTC encoders. Tuned in
     * `CleverKeys-ML/ctc/sweep_scoring.py`; distinct from the FUTO
     * `honorable_sturgeon` preset baked into the Python defaults.
     * @type {Readonly<{gamma:number, lambda:number, beta:number,
     *                  gammaPrune:number, betaPrune:number,
     *                  beamWidth:number, topK:number}>}
     */
    const CTC_SCORING = Object.freeze({
        gamma: 1.05,
        lambda: 1.1,
        beta: 0.2,
        gammaPrune: 0.3734,
        betaPrune: 0.9882,
        beamWidth: 100,
        topK: 8,
    });

    // ── featurization ───────────────────────────────────────────────────────

    /**
     * Python's built-in `round()` — half-to-even, unlike JS `Math.round`
     * (half-up). The two disagree on e.g. 4.5, which a 75 ms swipe hits
     * exactly, so `resample_to_60hz` needs this to stay in lockstep.
     * @param {number} v non-negative value
     * @returns {number}
     */
    function pyRound(v) {
        const floor = Math.floor(v);
        const frac = v - floor;
        if (frac > 0.5) return floor + 1;
        if (frac < 0.5) return floor;
        return floor % 2 === 0 ? floor : floor + 1;
    }

    /**
     * Port of Python's `bisect.bisect_left` — leftmost index i with a[i] >= x.
     * @param {ArrayLike<number>} arr ascending
     * @param {number} x
     * @returns {number}
     */
    function bisectLeft(arr, x) {
        let lo = 0;
        let hi = arr.length;
        while (lo < hi) {
            const mid = (lo + hi) >>> 1;
            if (arr[mid] < x) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** @param {number} a @param {number} b @param {number} t @returns {number} */
    function lerp(a, b, t) { return a + t * (b - a); }

    /** @param {number} v @returns {number} v clamped to [0,1] */
    function clamp01(v) { return Math.min(1.0, Math.max(0.0, v)); }

    /**
     * Stage 1 — uniform linspace over [0, duration] at 60 Hz.
     * Port of `resample_to_60hz` / resampler.cpp.
     * @param {ArrayLike<number>} x
     * @param {ArrayLike<number>} y
     * @param {ArrayLike<number>} t timestamps in ms, ascending
     * @returns {{x: number[], y: number[]}}
     */
    function resampleTo60Hz(x, y, t) {
        const n = x.length;
        if (n === 0) return { x: [], y: [] };
        if (n === 1) return { x: [x[0], x[0]], y: [y[0], y[0]] };
        const eps = 1e-12;
        const t0 = t[0];
        const duration = t[n - 1] - t0;
        if (duration <= eps) return { x: [x[0], x[n - 1]], y: [y[0], y[n - 1]] };

        const numOutput = Math.max(2, pyRound(duration / HZ_INTERVAL_MS) + 1);
        const step = duration / (numOutput - 1);
        const outX = new Array(numOutput);
        const outY = new Array(numOutput);
        for (let i = 0; i < numOutput; i++) {
            let targetT = t0 + i * step;
            if (targetT > t[n - 1]) targetT = t[n - 1];
            const idx1 = bisectLeft(t, targetT);
            if (idx1 === 0) {
                outX[i] = x[0]; outY[i] = y[0];
            } else if (idx1 >= n) {
                outX[i] = x[n - 1]; outY[i] = y[n - 1];
            } else {
                const idx0 = idx1 - 1;
                const seg = t[idx1] - t[idx0];
                const frac = seg > eps ? (targetT - t[idx0]) / seg : 0.0;
                outX[i] = lerp(x[idx0], x[idx1], frac);
                outY[i] = lerp(y[idx0], y[idx1], frac);
            }
        }
        return { x: outX, y: outY };
    }

    /**
     * Stage 2 — index-uniform resample to a fixed length, then clip to [0,1].
     * Port of `resample_fixed` / resampler.cpp's index-based branch.
     * @param {ArrayLike<number>} x
     * @param {ArrayLike<number>} y
     * @param {number} [length=64]
     * @returns {{x: number[], y: number[]}}
     */
    function resampleFixed(x, y, length) {
        const len = length || RESAMPLE_LENGTH;
        const n = x.length;
        const outX = new Array(len).fill(0.0);
        const outY = new Array(len).fill(0.0);
        if (n === 0) return { x: outX, y: outY };
        if (n === 1) {
            return { x: outX.fill(clamp01(x[0])), y: outY.fill(clamp01(y[0])) };
        }
        for (let i = 0; i < len; i++) {
            const fracPos = i / (len - 1);
            const idx = fracPos * (n - 1);
            const idx0 = Math.trunc(idx);              // Python int() truncation
            const idx1 = Math.min(idx0 + 1, n - 1);
            const frac = idx - idx0;
            outX[i] = clamp01(lerp(x[idx0], x[idx1], frac));
            outY[i] = clamp01(lerp(y[idx0], y[idx1], frac));
        }
        return { x: outX, y: outY };
    }

    /**
     * Full featurization: raw pointer samples -> the encoder's `features`
     * tensor payload, laid out as [x row (64), y row (64)] = [2,64] row-major.
     * Port of `featurize`.
     * @param {ArrayLike<number>} px x in [0,1] over the letter area
     * @param {ArrayLike<number>} py y in [0,1] over the letter area
     * @param {ArrayLike<number>} pt timestamps in ms
     * @returns {Float32Array} length 128
     */
    function featurize(px, py, pt) {
        const hz = resampleTo60Hz(px, py, pt);
        const fixed = resampleFixed(hz.x, hz.y, RESAMPLE_LENGTH);
        const out = new Float32Array(2 * RESAMPLE_LENGTH);
        out.set(fixed.x, 0);
        out.set(fixed.y, RESAMPLE_LENGTH);
        return out;
    }

    /**
     * `[32,65]` raw head -> `[32,27]`: columns 0..25 pass through, the blank
     * column (index MAX_KEYS in the full head) lands in slot 26.
     * Port of `slice_emissions`.
     * @param {Float32Array} full flattened [T, 65]
     * @param {number} T
     * @returns {Float32Array} flattened [T, 27]
     */
    function sliceEmissions(full, T) {
        const out = new Float32Array(T * CLASSES);
        for (let t = 0; t < T; t++) {
            const src = t * FULL_CLASSES;
            const dst = t * CLASSES;
            for (let c = 0; c < NUM_LETTERS; c++) out[dst + c] = full[src + c];
            out[dst + NUM_LETTERS] = full[src + MAX_KEYS];
        }
        return out;
    }

    // ── lexicon trie ────────────────────────────────────────────────────────

    const VOCAB_MAGIC = 'CKCTCV1\0';
    const VOCAB_HEADER_LEN = 20;

    /**
     * CSR trie over the 146,964-word a-z lexicon, built from the front-coded
     * blob produced by `web_demo/tools/build_ctc_vocab.py`.
     *
     * The beam never needs prefix *strings*: a trie node uniquely determines
     * its prefix, so `nodeChar` (the edge character entering the node) and
     * `nodeDepth` supply everything `futo_viterbi_beam` reads from `prefix`
     * (`prefix[-1]` and `len(prefix)`). Words are only materialised for the
     * handful of finalists, by walking `nodeParent` back to the root.
     */
    class CtcTrie {
        /** @param {ArrayBuffer} buffer raw ctc_vocab.bin */
        constructor(buffer) {
            const bytes = new Uint8Array(buffer);
            for (let i = 0; i < VOCAB_MAGIC.length; i++) {
                if (bytes[i] !== VOCAB_MAGIC.charCodeAt(i)) {
                    throw new Error('ctc_vocab.bin: bad magic');
                }
            }
            const header = new DataView(buffer, 0, VOCAB_HEADER_LEN);
            const wordCount = header.getUint32(8, true);
            const blobLen = header.getUint32(12, true);
            const maxWordLen = header.getUint32(16, true);
            if (maxWordLen > 255) throw new Error('ctc_vocab.bin: word too long for u8 depth');

            // Words arrive sorted, so the shared-prefix byte is exactly the
            // depth of the deepest reusable node: total nodes are known up
            // front as 1 + sum(suffixLen) = 1 + blobLen - 2*wordCount.
            const nodeCount = 1 + blobLen - 2 * wordCount;

            const firstChild = new Int32Array(nodeCount).fill(-1);
            const nextSibling = new Int32Array(nodeCount).fill(-1);
            const lastChild = new Int32Array(nodeCount).fill(-1);
            const nodeChar = new Uint8Array(nodeCount);
            const nodeDepth = new Uint8Array(nodeCount);
            const nodeParent = new Int32Array(nodeCount);
            const isWord = new Uint8Array(nodeCount);
            const logFreq = new Float64Array(nodeCount);

            let freqOffset = VOCAB_HEADER_LEN + blobLen;
            if (freqOffset % 2) freqOffset += 1;
            const freqs = new Uint16Array(buffer, freqOffset, wordCount);

            // path[k] = node id reached after consuming k characters
            const path = new Int32Array(maxWordLen + 1);
            path[0] = 0;
            let nextNode = 1;
            let pos = VOCAB_HEADER_LEN;
            const end = VOCAB_HEADER_LEN + blobLen;
            let wordIndex = 0;

            while (pos < end) {
                const shared = bytes[pos];
                const suffixLen = bytes[pos + 1];
                pos += 2;
                let node = path[shared];
                for (let k = 0; k < suffixLen; k++) {
                    const charIdx = bytes[pos + k] - 97;
                    const child = nextNode++;
                    nodeChar[child] = charIdx;
                    nodeDepth[child] = shared + k + 1;
                    nodeParent[child] = node;
                    // Sorted input => children are appended in ascending
                    // character order, so an O(1) tail append keeps them sorted.
                    if (lastChild[node] === -1) firstChild[node] = child;
                    else nextSibling[lastChild[node]] = child;
                    lastChild[node] = child;
                    node = child;
                    path[shared + k + 1] = node;
                }
                pos += suffixLen;
                isWord[node] = 1;
                // Matches LexTrie.insert: log_freq = log(freq + 1e-10).
                logFreq[node] = Math.log(freqs[wordIndex] + 1e-10);
                wordIndex++;
            }
            if (nextNode !== nodeCount) {
                throw new Error(`ctc_vocab.bin: built ${nextNode} nodes, expected ${nodeCount}`);
            }

            // Flatten the sibling lists into CSR for a tight beam inner loop.
            const edgeCount = nodeCount - 1;
            const childStart = new Int32Array(nodeCount + 1);
            for (let n = 0; n < nodeCount; n++) {
                let count = 0;
                for (let c = firstChild[n]; c !== -1; c = nextSibling[c]) count++;
                childStart[n + 1] = childStart[n] + count;
            }
            const childChar = new Uint8Array(edgeCount);
            const childTarget = new Int32Array(edgeCount);
            for (let n = 0; n < nodeCount; n++) {
                let w = childStart[n];
                for (let c = firstChild[n]; c !== -1; c = nextSibling[c]) {
                    childChar[w] = nodeChar[c];
                    childTarget[w] = c;
                    w++;
                }
            }

            /** @type {number} */ this.wordCount = wordCount;
            /** @type {number} */ this.nodeCount = nodeCount;
            /** @type {Int32Array} */ this.childStart = childStart;
            /** @type {Uint8Array} */ this.childChar = childChar;
            /** @type {Int32Array} */ this.childTarget = childTarget;
            /** @type {Uint8Array} */ this.nodeChar = nodeChar;
            /** @type {Uint8Array} */ this.nodeDepth = nodeDepth;
            /** @type {Int32Array} */ this.nodeParent = nodeParent;
            /** @type {Uint8Array} */ this.isWord = isWord;
            /** @type {Float64Array} */ this.logFreq = logFreq;
        }

        /**
         * Rebuild the surface form of a node by walking back to the root.
         * @param {number} node
         * @returns {string}
         */
        wordAt(node) {
            const depth = this.nodeDepth[node];
            const chars = new Array(depth);
            let cur = node;
            for (let i = depth - 1; i >= 0; i--) {
                chars[i] = String.fromCharCode(97 + this.nodeChar[cur]);
                cur = this.nodeParent[cur];
            }
            return chars.join('');
        }

        /**
         * @param {string} word
         * @returns {boolean} true if `word` is a complete entry
         */
        contains(word) {
            let node = 0;
            outer: for (let i = 0; i < word.length; i++) {
                const target = word.charCodeAt(i) - 97;
                for (let e = this.childStart[node]; e < this.childStart[node + 1]; e++) {
                    if (this.childChar[e] === target) { node = this.childTarget[e]; continue outer; }
                }
                return false;
            }
            return this.isWord[node] === 1;
        }
    }

    // ── FUTO single-stream Viterbi trie beam ────────────────────────────────

    /**
     * Port of `futo_viterbi_beam` (which ports beam_search.cpp's
     * TrieBeamSearch::run_beam_search for the single-swipe, delta_ts = 0 case).
     *
     * A hypothesis is `(score, node, blankEnded)`; hypotheses are deduped on
     * `(node, blankEnded)` with a MAX merge on the raw path score — Viterbi,
     * not the log-sum a textbook CTC prefix beam would use. Each step performs
     * the same three expansions as the C++:
     *
     *   A  blank  -> (node,  blankEnded=true)   += p[blank]
     *   B  child  -> (child, blankEnded=false)  += p[childChar]
     *   C  repeat -> (node,  blankEnded=false)  += p[lastChar], iff !blankEnded
     *                                              and node is not the root
     *
     * @param {Float32Array} logProbs flattened [T, 27]
     * @param {number} T
     * @param {CtcTrie} trie
     * @param {{beamWidth:number, topK:number, gamma:number, lambda:number,
     *          beta:number, gammaPrune:number, betaPrune:number}} params
     * @returns {Array<{word: string, score: number}>} best-first, <= topK
     */
    function futoViterbiBeam(logProbs, T, trie, params) {
        const { beamWidth, topK, gamma, lambda, beta, gammaPrune, betaPrune } = params;
        const { childStart, childChar, childTarget, nodeChar, nodeDepth, isWord, logFreq } = trie;
        const lpOnly = gammaPrune === 0.0 && betaPrune === 0.0;

        /** @param {number} score @param {number} depth @returns {number} */
        const pruneScore = (score, depth) => {
            if (lpOnly) return score;
            const d = depth > 0 ? depth : 1;
            return score / Math.pow(d, gammaPrune) + betaPrune * depth;
        };

        // Parallel arrays instead of objects — the beam is re-walked 32 times
        // and this keeps the hot loop allocation-free apart from the Map.
        let beamScore = [0.0];
        let beamNode = [0];
        let beamBlankEnded = [0];

        for (let t = 0; t < T; t++) {
            const row = t * CLASSES;
            const blankLp = logProbs[row + BLANK_IDX];
            /** @type {Map<number, number>} key = node*2 + blankEnded -> slot */
            const seen = new Map();
            const nextScore = [];
            const nextNode = [];
            const nextBlankEnded = [];

            /**
             * Insert-or-max-merge one candidate.
             * @param {number} key @param {number} score
             * @param {number} node @param {number} be
             */
            const offer = (key, score, node, be) => {
                const slot = seen.get(key);
                if (slot === undefined) {
                    seen.set(key, nextScore.length);
                    nextScore.push(score);
                    nextNode.push(node);
                    nextBlankEnded.push(be);
                } else if (nextScore[slot] < score) {
                    nextScore[slot] = score;
                }
            };

            for (let h = 0; h < beamScore.length; h++) {
                const score = beamScore[h];
                const node = beamNode[h];
                const be = beamBlankEnded[h];

                // A — emit blank, stay put
                offer(node * 2 + 1, score + blankLp, node, 1);

                // B — emit a new letter, descend
                const eEnd = childStart[node + 1];
                for (let e = childStart[node]; e < eEnd; e++) {
                    const child = childTarget[e];
                    offer(child * 2, score + logProbs[row + childChar[e]], child, 0);
                }

                // C — repeat the last letter (CTC collapse), stay put
                if (be === 0 && nodeDepth[node] > 0) {
                    offer(node * 2, score + logProbs[row + nodeChar[node]], node, 0);
                }
            }

            let order = null;
            if (nextScore.length > beamWidth) {
                order = new Int32Array(nextScore.length);
                for (let i = 0; i < order.length; i++) order[i] = i;
                const keys = new Float64Array(nextScore.length);
                for (let i = 0; i < keys.length; i++) {
                    keys[i] = pruneScore(nextScore[i], nodeDepth[nextNode[i]]);
                }
                // Array#sort is stable (ES2019+), matching Python's sorted().
                order = Array.from(order).sort((a, b) => keys[b] - keys[a]).slice(0, beamWidth);
            }

            if (order) {
                beamScore = order.map((i) => nextScore[i]);
                beamNode = order.map((i) => nextNode[i]);
                beamBlankEnded = order.map((i) => nextBlankEnded[i]);
            } else {
                beamScore = nextScore;
                beamNode = nextNode;
                beamBlankEnded = nextBlankEnded;
            }
            if (beamScore.length === 0) break;
        }

        // Collect complete words, deduping by node (a word node can survive as
        // both the blank-ended and non-blank-ended hypothesis).
        /** @type {Map<number, number>} node -> best final score */
        const best = new Map();
        for (let h = 0; h < beamScore.length; h++) {
            const node = beamNode[h];
            const depth = nodeDepth[node];
            if (!isWord[node] || depth === 0) continue;
            const L = depth > 0 ? depth : 1;
            const final = beamScore[h] / Math.pow(L, gamma) + beta * depth + lambda * logFreq[node];
            const prev = best.get(node);
            if (prev === undefined || prev < final) best.set(node, final);
        }

        return Array.from(best.entries())
            .sort((a, b) => b[1] - a[1])
            .slice(0, topK)
            .map(([node, score]) => ({ word: trie.wordAt(node), score }));
    }

    /**
     * Greedy CTC collapse over the sliced classes — the lexicon-free readout,
     * shown in the demo as a sanity signal next to the beam output.
     * Port of `futo_decoder_eval.py::greedy_ctc`.
     * @param {Float32Array} logProbs flattened [T, 27]
     * @param {number} T
     * @returns {string}
     */
    function greedyCtc(logProbs, T) {
        let out = '';
        let prev = -1;
        for (let t = 0; t < T; t++) {
            const row = t * CLASSES;
            let argmax = 0;
            let bestVal = -Infinity;
            for (let c = 0; c < CLASSES; c++) {
                if (logProbs[row + c] > bestVal) { bestVal = logProbs[row + c]; argmax = c; }
            }
            if (argmax !== prev && argmax !== BLANK_IDX) out += String.fromCharCode(97 + argmax);
            prev = argmax;
        }
        return out;
    }

    // ── engine ──────────────────────────────────────────────────────────────

    /**
     * One loaded CTC encoder plus the shared lexicon and layout. Sessions are
     * created lazily so switching engines in the dropdown only pays for the
     * model actually used.
     */
    class CtcEngine {
        /**
         * @param {{id: string, label: string, modelUrl: string}} spec
         * @param {CtcTrie} trie
         * @param {{keys: Float32Array, mask: Uint8Array}} layout
         * @param {object} [scoring] overrides for CTC_SCORING
         */
        constructor(spec, trie, layout, scoring) {
            this.id = spec.id;
            this.label = spec.label;
            this.modelUrl = spec.modelUrl;
            this.trie = trie;
            this.layout = layout;
            this.scoring = Object.assign({}, CTC_SCORING, scoring || {});
            /** @type {?object} */ this.session = null;
        }

        /** @returns {Promise<void>} resolves once the ONNX session is live */
        async load() {
            if (this.session) return;
            const response = await fetch(this.modelUrl, { cache: 'no-store' });
            if (!response.ok) {
                throw new Error(`${this.modelUrl}: HTTP ${response.status}`);
            }
            const buffer = await response.arrayBuffer();
            this.session = await global.ort.InferenceSession.create(buffer, {
                executionProviders: ['wasm'],
                graphOptimizationLevel: 'all',
            });
        }

        /**
         * Decode one swipe.
         * @param {{x: number[], y: number[], t: number[]}} points
         *        letter-area-normalised coordinates in [0,1] and ms timestamps
         * @returns {Promise<{candidates: Array<{word:string, score:number}>,
         *                    greedy: string,
         *                    timing: {featurizeMs:number, inferMs:number,
         *                             beamMs:number, totalMs:number}}>}
         */
        async decode(points) {
            if (!this.session) throw new Error(`engine ${this.id} not loaded`);
            const ort = global.ort;

            const t0 = performance.now();
            const features = featurize(points.x, points.y, points.t);
            const t1 = performance.now();

            const feeds = {
                features: new ort.Tensor('float32', features, [1, 2, RESAMPLE_LENGTH]),
                layout_keys: new ort.Tensor('float32', this.layout.keys, [1, MAX_KEYS, 2]),
                layout_mask: new ort.Tensor('bool', this.layout.mask, [1, MAX_KEYS]),
            };
            const output = await this.session.run(feeds);
            const emissions = output.log_emissions;
            const T = emissions.dims[1];
            const t2 = performance.now();

            const logProbs = sliceEmissions(emissions.data, T);
            const candidates = futoViterbiBeam(logProbs, T, this.trie, this.scoring);
            const greedy = greedyCtc(logProbs, T);
            const t3 = performance.now();

            return {
                candidates,
                greedy,
                timing: {
                    featurizeMs: t1 - t0,
                    inferMs: t2 - t1,
                    beamMs: t3 - t2,
                    totalMs: t3 - t0,
                },
            };
        }
    }

    /**
     * Build the padded `layout_keys` / `layout_mask` payloads from
     * `en_qwerty.json`. Key order defines the emission class order, so a..z
     * must stay in the JSON's own key array order (which is alphabetical).
     * @param {{letters: string, keys: Array<{letter:string, cx:number, cy:number}>}} layoutJson
     * @returns {{keys: Float32Array, mask: Uint8Array, letters: string[]}}
     */
    function buildLayout(layoutJson) {
        const keys = new Float32Array(MAX_KEYS * 2);
        const mask = new Uint8Array(MAX_KEYS);
        const letters = [];
        layoutJson.keys.forEach((key, i) => {
            if (i >= MAX_KEYS) throw new Error('layout has more than 64 keys');
            keys[i * 2] = key.cx;
            keys[i * 2 + 1] = key.cy;
            mask[i] = 1;
            letters.push(key.letter);
        });
        if (letters.length !== NUM_LETTERS) {
            throw new Error(`expected ${NUM_LETTERS} letter keys, got ${letters.length}`);
        }
        return { keys, mask, letters };
    }

    /**
     * Load the lexicon + layout once and construct every CTC engine (lazily
     * loading their ONNX sessions).
     * @param {{vocabUrl: string, layoutUrl: string,
     *          engines: Array<{id:string, label:string, modelUrl:string}>}} config
     * @returns {Promise<{trie: CtcTrie, layout: object,
     *                    engines: Map<string, CtcEngine>,
     *                    stats: {vocabBytes:number, fetchMs:number,
     *                            trieMs:number, words:number, nodes:number}}>}
     */
    async function createEngines(config) {
        const fetchStart = performance.now();
        const [vocabResponse, layoutResponse] = await Promise.all([
            fetch(config.vocabUrl, { cache: 'force-cache' }),
            fetch(config.layoutUrl, { cache: 'force-cache' }),
        ]);
        if (!vocabResponse.ok) throw new Error(`${config.vocabUrl}: HTTP ${vocabResponse.status}`);
        if (!layoutResponse.ok) throw new Error(`${config.layoutUrl}: HTTP ${layoutResponse.status}`);
        const vocabBuffer = await vocabResponse.arrayBuffer();
        const layoutJson = await layoutResponse.json();
        const fetchMs = performance.now() - fetchStart;

        const trieStart = performance.now();
        const trie = new CtcTrie(vocabBuffer);
        const trieMs = performance.now() - trieStart;

        const layout = buildLayout(layoutJson);
        const engines = new Map();
        for (const spec of config.engines) {
            engines.set(spec.id, new CtcEngine(spec, trie, layout, config.scoring));
        }

        return {
            trie,
            layout,
            engines,
            stats: {
                vocabBytes: vocabBuffer.byteLength,
                fetchMs,
                trieMs,
                words: trie.wordCount,
                nodes: trie.nodeCount,
            },
        };
    }

    global.CTC = {
        RESAMPLE_LENGTH,
        NUM_LETTERS,
        BLANK_IDX,
        CLASSES,
        CTC_SCORING,
        pyRound,
        bisectLeft,
        resampleTo60Hz,
        resampleFixed,
        featurize,
        sliceEmissions,
        greedyCtc,
        futoViterbiBeam,
        buildLayout,
        createEngines,
        CtcTrie,
        CtcEngine,
    };
})(typeof window !== 'undefined' ? window : globalThis);
