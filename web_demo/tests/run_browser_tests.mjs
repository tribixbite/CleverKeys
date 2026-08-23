/**
 * Headless runner for the demo's engine test sweep.
 *
 * Drives a real Chrome over `browser_test.mjs` and writes both a machine
 * readable report and the screenshots referenced by the README. The same
 * harness is what the puppeteer MCP session executes interactively; this file
 * exists so the run is reproducible from a shell:
 *
 *   python3 web_demo/tests/ctc_reference.py          # regenerate reference.json
 *   python3 web_demo/serve.py --port 8765 &          # serve the demo
 *   node web_demo/tests/run_browser_tests.mjs        # drive Chrome, write results
 *
 * Options:
 *   --url <url>       page to test (default http://127.0.0.1:8765/demo/)
 *   --chrome <path>   Chrome executable (default $CHROME_PATH or /usr/bin/google-chrome)
 *   --repeats <n>     latency repeats per engine (default 10)
 *   --out <dir>       output directory (default alongside this file)
 */

import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';

const HERE = dirname(fileURLToPath(import.meta.url));

/**
 * @param {string[]} argv
 * @returns {{url:string, chrome:string, repeats:number, out:string}}
 */
function parseArgs(argv) {
    const get = (flag, fallback) => {
        const i = argv.indexOf(flag);
        return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
    };
    return {
        url: get('--url', 'http://127.0.0.1:8765/demo/'),
        chrome: get('--chrome', process.env.CHROME_PATH || '/usr/bin/google-chrome'),
        repeats: Number(get('--repeats', '10')),
        out: get('--out', HERE),
    };
}

/**
 * Pretty per-engine summary table.
 * @param {object} report
 * @returns {string}
 */
function formatSummary(report) {
    const lines = [];
    lines.push('engine        top-1     mean ms   featurize  nn      beam');
    for (const engine of report.engines) {
        const words = report.words[engine];
        const lat = report.latency[engine];
        lines.push(
            `${engine.padEnd(13)} ${String(`${words.top1}/${words.total}`).padEnd(9)} ` +
            `${String(lat.meanTotalMs).padEnd(9)} ${String(lat.meanFeaturizeMs).padEnd(10)} ` +
            `${String(lat.meanInferMs).padEnd(7)} ${lat.meanBeamMs ?? '-'}`);
    }
    return lines.join('\n');
}

async function main() {
    const args = parseArgs(process.argv.slice(2));
    const shotDir = join(args.out, 'screenshots');
    await mkdir(shotDir, { recursive: true });

    const browser = await puppeteer.launch({
        executablePath: args.chrome,
        headless: true,
        args: ['--no-sandbox', '--disable-dev-shm-usage'],
        defaultViewport: { width: 900, height: 1200 },
    });

    try {
        const page = await browser.newPage();
        const pageErrors = [];
        page.on('pageerror', (error) => pageErrors.push(String(error)));
        page.on('console', (msg) => {
            if (msg.type() === 'error') pageErrors.push(`console.error: ${msg.text()}`);
        });

        await page.goto(args.url, { waitUntil: 'load', timeout: 120000 });
        await page.waitForFunction('window.isModelReady === true', { timeout: 180000 });

        const report = await page.evaluate(async (repeats) => {
            const harness = await import('/tests/browser_test.mjs');
            window.__harness = harness;
            return harness.runAll({ repeats });
        }, args.repeats);
        report.pageErrors = pageErrors;

        // Screenshots: one per engine, each showing a real decode.
        for (const engine of report.engines) {
            await page.evaluate(async (engineId) => {
                const harness = window.__harness;
                const reference = await harness.loadReference();
                // eslint-disable-next-line no-undef
                inputText = '';
                document.getElementById('inputText').textContent = '';
                await harness.selectEngine(engineId);
                const word = engineId === 'transformer' ? 'world' : 'keyboard';
                await window.processSwipe(harness.toSwipeData(reference.swipes[word]));
                window.scrollTo(0, 0);
            }, engine);
            const path = join(shotDir, `${engine}.png`);
            await page.screenshot({ path, clip: { x: 0, y: 0, width: 900, height: 620 } });
            report.screenshots = report.screenshots || {};
            report.screenshots[engine] = path;
        }

        const resultsPath = join(args.out, 'results.json');
        await writeFile(resultsPath, `${JSON.stringify(report, null, 2)}\n`);
        console.log(formatSummary(report));
        console.log(`\nfeaturizer max |diff| vs python: ${report.parity.featurizerMaxAbsDiff}`);
        for (const [engine, per] of Object.entries(report.parity.beam)) {
            for (const [word, detail] of Object.entries(per)) {
                console.log(`  ${engine} ${word}: top1=${detail.top1Match} ` +
                            `top8=${detail.top8Match} maxScoreDiff=${detail.maxScoreDiff.toExponential(2)}`);
            }
        }
        if (pageErrors.length) console.log(`\npage errors: ${pageErrors.length}`);
        console.log(`\nwrote ${resultsPath}`);
        return report.engines.every((e) => report.words[e].top1 > 0) && pageErrors.length === 0 ? 0 : 1;
    } finally {
        await browser.close();
    }
}

process.exitCode = await main();
