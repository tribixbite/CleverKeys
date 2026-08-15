/**
 * Headless end-to-end smoke test for the web demo's "CTC (shipped app engine)"
 * path — the same Node-VM + DOM-stub + onnxruntime-web pattern as the
 * a22b76ad neural parity smoke, now driving the CTC engine added for the
 * shipped `ctc_swipe_encoder.onnx`.
 *
 * It runs the ACTUAL shipped code: the inline script of `demo/index.html`,
 * `demo/ctc-engine.js`, the real 98k `en_enhanced.json` (STRIP trie), and the
 * real ONNX via onnxruntime-web (wasm). The engine is selected through the
 * REAL dropdown handler (`onEngineChange`, exercising the lazy session load
 * and the DEMO_CONFIG.engine persistence), and swipes are replayed through
 * `processSwipe` — the same entry point a finger-drawn gesture uses — from
 * the deterministic trajectories in `tests/reference.json`.
 *
 * Asserted outcomes (measured on the fixture trajectories, see EXPECT):
 * 7/9 words decode to themselves at top-1. Two are genuine model outcomes on
 * these synthetic constant-speed traces, asserted exactly rather than
 * skipped — the golden-fixture gate (ctc_app_parity.mjs) proves the port is
 * exact, so these are properties of model+preset+lexicon, not port drift:
 *  - `four` → top-1 `for` (`four` #2): the f-o-u-r path passes within a
 *    key-width of f-o-r and `for` is far more frequent — every CTC engine
 *    in the demo does this (README table).
 *  - `hello` → top-1 `help` (`hello` #2): unlike the experimental engines'
 *    147k FUTO lexicon, the 98k en_enhanced STRIP trie ranks `help` (a much
 *    more frequent word, λ=4.0) above `hello` on this synthetic trace.
 *
 * Run: cd web_demo/tests && bun install && node ctc_app_smoke.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.resolve(HERE, '..');

/** The demo's internal coordinate frame (index.html NORMALIZED_WIDTH/HEIGHT). */
const NORMALIZED_WIDTH = 360;
const NORMALIZED_HEIGHT = 215;

/**
 * Expected decode per reference word through the app engine: exact top-1,
 * plus (when the intended word is not top-1) the rank the intended word must
 * still reach. See module doc for why `hello` and `four` differ.
 * @type {Record<string, {top1: string, intendedWithin?: number}>}
 */
const EXPECT = {
    the: { top1: 'the' },
    hello: { top1: 'help', intendedWithin: 2 },
    keyboard: { top1: 'keyboard' },
    dont: { top1: 'dont' }, // raw candidate; display maps it to "don't" (checked below)
    world: { top1: 'world' },
    this: { top1: 'this' },
    about: { top1: 'about' },
    four: { top1: 'for', intendedWithin: 2 },
    something: { top1: 'something' },
};

