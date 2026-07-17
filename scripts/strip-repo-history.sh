#!/data/data/com.termux/files/usr/bin/bash
# strip-repo-history.sh — one-time git history rewrite to shrink clone size.
#
# WHY: fresh clones were ~987 MB. ~1.2 GB of history-only blobs (committed
# debug-APK dumps, .gradle cache, tools/gif_pipeline/discord_processed GIF
# working set, stray binaries) were identified 2026-07-16; none exist in any
# current tree. Stripping them shrinks the mirror to ~195 MB.
#
# SAFETY MODEL (do not weaken):
#   1. F-DROID GATE — fdroiddata pins builds by COMMIT SHA (not tag name).
#      Rewriting history before a pending version is PUBLISHED deletes the
#      pinned SHA and permanently breaks that build. This script refuses to
#      run until f-droid.org reports suggestedVersionCode >= MIN_PUBLISHED.
#   2. FRESH CLONE — always re-mirror from GitHub at run time. Never push a
#      stale pre-filtered mirror: commits landed since filtering would be
#      silently erased by `git push --mirror`.
#   3. TREE IDENTITY — after filtering, refs/heads/main and every tag in
#      MUST_MATCH must have byte-identical trees to pre-filter. Identical
#      trees == identical source checkout == F-Droid reproducible builds
#      and release APK rebuilds are unaffected. Older tags MAY legitimately
#      change (their trees contained the junk); F-Droid never re-fetches
#      published versions, so that is harmless.
#   4. NO IMPLICIT PUSH — pushing requires the literal argument `--push`.
#      Without it the script stops after verification and prints the size.
#
# AFTER a successful --push run:
#   - Re-clone all working copies (old clones' SHAs are orphaned):
#       cd ~/git/swype && mv cleverkeys cleverkeys.old \
#         && git clone https://github.com/tribixbite/CleverKeys.git cleverkeys
#       then carry over untracked files from cleverkeys.old and delete it
#       (the old copy also carries an ~18 GB local pack — reclaim it).
#   - Add .gitignore guards if not already present: .gradle/, build/,
#     *.apk at repo root, release_apks/, *_apk/ dirs.
#   - GIF packs are NOT affected: they are RELEASE ASSETS on the
#     CleverKeys-GIF release (GitHub blob storage, outside git objects);
#     releases stay attached to their tag NAME across force-updates.
#
# Usage:
#   scripts/strip-repo-history.sh            # dry run: clone, filter, verify
#   scripts/strip-repo-history.sh --push     # ...then actually force-push
#
# Requires: git-filter-repo (pip install git-filter-repo), rg, python3.

set -euo pipefail
export PATH=/data/data/com.termux/files/usr/bin:$HOME/.local/bin:$PATH

REPO_URL="https://github.com/tribixbite/CleverKeys.git"
APP_ID="tribixbite.cleverkeys"
MIN_PUBLISHED=105001            # v1.5.0 ABI floor — the pending build's gate
MUST_MATCH=(refs/heads/main refs/tags/v1.5.0 refs/tags/v1.4.0)
WORK="$HOME/ck-strip-$(date +%s)"
STRIP_LIST="$(cd "$(dirname "$0")" && pwd)/strip-repo-history-paths.txt"

[ -f "$STRIP_LIST" ] || { echo "FATAL: $STRIP_LIST missing"; exit 1; }
command -v git-filter-repo >/dev/null || { echo "FATAL: git-filter-repo not installed"; exit 1; }

# --- Gate 1: F-Droid has PUBLISHED the pending version -----------------------
CODE=$(curl -s --max-time 30 "https://f-droid.org/api/v1/packages/$APP_ID" \
  | python3 -c "import json,sys; print(json.load(sys.stdin).get('suggestedVersionCode',0))")
if [ "${CODE:-0}" -lt "$MIN_PUBLISHED" ]; then
    echo "REFUSING: F-Droid suggestedVersionCode=$CODE < $MIN_PUBLISHED."
    echo "fdroiddata pins the pending build by COMMIT SHA; rewriting now would break it."
    echo "Wait for the release-watch notification, then re-run."
    exit 1
fi
echo "Gate 1 OK: F-Droid published code $CODE (>= $MIN_PUBLISHED)"

# --- Gate 2: fresh mirror ----------------------------------------------------
echo "Fresh mirror clone into $WORK ..."
git clone --mirror "$REPO_URL" "$WORK"
cd "$WORK"
PRE_SIZE=$(du -sh . | cut -f1)

# Snapshot pre-filter tree hashes for every ref we must preserve.
PRE_TREES="$WORK/pre-trees.txt"
for ref in "${MUST_MATCH[@]}"; do
    echo "$ref $(git rev-parse "$ref^{tree}")" >> "$PRE_TREES"
done

# --- Filter -------------------------------------------------------------------
git filter-repo --invert-paths --paths-from-file "$STRIP_LIST" --force
POST_SIZE=$(du -sh . | cut -f1)

# --- Gate 3: tree identity ------------------------------------------------------
FAIL=0
while read -r ref pre; do
    post=$(git rev-parse "$ref^{tree}" 2>/dev/null || echo MISSING)
    if [ "$pre" = "$post" ]; then
        echo "VERIFY $ref: tree identical"
    else
        echo "VERIFY $ref: TREE MISMATCH pre=$pre post=$post"; FAIL=1
    fi
done < "$PRE_TREES"
[ "$FAIL" -eq 0 ] || { echo "FATAL: protected ref tree changed — NOT pushing. Mirror left at $WORK"; exit 1; }

echo "Size: $PRE_SIZE -> $POST_SIZE"

# --- Push (explicit only) -------------------------------------------------------
if [ "${1:-}" = "--push" ]; then
    echo "Force-pushing rewritten history (git push --mirror) in 5s — Ctrl-C to abort"
    sleep 5
    git push --mirror "$REPO_URL"
    echo "PUSHED. Now re-clone working copies (see header) and verify:"
    echo "  gh run list --limit 3   # CI on rewritten main"
else
    echo "DRY RUN complete. Mirror verified at $WORK. Re-run with --push to publish."
fi
