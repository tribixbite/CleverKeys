#!/data/data/com.termux/files/usr/bin/bash
# run_futo100k_fixed_loop.sh — drive run_futo100k_fixed.sh chunk-by-chunk until
# TARGET corrected traces exist, then exit. One background invocation completes the
# whole corrected-path neural decode; each inner call is a resumable CHUNK.
set -u
HOME_ABS=/data/data/com.termux/files/home
REPO=$HOME_ABS/git/swype/cleverkeys
CACHE=$HOME_ABS/.cache/cleverkeys-test
export CHUNK=${CHUNK:-400}
export TARGET=${TARGET:-10000}
cd "$REPO" || exit 1

done_lines() { cat "$CACHE"/neural_futo100k_fixed.part*.jsonl 2>/dev/null | wc -l; }

while :; do
  D=$(done_lines)
  if [ "$D" -ge "$TARGET" ]; then
    echo "[loop] DONE: $D >= $TARGET corrected traces"
    break
  fi
  bash scripts/run_futo100k_fixed.sh
  D2=$(done_lines)
  echo "[loop] progress: $D -> $D2 / $TARGET"
  # Safety: if a chunk made no progress, stop to avoid a hot spin.
  if [ "$D2" -le "$D" ]; then
    echo "[loop] ABORT: no progress this iteration ($D -> $D2) — inspect harness output"
    exit 2
  fi
done
