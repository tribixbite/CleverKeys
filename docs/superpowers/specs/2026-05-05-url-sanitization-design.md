# URL Sanitization on Clipboard Copy — Design

**Date**: 2026-05-05
**Status**: Approved (pending spec-reviewer + user sign-off)
**Affected files**: `ClipboardManager.kt`, `ClipboardSettings*.kt`, new `clipboard/sanitize/*` package, plus tests + new bundled assets.

## Problem

Users frequently copy URLs containing tracking parameters (`utm_*`, `fbclid`, `spm`, `aff_*`, etc.) and end up sharing dirty links. Existing solutions like ClearURLs maintain comprehensive rule sets but are browser extensions, not keyboards. CleverKeys' on-device clipboard is the right hook point: every URL that flows through the keyboard's copy path could be cleaned, embedded-fixed, or transformed by user-supplied rules before storage. CleverKeys' privacy-pure constraint (`NO INTERNET` permission) requires bundled rules + offline rule application.

## Goals

1. **Strip known tracking parameters** from URLs copied via CleverKeys, using the open-source ClearURLs ruleset (~700 providers, ~60 KB minified).
2. **Optionally rewrite host names** to embed-fixing services (`x.com → fxtwitter.com`, `reddit.com → rxddit.com`, etc.) for users sharing in Discord/Slack/iMessage.
3. **Allow user-supplied rule files** in ClearURLs format via SAF file picker, merged with bundled rules so users can extend, override, or disable specific providers.
4. **Idempotent** — sanitizing an already-sanitized URL returns it byte-identical.
5. **Privacy-pure** — no network, no telemetry, all rule application on-device.

## Non-goals

- System-clipboard interception (would require `READ_CLIPBOARD` permission; out of scope).
- Live ClearURLs ruleset updates from upstream (would need INTERNET; bundled snapshot only).
- Browser-redirect logic (e.g. `bit.ly` unfurling, `t.co` expansion). ClearURLs' `redirections` field is supported only for direct URL substitution, not HTTP follow-through.
- URL extraction from media attachments / OCR / etc.

## Architecture

### Three independent toggles, one ruleset format

```
Settings → Clipboard → URL handling
─────────────────────────────────────
[ ] Sanitize tracking parameters       (bundled clearurls.json — ~60KB, 700+ providers)
[ ] Enrich embeds for sharing          (bundled embed_enrich.json — ~3KB, ~10 providers)
[ ] Use custom rules                   (user file via SAF → custom.substitutions.json)
    [Browse...]    Status: 12 rules from custom.substitutions.json
```

All three sources use ClearURLs JSON format. Same parser, same engine. User can copy ClearURLs upstream JSON verbatim, edit, and import.

### Pipeline

```
copy gesture / key
  → ClipboardManager.addEntry(text, ...)
    → UrlSanitizer.process(text)        (only if any toggle is ON)
      → for each URL match in text:
          → unifiedRuleset.findProvider(url)?.apply(url)
          → no match → leave URL unchanged
      → preserve all non-URL text verbatim
    → SQLite insert (existing path, unchanged)
```

**Pipeline order — bundled before custom**: `clearurls.json` → `embed_enrich.json` → `custom.substitutions.json`. Custom rules are merged per-provider (not appended) so user can override single fields (e.g. add `exceptions: [".*"]` to disable a bundled provider entirely without losing the rest).

### `clipboard/sanitize/` package

```kotlin
// ClearURLs schema — direct port from upstream
data class Provider(
    val urlPattern: Regex,
    val rules: List<String> = emptyList(),         // case-insensitive query-param names to strip
    val rawRules: List<Regex> = emptyList(),       // regex strip from full URL
    val redirections: List<RedirectionRule> = emptyList(),
    val exceptions: List<Regex> = emptyList(),
    val completeProvider: Boolean = false,         // true = skip ALL processing (block)
)

data class RedirectionRule(
    val pattern: Regex,                            // capture-group regex
    val replacement: String,                       // $1, $2 substitution template
)

data class Ruleset(
    val providers: Map<String, Provider>,          // key = provider name
)

object RulesetParser {
    fun fromJson(jsonString: String): Ruleset
    fun merge(base: Ruleset, overlay: Ruleset): Ruleset    // per-provider field merge
}

interface UrlSanitizer {
    fun process(text: String): String   // idempotent; preserves non-URL content
}

class RulesetUrlSanitizer(private val ruleset: Ruleset) : UrlSanitizer {
    override fun process(text: String): String { /* ... */ }
}
```

