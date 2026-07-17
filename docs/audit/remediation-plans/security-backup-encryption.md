# Backup/Clipboard-Export Encryption — Design Document

**Status:** DESIGN — not implemented. Awaiting user decisions (§10).
**Audit finding:** 2026-07-17 code-quality audit, finding #2 (P1): `BackupRestoreActivity`
exported with 6 IMPORT/EXPORT actions and no caller authentication
(`AndroidManifest.xml:140-157`, `BackupRestoreActivity.kt:82-106`).
**Companion doc:** `docs/audit/remediation/2-data-security.md` R1 (proposed a
signature-permission gate — **rejected** by constraint: #70 Termux `am start`
automation must keep working from the shell, which cannot hold a signature permission).
**Related issue:** #156 "[Feature]: Encrypted Clipboard".

---

## 1. What issue #156 actually asks for (read 2026-07-17)

#156 (EsterWings, open, enhancement) requests an **encrypted clipboard**, with two
follow-up architecture suggestions in comments:

1. A "copy to encrypted clipboard" path (Ctrl+long-press-C / fingerprint) so the
   **OS clipboard never sees plaintext** — i.e., an in-keyboard private clipboard.
2. Failing that, encrypt-at-rest inside CleverKeys' clipboard history, minimizing the
   window where the OS clipboard holds plaintext (cites Urik keyboard's
   "clipboard history with encrypted storage").

The owner's reply confirms encrypted clipboard is "on the imminent to-do list" and
invites architecture suggestions. **No requirements in #156 mention export/backup.**

**Scope split.** #156 is about *at-rest / in-transit-to-OS* clipboard encryption.
*This* document covers the **export channel** (the audit's exfiltration+injection
vector). They are separate deliverables, but this design deliberately builds the
shared substrate #156 will need: a passphrase store, a KDF, and an AEAD file format
(`backup/crypto/`) that a future SQLCipher-free at-rest clipboard encryption can reuse.
Do not conflate them in implementation; land this first.

---

## 2. The vector, verified against source (2026-07-17)

- `AndroidManifest.xml:140-157` — `BackupRestoreActivity` is `exported="true"` with a
  MAIN filter **and** a filter for all six custom actions accepting `file` and
  `content` schemes.
- `BackupRestoreActivity.kt:82-106` — `onCreate` dispatches purely on `intent.action`
  with **no caller check**: `EXPORT_SETTINGS`/`EXPORT_DICTIONARIES`/`EXPORT_CLIPBOARD`
  write to `intent.data` (attacker-supplied URI); `IMPORT_*` read from `intent.data`
  **or** a `json_base64` extra (`resolveBase64Extra`, `:120-132`) that needs no URI
  grant at all.
- `BackupRestoreManager.kt:97` — `content://` sinks go straight to
  `context.contentResolver.openOutputStream(uri)`. A zero-permission app can pass a
  `content://` URI backed by its own `ContentProvider` (with
  `FLAG_GRANT_WRITE_URI_PERMISSION`) and receive:
  - full clipboard history text (`exportClipboardHistory` → `ClipboardDatabase.exportToJSON(textOnly=true)`, `BackupRestoreManager.kt:797`) — **passwords/OTPs the user copied**,
  - the learned/custom dictionary (`exportDictionaries`, `:584`),
  - all settings (`exportConfig`, `:308`).
- Injection: `IMPORT_SETTINGS`/`IMPORT_DICTIONARIES`/`IMPORT_CLIPBOARD` with
  `json_base64` applies **without preview** (headless path calls `importConfig` /
  `importDictionaries` / `importClipboardHistory` directly — see
  `BackupRestoreActivity.kt:157-176, 206-226, 245-263`).
- Constraint: #70 automation is a shipped, documented feature
  (`docs/wiki/troubleshooting/backup-restore.md:198-284` gives exact `am start`
  commands including the `json_base64` chunking script). A signature-permission gate
  would break `am start` from Termux (shell UID can't hold an app-signature permission
  without root). **Encryption is the chosen mitigation.**

