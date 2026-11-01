#!/usr/bin/env python3
"""Export per-locale review sheets for native-speaker translation review, and re-import them.

Every translation in res/values-*/strings.xml was produced or revised by a model; none has
been native-speaker reviewed (docs/i18n/*.md). This script has three modes.

1. Full sheets (the original mode): one CSV per locale with EVERY translatable string.

   python3 scripts/export_translation_review.py --out <dir> [--since <git-ref>] [--locale fa ...]

   Columns: key, area (command palette / keyboard keys / settings / other), english,
   translation, changed (text differs from --since), flag (docs/i18n/review-flags.json),
   verdict and correction (left empty for the reviewer). Rows are sorted flagged first, then
   changed, then by area and key.

2. Scoped native-review sheets: only the resources whose ENGLISH text was added or changed
   after a git ref or date, grouped by feature, with the usage context taken from the code.

   python3 scripts/export_translation_review.py --scope-since 2026-09-29 --out docs/i18n/review/2026-11

   A date (YYYY-MM-DD) resolves to the last commit before that day. Writes `<locale>.csv`.
   Columns: key, feature, english, translation, context, notes, machine_flags,
   reviewer_correction, reviewer_comment. The file starts with `#` header lines (locale, base
   commit, counts and every machine-flagged row); the column-header row is the first row whose
   first cell is `key`. `translation` and `reviewer_correction` hold the text as displayed
   (Android escapes \\' and \\" resolved; \\n kept as the two characters backslash-n).

3. Import: writes every non-empty `reviewer_correction` back into res/values-<locale>/strings.xml.

   python3 scripts/export_translation_review.py --import docs/i18n/review/2026-11/de.csv [--dry-run]

   The locale comes from the sheet's `# locale:` header line (or `--locale`). A correction is
   refused, and the file left unchanged for that row, when it changes the format placeholders,
   adds a literal % to a string without formatted="false", the resource carries inline markup,
   or the key is not in the locale file. The exit status is 1 when any row was refused.
   Only the edited element's text changes; the rest of the file is kept byte for byte.

The tests are scripts/test_export_translation_review.py (pure Python, no network).
"""
from __future__ import annotations

import argparse
import collections
import csv
import json
import re
import subprocess
import sys
import unicodedata
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RES = REPO / "res"
FLAGS_FILE = REPO / "docs" / "i18n" / "review-flags.json"
GLOSSARY_FILE = REPO / "docs" / "i18n" / "glossary.json"

# Key-prefix → review area for the full sheets. First match wins; order matters.
AREAS: list[tuple[str, str]] = [
    ("cmd_", "command palette"),
    ("command_category_", "command palette"),
    ("key_descr_", "keyboard keys"),
    ("extra_key_", "keyboard keys"),
    ("settings_", "settings"),
    ("pref_", "settings"),
]


def area_of(key: str) -> str:
    for prefix, area in AREAS:
        if key.startswith(prefix):
            return area
    return "other"


def element_text(el: ET.Element) -> str:
    """Resource text including inline markup such as <xliff:g>, as the reviewer sees it."""
    parts = [el.text or ""]
    for child in el:
        parts.append(ET.tostring(child, encoding="unicode"))
    return "".join(parts).strip()


def parse_strings(xml: str) -> dict[str, str]:
    """Translatable <string> and <plurals> items, keyed as in the CSV `key` column."""
    return {k: r.raw for k, r in parse_resources(xml).items()}


@dataclass
class Resource:
    """One translatable text: a <string> or one <plurals> item (`name#quantity`)."""
    raw: str                 # element text as stored (XML entities resolved, Android escapes kept)
    formatted: bool = True   # False when the element (or its <plurals>) says formatted="false"
    markup: bool = False     # True when the element has child elements (inline markup)

    @property
    def display(self) -> str:
        return android_decode(self.raw)


def parse_resources(xml: str) -> dict[str, Resource]:
    root = ET.fromstring(xml)
    out: dict[str, Resource] = {}
    for el in root:
        name = el.get("name")
        if not name or el.get("translatable") == "false":
            continue
        formatted = el.get("formatted") != "false"
        if el.tag == "string":
            out[name] = Resource(element_text(el), formatted, len(el) > 0)
        elif el.tag == "plurals":
            for item in el.findall("item"):
                out[f"{name}#{item.get('quantity')}"] = Resource(
                    element_text(item), formatted and item.get("formatted") != "false", len(item) > 0)
    return out


# ---------------------------------------------------------------------------------------------
# Android string-resource escapes
# ---------------------------------------------------------------------------------------------

def android_decode(raw: str) -> str:
    """Stored resource text → the text a reviewer reads.

    Resolves the whole-value "…" wrapper and the \\' \\" \\@ \\? escapes. \\n, \\t and \\uXXXX
    stay as written (two visible characters), so a reviewer sees and keeps the line breaks.
    """
    s = raw
    if len(s) >= 2 and s.startswith('"') and s.endswith('"') and not s.endswith('\\"'):
        s = s[1:-1]
    return re.sub(r"\\([\'\"@?])", r"\1", s)


def android_encode(text: str, wrapped: bool = False) -> str:
    """Displayed text → stored resource text (inverse of android_decode for every real string).

    Escapes ' and " (and a leading @ or ?), XML-escapes & and <, and turns a literal line break
    typed in a spreadsheet cell into \\n. `wrapped` keeps a whole-value "…" wrapper.
    """
    s = text.replace("\r\n", "\n").replace("\n", "\\n")
    s = s.replace("&", "&amp;").replace("<", "&lt;")
    s = re.sub(r"(?<!\\)(['\"])", r"\\\1", s)
    if s[:1] in ("@", "?"):
        s = "\\" + s
    return f'"{s}"' if wrapped else s


