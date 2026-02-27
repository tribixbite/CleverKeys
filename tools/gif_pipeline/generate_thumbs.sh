#!/bin/bash
# generate_thumbs.sh — Extract first-frame thumbnails from animated WebPs
# Uses webpmux to extract frame 1 as a static WebP thumbnail.
# For static (non-animated) WebPs, copies the file directly.
#
# Usage: ./generate_thumbs.sh <input_dir> <output_dir> [jobs]

set -euo pipefail

INDIR="${1:?Usage: $0 <input_dir> <output_dir> [jobs]}"
OUTDIR="${2:?Usage: $0 <input_dir> <output_dir> [jobs]}"
JOBS="${3:-$(( $(nproc) / 2 ))}"
[ "$JOBS" -lt 1 ] && JOBS=1

mkdir -p "$OUTDIR"

TOTAL=$(find "$INDIR" -maxdepth 1 -name "*.webp" -type f | wc -l)
EXISTING=$(find "$OUTDIR" -maxdepth 1 -name "*.webp" -type f 2>/dev/null | wc -l)
COUNTERFILE="$OUTDIR/.thumb-counter"

echo "Generate Thumbnails — First Frame Extraction"
echo "Input:   $INDIR ($TOTAL files)"
echo "Output:  $OUTDIR"
echo "Workers: $JOBS"
echo "Already: $EXISTING (will be skipped)"
echo ""

echo "$EXISTING" > "$COUNTERFILE"

find "$INDIR" -maxdepth 1 -name "*.webp" -type f -size +100c -print0 \
  | xargs -0 -P "$JOBS" -I{} bash -c '
    FNAME=$(basename "{}")
    OUTFILE="'"$OUTDIR"'/$FNAME"

    # Skip if already exists
    if [ -f "$OUTFILE" ] && [ "$(stat -c%s "$OUTFILE" 2>/dev/null || echo 0)" -gt 0 ]; then
      # Still count for progress
      (
        flock -x 200
        N=$(cat "'"$COUNTERFILE"'" 2>/dev/null || echo 0)
        N=$((N + 1))
        echo "$N" > "'"$COUNTERFILE"'"
      ) 200>"'"$COUNTERFILE"'.lock"
      exit 0
    fi

    # Try extracting frame 1 (works for animated WebP)
    webpmux -get frame 1 "{}" -o "$OUTFILE" 2>/dev/null
    if [ $? -ne 0 ] || [ ! -f "$OUTFILE" ] || [ "$(stat -c%s "$OUTFILE" 2>/dev/null || echo 0)" -eq 0 ]; then
      # Not animated or extraction failed — copy as-is (already static)
      cp "{}" "$OUTFILE" 2>/dev/null
    fi

    # Progress counter
    (
      flock -x 200
      N=$(cat "'"$COUNTERFILE"'" 2>/dev/null || echo 0)
      N=$((N + 1))
      echo "$N" > "'"$COUNTERFILE"'"
      if [ $((N % 2000)) -eq 0 ]; then
        echo "[$(date +%H:%M:%S)] $N / '"$TOTAL"' thumbnails"
      fi
    ) 200>"'"$COUNTERFILE"'.lock"
  '

echo ""
echo "=== COMPLETE ==="
DONE=$(find "$OUTDIR" -maxdepth 1 -name "*.webp" -type f 2>/dev/null | wc -l)
echo "Thumbnails: $DONE"

# Size stats
if [ "$DONE" -gt 0 ]; then
  TOTAL_BYTES=$(find "$OUTDIR" -maxdepth 1 -name "*.webp" -type f -exec stat -c%s {} + 2>/dev/null | paste -sd+ | bc)
  AVG=$((TOTAL_BYTES / DONE))
  echo "Total size: $(( TOTAL_BYTES / 1024 / 1024 )) MB"
  echo "Avg thumb:  $(( AVG / 1024 )) KB"
fi

rm -f "$COUNTERFILE" "$COUNTERFILE.lock"
