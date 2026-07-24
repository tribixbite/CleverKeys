#!/usr/bin/env node
/**
 * fetch_futo_train100k.mjs — SEEDED random ~100k-trace sample of the FUTO swipe
 * dataset's big split (`swipe-1/train`, 939,550 rows — "the 1M dataset",
 * arXiv 2606.25247, MIT) for the neural-vs-geometric head-to-head RE-RUN on
 * clean timestamps (the local 8.5k corpus has corrupt t → position-only bias).
 *
 * Methodology (recorded for the report):
 *  - Seeded LCG PRNG (SEED below) draws WINDOW start offsets uniformly over the
 *    split; each window pulls LENGTH rows via the datasets-server /rows API
 *    (cap 100/request). Cluster sampling at 100-row granularity: rows within a
 *    window may share session locality, mitigated by drawing many windows.
 *  - Filters IDENTICAL to fetch_futo_replay_sample.mjs (invalid-sentence flag,
 *    >= 3 points, word lowercases to /^[a-z']+$/), words stored lowercased.
 *  - Kept rows are SHUFFLED with the same PRNG before writing, so "first N
 *    lines" of the output is itself a seeded random subset (the neural engine
 *    decodes a labeled head-N subset; the geometric engine decodes all).
 *  - Output format bit-compatible with the existing replay caches:
 *    {"word","w","h","pts":[[nx,ny,t],...]} (t relative-ms).
 *
 * Usage: node scripts/fetch_futo_train100k.mjs [--target N] [--windows W] [--conc C]
 * Output: ~/.cache/cleverkeys-test/futo_train100k.jsonl.gz (LOCAL-ONLY, never committed)
 */

