/**
 * In-browser test harness for the swipe demo's three engines.
 *
 * Injected into a live page (see README) via
 *   `const m = await import('/tests/browser_test.mjs'); await m.runAll();`
 * and driven from a real Chrome through the puppeteer MCP tools.
 *
 * It replays the *same* synthetic trajectories `ctc_reference.py` decoded in
 * Python, through the *same* entry point a finger-drawn swipe uses
 * (`window.processSwipe`), and reports three things:
 *
 *   - per-engine per-word top-1 correctness
 *   - featurizer + beam parity against the Python reference
 *   - per-engine decode latency (mean of N repeats)
 *
 * Everything returned is small and JSON-serialisable so the driver never has
 * to pull a page snapshot back.
 *
 * @module browser_test
 */

/** The demo's internal coordinate frame (index.html NORMALIZED_WIDTH/HEIGHT). */
const NORMALIZED_WIDTH = 360;
const NORMALIZED_HEIGHT = 215;

/** @returns {Promise<object>} the reference bundle written by ctc_reference.py */
async function loadReference() {
    const response = await fetch('/tests/reference.json', { cache: 'no-store' });
    if (!response.ok) throw new Error(`reference.json: HTTP ${response.status}`);
    return response.json();
}

/**
 * Turn `[0,1]` letter-area points into the swipeData shape the pointer
 * handlers build, so the decode runs through the production entry point
 * rather than a test-only shortcut.
 * @param {{x:number[], y:number[], t:number[]}} swipe
 * @returns {{path: Array<{x:number,y:number,timestamp:number}>,
 *            keySequence: Array<{key:string}>, duration:number, word:string}}
 */
function toSwipeData(swipe) {
    const path = swipe.x.map((x, i) => ({
        x: x * NORMALIZED_WIDTH,
        y: swipe.y[i] * NORMALIZED_HEIGHT,
        timestamp: swipe.t[i],
    }));
    return {
        path,
        // The transformer's rerank reads keySequence.length as the expected
        // word length; derive it from the grid the same way the live capture
        // does, by recording each distinct nearest key along the path.
        keySequence: nearestKeySequence(path),
        duration: swipe.t[swipe.t.length - 1] - swipe.t[0],
        word: '',
    };
}

/**
 * Distinct nearest-key run along a path, mirroring what continueSwipe()
 * accumulates while a finger moves.
 * @param {Array<{x:number,y:number}>} path
 * @returns {Array<{key:string}>}
 */
function nearestKeySequence(path) {
    const rows = [
        { keys: 'qwertyuiop', offset: 0.0 },
        { keys: 'asdfghjkl', offset: 0.05 },
        { keys: 'zxcvbnm', offset: 0.15 },
    ];
    const centers = [];
    rows.forEach((row, r) => {
        for (let i = 0; i < row.keys.length; i++) {
            centers.push({
                char: row.keys[i],
                cx: row.offset + i * 0.1 + 0.05,
                cy: r / 3 + 1 / 6,
            });
        }
    });
    const out = [];
    let previous = null;
    for (const point of path) {
        const nx = point.x / NORMALIZED_WIDTH;
        const ny = point.y / NORMALIZED_HEIGHT;
        let best = null;
        let bestDist = Infinity;
        for (const key of centers) {
            const dx = nx - key.cx;
            const dy = ny - key.cy;
            const d = dx * dx + dy * dy;
            if (d < bestDist) { bestDist = d; best = key.char; }
        }
        if (best !== previous) { out.push({ key: best }); previous = best; }
    }
    return out;
}

/** @param {number} ms @returns {Promise<void>} */
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Select an engine in the dropdown and wait for its model to finish loading,
 * exactly as a user clicking the <select> would.
 * @param {string} engineId
 */
async function selectEngine(engineId) {
    const select = document.getElementById('engineSelect');
    select.value = engineId;
    select.dispatchEvent(new Event('change'));
    // onEngineChange is async and not awaited by the event dispatch; poll the
    // status line instead of guessing a fixed delay.
    for (let i = 0; i < 600; i++) {
        const status = document.getElementById('engineStatus').textContent;
        if (status === 'ready' || status.startsWith('load failed')) break;
        await sleep(100);
    }
    const status = document.getElementById('engineStatus').textContent;
    if (status.startsWith('load failed')) throw new Error(`${engineId}: ${status}`);
}

/**
 * Correctness sweep: every test word through every available engine.
 * @param {object} reference
 * @returns {Promise<object>} { [engineId]: { words: {...}, top1: n, total: n } }
 */
export async function runWordTests(reference) {
    const results = {};
    for (const engineId of availableEngineIds()) {
        await selectEngine(engineId);
        const words = {};
        let hits = 0;
        for (const word of reference.testWords) {
            const decode = await window.processSwipe(toSwipeData(reference.swipes[word]));
            const top1 = decode && decode.words.length ? decode.words[0] : null;
            const ok = top1 === word;
            if (ok) hits++;
            words[word] = {
                top1,
                ok,
                top5: decode ? decode.words.slice(0, 5) : [],
                totalMs: decode ? Number(decode.timing.totalMs.toFixed(2)) : null,
            };
        }
        results[engineId] = { words, top1: hits, total: reference.testWords.length };
    }
    return results;
}

