#!/usr/bin/env python3
"""Tests for scripts/export_translation_review.py (pure Python, no network, no Gradle).

Run: python3 scripts/test_export_translation_review.py
"""
from __future__ import annotations

import csv
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import export_translation_review as etr  # noqa: E402

ENGLISH_XML = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name" translatable="false">CleverKeys</string>
    <string name="greeting">Hello %1$s</string>
    <string name="apostrophe">Don\\'t swipe</string>
    <string name="percent" formatted="false">Height as % of the screen</string>
    <string name="wrapped">"  spaced  "</string>
    <string name="untouched">Clipboard</string>
    <plurals name="items">
        <item quantity="one">%1$d item</item>
        <item quantity="other">%1$d items</item>
    </plurals>
</resources>
"""

# The locale file keeps odd but valid formatting that the importer must not touch.
LOCALE_XML = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- comment kept -->
    <string name="greeting">Hallo %1$s</string>
    <string name="apostrophe">Nicht wischen</string>
    <string name="percent" formatted="false">Höhe in % des Bildschirms</string>
    <string name="wrapped">"  abstand  "</string>
    <string name="untouched">Clipboard</string>
    <plurals name="items">
        <item quantity="one">%1$d Eintrag</item>
        <item quantity="few">%1$d Einträge (few)</item>
        <item quantity="other">%1$d Einträge</item>
    </plurals>
</resources>
"""


def write_res(root: Path) -> Path:
    res = root / "res"
    (res / "values").mkdir(parents=True)
    (res / "values-de").mkdir()
    (res / "values" / "strings.xml").write_text(ENGLISH_XML, encoding="utf-8")
    (res / "values-de" / "strings.xml").write_text(LOCALE_XML, encoding="utf-8")
    return res