### Merge semantics (`RulesetParser.merge`)

Per-provider, field-level overlay:

```
For each provider name K in overlay:
  if base.providers[K] is null:
    base.providers[K] = overlay.providers[K]
  else:
    base.providers[K].rules ++= overlay.providers[K].rules
    base.providers[K].rawRules ++= overlay.providers[K].rawRules
    base.providers[K].redirections ++= overlay.providers[K].redirections
    base.providers[K].exceptions ++= overlay.providers[K].exceptions
    if overlay.providers[K].completeProvider == true:
      base.providers[K].completeProvider = true       (overlay can disable; cannot re-enable)
    if overlay.providers[K].urlPattern is non-default:
      base.providers[K].urlPattern = overlay.providers[K].urlPattern
```

**Why append rather than replace** for `rules`/`rawRules`/`redirections`/`exceptions`: extending strict subsets of behavior is the common case. User who supplies `{"twitter": {"rules": ["custom_param"]}}` adds one tracker without losing the bundled twitter rule's existing entries. User who wants surgical disable of a bundled provider's behavior adds `{"twitter": {"exceptions": [".*"]}}` — which appends to bundled exceptions and shorts every match.

### Provider matching algorithm

```
for url in extracted_urls(text):
  for provider in unified_ruleset.providers.values:
    if not provider.urlPattern.matches(url): continue
    if provider.exceptions.any { it.matches(url) }: continue
    if provider.completeProvider: skip url, no rewriting
    apply provider.redirections (first match wins, otherwise no-op)
    apply provider.rawRules (regex strip from URL string)
    apply provider.rules (case-insensitive query-param strip)
    break        // first matching provider wins; further providers skipped
```

Provider iteration order: globalRules first (always-on cleanups like `utm_*`), then specific providers in JSON-declaration order. Custom rules merged per-provider (not appended to the iteration list) so they take effect through the same matching path.

### URL extraction from arbitrary text

`android.util.Patterns.WEB_URL` matches HTTP/HTTPS URLs in free text. For each match, run the pipeline; preserve surrounding text. Non-HTTP URLs (mailto, tel, intent) skipped.

Markdown / wrapper handling: extract URLs; replace each match with the sanitized URL in-place. A URL inside `[text](url)` is matched by `Patterns.WEB_URL` against the URL substring only — preserves the wrapping syntax.

### Settings UI

Compose, in the existing Clipboard settings section. Three Material 3 `Switch` rows + a conditional "Browse..." button + status text:

```kotlin
@Composable
fun UrlSanitizationSection() {
    SettingsSwitch(
        title = "Sanitize tracking parameters",
        description = "Strip utm_*, fbclid, etc. from URLs you copy. Powered by ClearURLs.",
        checked = sanitizeEnabled,
        onCheckedChange = { ... }
    )
    SettingsSwitch(
        title = "Enrich embeds for sharing",
        description = "Rewrite x.com → fxtwitter.com, reddit.com → rxddit.com, etc.",
        checked = embedEnabled,
        onCheckedChange = { ... }
    )
    SettingsSwitch(
        title = "Use custom rules",
        description = "Apply your own ClearURLs-format JSON.",
        checked = customEnabled,
        onCheckedChange = { ... }
    )
    if (customEnabled) {
        Button(onClick = launchPicker) { Text("Browse...") }
        Text(customStatus)   // "12 rules from custom.substitutions.json" / "Invalid JSON: ..."
    }
    Text("Note: only applies to copies made via CleverKeys. Other apps' copies are unchanged.")
}
```

### File-picker flow

1. User taps **Browse...**
2. SAF `OpenDocument` picker (`mime = application/json`)
3. Result URI → coroutine reads + parses JSON
4. **On success**: copy bytes to `app_private/url_rules/custom.substitutions.json`, store URI in prefs, update status text
5. **On parse failure**: show error toast + status text; do NOT swap the previous valid file. User can retry.

Stored copy in app private dir avoids losing access if user revokes the SAF grant.

### Bundled assets

```
src/main/assets/url_rules/
  clearurls.json         (60KB, ClearURLs upstream `data.minify.json` snapshot)
  embed_enrich.json      (3KB, hand-curated ~10 redirection providers)
```

