# CleverKeys Issue Action Plan — Persistent Context

> This file preserves the full action plan across context boundaries.
> Check this file at session start to resume work.

## Status Summary (2026-02-11)

### Tests Written This Session
- **IssueRegressionTest.kt** — 57 pure JVM tests (all open issue config defaults)
- **TerminalUtilsTest.kt** — 28 MockK tests (#70 terminal app detection)
- **KeyRepeatLogicTest.kt** — 22 MockK tests (#81 backspace-only repeat logic)
- **AutoSpaceLogicTest.kt** — 25 pure JVM tests (#82 auto-space decision logic)
- Total: 132 new tests this session

### Test Counts
- Pure JVM: 781
- MockK: 176
- Instrumented: ~604
- **Total: ~1,561**

### Fixes Applied
- **#51** — KEYBOARD_OPACITY 81→100 (Config.kt:24)
- **#96** — WordListFragment.refresh() preserves search state (WordListFragment.kt:153-155,248,270,381-383)
- **#50** — Swedish language detection patterns (LanguageDetector.kt:42-48)
- **#50** — Fixed README.md and wiki docs (missing --name argument)
- **#48** — Removed password mode block on inline autofill (SuggestionBar.kt:507-510)
- **#48** — Added supportsInlineSuggestions="true" to method.xml
- **#48** — Added error logging for null inflate in InlineAutofillUtils.kt

---

## Issue Status

### Completed (close with verification)
| Issue | Title | Status | Notes |
|-------|-------|--------|-------|
| #21 | Subkey system | Implemented v1.2.0 | Close |
| #50 | Swedish language | Fixed detection + docs | Needs markdown reply to user |
| #51 | Keyboard opacity | Fixed default 81→100 | Close |
| #62 | Password manager clipboard | 27 packages listed | Close |
| #70 | Termux mode | TERMUX_MODE_ENABLED=true, 28 tests | Close |
| #74 | Haptic feedback | Per-event controls, VibratorCompatTest 11 tests | Close |
| #81 | Key repeat backspace-only | Config exists, 22 logic tests | Close |
| #82 | Auto-space after suggestion | Config exists, 25 logic tests | Close |
| #96 | Word list search reset | Fixed refresh() to preserve search state | Close |

### In Progress
| Issue | Title | Status | Next Steps |
|-------|-------|--------|------------|
| #48 | Inline autofill | 3 fixes applied, untested on device | Build & test with Safe/Bitwarden on Android 15 |

### Phase 3 — Low-Effort Fixes (1 session)
| Issue | Title | Effort | Notes |
|-------|-------|--------|-------|
| #99 | Calibration tutorial docs | Low | Wiki update only, no code |
| #35 | Autocorrect "I"→"o" false positive | Medium | AUTOCORRECT_CHAR_MATCH_THRESHOLD tuning (0.67) |
| #67 | Clipboard history "not working" | Low | Verify UI labels, add first-run hint |
| #59 | Clipboard date format | Low | ASK before changing format (UI change) |

### Phase 4 — Medium-Effort Bug Fixes (ASK before each)
| Issue | Title | Risk | Root Cause |
|-------|-------|------|------------|
| #92 | Custom theme background | Low | keyboardBackground not applied programmatically |
| #77 | Greek/Math key stays visible | Low | LayoutModifier missing SWITCH_GREEKMATH case |
| #30 | Custom short swipe actions | Medium | CustomShortSwipeExecutor missing keyboard events |
| #55 | Suggestion bar positioning | Medium | Device-specific padding/margin issue |
| #79 | Autocapitalization broken | Medium | EditorInfo.inputType detection issue |
| #83 | Clipboard pane overlapping | Low | CLIPBOARD_PANE_HEIGHT_PERCENT=30 sizing |

### Phase 5 — Higher-Effort Features (multi-session, ASK before each)
| Issue | Title | Notes |
|-------|-------|-------|
| #94 | Theme preview in settings | UI feature |
| #93 | Custom key sounds | Audio feature |
| #84 | Floating/split keyboard | Architecture change |
| #72 | Auto-capitalize "I" edge cases | Mid-sentence detection |
| #87 | Per-language keyboard height | Config extension |
| #52 | Swipe trail visual effects | UI polish |
| #98 | Hardware keyboard passthrough | Input method |
| #97 | Prediction bar gestures | UI gestures |
| #88 | Emoji search improvements | Search algorithm |
| #58 | Touch target per finger size | Calibration |
| #68 | One-handed mode | Layout mode |
| #49 | Dictionary import/export | Data format |

### Phase 6 — Architecture (deferred)
| Issue | Title | Risk |
|-------|-------|------|
| #78 | Shift key unreliable rapid typing | Touch event timing, latency risk |
| #75 | Landscape layout wrong on rotation | Lifecycle/config change handling |

### Excluded (per user directive)
- #89 (Play Store), #90 (custom size UI), #80 (clipboard strip),
  #69 (two-finger swiping), #61 (multi-language simultaneous), #31 (Cyrillic)

---

## Key Files Reference

### Test Files
| File | Tests | Runner |
|------|-------|--------|
| IssueRegressionTest.kt | 57 | Pure JVM |
| TerminalUtilsTest.kt | 28 | MockK |
| KeyRepeatLogicTest.kt | 22 | MockK |
| AutoSpaceLogicTest.kt | 25 | Pure JVM |
| VibratorCompatTest.kt | 11 | MockK |
| ConfigDefaultsTest.kt | ~30 | Pure JVM |

### Source Files Modified
| File | Change | Issue |
|------|--------|-------|
| Config.kt:24 | KEYBOARD_OPACITY 81→100 | #51 |
| WordListFragment.kt:153-155 | currentSearchQuery field | #96 |
| LanguageDetector.kt:42-48 | Swedish patterns | #50 |
| SuggestionBar.kt:507-510 | Removed password mode block | #48 |
| method.xml:2 | supportsInlineSuggestions="true" | #48 |
| InlineAutofillUtils.kt:142-155 | Inflate error logging | #48 |
| README.md:278 | Added --name to langpack command | #50 |
| wiki/layouts/language-packs.md:79 | Added --name to langpack command | #50 |

---

## #50 Reply Draft (for mawkler)

The user's errors:
1. Missing `--name` argument (now fixed in docs)
2. Used `--dict dictionaries/langpack-sv.zip` (wrong: needs .bin file, not .zip)
3. `compute_prefix_boosts.py` couldn't find `sv_enhanced.bin` in assets
4. The Swedish dictionary already ships in the repo at `src/main/assets/dictionaries/sv_enhanced.bin`

Reply: See below in "Pending Actions" section.

---

## Pending Actions

1. **Commit current changes** — #48 fix + test files + docs
2. **Draft #50 reply** — markdown response to mawkler's issue comment
3. **Build & test #48** on device with password manager
4. **Phase 3 work** — #99, #35, #67, #59
5. **Phase 4 work** — #92, #77, #30, #55, #79, #83 (ASK before each)
