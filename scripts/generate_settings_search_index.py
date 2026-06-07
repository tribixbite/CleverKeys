#!/usr/bin/env python3
"""Generate the in-app settings search index from the SettingsActivity controls.

CleverKeys' settings search used to rely on a hand-maintained `searchableSettings`
list kept in parallel with the actual UI controls. Settings silently drifted out of
search whenever someone added a `SettingsSwitch`/`SettingsSlider`/`SettingsDropdown`
and forgot the matching entry. This script removes that hand-maintenance: it scans
`SettingsActivity.kt` for every control, takes the control's FULL title as the search
title, derives keywords from the title (plus a small synonym map), figures out which
collapsible section the control lives in, and emits a generated Kotlin file that the
activity consumes directly.

Inputs:
  - src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt  (control definitions)
  - res/values/strings.xml                                     (resolves stringResource titles)

Output (argv[1], default build/generated/search/kotlin/.../SettingsSearchIndex.kt):
  internal data class GeneratedSearchEntry(title, keywords, sectionKey)
  internal val GENERATED_SEARCH_ENTRIES: List<GeneratedSearchEntry>

The activity maps each `sectionKey` to a display name + expand action (a small, stable
table — sections change far less often than individual settings) and registers a scroll
position for every control by a slug of its title, so search results scroll precisely.
"""
import os
import re
import sys

ACTIVITY = "src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt"
STRINGS = "res/values/strings.xml"
DEFAULT_OUT = "build/generated/search/kotlin/tribixbite/cleverkeys/SettingsSearchIndex.kt"

# Filler words that carry no search intent (mirrors SettingsSearchCoverageTest).
STOPWORDS = {
    "enable", "enabled", "disable", "disabled", "show", "hide", "use", "using",
    "for", "the", "and", "or", "with", "off", "your", "you", "mode",
    "settings", "setting", "this", "when", "auto", "only",
}

# Cross-vocabulary synonyms: when a title contains the key word, add these so users who
# type a different-but-equivalent term still find the setting. Keep this small and curated.
SYNONYMS = {
    "vibration": ["haptic", "vibrate", "tactile"],
    "haptic": ["vibration", "vibrate"],
    "opacity": ["transparent", "alpha"],
    "autocapitalization": ["capital", "uppercase", "shift"],
    "autocorrect": ["correction", "spelling", "typo"],
    "correction": ["autocorrect", "spelling"],
    "numpad": ["numbers", "keypad"],
    "gif": ["sticker", "meme", "reaction"],
    "clipboard": ["copy", "paste"],
    "sanitize": ["clean", "tracking", "utm", "clearurls"],
    "embed": ["embeds", "share", "fxtwitter", "rxddit"],
    "trail": ["swipe", "gesture"],
    "beam": ["neural", "prediction"],
    "incognito": ["private", "secret"],
}

CONTROL_RE = re.compile(r"\b(SettingsSwitch|SettingsSlider|SettingsDropdown)\s*\(")
TITLE_RE = re.compile(
    r'title\s*=\s*(?:"([^"]*)"|stringResource\(\s*R\.string\.(\w+)\s*\))'
)
SECTION_RE = re.compile(r"CollapsibleSettingsSection\s*\(")
EXPANDED_RE = re.compile(r"expanded\s*=\s*(\w+)")
STRING_RE = re.compile(r'<string name="([^"]+)">(.*?)</string>', re.DOTALL)


def load_strings(path):
    if not os.path.exists(path):
        return {}
    text = open(path, encoding="utf-8").read()
    out = {}
    for name, val in STRING_RE.findall(text):
        # Unescape the handful of XML/Android escapes that appear in titles.
        v = (val.replace("\\'", "'").replace('\\"', '"')
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .strip())
        out[name] = v
    return out


def slugify(title):
    return re.sub(r"[^a-z0-9]+", "_", title.lower()).strip("_")


def section_key(var):
    """clipboardSectionExpanded -> clipboard ; testKeyboardExpanded -> testKeyboard."""
    return re.sub(r"(Section)?Expanded$", "", var)


def keywords_for(title):
    words = [
        w for w in re.split(r"[^a-z0-9]+", title.lower())
        if len(w) >= 3 and w not in STOPWORDS and not w.isdigit()
    ]
    kws = list(dict.fromkeys(words))
    for w in list(kws):
        for syn in SYNONYMS.get(w, []):
            if syn not in kws:
                kws.append(syn)
    return kws


def find_sections(text):
    """Return sorted list of (char_index, sectionVar) for each CollapsibleSettingsSection."""
    sections = []
    for m in SECTION_RE.finditer(text):
        window = text[m.start():m.start() + 400]
        em = EXPANDED_RE.search(window)
        if em:
            sections.append((m.start(), em.group(1)))
    sections.sort()
    return sections


def section_for(pos, sections):
    cur = None
    for start, var in sections:
        if start <= pos:
            cur = var
        else:
            break
    return cur


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_OUT
    text = open(ACTIVITY, encoding="utf-8").read()
    strings = load_strings(STRINGS)
    sections = find_sections(text)

    entries = []  # (title, keywords, sectionKey)
    seen = set()
    for m in CONTROL_RE.finditer(text):
        window = text[m.start():m.start() + 500]
        tm = TITLE_RE.search(window)
        if not tm:
            continue
        if tm.group(1) is not None:
            title = tm.group(1)
        else:
            title = strings.get(tm.group(2))
        if not title:
            continue
        var = section_for(m.start(), sections)
        key = section_key(var) if var else "advanced"
        dedup = (title, key)
        if dedup in seen:
            continue
        seen.add(dedup)
        entries.append((title, keywords_for(title), key))

    if not entries:
        sys.exit("generate_settings_search_index: no controls found — parser broken")

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    lines = [
        "// GENERATED FILE — do not edit by hand.",
        "// Source: scripts/generate_settings_search_index.py (run via the",
        "// generateSettingsSearchIndex Gradle task before compilation).",
        "package tribixbite.cleverkeys",
        "",
        "/** One settings-search entry auto-derived from a SettingsSwitch/Slider/Dropdown control. */",
        "internal data class GeneratedSearchEntry(",
        "    val title: String,",
        "    val keywords: List<String>,",
        "    val sectionKey: String,",
        ")",
        "",
        "internal val GENERATED_SEARCH_ENTRIES: List<GeneratedSearchEntry> = listOf(",
    ]
    for title, kws, key in entries:
        kw_lit = ", ".join('"' + k.replace('"', '\\"') + '"' for k in kws)
        t_lit = title.replace("\\", "\\\\").replace('"', '\\"')
        lines.append(f'    GeneratedSearchEntry("{t_lit}", listOf({kw_lit}), "{key}"),')
    lines.append(")")
    lines.append("")

    open(out_path, "w", encoding="utf-8").write("\n".join(lines))
    print(f"generate_settings_search_index: wrote {len(entries)} entries -> {out_path}")


if __name__ == "__main__":
    main()
