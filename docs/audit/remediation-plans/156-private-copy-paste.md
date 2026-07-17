# #156 Private Copy/Paste — Design Document

**Status:** PROPOSED (design only — no code changes). Verified against source at commit `4ad8a536d`, 2026-07-17.
**Issue:** #156 "[Feature]: Encrypted Clipboard" (EsterWings). The author's *primary* worry is ask (1): a normal copy puts plaintext on the Android OS clipboard, where the foreground app and system processes can read it. This doc designs the chosen first deliverable: **a copy path that stores selected text directly into CleverKeys' private clipboard history and never calls `setPrimaryClip`.**
**Predecessors:** `156-at-rest-clipboard-encryption.md` (§7 sketched this feature; the user chose to build it FIRST), `security-backup-encryption.md` (shipped — encrypted exports exist, which §7 of this doc leans on).
**Composes with:** at-rest encryption later — private-copied entries land in a store that can additionally be encrypted at rest. The two features are independent; this one ships first.

---

## 1. Verified baseline: paste already private; copy is the gap

- **Paste from the panel never touches the OS clipboard.** `KeyEventHandler.paste_from_clipboard_pane` (`KeyEventHandler.kt:147`) → `sendTextDirect` (`:484-489`): `beginBatchEdit` / `commitText(text, 1)` / `endBatchEdit` on the target `InputConnection`. Verified — no `ClipboardManager` involvement anywhere on that path. (Caveat recorded for later: **media** paste, `paste_media_from_clipboard_pane`, has a `setPrimaryClip` *fallback* when `commitContent` fails, `KeyEventHandler.kt:190-215`. v1 of private copy is text-only, so this fallback is unreachable for private entries — but if private media entries ever exist, that fallback must be gated. See §5.6.)
- **Copy always goes through the OS.** Every current copy path is either `performContextMenuAction(android.R.id.copy)` (`Keyboard2View.kt:745`, `executeEditingCommand`) — the *target app* puts the text on the system clipboard — or a direct `setPrimaryClip` (panel long-press `ClipboardHistoryView.kt:1183-1188`, edit-field cut `:636`, etc.). History capture is then fed *from* the OS clipboard by the primary-clip listener → `ClipboardHistoryService.addClip` (`:265`).
- **So the missing half is exactly one thing:** get selected text into `ClipboardDatabase` without the OS clipboard ever holding it. Two independent acquisition routes below.

What this feature can and cannot promise (state this in the settings UI, honestly):