import { createGzip } from "node:zlib";
import { createWriteStream, mkdirSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { pipeline } from "node:stream/promises";
import { Readable } from "node:stream";

const DATASET = "futo-org%2Fswipe.futo.org";
const CONFIG = "swipe-1";
const SPLIT = "train";
const API = "https://datasets-server.huggingface.co/rows";

/** Fixed seed — the report records it; rerunning reproduces the exact sample. */
const SEED = 20260723;

function intArg(flag, dflt) {
  const i = process.argv.indexOf(flag);
  if (i >= 0 && i + 1 < process.argv.length) {
    const v = parseInt(process.argv[i + 1], 10);
    if (Number.isFinite(v) && v > 0) return v;
  }
  return dflt;
}

const TARGET_KEPT = intArg("--target", 100000);
const LENGTH = 100; // API cap
const WINDOWS = intArg("--windows", 1400); // 1400×100 = 140k raw → filters → ≥100k kept
const CONC = intArg("--conc", 6);

const CACHE_DIR =
  process.env.CLEVERKEYS_TEST_CACHE || join(homedir(), ".cache", "cleverkeys-test");
const OUT_FILE = join(CACHE_DIR, "futo_train100k.jsonl.gz");

// ── seeded PRNG (mulberry32) ─────────────────────────────────────────────────
function mulberry32(a) {
  return function () {
    let t = (a += 0x6d2b79f5);
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rand = mulberry32(SEED);

const WORD_RE = /^[a-z']+$/;
function normalizeWord(word) {
  if (typeof word !== "string") return null;
  const lc = word.toLowerCase();
  if (!WORD_RE.test(lc)) return null;
  if (lc.replace(/'/g, "").length === 0) return null;
  return lc;
}

async function fetchWindow(offset, length) {
  const url = `${API}?dataset=${DATASET}&config=${CONFIG}&split=${SPLIT}&offset=${offset}&length=${length}`;
  let lastErr;
  for (let attempt = 0; attempt < 5; attempt++) {
    try {
      const r = await fetch(url, { headers: { accept: "application/json" } });
      if (r.status === 429 || r.status >= 500) throw new Error(`HTTP ${r.status}`);
      if (!r.ok) throw new Error(`HTTP ${r.status} (non-retryable)`);
      return await r.json();
    } catch (e) {
      lastErr = e;
      await new Promise((res) => setTimeout(res, 700 * (attempt + 1)));
    }
  }
  throw lastErr;
}

async function main() {
  mkdirSync(CACHE_DIR, { recursive: true });

  const probe = await fetchWindow(0, 1);
  const total = probe.num_rows_total ?? 939550;
  console.log(`[futo-100k] split=${SPLIT} num_rows_total=${total} seed=${SEED}`);

  // Seeded random, DISTINCT, window-aligned offsets (aligned to LENGTH so windows
  // never overlap → no dedup pressure from the sampler itself).
  const maxWin = Math.floor((total - LENGTH) / LENGTH);
  const offsetSet = new Set();
  while (offsetSet.size < Math.min(WINDOWS, maxWin)) {
    offsetSet.add(Math.floor(rand() * maxWin) * LENGTH);
  }
  const offsets = [...offsetSet];

  let fetched = 0;
  let kept = 0;
  let failedWindows = 0;
  const dropped = { invalid_sentence: 0, too_few_points: 0, bad_word: 0, bad_data: 0 };
  const lines = [];
  const seen = new Set();

  function processRows(rows) {
    for (const wrap of rows) {
      if (kept >= TARGET_KEPT) return;
      fetched++;
      const row = wrap.row ?? wrap;
      if (row.potentially_invalid_sentence === true) { dropped.invalid_sentence++; continue; }
      const data = row.data;
      if (!Array.isArray(data) || data.length < 3) { dropped.too_few_points++; continue; }
      const word = normalizeWord(row.word);
      if (!word) { dropped.bad_word++; continue; }
      const w = Number(row.canvas_width);
      const h = Number(row.canvas_height);
      if (!Number.isFinite(w) || !Number.isFinite(h) || w <= 0 || h <= 0) { dropped.bad_data++; continue; }
      const t0 = Number(data[0].t) || 0;
      const pts = [];
      let ptOk = true;
      for (const p of data) {
        const x = Number(p.x);
        const y = Number(p.y);
        const t = Number(p.t);
        if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(t)) { ptOk = false; break; }
        pts.push([Math.round(x * 1e5) / 1e5, Math.round(y * 1e5) / 1e5, Math.max(0, Math.round(t - t0))]);
      }
      if (!ptOk || pts.length < 3) { dropped.bad_data++; continue; }
      const dedupKey = `${row.session}|${row.word_idx}|${word}`;
      if (seen.has(dedupKey)) continue;
      seen.add(dedupKey);
      lines.push(JSON.stringify({ word, w, h, pts }));
      kept++;
    }
  }

  // Bounded-concurrency window fetch.
  let next = 0;
  async function worker(id) {
    while (next < offsets.length && kept < TARGET_KEPT) {
      const idx = next++;
      const offset = offsets[idx];
      try {
        const j = await fetchWindow(offset, LENGTH);
        processRows(j.rows ?? []);
      } catch (e) {
        failedWindows++;
        console.warn(`[futo-100k] window offset=${offset} FAILED: ${e.message}`);
      }
      if (idx % 100 === 0) {
        console.log(`[futo-100k] progress: windows=${idx}/${offsets.length} kept=${kept}`);
      }
    }
  }
  await Promise.all(Array.from({ length: CONC }, (_, i) => worker(i)));

  // Seeded Fisher–Yates shuffle → head-N of the file is a random subset.
  for (let i = lines.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [lines[i], lines[j]] = [lines[j], lines[i]];
  }

  const gz = createGzip({ level: 9 });
  const out = createWriteStream(OUT_FILE);
  await pipeline(Readable.from([lines.join("\n") + (lines.length ? "\n" : "")]), gz, out);

  console.log(`[futo-100k] fetched=${fetched} kept=${kept} failedWindows=${failedWindows}`);
  console.log(`[futo-100k] dropped: ${JSON.stringify(dropped)}`);
  console.log(`[futo-100k] wrote ${kept} rows (SHUFFLED, seed=${SEED}) -> ${OUT_FILE}`);
}

main().catch((e) => {
  console.error(`[futo-100k] FATAL: ${e?.stack || e}`);
  process.exit(1);
});
