#!/usr/bin/env node
/**
 * build_local_corpus_replay.mjs — LOCAL-CORPUS converter for the geometric
 * swipe-engine REPLAY validation (spec `docs/specs/geometric-swipe-engine.md`,
 * As-Built Notes: "Real-corpus replay — LOCAL COMBINED CORPUS").
 *
 * Reads the neural model's held-out combined-corpus test set (a LOCAL file, never
 * committed — it is the SAME distribution the ONNX pipeline is scored on) and writes
 * a gzipped JSONL cache in the `{word, w, h, pts}` shape the existing geometric
 * replay loaders consume (`GeoRealCorpusReplayTest.loadSample` /
 * `GeoLocalCorpusReplayTest.loadSample`). The SCRIPT is the reproducibility artifact;
 * the DATA stays LOCAL-ONLY. `GeoLocalCorpusReplayTest` self-skips via
 * `Assume.assumeTrue(cache.exists())` when the cache is absent, so a clean checkout
 * and CI are unaffected.
 *
 * ── Input (verified) ──────────────────────────────────────────────────────────
 * `~/storage/shared/Download/combined_english_swipes_test.jsonl.txt` — 8,607 lines,
 * one JSON object per line:
 *   {"curve": {"x": [float...], "y": [float...], "t": [int ms from 0...],
 *              "grid_name": "qwerty_english"}, "word": "..."}
 * Coordinates are RAW PIXELS over the `qwerty_english` grid canvas. Measured extents
 * across the whole file: x ∈ [0.87, 360.00], y ∈ [0.00, 215.00] → the canonical grid
 * canvas is 360 × 215 px (the same frame `tools/test_cli_predict.py` /
 * `model/train_character_model.py` define the per-key centroids in; the neural
 * pipeline's separate `/280` normalization is an unrelated model-input squash and is
 * NOT the geometric coordinate frame).
 *
 * ── Output line ───────────────────────────────────────────────────────────────
 *   {"word", "w", "h", "pts": [[x, y, t], ...]}   where
 *     - w, h  = the pixel canvas the pts are NORMALIZED against (360, 215), matching
 *               the loader contract (`toTrace` multiplies each point by w, h to get
 *               key-area-local pixels for the engine).
 *     - x, y  = normalized [0,1] over the canvas (px / 360, px / 215).
 *     - t     = relative ms from the first point (first point → 0).
 *
 * ── Filters (spec deliverable 1 — every drop counted + reported) ──────────────
 *   - too_few_points : < 3 trace points (degenerate; engine emits empty result).
 *   - bad_word       : word does NOT lowercase to pure ASCII letters [a-z]+.
 *                      NOTE the shipped en dictionary contains ZERO apostrophe words,
 *                      so apostrophe words (e.g. "don't") are untypeable BY
 *                      CONSTRUCTION — they are dropped here and counted in
 *                      `bad_word` with an `apostrophe` sub-tally so the report is
 *                      explicit about the untypeable class.
 *   - bad_data       : a non-finite x/y/t, or mismatched x/y array lengths.
 *   The word IS lowercased into the output (the dictionary/engine emit the canonical
 *   lowercase surface form); coverage vs the dictionary is measured in Kotlin.
 *
 * Usage:  node scripts/build_local_corpus_replay.mjs [--input PATH] [--limit N]
 * Env:    CLEVERKEYS_TEST_CACHE overrides the output dir (default ~/.cache/cleverkeys-test).
 *
 * Device note: `curl`/`grep` are shimmed in the Termux login shell; this script uses
 * only Node stdlib (no network, no deps).
 */

import { createGzip } from "node:zlib";
import { createWriteStream, mkdirSync, existsSync, createReadStream } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { pipeline } from "node:stream/promises";
import { Readable } from "node:stream";
import { createInterface } from "node:readline";

// ── config ──────────────────────────────────────────────────────────────────

/** Canonical `qwerty_english` grid canvas (px). See file header + tools/test_cli_predict.py. */
const CANVAS_W = 360;
const CANVAS_H = 215;

/** Parse a `--flag value` CLI arg (string default). */
function strArg(flag, dflt) {
  const i = process.argv.indexOf(flag);
  if (i >= 0 && i + 1 < process.argv.length) return process.argv[i + 1];
  return dflt;
}

/** Parse a `--flag value` CLI arg (numeric default, 0 = unlimited). */
function intArg(flag, dflt) {
  const i = process.argv.indexOf(flag);
  if (i >= 0 && i + 1 < process.argv.length) {
    const v = parseInt(process.argv[i + 1], 10);
    if (Number.isFinite(v) && v >= 0) return v;
  }
  return dflt;
}

