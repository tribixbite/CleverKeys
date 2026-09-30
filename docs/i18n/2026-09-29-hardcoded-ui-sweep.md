# Hardcoded UI string sweep — 2026-09-29

Machine work (model implementers and model translators, reviewer = model). No string here has
been reviewed by a native speaker.

## Why

An on-device test on the Seeker phone with the app locale set to fa and to hu found English
text on translated screens:

- the launcher setup cards ("Configure up to 8 subkey actions per key", "✓ Done");
- the language-pack section ("Imported: <name> (<n> words)", "None");
- the keyboard's language-toggle message ("Secondary: None").

All 21 locales had every string resource translated. These texts were never resources.
Android lint's `HardcodedText` covers XML layouts and `SetTextI18n` covers `TextView.setText`.
Neither sees Compose `Text("…")`, named Compose parameters, toasts, dialogs built in Kotlin,
dropdown option lists, or manifest labels.

## Guard

`src/test/kotlin/tribixbite/cleverkeys/HardcodedUiStringTest.kt` is a pure-JVM source scan
that runs in `runPureTests`.

- **Prose literals.** It flags a string literal that reads as prose when it sits in a rendering
  position. A literal counts as prose when its first letter is an upper-case letter followed by
  a lower-case letter, or a run of capitals followed by a space, or it is exactly "OK".
- **Rendering positions:**
  - `Text(…)`;
  - named display parameters (`text`, `title`, `description`, `contentDescription`, `hint`,
    `placeholder`, `displayName`, result-dialog fields and similar);
  - `Toast.makeText`;
  - `AlertDialog` builder calls;
  - suggestion-bar and status-message calls;
  - `options = listOf(…)`;
  - positional `Icon(…)` descriptions;
  - `append`/`appendLine` in UI code;
  - `when` branches that return labels in UI packages.
- **What it ignores.** Comments are blanked first. Identifiers, pref values ("BALANCED"), MIME
  types and date patterns do not match.
- **Manifest labels.** A second check requires every `android:label` in `AndroidManifest.xml`
  to be a resource.

**Fail-first evidence:** before the sweep the scan reported **439 literals in 44 files**. After
the sweep it reports **0**, and the manifest check went from 11 hardcoded labels to 0.

### Allowlisted exceptions (`HardcodedUiStringTest.allowed`)

| File | Literal | Reason |
|---|---|---|
| activities/LauncherActivity.kt | CleverKeys | Brand wordmark. `app_name` is `translatable="false"`. |
| clipboard/ClipboardMediaManager.kt | WebP, WebM | File-format proper nouns. |
| prefs/ExtraKeysPreference.kt | Esc, Fn | Key-cap abbreviations printed the same on every keyboard. |

## What moved

The sweep added 502 strings and 41 plurals, all translated into the 21 locales. Counts
per area:

| Area | Strings | Plurals | Main files |
|---|---|---|---|
| core | 18 | 1 | `Keyboard2View` (text-menu and language-toggle messages, `TextActionPolicy` names and chooser titles), language-pack handlers, `MultiLanguageSection` |
| settings | 71 | 8 | `ui/settings/**` sections, dialogs, FAQ, reset presets |
| io | 120 | 23 | `ui/settings/io/**` result dialogs and toasts, `BackupRestoreResultMessages`, `BackupRestorePreviewDialogs`, `BackupRestoreActivity` |
| activities | 128 | 2 | Launcher, dictionary manager, `WordListFragment`, layout manager, extra keys, short-swipe customization and calibration, theme settings, swipe playground, manifest labels |
| custom | 130 | 4 | `ActionType`, `CustomShortSwipeExecutor`, intent presets, predefined themes, theme dialogs, extra-key names |
| ime | 35 | 3 | GIF and clipboard messages, clipboard relative times, private-copy feedback, QS tile, `IMEStatusHelper`, TalkBack keycode label, performance summary |

Shared `common_*` strings were added first so the areas reuse them instead of duplicating them:
`common_file_picker_failed`, `common_import_failed_detail`, `common_export_failed_detail`,
`common_delete_failed_detail`, `common_remove_failed_detail`, `common_copied_to_clipboard`,
`common_none`, `common_unknown_error` and `common_file_name`.

### Structural changes that came with it

- **Stable stored values.** Dropdowns now keep a value list and a display list apart. The pref
  values stay English identifiers ("sparkle", `both`, `CONSERVATIVE`, `no_symbols`, …).
  `SwipeSensitivityPreset` replaced the comparison against English "Low"/"Medium"/"High".