def is_wrapped(raw: str) -> bool:
    return len(raw) >= 2 and raw.startswith('"') and raw.endswith('"') and not raw.endswith('\\"')


# ---------------------------------------------------------------------------------------------
# Format placeholders
# ---------------------------------------------------------------------------------------------

# java.util.Formatter conversions (what Android's getString(id, args) uses); %n is a line break.
PLACEHOLDER = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([bBhHsScCdoxXeEfgGaAn%]|[tT][a-zA-Z])")
POSITIONAL = re.compile(r"%(\d+)\$[-#+ 0,(]*\d*(?:\.\d+)?([bBhHsScCdoxXeEfgGaA]|[tT][a-zA-Z])")
TYPE_WORDS = {"s": "text", "d": "number", "f": "decimal number", "x": "hex number", "c": "character"}


def placeholders(text: str, formatted: bool) -> list[str]:
    """Sorted format arguments (`%1$s`, `%d`). In a formatted="false" string, `%` is literal
    unless it is an explicit positional argument, which Android still substitutes."""
    if not formatted:
        return sorted(f"%{m.group(1)}${m.group(2)}" for m in POSITIONAL.finditer(text))
    out = []
    for m in PLACEHOLDER.finditer(text):
        if m.group(2) in ("%", "n"):
            continue  # %% is a literal percent sign, %n a line break
        out.append(f"%{m.group(1)}${m.group(2)}" if m.group(1) else f"%{m.group(2)}")
    return sorted(out)


def literal_percent(text: str) -> bool:
    """True when a formatted string has a % that is neither %% nor a valid argument."""
    stripped = PLACEHOLDER.sub("", text)
    return "%" in stripped


# ---------------------------------------------------------------------------------------------
# git / files
# ---------------------------------------------------------------------------------------------

def read_at(ref: str | None, rel: str, repo: Path = REPO) -> str | None:
    """File content at a git ref (None = working tree); None when it does not exist there."""
    if ref is None:
        path = repo / rel
        return path.read_text(encoding="utf-8") if path.exists() else None
    proc = subprocess.run(
        ["git", "-C", str(repo), "show", f"{ref}:{rel}"],
        capture_output=True, text=True, encoding="utf-8",
    )
    return proc.stdout if proc.returncode == 0 else None


def resolve_ref(since: str) -> str:
    """A git ref, or YYYY-MM-DD → the last commit on HEAD before that day."""
    if re.fullmatch(r"\d{4}-\d{2}-\d{2}", since):
        args = ["rev-list", "-1", f"--before={since}T00:00", "HEAD"]
    else:
        args = ["rev-parse", "--verify", f"{since}^{{commit}}"]
    out = subprocess.run(["git", "-C", str(REPO), *args], capture_output=True, text=True).stdout.strip()
    if not out:
        raise SystemExit(f"cannot resolve {since!r} to a commit")
    return out


def locale_dirs(res: Path = RES) -> list[str]:
    return sorted(p.parent.name.removeprefix("values-") for p in res.glob("values-*/strings.xml"))


def load_flags() -> dict[str, dict[str, str]]:
    """review-flags.json: {"locales": {"<locale>" | "*": {"<key>": "<question>"}}}."""
    if not FLAGS_FILE.exists():
        return {}
    return json.loads(FLAGS_FILE.read_text(encoding="utf-8")).get("locales", {})


# ---------------------------------------------------------------------------------------------
# Mode 1: full sheets
# ---------------------------------------------------------------------------------------------

