#!/data/data/com.termux/files/usr/bin/bash
# run_futo100k_fixed.sh — resumable chunked CORRECTED-PATH neural decode of the
# futo_train100k in-dict traces (97,887) through the production-equivalent harness.
#
# Corrected path (per docs/eval/2026-07-24-harness-conversion-audit.md):
#   --frame-remap identity --training-features --production
#   (raw [0,1] coords straight through, training-exact velocity/accel, prod beam).
#
# Writes NEW part files neural_futo100k_fixed.partNN.jsonl — NEVER mixes with the
# defective-path neural_futo100k.* caches. Resume: counts already-written lines
# across the *_fixed.part* files, --skip past them. Idempotent, one CHUNK per run.
#
# Trace order == geo_futo100k.jsonl idx order (verified 0 mismatches): both read
# futo_train100k.jsonl.gz in file order, filter by the 98,140-word en_enhanced dict,
# slice the resulting in-dict list. neural line k <-> geo idx k for futo100k_metrics.py.
set -u
HOME_ABS=/data/data/com.termux/files/home
REPO=$HOME_ABS/git/swype/cleverkeys
CACHE=$HOME_ABS/.cache/cleverkeys-test
CORPUS=$CACHE/futo_train100k.jsonl.gz
CHUNK=${CHUNK:-400}
TOTAL_INDICT=${TOTAL_INDICT:-97887}
TARGET=${TARGET:-10000}       # stop once this many corrected traces exist (0 = all)
BEAM=${BEAM:-2}
THREADS=${THREADS:-3}

cd "$REPO" || exit 1

done_lines() {
  cat "$CACHE"/neural_futo100k_fixed.part*.jsonl 2>/dev/null | wc -l
}

RAW_SKIP=$(done_lines)
STOP=$TOTAL_INDICT
if [ "$TARGET" -gt 0 ] && [ "$TARGET" -lt "$STOP" ]; then STOP=$TARGET; fi
if [ "$RAW_SKIP" -ge "$STOP" ]; then
  echo "[futo100k_fixed] complete: $RAW_SKIP >= $STOP corrected traces decoded"
  exit 0
fi
# Align to CHUNK boundaries so an interrupted partial part restarts cleanly (no gaps,
# clean overwrite of the partial part). Mirrors run_swipedata_20k.sh's ALIGNED_SKIP.
PART_IDX=$(( RAW_SKIP / CHUNK ))
SKIP=$(( PART_IDX * CHUNK ))
# Cap this chunk so we never overshoot STOP.
REMAIN=$(( STOP - SKIP ))
THIS_CHUNK=$CHUNK
if [ "$REMAIN" -lt "$CHUNK" ]; then THIS_CHUNK=$REMAIN; fi
PART=$(printf "%02d" "$PART_IDX")
OUT=$CACHE/neural_futo100k_fixed.part${PART}.jsonl
echo "[futo100k_fixed] aligned_skip=$SKIP chunk=$THIS_CHUNK -> part${PART}  (raw_done=$RAW_SKIP / stop=$STOP)"
python3 tools/test_cli_predict.py \
  --corpus "$CORPUS" --frame-remap identity --training-features --production \
  --beam "$BEAM" --threads "$THREADS" \
  --skip "$SKIP" --limit "$THIS_CHUNK" \
  --out "$OUT" 2>&1 | rg -i 'decoded|ERROR|Traceback' | tail -3
NEW=$(done_lines)
echo "[futo100k_fixed] now $NEW corrected traces total"