- ✅ Text privately copied via either entry point is never placed on the OS clipboard by CleverKeys. The foreground app never sees a "clipboard changed" event; Android's clipboard-access toasts never fire; the OS clipboard overlay/history (Gboard-style, OEM clipboard managers) never receive it.
- ⚠️ The **source app** that holds the selected text obviously already has the text (it's rendering it). Private copy protects against *clipboard-channel* exposure, not against the app you're copying from.
- ⚠️ If the user later *normally* copies the same text, or confirms the explicit "copy to system clipboard" escape hatch (§5.5), the OS sees it at that moment.

---

## 2. Feature overview

Two entry points feeding one new storage primitive:

| # | Entry point | Works where | Acquisition API |
|---|---|---|---|
| A | In-IME **"Private copy"** editing action (assignable to short-swipe / extra key / command palette) | Any field while CleverKeys is the active IME and the editor implements `getSelectedText` | `InputConnection.getSelectedText(0)` |
| B | **`ACTION_PROCESS_TEXT`** selection-toolbar activity ("Private copy" in other apps' text-selection toolbars) | Any app whose selection toolbar honors PROCESS_TEXT (API 23+; most TextViews, Chrome, most WebViews) — CleverKeys need not be the active keyboard | `Intent.EXTRA_PROCESS_TEXT` |

Both call a single new service method, `ClipboardHistoryService.addPrivateClip(text, sourcePackage)`, which stores with `is_private = 1` and **by construction contains no `ClipboardManager` reference** (§5.2). Entries surface in the existing panel with a 🔒 badge and are pasted via the already-private panel-paste path.

---

## 3. Entry point A — in-IME "Private copy" action

### 3.1 Placement decision

The customization framework gives us four surfaces for the price of one wiring, all driven by a `KeyValue` name:

1. **`KeyValue.Editing` enum + named key** — new `Editing.COPY_PRIVATE`, registered in `KeyValue.getSpecialKeyByName` as `"copy_private"` (editing-key block, `KeyValue.kt:715-747`; code point `0xE039` is the next free glyph slot after `textAssist`'s `0xE038`, or a small-font text label if no glyph is added to the key font — implementer's choice, text label `"🔒⎘"`-style is acceptable with `FLAG_SMALLER_FONT`).
2. **Short-swipe / command palette** — new `AvailableCommand.PRIVATE_COPY` in `customization/ActionType.kt` ("Private Copy", "Copy selection to CleverKeys only — never the system clipboard", icon `lock`), added to the `"Clipboard"` group in `groupedByCategory()`, plus a `CommandRegistry` entry (`name = "copy_private"`, `Category.CLIPBOARD`). Because `CommandRegistry.Command.name` resolves through `KeyValue.getKeyByName()` (`CommandRegistry.kt:26-28`), step 1 makes the palette entry executable with no further plumbing. `CustomShortSwipeExecutor.executeCommand` (`:432`) gets a `PRIVATE_COPY` branch.
3. **Extra keys / layout XML** — the named key is automatically placeable via existing extra-keys and custom-layout machinery (same as `"copy"`, `"textAssist"`).
4. **Editing pane** — the editing-mode key grid picks it up as any other `Editing` value once dispatch exists.

**Recommendation: no new dedicated UI.** Ship it as the named key + palette command; users place it where they want (short swipe on the existing copy key is the natural home — e.g. short-swipe-up on `copy` = private copy). This is the least-friction placement that fits the framework and adds zero layout churn.

### 3.2 Dispatch wiring

Two dispatch sites exist for `Editing` values; both get a branch:

- `KeyEventHandler.handleEditingKey` (`KeyEventHandler.kt:629`) — main key path. During clipboard inline-edit mode (`recv.isClipboardEditMode()`), `COPY_PRIVATE` is a **no-op** (like undo/redo there); otherwise:
  ```
  val text = recv.getCurrentInputConnection()?.getSelectedText(0)?.toString()
  if (text.isNullOrEmpty()) → recv/showSuggestionBarMessage("No text selected")
  else ClipboardHistoryService.privateCopy(context, text, recv.getCurrentEditorInfo()?.packageName)
       → showSuggestionBarMessage("Privately copied")
  ```
- `Keyboard2View.executeEditingCommand` (`Keyboard2View.kt:738`) — same logic; reuse the existing selected-text preamble pattern from `launchTextAssistActivity` (`:772-780`) including `showNoTextSelectedMessage` (`:765`). Feedback via `showSuggestionBarMessage`, **not** Toast — Toasts from IMEs are suppressed on Android 13+ (existing project convention, `Keyboard2View.kt:763-766`).

### 3.3 Limitations (documented, not solved)

- Works only while CleverKeys is the **active IME** with an input connection.
- `getSelectedText(0)` may return `null` even with a visible selection in editors that don't implement it (some WebView configurations, terminal emulators, custom editors). Feedback message covers this ("No text selected").
- Password/`textNoSuggestions` secure fields typically deny selection reads — correct behavior, no workaround wanted.
- Entry point B exists precisely to cover "CleverKeys isn't the focused IME / field won't yield a selection."

---

## 4. Entry point B — `PROCESS_TEXT` selection-toolbar activity

### 4.1 Manifest declaration

New activity `tribixbite.cleverkeys.PrivateCopyProcessTextActivity`:

```xml
<!-- #156 Private copy: selection-toolbar receiver. Exported BY DESIGN (threat
     review: docs/audit/remediation-plans/156-private-copy-paste.md §6) but
     android:enabled="false" until the user opts in via Settings → Clipboard.
     Inbound-only: reads EXTRA_PROCESS_TEXT, stores locally, returns no result. -->
<activity
    android:name="tribixbite.cleverkeys.PrivateCopyProcessTextActivity"
    android:label="@string/private_copy_toolbar_label"
    android:exported="true"
    android:enabled="false"
    android:theme="@android:style/Theme.NoDisplay"
    android:excludeFromRecents="true"
    android:noHistory="true"
    android:taskAffinity=""
    android:directBootAware="false">
  <intent-filter>
    <action android:name="android.intent.action.PROCESS_TEXT"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <data android:mimeType="text/plain"/>
  </intent-filter>
</activity>
```

Attribute rationale:

- **`android:enabled="false"` + settings toggle** — the load-bearing surface-control choice; see §6.6. The toggle flips the component via `PackageManager.setComponentEnabledSetting(component, COMPONENT_ENABLED_STATE_ENABLED / _DISABLED, DONT_KILL_APP)`. `STATE_DISABLED` (not `_DEFAULT`) on the off-path so the manifest default can later change without surprising users. While disabled the component is invisible to resolvers — **zero exported surface for users who never opt in**, preserving the spirit of the `5c1cdd6b`-era de-export hardening.
- **`Theme.NoDisplay`** — truly windowless. Contract: the activity MUST call `finish()` before `onResume()` completes or the framework throws (`Activity did not call finish()`); we finish inside `onCreate()`, satisfying it. No flicker, no dim, unlike `Translucent.NoTitleBar`.
- **`excludeFromRecents` + `noHistory` + `taskAffinity=""`** — belt-and-braces: even if a caller launches us plain (not `forResult`) with `NEW_TASK`, we never linger in recents, never restart, never root a task affiliated with our real activities.
- **`directBootAware="false"`** — `ClipboardDatabase` lives in Credential-Encrypted storage (the service's own Direct-Boot deferral, `ClipboardHistoryService.kt:818-838`). Pre-unlock, the activity must simply not exist. Guard in code too (§4.2).
- **Label** — the selection toolbar shows the activity label. `"Private copy"` reads best in the toolbar row (the toolbar already namespaces by app icon on most OEMs); use `"CleverKeys private copy"` if user prefers explicitness (string resource, trivially changed).
- `ACTION_PROCESS_TEXT` is API 23+; minSdk is 21 (`build.gradle:107`). On API 21-22 no system UI sends the action; a direct malicious launch is handled identically (§4.2 validation), so no version gate is needed beyond the component being opt-in.

### 4.2 Activity behavior (entire lifecycle in `onCreate`)

```
onCreate:
  1. Parse+validate via pure-JVM PrivateCopyIntentParser (§8):
       - action == ACTION_PROCESS_TEXT, type text/plain (lenient: extra presence is what matters)
       - text = intent.getCharSequenceExtra(EXTRA_PROCESS_TEXT)?.toString()
       - reject: null, blank-after-trim, byte length > clipboard_max_item_size_kb (same
         512 KB-class cap addClip enforces, ClipboardHistoryService.kt:272-290)
       - IGNORE intent.clipData, EXTRA_STREAM, and every other extra — we never read a
         URI, never call ContentResolver, never accept a granted permission. This
         closes the classic "exported activity coerced into opening attacker URIs" class.
       - EXTRA_PROCESS_TEXT_READONLY: read for logging only; behavior identical (§6.4).
  2. Guards: user unlocked (UserManager.isUserUnlocked — mirrors the service's own check,
     ClipboardHistoryService.kt:802-809); feature pref enabled (defensive double-check —
     component-disable is the real gate, but a stale resolver cache shouldn't bypass policy).
  3. Rate limit per calling package (§6.5). callerPkg = getCallingPackage() ?: "direct-launch".
  4. ClipboardHistoryService.privateCopy(applicationContext, text, callerPkg)
  5. Toast "Copied privately to CleverKeys" (activities may toast; the Android-13 IME
     toast suppression does not apply here). Suppressible via the same settings row.
  6. finish()   // no setResult → RESULT_CANCELED
```

**Config-initialization trap (verified, must not be skipped):** `Config.globalConfig()` *throws* when the IME hasn't initialized it (`Config.kt:1183-1184`), and `ClipboardHistoryService.addClip`-style code calls it liberally. The activity is a valid cold-start entry point of the process. Follow the established standalone-activity pattern (`SwipeCalibrationActivity.kt:124-126`): `if (Config.globalConfigOrNull() == null) Config.initGlobalConfig(DirectBootAwarePreferences.get_shared_preferences(this), resources, null, false)` before touching the service. `globalConfigOrNull()` exists exactly for this branch (`Config.kt:1194`).

### 4.3 Returned-value contract

`PROCESS_TEXT` is launched by `TextView` via `startActivityForResult` (request code 100); on `RESULT_OK` with an `EXTRA_PROCESS_TEXT` result extra, TextView **replaces the selection** with the returned text. We are a pure consumer: we never call `setResult(RESULT_OK, ...)` — default `RESULT_CANCELED` with no data means the host app leaves the text untouched, for both editable and read-only selections. This is also a security property: an activity that echoes text back could be abused to mutate a victim app's field content; ours structurally cannot.

---

## 5. Storage: the `is_private` marker (schema V5)

### 5.1 Decision: yes, a schema flag — reusing the entry path untouched is not viable

The three behaviors that define "private" — (a) never auto-placed on the OS clipboard, (b) badged in the panel, (c) excludable from exports — all require per-entry state that survives restarts, pin/todo copies, and re-ordering. The alternatives fail:

- *No marker, rely on "it just never gets pushed":* false — panel long-press (`ClipboardHistoryView.kt:1183-1188`) pushes any entry's text to `setPrimaryClip` today; without a flag we can't gate it.
- *Sidecar table keyed by content:* `content_hash` is 32-bit `String.hashCode()` (`ClipboardDatabase.kt:225`) — collision-prone as a foreign key, and inline content edits (`updateEntryContentInTable`) would orphan sidecar rows.
- *Tags JSON:* `clipboard_entries` (the table private copies land in) has **no tags column** in V4 — only pinned/todo do.

### 5.2 Schema V5 (clipboard DB)

`DATABASE_VERSION 4 → 5` in `ClipboardDatabase.kt` (`:1633`), O(1) `ALTER TABLE ADD COLUMN` migration in the existing v3→v4 pattern, applied to **all three tables** (`clipboard_entries`, `pinned_entries`, `todo_entries` — pin/todo use COPY semantics, so the marker must travel):

- `is_private INTEGER NOT NULL DEFAULT 0`
- `source_package TEXT` (nullable) — provenance; the threat-review mitigation (§6.3). For entry point A it's the target editor's package (`EditorInfo.packageName`); for entry point B it's `getCallingPackage()` (kernel-attested, non-spoofable) or `"direct-launch"` when launched without `forResult`. `NULL` for all pre-existing and normal-copy rows.

**Version-number coordination:** `156-at-rest-clipboard-encryption.md` §5.2 also claims V5. This feature ships first, so **private-copy takes V5; at-rest encryption becomes V6** (or absorbs into a single V5 if both land in one release). The encryption doc's `enc` columns are orthogonal to these; no conflict beyond the number.

Data-class impact: `ClipboardEntry`, `PinnedEntry`, `TodoEntry` gain `isPrivate: Boolean` + `sourcePackage: String?`; all `SELECT` sites in `ClipboardDatabase` add the two columns; `pinEntry` (`:487`) and `addTodoEntry` (`:666`) propagate them into the copy.

### 5.3 Write path: `addPrivateClip`

New `ClipboardHistoryService.addPrivateClip(text: String, sourcePackage: String?)` + static `privateCopy(ctx, text, source)` companion wrapper (constructs via the existing `get_service(ctx)` double-checked singleton, `:861` — already safe from a bare activity context; the constructor does **not** register the OS-clipboard listener, verified `:818-850`, so entry point B never accidentally starts clipboard monitoring).

Behavior relative to `addClip` (`:265`):

| Step | `addClip` (OS-listener path) | `addPrivateClip` |
|---|---|---|
| History-enabled gate | `clipboard_history_enabled` | **Own gate** — see Decision #4. `clipboard_history_enabled` governs *OS-clipboard monitoring*; a privacy-focused user may want monitoring OFF and private copy as the *only* capture route. Proposed: private copy works whenever the private-copy feature is enabled, independent of the monitoring toggle. |
| Size cap | 512 KB-class check | identical |
| URL sanitizer | `process(clip)` | identical (sanitizing the *stored* content is store hygiene, not an OS interaction) |
| `systemClipboardRewrite` | may call `setPrimaryClip` on sanitized URLs (`:352`) | **NEVER invoked.** The rewrite exists to fix a clip that is *already on* the OS clipboard; for a private copy nothing is there — invoking it would be the exact leak this feature exists to prevent. This is the single most important line-level difference, and the MockK test pins it (§8). |
| DB insert | `addClipboardEntry(content, expiry)` | `addClipboardEntry(content, expiry, isPrivate = true, sourcePackage)` (new params, defaulted so existing callers compile unchanged) |
| Limits + change listener | count/size pruning, `on_clipboard_history_change` | identical |

**Structural guarantee, enforced by review + test:** `addPrivateClip` and everything it transitively calls contain no `ClipboardManager` reference. The MockK suite verifies `verify(exactly = 0) { cm.setPrimaryClip(any()) }` across the whole private path.

### 5.4 Dedup interaction — sticky privacy

`addClipboardEntry` dedups (`content_hash = ? AND content = ?`, move-duplicate-to-top, `:229-`). Merge rule when a duplicate exists:

- **`is_private := old OR new` (sticky).** Privately copying text that already exists as a normal entry upgrades the row to private (henceforth export-excluded and push-gated). Rationale: the marker's forward-looking promises (never auto-push, exclude from export) are what the user is asking for *now*.
- A later **normal** copy of existing private content keeps `is_private = 1` — but note honestly: that normal copy already put the text on the OS clipboard via the OS listener; stickiness preserves the export/push policy, it cannot un-ring that bell. (Documented in the settings help text.)
- `source_package`: most-recent-non-null wins.

### 5.5 Read-path gating (promise (a))

Every site that moves *stored entry content* onto the OS clipboard gets an `is_private` gate with an explicit confirmation dialog ("This will expose the text to the system clipboard. Copy anyway?"):

- `ClipboardHistoryView.kt:1183-1188` — entry long-press "Copied to clipboard". (The edit-field cut at `:636` operates on in-progress edit text, not a stored entry — out of scope, unchanged.)
- Any future "copy entry" affordances inherit the rule via a single helper, `ClipboardHistoryView.copyEntryToSystemClipboard(entry)`, that owns the confirm-if-private branch.

**Always-confirm, no extra pref** (one fewer setting; the dialog is the discoverability moment for what "private" means). Panel paste (`paste_entry` → `ClipboardHistoryService.paste` → `paste_from_clipboard_pane`) is untouched — already private.

### 5.6 Pin / todo / expiry / media

- **Pin/todo:** flag + source propagate into the copy (§5.2). A pinned private entry stays private forever (pins don't expire).
- **Expiry:** unchanged — private entries obey the same TTL/limits. (A shorter private-TTL is a plausible follow-up, deliberately out of scope.)
- **Media:** v1 is **text-only** (both entry points only yield text). `is_private` on media rows is structurally possible but unreachable; if ever reached, the `commitContent`→`setPrimaryClip` fallback (`KeyEventHandler.kt:203-215`) MUST be gated to fail (not fall back) for private entries. Recorded as a `// TODO` guard comment in v1.

---

## 6. Exported-activity threat review

This section justifies deliberately re-expanding the exported surface we just contracted (10 activities de-exported in `5c1cdd6b`; the manifest's own comment at `AndroidManifest.xml:77-79` documents that posture). The bar: the new surface must be *narrower than what already remains exported* and every abuse path must be enumerated with a mitigation or an explicit acceptance.

### 6.1 Surface definition

One exported activity, no permission guard (PROCESS_TEXT resolvers cannot demand caller permissions — the OS toolbar launches from arbitrary apps), accepting `ACTION_PROCESS_TEXT` / `text/plain` with a `CharSequence` extra. Any installed app can `startActivity`/`startActivityForResult` at it directly with arbitrary text. **Dataflow is strictly inbound**: text in → local DB row + toast → `finish()` with `RESULT_CANCELED`, no result extras, no URI reads, no writes outside the app's own CE storage.

For calibration, the surface that already exists and survived the hardening pass: `SettingsActivity` is exported with `SEND`/`VIEW` ZIP intent-filters (attacker-supplied *archives* reach import parsing), and `BackupRestoreActivity` is exported with six action verbs accepting `file`/`content` schemes (`AndroidManifest.xml:46-67, :109-127`). A no-result text sink is categorically smaller than either.

### 6.2 Asset-by-asset analysis

| Property | Threat | Assessment |
|---|---|---|
| **Confidentiality** | Can a caller *read* anything? | **No path exists.** No result is returned; no content provider is exposed; the activity never reads clipboard history, only appends. A malicious launch learns nothing (not even whether the entry deduped — timing side channels on a local SQLite insert are noise). This is the decisive difference from clipboard-*reading* surfaces. |
| **Integrity of user data** | Any app can **inject entries into the user's clipboard history** — the real finding. | Two sub-threats, next rows. |
| ↳ *Planted-content* | Attacker inserts a lookalike wallet address / malicious URL / shell command hoping the user later pastes it believing they copied it (clipboard-substitution scam, inbound variant). | **The one genuinely interesting attack — but it is untargeted.** Classic clipboard substitution works because malware *reads* what you copied and swaps a matching-format value. This surface has **no read access**, so the attacker plants blind: the entry appears at the top of history with a timestamp, a 🔒 badge, and (mitigation §6.3) a **provenance line naming the calling package**. The user must still manually select and paste it. Residual risk: a user who privately copies something and immediately pastes "the top entry" without looking could be raced by a foreground attacker app. Mitigations: provenance display + the §6.5 rate limit + the platform's own foreground requirement (next row) reduce this to social-engineering-with-extra-steps. **Accepted with mitigations.** |
| ↳ *History eviction / spam DoS* | Flood entries until count/size pruning (`applySizeLimit*`) evicts the user's genuine history. | Bounded by three layers: **(1) Background-activity-launch restrictions (API 29+)** — a background app cannot start our activity at all; the injector must be foreground (visible) or hold SAW, i.e., the user is watching an app hammer their screen. On API 21-28 devices background starts are possible — the rate limit matters most there. **(2) Rate limit** (§6.5). **(3) Pinned/todo tables are never pruned by history limits** — the user's curated data cannot be evicted, only the rolling history. Accepted. |
| **Availability** | Launch-loop to churn the process/DB. | Each launch is a `Theme.NoDisplay` activity + one INSERT — cheap. BAL restrictions + rate limit as above. The 512 KB item cap bounds per-call cost; total DB growth is bounded by the user's existing count/size limits. Accepted. |
| **Confused deputy via extras** | Craft `ClipData`/URI extras so we open an attacker URI with granted permissions, or traverse a path. | **Structurally closed:** the parser touches exactly one extra (`getCharSequenceExtra(EXTRA_PROCESS_TEXT)`), never `intent.clipData`, never `ContentResolver`, never a file path. Enforced by the pure-JVM parser being the *only* intent-reading code, with tests asserting hostile extras are ignored (§8). |
| **UI spoofing** | Injected launch shows our "Copied privately" toast → user believes *they* did something. | Real but marginal: the toast fires while the *attacker's* app is foreground doing something the user didn't ask for. Provenance badge makes the resulting entry attributable after the fact. Accepted. |
| **Intent interception (outbound)** | — | N/A: we never send; this activity only receives. (CleverKeys' own outbound PROCESS_TEXT choosers, `Keyboard2View.kt:782, :816`, are unchanged and unrelated.) |

### 6.3 Can we distinguish a genuine toolbar launch from a programmatic one? (Analyzed: **no** — design accordingly)

- The system text-selection toolbar (TextView's `Editor`) launches PROCESS_TEXT via **`startActivityForResult`** from the host app, so `getCallingActivity()`/`getCallingPackage()` are non-null and identify the host app. But **any** app can equally use `startActivityForResult` — the signal proves *who* called (kernel-attested, unspoofable), not *why*. There is no "launched by the selection toolbar" attestation in the platform.
- `EXTRA_PROCESS_TEXT_READONLY` is set by the toolbar based on field editability — trivially settable by an attacker; not an origin signal.
- `getReferrer()` / `EXTRA_REFERRER` — app-supplied, spoofable. Rejected.
- Allowlisting callers is a non-starter: the legitimate caller set is "every app the user selects text in."

**Consequence:** we cannot gate on origin, so the design must be safe under arbitrary callers — which §6.2 shows it is, because the surface is inbound-only. What `getCallingPackage()` *does* give us is non-repudiable **attribution**, which we bank as the `source_package` column (§5.2): the panel's entry detail shows "via Private copy · <app label>", and a launch without `forResult` (which the real toolbar never does) is recorded as `direct-launch` — itself a strong tell that an entry was programmatically injected. This converts the unanswerable prevention question into a cheap detection answer.

### 6.4 `PROCESS_TEXT_READONLY` and the result contract

- READONLY true (selection in a non-editable view): identical behavior — we only read the extra. Copying from read-only text is a *primary* use case (articles, chat bubbles).
- READONLY false: still identical; we are not a text transformer. **Never** `setResult(RESULT_OK)` — see §4.3; returning text would create a field-mutation capability that does not need to exist.

### 6.5 Rate limit — concrete spec

`PrivateCopyRateLimiter` (pure JVM, injectable clock): sliding-window **10 accepts per calling package per minute, 30 total per minute**, excess → drop silently + `Log.w` with caller package (no toast — don't give a flooding app a UI channel). State: in-memory in a companion object (process-lifetime); persistence is deliberately omitted — the limiter is anti-annoyance defense-in-depth on top of BAL restrictions, not a security boundary, and a process restart resetting it is acceptable. Genuine human toolbar usage never approaches 10/min from one app.

### 6.6 Recommended posture (the decision, argued)

**Accept as a low-harm inbound surface, shipped OFF by default, with provenance + rate limiting + strict parsing.** Specifically:

1. `android:enabled="false"` — users who never opt in re-expand nothing; the `5c1cdd6b` posture is preserved by default. The settings toggle is the sole enabler. (This is the honest reconciliation of "we just de-exported 10 activities" with "we're adding an exported activity": the surface exists only for users who explicitly bought the feature it serves.)
2. No signature/permission gating (impossible for this action, §6.2 makes it unnecessary).
3. Provenance recorded and surfaced (§6.3) — injected entries are attributable, not silent.
4. Rate-limited (§6.5), strict single-extra parsing (§6.2 row 5), no result ever returned (§6.4).
5. Rejected alternatives: *origin gating* (no reliable signal exists — §6.3); *quarantine bucket for programmatic entries* (over-engineering for an attack that requires a foreground attacker app and yields an attributable, badged, user-visible entry; the `direct-launch` source marker already provides the distinction a future quarantine could key on if field reports ever justify it).

---

## 7. Export exclusion

The point of "private" is bounding exposure; exports are the only sanctioned egress of clipboard data (backup rules already exclude the DB from cloud/D2D — verified in the predecessor doc §2.3). Options:

- **(A) Exclude private entries from ALL exports.** Cleanest promise: "private = never leaves this device except by you re-typing it." Cost: a user's private entries silently don't survive device migration; surprise at restore time.
- **(B) Include only in ENCRYPTED (CKENC) exports; exclude from plaintext exports** — now possible because backup encryption shipped (`security-backup-encryption.md`, `1114bb749`; headless path is mandatory-encrypted already). Plaintext-export path reports "N private entries excluded (encrypt the export to include them)". Migration works; a plaintext JSON on a shared drive never contains private entries.
- **(C) Include everywhere** — defeats the marker; rejected.

**Recommendation: (B)**, because it makes the encrypted path strictly more capable (nudging users toward it) while keeping the plaintext failure mode safe. Mechanics: `ClipboardDatabase.exportToJSON` (`:1264`) gains `includePrivate: Boolean`; when included, rows carry `"is_private"` / `"source_package"` fields so the marker **round-trips** through backup/restore (import path writes them back; absent fields default to non-private for old backups). Genuine fork → Decision #2.

---

## 8. UX

- **Panel badge:** small 🔒 chip on private rows in all tabs (History/Pinned/Todos), rendered next to the existing mime/pin affordances in the entry row; entry expanded/detail view adds the provenance line ("Private copy · via <app label or 'direct launch'>", label resolved from `source_package` via `PackageManager`, falling back to the raw package name for uninstalled apps). No dedicated tab — tab real estate is scarce and the population is expected to be small; a "private only" toggle can join the existing filter row later if wanted.
- **Feedback:** entry point A → `showSuggestionBarMessage("Privately copied")` (IME-safe, §3.2); entry point B → Toast `"Copied privately to CleverKeys"` (activity context — not suppressed).
- **Discoverability:** the palette command ("Private Copy", Clipboard category) is the primary discovery surface; the settings section (below) explains both entry points; release notes + wiki page.
- **Settings (Clipboard section, `ui/settings/sections/ClipboardSection.kt`):** new "🔒 Private copy" group:
  - `private_copy_toolbar_enabled` (Boolean, default **false**) — "Show 'Private copy' in other apps' text-selection menus". Flips the component (§4.1) on change. Help text states the honest scope (§1 bullets) and that enabling adds an app entry other apps can see.
  - Static help row describing the in-IME action and how to bind it (short swipe / extra key).
  - New pref keys MUST be classified in `SETTINGS_DEFAULTS` (backup/SettingsDefaults.kt) — the `SettingsDefaultsDriftTest` tripwire enforces this; the component-state itself is derived from the pref (no second source of truth).
- **Always-confirm dialog** for exporting a private entry to the system clipboard (§5.5) — doubles as inline education.

---

## 9. Files to change + test strategy

### 9.1 New files

| File | Contents |
|---|---|
| `src/main/kotlin/tribixbite/cleverkeys/PrivateCopyProcessTextActivity.kt` | §4.2 lifecycle. Thin: parse (delegate) → guards → `privateCopy` → toast → finish. |
| `src/main/kotlin/tribixbite/cleverkeys/clipboard/PrivateCopyIntentParser.kt` | **Pure JVM.** `parse(action: String?, hasProcessTextExtra: Boolean, text: CharSequence?, maxBytes: Int): Result` where `Result = Accept(text) \| Reject(reason)`. Takes primitives/CharSequence (not `Intent`) so it tests without Android. Trims, size-caps (UTF-8 bytes), rejects null/blank. |
| `src/main/kotlin/tribixbite/cleverkeys/clipboard/PrivateCopyRateLimiter.kt` | **Pure JVM.** Sliding window per key + global (§6.5), injected `clock: () -> Long`. |

### 9.2 Modified files

| File | Change |
|---|---|
| `AndroidManifest.xml` | §4.1 activity block. |
| `KeyValue.kt` | `Editing.COPY_PRIVATE` (`:83-103` enum); `"copy_private"` named key (`:715-747` block). |
| `KeyEventHandler.kt` | `handleEditingKey` branch (`:629`); edit-mode no-op. |
| `Keyboard2View.kt` | `executeEditingCommand` branch (`:738`), reusing the `:772-780` selection-read pattern. |
| `customization/ActionType.kt` | `AvailableCommand.PRIVATE_COPY` + `groupedByCategory` Clipboard list. |
| `customization/CustomShortSwipeExecutor.kt` | `executeCommand` branch (`:443`). |
| `customization/CommandRegistry.kt` | `copy_private` entry, `Category.CLIPBOARD`. |
| `ClipboardHistoryService.kt` | `addPrivateClip` + static `privateCopy` (§5.3); refactor the shared cap/sanitize/insert/limits core out of `addClip` so the two paths differ **only** in the gate, the `systemClipboardRewrite` call, and the insert flags (DRY, and it makes the "no `ClipboardManager` on the private path" property reviewable in one screen). |
| `ClipboardDatabase.kt` | V5 migration (`:1633` + DDL); `addClipboardEntry(content, expiry, isPrivate = false, sourcePackage = null)`; dedup stickiness (§5.4); SELECT/entity plumbing; `pinEntry`/`addTodoEntry` propagation; `exportToJSON(textOnly, includePrivate)` (`:1264`) + import round-trip. |
| `ClipboardEntry.kt` / pinned+todo entities | `isPrivate`, `sourcePackage` fields. |
| `ClipboardHistoryView.kt` | 🔒 badge + provenance line; `copyEntryToSystemClipboard` helper with confirm-if-private (`:1183-1188` migrates into it). |
| `ui/settings/sections/ClipboardSection.kt`, `Config.kt`, `backup/SettingsDefaults.kt` | Toggle, pref, component flip, `SETTINGS_DEFAULTS` classification. |
| `res/values/strings.xml` | Toolbar label, toasts, dialog, settings strings. |
| `build.gradle` | Register new pure test classes in `pureTestClasses`. |
| `docs/specs/clipboard-privacy.md` | Record the feature per spec-driven workflow. |

### 9.3 Tests

**Pure JVM (`./gradlew runPureTests`, runs on this device):**

| Class | Asserts |
|---|---|
| `PrivateCopyIntentParserTest` | accept: normal text, READONLY-irrelevance, exactly-at-cap; reject: null extra, blank/whitespace, over-cap (multibyte UTF-8 counted in bytes), wrong action; hostile-extra matrix is moot by construction (parser API cannot receive clipData/URIs — assert the API shape via compilation, document in test header). |
| `PrivateCopyRateLimiterTest` | per-key window (11th call in 60 s dropped, allowed after window slides), global cap, key isolation, clock injection, process-restart reset semantics. |
| `PrivateClipMergeRuleTest` | sticky-privacy dedup rule as a pure function (old,new flags → merged flags; source most-recent-non-null) — extracted so the DB test only verifies wiring. |

**MockK (`./gradlew runMockTests`):**

| Class | Asserts |
|---|---|
| `PrivateCopyServiceTest` | `addPrivateClip` inserts with `isPrivate=true` + source; **`verify(exactly = 0) { cm.setPrimaryClip(any()) }` and no `systemClipboardRewrite` across the entire private path** (the load-bearing regression pin); sanitizer still applied to stored content; size-cap rejection; works with `clipboard_history_enabled=false` (per Decision #4 outcome); change-listener fired. |

**Instrumented (ew-cli: `--use-orchestrator --timeout 25m --device model=Pixel7,version=34`, debug APK):**

| Class | Asserts |
|---|---|
| `PrivateCopyProcessTextActivityTest` | launch with EXTRA_PROCESS_TEXT → row exists with flag+source; result is `RESULT_CANCELED` with null data (both READONLY values); hostile intents (no extra / oversized / clipData-URI-bearing) → no row, no crash, no URI access; component default-disabled (`PackageManager.getComponentEnabledSetting`), toggle flips it; **OS primary clip unchanged after the whole flow** (read `ClipboardManager` before/after). |
| `ClipboardDatabaseV5MigrationTest` | V4→V5 preserves all rows across three tables; new columns default `0`/`NULL`; pin/todo propagation of flag+source; dedup stickiness end-to-end; export includePrivate matrix + import round-trip of the marker. |
| `PrivateCopyEditingKeyTest` | in a test editor: select text → dispatch `Editing.COPY_PRIVATE` → entry stored privately, primary clip untouched, "No text selected" path when selection empty. |
| `ClipboardPanelPrivateBadgeTest` | private row renders badge + provenance; long-press on private entry shows confirm dialog and only writes `setPrimaryClip` after confirm. |

---

## 10. Decisions needed from the user

1. **Exported-activity posture (§6.6)** — confirm: exported PROCESS_TEXT activity, **`enabled="false"` by default**, opt-in via settings toggle, provenance column + rate limit, no origin gating (shown impossible in §6.3). The alternative — shipping it enabled by default for discoverability — is defensible for a feature users must find, but contradicts the fresh de-export posture; I recommend default-off.
2. **Export exclusion (§7)** — recommend **(B)**: private entries included only in encrypted exports, excluded (with a visible count) from plaintext exports, marker round-trips. Alternative (A): excluded from all exports — stronger promise, loses migration. Pick B or A.
3. **`is_private` schema (§5)** — confirm V5 with `is_private` + `source_package` on all three tables, and that **this feature takes V5, bumping the at-rest-encryption design to V6**. The `source_package` column is the threat-review mitigation; dropping it weakens §6.3's detection story — flag if you want it out.
4. **Gate interaction** — should private copy work while `clipboard_history_enabled` is **false**? Proposed **yes** (that pref governs OS-clipboard *monitoring*; monitoring-off + private-copy-only is a coherent, arguably ideal, privacy configuration). Alternative: require history enabled (simpler mental model, one gate).
5. **Sticky dedup (§5.4)** — confirm `is_private` ORs across duplicate copies (a private copy of an existing normal entry marks it private permanently). Alternative: keep flags per most-recent copy (non-sticky) — simpler but lets a later normal copy silently strip export exclusion.
6. **Toolbar label string** — `"Private copy"` vs `"CleverKeys private copy"` in other apps' selection toolbars (pure copywriting; changeable anytime).

---
*Design verified against source at `4ad8a536d` (2026-07-17): `KeyEventHandler.kt` (`:147, :484, :629`), `Keyboard2View.kt` (`:738-832`), `ClipboardHistoryService.kt` (`:245, :265-352, :794-900`), `ClipboardDatabase.kt` (`:222-240, :1264, :1633`), `ClipboardHistoryView.kt` (`:636, :1183-1200`), `customization/` (ActionType, CommandRegistry `:26`, CustomShortSwipeExecutor `:32-96, :432`), `KeyValue.kt` (`:83-103, :588, :715-747`), `Config.kt` (`:489-513, :1168-1196`), `AndroidManifest.xml`, `SwipeCalibrationActivity.kt:124-126` (standalone-activity Config-init precedent), `clipboard/sanitize/UrlSanitizer.kt` + `systemClipboardRewrite` (the precedent — and the call the private path must never make). No code changes made.*