`clearurls.json` is the ClearURLs project's MIT-licensed canonical ruleset. Snapshot frozen at the version present at implementation time; documented in `memory/todo.md` for periodic refresh tracking.

`embed_enrich.json` schema (full ClearURLs format, only `redirections` populated):

```json
{
  "providers": {
    "twitter_embed_fix": {
      "urlPattern": "^https?://(?:www\\.)?(?:twitter|x)\\.com/.+",
      "redirections": ["^https?://(?:www\\.)?(?:twitter|x)\\.com/(.+)$"],
      "completeProvider": false
    },
    "reddit_embed_fix": { ... },
    "instagram_embed_fix": { ... },
    "tiktok_embed_fix": { ... },
    "bsky_embed_fix": { ... },
    "pixiv_embed_fix": { ... },
    "furaffinity_embed_fix": { ... },
    "deviantart_embed_fix": { ... }
  }
}
```

Initial bundled providers: `x.com → fxtwitter.com`, `reddit.com → rxddit.com`, `instagram.com → ddinstagram.com`, `tiktok.com → vxtiktok.com`, `bsky.app → fxbsky.app`, `pixiv.net → phixiv.net`, `furaffinity.net → fxfuraffinity.net`, `deviantart.com → fxdeviantart.com`. (Subject to user review before freeze.)

### `ClipboardManager` integration

Single hook at the existing `addEntry(content: String, ...)` insert path. Before SQLite write:

```kotlin
suspend fun addEntry(content: String, mimeType: String?, ...) {
    val processed = if (mimeType == null || mimeType == "text/plain") {
        urlSanitizer.process(content)
    } else content    // image/video/file payloads pass through

    db.insert(processed, mimeType, ...)
}
```

Sanitization is no-op when ALL three toggles are off (sanitizer constructed with empty Ruleset, returns input unchanged in O(text-length) regex scan). Cost when fully enabled: O(URLs × providers) — negligible (<1ms typical) since ClearURLs uses host-pattern shortcutting.

### Idempotency

Apply rules to a URL → no params remain on the strip list, no redirection pattern matches → second pass is a no-op. Test contract: `process(process(s)) == process(s)` for arbitrary input strings containing URLs.

### Headless / Termux automation

The new feature is GATED ON `mimeType == text/plain` clipboard inserts AND requires at least one toggle enabled. Termux automation paths that programmatically write to clipboard via `am` Intents do NOT route through CleverKeys' `ClipboardManager.addEntry` (they hit Android's system clipboard directly), so this feature has zero impact on automation. Document explicitly in `memory/todo.md`.

## Error handling

| Failure | Behavior |
|---|---|
| `clearurls.json` asset missing or unparseable | Disable the "Sanitize tracking" toggle in settings (greyed out with "Bundled rules unavailable" status). Crash-loud in `Log.e`. |
| `embed_enrich.json` asset missing or unparseable | Same shape — disable that toggle. |
| User-supplied JSON malformed | Reject on import; status text shows error message; previous valid custom rules retained. Toast: "Invalid rules file: <reason>". |
| User-supplied JSON valid but contains a regex that fails to compile | Skip just that rule entry; surface count of invalid rules in status text ("12 rules loaded, 2 skipped — see logcat"). Don't reject the whole file. |
| URL match throws (e.g. malformed URL) | Catch + return original input unchanged for that URL; log warning. |
| Custom rules disabled mid-flight | Re-resolve the unified ruleset; sanitizer rebuilt on toggle change. No restart needed. |

## Testing

### Pure JVM (`runPureTests`)

