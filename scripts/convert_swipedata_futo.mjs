#!/usr/bin/env node
/**
 * convert_swipedata_futo.mjs — convert the raw upstream FUTO swipe JSONL the user
 * placed at ~/storage/shared/swipedata/{test,val,train}_hwsfuto.jsonl
 *   schema: {"word", "points":[{"t","x","y"}], "id", "session", "timestamp", "source"}
 *   coords already normalized to ~[0,1] over the FUTO qwerty canvas; t = real ms.
 *
 * → the {word, w, h, id, pts:[[nx,ny,t],...]} gzipped cache the neural harness
 *   (tools/test_cli_predict.py --corpus --frame-remap futo) consumed. --frame-remap
 *   futo ignored w/h for coordinates (x=360*nx, y=4.5+177*ny), so w/h are provenance
 *   only; we pass nominal 360x189.
 *
 * NOTE (2026-08-28, ARC-047): that harness was DELETED — its
 * swipe_{encoder,decoder}_android.onnx models went with ADR-011. The cache format is
 * still the corpus interchange shape for the FUTO reference-decoder scripts.
 *
 * SEEDED random sample (mulberry32) with the SAME filters as fetch_futo_train100k.mjs:
 *   word lowercases to /^[a-z']+$/ (a-z + apostrophe), >= 3 points, non-empty.
 * The original trace `id` is carried through so the per-trace cache is resumable/dedupable.
 *
 * Usage: node scripts/convert_swipedata_futo.mjs --split val --n 20000 --seed 20260724 --out PATH
 */
import { createGzip } from "node:zlib";
import { createWriteStream, mkdirSync, createReadStream } from "node:fs";
import { createInterface } from "node:readline";
import { homedir } from "node:os";
import { join } from "node:path";

function arg(flag, dflt) {
  const i = process.argv.indexOf(flag);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : dflt;
}
const SPLIT = arg("--split", "val");
const N = parseInt(arg("--n", "20000"), 10);
const SEED = parseInt(arg("--seed", "20260724"), 10);
const SRC_DIR = arg("--src", "/data/data/com.termux/files/home/storage/shared/swipedata");
const CACHE = process.env.CLEVERKEYS_TEST_CACHE || join(homedir(), ".cache", "cleverkeys-test");
const OUT = arg("--out", join(CACHE, `swipedata_${SPLIT}_seed${SEED}_n${N}.jsonl.gz`));

// mulberry32 seeded PRNG (same family as the fetch scripts) for reproducible shuffle.
function mulberry32(a) {
  return function () {
    let t = (a += 0x6d2b79f5);
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const WORD_RE = /^[a-z']+$/;
function normWord(w) {
  if (typeof w !== "string") return null;
  const lc = w.toLowerCase();
  if (!WORD_RE.test(lc)) return null;
  if (lc.replace(/'/g, "").length === 0) return null;
  return lc;
}

async function main() {
  mkdirSync(CACHE, { recursive: true });
  const srcFile = join(SRC_DIR, `${SPLIT}_hwsfuto.jsonl`);
  const rl = createInterface({ input: createReadStream(srcFile), crlfDelay: Infinity });

  // First pass: collect all valid (filtered) rows into a compact array.
  const kept = [];
  const dropped = { bad_word: 0, too_few_points: 0, bad_data: 0 };
  for await (const line of rl) {
    if (!line.trim()) continue;
    let o;
    try { o = JSON.parse(line); } catch { dropped.bad_data++; continue; }
    const w = normWord(o.word);
    if (!w) { dropped.bad_word++; continue; }
    const pts = o.points;
    if (!Array.isArray(pts) || pts.length < 3) { dropped.too_few_points++; continue; }
    kept.push({ word: w, id: o.id, pts });
  }

  // Seeded Fisher-Yates shuffle, then take first N.
  const rand = mulberry32(SEED);
  for (let i = kept.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [kept[i], kept[j]] = [kept[j], kept[i]];
  }
  const sample = N > 0 ? kept.slice(0, N) : kept;

  const gz = createGzip();
  const ws = createWriteStream(OUT);
  gz.pipe(ws);
  for (const row of sample) {
    // pts -> [[nx,ny,t],...]; t kept as REAL relative ms (points already start near t=0).
    const t0 = row.pts[0].t || 0;
    const outPts = row.pts.map((p) => [p.x, p.y, (p.t || 0) - t0]);
    gz.write(JSON.stringify({ word: row.word, w: 360, h: 189, id: row.id, pts: outPts }) + "\n");
  }
  await new Promise((res) => gz.end(res));
  await new Promise((res) => ws.on("close", res));
  console.error(
    `[convert] split=${SPLIT} seed=${SEED} kept=${kept.length} sampled=${sample.length} ` +
    `dropped=${JSON.stringify(dropped)} -> ${OUT}`
  );
}
main().catch((e) => { console.error(e); process.exit(1); });
