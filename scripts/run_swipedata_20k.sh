#!/data/data/com.termux/files/usr/bin/bash
# run_swipedata_20k.sh — resumable chunked neural decode of the 20,318-trace
# swipedata eval cache through the production-equivalent harness.
#
# Writes part files neural_swipedata.partNN.jsonl (chunk of CHUNK in-dict traces).
# Resume: counts already-written lines across parts, --skip past them. Idempotent.
#
# Production PROD column uses internal beam width 6 (Config NEURAL_BEAM_WIDTH);
# --beam 2 only affects the bare RAW/FILT columns (kept cheap).
set -u
HOME_ABS=/data/data/com.termux/files/home
REPO=$HOME_ABS/git/swype/cleverkeys
CACHE=$HOME_ABS/.cache/cleverkeys-test
CORPUS=$CACHE/swipedata_eval20k.jsonl.gz
CHUNK=${CHUNK:-2500}
TOTAL_INDICT=${TOTAL_INDICT:-19773}   # from swipedata_oov.py: 20318 - 545 OOV
BEAM=${BEAM:-2}
THREADS=${THREADS:-3}

cd "$REPO" || exit 1

done_lines() {
  cat "$CACHE"/neural_swipedata.part*.jsonl 2>/dev/null | wc -l
}

while :; do
  SKIP=$(done_lines)
  if [ "$SKIP" -ge "$TOTAL_INDICT" ]; then
    echo "[run20k] complete: $SKIP >= $TOTAL_INDICT in-dict traces decoded"
    break
  fi
  PART=$(printf "%02d" $((SKIP / CHUNK)))
  OUT=$CACHE/neural_swipedata.part${PART}.jsonl
  # If this part exists and is partial, restart it cleanly from its aligned skip.
  ALIGNED_SKIP=$(( (SKIP / CHUNK) * CHUNK ))
  echo "[run20k] skip=$ALIGNED_SKIP chunk=$CHUNK -> part${PART}"
  # Production-faithful features: identity 0-1 coords + training-exact velocity/accel.
  python3 tools/test_cli_predict.py \
    --corpus "$CORPUS" --frame-remap identity --training-features --production \
    --beam "$BEAM" --threads "$THREADS" \
    --skip "$ALIGNED_SKIP" --limit "$CHUNK" \
    --out "$OUT" 2>&1 | rg -i 'decoded|ERROR|Traceback' | tail -3
done