`UrlSanitizerTest`:
- `noProviders_returnsInputUnchanged`
- `noUrls_returnsInputUnchanged`
- `singleUrl_paramStripApplies`
- `multipleUrls_eachSanitizedIndependently`
- `nonHttpUrl_skipped` (mailto, tel)
- `markdownLinkSyntax_urlOnlyReplaced`
- `aliexpressUrl_allTrackingStripped` (the user's own example URL — locked-in)
- `idempotent_runTwiceMatchesOnce` (run sanitize on output, verify unchanged)
- `redirectionRule_replacesHostPreservingPath` (twitter → fxtwitter)
- `exceptionRule_bypassesProvider`
- `completeProvider_skipsUrlEntirely`
- `globalRulesAndProviderRules_bothApply`

`RulesetParserTest`:
- Each ClearURLs format field (`rules`, `rawRules`, `redirections`, `exceptions`, `completeProvider`, `urlPattern`) parses correctly
- Malformed JSON throws clear exception
- Invalid regex in any field surfaces with field path
- `merge_appendsRulesPerProvider`
- `merge_overlayCanForceCompleteProvider`
- `merge_overlayWithUnknownProvider_addsAsNew`

`EmbedEnrichRulesTest`:
- Each bundled embed-rewrite produces expected output for canonical inputs
  (`https://x.com/foo/status/123` → `https://fxtwitter.com/foo/status/123`)

### MockK (`runMockTests`)

`ClipboardManagerSanitizationTest`:
- `addEntry_textPlain_runsSanitizer` (verify the hook fires)
- `addEntry_image_doesNotRunSanitizer` (mime gate)
- `toggleAllOff_addEntryStoresInputUnchanged`
- `customRulesEnabled_butFileMissing_fallsBackToBundled`

### Compose UI (`androidTest`, ew-cli)

`UrlSanitizationSettingsComposeTest`:
- Three switches displayed
- Toggle changes write through to prefs
- Browse button visible only when "Use custom rules" enabled
- Status text updates after import

### Integration smoke

Real device test (manual checklist supplied at end of plan): copy each canonical URL via CleverKeys' clipboard panel and verify storage shape.

## Files

| Status | File | Δ lines |
|---|---|---|
| New | `src/main/kotlin/tribixbite/cleverkeys/clipboard/sanitize/Provider.kt` | ~60 |
| New | `src/main/kotlin/tribixbite/cleverkeys/clipboard/sanitize/RulesetParser.kt` | ~180 |
| New | `src/main/kotlin/tribixbite/cleverkeys/clipboard/sanitize/UrlSanitizer.kt` | ~150 |
| New | `src/main/kotlin/tribixbite/cleverkeys/clipboard/sanitize/SanitizationConfig.kt` | ~80 |
| New | `src/main/assets/url_rules/clearurls.json` | 60 KB asset |
| New | `src/main/assets/url_rules/embed_enrich.json` | 3 KB asset |
| Modified | `src/main/kotlin/tribixbite/cleverkeys/ClipboardManager.kt` | ~+30 |
| Modified | `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | ~+12 (3 prefs) |
| Modified | `src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt` (Clipboard section) | ~+90 |
| New | `src/test/kotlin/tribixbite/cleverkeys/clipboard/sanitize/UrlSanitizerTest.kt` | ~200 |
| New | `src/test/kotlin/tribixbite/cleverkeys/clipboard/sanitize/RulesetParserTest.kt` | ~150 |
| New | `src/test/kotlin/tribixbite/cleverkeys/clipboard/sanitize/EmbedEnrichRulesTest.kt` | ~80 |
| New | `src/test/kotlin/tribixbite/cleverkeys/ClipboardManagerSanitizationTest.kt` (MockK) | ~100 |
| New | `src/androidTest/kotlin/tribixbite/cleverkeys/UrlSanitizationSettingsComposeTest.kt` | ~120 |
| Modified | `build.gradle` | +5 (test class registration) |
| Modified | `memory/todo.md` | completion entry |

## Pre-existing concerns flagged but NOT addressed

1. **`ClipboardSettingsActivity` orphan** (700+ lines, never declared in AndroidManifest). Out of scope for this work; flagged in earlier session.
2. **No mechanism to refresh bundled ClearURLs ruleset.** Snapshot frozen at implementation time. Refresh requires manual re-bundling and a release. Document in `memory/todo.md` as known limitation.

## Hard-won lessons to capture in `memory/todo.md`

- ClearURLs format is the right format for any URL-rule modular system — covers param-strip, host rewrite, regex strip, exceptions, complete-disable.
- Per-provider field-level merge (not full replacement, not flat append) is what enables surgical user override of bundled rules.
- Bundle the ClearURLs minified snapshot as an asset (~60 KB); don't try to fetch live (project bans INTERNET).
- Hook clipboard sanitization at CleverKeys' own `ClipboardManager.addEntry`, NOT Android system clipboard (would need permission + lifecycle hooks unavailable to non-default IMEs).
