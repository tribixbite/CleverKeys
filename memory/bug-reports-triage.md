# Bug Reports — Triage Queue

**Purpose:** Rolling collection of user-reported bugs to investigate as a
batch. Bugs are logged here as reported; deep investigation + fixes happen
in one focused pass once the user signals the batch is ready.

**Status convention:**
- 🆕 `REPORTED` — logged, not yet investigated
- 🔍 `INVESTIGATING` — root-cause analysis in progress
- 🛠️ `FIX-IN-PROGRESS`
- ✅ `FIXED` (commit ref)
- ❌ `WONTFIX` / `CANT-REPRO` (with reason)

> **Do not start investigation until:** (a) current feature/commit work is
> finished AND (b) the user signals the batch is complete. The user is still
> adding reports.

---

## B1 — Valid plural autocorrected to a distant word ✅ FIXED

**Fixed:** morphological guard in `WordPredictor.autoCorrect` +
`autocorrect/Morphology.kt` (commit on main, B1). `immunizations` now stays
unchanged. 9 pure + 2 instrumented tests. Original analysis below for record.

---

## B1 (original analysis) — Valid plural autocorrected to a distant word

**Reported:** 2026-06-02 (user)

**Symptom:** Typing `immunizations` autocorrects to `organizations`.

**Why it happens (initial hypothesis — UNVERIFIED):**
`immunizations` is a valid English plural but is (apparently) absent from the
bundled dictionary (`en_enhanced`). The Tier-A adjacency-weighted autocorrect
in `WordPredictor.autoCorrect` therefore treats it as a typo and searches for
candidates. `immunizations` (13 chars) and `organizations` (13 chars) are the
**same length**, so the same-length dual-gate path runs:
- Position-by-position exact matches: `…nizations` tail aligns →
  roughly 9/13 ≈ 0.69 exact ratio, clearing `MIN_SAME_LENGTH_EXACT_RATIO`
  (0.50).
- Weighted adjacency score is high because the shared 9-char tail contributes
  1.0 per position.
- `organizations` has higher dictionary frequency, so it wins the tiebreaker.

**Root issue is two-fold:**
1. **Missing morphology** — the dictionary lacks regular plurals/inflections,
   so legitimately-typed plurals look like typos.
2. **Over-aggressive correction** — autocorrect will rewrite a long,
   well-formed word into a *distant* real word. A 4-position-different
   13-char word is almost never a fat-finger typo of another 13-char word.

**Fix directions to evaluate (during batch):**
- (a) Morphological guard: before correcting, check if the typed word is
  `<dictWord> + {s, es, ed, ing, ...}` where the stem IS in the dict →
  treat as valid, skip correction. (Cheapest, highest-value.)
- (b) Plural generation: synthesize regular plurals into the dictionary at
  load time (watch memory — `en_enhanced` is ~50k words; see MEMORY notes on
  in-place cache mutation).
- (c) Tighten same-length acceptance for long words: require a higher exact
  ratio (or cap the number of differing positions) as word length grows, so
  `organizations` can't win for a 4-diff 13-char input.
- (d) "Don't correct toward a word that shares only a common suffix" —
  penalize candidates whose match is dominated by a shared inflectional tail.

**Suspected files:**
- `src/main/kotlin/tribixbite/cleverkeys/WordPredictor.kt` — `autoCorrect()`,
  the four-tier selection + dual-gate scoring (Tier A, just shipped).
- `src/main/kotlin/tribixbite/cleverkeys/autocorrect/KeyAdjacency.kt`
- Dictionary loading: `BinaryDictionaryLoader.kt`, `en_enhanced.*`
- Tests: `src/androidTest/.../AutocorrectTest.kt`,
  `src/test/.../autocorrect/KeyAdjacencyTest.kt`

**Spec to update after fix:** `docs/wiki/specs/typing/autocorrect-spec.md`

---

## B2 — Copying a GIF to clipboard yields a static JPG 🔍 ROOT-CAUSED (fix needs decision)

**Reported:** 2026-06-02 (user)

**Symptom:** Copying a GIF to the clipboard appears to copy only a static JPG
frame, not the animated GIF.

