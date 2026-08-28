# #156 At-Rest Clipboard Encryption — Design Document

**Status:** PROPOSED (design only — no code changes). Verified against source at commit `4ad8a536d`, 2026-07-17.
**Issue:** #156 "[Feature]: Encrypted Clipboard" (EsterWings, open; owner reply confirms it's on the wish list and invites architecture input).
**Predecessor:** `docs/audit/remediation-plans/security-backup-encryption.md` — shipped 2026-07-17 in `1114bb749` with the reusable substrate `src/main/kotlin/tribixbite/cleverkeys/backup/crypto/` (`Pbkdf2Sha256`, `EncryptedBackupFormat` CKENC1, `BackupCrypto`, `BackupPassphraseStore`). §10.6 of that doc resolved sequencing: export-channel first, at-rest clipboard (this doc) second.

---

## 1. What #156 actually asks (read 2026-07-17, incl. all 3 comments)

The issue plus the author's follow-up comment contain **two distinct asks**:

1. **"Copy to encrypted clipboard"** — a private copy path (suggested UX: Ctrl+long-press-C / fingerprint; or a selection-toolbar entry) so the **OS clipboard never sees plaintext**, because "currently-open-app and android system processes all have access to entirety of clipboard." The author explicitly worries whether the OS keeps its own log of copied texts.
2. **Encrypt-at-rest** in CleverKeys' own clipboard history — cites the Urik keyboard's "Clipboard history with encrypted storage" and correctly notes Urik's claim "says it only about *storage*."

**This design targets (2)** — encrypting CleverKeys' clipboard history database at rest. §7 assesses (1)'s feasibility separately, because (1) is arguably the *bigger* privacy win and is partially satisfiable.

---

## 2. Current storage, verified against source

All facts below were verified in-source; several diverge from folklore (including project memory), so they are load-bearing for the design.

### 2.1 Database (`ClipboardDatabase.kt`, 1745 lines)

- `clipboard_history.db`, ~~schema V4~~ **schema V5 as of the #156 private-copy ship** (`DATABASE_VERSION = 5`; V5 added `is_private` + `source_package` to all three tables — see `156-private-copy-paste.md` §5.2, which reserved V5 for private copy and **renumbered this design's migration to V6**). *(Original 2026-07-17 text said "no V5 in the clipboard DB"; corrected 2026-08-28.)*
- Three tables (`:1658-1727`): `clipboard_entries` (id, **content TEXT**, timestamp, expiry_timestamp, **content_hash TEXT**, mime_type, **thumbnail_blob BLOB**, **media_path TEXT**), `pinned_entries` (+ created/pinned timestamps, position REAL, tags JSON TEXT), `todo_entries` (+ status, tags, position).
- **`content_hash` for text rows is `String.hashCode().toString()`** (`:225,496,675,825`) — a 32-bit non-cryptographic hash of the plaintext. For media rows it is the SHA-256 hex of the file (`ClipboardMediaManager.kt:465`).
- **Nearly every mutation is content-keyed**: `removeClipboardEntry`/`unpinEntry`/`removeTodoEntry`/`setTodoEntryStatus`/`setPinnedEntryTags`/`updateEntryContentInTable` all do `WHERE content = ?` (via `resolveContentKey`, `:769`), and dedup checks use `content_hash = ? AND content = ?`. This is the single largest refactoring constraint (§5.4).
- Expiry cleanup, size limits, ordering, and stats are **pure SQL over timestamp/expiry/position/status/LENGTH(content)** (`:415-440, 1051-1135, 1016-1044`) — these columns must stay plaintext-queryable.

### 2.2 Search — **there is NO FTS on the clipboard DB**

This is the most important verified fact. `rg -i fts src/main/kotlin` shows FTS4 exists **only** in `gif/GifDatabase.kt`. Clipboard search works like this:

- The panel loads the **entire** history into memory: `ClipboardHistoryService.clearExpiredAndGetHistory()` (`:205`) → `ClipboardDatabase.getActiveClipboardEntries()` (`:327`) — `SELECT` all non-expired rows including thumbnail BLOBs.
- `ClipboardHistoryView.applyFilters()` (`:237-330`) then does **in-memory** substring or regex matching (`ClipboardSearchUtils.expandGlobShorthand` glob→regex), across *all* items ("searches ALL items", `:252`), plus date/tag/status filters.
- Pagination (`ITEMS_PER_PAGE = 100`, `:151`) is applied **after** filtering, as a `subList` view.