Interactive paths for context (unchanged threat-wise — all user-mediated via SAF
pickers): `ui/settings/io/SettingsBackupHandlers.kt` (config + full backup),
`SettingsDictionaryHandlers.kt`, `SettingsClipboardHandlers.kt:196,221`
(clipboard ZIP with media).

The single import read seam is `BackupRestoreManager.readJsonFromUri()`
(`:276`, used by `buildSettingsImportPlan:447`, `buildDictImportPlan:707`,
`importClipboardHistory:993`); ZIP imports read `contentResolver.openInputStream`
directly (`importClipboardHistoryZip:891`, `importFullBackup:1265`). The single
export write seam is `openOutputStream()` (`:74`). These two seams are where
encryption plugs in.

---

## 3. Crypto scheme

### 3.1 Cipher: AES-256-GCM (`AES/GCM/NoPadding`)

- Authenticated encryption with associated data (AEAD) — one primitive gives both
  confidentiality (closes exfiltration) and integrity/authenticity (closes injection:
  an attacker without the key cannot produce a payload that passes the tag check).
- **Available on-platform**: `Cipher.getInstance("AES/GCM/NoPadding")` +
  `GCMParameterSpec` work from API 19; project `minSdk 21` (`build.gradle:107`) is fine.
- **Zero new dependencies.** Verified: the app currently uses no crypto library at all
  (only `java.security.MessageDigest` SHA-256 in `ClipboardMediaManager.kt:436`);
  deps in `build.gradle:8-38` are androidx/compose/onnx/gson/coil. This is a
  no-INTERNET, F-Droid-reproducible keyboard — adding BouncyCastle (~4 MB) or Tink
  for one file format is unjustified. `javax.crypto` is pure-JVM, so the whole
  crypto/format layer runs under the ARM64 `runPureTests` harness.
- 12-byte (96-bit) random nonce per encryption, 16-byte (128-bit) tag, fresh
  `SecureRandom` salt per export. Nonce reuse is structurally impossible because the
  key is re-derived per file from a fresh random salt (key,nonce) pairs never repeat.

### 3.2 KDF: PBKDF2-HMAC-SHA256, in-repo implementation

- **Why not Argon2id (preferred in the abstract):** no platform support; the vetted
  routes are BouncyCastle (`Argon2BytesGenerator`, heavy dep) or `argon2kt` (JNI →
  cannot run in the pure-JVM ARM64 test harness, and adds a native lib per ABI to a
  reproducible-build APK). Rejected for now. The format carries a `kdf_id` byte so
  Argon2id can be added later without a format break.
- **Why not scrypt:** same story — not in the Android platform.
- **Why an in-repo PBKDF2 rather than `SecretKeyFactory`:**
  `PBKDF2WithHmacSHA256` via `SecretKeyFactory` is **API 26+**; minSdk is 21 (only
  `PBKDF2WithHmacSHA1` exists there). Instead of branching per API level, implement
  RFC 2898 PBKDF2 directly over `javax.crypto.Mac("HmacSHA256")` (available since
  API 1) — ~40 lines, identical output on every device and on desktop JVM, verified
  against the published PBKDF2-HMAC-SHA256 test vectors (RFC 7914 §11). One code
  path, deterministic, pure-JVM testable.
- **Parameters:** 600,000 iterations default (OWASP 2023 recommendation for
  PBKDF2-SHA256), 16-byte salt, 32-byte derived key. ~0.3–1.5 s on 2020+ phones —
  acceptable for an operation that runs once per export/import, and it prices offline
  brute force of a leaked ciphertext. Iteration count is a **header field**, so it can
  be raised later and old files still decrypt. Import must **cap accepted iterations
  at 5,000,000** to prevent a malicious header from causing a CPU DoS.
- KDF is invoked once per file; derived key is used for exactly one GCM operation.

