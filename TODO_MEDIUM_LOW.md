# MEDIUM & LOW Priority Bugs TODO

**Priority: NORMAL - Fix when convenient**

## MEDIUM Priority

### Configuration
- [ ] **Bug #75**: CharKey flags hardcoded to emptySet()
  - File: ComposeKey.kt
  - Impact: Some compose key flags lost

- [ ] **Bug #80**: TRIGGER_CHARACTERS expanded beyond Java
  - File: ComposeKeyData.kt
  - Impact: Functionality change (may be intentional)

### UI
- [ ] **Bug #116**: Hardcoded header text
  - File: ClipboardPinView.kt
  - Impact: No i18n support

- [ ] **Bug #117**: Hardcoded button text
  - File: ClipboardPinView.kt
  - Impact: No i18n support

- [ ] **Bug #119**: Hardcoded emoji icons
  - File: ClipboardPinView.kt
  - Impact: No customization

- [ ] **Bug #121**: Hardcoded toast message
  - File: ClipboardHistoryCheckBox.kt
  - Impact: No i18n support

- [ ] **Bug #128**: Blocking initialization in lazy property
  - File: ClipboardHistoryService.kt
  - Impact: Potential ANR (Application Not Responding)

## LOW Priority

### Code Quality
- [ ] **Bug #77**: LegacyComposeSystem - 90 lines of UNUSED dead code
  - File: ComposeKey.kt
  - Impact: Code bloat only

- [ ] **Bug #129**: Different method name - clear_expired_and_get_history
  - File: ClipboardHistoryService.kt
  - Impact: API naming inconsistency

- [ ] **Bug #130**: Interface moved from inner to top-level
  - File: ClipboardHistoryService.kt
  - Impact: Minor architectural difference

- [ ] **Bug #272**: TracePoint comment incorrect
  - File: SwipeMLData.kt
  - Impact: Documentation only

**Total Medium/Low: 12 bugs**

See REVIEW_COMPLETED.md for detailed analysis of each bug.