async function main() {
    const ort = require('onnxruntime-web');
    ort.env.wasm.numThreads = 1;
    ort.env.wasm.simd = true;
    // Pin wasm binaries to the local package — the shipped demo code assigns
    // 'vendor/ort/' which is meaningless under node. Freeze against overwrite.
    const localWasm = path.join(HERE, 'node_modules/onnxruntime-web/dist/');
    Object.defineProperty(ort.env.wasm, 'wasmPaths', {
        get: () => localWasm,
        set: () => {},
        configurable: true,
    });

    // ── DOM stubs (memoized per id so <select>.value round-trips) ────
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

    // fetch shim → local files (strip query string). Mirrors serve.py: try
    // web_demo/<p> first (the CI flatten), then web_demo/demo/<p> (models/,
    // vendor/ live under demo/).
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

    // ── Sandbox ──────────────────────────────────────────────────
    const savedConfigs = [];
    const sandboxConsole = {
        log: () => {},
        warn: (...a) => console.warn('[demo]', ...a),
        error: (...a) => console.error('[demo]', ...a),
    };
    const sandbox = {
        console: sandboxConsole, ort, fetch: fetchShim, performance,
        setTimeout, clearTimeout, Math, JSON, Object, Array, Number, String,
        Float32Array, Float64Array, Int32Array, Uint8Array, Uint16Array,
        DataView, ArrayBuffer, BigInt, Set, Map, Infinity, NaN, Promise,
        Error, RegExp,
        document: documentStub,
        window: { addEventListener() {} },
        navigator: { hardwareConcurrency: 1 },
        localStorage: {
            getItem: () => JSON.stringify({ wasmThreads: false, batchBeams: true, dictionary: 'apk' }),
            setItem: (k, v) => savedConfigs.push([k, v]),
        },
        location: { reload() {} },
        alert() {}, confirm: () => false,
        URL, Blob: class {}, FileReader: class {},
    };
    sandbox.globalThis = sandbox;
    vm.createContext(sandbox);

    // Load the demo's classic-script dependencies, then the shipped inline JS.
    for (const f of ['swipe-vocabulary.js', 'custom-dictionary.js',
                     'niche-words-loader.js', 'demo/ctc-engine.js']) {
        vm.runInContext(fs.readFileSync(path.join(WEB, f), 'utf8'), sandbox, { filename: f });
    }
    // ctc-engine.js attaches to (and reads ort from) `window` when one
    // exists; in the browser window IS the global, in this sandbox it's a
    // stub — bridge both directions.
    sandbox.CTC = sandbox.window.CTC;
    sandbox.window.ort = ort;
    const html = fs.readFileSync(path.join(WEB, 'demo/index.html'), 'utf8');
    const start = html.indexOf('<script>        // Configuration');
    const end = html.indexOf('<\/script>', start);
    if (start < 0 || end < 0) throw new Error('inline demo script markers not found');
    vm.runInContext(html.slice(start + 8, end), sandbox, { filename: 'demo-inline.js' });

    // Bring every engine up through the shipped load path.
    await vm.runInContext('loadModels()', sandbox);
    if (!vm.runInContext('isModelReady', sandbox)) {
        throw new Error('loadModels() did not reach ready state');
    }
    const engineIds = vm.runInContext(
        'ENGINE_SPECS.filter(s => engineAvailable(s.id)).map(s => s.id)', sandbox);
    console.log('engines available:', engineIds.join(', '));
    if (!engineIds.includes('ctc_app')) throw new Error('ctc_app engine unavailable');

    // Select the app engine through the REAL dropdown handler.
    documentStub.getElementById('engineSelect').value = 'ctc_app';
    await vm.runInContext('onEngineChange()', sandbox);
    const active = vm.runInContext('activeEngineId', sandbox);
    if (active !== 'ctc_app') throw new Error(`engine switch failed (active=${active})`);
    const persisted = savedConfigs.length > 0
        ? JSON.parse(savedConfigs[savedConfigs.length - 1][1]).engine : null;
    console.log(`engine selected: ${active} (persisted DEMO_CONFIG.engine=${persisted})`);
    if (persisted !== 'ctc_app') throw new Error('engine choice was not persisted to config');

    // ── Replay the reference trajectories through processSwipe ───
    const reference = JSON.parse(fs.readFileSync(path.join(HERE, 'reference.json'), 'utf8'));
    sandbox.__swipeData = null;

    let failures = 0;
    console.log('\nword        top-1        candidates (top 4)                 total ms');
    for (const [word, spec] of Object.entries(EXPECT)) {
        const expected = spec.top1;
        const swipe = reference.swipes[word];
        if (!swipe) { console.error(`  reference.json has no swipe for "${word}"`); failures++; continue; }
        const swipePath = swipe.x.map((x, i) => ({
            x: x * NORMALIZED_WIDTH,
            y: swipe.y[i] * NORMALIZED_HEIGHT,
            timestamp: swipe.t[i],
        }));
        sandbox.__swipeData = {
            path: swipePath,
            keySequence: [],
            duration: swipe.t[swipe.t.length - 1] - swipe.t[0],
            word: '',
        };
        const decode = await vm.runInContext('processSwipe(__swipeData)', sandbox);
        if (!decode || decode.engine !== 'ctc_app') {
            console.error(`  ${word}: decode failed or wrong engine (${decode && decode.engine})`);
            failures++;
            continue;
        }
        const top1 = decode.words[0] || '∅';
        let ok = top1 === expected;
        if (ok && spec.intendedWithin !== undefined) {
            const rank = decode.words.indexOf(word);
            if (rank < 0 || rank >= spec.intendedWithin) {
                console.error(`  ${word}: intended word not in top-${spec.intendedWithin} ` +
                              `(rank ${rank < 0 ? 'absent' : rank + 1})`);
                ok = false;
            }
        }
        if (!ok) failures++;
        console.log(
            `${word.padEnd(11)} ${(top1 === expected ? top1 : `${top1} (want ${expected})`).padEnd(12)} ` +
            `[${decode.candidates.slice(0, 4).map((c) => c.word).join(', ')}]`.padEnd(35) +
            ` ${decode.timing.totalMs.toFixed(1)}`);
    }

    // Contraction display: the raw candidate "dont" must render as "don't"
    // through the shipped display fixup (same layer the on-screen chips use).
    const fixed = vm.runInContext('applyContractionFixup("dont")', sandbox);
    if (fixed !== "don't") {
        console.error(`contraction display: applyContractionFixup("dont") = "${fixed}" != "don't"`);
        failures++;
    } else {
        console.log('\ncontraction display: "dont" → "don\'t" ok');
    }
    // Paired-base guard: "well" is a real word and must NOT be rewritten.
    const well = vm.runInContext('applyContractionFixup("well")', sandbox);
    if (well !== 'well') {
        console.error(`paired-base guard: "well" was rewritten to "${well}"`);
        failures++;
    } else {
        console.log('paired-base guard: "well" stays "well" ok');
    }

    // Custom-word overlay reaches the app trie: insert, decodable, remove.
    const overlayOk = vm.runInContext(`(() => {
        const w = 'zzqxj';
        if (ctcAppTrie.contains(w)) return 'preexisting';
        insertCustomWordIntoCtc(w);
        const present = ctcAppTrie.contains(w);
        removeCustomWordFromCtc(w);
        const gone = !ctcAppTrie.contains(w);
        return present && gone ? 'ok' : 'present=' + present + ' gone=' + gone;
    })()`, sandbox);
    if (overlayOk !== 'ok') { console.error(`custom-word overlay: ${overlayOk}`); failures++; }
    else console.log('custom-word overlay: insert/remove on app trie ok');

    console.log(failures === 0 ? '\nSMOKE: ALL PASS' : `\nSMOKE: ${failures} FAILURE(S)`);
    process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