### 3.3 AAD binding

The entire header (magic through nonce, §5) is passed as GCM AAD. Tampering with
version, KDF params, content-type, or timestamp invalidates the tag. The
`content_type` byte cryptographically binds a file to its import action (a clipboard
export cannot be replayed into `IMPORT_SETTINGS` even if the inner JSON were shaped
compatibly).

---

## 4. Key management

### 4.1 Options analyzed

| Option | Cross-device restore | Headless `am start` | Threat notes |
|---|---|---|---|
| **A. Passphrase per-invocation intent extra** (`--es passphrase`) | ✅ | ✅ but passphrase on the command line | Extras aren't readable by other zero-perm apps in transit, but the command leaks via shell history, `ps` snapshot windows, and any logging of the `am` invocation. Worst option as the *primary* channel. |
| **B. Android Keystore random key** | ❌ **broken** — Keystore keys are non-exportable; a factory reset, new device, or even some OS updates orphan every backup. A backup you can't restore elsewhere is not a backup. | ✅ (app holds key) | Strong at-rest key protection, but defeats the core purpose. Rejected as the sole mechanism. |
| **C. User-set backup passphrase, stored app-private (Keystore-wrapped at rest)** — **RECOMMENDED** | ✅ (user re-enters passphrase on the new device) | ✅ — the app itself holds the passphrase; the shell command carries **nothing secret** | A zero-permission app cannot read another app's private storage, so the stored passphrase is out of the attacker's reach by the same OS guarantee that protects the clipboard DB itself. Root/physical attackers defeat this — and everything else on the device. |
| D. Passphrase file path extra | ✅ | ✅ | Leaves the passphrase in shared storage (world-readable pre-scoped-storage, Termux-readable always) and hits the same scoped-storage read pain that motivated `json_base64`. Rejected. |

### 4.2 Recommended design (Option C, with A as an explicit escape hatch)

- **`BackupPassphraseStore`** (new, `backup/crypto/BackupPassphraseStore.kt`,
  Android-side): stores the user's backup passphrase in app-private
  `SharedPreferences`, encrypted at rest with an Android Keystore AES-GCM wrapping
  key (`setUserAuthenticationRequired(false)` — it must be usable headlessly and
  after reboot pre-unlock is irrelevant since prefs are credential-encrypted;
  note `directBootAware="true"` on the activity means device-protected-storage
  callers pre-unlock get "no passphrase available" and headless ops fail cleanly).
  If Keystore is unavailable (API 21/22 quirks, StrongBox errors), fall back to
  storing obfuscated-plaintext in app-private prefs — same OS-sandbox protection,
  strictly better than today's plaintext *exports*. The wrap is defense-in-depth,
  not the security boundary.
- **Export (headless or UI):** app derives the key from the stored passphrase +
  fresh salt. The `am start` command line is unchanged from today — no secret in it.
- **Import (headless):** app decrypts with the stored passphrase. Restoring a backup
  made on another device with the *same* passphrase works automatically.
- **Import (UI, passphrase mismatch or none stored):** prompt dialog (§9).
- **Escape hatch for automation restoring a foreign backup:** optional
  `--es passphrase <p>` extra on `IMPORT_*` only, **off by default** behind a settings
  toggle ("Allow passphrase via automation intent"), documented with the shell-history
  caveat (`HISTIGNORE`/leading-space). Never accepted for `EXPORT_*` (an attacker
  supplying their *own* passphrase to an export would reopen exfiltration —
  **exports must only ever use the stored passphrase**). This is the single most
  important rule in the design.

### 4.3 The "no passphrase set" state → enforcement policy

Encryption cannot be silently optional on the attacker-reachable path, or the vector
stays open. Policy:

- **Headless (exported-activity) path: encryption is MANDATORY.**
  - No stored passphrase → every `EXPORT_*`/`IMPORT_*` intent fails closed: toast +
    log `"Set a backup password in Settings → Backup & Restore first"`, nothing
    written, nothing applied, `finish()`.
  - Headless `IMPORT_*` of a plaintext (legacy) payload → **rejected** (this is what
    closes injection). Error message points to the UI import path.
  - Headless `EXPORT_*` always writes the encrypted container.
- **Interactive SAF path: encryption default-on, plaintext by explicit opt-out.**
  The SAF flow requires a foreground user gesture and a system file picker — it is
  not attacker-triggerable, so a user who consciously chooses "Export unencrypted
  (not recommended)" is exercising the same right they have with any file on their
  device. Plaintext *import* via UI remains fully supported (backward compat, §5.3)
  and keeps the preview.

This split answers "if opt-in, the exfil vector stays open when off": the vector runs
exclusively through the exported activity, and on that path there is no off switch.
The interactive path's opt-out does not reopen it.

---

## 5. On-disk format: `CKENC1` container

### 5.1 Layout (binary, big-endian)

```
offset  size  field
0       8     magic: ASCII "CKENC1" + 0x0D 0x0A   (8 bytes: 43 4B 45 4E 43 31 0D 0A)
8       1     format_version = 0x01
9       1     content_type: 1=settings JSON, 2=dictionaries JSON, 3=clipboard JSON,
              4=clipboard ZIP (media), 5=full-backup ZIP
10      1     kdf_id: 1 = PBKDF2-HMAC-SHA256   (2 reserved for Argon2id)
11      4     kdf_iterations (uint32)
15      16    kdf_salt
31      12    gcm_nonce
43      8     export_timestamp_epoch_millis (informational; AAD-covered)
51      ...   ciphertext ‖ 16-byte GCM tag
```

AAD = bytes `[0, 51)`. Total overhead: 67 bytes. The `\r\n` in the magic detects
text-mode transfer corruption (a classic PNG trick). Binary container (not a JSON
envelope) because content types 4–5 are multi-megabyte ZIPs — base64-in-JSON would
cost 33% and force whole-file strings; `base64 -w0 < file` piping for `json_base64`
handles binary fine (the extra is decoded to bytes before sniffing, so nothing
changes for the chunking script in the wiki).

File naming: keep user-facing extensions but append `.ckenc` for encrypted files
(`cleverkeys_settings_2026-07-17.json.ckenc`, `..._clipboard.zip.ckenc`) so shell
globs and humans can tell them apart; detection never trusts the extension.

### 5.2 Detection (import sniffing)

`EncryptedBackupFormat.sniff(firstBytes)`:
1. starts with `CKENC1\r\n` → encrypted container (then: `format_version` >
   supported → fail with "backup from a newer CleverKeys version");
2. starts with `PK\x03\x04` → legacy plaintext ZIP;
3. first non-whitespace byte `{` → legacy plaintext JSON;
4. else → "unrecognized file".

### 5.3 Backward compatibility & migration

