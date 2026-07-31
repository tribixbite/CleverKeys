#!/usr/bin/env node
/**
 * convert_test2400_aligned.mjs — convert the FUTO test split
 *   ~/storage/shared/swipedata/test_hwsfuto.jsonl  ({"word","points":[{"t","x","y"}]})
 * into the {word, w, h, idx, pts:[[nx,ny,t]]} gzipped cache that
 * tools/test_cli_predict.py --corpus --frame-remap identity consumes.
 *
 * DIFFERENT from convert_swipedata_futo.mjs on purpose: that script SEED-SHUFFLES
 * and word-FILTERS a random sample. Here we need an EXACT, order-preserving 3-way
 * alignment with the FUTO reference-decoder cache (futo_decoder_test2400.jsonl, whose
 * rows carry idx=0..2399 in ORIGINAL file order). So:
 *   - NO shuffle: rows stay in original file order.
 *   - NO word filter: ALL rows are kept (OOV/capitalized targets included) so that
 *     output line k <-> original idx k <-> the FUTO cache's idx k. Each engine is then
 *     scored against its own vocabulary (an OOV target simply counts as a miss), which
 *     is exactly how the FUTO floor treated its 99 OOV rows — apples-to-apples.
 *   - word is lowercased for scoring parity (our production trie + en_enhanced are
 *     lowercase; the FUTO floor scored lowercased too).
 *   - idx is carried as provenance (the harness ignores it and emits one line per input
 *     in order, so positional alignment holds; verify output line count == kept).
 *
 * Usage: node scripts/convert_test2400_aligned.mjs [--src DIR] [--out PATH]
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
const SRC_DIR = arg("--src", "/data/data/com.termux/files/home/storage/shared/swipedata");
const CACHE = process.env.CLEVERKEYS_TEST_CACHE || join(homedir(), ".cache", "cleverkeys-test");
const OUT = arg("--out", join(CACHE, "test2400_ordered.jsonl.gz"));

async function main() {
  mkdirSync(CACHE, { recursive: true });
  const srcFile = join(SRC_DIR, "test_hwsfuto.jsonl");
  const rl = createInterface({ input: createReadStream(srcFile), crlfDelay: Infinity });

  const gz = createGzip();
  const ws = createWriteStream(OUT);
  gz.pipe(ws);

  let idx = 0; // original file line index (0-based) — the join key vs the FUTO cache
  let kept = 0;
  const dropped = { bad_data: 0, no_points: 0 };
  for await (const line of rl) {
    if (!line.trim()) continue;
    const thisIdx = idx++;
    let o;
    try { o = JSON.parse(line); } catch { dropped.bad_data++; continue; }
    const pts = o.points;
    if (!Array.isArray(pts) || pts.length < 2) { dropped.no_points++; continue; }
    const word = typeof o.word === "string" ? o.word.toLowerCase() : "";
    const t0 = pts[0].t || 0;
    const outPts = pts.map((p) => [p.x, p.y, (p.t || 0) - t0]);
    gz.write(JSON.stringify({ word, w: 360, h: 189, idx: thisIdx, pts: outPts }) + "\n");
    kept++;
  }
  await new Promise((res) => gz.end(res));
  await new Promise((res) => ws.on("close", res));
  console.error(
    `[convert-aligned] read=${idx} kept=${kept} dropped=${JSON.stringify(dropped)} -> ${OUT}\n` +
    `  NOTE: if kept < ${idx}, positional idx alignment is broken for the dropped rows; ` +
    `all 2400 test rows are expected to be well-formed (FUTO floor decoded all with 0 errors).`
  );
}
main().catch((e) => { console.error(e); process.exit(1); });
