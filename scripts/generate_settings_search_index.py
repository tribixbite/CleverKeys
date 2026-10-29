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
  - src/main/kotlin/tribixbite/cleverkeys/activities/SettingsActivity.kt  (control definitions)
  - res/values/strings.xml                                     (resolves stringResource titles)

Output (argv[1], default build/generated/search/kotlin/.../SettingsSearchIndex.kt):
  internal data class GeneratedSearchEntry(title, keywords, sectionKey)
  internal val GENERATED_SEARCH_ENTRIES: List<GeneratedSearchEntry>

The activity maps each `sectionKey` to a display name + expand action (a small, stable
table — sections change far less often than individual settings).

Localization (2026-09-30): every entry carries its title's string resource (`titleRes`) and a
locale-independent `settingId` — the resource NAME (e.g. `swipe_enable_title`). The UI shows
and matches the title in the UI language (plus the English `title` here as a secondary match),
and each control registers its scroll position under the ids whose localized title equals its
visible title. The old key, a slug of the visible title, was ASCII-only: under fa/ja/ru/…
controls registered under slugs no entry used, so results opened the section without scrolling.
A control whose title is a literal (not a resource) falls back to the slug of that literal.
"""
import os
import re
import sys

ACTIVITY = "src/main/kotlin/tribixbite/cleverkeys/activities/SettingsActivity.kt"
SECTIONS_DIR = "src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections"
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
    "voice": ["speech", "dictation", "dictate", "microphone", "mic"],
    "clipboard": ["copy", "paste"],
    "sanitize": ["clean", "tracking", "utm", "clearurls"],
    "embed": ["embeds", "share", "fxtwitter", "rxddit"],
    "trail": ["swipe", "gesture"],
    "beam": ["swipe", "prediction"],
    "incognito": ["private", "secret"],
}

# Section files whose controls render inside ANOTHER file's CollapsibleSettingsSection
# (they contain no section marker themselves, so position-based inference can't see it).
# Without an entry here such files fall back to "advanced".
FILE_DEFAULT_SECTION = {
    # LearningDataManagerBlock renders inside InputBehaviorSection's input section
    "LearningDataSection.kt": "input",
}

CONTROL_RE = re.compile(r"\b(SettingsSwitch|SettingsSlider|SettingsDropdown)\s*\(")
TITLE_RE = re.compile(
    r'title\s*=\s*(?:"([^"]*)"|stringResource\(\s*R\.string\.(\w+)\s*\))'
)
SECTION_RE = re.compile(r"CollapsibleSettingsSection\s*\(")
EXPANDED_RE = re.compile(r"expanded\s*=\s*(\w+)")
# `[^>]*` after the name so a string carrying ANY further attribute is still seen. The
# original pattern required name= to be the last attribute, so
# `<string name="x" tools:ignore="MissingTranslation">` was silently skipped — the title
# then looked "missing" to SettingsSearchCoverageTest even though the control existed and
# the string was defined. Silent invisibility, not an error, which is why it is worth the
# wider pattern. `translatable="false"` strings hit the same trap.
STRING_RE = re.compile(r'<string name="([^"]+)"[^>]*>(.*?)</string>', re.DOTALL)


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


IF_RE = re.compile(r"\bif\s*\(")
# Identifiers (dotted paths such as `tuning.ranking` included) in an `if` condition.
IDENT_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*")
# Condition tokens that never name the state a control sets.
NON_STATE_IDENTS = {"true", "false", "null", "it", "is", "in", "isNotEmpty", "isEmpty"}


def matching_close(text, open_idx, open_ch, close_ch):
    """Index just past the bracket that closes the one at [open_idx] (string contents ignored:
    the settings sections keep no unbalanced brackets inside literals)."""
    depth = 0
    for i in range(open_idx, len(text)):
        c = text[i]
        if c == open_ch:
            depth += 1
        elif c == close_ch:
            depth -= 1
            if depth == 0:
                return i + 1
    return len(text)


def if_blocks(text):
    """(body_start, body_end, condition) for every `if (cond) { ... }` in [text]."""
    blocks = []
    for m in IF_RE.finditer(text):
        paren = m.end() - 1
        cond_end = matching_close(text, paren, "(", ")")
        j = cond_end
        while j < len(text) and text[j] in " \t\r\n":
            j += 1
        if j >= len(text) or text[j] != "{":
            continue
        blocks.append((j, matching_close(text, j, "{", "}"), text[paren + 1:cond_end - 1]))
    return blocks


def gate_ids_for(text, ctrl_start, ctrl_sid, blocks, controls):
    """Setting ids of the controls that reveal the control at [ctrl_start] of [text], innermost
    first.

    A control inside `if (multiLangEnabled) { ... }` is not composed while that state is false,
    so its search result has nothing to scroll to (device finding 2026-10-22: "Primary Language"
    with Multi-Language off scrolled nowhere). For each enclosing block, every state identifier
    in the condition is matched to the nearest control BEFORE the block whose own call text
    mentions it: the switch whose `checked = multiLangEnabled`, the dropdown whose
    `selectedIndex` reads `clipboardLimitType`. The app scrolls to the first of these that is on
    screen when the target itself is not.

    # TODO: gates are resolved within one source file. A condition naming state whose control
    # lives in another section file (`swipeTypingEnabled` around "Backspace Undo Swipe" in
    # InputBehaviorSection) gets no gate for that part, so with only that state off the result
    # still lands nowhere.
    """
    gates = []
    enclosing = sorted((b for b in blocks if b[0] < ctrl_start < b[1]), key=lambda b: -b[0])
    for body_start, _end, cond in enclosing:
        found = []
        for ident in dict.fromkeys(IDENT_RE.findall(cond)):
            if ident in NON_STATE_IDENTS or ident[0].isupper():
                continue  # keywords, and constants such as AutocorrectRanking.CHANNEL
            pat = re.compile(r"(?<![\w.])" + re.escape(ident) + r"(?![\w])")
            best = None
            for c_start, c_end, sid in controls:
                if c_start < body_start and sid != ctrl_sid and pat.search(text[c_start:c_end]):
                    if best is None or c_start > best[0]:
                        best = (c_start, sid)
            if best:
                found.append(best)
        for _start, sid in sorted(found, key=lambda f: -f[0]):
            if sid not in gates:
                gates.append(sid)
    return gates


def scan_file(text, strings, entries, seen, default_key="advanced"):
    """Scan a single Kotlin source text for controls, appending to entries."""
    sections = find_sections(text)
    found = []  # (start, call_end, title, res_name, sid)
    for m in CONTROL_RE.finditer(text):
        window = text[m.start():m.start() + 500]
        tm = TITLE_RE.search(window)
        if not tm:
            continue
        if tm.group(1) is not None:
            title = tm.group(1)
            res_name = None
        else:
            res_name = tm.group(2)
            title = strings.get(res_name)
        if not title:
            continue
        call_end = matching_close(text, m.end() - 1, "(", ")")
        found.append((m.start(), call_end, title, res_name, res_name if res_name else slugify(title)))

    blocks = if_blocks(text)
    controls = [(start, end, sid) for start, end, _t, _r, sid in found]
    for start, _end, title, res_name, sid in found:
        var = section_for(start, sections)
        key = section_key(var) if var else default_key
        dedup = (title, key)
        if dedup in seen:
            continue
        seen.add(dedup)
        entries.append((title, keywords_for(title), key, res_name, gate_ids_for(text, start, sid, blocks, controls)))


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_OUT
    strings = load_strings(STRINGS)

    entries = []  # (title, keywords, sectionKey, titleResName or None, gateIds)
    seen = set()

    # Scan SettingsActivity.kt (retains any controls defined inline there)
    scan_file(open(ACTIVITY, encoding="utf-8").read(), strings, entries, seen)

    # Scan all section files — controls moved here during the settings decomposition refactor
    if os.path.isdir(SECTIONS_DIR):
        for fname in sorted(os.listdir(SECTIONS_DIR)):
            if fname.endswith(".kt"):
                fpath = os.path.join(SECTIONS_DIR, fname)
                scan_file(open(fpath, encoding="utf-8").read(), strings, entries, seen,
                          default_key=FILE_DEFAULT_SECTION.get(fname, "advanced"))

    if not entries:
        sys.exit("generate_settings_search_index: no controls found — parser broken")

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    lines = [
        "// GENERATED FILE — do not edit by hand.",
        "// Source: scripts/generate_settings_search_index.py (run via the",
        "// generateSettingsSearchIndex Gradle task before compilation).",
        "package tribixbite.cleverkeys",
        "",
        "/**",
        " * One settings-search entry auto-derived from a SettingsSwitch/Slider/Dropdown control.",
        " * [title] is the ENGLISH title (res/values) and [keywords] are English; [titleRes] is the",
        " * title resource shown and matched in the UI language (0 for a literal title); [settingId]",
        " * is the locale-independent scroll/highlight key (the resource name, or a slug of a literal).",
        " * [gateIds] are the setting ids of the controls whose state reveals this one (it sits in",
        " * their `if` block), innermost first: the search scrolls to the first of them on screen",
        " * when this control is not composed.",
        " */",
        "internal data class GeneratedSearchEntry(",
        "    val title: String,",
        "    val keywords: List<String>,",
        "    val sectionKey: String,",
        "    val titleRes: Int,",
        "    val settingId: String,",
        "    val gateIds: List<String> = emptyList(),",
        ")",
        "",
        "internal val GENERATED_SEARCH_ENTRIES: List<GeneratedSearchEntry> = listOf(",
    ]
    for title, kws, key, res_name, gates in entries:
        kw_lit = ", ".join('"' + k.replace('"', '\\"') + '"' for k in kws)
        t_lit = title.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
        res_lit = f"R.string.{res_name}" if res_name else "0"
        sid = res_name if res_name else slugify(title)
        gate_lit = ", ".join(f'"{g}"' for g in gates)
        lines.append(f'    GeneratedSearchEntry("{t_lit}", listOf({kw_lit}), "{key}", {res_lit}, "{sid}", listOf({gate_lit})),')
    lines.append(")")
    lines.append("")

    open(out_path, "w", encoding="utf-8").write("\n".join(lines))
    print(f"generate_settings_search_index: wrote {len(entries)} entries -> {out_path}")


if __name__ == "__main__":
    main()
