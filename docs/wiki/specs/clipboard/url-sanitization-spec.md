---
title: URL Sanitization — Technical Specification
description: ClearURLs-format ruleset engine for stripping tracking parameters from clipboard URLs. Provider-aware, regex-based, with optional system-clipboard write-back and custom-rules SAF loader.
user_guide: ../../clipboard/url-sanitization.md
status: implemented
version: v1.5.0
---

# URL Sanitization Technical Specification

## Overview

CleverKeys ships a clipboard URL sanitizer based on the [ClearURLs](https://docs.clearurls.xyz/) ruleset format. When the system clipboard receives text containing HTTP/HTTPS URLs, a regex pass detects each URL, matches it against provider patterns from the bundled `clearurls.json` (207 providers including globalRules) and optional `embed_enrich.json`, and strips/rewrites tracking parameters and embed hosts. Custom user-supplied rules are merged in via SAF when enabled.

The feature operates exclusively on clipboard insert events, not on typed text (the separate [autocorrect non-prose guard](../typing/autocorrect-spec.md) protects typed URLs from *correction*; it performs no sanitization). There is no UI feedback — sanitization is silent. The cleaned text is what lands in CleverKeys' clipboard history; when the `clipboard_sanitize_system_clipboard` toggle is on (default) and a URL was actually changed, the cleaned form is also written back to the Android system clipboard.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| `UrlSanitizer` / `RulesetUrlSanitizer` | `src/main/kotlin/tribixbite/cleverkeys/clipboard/sanitize/UrlSanitizer.kt` | Provider matching + per-URL `process()` + `stripQueryParams()` |
| `systemClipboardRewrite` | `UrlSanitizer.kt:34-35` | Pure gating function for the system-clipboard write-back |
| `SanitizationConfig` | `src/main/kotlin/tribixbite/cleverkeys/clipboard/sanitize/SanitizationConfig.kt` | Toggle resolution, asset loading, rebuild on settings change |
| `RulesetParser` | `src/main/kotlin/tribixbite/cleverkeys/clipboard/sanitize/RulesetParser.kt` | ClearURLs JSON parse + merge (LinkedHashMap preserves provider order) |
| `ClipboardHistoryService` | `src/main/kotlin/tribixbite/cleverkeys/ClipboardHistoryService.kt:298` | Entry point — `addClip(text)` calls `_sanitizationConfig.sanitizer().process(clip)` |
| `clearurls.json` | `src/main/assets/url_rules/clearurls.json` | Bundled ruleset (207 providers including globalRules and a `reddit_embed_host_url` provider) |
| `embed_enrich.json` | `src/main/assets/url_rules/embed_enrich.json` | Bundled embed-rewrite redirections (x.com → fxtwitter.com, reddit → rxddit, instagram → ddinstagram, tiktok → vxtiktok, bsky → fxbsky, pixiv → phixiv, furaffinity/deviantart → fx*) |
| Custom rules | `<filesDir>/url_rules/custom.substitutions.json` | User-supplied ClearURLs-format JSON loaded via SAF (`SanitizationConfig.kt:98-99`) |

## Pipeline

```
System clipboard change
        ↓
ClipboardHistoryService.addClip(text)
        ↓
┌───────────────────────────────────────────────────┐
│  _sanitizationConfig.sanitizer()                  │
│    └─ build() (lazy, cached until rebuild)        │
│         ├─ if sanitize_links_enabled:             │
│         │    load assets/url_rules/clearurls.json │
│         ├─ if embed_enrich_enabled:               │
│         │    load assets/url_rules/embed_enrich.json│
│         ├─ if custom_rules_enabled:               │
│         │    load <filesDir>/url_rules/custom.*   │
│         └─ RulesetParser.merge(rulesets)          │
│            → returns RulesetUrlSanitizer          │
│              (empty ruleset if all toggles off)   │
└───────────────────────────────────────────────────┘
        ↓
RulesetUrlSanitizer.process(text)
        ↓
For each URL match (regex):
  sanitizeOne(url) →
    iterate providers in declaration order →
      if provider.urlPattern matches:
        applyRedirections() →
        applyRawRules() →
        stripQueryParams() →
        return modified url
        ↓
Reassemble text with cleaned URLs
        ↓
systemClipboardRewrite(original, processed, toggle)
  → if toggle on AND processed != original:
      setPrimaryClip(cleaned)  (main thread, best-effort)
        ↓
Sanitized text → ClipboardEntry → DB write
```

## Configuration

All in `Config.kt` (declarations at lines 505-513, refresh at 793-797):

| Setting | Key | Default | Type | Purpose |
|---------|-----|---------|------|---------|
| **Sanitize tracking** | `clipboard_sanitize_links_enabled` | `false` | Bool | Load bundled `clearurls.json` |
| **Enrich embeds** | `clipboard_embed_enrich_enabled` | `false` | Bool | Load bundled `embed_enrich.json` (redirect rewrites) |
| **Custom rules** | `clipboard_custom_rules_enabled` | `false` | Bool | Load user-supplied ruleset from `clipboard_custom_rules_uri` |
| **Custom rules path** | `clipboard_custom_rules_uri` | `null` | String? | SAF URI or local file path |
| **Clean system clipboard** | `clipboard_sanitize_system_clipboard` | `true` | Bool | Write the cleaned URL back to the system clipboard when a rule actually changed it |

The three rule toggles default OFF. When all are off, `SanitizationConfig.build()` returns a `RulesetUrlSanitizer` over an empty ruleset (SanitizationConfig.kt:49-52) without opening any asset files. `clipboard_sanitize_system_clipboard` defaults ON but is inert until a rule toggle enables sanitization.

The rule toggles are independent — there are no per-provider sub-toggles in the UI. To strip only one provider's params, the user must write a custom rules file containing only that provider.

## Algorithm Details

### URL detection (UrlSanitizer.kt:47-49)

Hand-rolled regex (no `android.util.Patterns` dependency, keeps the module JVM-pure for tests):

```kotlin
private val URL_REGEX = Regex(
    "https?://[^\\s<>]+[^\\s<>\".,;:!?)]"
)
```

A double-quote is allowed *inside* the URL — real-world links like AliExpress put literal `"` in params such as `pdp_ext_f={"order":...}`; excluding it truncated the URL and left the rest unsanitized. The trailing character class still excludes `"` and common URL-following punctuation/brackets as the FINAL char, so `(see https://x.com)` drops the closing paren and `"https://x.com"` drops the closing quote (comment at UrlSanitizer.kt:40-46).

### Provider matching (UrlSanitizer.kt:72-106)

For each detected URL, `sanitizeOne()` iterates providers in declaration order (the `LinkedHashMap` from `RulesetParser` preserves the JSON insertion order). A provider applies when:

1. Its `urlPattern` regex matches the URL (case-insensitive).
2. No `exceptions[]` regex matches.
3. The `completeProvider: true` flag is not set (that flag suppresses the provider).

Multiple providers can apply to the same URL — global rules + per-domain rules both fire when the domain provider has no `completeProvider` exclusion. Application is **in declaration order**, so later providers see the URL after earlier providers' modifications. This chaining is load-bearing: the `reddit_embed_host_url` provider matches both `reddit.com` and `rxddit.com`, so it strips `embed_host_url` whether the link is copied raw or after the embed-enrich reddit→rxddit rewrite.

### Three rule layers per provider

```kotlin
// UrlSanitizer.kt:83-102
// Apply redirections first (host swap / chained redirect bypass)
for (redir in provider.redirections) {
    val match = redir.pattern.find(current) ?: continue
    current = if (redir.replacement != null) {
        // Our extension: explicit replacement template
        redir.pattern.replaceFirst(current, redir.replacement)
    } else {
        // Upstream: group(1) is the new URL
        match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: current
    }
}

// Apply rawRules (regex strip from URL string)
for (raw in provider.rawRules) {
    current = raw.replace(current, "")
}

// Apply rules (regex-based query-param strip)
val rulePatterns = compiledRules[provider] ?: emptyList()
current = stripQueryParams(current, rulePatterns)
```

1. **Redirections** (`provider.redirections`) — full-URL regex; either an explicit `replacement` template (CleverKeys extension, used by `embed_enrich.json`) or the upstream group(1)-is-the-new-URL convention. Applied first.
2. **rawRules** (`provider.rawRules`) — full-URL regex substring strips. Removes path segments like `/ref=...`.
3. **rules** (`provider.rules`) — query-parameter KEY patterns. ClearURLs `rules` entries are **regex source strings** (e.g. `(?:%3F)?spm`, `utm(?:_[a-z_]*)?`, `scm[_a-z-]*`), not literal param names. Each is compiled once at construction as an anchored case-insensitive regex matching the full param key (UrlSanitizer.kt:52-62); malformed patterns are dropped per-rule via `mapNotNull` so one bad entry doesn't kill the provider.

### Query-param strip (UrlSanitizer.kt:108-130)

```kotlin
private fun stripQueryParams(url: String, rulePatterns: List<Regex>): String {
    if (rulePatterns.isEmpty()) return url
    val qIdx = url.indexOf('?')
    if (qIdx < 0) return url

    // Split off the fragment (#anchor) — preserve verbatim.
    val fragIdx = url.indexOf('#', startIndex = qIdx)
    val fragment = if (fragIdx >= 0) url.substring(fragIdx) else ""
    val pathAndQuery = if (fragIdx >= 0) url.substring(0, fragIdx) else url

    val base = pathAndQuery.substring(0, qIdx)
    val query = pathAndQuery.substring(qIdx + 1)

    val keptParams = query.split('&').filter { kv ->
        val key = kv.substringBefore('=')
        key.isNotEmpty() && rulePatterns.none { it.matches(key) }
    }

    return when {
        keptParams.isEmpty() -> base + fragment
        else -> base + "?" + keptParams.joinToString("&") + fragment
    }
}
```

Notes:
- Splits by `&`, preserves order of kept params.
- Drops the `?` entirely if no params survive.
- Preserves URL fragment (`#section`) untouched.

### Cache + rebuild (SanitizationConfig.kt:19-36)

```kotlin
@Volatile private var cached: UrlSanitizer? = null

internal fun sanitizer(): UrlSanitizer {
    cached?.let { return it }
    synchronized(this) {
        cached?.let { return it }
        val s = build()
        cached = s
        return s
    }
}

/** Force the next call to sanitizer() to rebuild from current Config + custom file. */
fun rebuild() { cached = null }
```

Mid-session settings changes apply without a keyboard restart: `ClipboardHistoryService` registers a receiver for `SettingsActivity.ACTION_SANITIZATION_RULES_CHANGED` (ClipboardHistoryService.kt:51-66, registered at :74). On receipt it first calls `Config.globalConfig().reloadSanitizationSettings()` (Config.kt:965-971 — re-reads the five sanitization prefs from SharedPreferences) and then `_sanitizationConfig.rebuild()`. The broadcast fires whenever any toggle or the custom-rules URI changes in Settings.

## Entry Point — ClipboardHistoryService

```kotlin
// ClipboardHistoryService.kt:297-311
// URL sanitization (text/plain only). No-op when all three toggles are off.
val processed = _sanitizationConfig.sanitizer().process(clip)

// If sanitization actually cleaned the URL and the user opted in, also overwrite the
// Android system clipboard so pastes from ANY app deliver the sanitized URL (not just
// CleverKeys' own panel). Idempotent: the re-fired listener re-sanitizes an already-clean
// value → no further change → no loop. See [systemClipboardRewrite].
systemClipboardRewrite(
    original = clip,
    processed = processed,
    enabled = Config.globalConfig().clipboard_sanitize_system_clipboard,
)?.let { rewriteSystemClipboard(it) }

// Add to database (handles duplicate detection automatically)
val added = _database.addClipboardEntry(processed, expiryTime)
```

The sanitizer runs AFTER the size-limit gate but BEFORE the database write, so the stored text is always the cleaned version. There's no separate "raw clipboard" preserved alongside.

## System Clipboard Write-Back

The gating logic is a pure function so it's unit-testable:

```kotlin
// UrlSanitizer.kt:34-35
internal fun systemClipboardRewrite(original: String, processed: String, enabled: Boolean): String? =
    if (enabled && processed != original) processed else null
```

Two guards: the `clipboard_sanitize_system_clipboard` toggle, and `processed != original` (nothing stripped → don't churn the clipboard). The second guard also terminates the re-entrant listener loop: `setPrimaryClip` re-fires the clipboard-changed listener, but re-sanitizing an already-clean value is idempotent, so the second pass returns `null` and stops.

The actual write (`rewriteSystemClipboard`, ClipboardHistoryService.kt:349-362) posts to the main thread (`setPrimaryClip` requires a Looper thread; `addClip` may run on `Dispatchers.IO` for content-URI text) and is best-effort — Android 10+ throws `SecurityException` when the IME isn't focused, which is swallowed like the read path. The original clip's label/metadata is not preserved; the rewritten clip uses a neutral `"CleverKeys"` label.

## Custom Rules Loading

UI: Settings → Clipboard → URL handling → Use custom rules → **Browse…** (only visible when toggle enabled). Triggers Android's SAF file picker. Selected file is copied into the app's private storage at `<app data>/url_rules/custom.substitutions.json` and the path stored in `clipboard_custom_rules_uri`.

Format must match the ClearURLs spec: top-level `{ "providers": { name: { urlPattern, rules, rawRules, redirections, exceptions, completeProvider } } }`. Anything else is rejected by `RulesetParser`.

Custom rules MERGE with bundled rules — they don't replace them. To use ONLY custom rules, disable the master `sanitize_links_enabled` toggle and rely on custom alone.

## Test Coverage

| Suite | File | Cases |
|-------|------|-------|
| Pure JVM | `src/test/kotlin/.../sanitize/UrlSanitizerTest.kt` | 18 (empty rulesets, no URLs, global rules, AliExpress domain rules incl. embedded-quote URLs, reddit/rxddit `embed_host_url`, multi-URL, non-HTTP schemes, regex rules, malformed patterns, idempotency, system-clipboard rewrite gating) |
| Pure JVM | `src/test/kotlin/.../sanitize/RulesetParserTest.kt` | 14 (parse + per-provider merge) |
| Pure JVM | `src/test/kotlin/.../sanitize/EmbedEnrichRulesTest.kt` | Bundled embed ruleset behavior |
| Mock | `src/test/kotlin/.../sanitize/SanitizationConfigBuildTest.kt` | 2 (toggleAllOff + customRulesEnabled_fileMissing) |
| Compose UI | `src/androidTest/kotlin/.../UrlSanitizationSettingsComposeTest.kt` | Subsection visible, switches render, Browse button gated on custom toggle |

## Known Limitations

- **No short-URL expansion.** Sanitizer doesn't fetch HTTP — it only rewrites locally. Short URLs (`bit.ly`, `t.co`) reach the destination as-is unless the ClearURLs ruleset has an explicit redirect-rewrite rule for them.
- **No non-HTTP scheme support.** Regex requires `https?://` — `mailto:`, `tel:`, `intent:` and other schemes are passed through untouched.
- **Coverage gap on small/new domains.** 207 providers ship in `clearurls.json` (updated with app releases). Out-of-list domains fall through to `globalRules` only (`utm_*`, `fbclid`, `gclid`, `spm` family). Custom rules let users patch in coverage.
- **No per-provider UI toggles.** All-or-nothing per master toggle. Fine-grained control requires custom rules.
- **System-clipboard write-back is best-effort.** Android 10+ only allows `setPrimaryClip` while the IME is focused; when the write is denied, CleverKeys' own history still stores the cleaned text but the system clipboard keeps the original.

## Related Specifications

- [Clipboard History](./clipboard-history-spec.md) — Database schema, entry types, deduplication
- [Privacy](./privacy-spec.md) — Password-manager exclusions, sensitive-flag handling (the privacy gates run BEFORE sanitization in `addClip`)
