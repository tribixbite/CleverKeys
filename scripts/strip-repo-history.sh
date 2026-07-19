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
# F-DROID GOTCHAS (hard-won, verified 2026-07-15/16):
#   - fdroiddata build blocks pin `commit: <FULL SHA>` — NOT the tag name —
#     even though AutoUpdateMode is "Version v%v". Published versions are
#     never re-fetched (safe to orphan their SHAs); PENDING versions are not.
#   - The update bot detects releases by scraping the GITHUB RELEASE BODY
#     for the literal lines `Version: X.Y.Z` and `VersionCode: NNNNN`
#     (UpdateCheckMode: HTTP + regex). release.yml emits them in the Build
#     Information section. NEVER remove/reword those two lines when editing
#     a release body (`gh release edit`) — v1.2.9–v1.3.0 were silently never
#     picked up after they went missing.
#   - Reproducible-build verification compares APK bytes rebuilt from the
#     pinned source against the GitHub release APKs. It depends on the TREE
#     (source content), not commit SHAs — which is why Gate 3 (tree
#     identity) is sufficient to keep verification working across a rewrite.
#   - Publish ≠ build: after a cycle builds the APK it still needs signing +
#     index publication (typically a few extra hours). The gate here checks
#     the PUBLISHED index (api/v1/packages), the only state that matters.
#
# GIF ASSET PACKS — MUST REMAIN DOWNLOADABLE:
#   - All 22 pack files (~1.2 GB: discord-community-*.zip, thumbs-*.zip,
#     gifs_v2.db.gz) are RELEASE ASSETS on the `CleverKeys-GIF` release.
#     Users download them from GitHub (the app has no INTERNET permission;
#     packs import via SAF). Release assets live in GitHub blob storage,
#     OUTSIDE git objects — a history rewrite cannot touch them.
#   - Releases stay attached to their tag NAME across force-updates, and
#     `git push --mirror` re-creates the (rewritten) CleverKeys-GIF tag, so
#     the release keeps its tag anchor. Do NOT delete that tag or release,
#     and never prune it from the mirror before pushing.
#   - The 601 MB stripped from history (tools/gif_pipeline/discord_processed)
#     was the accidentally-committed WORKING SET used to build the packs —
#     not the downloadable packs themselves.
#
# AFTER a successful --push run:
#   - Re-clone all working copies (old clones' SHAs are orphaned):
#       cd ~/git/swype && mv cleverkeys cleverkeys.old \
#         && git clone https://github.com/tribixbite/CleverKeys.git cleverkeys
#       then carry over untracked files from cleverkeys.old and delete it
#       (the old copy also carries an ~18 GB local pack — reclaim it).
#   - Add .gitignore guards if not already present: .gradle/, build/,
#     *.apk at repo root, release_apks/, *_apk/ dirs.
#   - Spot-check afterwards: CleverKeys-GIF release still lists 22 assets
#     (`gh release view CleverKeys-GIF --json assets --jq '.assets|length'`),
#     v1.5.0 release intact, CI green on rewritten main.
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

# --- Auth preflight: fail fast if we can't reach/authenticate to the remote --
# Cheaper than discovering a bad credential AFTER the full mirror + filter run.
git ls-remote "$REPO_URL" >/dev/null || { echo "FATAL: cannot reach/authenticate to $REPO_URL"; exit 1; }
echo "Auth preflight OK: $REPO_URL reachable"

# --- Gate 2: fresh mirror ----------------------------------------------------
echo "Fresh mirror clone into $WORK ..."
git clone --mirror "$REPO_URL" "$WORK"
cd "$WORK"
PRE_SIZE=$(du -sh . | cut -f1)

# Pristine pre-filter backup: a bundle of ALL refs, written OUTSIDE $WORK so a
# botched filter/push is fully recoverable (git clone <bundle> restores it).
git bundle create "$WORK/../ck-prefilter-$(date +%s).bundle" --all
echo "Backup: pre-filter bundle written to $WORK/../ck-prefilter-*.bundle"

# Snapshot pre-filter tree hashes for every ref we must preserve.
PRE_TREES="$WORK/pre-trees.txt"
for ref in "${MUST_MATCH[@]}"; do
    echo "$ref $(git rev-parse "$ref^{tree}")" >> "$PRE_TREES"
done

# --- Filter -------------------------------------------------------------------
git filter-repo --invert-paths --paths-from-file "$STRIP_LIST" --force

# Drop GitHub's read-only pull-request refs (refs/pull/*). A mirror clone
# carries them, but `git push --mirror` tries to update them and GitHub rejects
# the write — which, under `set -e`, aborts the whole push. Delete them so the
# mirror push only touches heads + tags.
git for-each-ref --format='delete %(refname)' refs/pull | git update-ref --stdin

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
    git push --mirror --atomic "$REPO_URL"
    echo "PUSHED. Now re-clone working copies (see header) and verify:"
    echo "  gh run list --limit 3   # CI on rewritten main"
else
    echo "DRY RUN complete. Mirror verified at $WORK. Re-run with --push to publish."
fi
