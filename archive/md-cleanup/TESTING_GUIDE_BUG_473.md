# Testing Guide: Bug #473 - Tabbed Dictionary Manager

**APK Version**: tribixbite.keyboard2.debug.apk (52MB)
**Build Date**: November 16, 2025
**Installation**: Triggered via termux-open

---

## 🧪 Quick Test Checklist

### Step 1: Install the APK
- Installation dialog should appear on your device
- Accept the installation prompt
- Wait for "App installed" confirmation

### Step 2: Access Dictionary Manager
1. Open **CleverKeys Settings** app
2. Tap **"Dictionary Manager"** option
3. You should see **3 TABS** at the top:
   - **User Words** | **Built-in (10k)** | **Disabled**

### Step 3: Test Tab 1 - User Words
- [ ] **Search**: Type in search field → words filter instantly
- [ ] **Add Word**: Tap FAB (+) button → Add custom word (e.g., "TestWord123")
- [ ] **Delete Word**: Tap trash icon on any word → Word removed
- [ ] **Empty State**: If no words, see "No Custom Words" message
- [ ] **Word Count**: Bottom shows count (e.g., "5 words")

### Step 4: Test Tab 2 - Built-in Dictionary
- [ ] **Load Dictionary**: Tab opens → Should load ~10,000 words
- [ ] **Search**: Type "hello" → Filters to matching words
- [ ] **Word Count**: Shows "Showing X of 9,999 words"
- [ ] **Rank Display**: Each word shows "Rank #1", "Rank #2", etc.
- [ ] **Disable Word**: Tap "Disable" button on any word
  - Word card turns RED (error container color)
  - "Disable" button disappears
  - Toast: "Disabled \"word\" from predictions"

### Step 5: Test Tab 3 - Disabled Words
- [ ] **View Disabled**: After disabling words in Tab 2, they appear here
- [ ] **Search**: Type word → Filters disabled words
- [ ] **Word Count**: Shows "X words disabled"
- [ ] **Enable Word**: Tap "Enable" button → Word restored
  - Toast: "Enabled \"word\" in predictions"
  - Word disappears from Tab 3
- [ ] **Clear All**: Tap "Clear All" button (top right)
  - All disabled words removed
  - Toast: "All disabled words cleared"
- [ ] **Empty State**: If no disabled words, see message

### Step 6: Test Prediction Integration
**CRITICAL TEST**: Verify disabled words don't appear in keyboard suggestions

1. **Enable keyboard**:
   - Settings → System → Languages & input → On-screen keyboard
   - Enable "CleverKeys"
   - Set as default

2. **Disable a common word**:
   - Open Dictionary Manager → Tab 2 (Built-in)
   - Search for "the"
   - Tap "Disable" on "the"

3. **Test in text field**:
   - Open any app (Messages, Notes, etc.)
   - Activate CleverKeys keyboard
   - Type "th" → **"the" should NOT appear in predictions**
   - This confirms blacklist integration works!

4. **Re-enable word**:
   - Open Dictionary Manager → Tab 3 (Disabled)
   - Find "the"
   - Tap "Enable"
   - Type "th" again → "the" should now appear ✅

---

## 🎯 Expected Behavior

### Tab Navigation
- Smooth tab switching with no lag
- FAB (+) only visible on User Words tab
- Each tab has independent search state

### Search Functionality
- **Real-time filtering** as you type
- **Case-insensitive** matching
- **"No results"** message when filter returns empty

### UI/UX
- Material 3 design (dark theme)
- Red background for disabled words
- Toast notifications for all actions
- Empty states with helpful messages

### Performance
- 10k words load instantly (< 1 second)
- LazyColumn scrolls smoothly
- Search filters instantly
- No UI blocking or freezing

---

## 🐛 Known Issues (Report if you encounter)

### Potential Issues to Watch For:
- [ ] Dictionary fails to load (stuck on loading spinner)
- [ ] Search doesn't filter results
- [ ] Disabled words still appear in predictions
- [ ] App crashes when switching tabs
- [ ] Toast messages don't appear
- [ ] FAB doesn't work

### If You Find Issues:
1. Check logcat: `adb logcat | grep CleverKeys`
2. Note the exact steps to reproduce
3. Check if it's consistent or intermittent

---

## 📊 Testing Results Template

**Device**: [Your device name]
**Android Version**: [e.g., Android 13]
**Date**: [Test date]

### Tab 1 - User Words
- [ ] Search: ✅ / ❌
- [ ] Add word: ✅ / ❌
- [ ] Delete word: ✅ / ❌
- [ ] Empty state: ✅ / ❌

### Tab 2 - Built-in Dictionary
- [ ] Load 10k words: ✅ / ❌
- [ ] Search: ✅ / ❌
- [ ] Disable word: ✅ / ❌
- [ ] Visual feedback: ✅ / ❌

### Tab 3 - Disabled Words
- [ ] List disabled words: ✅ / ❌
- [ ] Enable word: ✅ / ❌
- [ ] Clear all: ✅ / ❌
- [ ] Empty state: ✅ / ❌

### Integration Test
- [ ] Disabled words DON'T appear in predictions: ✅ / ❌
- [ ] Re-enabled words DO appear in predictions: ✅ / ❌

**Overall Assessment**: ✅ PASS / ❌ FAIL
**Notes**: [Any observations or issues]

---

## 🔍 Detailed Verification Steps

### Verify Built-in Dictionary Loading
**Expected**: 9,999 words from `assets/dictionaries/en.txt`

1. Open Tab 2 (Built-in)
2. Wait for loading to complete
3. Check word count at bottom
4. Verify shows "9999 words" (or close to it)
5. Scroll through list → should see thousands of words

### Verify Search Performance
**Expected**: Instant filtering with no lag

1. Tab 2 (Built-in) → Type "test"
2. Results should filter INSTANTLY as you type
3. Word count updates: "Showing X of 9,999 words"
4. Clear search → all words reappear

### Verify Persistence
**Expected**: Disabled words persist across app restarts

1. Disable 3-5 words in Tab 2
2. Close Dictionary Manager completely
3. Force-stop CleverKeys app (Settings → Apps → CleverKeys → Force Stop)
4. Re-open Dictionary Manager
5. Tab 3 → All disabled words should still be there

### Verify StateFlow Reactivity
**Expected**: Changes in one tab immediately reflect in others

1. Tab 2 → Disable word "example"
2. Switch to Tab 3 → "example" immediately appears
3. Tab 3 → Enable "example"
4. Switch to Tab 2 → "example" no longer has red background

---

## ✅ Success Criteria

All features work as expected:
- ✅ 3 tabs navigable
- ✅ Search works in all tabs
- ✅ User words: add/delete
- ✅ Built-in dictionary: browse 10k words
- ✅ Disabled words: disable/enable/clear
- ✅ Prediction integration: blacklist works
- ✅ Persistence: disabled words survive app restart
- ✅ Performance: smooth with 10k words
- ✅ UI: Material 3, proper theming, toast messages

---

## 📝 Next Steps After Testing

### If All Tests Pass:
1. Use keyboard normally for a few days
2. Customize word blacklist as needed
3. Report any UX improvements

### If Issues Found:
1. Document the issue (steps to reproduce)
2. Check logcat for error messages
3. Report findings

---

**Testing Guide Complete**
**Ready for Device Testing**: ✅
**APK Installed**: Check device notification
