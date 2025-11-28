# Missing Asset Files - Future Work

**Status**: Non-blocking - Keyboard functions without these assets
**Impact**: Reduced prediction accuracy until assets are created

---

## Required Asset Files

### 1. Dictionary Files
**Location**: `src/main/assets/dictionaries/`

**Files Needed**:
- `en_enhanced.json` - English dictionary with word frequencies (50k+ words)
- `es_enhanced.json` - Spanish dictionary
- `fr_enhanced.json` - French dictionary
- `de_enhanced.json` - German dictionary
- etc.

**Format** (JSON):
```json
{
  "the": 255,
  "and": 254,
  "for": 253,
  "you": 252,
  "that": 251,
  ...
}
```

**Alternative Format** (TXT):
```
the
and
for
you
that
...
```

**Current Behavior Without Assets**:
- WordPredictor loads with empty dictionary
- Logs: "WordPredictor dictionary loading failed"
- Predictions still work via custom words and user dictionary
- No frequency-based ranking

---

### 2. Bigram Files
**Location**: `src/main/assets/bigrams/`

**Files Needed**:
- `en_bigrams.json` - English bigram probabilities
- `es_bigrams.json` - Spanish bigrams
- `fr_bigrams.json` - French bigrams
- `de_bigrams.json` - German bigrams
- etc.

**Format** (JSON):
```json
{
  "the house": 0.85,
  "the car": 0.92,
  "the book": 0.78,
  "for you": 0.65,
  ...
}
```

**Current Behavior Without Assets**:
- BigramModel loads with empty probabilities
- Logs: "BigramModel asset loading failed"
- Context-aware predictions fallback to unigram
- No bigram boost for common word pairs

---

## Graceful Degradation

The code is designed to handle missing assets gracefully:

### WordPredictor (WordPredictor.kt:236-289)
```kotlin
try {
    val reader = BufferedReader(InputStreamReader(context.assets.open(jsonFilename)))
    // Load dictionary...
    loaded = true
} catch (e: Exception) {
    Log.w(TAG, "JSON dictionary not found, trying text format: ${e.message}")
}

if (!loaded) {
    try {
        // Try text format...
    } catch (e: IOException) {
        Log.e(TAG, "Failed to load dictionary: ${e.message}")
    }
}

// Always load custom words and user dictionary (works without assets)
loadCustomAndUserWords()
```

### BigramModel (BigramModel.kt:90-116)
```kotlin
try {
    val reader = BufferedReader(InputStreamReader(context.assets.open(filename)))
    // Load bigrams...
    Log.d(TAG, "Loaded bigram model for $language: ${bigramProbs.size} entries")
} catch (e: Exception) {
    Log.w(TAG, "Failed to load bigram data for $language: ${e.message}")
}
```

**Result**:
- No crashes
- No exceptions thrown to user
- Warning logs for debugging
- Keyboard continues to function

---

## Current Prediction Sources (Without Assets)

Even without dictionary/bigram assets, predictions still work via:

1. **Custom Words** - User-defined shortcuts and expansions
2. **User Dictionary** - Android's built-in user dictionary
3. **Recent Words** - Last 5 typed words for context
4. **User Adaptation** - Frequently selected words (SharedPreferences)
5. **Prefix Matching** - Live filtering of available words

**Prediction Quality**:
- Good for frequently typed words
- Good for user-added custom words
- Limited for uncommon words
- No frequency-based ranking
- No bigram context boost

---

## How to Create Asset Files

### Option 1: Use Existing Dictionary Data
Download from open sources:
- [WordFreq](https://github.com/rspeer/wordfreq) - Frequency lists for many languages
- [Google Books Ngrams](http://storage.googleapis.com/books/ngrams/books/datasetsv2.html)
- [SUBTLEX](http://crr.ugent.be/programs-data/subtitle-frequencies) - Subtitle-based frequencies

### Option 2: Generate from Corpus
```python
# Example: Generate en_enhanced.json
import json
from collections import Counter

# Load corpus (e.g., text files)
words = []
with open('corpus_en.txt', 'r') as f:
    for line in f:
        words.extend(line.lower().split())

# Count frequencies
word_freq = Counter(words)

# Take top 50k words
top_words = word_freq.most_common(50000)

# Scale to 128-255 range
max_freq = top_words[0][1]
scaled = {}
for word, freq in top_words:
    scaled_freq = 128 + int((freq / max_freq) * 127)
    scaled[word] = scaled_freq

# Save as JSON
with open('en_enhanced.json', 'w') as f:
    json.dump(scaled, f, ensure_ascii=False)
```

### Option 3: Extract from Existing Keyboard
If you have an APK with dictionaries:
```bash
# Extract APK
unzip keyboard.apk

# Find dictionary files
find assets/ -name "*.dict" -o -name "*.json" -o -name "*.bin"

# Convert to required format
```

---

## Installation Instructions

Once asset files are created:

1. **Create directories**:
   ```bash
   mkdir -p src/main/assets/dictionaries
   mkdir -p src/main/assets/bigrams
   ```

2. **Copy files**:
   ```bash
   cp en_enhanced.json src/main/assets/dictionaries/
   cp en_bigrams.json src/main/assets/bigrams/
   ```

3. **Rebuild APK**:
   ```bash
   ./gradlew assembleDebug
   ```

4. **Verify loading**:
   ```bash
   adb logcat -s CleverKeys:D | grep -E "(dictionary|bigram)"
   ```

Expected logs:
```
✅ WordPredictor dictionary loaded (50123 words)
✅ BigramModel loaded from assets (bigrams_en.json)
```

---

## Testing Without Assets

Current testing can proceed without assets:

### What Works:
- ✅ Tap typing (character input)
- ✅ Swipe typing (gesture recognition)
- ✅ Custom word predictions
- ✅ User dictionary integration
- ✅ Recent word context
- ✅ User adaptation learning
- ✅ Auto-repeat (backspace/arrows)
- ✅ All keyboard UI features

### What's Limited:
- ⚠️ Dictionary size (only user words + custom words)
- ⚠️ Prediction quality (no frequency ranking)
- ⚠️ Context awareness (no bigram boost)
- ⚠️ Uncommon word suggestions

---

## Priority for Future Work

**Priority**: Medium (not blocking MVP testing)

**Rationale**:
- Keyboard is fully functional without assets
- User can add custom words manually
- User adaptation learns frequently used words
- Asset creation is time-consuming
- Can be added incrementally (start with English, add more languages later)

**Recommended Approach**:
1. Complete manual testing without assets
2. Identify any critical prediction issues
3. Create English dictionary/bigrams first (largest user base)
4. Test with English assets
5. Expand to other languages based on user demand

---

## Summary

**Current Status**:
- ✅ All code ready to use asset files
- ✅ Graceful degradation handles missing files
- ✅ No crashes or errors
- ⚠️ Reduced prediction quality without assets
- ⏳ Asset creation is future work

**Action Required**: None for testing MVP functionality

**Next Steps** (after MVP testing):
1. Generate or download dictionary data
2. Create bigram probability data
3. Format as JSON
4. Place in assets directories
5. Rebuild and test with assets
