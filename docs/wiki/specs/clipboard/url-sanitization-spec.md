---
title: URL Sanitization — Technical Specification
description: ClearURLs-format ruleset engine for stripping tracking parameters from clipboard URLs. Provider-aware, regex-based, monolithic toggles with custom-rules SAF loader.
user_guide: ../../clipboard/url-sanitization.md
status: implemented
version: v1.4.0
---

# URL Sanitization Technical Specification

## Overview

CleverKeys ships a clipboard URL sanitizer based on the [ClearURLs](https://docs.clearurls.xyz/) ruleset format. When the system clipboard receives text containing HTTP/HTTPS URLs, a regex pass detects each URL, matches it against provider patterns from the bundled `clearurls.json` (~100 providers + globalRules) and optional `embed_enrich.json`, and strips/rewrites tracking parameters and embed hosts. Custom user-supplied rules are merged in via SAF when enabled.

The feature operates exclusively on clipboard insert events, not on typed text. There is no UI feedback — sanitization is silent and the cleaned text is what reaches the destination app.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| `UrlSanitizer` | `src/main/kotlin/tribixbite/cleverkeys/clipboard/sanitize/UrlSanitizer.kt` | Provider matching + per-URL `process()` + `stripQueryParams()` |
| `SanitizationConfig` | `src/main/kotlin/tribixbite/cleverkeys/clipboard/sanitize/SanitizationConfig.kt` | Toggle resolution, asset loading, rebuild on settings change |
| `RulesetParser` | `src/main/kotlin/tribixbite/cleverkeys/clipboard/sanitize/RulesetParser.kt` | ClearURLs JSON parse + merge (LinkedHashMap preserves provider order) |
| `ClipboardHistoryService` | `src/main/kotlin/tribixbite/cleverkeys/ClipboardHistoryService.kt:288` | Entry point — `addClip(text)` calls `_sanitizationConfig.sanitizer().process(text)` |
| `clearurls.json` | `src/main/assets/url_rules/clearurls.json` | Bundled ruleset (~3184 lines, ~100 providers including globalRules) |
| `embed_enrich.json` | `src/main/assets/url_rules/embed_enrich.json` | Bundled embed-rewrite redirections (x.com → fxtwitter.com, etc.) |
| Custom rules | `<filesDir>/url_rules/custom.substitutions.json` | User-supplied ClearURLs-format JSON loaded via SAF |

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
│              (or NoOpSanitizer if all toggles off)│
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
Sanitized text → ClipboardEntry → DB write
```

## Configuration

All in `Config.kt` (lines 464 / 743 for the "Chunk 3" block):

| Setting | Key | Default | Type | Purpose |
|---------|-----|---------|------|---------|
| **Sanitize tracking** | `clipboard_sanitize_links_enabled` | `false` | Bool | Load bundled `clearurls.json` |
| **Enrich embeds** | `clipboard_embed_enrich_enabled` | `false` | Bool | Load bundled `embed_enrich.json` (redirect rewrites) |
| **Custom rules** | `clipboard_custom_rules_enabled` | `false` | Bool | Load user-supplied ruleset from `clipboard_custom_rules_uri` |
| **Custom rules path** | `clipboard_custom_rules_uri` | `null` | String? | SAF URI or local file path |

All three toggles default OFF. When all are off, `SanitizationConfig.build()` returns `NoOpSanitizer` (SanitizationConfig.kt:49-51) without opening any asset files.

The three toggles are independent — there are no per-provider sub-toggles in the UI. To strip only one provider's params, the user must write a custom rules file containing only that provider.

## Algorithm Details

### URL detection (UrlSanitizer.kt:23-25)

Hand-rolled regex (no `android.util.Patterns` dependency, keeps the module JVM-pure for tests):

```kotlin
private val URL_REGEX = Regex(
    """https?://[^\s<>"]+[^\s<>".,;:!?)]"""
)
```

The trailing character class excludes common URL-following punctuation so "see https://foo.com." doesn't capture the period as part of the URL.

### Provider matching (UrlSanitizer.kt:48-82)

For each detected URL, `sanitizeOne()` iterates providers in declaration order (the `LinkedHashMap` from `RulesetParser` preserves the JSON insertion order). A provider applies when:

1. Its `urlPattern` regex matches the URL (case-insensitive).
2. No `exceptions[]` regex matches.
3. The `completeProvider: true` flag is not set (that flag suppresses the provider).

Multiple providers can apply to the same URL — global rules + per-domain rules both fire when the domain provider has no `completeProvider` exclusion. Application is **in declaration order**, so later providers see the URL after earlier providers' modifications.

### Three rule layers per provider

```kotlin
// UrlSanitizer.kt:71-78
for (raw in provider.rawRules) {
    url = raw.regex.replace(url, "")
}
url = stripQueryParams(url, provider.rules)
```

1. **Redirections** (`provider.redirections`) — full-URL regex with `$1`, `$2` capture-group interpolation. Applied first. Used for short-URL unfurling and embed rewriting (`x.com` → `fxtwitter.com`).
2. **rawRules** (`provider.rawRules`) — full-URL regex substring strips. Removes path segments like `/ref=...`.
3. **rules** (`provider.rules`) — query-parameter NAME regex patterns. Each query param's key is tested against EVERY compiled rule regex; if any match, the param is dropped.

### Query-param strip (UrlSanitizer.kt:84-106)

```kotlin
private fun stripQueryParams(url: String, rules: List<Regex>): String {
    if (rules.isEmpty()) return url
    val qIndex = url.indexOf('?')
    if (qIndex < 0) return url
    val fragmentIndex = url.indexOf('#', qIndex)
    val queryEnd = if (fragmentIndex < 0) url.length else fragmentIndex
    val query = url.substring(qIndex + 1, queryEnd)
    val fragment = if (fragmentIndex < 0) "" else url.substring(fragmentIndex)
    val kept = query.split('&').filter { kv ->
        val name = kv.substringBefore('=')
        rules.none { it.matches(name) }
    }
    return buildString {
        append(url, 0, qIndex)
        if (kept.isNotEmpty()) {
            append('?')
            append(kept.joinToString("&"))
        }
        append(fragment)
    }
}
```

Notes:
- Splits by `&`, preserves order of kept params.
- Drops the `?` entirely if no params survive.
- Preserves URL fragment (`#section`) untouched.
- Rules are compiled to `Regex` ONCE at construction time (UrlSanitizer.kt:33-38) via `mapNotNull` — malformed patterns are silently dropped, one bad regex doesn't break the provider.

### Cache + rebuild (SanitizationConfig.kt:25-32)

```kotlin
@Volatile private var _sanitizer: UrlSanitizer? = null

fun sanitizer(): UrlSanitizer {
    val cached = _sanitizer
    if (cached != null) return cached
    return build().also { _sanitizer = it }
}

fun rebuild() {
    _sanitizer = null  // Drop cache; next sanitizer() call rebuilds.
}
```

Triggered by `ClipboardHistoryService` listening to the broadcast `SettingsActivity.ACTION_SANITIZATION_RULES_CHANGED` (ClipboardHistoryService.kt:50-56). The broadcast fires when any of the three toggles or the custom-rules URI changes in Settings, so the service refreshes its cached sanitizer immediately rather than the next process restart.

## Entry Point — ClipboardHistoryService

```kotlin
// ClipboardHistoryService.kt:256-288
fun addClip(rawText: String) {
    if (!enabled) return
    val capped = rawText.take(MAX_CLIP_LEN)
    // ...size + privacy gates...
    val processed = _sanitizationConfig.sanitizer().process(capped)
    val entry = ClipboardEntry(text = processed, ...)
    database.insertEntry(entry)
}
```

The sanitizer runs AFTER size capping but BEFORE the database write, so the stored text is always the cleaned version. There's no separate "raw clipboard" preserved alongside.

## Custom Rules Loading

UI: Settings → Clipboard → URL handling → Use custom rules → **Browse…** (only visible when toggle enabled). Triggers Android's SAF file picker. Selected file is copied into the app's private storage at `<app data>/url_rules/custom.substitutions.json` and the path stored in `clipboard_custom_rules_uri`.

Format must match the ClearURLs spec: top-level `{ "providers": { name: { urlPattern, rules, rawRules, redirections, exceptions, completeProvider } } }`. Anything else is rejected by `RulesetParser`.

Custom rules MERGE with bundled rules — they don't replace them. To use ONLY custom rules, disable the master `sanitize_links_enabled` toggle and rely on custom alone.

## Test Coverage

| Suite | File | Cases |
|-------|------|-------|
| Pure JVM | `src/test/kotlin/.../sanitize/UrlSanitizerTest.kt` | 9 (empty rulesets, no URLs, global rules, AliExpress domain rules, multi-URL, non-HTTP schemes, regex rules, malformed patterns, idempotency) |
| Mock | `src/test/kotlin/.../sanitize/SanitizationConfigBuildTest.kt` | 2 (toggleAllOff + customRulesEnabled_fileMissing) |
| Compose UI | `src/androidTest/kotlin/.../UrlSanitizationSettingsComposeTest.kt` | 3+ (subsection visible, 3 switches render, Browse button gated on custom toggle) |

## Known Limitations

- **No short-URL expansion.** Sanitizer doesn't fetch HTTP — it only rewrites locally. Short URLs (`bit.ly`, `t.co`) reach the destination as-is unless the ClearURLs ruleset has an explicit redirect-rewrite rule for them.
- **No HTTP scheme support.** Regex requires `https?://` — `mailto:`, `tel:`, `intent:` and other schemes are passed through untouched.
- **Coverage gap on small/new domains.** ~100 providers ship in `clearurls.json` (updated with app releases). Out-of-list domains fall through to `globalRules` only (`utm_*`, `fbclid`, `gclid` family). Custom rules let users patch in coverage.
- **No per-provider UI toggles.** All-or-nothing per master toggle. Fine-grained control requires custom rules.
- **AliExpress incomplete coverage.** Bundled rules cover `spm`, `scm*`, `algo_pvid`, `gps-id`, `aff_request_id`, `ws_ab_test`, `btsid`, `mall_affr`, `terminal_id`, `cv`, `af`, `dp`, `sk`. AliExpress shares many other params (`aff_short`, `pdp_npi`, `utparam-url`, `aff_platform`) that the bundled list doesn't include yet. Track upstream ClearURLs updates or add a custom rules file.

## Related Specifications

- [Clipboard History](./clipboard-history-spec.md) — Database schema, entry types, deduplication
- [Privacy](./privacy-spec.md) — Password-manager exclusions, sensitive-flag handling (the privacy gates run BEFORE sanitization in `addClip`)