/**
 * Strict JS-vs-Python parity on the fixed parity paths.
 *
 * The featurizer is compared elementwise against Python's float32 output; the
 * beam is compared on top-1 (exact string) and on the whole top-8 ordering.
 * The beam runs off the browser's own ONNX emissions, so a top-8 disagreement
 * would implicate either the beam port or WASM-vs-native float drift — the
 * per-candidate score deltas in the report separate the two.
 * @param {object} reference
 * @returns {Promise<object>}
 */
export async function runParityTests(reference) {
    const out = { featurizer: {}, beam: {} };

    // ── featurizer: pure function, no model involved ────────────────────────
    let worstFeature = 0;
    for (const word of reference.parityWords) {
        const swipe = reference.swipes[word];
        const actual = window.CTC.featurize(swipe.x, swipe.y, swipe.t);
        const expected = reference.features[word];
        if (actual.length !== expected.length) {
            throw new Error(`featurize(${word}): length ${actual.length} vs ${expected.length}`);
        }
        let worst = 0;
        for (let i = 0; i < actual.length; i++) {
            worst = Math.max(worst, Math.abs(actual[i] - expected[i]));
        }
        worstFeature = Math.max(worstFeature, worst);
        out.featurizer[word] = { maxAbsDiff: worst, length: actual.length };
    }
    out.featurizerMaxAbsDiff = worstFeature;

    // ── beam: run the browser's emissions through the JS beam ───────────────
    for (const engineId of availableEngineIds().filter((id) => id.startsWith('ctc_'))) {
        await selectEngine(engineId);
        const perWord = {};
        for (const word of reference.parityWords) {
            const swipe = reference.swipes[word];
            const decode = await window.processSwipe(toSwipeData(reference.swipes[word]));
            const expected = reference.engines[engineId][word];
            const jsWords = decode.candidates.map((c) => c.word);
            const pyWords = expected.candidates.map((c) => c.word);
            let worstScore = 0;
            for (let i = 0; i < Math.min(jsWords.length, pyWords.length); i++) {
                if (jsWords[i] !== pyWords[i]) break;
                worstScore = Math.max(
                    worstScore,
                    Math.abs(decode.candidates[i].score - expected.candidates[i].score));
            }
            perWord[word] = {
                top1Match: jsWords[0] === pyWords[0],
                top8Match: JSON.stringify(jsWords) === JSON.stringify(pyWords),
                greedyMatch: decode.greedy === expected.greedy,
                maxScoreDiff: worstScore,
                js: jsWords,
                py: pyWords,
            };
            // The points reach the engine via the 360x215 round-trip that a
            // real swipe uses, so scores can differ in the last float bits;
            // the top-1 string must not.
            void swipe;
        }
        out.beam[engineId] = perWord;
    }
    return out;
}

/**
 * Mean decode latency per engine over `repeats` runs of a mid-length word.
 * @param {object} reference
 * @param {number} [repeats=10]
 * @returns {Promise<object>}
 */
export async function runLatency(reference, repeats = 10) {
    const results = {};
    const word = 'keyboard';
    for (const engineId of availableEngineIds()) {
        await selectEngine(engineId);
        const swipeData = toSwipeData(reference.swipes[word]);
        await window.processSwipe(swipeData);   // warm-up, excluded
        const totals = [];
        const feats = [];
        const infers = [];
        const beams = [];
        for (let i = 0; i < repeats; i++) {
            const decode = await window.processSwipe(swipeData);
            totals.push(decode.timing.totalMs);
            feats.push(decode.timing.featurizeMs);
            infers.push(decode.timing.inferMs);
            if (decode.timing.beamMs !== undefined) beams.push(decode.timing.beamMs);
        }
        const mean = (arr) => (arr.length
            ? Number((arr.reduce((a, b) => a + b, 0) / arr.length).toFixed(2))
            : null);
        results[engineId] = {
            repeats,
            meanTotalMs: mean(totals),
            meanFeaturizeMs: mean(feats),
            meanInferMs: mean(infers),
            meanBeamMs: mean(beams),
            minTotalMs: Number(Math.min(...totals).toFixed(2)),
            maxTotalMs: Number(Math.max(...totals).toFixed(2)),
        };
    }
    return results;
}

/** @returns {string[]} ids of engines the page reports as usable */
function availableEngineIds() {
    return Array.from(document.getElementById('engineSelect').options)
        .filter((option) => !option.disabled)
        .map((option) => option.value);
}

/** Wait until loadModels() has finished bringing engines up. */
async function waitForReady(timeoutMs = 180000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        if (window.isModelReady) return;
        await sleep(250);
    }
    throw new Error('timed out waiting for window.isModelReady');
}

/**
 * Full sweep. Returns a compact, JSON-serialisable report.
 * @param {{repeats?: number}} [options]
 * @returns {Promise<object>}
 */
export async function runAll(options = {}) {
    await waitForReady();
    const reference = await loadReference();
    const engines = availableEngineIds();
    const words = await runWordTests(reference);
    const parity = await runParityTests(reference);
    const latency = await runLatency(reference, options.repeats || 10);

    // Leave the UI showing a real CTC result for the screenshot.
    await selectEngine(engines.find((id) => id.startsWith('ctc_')) || engines[0]);
    await window.processSwipe(toSwipeData(reference.swipes.keyboard));

    return {
        engines,
        vocab: window.ctcVocabStats || null,
        words,
        parity,
        latency,
        pythonTop1: Object.fromEntries(Object.entries(reference.engines).map(
            ([id, per]) => [id, reference.testWords.filter((w) => per[w].top1 === w).length])),
    };
}

export { toSwipeData, availableEngineIds, selectEngine, loadReference };