**Consequence:** the "encryption kills FTS search" problem posed in the tasking does not exist here. Search is already "load everything, match in memory" — precisely the shape that per-row encryption is compatible with. The cost of encryption on search is *only* the decrypt-on-load cost (§6), not a functionality regression. (Correction recorded deliberately: project memory's "FTS: Uses FTS4" line refers to the GIF DB.)

### 2.3 Media, service, Direct Boot, backup exposure

- Media files: `{filesDir}/clipboard_media/{partition}/{sha256}.{ext}` (`ClipboardMediaManager.kt:23,127`) — plaintext bytes on disk, **filename is the plaintext's SHA-256** plus a real extension. Thumbnails are plaintext WebP BLOBs in the DB.
- `ClipboardHistoryService` initialization is **already Direct-Boot deferred**: `on_startup` checks `isUserUnlocked` and defers via `DirectBootManager` (`:802-838`) because the DB lives in Credential-Encrypted storage. Pre-unlock, there is no clipboard service and no panel data — the DEK-availability question (§4) inherits this cleanly.
- Existing sensitive-content mitigations (relevant to the honest threat model): password-manager package exclusion (default on, `Config.kt:495`), Android 13+ `IS_SENSITIVE` flag respect (default on, `:496`, `ClipboardHistoryService.kt:605-619`), URL sanitizer with optional system-clipboard rewrite.
- Backup exposure: `res/xml/backup_rules.xml` + `data_extraction_rules.xml` exclude **all databases** and `clipboard_media/` from cloud backup **and** device-to-device transfer. The only sanctioned egress is the export channel, which since `1114bb749` is AEAD-encrypted (mandatory on the headless path).
- Defaults that size the problem: history limit **0 = unlimited**, TTL **-1 = never expire**, per-item cap 512 KB, media size budget 5 MB (`Config.kt:215-232`).

---

## 3. Threat model — the honest version

At-rest DB encryption must be justified against what Android already provides. Being honest about this is a design requirement, not a disclaimer.

### 3.1 What already protects the data

| Layer | Protection |
|---|---|
| App sandbox | No other app (zero-perm or otherwise) can read `clipboard_history.db` or `clipboard_media/`. This is the same boundary that protects the passphrase store. |
| Android FBE (file-based encryption) | The entire CE storage area — including this DB — is already AES-encrypted at rest by the OS, keyed to the lock screen. A powered-off or before-first-unlock device yields ciphertext to a chip-off attacker *from the OS layer alone*. |
| Backup rules | DB and media excluded from cloud backup, adb backup, and D2D transfer (§2.3). |
| Export channel | Headless exports are AEAD-encrypted, mandatory (shipped `1114bb749`). |
| Runtime necessity | While the keyboard is running on an unlocked device, the decryption key **must** be in process memory. Nothing app-level can change that. |

### 3.2 What app-level encryption genuinely adds

1. **AFU (after-first-unlock) forensic extraction — the core real gain.** Most real-world seizures/thefts happen with the device *after first unlock*, where CE storage is mounted and forensic tooling (or file-exfiltrating malware, or a hasty `su`-wielding acquaintance) can image files without defeating the lock screen. Android FBE does **not** help in AFU state — files read as plaintext. A **hardware-backed Keystore key** (TEE/StrongBox, non-exportable) means the imaged DB is ciphertext, and decrypting it requires executing code *as this app on this device* — a materially higher bar than copying files.
2. **File-only exfiltration by root-capable malware** that copies `/data/data/...` wholesale but does not inject into processes or drive Keystore per-app. Partial: sophisticated root malware *can* assume the app's UID and use its Keystore keys — this layer raises effort, it is not a wall.
3. **Stray plaintext copies get less likely to matter**: any future bug, OEM backup quirk, or misconfigured rule that leaks the DB file leaks ciphertext.
4. **Defense-in-depth parity**: FBE strength is bounded by the lock-screen credential; a weak PIN + an FBE-bypass exploit class leaves the app layer standing (when a hardware-bound key is used, brute-forcing the PIN off-device does not unlock the app layer).

### 3.3 What it does NOT add (illusory gains — say so in the UI too)

- **Nothing against other apps** — they could never read the DB anyway.
- **Nothing against the OS clipboard exposure** the issue author is most worried about — that's ask (1), §7. Copied text still transits the system clipboard in plaintext; the target app still receives plaintext on paste.
- **Nothing while the process is attackable**: a debugger/memory-dump/injected-code attacker gets the in-memory DEK.
- **Nothing against pre-unlock theft** beyond what FBE already provides (and the service doesn't even run pre-unlock).
- **Flash remanence**: enabling encryption re-writes rows, but old plaintext pages persist in unallocated flash blocks and old SQLite WAL/freelist pages until TRIM'd/overwritten. `VACUUM` after migration shrinks the window; it cannot eliminate it (§5.6). Existing users must understand "encrypt now" is not retroactive at the physical layer.

### 3.4 Verdict

**The honest gain is real but narrow: it is an anti-forensic / anti-file-exfiltration hardening for the AFU window, plus optics parity with Urik.** It is *not* what the issue author primarily asked for (that's §7). Given the substrate already exists and the hot-path cost is tiny (§6), the effort-to-gain ratio is reasonable — but the user should confirm the priority ordering between this and §7 before implementation (see Decisions).

---

## 4. Key management

### 4.1 Options

| Option | At-rest strength | Hot-path cost | Availability | Recoverability | Notes |
|---|---|---|---|---|---|
| (a) Keystore AES key used directly for every row op | Hardware-bound | **~1–10 ms per op** (every AES-GCM runs in TEE via Binder; non-batchable). 100-entry page ≈ 0.1–1 s. **Disqualified for the hot path.** | after boot, no unlock gesture needed (`setUserAuthenticationRequired(false)`) | Lost if Keystore key lost | Also: Keystore AES-GCM won't accept caller nonces without `setRandomizedEncryptionRequired(false)`; irrelevant since rejected. |
| (b) Passphrase-derived DEK (PBKDF2 once, cached; reuses `BackupPassphraseStore`) | Bounded by passphrase *only if* the passphrase isn't stored — but our store keeps it on-device Keystore-wrapped, so effective strength ≈ Keystore anyway | Fast after derivation; **but 600k-iteration PBKDF2 (~0.3–1.5 s) on every IME process start** — a cold-start hot-path tax on a keyboard | Needs passphrase set; if we *prompted* instead of using the store, the panel is locked until the user types a passphrase — terrible for a keyboard | Survives Keystore loss; same passphrase as backups | The security benefit over (c) only materializes if the passphrase is NOT stored, which is UX-prohibitive here. |
| **(c) Keystore-wrapped random DEK (hybrid) — RECOMMENDED** | Hardware-bound (DEK ciphertext useless without the non-exportable wrap key) | **Software AES with in-memory DEK: ~5–20 µs per row** (§6) | Unwrap once per process start (~ms); works headlessly, post-boot, no user gesture | Lost if Keystore wrap key lost (rare with `setUserAuthenticationRequired(false)` keys; see escrow option) | Non-exportability is FINE here, unlike backups: clipboard history is device-local by nature, and cross-device transfer already goes through the (passphrase-encrypted) export channel. This is exactly the asymmetry that made (b/c) different for backups. |

### 4.2 Recommended design: `ClipboardKeyManager` (Option c)

New Android-side class mirroring `BackupPassphraseStore`'s proven wrap pattern (same Keystore code shape, different alias/prefs):

- On first enable: generate a random 32-byte **master DEK** (`SecureRandom`), wrap with a new Keystore AES-GCM key (`alias ck_clip_dek_wrap`, `setUserAuthenticationRequired(false)`), store ciphertext+IV in app-private prefs file `ck_clipboard_crypto` (keys added to `SettingsValidation.INTERNAL_KEYS` — never exported; the `SettingsDefaultsDriftTest` tripwire enforces this).
- On service init (post-Direct-Boot-unlock, same place `ClipboardDatabase` comes up): unwrap once, hold the DEK in memory for the process lifetime. Zero the array on `on_shutdown()` (hygiene, not guarantee).
- **Domain separation**: derive two subkeys from the master DEK via `HmacSHA256(DEK, "ck-clip-enc-v1")` → AES key, and `HmacSHA256(DEK, "ck-clip-hash-v1")` → blind-index MAC key (§5.4). Never use one key for both GCM and HMAC. Pure-JVM, no new deps.
- **Keystore-unavailable fallback** (API < 23 / provider errors): store the DEK base64 in the same app-private prefs, exactly like `BackupPassphraseStore`'s documented fallback — sandbox remains the boundary; log the degradation. On such devices the honest §3.2 gain (1) mostly evaporates; the toggle's dialog should say "hardware-backed" only when it actually is (`isWrapped` flag, same as `PREF_WRAPPED`).
- **Optional DEK escrow (decision #4)**: if a backup passphrase exists, additionally store a PBKDF2-passphrase-wrapped copy of the DEK. This makes the encrypted history recoverable after a Keystore wipe (OEM lockscreen-reset quirks) and lets a restored full-backup ZIP re-attach. Costs: a second wrap to keep in sync on passphrase change/remove.

### 4.3 Reboot / Direct Boot / pre-unlock behavior

Verified: the service and DB are already unavailable before first unlock (`on_startup` defers; `ClipboardMediaManager` has its own Direct-Boot guard, `:49`). The DEK unwrap happens at the same deferred-init moment, so **there is no new pre-unlock state to design for**: the clipboard panel before first unlock is exactly as empty as it is today. After first unlock the wrap key is usable without any user gesture, so the panel works immediately — no unlock prompt, ever. If the DEK cannot be unwrapped (corrupt prefs / Keystore key vanished): encrypted rows are unreadable → panel shows a per-row "🔒 unrecoverable" placeholder + a settings banner offering "reset encrypted history" (delete encrypted rows) or escrow recovery if enabled. Fail loudly, never silently drop rows.

---

## 5. Schema, row format, and migration

### 5.1 Per-row ciphertext format — compact, NOT the CKENC container

CKENC1 costs 67 bytes/blob and embeds KDF parameters that are meaningless when the key is a cached DEK (no per-blob KDF!). Reusing it per-row would also run PBKDF2 per entry — the exact non-starter the tasking flags. Instead, a minimal AEAD cell format, `ClipboardCrypto.seal()`:

```
offset size  field
0      1     format_version = 0x01
1      12    gcm_nonce (SecureRandom per seal)
13     ...   ciphertext ‖ 16-byte GCM tag
```

Overhead: **29 bytes/cell**. AAD = 2 bytes `{table_id, column_id}` (binds a blob to its table+column so a ciphertext can't be replayed from `todo_entries.content` into `pinned_entries.content`; cross-row swap within a column is accepted residual risk — an attacker with DB *write* access on-device already owns everything, and the offline-modify-and-return scenario is out of proportion). Random 96-bit nonces under one key are safe far beyond any plausible clipboard volume (NIST bound 2³²).

Version byte reserves format evolution (e.g., XChaCha or key rotation) without a schema migration.

### 5.2 Schema V6 *(was "V5" in the 2026-07-17 draft; private copy took V5 per its §5.2 — renumbered 2026-08-28)*

`DATABASE_VERSION → 6`; `migrateV5toV6` uses the existing O(1) `ALTER TABLE ADD COLUMN` pattern (like v3→v4 and v4→v5) on all three tables:

- `enc INTEGER NOT NULL DEFAULT 0` — per-row marker. Per-row (not global) so a mid-migration crash leaves a readable mixed-state DB; the read path branches per row.
- `content_enc BLOB` — sealed content when `enc=1`; `content` is then set to `''`. (Kept as a separate column rather than stuffing BLOBs into the TEXT column — type-affinity games are legal in SQLite but hostile to every existing `LENGTH(CAST(content AS BLOB))` size query and to debuggability.)
- `tags_enc BLOB` (pinned/todo) — tags are user-authored labels; encrypt them (they're loaded wholesale into memory for the tag filter already — `getAllPinnedTags` parses every row — so nothing SQL-side needs them).
- `thumbnail_blob` — sealed **in place** when `enc=1` (same column; row flag disambiguates).
- `content_hash` — repurposed when `enc=1` to hold the **blind index**: hex `HmacSHA256(hashKey, exact_stored_content)`, truncated to 128 bits. See §5.4.

**Stays plaintext, with the leak stated honestly**: `timestamp`, `expiry_timestamp` (SQL cleanup), `position` (SQL ordering), `status` (SQL index/filter), `mime_type` (routing + text-only filter), `media_path` (opaque under §5.5 renaming), row count and approximate content sizes (`LENGTH` — padding to fixed buckets is possible but out of proportion; size limits also depend on it). An attacker with the ciphertext DB learns *when* you copy, *how much*, *what kind*, and your todo-completion habits — but not content, tags, or media.

### 5.3 Size-limit interaction

`applySizeLimitBytes` measures `LENGTH(CAST(content AS BLOB))` — for encrypted rows it must use `COALESCE(LENGTH(content_enc), LENGTH(CAST(content AS BLOB)))`. Ciphertext is plaintext+29B, so budgets stay honest. The 512 KB per-item check happens pre-encryption in `addClip` — unchanged.

### 5.4 The content-keyed-API problem (the real hard problem of this codebase)

Random-nonce encryption makes equal plaintexts yield different ciphertexts, so every `WHERE content = ?` and `content_hash = ? AND content = ?` breaks. Two fixes considered:

- **Rekey all APIs to row `id`** — architecturally cleanest, but the COPY-semantics design deliberately keys cross-table operations by content (pin *this text*, regardless of which table copy you tapped), and the service/view layer passes content strings everywhere (`ClipboardHistoryService.kt:234,380-431`, panel views). A full id-rekey is a large, risky refactor orthogonal to encryption.
- **Blind index (RECOMMENDED)**: `content_hash := HMAC-SHA256(hashKey, content)` for encrypted rows. All existing lookups become `WHERE content_hash = ?` (computed at the seam from the caller's plaintext), dropping the `AND content = ?` clause for encrypted rows — safe because a 128-bit keyed MAC makes collisions cryptographically negligible, unlike today's 32-bit `hashCode` (which needed the `AND content` guard). `resolveContentKey`'s exact-then-trimmed fallback maps directly to two blind-index probes. Dedup ("move duplicate to top", pin/todo already-exists checks) works unchanged in structure.

**Security note that makes the blind index mandatory, not optional:** if `content` were encrypted but `content_hash` kept `String.hashCode()`, the encryption would be *decorative* for exactly the secrets that matter — a 6-digit OTP or 4-digit PIN has ≤10⁶ candidates, and inverting `String.hashCode` over that space is instant. The keyed blind index leaks only equality-under-our-key (an attacker without the DEK learns "rows 3 and 41 are the same text" and nothing else — the standard, explicitly-accepted deterministic-index leak). Equality of encrypted entries is *already* observable via dedup behavior anyway.

### 5.5 Media files and thumbnails

- **Media bytes**: stream-seal with the DEK using a raw-key variant of the existing chunked-`Cipher.update` loop from `BackupCrypto.encryptStream` (same no-`CipherInputStream` rule; single GCM stream, tag verified before any consumer sees plaintext — media paste materializes a temp decrypted file for `commitContent`, deleted after; same authenticate-then-use contract as the ZIP import path).
- **Filename**: currently `{sha256-of-plaintext}.{ext}` — leaks a content identifier and the type. Encrypted media renames to `{blind-index-hex}.bin` (dedup still works — the DB `content_hash` and filename both key off the blind index; `mime_type` in the DB supplies the type). Path-traversal guard (`ClipboardMediaManager.kt:220-242`) is unaffected.
- **Thumbnails**: sealed in the DB column. Decrypt at render time. Note honestly: `getActiveClipboardEntries` currently loads all thumbnail BLOBs eagerly; decrypting them eagerly too is the simple v1 (media entries are few and thumbnails small); lazy decrypt is a follow-up optimization, not a correctness need.

### 5.6 Enable / disable migration

- **Enable** (Settings toggle on): background coroutine, one transaction per table (chunked per 500 rows to bound transaction size): read plaintext row → seal content/tags/thumbnail → write `content_enc`/`tags_enc`/`thumbnail_blob`, set `content=''`, `content_hash=blindIndex`, `enc=1`. Then stream-encrypt each media file to its new name, update `media_path`, delete the old file. Finish with `wal_checkpoint(TRUNCATE)` + `VACUUM` (rewrites the DB file so freed plaintext pages leave the live file). Progress UI in settings; panel usable throughout thanks to per-row `enc`.
- **Disable**: exact inverse (decrypt back to plaintext columns, restore `hashCode`-style `content_hash` for text rows / SHA-256 for media to match legacy expectations, decrypt media files back to hash-named plaintext), then offer to clear the DEK. Requires a working DEK — disable with a lost DEK degrades to "delete encrypted rows."
- **Flash remanence caveat** (§3.3) is shown once in the enable dialog: *"Previously stored entries are re-encrypted, but traces of old data can remain in already-freed storage until the system reclaims it."* No hand-waving.
- **Downgrade**: an old APK opening a V5 DB fails `onUpgrade`-less open (SQLiteOpenHelper downgrade throws) — standard behavior, same class of issue as every prior version bump; no extra handling.

---

## 6. Performance budget

Measured expectations (Conscrypt AES-GCM uses ARMv8 crypto extensions; bulk throughput >1 GB/s; per-op cost is dominated by JNI `Cipher` init+doFinal):

| Operation | Cost with cached DEK | Context |
|---|---|---|
| Encrypt one entry on copy (`addClip`) | ~5–20 µs + 29 B | Noise next to the existing SQLite insert (+dup query) at ~1 ms; also off the typing path entirely (clipboard listener). Blind-index HMAC ≈ 1 µs. |
| Decrypt one page of 100 entries (panel) | **~0.5–2 ms** (avg entry ≤1 KB) | Imperceptible. |
| Decrypt-all for search / full panel load, 5,000 text entries | ~25–100 ms | Comparable to the existing cursor-iteration + object-allocation cost of loading 5,000 rows; done where the load already happens. Recommendation (independent of encryption): move `clearExpiredAndGetHistory` off the main thread for histories >1k — encryption makes an existing latent jank slightly heavier, it doesn't create it. |
| Thumbnails (media rows) | ~30-KB WebP ≈ 30 µs each | Few entries in practice; eager in v1 (§5.5). |
| Media file paste | streaming decrypt at >100 MB/s | 5 MB cap → <100 ms, on IO dispatcher. |
| One-time enable migration | dominated by SQLite rewrite + VACUUM, not crypto | Seconds for realistic DBs; progress UI. |

**There is no FTS loss to weigh** (§2.2). Search options from the tasking, resolved: (a) decrypt-all-in-memory **is the recommendation and is functionally identical to today's search**; (b) dropping search is unnecessary; (c) blind-index token search (word-granular deterministic encryption) would add real leak surface (per-word frequency fingerprints) and real complexity to buy nothing we need — rejected. The blind index in §5.4 is for *equality/dedup only*, not search.

---

## 7. Ask (1): "the OS clipboard never sees plaintext" — feasibility

Verified plumbing: pasting **from** the CleverKeys panel already bypasses the OS clipboard entirely — `paste_from_clipboard_pane` → `sendTextDirect` → `InputConnection.commitText` (`KeyEventHandler.kt:147-155`); media uses `commitContent`. So the *paste* half of a private clipboard already exists. The missing half is *copy without the OS clipboard*:

1. **In-IME private copy** — an editing-pane/short-swipe action "Copy to CleverKeys only": read the selection via `InputConnection.getSelectedText(0)` and store straight to the (encrypted) DB, never calling `setPrimaryClip`. Fully feasible with existing machinery (the customization framework in `customization/` already dispatches custom actions). Limitation: works only while CleverKeys is the focused IME and the field allows `getSelectedText`.
2. **Selection-toolbar entry via `ACTION_PROCESS_TEXT`** — an exported no-UI activity with the `PROCESS_TEXT` intent filter appears in most apps' text-selection toolbars ("CleverKeys: private copy"); the selected text arrives as an intent extra, OS clipboard uninvolved. This is exactly the author's suggestion #1 ("a context-menu option … then the OS too won't see it") and it is implementable today. Caveats: not available in every app (WebViews/custom editors vary), and the activity becomes a new exported surface (text flows *in* only — benign, but document it).
3. What is **not** feasible: intercepting normal Ctrl-C/long-press-copy system-wide, or preventing the OS/foreground app from seeing content the *user copies normally*. Android gives the focused app and the IME clipboard access by design; no keyboard can revoke it. The author's suggestion #2 (copy-then-overwrite) is already partially embodied by the sanitizer's `systemClipboardRewrite` and Android 13+'s own clipboard auto-clear, but the plaintext instant on the OS clipboard is unavoidable for a normal copy.

**Recommendation:** treat (1) as a separate follow-up feature ("Private copy/paste") — items 1+2 above satisfy the spirit of the request far better than at-rest encryption does, and they compose with it (private-copied entries land in an already-encrypted store, flaggable as private-only entries that never touch `setPrimaryClip`). Needs its own design (exported-activity threat review, UX for a "private" badge/tab, whether private entries are excluded from exports).

---

## 8. UX

- **Settings → Clipboard → new "🔒 Encrypt clipboard history" toggle** (in `ui/settings/sections/ClipboardSection.kt`, near the privacy toggles it belongs with). Off by default (opt-in).
- Enable dialog states the honest scope: *"Encrypts stored clipboard history, pins, todos, tags, and media with a hardware-backed key on this device. Protects data if files are extracted from the device. Does not hide what you copy from the system clipboard or other apps while you use them."* Plus the remanence caveat (§5.6) and — on Keystore-fallback devices — a "software-key" qualifier.
- **No passphrase, no unlock prompts** (Option c): the toggle is the entire UX. If DEK escrow (decision #4) ships, one extra line: "Recoverable with your backup password."
- Panel: no visible change post-unlock (decryption is transparent). Unrecoverable-DEK state shows the banner + reset path (§4.3). Pre-first-unlock: unchanged (panel has no data today either).
- Export/import: unchanged UX — exports read decrypted content through the DB API and flow into the already-shipped CKENC channel; imports write through `addClip`-equivalents which seal when the toggle is on.

---

## 9. Files to change + test strategy

### 9.1 New files

| File | Contents |
|---|---|
| `backup/crypto/ClipboardCrypto.kt` | **Pure JVM.** `seal(plaintext, key, aad): ByteArray` / `open(blob, key, aad)` (§5.1 format); `sealStream`/`openStream` raw-key chunked variants (share the loop with `BackupCrypto` by extracting a private raw-key core there — the passphrase layer becomes a thin wrapper, no behavior change to shipped code paths); `blindIndex(hashKey, content): String`; `deriveSubkeys(dek): Pair<SecretKey, ByteArray>` (HMAC domain separation). Injected `SecureRandom`. |
| `backup/crypto/ClipboardKeyManager.kt` | Android-side (thin, like `BackupPassphraseStore`): DEK generate/wrap/unwrap/cache/clear, Keystore alias `ck_clip_dek_wrap`, prefs `ck_clipboard_crypto`, `isHardwareBacked()`, optional escrow. |
| `clipboard/EncryptedRowCodec.kt` | **Pure JVM** row-transform logic used by both live read/write and the enable/disable migration: `(plaintextRow, keys) → encryptedRow` and inverse, so migration correctness is JVM-testable without SQLite. |

### 9.2 Modified files

| File | Change |
|---|---|
| `ClipboardDatabase.kt` | V5 migration (`:82-104` chain + DDL); read seams (`getActiveClipboardEntries:327`, `getPinnedEntries*:580,610`, `getTodoEntries*:887,918`) branch on `enc`; write seams (`addClipboardEntry:222`, `addMediaClipboardEntry:272`, `pinEntry:487`, `addTodoEntry:666`, `updateEntryContentInTable:816`, tag setters `:1193,1208`) seal + blind-index; lookup seams (`resolveContentKey:769`, dedup queries) use blind index for encrypted rows; `applySizeLimitBytes:1091` + `getStorageStats:1016` LENGTH fix; `exportToJSON:1264` decrypts (so the export channel keeps working unchanged); enable/disable migration entry points. The DB takes an optional `ClipboardCryptoState` (keys + enabled flag) injected by the service — DB stays constructible without crypto for existing tests. |
| `ClipboardHistoryService.kt` | Init DEK in `initializeService`/constructor (post-unlock path, `:843`); zero on `on_shutdown:854`; pass crypto state to DB + media manager; settings-toggle handler triggering migration. |
| `ClipboardMediaManager.kt` | `saveMedia:69` seal-on-write + blind-index filename when enabled; `resolveMediaFile` decrypt-to-cache for paste; orphan cleanup name handling. |
| `ui/settings/sections/ClipboardSection.kt` (+ `ClipboardSettingsActivity.kt` if it mirrors) | Toggle + dialogs + migration progress + unrecoverable-state banner. |
| `backup/SettingsValidation.kt` | Add `ck_clipboard_crypto` pref keys to `INTERNAL_KEYS` (drift-test tripwire, same load-bearing rule as the passphrase store). |
| `docs/specs/clipboard-privacy.md` | Record the feature per the spec-driven workflow. |
| `build.gradle` | Register new pure test classes in `pureTestClasses`. |

### 9.3 Tests

**Pure JVM (`runPureTests`, runnable on this ARM64 device):**

| Class | Asserts |
|---|---|
| `ClipboardCryptoTest` | seal/open round-trip (UTF-8 incl. emoji, empty-adjacent, 512 KB max item); wrong key / flipped bit anywhere / wrong AAD (cross-table, cross-column) → `AEADBadTagException`; version byte honored; two seals of same plaintext differ; stream round-trip >1 MiB chunk-boundary-exact; blind index: deterministic, key-separated (enc key ≠ hash key outputs), 128-bit length, differs for trimmed vs untrimmed content. |
| `EncryptedRowCodecTest` | plaintext↔encrypted row transforms round-trip for all three table shapes (content, tags, thumbnail, media rename); mixed-state rows (enc=0) pass through untouched; legacy `hashCode` hash regenerated correctly on disable. |
| `ClipboardCryptoPerfSmokeTest` | 1,000 seal+open of 1 KB entries under a generous time bound — regression tripwire for accidental per-entry KDF (the §4.1(a)/(b) failure mode). |

**MockK (`runMockTests`):**

| Class | Asserts |
|---|---|
| `ClipboardEncryptionServiceTest` | service seals before DB write and blind-indexes lookups (mock DB verifies no plaintext reaches `content` when enabled); DEK-unavailable path fails closed (no plaintext writes, banner state set); export path receives decrypted JSON. |

**Instrumented (ew-cli, Pixel7/API 34, `--use-orchestrator --timeout 25m`):**

| Class | Asserts |
|---|---|
| `ClipboardDatabaseEncryptionTest` | V4→V5 schema migration preserves rows; enable-migration converts all three tables + media files, panel-visible content identical before/after; disable restores byte-identical plaintext rows; mid-migration-interrupt simulation leaves a fully readable mixed DB; content-keyed ops (remove/pin/unpin/todo-status/tags/edit, incl. the trimmed-fallback path) work on encrypted rows; dedup move-to-top works via blind index; size limits count ciphertext. |
| `ClipboardKeyManagerTest` | DEK survives store re-instantiation; hardware/fallback flag correct; clear() removes key + prefs; unwrap-failure surfaces null (not crash). |
| `ClipboardSearchEncryptedTest` | regex/glob/date/tag/status search over an encrypted store returns identical results to the same plaintext store (the §2.2 guarantee, end-to-end). |

---

## 10. Decisions needed from the user

1. **Is the honest gain worth it / priority order?** §3 is candid: at-rest encryption hardens the AFU-forensics and file-exfil cases only — it does **not** address the issue author's primary concern (OS clipboard exposure). §7's "private copy/paste" (in-IME private copy + `PROCESS_TEXT` selection-toolbar entry) addresses that concern more directly and is independently shippable. Options: (a) ship at-rest first (this doc) then private-copy; (b) private-copy first; (c) both in one release. My read: (a) is fine since this design is fully specified and small, but (b) is defensible if you want #156's author delighted sooner.
2. **Key management** — confirm Option (c) (Keystore-wrapped random DEK, no passphrase, no unlock UX). The alternative (b) ties clipboard to the backup passphrase but adds a ~0.5–1.5 s PBKDF2 on every IME process start or an unlock prompt; I recommend against it.
3. **Search strategy** — confirm (a): keep today's in-memory search over decrypted entries (no functional regression, ~25–100 ms extra load for multi-thousand-entry histories). The blind index is used for equality/dedup only, accepting the standard equality-leak. Rejecting (c) searchable-encryption tokens as complexity without need.
4. **DEK escrow under the backup passphrase** (recoverability after Keystore loss + restore-on-new-device coherence) — include, or keep the simpler "Keystore loss ⇒ history reset" story? Recommend: include *if* a backup passphrase exists, skip otherwise.
5. **Scope of encrypted fields** — proposed: content, tags, thumbnails, media files encrypted; timestamps/expiry/position/status/mime_type/sizes plaintext (SQL-required). Acceptable metadata leak, or should `mime_type` also be folded into the sealed payload (costs: text-only filtering and media routing move from SQL to post-decrypt)?
6. **Default state** — opt-in toggle (proposed, matches "the honest gain is narrow"), or default-on for new installs with the migration prompt for existing ones (matches the privacy-first brand, costs the §6 load overhead for everyone)?

---
*Design verified against source at `4ad8a536d` (2026-07-17): `ClipboardDatabase.kt`, `ClipboardHistoryService.kt`, `ClipboardHistoryView.kt`, `ClipboardManager.kt`, `ClipboardMediaManager.kt`, `ClipboardSearchUtils.kt`, `KeyEventHandler.kt:147`, `backup/crypto/*`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`, `Config.kt`, `AndroidManifest.xml`. No code changes made. Notable corrections established during verification: the clipboard DB has **no FTS** (FTS4 is the GIF DB); clipboard search is in-memory regex over the fully loaded history; `content_hash` is 32-bit `String.hashCode()` for text (making the §5.4 blind index security-mandatory, not optional); Direct-Boot deferral already exists.*
