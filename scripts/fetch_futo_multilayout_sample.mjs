#!/usr/bin/env node
/**
 * fetch_futo_multilayout_sample.mjs — real-corpus sampler for the NON-QWERTY
 * geometric swipe-engine REPLAY validation (spec
 * `docs/specs/geometric-swipe-engine.md`, As-Built Notes: "Real-corpus replay").
 *
 * The sibling `fetch_futo_replay_sample.mjs` covers QWERTY-en (config `swipe-1`).
 * This script covers the layouts the geometric engine actually EXISTS for — the
 * non-QWERTY regime — by pulling ALL rows for five layouts from the `swipe-5`
 * `train` split of `futo-org/swipe.futo.org` (MIT) via the datasets-server
 * `/filter` JSON API (no parquet, no new deps), and by fetching the OFFICIAL FUTO
 * per-layout key geometries so the replay is faithful to the data's coordinate frame.
 *
 * ## Layouts + dictionary mapping (spec deliverable)
 *   layout    language  our dictionary        (rows, dual_finger=0, lang-matched)
 *   dvorak    en        en_enhanced.bin 98k   2809   ← the KNOWN-PARTIAL direct test
 *   azerty    fr        fr_enhanced.bin 25k    2542
 *   qwertz    de        de_enhanced.bin 25k    1402
 *   german    de        de_enhanced.bin 25k    2594
 *   spanish   es        es_enhanced.bin 50k    2029
 *
 * ## Row schema (verified)
 * Each `swipe-5` row carries per-row `layout`, `language`, `dual_finger` columns in
 * addition to the swipe-1 fields: { id, session, timestamp, word, canvas_width,
 * canvas_height, orientation, data: [{t,x,y}], sentence, word_idx, language,
 * dual_finger, layout, distance }. x,y are ALREADY NORMALIZED [0,1]² over the
 * letter-area canvas; `t` is an absolute epoch-ms; `word` may be capitalized.
 *
 * ## Filters (spec deliverable 1)
 *   - `dual_finger == 0`  (single-finger swipes only — bimanual traces are a
 *     different input mode the geometric engine does not model)
 *   - `language == <expected>`  (dvorak→en, azerty→fr, qwertz/german→de, spanish→es);
 *     for qwertz this drops ~40% (qwertz hosts many non-German sessions) but those
 *     words would be OOV against the German dictionary anyway.
 *   - >= 3 trace points
 *   - `word` lowercases (Locale.ROOT-ish) to pure Unicode letters (+ apostrophe),
 *     so accented forms (é, ä, ñ) are KEPT — the Kotlin side projects them via the
 *     engine's NFD/alias tiers and reports dictionary coverage + projection failures.
 *
 * The `where` clause is URL-encoded; the `/filter` response carries
 * `rows[].row` + `num_rows_total`. Requests are paged at limit=100 (the API cap).
 *
 * ## Outputs
 *   - per-layout gzipped JSONL to the LOCAL cache
 *     `$CLEVERKEYS_TEST_CACHE|~/.cache/cleverkeys-test/futo_swipe5_<layout>.jsonl.gz`
 *     with lines `{"word","w","h","pts":[[x,y,t],...]}` (t rebased to relative-ms).
 *     The DATA is never committed — this SCRIPT is the reproducibility artifact;
 *     `GeoRealCorpusMultiLayoutTest` self-skips via `Assume` when a file is absent.
 *   - the five OFFICIAL layout geometries to
 *     `src/test/resources/layouts/futo_<layout>.json` (COMMITTED — same format as the
 *     precedent `futo_qwerty.json`; needs `git add -f` past the blanket *.json ignore).
 *
 * Usage:  node scripts/fetch_futo_multilayout_sample.mjs [--layouts a,b,...] [--limit 100]
 * Env:    CLEVERKEYS_TEST_CACHE overrides the cache dir.
 *
 * Device note: `curl` is shimmed/broken in the Termux shell (a login-profile
 * function injects `-G`); this script uses global `fetch` (Node >= 18) only.
 */

