/**
 * Committed regression gate for the web-demo F1/F2/F3 fixes (ARC-046).
 *
 * The 35cbaee3 remediation of the 2026-08-11 pipeline audit
 * (docs/history/audits/remediation-plans/web-demo-pipeline-findings.md) was
 * verified only by a scratchpad harness that no longer exists. This file is
 * the durable gate: the same Node-VM + DOM-stub pattern as ctc_app_smoke.mjs
 * (no onnxruntime needed — these layers sit below the decoders), running the
 * ACTUAL shipped code: the inline script of `demo/index.html`,
 * `swipe-vocabulary.js`, `custom-dictionary.js`, and the real dictionaries.
 *
 * Guarded regressions (each assertion cites the pre-fix failure it detects):
 *
 *  F1 (major) — "full 150k" swipe_vocabulary.json mode was functionally
 *    gutted: the APK-scale rare-word floor CONFIG_MIN_FREQ=0.01 was applied
 *    to raw corpus probabilities, so every tier-0 word (~138k of 150,252) was
 *    filtered out. Fixed by a monotone log10 min-max mapping onto the
 *    production [0.001, 1.0] scale at load. Gate: tier-0 witnesses whose RAW
 *    probability is < 0.01 (pre-fix: unconditionally dropped) must survive
 *    filterPredictions; a broad tier-0 sample must pass at ~90% (pre-fix:
 *    0%); equal-confidence ranking must follow frequency (pre-fix: the
 *    linear freq term at ~1e-7 made ranking confidence-only).
 *
 *  F2 (minor) — tap typing completed from `commonWords`/`top5000`, which are
 *    production TIER-RANK sets (100/3000 entries), starving the suggestions;
 *    `findFuzzyMatches` sliced commonWords to 200 when it held 100. Fixed by
 *    dedicated freq-descending pools (top-20k completion / top-2k fuzzy).
 *    Gate: driven through the REAL tap entry point (handleKeyTap →
 *    generateTapPredictions), a prefix must surface a completion that lies
 *    OUTSIDE commonWords ∪ top5000 (pre-fix: impossible); the tier sets must
 *    keep their production sizes (100/3000 — conflating them was the root
 *    cause); and findFuzzyMatches must not throw (the pre-fix
 *    levenshteinDistance declared its swapped rows `const`, so EVERY fuzzy
 *    call threw "Assignment to constant variable").
 *
 *  F3 (cosmetic) — removePersonalWord → unboostWord deleted a word from
 *    wordFreq but never removed it from the beam-masking trie, so a removed
 *    word stayed swipe-decodable until page reload. Fixed by real trie
 *    removal with bottom-up pruning. Gate: a personal word added through the
 *    shipped UI path must reach the masking trie, then vanish from it on
 *    removal with the trie pruned back to its pre-add node count; removal of
 *    a boosted DICTIONARY word must restore its original frequency and leave
 *    it in the trie; removeWordFromTrie must refuse to unmap a word still in
 *    wordFreq.
 *
 * Run: cd web_demo/tests && node f_regression.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.resolve(HERE, '..');
const CONFIG_KEY = 'cleverkeys.demo.config.v2';

/** F1 witnesses: tier-0 words with raw probability far below the 0.01 floor. */
const F1_WITNESSES = ['ethereum', 'albinism', 'serendipity'];