- **Reading:** all existing plaintext backups import forever via the UI path
  (sniff → route to today's code unchanged, preview included). No migration tool
  needed — old files are already on disk and re-exporting produces encrypted ones.
- **Headless:** plaintext import rejected from day one (see §4.3). Grace option if
  the user wants it: a default-off setting "Allow legacy plaintext automation import"
  for one release, with a loud log warning. **Recommend shipping without it** — the
  user controls both ends of their automation and can re-export.
- **Writing:** headless always encrypted; UI encrypted-by-default once a passphrase
  exists.
- Old app versions reading new encrypted files: fail with "unrecognized file" — the
  magic makes this a clean, non-crashing parse failure in the existing JSON
  try/catch paths (first char `C` is invalid JSON).

---

## 6. Import path & preview preservation

Decryption happens **before** parsing, at the two existing seams; everything
downstream — including the pure-JVM diff/preview engine — is untouched:

1. `BackupRestoreManager.readJsonFromUri(uri)` (`:276`) becomes internally:
   read bytes → `sniff` → if encrypted: `BackupCrypto.decrypt(bytes, passphrase)` →
   UTF-8 string; if plaintext: current behavior. Callers
   (`buildSettingsImportPlan:447`, `buildDictImportPlan:707`,
   `importClipboardHistory:993`) receive the same JSON string they do today —
   **`SettingsImportPlanBuilder.fromJson(...)`, the preview dialogs
   (`BackupRestorePreviewDialogs.kt`), and the short-swipe diff all work unchanged
   for decryptable imports.**
2. ZIP imports (`importClipboardHistoryZip:891`, `importFullBackup:1265`): if
   encrypted, stream-decrypt to a temp file in `cacheDir` (`decrypt_<ts>.zip`),
   and **only after the final `doFinal()` verifies the tag** hand the temp file to
   the existing ZIP import code; delete in `finally`. Authenticate-then-parse is
   preserved: no ZIP entry is ever read from unauthenticated bytes.

**Do NOT use `javax.crypto.CipherInputStream`** for decryption: Android/JDK versions
of it have historically swallowed `AEADBadTagException` and returned truncated
plaintext as a silent EOF, and it releases unauthenticated plaintext incrementally.
`BackupCrypto` implements an explicit 64 KiB `cipher.update()` chunk loop with a
final `cipher.doFinal()`; the decrypted output is not surfaced to any consumer until
`doFinal()` returns. (For content types 1–3 the payload is held in memory — cap at
64 MiB; for 4–5 the chunk loop writes the temp file but the *contract* is that the
file is opened only post-verification.)

**Failure semantics (no partial apply):**
- Wrong passphrase and tampered ciphertext are **cryptographically
  indistinguishable** in GCM (both → `AEADBadTagException`). Single user-facing
  message: *"Wrong backup password, or the file is corrupted/tampered."*
- The exception is thrown before any JSON parse, any preview, any pref write, any DB
  insert. The existing apply paths already run only after a fully-parsed plan;
  nothing new is needed downstream — the guarantee is "decrypt-verify happens first
  or not at all."
- Header-level failures (bad magic version, kdf_id unknown, iterations > cap,
  truncated header) get distinct messages since the header is plaintext.

---

## 7. Threat-model verdict

| Attack | Before | After |
|---|---|---|
| Zero-perm app sends `EXPORT_CLIPBOARD` with its own `content://` sink | Gets full clipboard history plaintext (passwords/OTPs) | Gets AES-256-GCM ciphertext under a key derived from a passphrase it cannot read (app-private storage). If no passphrase set, gets **nothing** (export refuses). **Closed.** |
| Same for dictionary/settings exfil | Plaintext | Same as above. **Closed.** |
| Zero-perm app injects settings/dictionary/clipboard via `json_base64` or URI | Applied with no preview | Plaintext rejected on the headless path; forged ciphertext fails the GCM tag (existential forgery of AES-GCM without the key is computationally infeasible). **Closed.** |
| Legit user's Termux automation | Works | Works — identical `am start` commands, no secret on the command line; one-time setup: set a backup password in Settings. |

**Residual risks (explicit):**
1. **No-passphrase state** — fails closed on the exported path (nothing exported,
   nothing imported), so the *default* install is safe even before the user does the
   one-time setup. The cost is that automation requires that setup; the error toast
   says so.
2. **Replay** — an attacker who somehow possesses a *genuine* old encrypted backup
   (which already implies access to the user's storage or Termux home) can replay it
   into headless `IMPORT_*`, reverting settings/dictionary/clipboard to an old-but-
   authentic state. GCM authenticates content, not freshness. Partial mitigations:
   AAD-covered timestamp is logged on import and shown in the UI preview; content-type
   binding prevents cross-action replay. Full replay protection (monotonic counters)
   is out of proportion for this threat — an attacker with backup-file access can
   read the plaintext-equivalent data anyway on the source device. **Accepted.**
3. **Passphrase-delivery escape hatch** — the optional `--es passphrase` import
   override leaks via shell history if the user enables and uses it carelessly.
   Default-off, export never accepts it, docs carry the warning.
4. **Offline brute force of a leaked ciphertext** — bounded by passphrase strength ×
   600k PBKDF2 iterations. PBKDF2 is GPU-friendlier than Argon2id; a weak passphrase
   ("1234") remains crackable. UI enforces a minimum (8 chars) and warns below 12.
   `kdf_id` reserves the Argon2id upgrade path.
5. **DoS surface stays** — a zero-perm app can still spam `EXPORT_*` (disk writes to
   Downloads via the `file://` fallback chain, `BackupRestoreManager.kt:105`) and
   `IMPORT_*` (600k-iteration KDF burns before tag failure — only when a passphrase
   exists and payload sniffs as encrypted). Cheap hardening to include: rate-limit
   headless actions (e.g., min 2 s spacing, in-memory timestamp) and run the KDF
   only after header sanity checks.
6. **Root / physical / Termux-uid attackers** — can read app-private storage or the
   screen; out of scope (they already own the clipboard DB itself).
7. **Toast/log leakage** — headless toasts include only counts and output paths
   (`headlessToast`, `BackupRestoreActivity.kt:109`), never content; keep it that way.
8. **Memory hygiene** — passphrase handled as `CharArray`, zeroed best-effort after
   key derivation; JVM copies mean this is hygiene, not a guarantee.

**Verdict: yes** — exfiltration and injection are both closed by AEAD + a key the
attacker cannot obtain, while `am start` automation continues to work with zero
command-line changes after a one-time in-app password setup.

---

## 8. Files to change + test strategy

### 8.1 New module: `src/main/kotlin/tribixbite/cleverkeys/backup/crypto/` (pure JVM except the store)

| File | Contents |
|---|---|
| `Pbkdf2Sha256.kt` | RFC 2898 PBKDF2 over `javax.crypto.Mac("HmacSHA256")`. Pure JVM. `derive(password: CharArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray`. |
| `EncryptedBackupFormat.kt` | Header data class, `serialize`/`parse`, `sniff(bytes): PayloadKind` (ENCRYPTED / PLAINTEXT_JSON / PLAINTEXT_ZIP / UNKNOWN), constants (magic, content types, iteration cap). Pure JVM, no Android imports. |
| `BackupCrypto.kt` | `encrypt(plaintext: ByteArray, passphrase: CharArray, contentType: Byte, now: Long, random: SecureRandom): ByteArray` and `decrypt(container: ByteArray, passphrase: CharArray): DecryptedPayload(contentType, timestamp, bytes)`; plus streaming variants `encryptStream`/`decryptToFile` using the explicit chunk loop (no `CipherInputStream`). Pure JVM (`javax.crypto.Cipher`). Injected `SecureRandom` for deterministic tests. |
| `BackupPassphraseStore.kt` | Android-side: get/set/clear passphrase; Keystore-wrapped at rest with prefs fallback; `hasPassphrase()`. The only file in the module that touches Android APIs — keep it thin so everything else runs under `runPureTests`. |

### 8.2 Modified files

| File | Change |
|---|---|
| `BackupRestoreManager.kt` | `readJsonFromUri` (`:276`): sniff + decrypt seam. `openOutputStream` writers in `exportConfig:313`, `exportDictionaries:588`, `exportClipboardHistory:803`: route through an `encryptIfRequired(contentType)` wrapper. ZIP paths `exportClipboardHistoryZip:840` / `exportFullBackup:1157` / `importClipboardHistoryZip:891` / `importFullBackup:1265`: stream encrypt / decrypt-to-temp-then-parse. Add an `EncryptionPolicy` parameter (HEADLESS_MANDATORY vs UI_DEFAULT vs UI_PLAINTEXT_OPTOUT) supplied by the caller. |
| `BackupRestoreActivity.kt` | Before dispatch (`:84`): if `!passphraseStore.hasPassphrase()` → toast the setup message, log, `finish()`. Pass HEADLESS_MANDATORY policy. Optional: read the gated `passphrase` extra for `IMPORT_*` when the toggle is on. Rate-limit consecutive headless actions. |
| `ui/settings/sections/BackupRestoreSection.kt` | "Backup password" UI block (§9): status row + Set/Change/Remove; "Encrypt exports" indicator; plaintext opt-out checkbox on export. |
| `ui/settings/io/SettingsBackupHandlers.kt`, `SettingsDictionaryHandlers.kt`, `SettingsClipboardHandlers.kt` | Pass UI policy; on import `AEADBadTagException` → passphrase-prompt dialog → retry with entered passphrase. |
| `backup/SettingsValidation.kt` (`:30 INTERNAL_KEYS`) | Add the new pref keys (`backup_passphrase_ciphertext`, `backup_passphrase_iv`, `backup_allow_intent_passphrase`, …) so they are **never exported** and `SettingsDefaultsDriftTest` stays green. This is load-bearing: exporting the wrapped passphrase inside the settings backup it protects would be circular. |
| `AndroidManifest.xml:140` | Unchanged for this plan (activity stays exported — that's the constraint). Independent hardening from audit finding #5 (drop the MAIN filter per `remediation/2-data-security.md`) can ride along but is not part of this design. |
| `docs/wiki/troubleshooting/backup-restore.md` | Document: one-time password setup, unchanged `am start` commands, new failure messages, `.ckenc` naming, foreign-restore escape hatch + history warning. |
| `build.gradle` (`:349` `pureTestClasses`) | Register the new pure test classes. |

### 8.3 Tests (all pure-JVM ones runnable on this ARM64/Termux device via `./gradlew runPureTests -PtestClass=...`)

**Pure JVM (`src/test/kotlin/tribixbite/cleverkeys/backup/crypto/`):**

| Class | Asserts |
|---|---|
| `Pbkdf2Sha256VectorTest` | Output matches published PBKDF2-HMAC-SHA256 test vectors (RFC 7914 §11: P="passwd"/S="salt"/c=1/dkLen=64 and c=80000 cases); determinism (same inputs → same key twice); different salt → different key. |
| `BackupCryptoRoundTripTest` | encrypt→decrypt round-trips UTF-8 JSON (incl. emoji/multi-byte) and multi-MB binary (ZIP-shaped) payloads byte-exact; content type + timestamp survive; two encrypts of the same plaintext differ (fresh salt+nonce) yet both decrypt; streaming variant round-trips a >1 MiB payload chunk-boundary-exactly. |
| `BackupCryptoTamperTest` | Flipping any single bit in ciphertext, tag, or any header field (version, content_type, kdf params, salt, nonce, timestamp — AAD coverage) → `AEADBadTagException`/format error, never wrong plaintext; truncated file → clean error; wrong passphrase → `AEADBadTagException`; iterations above cap → rejected **before** KDF runs (assert via time bound or injected counter). |
| `EncryptedBackupFormatTest` | Header serialize/parse round-trip; `sniff` classifies: magic → ENCRYPTED, `PK\x03\x04` → PLAINTEXT_ZIP, `{`/leading-whitespace-then-`{` → PLAINTEXT_JSON, garbage → UNKNOWN; future `format_version` → distinct "newer version" error; magic with corrupted `\r\n` → UNKNOWN. |
| `BackupRestoreManagerHeadlessTest` (existing, extend) | Plan-builder behavior with encrypted input decrypts then produces the identical `SettingsImportPlan` as the plaintext equivalent (preview preservation, asserted by comparing plans built from plaintext JSON vs its encrypted container). |

**Instrumented (ew-cli, `src/androidTest/`):**

| Class | Asserts |
|---|---|
| `BackupRestoreEncryptionEndToEndTest` | With a stored passphrase: exportConfig/exportDictionaries/exportClipboardHistory produce `CKENC1`-magic files; import round-trip restores counts; importing with a changed stored passphrase fails with zero prefs/DB writes (snapshot-compare). |
| `BackupRestoreActivityHeadlessEncryptionTest` (extends existing activity test harness w/ `testManagerOverride`) | No passphrase → all six actions refuse (manager never invoked); passphrase set → `EXPORT_*` writes encrypted bytes to the supplied URI; headless `IMPORT_SETTINGS` with plaintext `json_base64` → rejected, no apply; with valid ciphertext extra → applied. |
| `BackupPassphraseStoreTest` | set/get/clear round-trip; survives process-ish recreation (new store instance); Keystore-fallback path stores and retrieves. |

Drift: `SettingsDefaultsDriftTest` (existing) automatically enforces classification of
the new pref keys — expect it to fail until §8.2's `INTERNAL_KEYS` addition lands;
that's the designed tripwire.

---

## 9. UX

**Settings → 💾 Backup & Restore → "Backup password" block (top of section):**
- No password: row "Backup password — Not set", warning subtext *"Required for
  automation (am start) export/import; encrypts all backups."* → tap → dialog with
  two fields (enter + confirm), min 8 chars, weak-warning under 12, show/hide toggle.
- Set: row "Backup password — Set ✓ (exports encrypted)"; actions **Change**
  (old → new) and **Remove** (requires typing current password; confirmation warns
  *"Automation backup/restore will stop working and new exports will be unencrypted
  only via the manual opt-out."*).
- Export buttons when a password is set: encrypted by default; overflow/long-press
  option "Export unencrypted…" with a scary-but-honest confirm.

**Import flows:**
- Encrypted file + stored passphrase decrypts → straight into today's preview dialog
  (now also showing the header's export timestamp + "🔒 encrypted" badge).
- Encrypted file + no/wrong stored passphrase → password prompt dialog ("This backup
  is encrypted. Enter its backup password."); wrong entry → *"Wrong backup password,
  or the file is corrupted/tampered."* with retry; cancel → import aborted, nothing
  touched.
- Plaintext legacy file via UI → today's flow, plus a one-line notice *"Unencrypted
  backup — consider re-exporting encrypted."*

**Headless toasts (unchanged shape, new cases):**
- `"Backup password not set — configure in Settings → Backup & Restore"`
- `"Import failed: wrong backup password or corrupted file"`
- `"Import failed: plaintext backups are not accepted via automation — use the app's Import button"`

---

## 10. Open questions for the user (blocking decisions)

1. **Interactive plaintext opt-out** — keep the "Export unencrypted…" escape (this
   design's recommendation), or make encryption fully mandatory once a password is
   set (simpler story, but breaks users who post-process JSON in scripts on-device —
   note they can still decrypt with a documented `openssl`-free Python snippet we
   should ship in the wiki either way)?
2. **Foreign-restore automation escape hatch** — ship the default-off
   `--es passphrase` import override, or omit it entirely (UI-only for
   foreign-device restores)?
3. **Legacy plaintext headless import grace period** — reject immediately (recommended)
   or one release behind a default-off toggle?
4. **KDF ambition** — accept PBKDF2-SHA256@600k now with a reserved Argon2id id, or
   pay the BouncyCastle dependency cost immediately for Argon2id?
5. **Decryption helper for scripts** — publish a reference `ckenc-decrypt.py`
   (stdlib + `cryptography`? or pure-stdlib AES-GCM is impossible — needs
   `cryptography` pip) in `scripts/` so Termux users can open their own encrypted
   exports off-app? (Recommended: yes.)
6. **#156 sequencing** — confirm this export-channel plan lands first and at-rest
   clipboard-DB encryption (the actual #156 ask) is a follow-up design that reuses
   `BackupPassphraseStore`/`BackupCrypto`.

---
*Design verified against source at commit `4ad8a536d` (2026-07-17). No code changes made.*
