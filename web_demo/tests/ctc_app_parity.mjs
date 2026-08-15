/**
 * Golden-fixture parity gate for the APP CTC engine in the web demo.
 *
 * Runs the REAL demo JS (`demo/ctc-engine.js`, loaded verbatim into a Node VM
 * the same way the a22b76ad neural smoke does) plus the REAL shipped ONNX
 * (`web_demo/ctc_swipe_encoder.onnx`, byte-identical to the APK asset
 * `models/ctc_swipe_encoder.onnx`) against the repo's golden fixture
 * `src/test/resources/ctc/ctc_golden.json` — the same fixture the Kotlin port
 * (`CtcFeaturizer` / `CtcBeamDecoder`) is gated on.
 *
 * Gates:
 *  - featurize cases: bit-exact float32 match (the fixture stores the Python
 *    port's np.float32 output; the JS featurizer writes into a Float32Array,
 *    so every element must compare `===`).
 *  - beam cases: featurize(points) must ALSO be bit-exact; then the shipped
 *    ONNX runs via onnxruntime-web (wasm) on the fixture layout, emissions are
 *    sliced by the demo's sliceEmissions, the fixture lexicon is built through
 *    CtcTrie.fromFrequencyMap (the app-trie STRIP builder), and the demo's
 *    futoViterbiBeam at the per-case params must reproduce `greedy` and the
 *    `topk` words exactly, scores within 1e-3.
 *
 * Run: cd web_demo/tests && bun install && node ctc_app_parity.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.resolve(HERE, '..');
const REPO = path.resolve(WEB, '..');
const FIXTURE = path.join(REPO, 'src/test/resources/ctc/ctc_golden.json');
const MODEL = path.join(WEB, 'ctc_swipe_encoder.onnx');

const SCORE_TOL = 1e-3;

// ── load the real demo module into a VM sandbox ─────────────────────────────
const sandbox = {
    console, Math, Object, Array, Number, String, Set, Map, JSON,
    Uint8Array, Uint16Array, Int32Array, Float32Array, Float64Array,
    DataView, ArrayBuffer, Error, RegExp, Infinity, performance,
};
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync(path.join(WEB, 'demo/ctc-engine.js'), 'utf8'),
    sandbox, { filename: 'ctc-engine.js' });
const CTC = sandbox.CTC;

const fixture = JSON.parse(fs.readFileSync(FIXTURE, 'utf8'));

let failures = 0;
const fail = (msg) => { failures++; console.error(`  FAIL ${msg}`); };
const pass = (msg) => console.log(`  ok   ${msg}`);

// ── featurize cases: bit-exact f32 ──────────────────────────────────────────
console.log('== featurize (bit-exact float32) ==');
for (const c of fixture.cases.filter((x) => x.kind === 'featurize')) {
    const got = CTC.featurize(c.points.x, c.points.y, c.points.t);
    const want = Float32Array.from(c.features);
    let bad = -1;
    for (let i = 0; i < 128; i++) if (got[i] !== want[i]) { bad = i; break; }
    if (bad >= 0) fail(`${c.name}: [${bad}] got ${got[bad]} want ${want[bad]}`);
    else pass(`${c.name}: 128/128 exact`);
}

// ── beam cases: real ONNX via onnxruntime-web + demo beam ───────────────────
const ort = require('onnxruntime-web');
ort.env.wasm.numThreads = 1;
ort.env.wasm.simd = true;
ort.env.wasm.wasmPaths = path.join(HERE, 'node_modules/onnxruntime-web/dist/') ;

const MAX_KEYS = 64;
function fixtureLayoutTensors(layout) {
    const keys = new Float32Array(MAX_KEYS * 2);
    const mask = new Uint8Array(MAX_KEYS);
    for (let i = 0; i < layout.letters.length; i++) {
        keys[i * 2] = layout.cx[i];
        keys[i * 2 + 1] = layout.cy[i];
        mask[i] = 1;
    }
    return {
        layout_keys: new ort.Tensor('float32', keys, [1, MAX_KEYS, 2]),
        layout_mask: new ort.Tensor('bool', mask, [1, MAX_KEYS]),
    };
}

const modelBytes = fs.readFileSync(MODEL);
const session = await ort.InferenceSession.create(new Uint8Array(modelBytes), {
    executionProviders: ['wasm'],
    graphOptimizationLevel: 'all',
});
console.log(`\nmodel: ${path.basename(MODEL)} (${modelBytes.length} B)`);
console.log(`  inputs:  ${session.inputNames.join(', ')}`);
console.log(`  outputs: ${session.outputNames.join(', ')}`);

console.log('\n== beam (real ONNX + demo trie/beam vs golden) ==');
for (const c of fixture.cases.filter((x) => x.kind === 'beam')) {
    // 1. featurizer must be bit-exact on the beam case's own points too.
    const features = CTC.featurize(c.points.x, c.points.y, c.points.t);
    const wantFeat = Float32Array.from(c.features);
    let featBad = -1;
    for (let i = 0; i < 128; i++) if (features[i] !== wantFeat[i]) { featBad = i; break; }
    if (featBad >= 0) {
        fail(`${c.name}: featurize [${featBad}] got ${features[featBad]} want ${wantFeat[featBad]}`);
        continue;
    }

    // 2. run the shipped ONNX on the fixture layout.
    const feeds = {
        features: new ort.Tensor('float32', features, [1, 2, CTC.RESAMPLE_LENGTH]),
        ...fixtureLayoutTensors(fixture.layout),
    };
    const out = await session.run(feeds);
    const emissions = out.log_emissions;
    const T = emissions.dims[1];
    if (T !== c.frames) { fail(`${c.name}: frames ${T} != ${c.frames}`); continue; }
    const logProbs = CTC.sliceEmissions(emissions.data, T);

    // Informational cross-check: ORT-web emissions vs the fixture's recorded
    // (already-sliced) emissions. Platform/EP float drift is expected; the
    // hard gate below is on the decode outcome, not on this number.
    let maxEmisDiff = 0;
    for (let t = 0; t < T; t++) {
        for (let k = 0; k < c.numClasses; k++) {
            const d = Math.abs(logProbs[t * c.numClasses + k] - c.emissions[t][k]);
            if (d > maxEmisDiff) maxEmisDiff = d;
        }
    }

    // 3. fixture lexicon through the app-trie STRIP builder, per-case params.
    const trie = CTC.CtcTrie.fromFrequencyMap(c.lexicon);
    const params = {
        beamWidth: c.params.beamWidth,
        topK: c.params.topK,
        gamma: c.params.gamma,
        lambda: c.params.lambda,
        beta: c.params.beta,
        gammaPrune: c.params.gammaPrune,
        betaPrune: c.params.betaPrune,
    };
    const got = CTC.futoViterbiBeam(logProbs, T, trie, params);
    const greedy = CTC.greedyCtc(logProbs, T);

    let ok = true;
    if (greedy !== c.greedy) { fail(`${c.name}: greedy "${greedy}" != "${c.greedy}"`); ok = false; }
    if (got.length !== c.topk.length) {
        fail(`${c.name}: topk length ${got.length} != ${c.topk.length}`); ok = false;
    } else {
        for (let i = 0; i < got.length; i++) {
            const [word, score] = c.topk[i];
            if (got[i].word !== word) {
                fail(`${c.name}: topk[${i}] "${got[i].word}" != "${word}"`); ok = false;
            } else if (Math.abs(got[i].score - score) > SCORE_TOL) {
                fail(`${c.name}: topk[${i}] "${word}" score ${got[i].score} vs ${score} ` +
                     `(|Δ|=${Math.abs(got[i].score - score).toExponential(2)} > ${SCORE_TOL})`);
                ok = false;
            }
        }
    }
    if (ok) {
        const worst = Math.max(...got.map((g, i) => Math.abs(g.score - c.topk[i][1])));
        pass(`${c.name}: greedy="${greedy}" topk=[${got.map((g) => g.word).join(', ')}] ` +
             `maxScoreΔ=${worst.toExponential(2)} emissionsΔ=${maxEmisDiff.toExponential(2)}`);
    }
}

console.log(failures === 0
    ? '\nPARITY: ALL CASES PASS'
    : `\nPARITY: ${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);