let failures = 0;
let checks = 0;
function check(name, ok, detail = '') {
    checks++;
    if (ok) {
        console.log(`  ok   ${name}`);
    } else {
        failures++;
        console.error(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`);
    }
}

function buildSandbox() {
    // ── DOM stubs (memoized per id so element state round-trips) ─────
    const mkEl = () => ({
        textContent: '', innerHTML: '', value: '', style: {}, dataset: {},
        checked: false, options: [],
        classList: { add() {}, remove() {}, toggle() {}, contains: () => false },
        setAttribute() {}, appendChild() {}, addEventListener() {},
        getBoundingClientRect: () => ({ left: 0, top: 0, right: 360, bottom: 215, width: 360, height: 215 }),
        getContext: () => ({
            clearRect() {}, beginPath() {}, moveTo() {}, lineTo() {}, stroke() {},
            arc() {}, fill() {}, createLinearGradient: () => ({ addColorStop() {} }),
        }),
        parentElement: { getBoundingClientRect: () => ({ width: 360, height: 215 }) },
    });
    const elements = new Map();
    const documentStub = {
        getElementById: (id) => {
            if (!elements.has(id)) elements.set(id, mkEl());
            return elements.get(id);
        },
        querySelector: () => null,
        querySelectorAll: () => [],
        createElement: () => mkEl(),
    };

    // fetch shim → local files (strip query string), mirroring serve.py:
    // web_demo/<p> first (the CI flatten), then web_demo/demo/<p>.
    const fetchShim = async (url) => {
        const clean = String(url).split('?')[0];
        let file = path.join(WEB, clean);
        if (!fs.existsSync(file)) file = path.join(WEB, 'demo', clean);
        if (!fs.existsSync(file)) return { ok: false, status: 404, headers: { get: () => null } };
        const stat = fs.statSync(file);
        return {
            ok: true,
            status: 200,
            headers: { get: (h) => (h === 'content-length' ? String(stat.size) : null) },
            json: async () => JSON.parse(fs.readFileSync(file, 'utf8')),
            text: async () => fs.readFileSync(file, 'utf8'),
            arrayBuffer: async () => {
                const b = fs.readFileSync(file);
                return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength);
            },
        };
    };

    const sandbox = {
        console: { log: () => {}, warn: () => {}, error: (...a) => console.error('[demo]', ...a) },
        // Dummy ORT namespace: the decoders never run in this gate, but the
        // inline script's helpers reference `ort.env` when configuring.
        ort: { env: { wasm: {} } },
        fetch: fetchShim, performance,
        setTimeout, clearTimeout, Math, JSON, Object, Array, Number, String,
        Float32Array, Float64Array, Int32Array, Uint8Array, Uint16Array,
        DataView, ArrayBuffer, BigInt, Set, Map, Infinity, NaN, Promise,
        Error, RegExp,
        document: documentStub,
        window: { addEventListener() {} },
        navigator: { hardwareConcurrency: 1 },
        localStorage: {
            // The demo config is the only persisted state this gate seeds;
            // `customDictionaries` etc. resolve to null (clean profile).
            getItem: (k) => (k === CONFIG_KEY
                ? JSON.stringify({ wasmThreads: false, batchBeams: true, dictionary: 'apk' })
                : null),
            setItem: () => {}, removeItem: () => {},
        },
        location: { reload() {} },
        alert() {}, confirm: () => false,
        URL, Blob: class {}, FileReader: class {},
    };
    sandbox.globalThis = sandbox;
    vm.createContext(sandbox);

    for (const f of ['swipe-vocabulary.js', 'custom-dictionary.js',
                     'niche-words-loader.js', 'demo/ctc-engine.js']) {
        vm.runInContext(fs.readFileSync(path.join(WEB, f), 'utf8'), sandbox, { filename: f });
    }
    sandbox.CTC = sandbox.window.CTC;
    const html = fs.readFileSync(path.join(WEB, 'demo/index.html'), 'utf8');
    const start = html.indexOf('<script>        // Configuration');
    const end = html.indexOf('<\/script>', start);
    if (start < 0 || end < 0) throw new Error('inline demo script markers not found');
    vm.runInContext(html.slice(start + 8, end), sandbox, { filename: 'demo-inline.js' });
    return sandbox;
}

/** Count trie nodes (object graph size) for the prune-back assertion. */
const COUNT_TRIE_NODES = `(function count(node) {
    let n = 1;
    for (const k in node) { if (k !== '$') n += count(node[k]); }
    return n;
})(swipeVocabulary.ensureTrie())`;

async function main() {
    const sandbox = buildSandbox();
    const run = (code) => vm.runInContext(code, sandbox);

    // ════ F1 — full 150k dictionary mode ════════════════════════════
    console.log('F1 — full-dict rare-word gate (probability→production-scale mapping)');
    run(`DEMO_CONFIG.dictionary = 'full'`);
    await run('loadVocabulary()');
    check('full dict loaded', run('swipeVocabulary.isLoaded === true && swipeVocabulary.wordFreq.size >= 150000'),
        `size=${run('swipeVocabulary.wordFreq.size')}`);
    check('probabilities mapped onto production scale',
        run(`swipeVocabulary.freqScale === 'normalized' && swipeVocabulary.freqScaleInfo && swipeVocabulary.freqScaleInfo.source === 'probability'`));

    // Witness precondition, read from the SHIPPED file: raw probability below
    // the 0.01 floor and tier-0 — exactly the class the pre-fix gate dropped.
    const rawDict = JSON.parse(fs.readFileSync(path.join(WEB, 'swipe_vocabulary.json'), 'utf8'));
    const commonSet = new Set(rawDict.common_words);
    const topSet = new Set(rawDict.top_5000 || rawDict.top5000);
    for (const w of F1_WITNESSES) {
        const raw = rawDict.word_frequencies[w];
        check(`witness precondition: "${w}" is tier-0 with raw p=${raw} < 0.01`,
            typeof raw === 'number' && raw < 0.01 && !commonSet.has(w) && !topSet.has(w));
        const out = run(`swipeVocabulary.filterPredictions(
            [{ word: '${w}', confidence: 0.9 }],
            { expectedLength: ${w.length} }).map(r => r.word)`);
        check(`tier-0 witness "${w}" survives filterPredictions (pre-fix: dropped by the 0.01 floor)`,
            Array.isArray(out) && out.includes(w), `got [${out}]`);
    }

    // Broad sweep: sample tier-0 words across the whole dict. Pre-fix pass
    // rate was 0% (6 of 150,252 total cleared the floor); post-fix the mapped
    // per-length floors reject ~10.7%, so gate at >= 80%.
    const tier0 = Object.keys(rawDict.word_frequencies)
        .filter((w) => /^[a-z]+$/.test(w) && !commonSet.has(w) && !topSet.has(w));
    const stride = Math.max(1, Math.floor(tier0.length / 200));
    const sample = [];
    for (let i = 0; i < tier0.length && sample.length < 200; i += stride) sample.push(tier0[i]);
    sandbox.__sample = sample;
    const passed = run(`__sample.filter((w) =>
        swipeVocabulary.filterPredictions([{ word: w, confidence: 0.9 }],
            { expectedLength: w.length }).some((r) => r.word === w)).length`);
    check(`tier-0 sample pass rate ${passed}/${sample.length} >= 80% (pre-fix: 0%)`,
        passed / sample.length >= 0.8);

    // Equal confidence must rank by frequency — pre-fix the linear frequency
    // term was ~1e-7 on raw probabilities, so order degenerated to input order.
    const ranked = run(`swipeVocabulary.filterPredictions([
        { word: 'serendipity', confidence: 0.5 },
        { word: 'the', confidence: 0.5 }]).map(r => r.word)`);
    check('equal-confidence ranking follows frequency ("the" first)',
        ranked[0] === 'the' && ranked.includes('serendipity'), `got [${ranked}]`);

    // ════ F2 — dedicated tap pools (default APK dict) ═══════════════
    console.log('F2 — tap-typing pools decoupled from the tier-rank sets');
    run(`DEMO_CONFIG.dictionary = 'apk'`);
    await run('loadVocabulary()');
    check('APK dict loaded (98k, normalized scale)',
        run(`swipeVocabulary.isLoaded === true && swipeVocabulary.wordFreq.size >= 98000 && swipeVocabulary.freqScale === 'normalized'`));

    // The tier sets must KEEP production rank sizes — repurposing them as tap
    // pools (and vice versa) was the F2 root cause.
    check('commonWords is the 100-entry tier-2 rank set',
        run('swipeVocabulary.commonWords.size === 100'), `size=${run('swipeVocabulary.commonWords.size')}`);
    check('top5000 is the 3000-entry tier-1 rank set',
        run('swipeVocabulary.top5000.size === 3000'), `size=${run('swipeVocabulary.top5000.size')}`);

    // Drive the REAL tap path. handleKeyTap is the shipped per-keystroke
    // entry; by design it auto-commits any pending prediction when the next
    // tap arrives (see testAutoPrediction in the page), so multi-char
    // prefixes are exercised through generateTapPredictions — the exact
    // function handleKeyTap invokes with the accumulated word — which fans
    // out to findWordCompletions / findFuzzyMatches → currentPredictions.
    run('clearPredictionState()');
    run(`handleKeyTap('p')`);
    check('handleKeyTap tracks the typed word and raises predictions',
        run(`currentTypedWord === 'p' && hasPendingPredictions === true && currentPredictions[0] === 'p'`),
        `typed=${run('currentTypedWord')} preds=[${run('currentPredictions.slice()')}]`);
    const tapPreds = (prefix) => {
        run('clearPredictionState()');
        run(`generateTapPredictions('${prefix}')`);
        return run('currentPredictions.slice()');
    };
    for (const [prefix, expect] of [['progra', 'programming'], ['zon', 'zones']]) {
        const preds = tapPreds(prefix);
        check(`tap "${prefix}" yields completions`, preds.length > 1, `got [${preds}]`);
        // Pre-fix witness: the old pools were commonWords(100) ∪ top5000(3000),
        // so NO completion outside those sets could ever surface.
        const outside = preds.filter((w) => w !== prefix &&
            run(`!swipeVocabulary.commonWords.has('${w}') && !swipeVocabulary.top5000.has('${w}')`));
        check(`tap "${prefix}" surfaces a completion beyond the tier sets (e.g. "${expect}"; pre-fix: impossible)`,
            outside.length > 0, `got [${preds}]`);
    }
    check('tap pools have the dedicated sizes (20k completion / 2k fuzzy)',
        run('swipeVocabulary.tapCompletionPool.length === 20000 && swipeVocabulary.tapFuzzyPool.length === 2000'));

    // Fuzzy matching: the pre-fix levenshteinDistance swapped `const` rows, so
    // EVERY call threw "Assignment to constant variable" — calling it at all
    // is the regression witness; the content check rides along.
    let fuzzy;
    try {
        fuzzy = run(`findFuzzyMatches('helo')`);
        check('findFuzzyMatches("helo") returns edit-distance-1 words (pre-fix: threw)',
            Array.isArray(fuzzy) && fuzzy.length > 0 &&
            fuzzy.every((w) => run(`levenshteinDistance('helo', '${w}')`) <= 1),
            `got [${fuzzy}]`);
    } catch (e) {
        check('findFuzzyMatches("helo") returns edit-distance-1 words (pre-fix: threw)', false, e.message);
    }
    const fuzzyPreds = tapPreds('helo');
    check('tap path surfaces a fuzzy correction for "helo"',
        fuzzyPreds.some((w) => w === 'hello' || w === 'help'), `got [${fuzzyPreds}]`);

    // ════ F3 — personal-word removal reaches the masking trie ═══════
    console.log('F3 — removePersonalWord prunes the beam-masking trie');
    const W = 'zzqxjv'; // not a dictionary word
    check(`precondition: "${W}" absent from vocab and trie`,
        run(`!swipeVocabulary.hasWord('${W}') && !swipeVocabulary.containsWord('${W}')`));
    const nodesBefore = run(COUNT_TRIE_NODES);

    // Add through the shipped UI path (newWord input → addPersonalWord()).
    run(`document.getElementById('newWord').value = '${W}'`);
    run('addPersonalWord()');
    check(`"${W}" added: present in wordFreq`, run(`swipeVocabulary.hasWord('${W}')`));
    check(`"${W}" added: reachable in the masking trie`, run(`swipeVocabulary.containsWord('${W}')`));
    check('tap pools invalidated by the add', run('swipeVocabulary.tapCompletionPool === null'));
    const nodesDuring = run(COUNT_TRIE_NODES);
    check('trie grew for the personal word', nodesDuring > nodesBefore);

    // Remove through the shipped UI path. Pre-fix: wordFreq lost the word but
    // the trie kept it, so it stayed beam-reachable until reload.
    run(`removePersonalWord('${W}')`);
    check(`"${W}" removed from wordFreq`, run(`!swipeVocabulary.hasWord('${W}')`));
    check(`"${W}" no longer beam-reachable (pre-fix: stayed in the trie)`,
        run(`!swipeVocabulary.containsWord('${W}')`));
    check('trie pruned back to its pre-add node count',
        run(COUNT_TRIE_NODES) === nodesBefore,
        `before=${nodesBefore} after=${run(COUNT_TRIE_NODES)}`);

    // Boost-then-remove on a DICTIONARY word must restore, not delete.
    const origFreq = run(`swipeVocabulary.wordFreq.get('zone')`);
    run(`document.getElementById('newWord').value = 'zone'`);
    run('addPersonalWord()');
    run(`removePersonalWord('zone')`);
    check('boosted dictionary word "zone" keeps its trie entry after removal',
        run(`swipeVocabulary.containsWord('zone')`));
    check('boosted dictionary word "zone" restored to its original frequency',
        run(`swipeVocabulary.wordFreq.get('zone')`) === origFreq,
        `orig=${origFreq} now=${run(`swipeVocabulary.wordFreq.get('zone')`)}`);

    // The trie guard itself: refuse to unmap anything still in wordFreq.
    check('removeWordFromTrie refuses while the word is still in wordFreq',
        run(`swipeVocabulary.removeWordFromTrie('the') === false && swipeVocabulary.containsWord('the')`));

    console.log(failures === 0
        ? `\nF-REGRESSION: ALL ${checks} CHECKS PASS`
        : `\nF-REGRESSION: ${failures}/${checks} FAILURE(S)`);
    process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