def export_locale(locale: str, english: dict[str, str], since: str | None,
                  flags: dict[str, dict[str, str]], out_dir: Path) -> tuple[int, int, int]:
    rel = f"res/values-{locale}/strings.xml"
    current_xml = read_at(None, rel)
    if current_xml is None:
        raise SystemExit(f"no such locale: {rel}")
    current = parse_strings(current_xml)
    before: dict[str, str] = {}
    if since is not None:
        old_xml = read_at(since, rel)
        before = parse_strings(old_xml) if old_xml is not None else {}

    locale_flags = {**flags.get("*", {}), **flags.get(locale, {})}
    rows = []
    for key, src in english.items():
        text = current.get(key, "")
        changed = since is not None and text != before.get(key, "")
        rows.append({
            "key": key,
            "area": area_of(key.split("#", 1)[0]),
            "english": src,
            "translation": text,
            "changed": "yes" if changed else "",
            "flag": locale_flags.get(key.split("#", 1)[0], ""),
            "verdict": "",
            "correction": "",
        })
    rows.sort(key=lambda r: (r["flag"] == "", r["changed"] == "", r["area"], r["key"]))

    out = out_dir / f"review-{locale}.csv"
    # utf-8-sig so Excel detects the encoding; LibreOffice and Sheets ignore the BOM.
    with out.open("w", encoding="utf-8-sig", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    return len(rows), sum(r["flag"] != "" for r in rows), sum(r["changed"] == "yes" for r in rows)


# ---------------------------------------------------------------------------------------------
# Mode 2: scoped native-review sheets
# ---------------------------------------------------------------------------------------------

SHEET_COLUMNS = ["key", "feature", "english", "translation", "context", "notes",
                 "machine_flags", "reviewer_correction", "reviewer_comment"]

# Feature groups, in sheet order. A row joins the FIRST group whose key regex matches or one
# of whose source files uses the key. Keys are matched without the `#quantity` suffix.
FEATURES: list[tuple[str, str, tuple[str, ...]]] = [
    ("Persian and right-to-left text", r"zwnj|halfspace|half_space|persian|arabic|_rtl|hebrew", ()),
    ("Voice input", r"^voice_|^cmd_voice|_voice", ("stt/", "VoiceInputBridge", "MicPermission")),
    ("Open-source licences screen", r"^licenses_|^help_third_party", ("OpenSourceLicenses",)),
    ("Autocorrect: Unfinished Words, Ranking & Tuning", r"^autocorrect_", ("AutoCorrectionSection",)),
    ("Familiar looks and Theme Creator", r"^theme_|^appearance_look_|_theme_|^custom_theme",
     ("ThemeLookEditor", "ThemeSettingsActivity", "PredefinedThemes", "CustomThemeDialog",
      "LayoutThemePicker", "CustomThemeManager", "ThemeEditorPreview", "theme/")),
    ("Layout geometry (key spacing and borders)", r"geometry", ("LayoutKeyGeometryDialog",)),
    ("Short-swipe customization and calibration",
     r"^short_swipe_|^calibration_|^intent_|^action_type|^custom_short_swipe",
     ("ShortSwipeCustomizationActivity", "ShortSwipeCalibrationActivity", "CustomShortSwipeExecutor",
      "SwipeDirection", "IntentDefinition", "ActionType", "ShortSwipeAssignment",
      "ShortSwipeAccessibilityActions")),
    ("Subkeys and popover", r"^subkey_|^popover_",
     ("SubkeyAssignActivity", "KeySubkeySources", "SubkeySlotActions", "SubkeyEditUi",
      "LayoutSubkeyEditor", "popover/")),
    ("Clipboard", r"^clipboard_|^private_copy|^system_clipboard|clipboard",
     ("clipboard/", "SettingsClipboardHandlers", "ClipboardSection")),
    ("Command palette (command names and descriptions)", r"^cmd_|^command_",
     ("CommandRegistry", "CommandPaletteDialog")),
    ("Extra keys and key descriptions", r"^extra_key_|^key_descr_",
     ("ExtraKeysPreference", "a11y/KeyLabels")),
    ("Layouts (manager and language binding)", r"^layout_",
     ("LayoutManagerActivity",)),
    ("Backup, restore and import", r"^backup_|^import_|^io_|^pack_",
     ("BackupRestore", "SettingsBackupHandlers", "SettingsImportPlan", "IoFailure", "PackImportFailure",
      "backup/")),
    ("Dictionary, language packs and multilingual", r"^dict_|^dictionary_|^multilang_|^word_",
     ("DictionaryManagerActivity", "WordList", "SettingsDictionaryHandlers", "MultiLanguageSection",
      "SettingsLanguagePackHandlers")),
    ("GIF panel", r"^gif_", ("gif/", "SettingsGifHandlers")),
    ("Swipe typing and gestures", r"^swipe_|^gesture_|^continuous_swipe",
     ("SwipeDebugActivity", "GestureTuningSection", "SwipeTrailSection", "SwipeTypingSection",
      "SwipePerformanceStats", "swipe/")),
    ("Suggestion bar, text actions and key magnifier",
     r"^suggestion_|^bar_|^process_text|^dynamic_action|^case_cycle|^key_magnifier",
     ("SuggestionBar", "SuggestionHandler", "TextActionLauncher", "KeyMagnifierView")),
    ("Settings (other sections)", r"^settings_|^input_|^appearance_|^accessibility_|^advanced_|"
     r"^privacy_|^learning_|^perf_|^help_", ("ui/settings/",)),
    ("Keyboard, tile, launcher and system", r"^keyboard_|^tile_|^launcher_|^manifest_|^ime_|^a11y_",
     ("CleverKeysService", "KeyboardTileAction", "KeyboardReceiver", "Keyboard2View", "LauncherActivity")),
    ("Other", r".", ()),
]

# Usage file → screen name shown in the context column. Unlisted files fall back to rules below.
SCREENS: dict[str, str] = {
    "CommandRegistry.kt": "Command palette",
    "CommandPaletteDialog.kt": "Command palette dialog",
    "ExtraKeysPreference.kt": "Settings › Extra Keys list",
    "KeyLabels.kt": "TalkBack key description",
    "ThemeLookEditor.kt": "Theme Creator › Keyboard Look editor",
    "ThemeSettingsActivity.kt": "Theme Manager screen",
    "PredefinedThemes.kt": "Theme Manager › built-in theme name",
    "CustomThemeDialog.kt": "Theme Creator dialog",
    "LayoutThemePicker.kt": "Layout Manager › per-layout theme picker",
    "ClipboardManager.kt": "Clipboard pane (keyboard)",
    "ClipboardHistoryView.kt": "Clipboard pane › history list",
    "clipboard_pane.xml": "Clipboard pane (keyboard)",
    "clipboard_history_entry.xml": "Clipboard pane › history row",
    "clipboard_filter_dialog.xml": "Clipboard pane › filter dialog",
    "BackupRestorePreviewDialogs.kt": "Backup & Restore › import preview dialog",
    "BackupRestoreResultMessages.kt": "Backup & Restore › result message",
    "BackupRestoreActivity.kt": "Backup & Restore screen",
    "WordListFragment.kt": "Dictionary Manager › word list",
    "WordListAdapter.kt": "Dictionary Manager › word row",
    "DictionaryManagerActivity.kt": "Dictionary Manager screen",
    "SettingsInfoCards.kt": "Settings › information card",
    "SwipeDebugActivity.kt": "Swipe Playground (debug) screen",
    "Keyboard2View.kt": "Keyboard view",
    "SettingsActivity.kt": "Settings screen",
    "VoiceInputController.kt": "Keyboard › voice input (status, toast or TalkBack)",
    "VoicePackImportFailure.kt": "Settings › Voice input › import error",
    "IoFailure.kt": "Import/export error message",
    "PackImportFailure.kt": "Language pack import error",
    "IntentDefinition.kt": "Short Swipes › Send Intent editor",
    "CustomShortSwipeExecutor.kt": "Toast after a custom short swipe",
    "SubkeyAssignActivity.kt": "Subkey assignment screen",
    "ActionType.kt": "Short Swipes › action type",
    "SettingsDialogs.kt": "Settings › dialog",
    "KeyboardTileAction.kt": "Quick Settings tile",
    "KeyboardReceiver.kt": "Keyboard broadcast toast",
    "KeyEventHandler.kt": "Keyboard › toast while typing",
    "SwipeDirection.kt": "Short Swipes › direction name",
    "SwipePerformanceStats.kt": "Settings › swipe statistics",
    "OpenSourceLicensesActivity.kt": "About › Open-source licenses screen",
    "KeySubkeySources.kt": "Subkey editor › source label",
    "SettingsImportPlan.kt": "Backup & Restore › import plan",
    "ClipboardEntry.kt": "Clipboard pane › entry",
    "CustomThemeManager.kt": "Theme Manager › custom themes",
    "MicPermissionActivity.kt": "Microphone permission prompt",
    "SubkeySlotActions.kt": "Subkey editor › slot action",
    "SettingsResetPresets.kt": "Settings › reset presets",
    "VoiceInputBridge.kt": "Keyboard › voice input",
    "SubkeyEditUi.kt": "Subkey editor",
    "CleverKeysService.kt": "Keyboard service (toast or notice)",
    "LauncherActivity.kt": "Launcher screen (app icon)",
    "ShortSwipeAssignment.kt": "Short Swipes › assignment",
    "LayoutKeyGeometryDialog.kt": "Layout Manager › Key spacing & borders dialog",
    "SuggestionBar.kt": "Suggestion bar",
    "LayoutSubkeyEditor.kt": "Layout editor › subkeys",
    "PrivateCopyDispatch.kt": "Private copy (toast)",
    "TextActionLauncher.kt": "Text actions (toast)",
    "SettingsControls.kt": "Settings › control",
    "TodoEntry.kt": "Clipboard › Todos tab",
    "ClipboardHistoryService.kt": "Clipboard history (toast)",
    "ThemeEditorPreview.kt": "Theme Creator › preview",
    "SwipeEngineFallback.kt": "Swipe engine fallback notice",
    "SuggestionHandler.kt": "Suggestion bar (toast)",
    "IMEStatusHelper.kt": "Keyboard status prompt",
    "SettingsSearch.kt": "Settings › search",
    "SettingsScreen.kt": "Settings screen",
    "ShortSwipeAccessibilityActions.kt": "TalkBack action (short swipes)",
    "KeyMagnifierView.kt": "Key magnifier",
    "AndroidManifest.xml": "Android system (app/activity label in launcher, settings or share menu)",
    "fragment_word_list.xml": "Dictionary Manager › word list",
    "gif_pane.xml": "GIF pane (keyboard)",
}


def screen_of(path: str) -> str:
    name = Path(path).name
    if name in SCREENS:
        return SCREENS[name]
    stem = Path(path).stem
    if "/ui/settings/sections/" in path:
        return "Settings › " + re.sub(r"(?<!^)(?=[A-Z])", " ", stem.removesuffix("Section")) + " section"
    if "/ui/settings/io/" in path:
        return "Settings › " + re.sub(r"(?<!^)(?=[A-Z])", " ",
                                     stem.removeprefix("Settings").removesuffix("Handlers")) + " import/export message"
    if stem.endswith("Activity"):
        return re.sub(r"(?<!^)(?=[A-Z])", " ", stem.removesuffix("Activity")) + " screen"
    if path.startswith("res/layout/"):
        return f"layout {name}"
    return name


FUN_RE = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(")
CLASS_RE = re.compile(r"\b(?:class|object)\s+(\w+)")
COMMAND_RE = re.compile(
    r'Command\(\s*"([^"]+)"\s*,\s*R\.string\.(\w+)\s*,\s*R\.string\.(\w+)\s*,\s*Category\.(\w+)')
USE_RE = re.compile(r"R\.(?:string|plurals)\.(\w+)|@string/(\w+)")


@dataclass
class Usage:
    path: str
    line: int
    scope: str


STRING_LIT = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')


def code_scopes(lines: list[str]) -> list[tuple[int, int, str]]:
    """(first line, last line, name) of every fun/class/object. String literals and // comments
    are blanked first; the parameter list is skipped by paren matching, then the body is found
    by brace matching. An expression body (`= …`) without a brace on that line spans up to it."""
    clean = [STRING_LIT.sub('""', ln.split("//", 1)[0] if "://" not in ln else ln) for ln in lines]
    scopes: list[tuple[int, int, str]] = []
    for i, text in enumerate(clean):
        m = FUN_RE.search(text) or CLASS_RE.search(text)
        if not m:
            continue
        # Character stream from the declaration onward, with the line index of each character.
        paren = brace = 0
        state = "params" if text[m.end() - 1:m.end()] == "(" else "header"
        end = i
        done = False
        for j in range(i, min(len(clean), i + 4000)):
            seg = clean[j][m.end():] if j == i else clean[j]
            for ch in seg:
                if state == "params":
                    paren += ch == "("
                    paren -= ch == ")"
                    if paren < 0:
                        state = "header"
                elif state == "header":
                    if ch == "{":
                        state, brace = "body", 1
                    elif ch == "=":
                        state = "expr"
                elif state == "expr":
                    if ch == "{":
                        state, brace = "body", 1
                elif state == "body":
                    brace += ch == "{"
                    brace -= ch == "}"
                    if brace == 0:
                        done = True
                        break
            end = j
            if done or (state == "expr" and "{" not in seg):
                break
            if state == "header" and j > i + 3:
                end = i  # abstract / interface member: no body
                break
        scopes.append((i + 1, end + 1, m.group(1)))
    return scopes


def enclosing(scopes: list[tuple[int, int, str]], line: int) -> str:
    inside = [s for s in scopes if s[0] <= line <= s[1]]
    return max(inside, key=lambda s: s[0])[2] if inside else ""


def collect_usages(repo: Path = REPO) -> dict[str, list[Usage]]:
    """Every R.string / R.plurals / @string reference in the app's code, manifest and XML."""
    roots = [repo / "src" / "main" / "kotlin", repo / "src" / "main" / "java", repo / "res" / "layout",
             repo / "res" / "xml", repo / "res" / "menu"]
    files = [p for r in roots if r.exists() for p in sorted(r.rglob("*")) if p.suffix in (".kt", ".java", ".xml")]
    files.append(repo / "AndroidManifest.xml")
    out: dict[str, list[Usage]] = collections.defaultdict(list)
    for path in files:
        if not path.exists():
            continue
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        scopes = code_scopes(lines) if path.suffix in (".kt", ".java") else []
        for i, text in enumerate(lines, 1):
            for u in USE_RE.finditer(text):
                out[u.group(1) or u.group(2)].append(Usage(str(path.relative_to(repo)), i, enclosing(scopes, i)))
    return out


def command_index(repo: Path = REPO) -> dict[str, str]:
    """cmd string key → 'command <id> (<Category>), name|description'."""
    path = repo / "src/main/kotlin/tribixbite/cleverkeys/customization/CommandRegistry.kt"
    out: dict[str, str] = {}
    if path.exists():
        for m in COMMAND_RE.finditer(path.read_text(encoding="utf-8")):
            cid, name, desc, cat = m.groups()
            cat_label = cat.replace("_", " ").title()
            out[name] = (f"Command palette, category {cat_label}: the NAME of command `{cid}` "
                         "(one row, ellipsized; also shown in Short Swipes and Extra Keys pickers)")
            out[desc] = (f"Command palette, category {cat_label}: the DESCRIPTION line under command "
                         f"`{cid}` (one line, ellipsized; also searchable)")
    return out


def context_for(key: str, usages: dict[str, list[Usage]], commands: dict[str, str]) -> str:
    base = key.split("#", 1)[0]
    parts: list[str] = []
    if base in commands:
        parts.append(commands[base])
    seen: set[tuple[str, str]] = set()
    sites: list[str] = []
    for u in usages.get(base, []):
        if base in commands and u.path.endswith("CommandRegistry.kt"):
            continue  # already described by the command index
        screen = screen_of(u.path)
        if (screen, u.scope) in seen:
            continue
        seen.add((screen, u.scope))
        where = f"{Path(u.path).name}:{u.line}" + (f" {u.scope}()" if u.scope and u.path.endswith(".kt") else "")
        sites.append(f"{screen} [{where}]")
    if sites:
        parts.append("; ".join(sites[:3]) + (f"; +{len(sites) - 3} more" if len(sites) > 3 else ""))
    if not parts:
        parts.append("no direct code reference found (system-provided label or not yet wired)")
    return " — ".join(parts)


# Thresholds mirror src/test/kotlin/tribixbite/cleverkeys/TranslationLengthTest.kt.
BAR_RATIO, TITLE_RATIO, MIN_LENGTH = 1.75, 2.0, 40
ARG_FOR_LENGTH = re.compile(r"%[0-9]+\$[-#+ 0,(]*[0-9]*(?:\.[0-9]+)?[a-zA-Z]")


def visible_length(display: str) -> int:
    s = ARG_FOR_LENGTH.sub("xxxx", display.replace("\\n", " ").replace("\\t", " ").strip())
    return len(s)


def length_rule(key: str) -> tuple[float, str] | None:
    base = key.split("#", 1)[0]
    if base.startswith("suggestion_"):
        return BAR_RATIO, "suggestion-bar chip, at most 2 lines"
    if base.endswith("_title") or base.startswith("provenance_origin_"):
        return TITLE_RATIO, "title"
    if base.startswith("cmd_") or base.startswith("command_category_"):
        return TITLE_RATIO, "one palette row, ellipsized"
    return None


PLURAL_HINT = {
    "zero": "count 0 (and other counts this language groups with it)",
    "one": "count 1 (and other counts this language groups with it)",
    "two": "count 2", "few": "a small count (language rule)", "many": "a large count (language rule)",
    "other": "every other count (in cs/pl/ru/uk: fractions only)",
}


def notes_for(key: str, en: Resource, question: str) -> str:
    notes: list[str] = []
    args = placeholders(en.raw, en.formatted)
    if args:
        desc = ", ".join(f"{a} = {TYPE_WORDS.get(a[-1], 'value')}" for a in sorted(set(args)))
        notes.append(f"keep placeholders {desc}; their order may change")
    if not en.formatted:
        notes.append("formatted=\"false\": a % here is a literal percent sign")
    if "\\n" in en.raw:
        notes.append(f"keep the {en.raw.count(chr(92) + 'n')} line break(s) \\n")
    if "#" in key:
        q = key.split("#", 1)[1]
        notes.append(f"plural item '{q}': {PLURAL_HINT.get(q, q)}")
    rule = length_rule(key)
    en_len = visible_length(en.display)
    if rule:
        ratio, what = rule
        limit = max(MIN_LENGTH, int(ratio * en_len))
        notes.append(f"max ~{limit} characters ({what}; English is {en_len})")
    elif key.endswith("_short") or re.search(r"_short_\w+$", key) or (key.startswith("extra_key_") and en_len <= 12):
        notes.append(f"short label on a key or chip: keep about as short as English ({en_len})")
    if question:
        notes.append(f"OPEN QUESTION: {question}")
    return "; ".join(notes)


# Glossary probe: English trigger per glossary concept. A row whose English matches the trigger
# is checked against the locale's allowed / forbidden stems (same matching as TranslationGlossaryTest).
CONCEPT_TRIGGERS: dict[str, str] = {
    "swipe": r"\bswip", "dictionary": r"\bdictionar", "suggestion": r"\bsuggestion",
    "learn": r"\blearn", "clipboard": r"\bclipboard", "layout": r"\blayouts?\b",
    "gesture": r"\bgesture", "word": r"\bwords?\b", "correction": r"correct",
}


def _norm(s: str) -> str:
    return unicodedata.normalize("NFC", s).lower().replace("\u0307", "")


def contains_stem(text: str, stem: str, anywhere: bool) -> bool:
    t, st = _norm(text), _norm(stem)
    start = t.find(st)
    while start >= 0:
        if anywhere or start == 0:
            return True
        prev = t[start - 1]
        if not (prev.isalpha() or unicodedata.category(prev).startswith("M")):
            return True
        start = t.find(st, start + 1)
    return False


def load_glossary(path: Path = GLOSSARY_FILE) -> dict:
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {"locales": {}}


def machine_flags(locale: str, key: str, en: Resource, tr: Resource | None, glossary: dict) -> list[str]:
    if tr is None:
        return ["missing in this locale"]
    flags: list[str] = []
    en_d, tr_d = en.display, tr.display
    if tr_d == en_d and re.search(r"[A-Za-z]{3,}", re.sub(r"%\d+\$[a-z]", "", en_d)):
        flags.append("identical to English (untranslated, or a name/brand that stays English?)")
    if placeholders(en.raw, en.formatted) != placeholders(tr.raw, tr.formatted):
        en_args, tr_args = placeholders(en.raw, en.formatted), placeholders(tr.raw, tr.formatted)
        if "#" not in key or key.endswith("#other") or set(tr_args) - set(en_args):
            flags.append(f"placeholders differ: English {en_args} vs {tr_args}")
    if tr.formatted and literal_percent(tr.raw):
        flags.append("literal % without formatted=\"false\"")
    if en.raw.count("\\n") != tr.raw.count("\\n"):
        flags.append(f"line breaks differ: English {en.raw.count(chr(92) + 'n')} vs {tr.raw.count(chr(92) + 'n')}")
    rule = length_rule(key)
    if rule:
        en_len, tr_len = visible_length(en_d), visible_length(tr_d)
        if tr_len > MIN_LENGTH and tr_len > rule[0] * en_len:
            flags.append(f"too long: {tr_len} characters vs English {en_len}")
    loc = glossary.get("locales", {}).get(locale)
    if loc:
        anywhere = loc.get("matchAnywhere", False)
        for concept, terms in loc.get("terms", {}).items():
            trigger = CONCEPT_TRIGGERS.get(concept)
            if not trigger or not re.search(trigger, en_d, re.IGNORECASE):
                continue
            bad = [s for s in terms.get("forbidden", []) if contains_stem(tr_d, s, anywhere)]
            if bad:
                flags.append(f"glossary: '{concept}' uses non-glossary term {bad} (glossary: {terms['allowed']})")
            elif not any(contains_stem(tr_d, s, anywhere) for s in terms.get("allowed", [])):
                flags.append(f"glossary: English says '{concept}' but none of {terms['allowed']} appears")
    return flags


def feature_of(key: str, usages: dict[str, list[Usage]]) -> str:
    base = key.split("#", 1)[0]
    paths = [u.path for u in usages.get(base, [])]
    for name, key_re, files in FEATURES:
        if re.search(key_re, base) or any(f in p for f in files for p in paths):
            return name
    return "Other"


def scoped_keys(base_ref: str, res: Path = RES) -> list[str]:
    """Resource NAMES whose English text was added or changed after base_ref. A <plurals> is in
    scope when any of its English items is; each locale then reviews its OWN quantity items."""
    current = parse_resources((res / "values" / "strings.xml").read_text(encoding="utf-8"))
    old_xml = read_at(base_ref, "res/values/strings.xml")
    old = parse_resources(old_xml) if old_xml else {}
    names: list[str] = []
    for k, r in current.items():
        name = k.split("#", 1)[0]
        if (k not in old or old[k].raw != r.raw) and name not in names:
            names.append(name)
    return names


QUANTITY_ORDER = ["zero", "one", "two", "few", "many", "other"]


def locale_rows_keys(names: list[str], english: dict[str, Resource],
                     current: dict[str, Resource]) -> list[tuple[str, str]]:
    """(row key, English source key) per locale. A plural expands to the locale's own quantities
    (ru few/many, ja other only); a quantity English lacks is reviewed against English 'other'."""
    out: list[tuple[str, str]] = []
    for name in names:
        if name in english:
            out.append((name, name))
            continue
        en_q = [q for q in QUANTITY_ORDER if f"{name}#{q}" in english]
        loc_q = [q for q in QUANTITY_ORDER if f"{name}#{q}" in current] or en_q
        for q in loc_q:
            out.append((f"{name}#{q}", f"{name}#{q}" if q in en_q else f"{name}#other"))
    return out


def export_scoped(locale: str, keys: list[str], english: dict[str, Resource], base_ref: str, since: str,
                  usages: dict[str, list[Usage]], commands: dict[str, str], flags: dict[str, dict[str, str]],
                  glossary: dict, out_dir: Path, res: Path = RES) -> tuple[int, int]:
    rel = f"res/values-{locale}/strings.xml"
    path = res / f"values-{locale}" / "strings.xml"
    if not path.exists():
        raise SystemExit(f"no such locale: {path}")
    current_xml = path.read_text(encoding="utf-8")
    current = parse_resources(current_xml)
    locale_flags = {**flags.get("*", {}), **flags.get(locale, {})}
    order = {name: i for i, (name, _, _) in enumerate(FEATURES)}

    # Strings whose English is older than the base but whose TRANSLATION changed after it (agent
    # fixes such as the 2026-10-25 LanguageTool pass) are in scope too.
    old_xml = read_at(base_ref, rel)
    old = parse_resources(old_xml) if old_xml else {}
    scope = set(keys)
    retranslated = {k for k, r in current.items()
                    if k.split("#", 1)[0] not in scope and (k not in old or old[k].raw != r.raw)
                    and (k in english or f"{k.split('#', 1)[0]}#other" in english)}
    pairs = locale_rows_keys(keys, english, current)
    pairs += [(k, k if k in english else f"{k.split('#', 1)[0]}#other") for k in sorted(retranslated)]

    rows = []
    for key, en_key in pairs:
        en = english[en_key]
        tr = current.get(key)
        mflags = machine_flags(locale, key, en, tr, glossary)
        rows.append({
            "key": key,
            "feature": feature_of(key, usages),
            "english": en.display,
            "translation": tr.display if tr else "",
            "context": context_for(key, usages, commands),
            "notes": ("translation changed since the base; English unchanged; " if key in retranslated else "")
                     + ("" if en_key == key else f"English has no '{key.split('#')[1]}' form; the English "
                      "column shows its 'other' form; ") + notes_for(key, en, locale_flags.get(key.split("#", 1)[0], "")),
            "machine_flags": " | ".join(mflags),
            "reviewer_correction": "",
            "reviewer_comment": "",
        })
    def sort_key(r: dict[str, str]) -> tuple[int, str, int]:
        name, _, qty = r["key"].partition("#")
        return order.get(r["feature"], len(order)), name, QUANTITY_ORDER.index(qty) if qty else -1
    rows.sort(key=sort_key)

    flagged = [r for r in rows if r["machine_flags"]]
    by_feature = collections.Counter(r["feature"] for r in rows)
    kinds = collections.Counter(f.split(":")[0].split(" (")[0] for r in flagged
                                for f in r["machine_flags"].split(" | "))
    header = [
        f"locale: {locale}",
        f"scope: every string and plural item whose ENGLISH text was added or changed since {since} "
        f"(base commit {base_ref[:10]}): {len(keys)} resources, plus {len(retranslated)} older strings whose "
        f"{locale} translation changed since then; {len(rows)} rows (plurals: one row per quantity this "
        "language uses)",
        "how to review: read README.md in this folder; write ONLY in reviewer_correction "
        "(the full corrected text) and reviewer_comment; leave every other column as it is",
        "rows per feature: " + "; ".join(f"{name} {n}" for name, n in
                                          sorted(by_feature.items(), key=lambda kv: order.get(kv[0], 99))),
        f"machine sanity pass (no external service): {len(flagged)} flagged rows — "
        + ("; ".join(f"{k} {n}" for k, n in kinds.most_common()) if kinds else "none"),
    ]
    header += [f"flagged: {r['key']} — {r['machine_flags']}" for r in flagged]

    out = out_dir / f"{locale}.csv"
    with out.open("w", encoding="utf-8-sig", newline="") as fh:
        writer = csv.writer(fh)
        for line in header:
            writer.writerow([f"# {line}"])
        dw = csv.DictWriter(fh, fieldnames=SHEET_COLUMNS)
        dw.writeheader()
        dw.writerows(rows)
    return len(rows), len(flagged)


# ---------------------------------------------------------------------------------------------
# Mode 3: import
# ---------------------------------------------------------------------------------------------

@dataclass
class ImportResult:
    applied: list[str] = field(default_factory=list)
    unchanged: list[str] = field(default_factory=list)
    refused: list[str] = field(default_factory=list)


def read_sheet(path: Path) -> tuple[str | None, list[dict[str, str]]]:
    """(locale from the `# locale:` header, rows). Header lines are any rows before the row whose
    first cell is `key`; spreadsheets may append empty cells to them, which is tolerated."""
    with path.open(encoding="utf-8-sig", newline="") as fh:
        all_rows = list(csv.reader(fh))
    locale = None
    for i, row in enumerate(all_rows):
        first = row[0].strip() if row else ""
        m = re.match(r"#\s*locale:\s*(\S+)", first)
        if m:
            locale = m.group(1)
        if first == "key":
            cols = [c.strip() for c in row]
            return locale, [dict(zip(cols, r)) for r in all_rows[i + 1:] if r and r[0].strip()]
    raise SystemExit(f"{path}: no column-header row starting with 'key'")


def _element_span(xml: str, key: str) -> tuple[int, int] | None:
    """(start, end) of the stored text of `key` (`name` or `name#quantity`) in the raw file."""
    if "#" in key:
        name, qty = key.split("#", 1)
        block = re.search(rf'<plurals\s+name="{re.escape(name)}"[^>]*>(.*?)</plurals>', xml, re.S)
        if not block:
            return None
        item = re.search(rf'<item\s+quantity="{re.escape(qty)}"\s*>(.*?)</item>', block.group(1), re.S)
        if not item:
            return None
        off = block.start(1)
        return off + item.start(1), off + item.end(1)
    matches = list(re.finditer(rf'<string\s+name="{re.escape(key)}"(?:\s[^>]*)?>(.*?)</string>', xml, re.S))
    if len(matches) != 1:
        return None
    return matches[0].start(1), matches[0].end(1)


def import_sheet(sheet: Path, locale: str | None = None, res: Path = RES, dry_run: bool = False) -> ImportResult:
    sheet_locale, rows = read_sheet(sheet)
    locale = locale or sheet_locale or sheet.stem
    path = res / f"values-{locale}" / "strings.xml"
    if not path.exists():
        raise SystemExit(f"no such locale file: {path}")
    xml = path.read_text(encoding="utf-8")
    english = parse_resources((res / "values" / "strings.xml").read_text(encoding="utf-8"))
    result = ImportResult()
    parsed = parse_resources(xml)  # re-parsed only after an applied edit

    for row in rows:
        key = row.get("key", "").strip()
        cell = row.get("reviewer_correction") or ""
        if not key or not cell.strip():
            continue
        current = parsed.get(key)
        if current is None:
            result.refused.append(f"{key}: not in {path.relative_to(res.parent)}")
            continue
        # Only a "…"-wrapped value keeps its outer spaces on the device; elsewhere aapt trims them.
        correction = cell if is_wrapped(current.raw) else cell.strip()
        if current.markup:
            result.refused.append(f"{key}: has inline markup; edit the XML by hand")
            continue
        if correction == current.display:
            result.unchanged.append(key)
            continue
        expected = placeholders(current.raw, current.formatted)
        en = english.get(key)
        got = placeholders(android_encode(correction), current.formatted)
        if got != expected:
            result.refused.append(f"{key}: placeholders {got} do not match the current {expected}")
            continue
        if current.formatted and literal_percent(correction):
            result.refused.append(f"{key}: literal % in a formatted string; write %% or ask a developer "
                                  "to mark it formatted=\"false\"")
            continue
        span = _element_span(xml, key)
        if span is None:
            result.refused.append(f"{key}: could not locate a unique element in the file")
            continue
        new_raw = android_encode(correction, wrapped=is_wrapped(current.raw))
        candidate = xml[:span[0]] + new_raw + xml[span[1]:]
        candidate_parsed = parse_resources(candidate)
        reparsed = candidate_parsed.get(key)
        if reparsed is None or reparsed.display != correction.replace("\r\n", "\n").replace("\n", "\\n"):
            result.refused.append(f"{key}: round-trip check failed; edit the XML by hand")
            continue
        xml, parsed = candidate, candidate_parsed
        result.applied.append(key + ("" if en else " (no English source)"))

    if result.applied and not dry_run:
        path.write_text(xml, encoding="utf-8")
    return result


# ---------------------------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, help="output directory (created if missing)")
    ap.add_argument("--since", help="full sheets: git ref; marks strings whose translation changed after it")
    ap.add_argument("--scope-since", help="scoped sheets: git ref or YYYY-MM-DD; only strings whose English "
                                          "was added or changed after it")
    ap.add_argument("--locale", action="append", help="locale dir suffix, e.g. fa or zh-rCN (repeatable; default all)")
    ap.add_argument("--import", dest="import_", type=Path, nargs="+", metavar="SHEET",
                    help="write reviewer_correction cells from these sheets into res/values-<locale>/strings.xml")
    ap.add_argument("--dry-run", action="store_true", help="with --import: validate only, write nothing")
    args = ap.parse_args()

    if args.import_:
        if args.locale and len(args.locale) != 1:
            ap.error("--import takes at most one --locale (it overrides the sheet's own)")
        status = 0
        for sheet in args.import_:
            r = import_sheet(sheet, args.locale[0] if args.locale else None, dry_run=args.dry_run)
            verb = "would apply" if args.dry_run else "applied"
            print(f"{sheet}: {verb} {len(r.applied)}, unchanged {len(r.unchanged)}, refused {len(r.refused)}")
            for line in r.refused:
                print(f"  refused {line}", file=sys.stderr)
            status |= 1 if r.refused else 0
        return status

    if args.out is None:
        ap.error("--out is required unless --import is given")
    locales = args.locale or locale_dirs()
    flags = load_flags()
    args.out.mkdir(parents=True, exist_ok=True)

    if args.scope_since:
        base = resolve_ref(args.scope_since)
        english = parse_resources((RES / "values" / "strings.xml").read_text(encoding="utf-8"))
        keys = scoped_keys(base)
        usages, commands, glossary = collect_usages(), command_index(), load_glossary()
        for locale in locales:
            total, flagged = export_scoped(locale, keys, english, base, args.scope_since, usages, commands,
                                           flags, glossary, args.out)
            print(f"{locale}: {total} rows, {flagged} machine-flagged")
        return 0

    english_raw = parse_strings((RES / "values" / "strings.xml").read_text(encoding="utf-8"))
    unknown = [k for loc in flags.values() for k in loc if k not in english_raw and f"{k}#other" not in english_raw]
    if unknown:
        print(f"review-flags.json names keys not in res/values: {unknown}", file=sys.stderr)
        return 1
    for locale in locales:
        total, flagged, changed = export_locale(locale, english_raw, args.since, flags, args.out)
        extra = f", {changed} changed since {args.since}" if args.since else ""
        print(f"{locale}: {total} strings, {flagged} flagged{extra}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