- **Typed statuses.** `LanguagePackImportStatus` (Imported/Failed) replaced
  `status.startsWith("Error")`, the same fix `GifImportStatus` already had.
- **Language names.** `LanguageDisplayNames` replaced two English tables. It uses the CLDR name
  in the UI language plus the language's own name, e.g. "Spanyol (Español)" under hu.
- **Pure-JVM producers take resource ids or a resolver.** Examples: `TextActionPolicy`,
  `BackupRestoreResultMessages` (`ResultText`), `PrivateCopyDispatch.Outcome`, clipboard
  `RelativeTime` and `KeyboardTileService`. The tests pin the English wording by reading
  `res/values` (`EnglishResourceText`, `TranslationResources`).
- **Counts use `<plurals>`.** This removed the "(s)" forms and the
  `if (n == 1) "entry was" else "entries were"` branches.
- **One string per sentence.** Sentences are no longer assembled from fragments: "Mapped %1$s →
  "%2$s" (%3$s)" is a single resource.

## Translation notes

Eleven model translators produced the 21 locales. They worked from English plus a
per-key note describing where the text is shown and what each argument is. Every file was
checked for the exact key set, identical placeholders, the locale's plural quantity set, a
count in every plural item, and the locale's register rules.

**Persian data-change arrows.** "→" is not bidi-mirrored. In an RTL paragraph, digits and
Persian words make the bidi algorithm reverse "a → b", so the arrow points at the old value;
Latin words do not reverse. Value-change expressions in fa are therefore wrapped in a Unicode
LTR isolate (U+2066…U+2069) and keep "→". The same fix went into four existing fa strings in
f4c1418b. Menu-path breadcrumbs use "←" in fa.

**Other notes.**
- The FAQ typo "useable output-" was fixed in the English source.
- Translators asked for review of these points, which are recorded for a native reviewer:
  - short compass abbreviations for short-swipe directions (fa, uk, fil, in);
  - diacritic names on extra keys;
  - the "Swipe Playground" screen name;
  - Android system-settings path names in `ime_default_prompt_toast`.

## Deferred (TODO(i18n) in code) — status as of 2026-09-29

Resolved on 2026-09-30 except where noted; see "Resolution" below.

- **Command catalog.** `customization/CommandRegistry.kt` holds about 228 command display names,
  descriptions, search keywords and category names. It also drives command-palette search
  ranking, so localizing it is its own job.
- **Settings search.** The generated index (`scripts/generate_settings_search_index.py`) takes
  its titles from `res/values` in English. Scroll targets are `settingSlug(title)` of the
  control's *visible* title, which is ASCII-only (`[^a-z0-9]` becomes `_`). Under a non-Latin
  UI locale, and partly under any non-English one, the controls register their scroll
  positions under slugs that no search entry uses. Search results then open the right section
  but do not scroll to the control. This needs locale-independent setting ids and
  resource-backed titles.
- **Domain-layer error details** are still English, shown inside a localized wrapper such as
  "Import failed: %1$s". They come from `LanguagePackManager`/`GifPackManager`
  `ImportResult.Error`, `BackupRestoreManager` and `backup.crypto` exceptions,
  `SkippedKey.reason` and `BackupRestoreManager.WRONG_PASSWORD_OR_CORRUPT`.
- **Other English identifiers.** Combining-diacritic extra-key titles (Arabic/Hebrew marks) are
  derived from the key id. `AvailableCommand` metadata is not rendered anywhere.
- **In-keyboard pager glyphs.** The ◀/▶ arrows in the GIF and clipboard panes
  (`glyph_page_prev/next`, IME layouts) are not mirrored. The keyboard surface's layout
  direction was not changed in this round.
- **Stale FAQ content.** The swipe-typing FAQ answers mention "Length Penalty (Alpha)",
  "Vocab Frequency Weight" and "Prefix Boost", which appear to be settings of the removed
  neural engine. The text was moved verbatim, and it needs a content fix.

## Resolution (2026-09-30)

Model work again (implementer and translators = model, reviewer = model). One commit per item.

