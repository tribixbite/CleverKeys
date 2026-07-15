---
title: URL Sanitization
description: Strip tracking parameters from URLs you paste, using the ClearURLs ruleset (utm_*, fbclid, gclid, aff_*, and ~200 provider-specific patterns)
category: Clipboard
difficulty: intermediate
related_spec: ../specs/clipboard/url-sanitization-spec.md
---

# URL Sanitization

CleverKeys can automatically strip tracking and analytics parameters from URLs **as they enter your clipboard history**. Copy a link with `utm_source=...&fbclid=...&aff_short=...` attached, and the version saved to your clipboard arrives clean: just the URL you actually want to share. The feature uses the [ClearURLs](https://docs.clearurls.xyz/) ruleset and supports your own custom rules.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Remove `utm_*`, `fbclid`, `gclid`, `aff_*`, and other tracking params from copied URLs |
| **Trigger** | Runs when text enters the clipboard history (system copy event) |
| **Scope** | Clipboard only — typed text is never sanitized |
| **Feedback** | Silent — sanitized text replaces the original with no toast |
| **Default** | Off — all three rule toggles default to disabled ("Also clean system clipboard" defaults on, but does nothing until a rule toggle is enabled) |
| **Access** | Settings → Clipboard → URL handling |

## How It Works

When you copy text containing URLs:

1. The system clipboard change fires `ClipboardHistoryService.addClip()`.
2. The full clipboard text is run through a URL detector (regex over the whole text — handles multi-URL and surrounding prose).
3. Each detected URL is matched against the active ruleset, which has ~200 providers like Amazon, AliExpress, Google, Facebook, Twitter, Reddit, plus a `globalRules` provider that handles the universal `utm_*` family.
4. Matching parameters are dropped; non-matching parameters are preserved. URLs containing literal quotes (AliExpress embeds JSON like `pdp_ext_f={"order":...}` in params) are captured whole, so the junk after the quote gets stripped too.
5. The sanitized text is what gets saved to clipboard history (and pasted from CleverKeys' clipboard panel).
6. If **Also clean system clipboard** is on (the default) and something was actually stripped, the cleaned URL is written back to the Android system clipboard too — so a plain long-press → Paste in any app also gets the clean link.

Settings changes apply immediately: toggling any of these mid-session rebuilds the sanitizer without restarting the keyboard.

## The Toggles

All in Settings → Clipboard → **URL handling**:

| Toggle | Setting key | What it does |
|--------|-------------|--------------|
| **Sanitize tracking parameters** | `clipboard_sanitize_links_enabled` | Master switch for the bundled ClearURLs ruleset. Strips `utm_*`, `fbclid`, `gclid`, per-provider trackers like AliExpress `spm`/`scm*`/`aff_*`/`pdp_*`, Amazon `ref`/`tag`, Reddit share trackers, etc. |
| **Enrich embeds for sharing** | `clipboard_embed_enrich_enabled` | Separate ruleset that rewrites social-media hosts for richer link previews (`x.com` → `fxtwitter.com`, `reddit.com` → `rxddit.com`, etc.). |
| **Use custom rules** | `clipboard_custom_rules_enabled` | Load your own ClearURLs-format JSON. When enabled, a **Browse…** button appears for picking the file via SAF. |
| **Also clean system clipboard** | `clipboard_sanitize_system_clipboard` | When a URL was actually cleaned, write the cleaned form back to the Android system clipboard so pasting in *any* app gets the clean link. On by default; inert until one of the rule toggles above is enabled. |

> [!NOTE]
> The three rule toggles are **independent**. Enabling "Enrich embeds" alone does NOT strip tracking parameters — you also need "Sanitize tracking parameters" on for the bundled ClearURLs rules to apply. If you previously enabled sanitization and saw no effect, double-check the master toggle is on.

## Worked Examples

### Standard tracking strip

Copy:

```
Check this out: https://example.com/article?utm_source=newsletter&utm_medium=email&id=42
```

After sanitization (master toggle on):

```
Check this out: https://example.com/article?id=42
```

`utm_source` and `utm_medium` matched the global `utm_*` rule; `id` did not match any rule and was preserved.

### Provider-specific (AliExpress)

Copy:

```
https://www.aliexpress.com/item/12345.html?spm=abc&scm_id=xyz&algo_pvid=123&utm_source=share&keep=value
```

After sanitization:

```
https://www.aliexpress.com/item/12345.html?keep=value
```

AliExpress's `spm`, `scm*`, and `algo_pvid` matched the provider's ruleset; `utm_source` matched the global rule; `keep` matched nothing.

### Embed enrichment

With **Enrich embeds for sharing** on, a Twitter link becomes a [fxtwitter](https://github.com/FixTweet/FxTwitter) link for better Discord/Slack/Signal previews:

```
https://x.com/user/status/12345  →  https://fxtwitter.com/user/status/12345
```

This is a redirect rewrite, not a parameter strip. Rewrites ship for Twitter/X, Reddit, Instagram, TikTok, Bluesky, Pixiv, FurAffinity, and DeviantArt. A companion rule in the *tracking* ruleset strips the `embed_host_url` parameter from reddit.com and rxddit.com links (it leaks the embedding site) — so with both toggles on, a copied Reddit embed link comes out as a clean rxddit link.

## Custom Rules

Toggle **Use custom rules** to load your own ClearURLs-format JSON. The format is documented at [docs.clearurls.xyz](https://docs.clearurls.xyz/1.27.3/specs/rules/) — a `providers` object keyed by name, each provider has `urlPattern`, `rules` (regex per-param to strip), `rawRules` (regex per-URL substring to strip), `redirections`, and optional `exceptions`.

After enabling the toggle, tap **Browse…** to pick the file via the Android file picker. It's copied into the app's private storage at `<app data>/url_rules/custom.substitutions.json`. The custom rules are MERGED with the bundled ClearURLs rules (if the master toggle is also on), with custom providers added alongside bundled ones.

> [!TIP]
> Common reason to add custom rules: a small domain you use frequently isn't covered by the bundled ~100 providers. Write a minimal `urlPattern` + `rules` block for it. The bundled set updates with app releases; custom rules let you patch in coverage between releases.

## What This Does NOT Do

- **Doesn't follow short URLs.** `bit.ly`, `t.co`, etc. are not expanded — the rules can rewrite some short URLs to their long forms via redirect rules, but there's no live HTTP resolution.
- **Doesn't run on typed text.** If you type a URL with tracking params directly into a text field, it's not touched. Only clipboard inserts (system copy events) trigger sanitization. (Autocorrect separately *avoids* URLs you type — see [Autocorrect](../typing/autocorrect.md) — but that's a typing guard, not sanitization.)
- **Doesn't notify you.** No toast, no badge, no log entry. Sanitization is silent. To verify it's working, copy a known tracking URL and inspect the clipboard history entry (Settings → Clipboard → History).
- **Doesn't have per-provider granularity in the UI.** You can't toggle "strip Google only" — it's all-or-nothing per ruleset. For fine-grained control, write a custom rules file with just the providers you want.

## Troubleshooting

### "I enabled the sanitizer but my AliExpress URL still has all the tracking junk"

Three things to check:

1. **Is the master toggle on?** Settings → Clipboard → URL handling → **Sanitize tracking parameters** must be checked. Enabling only "Enrich embeds" or only "Use custom rules" doesn't enable the bundled rules.
2. **Are the specific params in the bundled ruleset?** AliExpress bundled rules cover `spm` (via the global rules), `scm*`, `algo_*`, `gps-id`, the whole `aff_*` family (`aff_short`, `aff_platform`, `aff_request_id`, ...), `pdp_*` (including `pdp_npi`), `utparam-url`, `ws_ab_test`, `btsid`, `mall_affr`, `terminal_id`, `gatewayAdapt`, `srcSns`, and more. A param that still survives probably isn't in the bundled set — add a custom rules file for it.
3. **Did you copy the link, or paste a previously-saved one?** Sanitization runs on the copy event. URLs already saved in clipboard history before you enabled the feature aren't reprocessed.

### "The sanitizer broke a URL I need"

If a domain's `urlPattern` matches your URL but a rule incorrectly strips something useful, you have three options:

1. Disable the master toggle when copying that particular URL (suboptimal).
2. Write a custom rules file with an `exceptions` regex for that URL pattern.
3. Report the bundled ClearURLs rule upstream at [docs.clearurls.xyz](https://docs.clearurls.xyz/).

## Configuration Reference

| Setting | Key | Default | Range |
|---------|-----|---------|-------|
| **Sanitize tracking params** | `clipboard_sanitize_links_enabled` | `false` | bool |
| **Enrich embeds** | `clipboard_embed_enrich_enabled` | `false` | bool |
| **Use custom rules** | `clipboard_custom_rules_enabled` | `false` | bool |
| **Custom rules file** | `clipboard_custom_rules_uri` | (none) | SAF URI / local path |
| **Also clean system clipboard** | `clipboard_sanitize_system_clipboard` | `true` | bool |

## Related Features

- [Clipboard History](clipboard-history.md) — Where sanitized URLs land
- [Clipboard Privacy](../settings/privacy.md) — Password-manager exclusions, sensitive-flag handling
- [Text Selection](text-selection.md) — Swipe-to-select gestures

## Technical Details

See [URL Sanitization Technical Specification](../specs/clipboard/url-sanitization-spec.md) for the algorithm, ruleset format, and code references.