**Root cause (verified in code, 2026-06-03):**
The "Copy GIF" long-press action (`KeyboardReceiver.showGifPopup`, ~line 585)
copies the **animated WebP** full file (`gifs/full/{id}.webp`, see
`Gif.getFullPath()`) via `FileProvider.getUriForFile` + `ClipData.newUri`.
`FileProvider.getType()` correctly resolves `.webp` → `image/webp` (NOT
octet-stream — verified webp is in Android's MimeTypeMap). So our side puts a
valid animated `image/webp` on the system clipboard.

The "static JPG" is therefore **receiver-side**: many Android apps don't
render animated WebP pasted from the clipboard — they grab the first frame
and often re-encode it as JPEG. Animated-WebP clipboard support is spotty.
Note GIFs are cached locally as WebP only; the original Giphy asset is a
`.gif` (`giphy.gif` URL) but there's **no INTERNET permission** to fetch it
(GIF packs are imported via SAF), so we can't trivially copy a real `.gif`.

Also note: a plain **tap** on a GIF inserts the Giphy **URL as text**
(`onGifSelected`, line 343), not an image — so the only image-copy path is
the long-press "Copy GIF" action above.

**Fix options (need a decision — none is a trivial patch):**
1. **Transcode WebP → animated GIF at copy time** and copy that as
   `image/gif` (far more universally animated on the clipboard). Cost:
   animated-GIF encoding on Android isn't built-in — needs a GIF89a encoder
   (manual frame encoding or a small library) + an animated-WebP decoder to
   read frames. Moderate effort, no new permission.
2. **Serve via a ContentProvider advertising multiple MIME types**
   (`image/gif` + `image/webp`) and transcode on read — more complex, same
   encoder requirement as (1).
3. **Accept as a platform limitation** and document it (some apps just won't
   animate clipboard images); optionally improve the toast to say
   "Copied as animated WebP — some apps show a still frame."
4. **commitContent instead of clipboard** for inserting into the *current*
   field (KeyEventHandler already does this for media) — but that's
   insert-here, not copy-for-elsewhere, so it doesn't satisfy "copy to
   clipboard."

**Recommendation:** Option 1 (webp→gif transcode) is the real fix but is a
bounded mini-project (encoder). Confirm before building, or ship Option 3's
clearer messaging as an interim.

**Suspected files:** `KeyboardReceiver.kt:585` (Copy GIF), `gif/Gif.kt`
(paths/MIME), `gif/GifAssetManager.kt` (full vs thumb files).

---

## B2 (original hypothesis — superseded by root-cause above)

**Why it happens (initial hypothesis — UNVERIFIED):**
The clipboard media path likely flattens the animated source to a single
still frame (or stores/returns a generated thumbnail) instead of preserving
the original animated bytes + MIME type. Schema V4 added `mime_type`,
`thumbnail_blob`, and `media_path` columns — the bug may be that the
*thumbnail* (a static raster) is what gets re-exported to the system
clipboard, or that the capture step transcodes GIF/animated-WebP → JPEG.

**Open questions for investigation:**
- What is the source of the GIF copy? GIF panel "copy" action, or system
  share-into-clipboard, or long-press on a rendered GIF?
- Is the original animated file preserved at `media_path`, or only a
  `thumbnail_blob`?
- When re-exporting to the Android system clipboard, what `ClipData` MIME
  type + URI is set? Animated formats need `image/gif` (or `image/webp`)
  with a content URI the receiving app can read, not a flattened bitmap.
- Does the receiving app matter (some apps only accept static images)?

**Suspected files:**
- `ClipboardMediaManager` (referenced in this session's backup work)
- `ClipboardHistoryService.kt` — `addClip` / media capture path
- `ClipboardDatabase.kt` — V4 media columns (`mime_type`, `thumbnail_blob`,
  `media_path`)
- GIF panel: `GifGridView.kt`, `Gif*.kt` (GIF schema V5, `gif_pack_membership`)
- Coil image loading (animated WebP support is configured per MEMORY notes)

**Note:** No INTERNET permission — GIFs are imported via SAF file picker, so
the original file bytes should exist locally and be preservable.

---

## B3 — Clipboard URL sanitizer does nothing (until restart) ✅ FIXED

**Reported:** 2026-06-04 (user)

**Symptom:** Enabling "Sanitize tracking parameters" had no effect — copied URLs
kept their `utm_*`/`fbclid` params.

**Root cause:** the toggle persists to SharedPreferences and broadcasts a
rules-changed signal that drops the cached sanitizer, but the in-memory `Config`
field it reads was never refreshed (`SettingsActivity.updateConfigFromSettings()`
syncs only a hardcoded subset of fields). So `SanitizationConfig.build()` re-read
the stale startup value (`false`) and rebuilt a no-op sanitizer. Only a full
keyboard restart (which re-runs `Config.refresh()`) fixed it.

**Fix:** `Config.reloadSanitizationSettings()` re-reads the four Chunk-3 keys; the
`ClipboardHistoryService` rules-changed receiver now calls it before `rebuild()`.
Verified by `SanitizationReloadInstrumentedTest` (ew-cli, Pixel7 API34): toggle
ON mid-session → `utm_source` actually stripped, no restart needed.

---

## B4 — Settings not findable via search ✅ FIXED

**Reported:** 2026-06-04 (user)

**Symptom:** Typing "sanitize" (and many other setting names) returned no search
results despite the setting being visible on screen.

**Root cause:** settings search uses a hand-maintained `searchableSettings` index
parallel to the UI controls, with nothing enforcing sync. An audit found 83/126
control titles had no exact search match and ~25 controls had ZERO term coverage
(the 3 URL toggles, an entire Auto-Correction section, learning/boost sliders) —
so newly-added settings silently dropped out of search.

**Fix:** (1) backfilled search entries/keywords for every control whose name-words
were unsearchable; (2) added `SettingsSearchCoverageTest` (pure JVM, in
`runPureTests`) that scans `SettingsActivity.kt` and fails the build if any control
title word is absent from the search index — preventing recurrence, including for
future settings. `settings-preferences.md` skill documents the requirement.

---

## (awaiting further reports)
