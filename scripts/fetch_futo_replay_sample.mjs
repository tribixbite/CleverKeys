#!/usr/bin/env node
/**
 * fetch_futo_replay_sample.mjs — real-corpus sampler for the geometric swipe-engine
 * REPLAY validation (spec `docs/specs/geometric-swipe-engine.md`, As-Built Notes:
 * "Real-corpus replay (FUTO swipe-1/test)").
 *
 * Fetches a STRATIFIED sample of ~4000 rows from the held-out `test` split of the
 * HuggingFace dataset `futo-org/swipe.futo.org` (config `swipe-1`, MIT license) via
 * the datasets-server `/rows` JSON API (no parquet, no new deps) and writes a
 * gzipped JSONL sample to the LOCAL cache dir. The DATA is never committed — this
 * SCRIPT is the reproducibility artifact; `GeoRealCorpusReplayTest` consumes the
 * cache file (and self-skips via `Assume.assumeTrue(sampleFile.exists())` when it is
 * absent, so CI and the default suite are unaffected).
 *
 * Row schema (verified): { id, session, timestamp, word, canvas_width,
 * canvas_height, orientation, data: [{t, x, y}], sentence, word_idx,
 * potentially_invalid_sentence, distance }. x,y are ALREADY NORMALIZED [0,1]² over
 * the keyboard letter area (~60 Hz). The geometric engine's coordinate contract
 * wants key-area-local PIXELS + keyAreaWidthPx/HeightPx, so the sample stores the
 * normalized pts plus the per-row canvas w/h; the Kotlin test does the px multiply.
 *
 * Filters (spec deliverable 1):
 *   - potentially_invalid_sentence == false
 *   - >= 3 trace points
 *   - word lowercases to pure ASCII letters (apostrophes allowed — the en dictionary
 *     carries contraction canonical forms; the test still only decodes in-dictionary
 *     words and reports coverage separately).
 *
 * Output line: {"word","w","h","pts":[[x,y,t],...]} (t relative-ms, first point = 0).
 *
 * Usage:  node scripts/fetch_futo_replay_sample.mjs [--windows N] [--length L] [--target T]
 * Env:    CLEVERKEYS_TEST_CACHE overrides the output dir.
 *
 * Device note: `curl` is shimmed/broken in the Termux shell (a login-profile
 * function injects `-G`); this script uses global `fetch` (Node >= 18) only.
 */