def write_sheet(path: Path, rows: list[dict[str, str]]) -> None:
    """A sheet in the current format: plain CSV, the correction in `your_fix`."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as fh:
        dw = csv.DictWriter(fh, fieldnames=etr.CHECK_COLUMNS)
        dw.writeheader()
        for r in rows:
            dw.writerow({c: r.get(c, "") for c in etr.CHECK_COLUMNS})


# The first (2026-11-03) format: `#` header lines, then `reviewer_correction`. Still importable.
OLD_COLUMNS = ["key", "feature", "english", "translation", "context", "notes",
               "machine_flags", "reviewer_correction", "reviewer_comment"]


def write_old_sheet(path: Path, rows: list[dict[str, str]], locale: str = "de") -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as fh:
        w = csv.writer(fh)
        # A spreadsheet re-save pads header lines with empty cells; the reader must cope.
        w.writerow([f"# locale: {locale}", "", ""])
        w.writerow(["# flagged: untouched — identical to English", ""])
        dw = csv.DictWriter(fh, fieldnames=OLD_COLUMNS)
        dw.writeheader()
        for r in rows:
            dw.writerow({c: r.get(c, "") for c in OLD_COLUMNS})


class EscapeRoundTrip(unittest.TestCase):
    def roundtrip(self, raw: str) -> None:
        shown = etr.android_decode(raw)
        stored = etr.android_encode(shown, wrapped=etr.is_wrapped(raw))
        el = ET.fromstring(f"<string>{stored}</string>")
        self.assertEqual(etr.android_decode(etr.element_text(el)), shown, raw)

    def test_fixture_cases(self) -> None:
        for raw in ["Don\\'t", '\\"%1$s\\" added', "a\\nb", '"  wrapped \' "', "\\@home", "A & B < C",
                    "plain"]:
            self.roundtrip(raw)

    def test_decode_and_encode(self) -> None:
        self.assertEqual(etr.android_decode("Don\\'t \\\"x\\\"\\nnext"), "Don't \"x\"\\nnext")
        self.assertEqual(etr.android_encode("Don't \"x\"\nnext & <b>"),
                         "Don\\'t \\\"x\\\"\\nnext &amp; &lt;b>")
        self.assertEqual(etr.android_encode("@home"), "\\@home")

    def test_every_real_resource_round_trips(self) -> None:
        """Every string in every real locale survives display → store → display unchanged."""
        count = 0
        for path in sorted(etr.RES.glob("values*/strings.xml")):
            for key, r in etr.parse_resources(path.read_text(encoding="utf-8")).items():
                if not r.markup:
                    self.roundtrip(r.raw)
                    count += 1
        self.assertGreater(count, 20000)


class Placeholders(unittest.TestCase):
    def test_rules(self) -> None:
        self.assertEqual(etr.placeholders("%2$d of %1$s, 100%%", True), ["%1$s", "%2$d"])
        self.assertEqual(etr.placeholders("%d words", True), ["%d"])
        self.assertEqual(etr.placeholders("as % of %1$s", False), ["%1$s"])
        self.assertTrue(etr.literal_percent("50 % mehr"))
        self.assertFalse(etr.literal_percent("% d is a Formatter argument (space flag)"))
        self.assertFalse(etr.literal_percent("50%% done %1$d"))


class Scopes(unittest.TestCase):
    def test_multiline_signature_and_expression_body(self) -> None:
        src = [
            "class A {",                          # 1
            "    @Composable",                     # 2
            "    fun Screen(",                     # 3
            "        modifier: Modifier = Modifier,",
            "    ) {",                             # 5
            "        Text(stringResource(R.string.x))",  # 6
            "    }",                               # 7
            "    private fun msg(r: Int): Int = when (r) {",  # 8
            "        1 -> R.string.y",             # 9
            "        else -> R.string.z",
            "    }",                               # 11
            "    fun one() = R.string.w",          # 12
            "}",
        ]
        scopes = etr.code_scopes(src)
        self.assertEqual(etr.enclosing(scopes, 6), "Screen")
        self.assertEqual(etr.enclosing(scopes, 9), "msg")
        self.assertEqual(etr.enclosing(scopes, 12), "one")
        self.assertEqual(etr.enclosing(scopes, 13), "A")


class PluralRows(unittest.TestCase):
    def test_locale_quantities_drive_the_rows(self) -> None:
        en = etr.parse_resources(ENGLISH_XML)
        de = etr.parse_resources(LOCALE_XML)
        pairs = etr.locale_rows_keys(["greeting", "items"], en, de)
        self.assertEqual(pairs, [("greeting", "greeting"), ("items#one", "items#one"),
                                 ("items#few", "items#other"), ("items#other", "items#other")])


class MachineFlags(unittest.TestCase):
    glossary = {"locales": {"de": {"matchAnywhere": True, "terms": {
        "clipboard": {"allowed": ["zwischenablage"]},
        "swipe": {"allowed": ["wisch"], "forbidden": ["swip"]}}}}}

    def flags(self, key: str, en: str, tr: str) -> list[str]:
        return etr.machine_flags("de", key, etr.Resource(en), etr.Resource(tr), self.glossary)

    def test_identical_glossary_and_placeholders(self) -> None:
        self.assertTrue(any("identical" in f for f in self.flags("k", "Clipboard", "Clipboard")))
        self.assertTrue(any("none of ['zwischenablage']" in f for f in self.flags("k", "Clipboard", "Clipboard")))
        self.assertTrue(any("non-glossary term" in f for f in self.flags("k", "Swipe typing", "Swipen")))
        self.assertTrue(any("placeholders differ" in f for f in self.flags("k", "%1$s added", "hinzugefügt")))
        self.assertEqual(self.flags("k", "Swipe %1$s", "Wischen %1$s"), [])
        # A non-other plural item may drop the count (ru/uk 'one' covers 21, 31, …) …
        self.assertEqual(self.flags("items#one", "%1$d item", "ein Eintrag"), [])
        # … but the 'other' item may not.
        self.assertTrue(self.flags("items#other", "%1$d items", "Einträge"))


class Import(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.res = write_res(self.root)
        self.sheet = self.root / "review" / "de" / etr.CHECK_FILE

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def target(self) -> str:
        return (self.res / "values-de" / "strings.xml").read_text(encoding="utf-8")

    def test_applies_valid_corrections_and_keeps_the_rest_byte_for_byte(self) -> None:
        write_sheet(self.sheet, [
            {"key": "greeting", "your_fix": "Guten Tag, %1$s"},
            {"key": "apostrophe", "your_fix": "Nicht \"wischen\", bitte's"},
            {"key": "items#few", "your_fix": "%1$d Einträge"},
            {"key": "wrapped", "your_fix": "  neu  "},
            {"key": "untouched", "your_fix": "Zwischenablage &\nmehr"},
            {"key": "percent", "your_fix": ""},          # empty: ignored
        ])
        result = etr.import_sheet(self.sheet, res=self.res)
        self.assertEqual(result.refused, [])
        self.assertEqual(len(result.applied), 5)
        after = self.target()
        self.assertIn('<string name="greeting">Guten Tag, %1$s</string>', after)
        self.assertIn('<string name="apostrophe">Nicht \\"wischen\\", bitte\\\'s</string>', after)
        self.assertIn('<item quantity="few">%1$d Einträge</item>', after)
        self.assertIn('<string name="wrapped">"  neu  "</string>', after)
        self.assertIn('<string name="untouched">Zwischenablage &amp;\\nmehr</string>', after)
        # Everything else is unchanged, including the comment and the other plural items.
        changed = {i for i, (a, b) in enumerate(zip(LOCALE_XML.splitlines(), after.splitlines())) if a != b}
        self.assertEqual(len(changed), 5)
        self.assertIn("<!-- comment kept -->", after)
        self.assertIn('<item quantity="one">%1$d Eintrag</item>', after)
        parsed = etr.parse_resources(after)
        self.assertEqual(parsed["apostrophe"].display, "Nicht \"wischen\", bitte's")

    def test_refuses_unsafe_rows_and_leaves_them_untouched(self) -> None:
        write_sheet(self.sheet, [
            {"key": "greeting", "your_fix": "Hallo"},                 # drops %1$s
            {"key": "items#other", "your_fix": "%1$s Einträge"},      # wrong type
            {"key": "untouched", "your_fix": "50 % mehr Zwischenablage"},  # literal %
            {"key": "nope", "your_fix": "x"},                         # unknown key
            {"key": "apostrophe", "your_fix": "Nicht wischen"},       # same as current
            {"key": "percent", "your_fix": "Höhe als % des Bildschirms"},  # allowed: formatted=false
        ])
        result = etr.import_sheet(self.sheet, res=self.res)
        self.assertEqual(len(result.refused), 4, result.refused)
        self.assertEqual(result.unchanged, ["apostrophe"])
        self.assertEqual(result.applied, ["percent"])
        after = self.target()
        self.assertIn('<string name="greeting">Hallo %1$s</string>', after)
        self.assertIn('<string name="percent" formatted="false">Höhe als % des Bildschirms</string>', after)

    def test_dry_run_writes_nothing(self) -> None:
        write_sheet(self.sheet, [{"key": "greeting", "your_fix": "Servus %1$s"}])
        result = etr.import_sheet(self.sheet, res=self.res, dry_run=True)
        self.assertEqual(result.applied, ["greeting"])
        self.assertEqual(self.target(), LOCALE_XML)

    def test_locale_comes_from_the_folder(self) -> None:
        sheet = self.root / "elsewhere" / "de" / "renamed.csv"
        write_sheet(sheet, [{"key": "greeting", "your_fix": "Moin %1$s"}])
        self.assertEqual(etr.import_sheet(sheet, res=self.res).applied, ["greeting"])

    def test_translate_sheet_column_is_read(self) -> None:
        sheet = self.root / "review" / "de" / etr.TRANSLATE_FILE
        sheet.parent.mkdir(parents=True, exist_ok=True)
        with sheet.open("w", encoding="utf-8-sig", newline="") as fh:
            dw = csv.DictWriter(fh, fieldnames=etr.TRANSLATE_COLUMNS)
            dw.writeheader()
            dw.writerow({"english": "Clipboard", "your_translation": "Zwischenablage", "key": "untouched"})
            dw.writerow({"english": "Hello %1$s", "your_translation": "", "key": "greeting"})  # empty: keep
        result = etr.import_sheet(sheet, res=self.res)
        self.assertEqual((result.applied, result.refused), (["untouched"], []))
        self.assertIn('<string name="untouched">Zwischenablage</string>', self.target())

    def test_first_format_sheet_still_imports(self) -> None:
        sheet = self.root / "renamed.csv"
        write_old_sheet(sheet, [{"key": "greeting", "reviewer_correction": "Moin %1$s"}], locale="de")
        self.assertEqual(etr.import_sheet(sheet, res=self.res).applied, ["greeting"])


class ExportImportRoundTrip(unittest.TestCase):
    def read(self, path: Path) -> list[dict[str, str]]:
        with path.open(encoding="utf-8-sig", newline="") as fh:
            return list(csv.DictReader(fh))

    def test_rows_split_into_three_sheets_and_reimport_as_a_no_op(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            res = write_res(root)
            out = root / "review"
            english = etr.parse_resources(ENGLISH_XML)
            flags = {"de": {"greeting": "Is 'Hallo' too casual here?"}}
            with mock.patch.object(etr, "read_at", return_value=None):
                counts = etr.export_scoped("de", ["greeting", "apostrophe", "untouched", "items"], english,
                                           "0" * 40, "2026-09-29", {}, {}, flags, {"locales": {}}, out, res,
                                           keep_english=set())
            # 3 strings + 3 de plural items, plus `percent` and `wrapped`: outside the English scope,
            # but read_at() finds no base version of the locale file, so their translation is "new".
            self.assertEqual((counts.translate, counts.check, counts.read), (1, 1, 6))
            folder = out / "de"
            translate = self.read(folder / etr.TRANSLATE_FILE)
            check = self.read(folder / etr.CHECK_FILE)
            read = self.read(folder / etr.READ_FILE)
            # No comment lines: the first line is the header, and the English text comes first.
            for name, columns in ((etr.TRANSLATE_FILE, etr.TRANSLATE_COLUMNS), (etr.CHECK_FILE, etr.CHECK_COLUMNS),
                                  (etr.READ_FILE, etr.READ_COLUMNS)):
                first = (folder / name).read_text(encoding="utf-8-sig").splitlines()[0]
                self.assertEqual(first, ",".join(columns))
                self.assertEqual(columns[0], "english")
            self.assertEqual([r["key"] for r in translate], ["untouched"])      # still English
            self.assertEqual([r["key"] for r in check], ["greeting"])           # has an open question
            self.assertIn("too casual", check[0]["why_check"])
            keys = [r["key"] for r in read]
            self.assertEqual([k for k in keys if k.startswith("items")], ["items#one", "items#few", "items#other"])
            self.assertEqual(len(keys + ["untouched", "greeting"]), len(set(keys + ["untouched", "greeting"])))
            percent = next(r for r in read if r["key"] == "percent")
            self.assertIn("translation was edited after", percent["notes"])
            self.assertIn("literal percent", percent["notes"])
            # A keepEnglish key that is identical to English is not "to translate".
            with mock.patch.object(etr, "read_at", return_value=None):
                kept = etr.export_scoped("de", ["untouched"], english, "0" * 40, "2026-09-29", {}, {}, {},
                                         {"locales": {}}, root / "kept", res, keep_english={"untouched"})
            self.assertEqual(kept.translate, 0)
            self.assertFalse((root / "kept" / "de" / etr.TRANSLATE_FILE).exists())
            # Copying every current text into the reviewer's cell changes nothing.
            for sheet, rows, column in ((etr.CHECK_FILE, check, "your_fix"), (etr.READ_FILE, read, "your_fix")):
                for r in rows:
                    r[column] = r["translation"]
                write_sheet(folder / sheet, rows)
                result = etr.import_sheet(folder / sheet, res=res)
                self.assertEqual((result.applied, result.refused), ([], []))
            self.assertEqual((res / "values-de" / "strings.xml").read_text(encoding="utf-8"), LOCALE_XML)


if __name__ == "__main__":
    unittest.main(verbosity=2)