import { createGzip } from "node:zlib";
import { createWriteStream, mkdirSync, writeFileSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { pipeline } from "node:stream/promises";
import { Readable } from "node:stream";

// ── config ────────────────────────────────────────────────────────────────────

const DATASET = "futo-org%2Fswipe.futo.org";
const CONFIG = "swipe-5";
const SPLIT = "train";
const FILTER_API = "https://datasets-server.huggingface.co/filter";
const LAYOUT_BASE =
  "https://huggingface.co/datasets/futo-org/swipe.futo.org/resolve/main/swipe-5/layouts";

/** layout → the dictionary language we replay it against (spec mapping). */
const LAYOUT_LANG = {
  dvorak: "en",
  azerty: "fr",
  qwertz: "de",
  german: "de",
  spanish: "es",
};

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = join(__dirname, "..");
const LAYOUT_OUT_DIR = join(REPO_ROOT, "src", "test", "resources", "layouts");

const CACHE_DIR =
  process.env.CLEVERKEYS_TEST_CACHE ||
  join(homedir(), ".cache", "cleverkeys-test");

/** Parse a `--flag value` CLI arg with a string/number default. */
function strArg(flag, dflt) {
  const i = process.argv.indexOf(flag);
  if (i >= 0 && i + 1 < process.argv.length) return process.argv[i + 1];
  return dflt;
}
function intArg(flag, dflt) {
  const v = parseInt(strArg(flag, ""), 10);
  return Number.isFinite(v) && v > 0 ? v : dflt;
}

const LIMIT = Math.min(100, intArg("--limit", 100)); // API caps at 100/request
const LAYOUTS = strArg("--layouts", Object.keys(LAYOUT_LANG).join(","))
  .split(",")
  .map((s) => s.trim())
  .filter((s) => s in LAYOUT_LANG);

// ── word filter ────────────────────────────────────────────────────────────────

// Pure Unicode letters after lowercasing, apostrophes permitted. `\p{L}` keeps
// accented forms (é/ä/ñ) — the engine projects those via its NFD/alias tiers; the
// Kotlin side reports coverage + projection failures. Whitespace/digits/punct reject.
const WORD_RE = /^[\p{L}']+$/u;

/** True iff `word` passes the lexical filter → the lowercased form, else null. */
function normalizeWord(word) {
  if (typeof word !== "string") return null;
  const lc = word.toLowerCase();
  if (!WORD_RE.test(lc)) return null;
  if (lc.replace(/'/g, "").length === 0) return null; // all-apostrophe guard
  return lc;
}

// ── fetch helpers ────────────────────────────────────────────────────────────

/** Fetch a URL as JSON with a small bounded retry (transient HF 5xx/429/timeouts). */
async function fetchJsonRetry(url, label) {
  let lastErr;
  for (let attempt = 0; attempt < 5; attempt++) {
    try {
      const r = await fetch(url, { headers: { accept: "application/json" } });
      if (r.status === 429 || r.status >= 500) throw new Error(`HTTP ${r.status}`);
      if (!r.ok) throw new Error(`HTTP ${r.status} (non-retryable)`);
      return await r.json();
    } catch (e) {
      lastErr = e;
      await new Promise((res) => setTimeout(res, 600 * (attempt + 1)));
    }
  }
  throw new Error(`${label}: ${lastErr && lastErr.message}`);
}

/** Fetch text (layout JSON) with the same retry policy. */
async function fetchTextRetry(url, label) {
  let lastErr;
  for (let attempt = 0; attempt < 5; attempt++) {
    try {
      const r = await fetch(url);
      if (r.status === 429 || r.status >= 500) throw new Error(`HTTP ${r.status}`);
      if (!r.ok) throw new Error(`HTTP ${r.status} (non-retryable)`);
      return await r.text();
    } catch (e) {
      lastErr = e;
      await new Promise((res) => setTimeout(res, 600 * (attempt + 1)));
    }
  }
  throw new Error(`${label}: ${lastErr && lastErr.message}`);
}

/** Build a `/filter` URL for a `where` clause + paging window. */
function filterUrl(where, offset, limit) {
  const w = encodeURIComponent(where);
  return `${FILTER_API}?dataset=${DATASET}&config=${CONFIG}&split=${SPLIT}&where=${w}&offset=${offset}&limit=${limit}`;
}

// ── per-layout pipeline ─────────────────────────────────────────────────────

/** Fetch + filter every row of one layout; write the gzipped JSONL sample. */
async function processLayout(layout) {
  const lang = LAYOUT_LANG[layout];
  const where = `"layout"='${layout}' AND "dual_finger"=0 AND "language"='${lang}'`;

  // Probe for the total (num_rows_total) to page deterministically.
  const probe = await fetchJsonRetry(filterUrl(where, 0, 1), `${layout} probe`);
  const total = probe.num_rows_total ?? 0;

  let fetched = 0;
  let kept = 0;
  const dropped = { too_few_points: 0, bad_word: 0, bad_data: 0 };
  const lines = [];
  const seen = new Set(); // de-dup identical (session,word_idx,word)

  for (let offset = 0; offset < total; offset += LIMIT) {
    const j = await fetchJsonRetry(
      filterUrl(where, offset, LIMIT),
      `${layout} offset=${offset}`,
    );
    const rows = j.rows ?? [];
    for (const wrap of rows) {
      fetched++;
      const row = wrap.row ?? wrap;

      const data = row.data;
      if (!Array.isArray(data) || data.length < 3) {
        dropped.too_few_points++;
        continue;
      }
      const word = normalizeWord(row.word);
      if (!word) {
        dropped.bad_word++;
        continue;
      }
      const w = Number(row.canvas_width);
      const h = Number(row.canvas_height);
      if (!Number.isFinite(w) || !Number.isFinite(h) || w <= 0 || h <= 0) {
        dropped.bad_data++;
        continue;
      }

      // Rebase timestamps to relative-ms (first point 0) so the absolute epoch is
      // not leaked and the ints stay small; round x,y to 5 decimals (px precision).
      const t0 = Number(data[0].t) || 0;
      const pts = [];
      let ptOk = true;
      for (const p of data) {
        const x = Number(p.x);
        const y = Number(p.y);
        const t = Number(p.t);
        if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(t)) {
          ptOk = false;
          break;
        }
        pts.push([
          Math.round(x * 1e5) / 1e5,
          Math.round(y * 1e5) / 1e5,
          Math.max(0, Math.round(t - t0)),
        ]);
      }
      if (!ptOk || pts.length < 3) {
        dropped.bad_data++;
        continue;
      }

      const dedupKey = `${row.session}|${row.word_idx}|${word}`;
      if (seen.has(dedupKey)) continue;
      seen.add(dedupKey);

      lines.push(JSON.stringify({ word, w, h, pts }));
      kept++;
    }
  }

  // Write gzipped JSONL to the local cache.
  const outFile = join(CACHE_DIR, `futo_swipe5_${layout}.jsonl.gz`);
  const gz = createGzip({ level: 9 });
  const out = createWriteStream(outFile);
  const body = lines.join("\n") + (lines.length ? "\n" : "");
  await pipeline(Readable.from([body]), gz, out);

  console.log(
    `[${layout}→${lang}] total=${total} fetched=${fetched} kept=${kept}  ` +
      `dropped{few_pts=${dropped.too_few_points} bad_word=${dropped.bad_word} ` +
      `bad_data=${dropped.bad_data}}  -> ${outFile}`,
  );
  return { layout, lang, total, kept };
}

/** Fetch + write the official per-layout key geometry (committed test resource). */
async function fetchLayoutJson(layout) {
  const url = `${LAYOUT_BASE}/${layout}.json`;
  const text = await fetchTextRetry(url, `${layout}.json`);
  // Validate it parses and has the expected shape before writing.
  const obj = JSON.parse(text);
  if (!Array.isArray(obj.keys) || obj.keys.length < 20) {
    throw new Error(`${layout}.json: unexpected shape (keys=${obj.keys?.length})`);
  }
  const outFile = join(LAYOUT_OUT_DIR, `futo_${layout}.json`);
  writeFileSync(outFile, text);
  console.log(`[${layout}] layout geometry keys=${obj.keys.length} -> ${outFile}`);
}

// ── main ──────────────────────────────────────────────────────────────────────

async function main() {
  mkdirSync(CACHE_DIR, { recursive: true });
  mkdirSync(LAYOUT_OUT_DIR, { recursive: true });
  console.log(
    `[futo-multi] config=${CONFIG} split=${SPLIT} layouts=[${LAYOUTS.join(",")}]`,
  );

  // Fetch the official geometries first (cheap; commit-tracked resources).
  for (const layout of LAYOUTS) await fetchLayoutJson(layout);

  // Then the traces (all rows per layout).
  const summary = [];
  for (const layout of LAYOUTS) summary.push(await processLayout(layout));

  console.log("\n[futo-multi] per-layout kept:");
  for (const s of summary) {
    console.log(`  ${s.layout.padEnd(8)} ${s.lang}  kept=${s.kept}/${s.total}`);
  }
}

main().catch((e) => {
  console.error("[futo-multi] FATAL:", e);
  process.exit(1);
});