| Item | Commit | What changed | Guard (fail-first) |
|---|---|---|---|
| Stale FAQ | `721c757d` | The swipe-typing answer points at Swipe Typing → Prediction Engine (CTC or Geometric); the other-languages answer explains language packs and the geometric fallback; the numbers answer names the real card, "Customize Per-Key Actions". All 21 locales use their own section titles. | `FaqContentDriftTest`: no FAQ may name a removed neural setting (hit en + all 21 locales before), and every English "Settings → A → B" segment must be a real title. |
| Pane pager arrows | `213e8d52` | `PanePagerArrows` picks ◀/▶ per button from the configuration's layout direction, so under RTL "previous" (laid out on the right) points right. GIF and clipboard panes bind through it. | `RtlMirroringDriftTest`: the swap, and every layout view carrying a page glyph is bound through `PanePagerArrows.apply` (4 unbound before). |
| Domain error text | `cfc0eed2` | `IoFailureReason` + `IoFailureClassifier` (not found, permission, invalid format, newer version, out of space, too large, wrong password or tampered, no password, wrong backup kind, no file picker, read/write, unknown); typed throw sites (`ClassifiedIoException`, `BackupFormatException`/`BackupDecryptException` carry a reason); `PackImportFailure` for language/GIF packs; `SkipKind` for the settings-import preview. Raw text is logged only. Encrypted imports prompt for a password only when it is missing or wrong. 26 strings. | `IoFailureLocalizationTest` (classification, rendering, all locales, placeholders; source scan: no `getString(R.string.x, …e.message)` in `ui/settings/io` — 30 `e.message` uses there before, the only user-visible ones left are the two clipboard custom-rules parser details, which name the user's own rule). |
| Command catalog | `19d64857` | 222 names + 222 descriptions + 21 category headers → `cmd_<id>`, `cmd_<id>_desc`, `command_category_<cat>` (465 strings × 21 locales). Search/ranking match the UI language, then English, then the English keywords. Command ids unchanged. | `CommandCatalogLocalizationTest`: names derived from the stable ids, every string in every locale, < 25% English copies per locale, localized-name search ranks the command first. `TranslationLengthTest` treats palette rows like titles. |
| Settings search | `607da6df` | The index carries `titleRes` and a locale-independent `settingId` (the title's resource name); controls register under the ids of their visible title. Results show and match the UI-language title, then English title and keywords; matching folds accents, ZWNJ and Arabic-keyboard yeh/kaf. Hand entries and section names use the screens' own resources; "in %1$s" is a resource. | `SettingsSearchLocalizationTest`: every entry resolves by id in every locale; the old slug keys diverged for > 90% of fa entries; hu/fa queries find localized titles. `SettingsSearchCoverageTest` pins the advanced-panel ids. |

### Translator notes for the native review (command catalog)

- Terms were reused from each locale's `key_descr_*`, `extra_key_*` and `text_action_*` strings
  where they existed; where the app itself is split, the majority form was used.
- ru: "dead key" aligned to the app's «Немая клавиша»; Church Slavonic mark names (Звательце,
  Дасия, Покрытие, Взмет, Паерок, Кавыка) need a liturgical-typography check.
- pl: descriptions use 2sg imperatives, matching the existing pl `key_descr_*` style.
- tr: the app's existing `key_descr_dead_key` "Boş tuş" ("empty key") looks wrong; the catalog
  uses "ölü tuş".
- hu: the app's own accent labels "Áthúzás"/"Vízszintes áthúzás" contain the glossary-forbidden
  swipe stem "húzás"; the catalog uses "Ferde vonal"/"Vízszintes vonal".
- in: Home/End/Page Up/Page Down kept Latin; the app's "Beranda"/"Halaman atas" read as
  mistranslations.
- zh-rCN: caron 抑扬符 vs circumflex 扬抑符 are easy to confuse; Slavonic marks rendered by
  meaning.
- fil follows the app's Taglish style; many key names and some descriptions stay English.
- The long-date hint "(Day, Month DD, YYYY)" was translated around the DD/YYYY tokens in most
  locales; the actual output pattern is fixed (`EEEE, MMMM d, yyyy`).

### Still open

- The clipboard custom-rules status shows the rules parser's English detail (TODO(i18n) in
  `SettingsClipboardHandlers.kt`); it names the rule in the user's own JSON.
- Combining-diacritic extra-key titles (`ExtraKeysPreference`) and the unrendered
  `AvailableCommand` metadata are unchanged.
- The headless (automation) backup path's result messages are unchanged English.
- Native review of every locale and a device check of fa (pane arrows, FAQ, search scroll) and
  hu (search) are still owed. Review sheets per locale: `scripts/export_translation_review.py`;
  the open questions above live in `docs/i18n/review-flags.json`.
- 2026-09-30: the tr/hu/in app strings the catalog translators flagged now use the catalog's terms
  ("Ölü tuş"; "Ferde vonal"/"Vízszintes vonal"; Latin Home/End/Page Up/Page Down). Still flagged
  for the reviewer.

