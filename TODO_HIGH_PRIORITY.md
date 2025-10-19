# HIGH Priority Bugs TODO

**Priority: URGENT - Should fix soon**

## Clipboard & History
- [ ] **Bug #114**: Missing AttributeSet constructor parameter
  - File: ClipboardHistoryView.kt
  - Impact: Custom view attributes broken

- [ ] **Bug #115**: Missing adapter pattern
  - File: ClipboardHistoryView.kt
  - Impact: No data binding

- [ ] **Bug #118**: Broken pin functionality
  - File: ClipboardPinView.kt
  - Impact: Cannot pin clipboard items

- [ ] **Bug #120**: Missing paste functionality
  - File: ClipboardPinView.kt
  - Impact: Cannot paste from pinned items

- [ ] **Bug #122**: Missing update_data() implementation
  - File: ClipboardHistoryCheckBox.kt
  - Impact: UI doesn't update

- [ ] **Bug #123**: Missing lifecycle hook
  - File: ClipboardHistoryCheckBox.kt
  - Impact: Memory leaks possible

- [ ] **Bug #126**: Missing callback-based notification support
  - File: ClipboardHistoryService.kt
  - Impact: UI can't react to changes

- [ ] **Bug #127**: Inconsistent API naming breaks all call sites
  - File: ClipboardHistoryService.kt
  - Impact: All existing code broken

## ML & Training
- [ ] **Bug #270**: addRawPoint() incorrect time delta calculation
  - File: SwipeMLData.kt
  - Impact: Training data timestamps wrong

- [ ] **Bug #271**: addRegisteredKey() doesn't avoid consecutive duplicates
  - File: SwipeMLData.kt
  - Impact: Noisy training data

- [ ] **Bug #277**: Multi-language support missing
  - Impact: Only English supported

## Voice Input
- [ ] **Bug #264**: VoiceImeSwitcher doesn't actually switch to voice IME
  - File: VoiceImeSwitcher.kt (File 68)
  - Impact: Launches speech recognizer instead of switching IME
  - Needs: Proper IME switching implementation

**Total High Priority: 12 bugs**

See REVIEW_COMPLETED.md for detailed analysis of each bug.