const INPUT = strArg(
  "--input",
  join(homedir(), "storage/shared/Download/combined_english_swipes_test.jsonl.txt"),
);
const LIMIT = intArg("--limit", 0); // 0 = all
const CACHE_DIR = process.env.CLEVERKEYS_TEST_CACHE || join(homedir(), ".cache/cleverkeys-test");
const OUT_FILE = join(CACHE_DIR, "combined_english_swipes.jsonl.gz");

// ── conversion ────────────────────────────────────────────────────────────────

async function main() {
  if (!existsSync(INPUT)) {
    console.error(`[local-replay] FATAL: input not found: ${INPUT}`);
    console.error(`[local-replay] (data is LOCAL-ONLY; nothing to do). Skipping.`);
    process.exit(2);
  }
  mkdirSync(CACHE_DIR, { recursive: true });

  let read = 0;
  let kept = 0;
  const dropped = { too_few_points: 0, bad_word: 0, bad_word_apostrophe: 0, bad_data: 0, unparseable: 0 };
  const lines = [];

  const rl = createInterface({
    input: createReadStream(INPUT, { encoding: "utf-8" }),
    crlfDelay: Infinity,
  });

  for await (const raw of rl) {
    const line = raw.trim();
    if (!line) continue;
    read++;
    if (LIMIT && read > LIMIT) {
      read--; // don't count the overflow line
      break;
    }

    let obj;
    try {
      obj = JSON.parse(line);
    } catch {
      dropped.unparseable++;
      continue;
    }

    const curve = obj && obj.curve;
    const rawWord = obj && obj.word;
    if (!curve || typeof rawWord !== "string") {
      dropped.bad_data++;
      continue;
    }

    // ── word filter: lowercases to pure ASCII letters ─────────────────────────
    const word = rawWord.toLowerCase();
    if (!/^[a-z]+$/.test(word)) {
      dropped.bad_word++;
      // Sub-tally the apostrophe class explicitly: the en dictionary carries ZERO
      // apostrophe words, so these are untypeable by construction (spec deliverable 1).
      if (word.includes("'") || word.includes("’")) dropped.bad_word_apostrophe++;
      continue;
    }

    const xs = curve.x;
    const ys = curve.y;
    const ts = curve.t;
    if (!Array.isArray(xs) || !Array.isArray(ys) || xs.length !== ys.length) {
      dropped.bad_data++;
      continue;
    }
    if (xs.length < 3) {
      dropped.too_few_points++;
      continue;
    }

    // ── build normalized pts; drop the row on any non-finite sample ────────────
    const hasT = Array.isArray(ts) && ts.length === xs.length;
    const t0 = hasT ? Number(ts[0]) || 0 : 0;
    const pts = [];
    let ok = true;
    for (let i = 0; i < xs.length; i++) {
      const px = Number(xs[i]);
      const py = Number(ys[i]);
      const t = hasT ? Number(ts[i]) : i; // fall back to sample index when t absent
      if (!Number.isFinite(px) || !Number.isFinite(py) || !Number.isFinite(t)) {
        ok = false;
        break;
      }
      // Normalize px → [0,1] over the canonical canvas; round to 5 decimals to shrink.
      const nx = Math.round((px / CANVAS_W) * 1e5) / 1e5;
      const ny = Math.round((py / CANVAS_H) * 1e5) / 1e5;
      pts.push([nx, ny, Math.max(0, Math.round(t - t0))]);
    }
    if (!ok || pts.length < 3) {
      dropped.bad_data++;
      continue;
    }

    lines.push(JSON.stringify({ word, w: CANVAS_W, h: CANVAS_H, pts }));
    kept++;
  }

  // Write gzipped JSONL (deterministic ordering = input order).
  const gz = createGzip({ level: 9 });
  const out = createWriteStream(OUT_FILE);
  const body = lines.join("\n") + (lines.length ? "\n" : "");
  await pipeline(Readable.from([body]), gz, out);

  // Report (spec deliverable 1: print every filter drop).
  console.log(`[local-replay] input:  ${INPUT}`);
  console.log(`[local-replay] canvas: ${CANVAS_W} x ${CANVAS_H} px (qwerty_english grid)`);
  console.log(`[local-replay] read=${read} kept=${kept}`);
  console.log(
    `[local-replay] dropped: ` +
      `too_few_points=${dropped.too_few_points} ` +
      `bad_word=${dropped.bad_word} (of which apostrophe=${dropped.bad_word_apostrophe}) ` +
      `bad_data=${dropped.bad_data} ` +
      `unparseable=${dropped.unparseable}`,
  );
  console.log(`[local-replay] wrote ${kept} rows -> ${OUT_FILE}`);
}

main().catch((e) => {
  console.error("[local-replay] FATAL:", e);
  process.exit(1);
});