import { createGzip } from "node:zlib";
import { createWriteStream, mkdirSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { pipeline } from "node:stream/promises";
import { Readable } from "node:stream";

// ── config ────────────────────────────────────────────────────────────────────

const DATASET = "futo-org%2Fswipe.futo.org";
const CONFIG = "swipe-1";
const SPLIT = "test";
const API = "https://datasets-server.huggingface.co/rows";

/** Parse a `--flag value` CLI arg with a numeric default. */
function intArg(flag, dflt) {
  const i = process.argv.indexOf(flag);
  if (i >= 0 && i + 1 < process.argv.length) {
    const v = parseInt(process.argv[i + 1], 10);
    if (Number.isFinite(v) && v > 0) return v;
  }
  return dflt;
}

// ~4000 kept rows target. The API caps `length` at 100 rows/request, so we stride
// WINDOWS evenly-spaced offsets across the split (stratified sampling — avoids the
// session/word-order clustering of a contiguous head slice) and pull LENGTH each.
const WINDOWS = intArg("--windows", 60);
const LENGTH = intArg("--length", 100);
const TARGET_KEPT = intArg("--target", 4000);

const CACHE_DIR =
  process.env.CLEVERKEYS_TEST_CACHE ||
  join(homedir(), ".cache", "cleverkeys-test");
const OUT_FILE = join(CACHE_DIR, "futo_swipe1_test_sample.jsonl.gz");

// ── filters ───────────────────────────────────────────────────────────────────

// Pure-letters after lowercasing, apostrophes permitted (contraction canonical
// forms exist in the en dictionary; the Kotlin side gates on actual membership).
const WORD_RE = /^[a-z']+$/;

/** True iff `word` passes the lexical filter (returns the lowercased form or null). */
function normalizeWord(word) {
  if (typeof word !== "string") return null;
  const lc = word.toLowerCase();
  if (!WORD_RE.test(lc)) return null;
  if (lc.replace(/'/g, "").length === 0) return null; // all-apostrophe guard
  return lc;
}

// ── fetch ─────────────────────────────────────────────────────────────────────

/** Fetch one window with a small bounded retry (transient HF 5xx/timeouts). */
async function fetchWindow(offset, length) {
  const url = `${API}?dataset=${DATASET}&config=${CONFIG}&split=${SPLIT}&offset=${offset}&length=${length}`;
  let lastErr;
  for (let attempt = 0; attempt < 4; attempt++) {
    try {
      const r = await fetch(url, { headers: { accept: "application/json" } });
      if (r.status === 429 || r.status >= 500) {
        throw new Error(`HTTP ${r.status}`);
      }
      if (!r.ok) throw new Error(`HTTP ${r.status} (non-retryable)`);
      return await r.json();
    } catch (e) {
      lastErr = e;
      await new Promise((res) => setTimeout(res, 500 * (attempt + 1)));
    }
  }
  throw lastErr;
}

/** Discover the split length (num_rows_total) from a probe request. */
async function discoverTotal() {
  const j = await fetchWindow(0, 1);
  return j.num_rows_total ?? 49970;
}

// ── main ──────────────────────────────────────────────────────────────────────

async function main() {
  mkdirSync(CACHE_DIR, { recursive: true });

  const total = await discoverTotal();
  console.log(`[futo-replay] split=${SPLIT} num_rows_total=${total}`);

  // Evenly-spaced window start offsets across the split (stratified strides).
  const usableWindows = Math.min(WINDOWS, Math.max(1, Math.floor(total / LENGTH)));
  const stride = Math.max(LENGTH, Math.floor(total / usableWindows));
  const offsets = [];
  for (let k = 0; k < usableWindows; k++) {
    const off = Math.min(k * stride, Math.max(0, total - LENGTH));
    offsets.push(off);
  }

  // Counters.
  let fetched = 0;
  let kept = 0;
  const dropped = {
    potentially_invalid_sentence: 0,
    too_few_points: 0,
    bad_word: 0,
    bad_data: 0,
  };

  // Collect kept lines in memory (≈4k small objects — trivial), then gzip-write.
  const lines = [];
  const seen = new Set(); // de-dup identical (word,session,word_idx) retries

  for (const offset of offsets) {
    if (kept >= TARGET_KEPT) break;
    let j;
    try {
      j = await fetchWindow(offset, LENGTH);
    } catch (e) {
      console.warn(`[futo-replay] window offset=${offset} failed: ${e.message}`);
      continue;
    }
    const rows = j.rows ?? [];
    for (const wrap of rows) {
      fetched++;
      const row = wrap.row ?? wrap;

      if (row.potentially_invalid_sentence === true) {
        dropped.potentially_invalid_sentence++;
        continue;
      }
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

      // Build the point array; timestamps rebased to relative-ms (first point 0)
      // so the absolute epoch is not leaked and the ints stay small.
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
        // round to 5 decimals (px-scale precision at any real canvas) to shrink file
        pts.push([Math.round(x * 1e5) / 1e5, Math.round(y * 1e5) / 1e5, Math.max(0, Math.round(t - t0))]);
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
      if (kept >= TARGET_KEPT) break;
    }
  }

  // Write gzipped JSONL.
  const gz = createGzip({ level: 9 });
  const out = createWriteStream(OUT_FILE);
  const body = lines.join("\n") + (lines.length ? "\n" : "");
  await pipeline(Readable.from([body]), gz, out);

  // Report (spec deliverable 1: print counts).
  console.log(`[futo-replay] fetched=${fetched} kept=${kept}`);
  console.log(`[futo-replay] dropped: ` +
    `potentially_invalid_sentence=${dropped.potentially_invalid_sentence} ` +
    `too_few_points=${dropped.too_few_points} ` +
    `bad_word=${dropped.bad_word} ` +
    `bad_data=${dropped.bad_data}`);
  console.log(`[futo-replay] wrote ${kept} rows -> ${OUT_FILE}`);
}

main().catch((e) => {
  console.error("[futo-replay] FATAL:", e);
  process.exit(1);
});
