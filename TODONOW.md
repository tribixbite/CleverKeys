# COMPLETE SYSTEMATIC COMPARISON - Unexpected-Keyboard vs CleverKeys

## CRITICAL DISCOVERY: 25+ MISSING CORE JAVA FILES

### Files in Unexpected-Keyboard BUT NOT in CleverKeys:

1. **AsyncPredictionHandler.java** - MISSING
   - Async prediction coordination
   - Background prediction processing

2. **BigramModel.java** - MISSING
   - Bigram language model support
   - Context-aware predictions

3. **ComprehensiveTraceAnalyzer.java** - MISSING
   - Advanced trace analysis
   - Gesture quality metrics

4. **ContinuousGestureRecognizer.java** - MISSING
   - CGR gesture recognition (original algorithm)

5. **ContinuousSwipeGestureRecognizer.java** - MISSING
   - Continuous swipe processing

6. **DictionaryManager.java** - MISSING
   - Dictionary loading/management
   - Language-specific dictionaries

7. **Gesture.java** - MISSING
   - Base gesture abstraction
   - Gesture type definitions

8. **ImprovedSwipeGestureRecognizer.java** - MISSING
   - Enhanced CGR variant

9. **KeyValueParser.java** - **CRITICAL MISSING**
   - Parses KeyValue from XML strings
   - EXPLAINS CHINESE CHARACTER BUG
   - Layout parsing completely broken without this

10. **LanguageDetector.java** - MISSING
    - Auto-detect input language
    - Switch dictionaries dynamically

11. **LoopGestureDetector.java** - MISSING
    - Loop gesture detection
    - Special gesture handling

12. **NeuralVocabulary.java** - MISSING
    - Neural-specific vocabulary
    - We have OptimizedVocabularyImpl but may be incomplete

13. **NgramModel.java** - MISSING
    - N-gram language modeling
    - Advanced prediction context

14. **PersonalizationManager.java** - MISSING
    - User personalization
    - Learning from usage

15. **ProbabilisticKeyDetector.java** - MISSING
    - Probabilistic key detection
    - Error correction for fat fingers

16. **RealTimeSwipePredictor.java** - MISSING
    - Real-time swipe prediction
    - Live prediction updates

17. **SwipeGestureRecognizer.java** - MISSING
    - Base class for gesture recognizers
    - Common gesture logic

18. **SwipePruner.java** - MISSING
    - Gesture path pruning
    - Noise reduction

19. **SwipeTrajectoryProcessor.java** - MISSING
    - Trajectory preprocessing
    - Feature extraction base

20. **TemplateBrowserActivity.java** - MISSING
    - Template browsing UI
    - Gesture template viewer

21. **UserAdaptationManager.java** - MISSING
    - User adaptation system
    - Personalized corrections

22. **WordGestureTemplateGenerator.java** - MISSING
    - Generate word templates
    - Template-based matching

23. **WordPredictor.java** - MISSING
    - Base word prediction interface
    - Prediction coordination

24. **prefs/ListGroupPreference.java** - MISSING
    - List group preference widget
    - Complex preference management

## SYSTEMATIC FILE-BY-FILE REVIEW PLAN

### Phase 1: CRITICAL FIXES (Week 1)
Priority: Fix showstopper bugs

1. [ ] **KeyValueParser.java → KeyValue.kt**
   - CRITICAL: Without parser, layouts don't load correctly
   - Explains Chinese character bug
   - Need to port parseKeyValueOpt(), parseKeyValue(), parseEvent(), etc.
   - Action: Read KeyValueParser.java entirely, implement in KeyValue.kt

2. [ ] **Keyboard2.java → CleverKeysService.kt**
   - Line-by-line comparison
   - Missing: onCreateInputView(), onCreateCandidatesView()
   - Missing: Input connection management
   - Missing: Auto-capitalization integration
   - Action: Read both files completely, document ALL differences

3. [ ] **Keyboard2View.java → Keyboard2View.kt**
   - PARTIALLY BROKEN: Rendering fixed but still incomplete
   - Missing: Pointer handling edge cases
   - Missing: Key repeat logic
   - Missing: Slider key support
   - Action: Compare every single method

4. [ ] **Config.java → Config.kt**
   - Settings not persisting correctly
   - Missing configuration options
   - Action: Compare all properties, all methods

5. **SuggestionBar.java → SuggestionBar.kt**
   - PREDICTION BAR NOT SHOWING
   - Missing: Visibility logic
   - Missing: Word selection handling
   - Action: Full comparison

### Phase 2: MISSING COMPONENTS (Week 2-3)

6. [ ] **Port KeyValueParser.java**
   - Create KeyValueParser.kt
   - parseKeyValueOpt(), parseKeyValue(), parseEvent(), parseModifier()
   - parseString(), parseChar(), parseSpecialChar()
   - Complete implementation

7. [ ] **Port DictionaryManager.java**
   - Dictionary loading logic
   - Language-specific handling
   - Dictionary selection

8. [ ] **Port WordPredictor.java**
   - Base prediction interface
   - Prediction coordination
   - Multiple predictor support

9. [ ] **Port AsyncPredictionHandler.java**
   - Background prediction processing
   - Thread coordination
   - Result caching

10. [ ] **Port BigramModel.java / NgramModel.java**
    - Context-aware predictions
    - Language modeling

### Phase 3: GESTURE SYSTEM (Week 3-4)

11. [ ] **Port Gesture.java**
    - Base gesture abstraction
    - Gesture type system

12. [ ] **Port SwipeGestureRecognizer.java**
    - Base recognizer class
    - Common gesture logic

13. [ ] **Port ContinuousGestureRecognizer.java**
    - Original CGR algorithm
    - Template matching

14. [ ] **Port SwipePruner.java**
    - Path noise reduction
    - Gesture cleanup

15. [ ] **Port SwipeTrajectoryProcessor.java**
    - Trajectory preprocessing
    - Feature extraction

### Phase 4: ADVANCED FEATURES (Week 4-6)

16. [ ] **Port LanguageDetector.java**
    - Auto-detect language
    - Dynamic dictionary switching

17. [ ] **Port PersonalizationManager.java**
    - User adaptation
    - Learning system

18. [ ] **Port ProbabilisticKeyDetector.java**
    - Error correction
    - Fat finger compensation

19. [ ] **Port ComprehensiveTraceAnalyzer.java**
    - Gesture quality metrics
    - Performance analysis

20. [ ] **Port prefs/ListGroupPreference.java**
    - Complex preference widget
    - List management UI

### Phase 5: RESOURCE & LAYOUT FILES (Week 6-7)

21. [ ] **Compare res/xml/ files**
    - bottom_row.xml, number_row.xml, etc.
    - Ensure all layouts present
    - Validate XML structure

22. [ ] **Compare res/layout/ files**
    - All activity layouts
    - Widget layouts
    - Dialog layouts

23. [ ] **Compare res/values/ files**
    - strings.xml (all languages)
    - styles.xml, themes.xml
    - arrays.xml, attrs.xml

### Phase 6: DETAILED METHOD-BY-METHOD COMPARISON (Week 7-12)

For EVERY file that exists in both codebases, perform detailed comparison:

#### Core Files (Priority Order):

24. [ ] **Keyboard2View: COMPLETE METHOD COMPARISON**
    - [ ] Constructor
    - [ ] onMeasure()
    - [ ] onDraw()
    - [ ] onTouch()
    - [ ] drawLabel()
    - [ ] drawSubLabel()
    - [ ] drawKeyFrame()
    - [ ] drawIndication()
    - [ ] getKeyAt()
    - [ ] modifyKey()
    - [ ] pointers handling
    - [ ] ALL remaining methods

25. [ ] **Keyboard2 / CleverKeysService: COMPLETE METHOD COMPARISON**
    - [ ] onCreate()
    - [ ] onCreateInputView()
    - [ ] onCreateCandidatesView()
    - [ ] onStartInput()
    - [ ] onStartInputView()
    - [ ] onFinishInput()
    - [ ] onEvaluateFullscreenMode()
    - [ ] onConfigurationChanged()
    - [ ] ALL remaining methods

26. [ ] **Config: COMPLETE PROPERTY & METHOD COMPARISON**
    - [ ] ALL config properties
    - [ ] load() / save()
    - [ ] Migration logic
    - [ ] Default values
    - [ ] Validation

27. [ ] **Pointers: COMPLETE METHOD COMPARISON**
    - [ ] onTouchDown()
    - [ ] onTouchMove()
    - [ ] onTouchUp()
    - [ ] getKeyFlags()
    - [ ] Modifiers handling
    - [ ] Flag management
    - [ ] ALL remaining methods

28. [ ] **KeyValue: COMPLETE COMPARISON**
    - [ ] All key types
    - [ ] All factory methods
    - [ ] getString() vs displayString
    - [ ] Flag handling
    - [ ] Comparison logic

29. [ ] **KeyboardData: COMPLETE COMPARISON**
    - [ ] XML parsing
    - [ ] Key structure
    - [ ] Row structure
    - [ ] load() method
    - [ ] ALL parsing logic

30. [ ] **Theme: COMPLETE COMPARISON**
    - [ ] Color properties
    - [ ] Style properties
    - [ ] Computed values
    - [ ] Paint creation
    - [ ] ALL theme logic

31. [ ] **ComposeKey: COMPLETE COMPARISON**
    - [ ] Composition logic
    - [ ] Accent handling
    - [ ] State management

32. [ ] **KeyModifier: COMPLETE COMPARISON**
    - [ ] Modifier application
    - [ ] Shift handling
    - [ ] Compose handling
    - [ ] ALL modifier logic

33. [ ] **KeyEventHandler: COMPLETE COMPARISON**
    - [ ] Event processing
    - [ ] Key dispatch
    - [ ] Input connection
    - [ ] ALL event handling

34. [ ] **ExtraKeys: COMPLETE COMPARISON**
    - [ ] Extra key parsing
    - [ ] Layout integration
    - [ ] Configuration

35. [ ] **Utils: COMPLETE COMPARISON**
    - [ ] ALL utility methods
    - [ ] String formatting
    - [ ] Resource loading
    - [ ] Helper functions

### Phase 7: UI COMPONENTS (Week 12-13)

36. [ ] **LauncherActivity: COMPLETE COMPARISON**
37. [ ] **SettingsActivity: COMPLETE COMPARISON**
38. [ ] **SwipeCalibrationActivity: COMPLETE COMPARISON**
39. [ ] **EmojiGridView: COMPLETE COMPARISON**
40. [ ] **EmojiGroupButtonsBar: COMPLETE COMPARISON**
41. [ ] **ClipboardHistoryView: COMPLETE COMPARISON**
42. [ ] **ClipboardPinView: COMPLETE COMPARISON**
43. [ ] **NonScrollListView: COMPLETE COMPARISON**

### Phase 8: PREFERENCES (Week 13-14)

44. [ ] **ExtraKeysPreference: COMPLETE COMPARISON**
45. [ ] **LayoutsPreference: COMPLETE COMPARISON**
46. [ ] **SlideBarPreference: COMPLETE COMPARISON**
47. [ ] **IntSlideBarPreference: COMPLETE COMPARISON**
48. [ ] **CustomExtraKeysPreference: COMPLETE COMPARISON**

### Phase 9: SUPPORTING CLASSES (Week 14-15)

49. [ ] **Autocapitalisation: COMPLETE COMPARISON**
50. [ ] **ClipboardDatabase: COMPLETE COMPARISON**
51. [ ] **ClipboardHistoryService: COMPLETE COMPARISON**
52. [ ] **ComposeKeyData: COMPLETE COMPARISON**
53. [ ] **DirectBootAwarePreferences: COMPLETE COMPARISON**
54. [ ] **Emoji: COMPLETE COMPARISON**
55. [ ] **FoldStateTracker: COMPLETE COMPARISON**
56. [ ] **LayoutModifier: COMPLETE COMPARISON**
57. [ ] **Logs: COMPLETE COMPARISON**
58. [ ] **Modmap: COMPLETE COMPARISON**
59. [ ] **NumberLayout: COMPLETE COMPARISON**
60. [ ] **VibratorCompat: COMPLETE COMPARISON**
61. [ ] **VoiceImeSwitcher: COMPLETE COMPARISON**

### Phase 10: NEURAL/SWIPE COMPONENTS (Week 15-17)

62. [ ] **EnhancedSwipeGestureRecognizer: COMPLETE COMPARISON**
63. [ ] **SwipeDetector: COMPLETE COMPARISON**
64. [ ] **SwipeInput: COMPLETE COMPARISON**
65. [ ] **SwipeTokenizer: COMPLETE COMPARISON**
66. [ ] **NeuralSwipeTypingEngine: COMPLETE COMPARISON**
67. [ ] **OnnxSwipePredictor: COMPLETE COMPARISON**
68. [ ] **PredictionResult: COMPLETE COMPARISON**
69. [ ] **SwipeAdvancedSettings: COMPLETE COMPARISON**
70. [ ] **SwipeMLData: COMPLETE COMPARISON**
71. [ ] **SwipeMLDataStore: COMPLETE COMPARISON**

## IMMEDIATE ACTION ITEMS (TODAY)

### 1. Fix KeyValueParser - CRITICAL
**Problem**: Chinese character appearing because key parsing is broken
**File**: Need to create KeyValueParser.kt from KeyValueParser.java
**Lines to read**: ALL 1000+ lines of KeyValueParser.java

### 2. Fix SuggestionBar visibility
**Problem**: Prediction bar not showing
**Files**:
- SuggestionBar.java (lines 1-500+)
- SuggestionBar.kt (lines 1-200+)
**Action**: Complete comparison, identify missing visibility logic

### 3. Fix bottom bar
**Problem**: Bottom bar missing
**Files**:
- Keyboard2.java onCreateInputView()
- CleverKeysService.kt onCreateInputView()
**Action**: Compare view hierarchy construction

### 4. Fix text size
**Problem**: Text size wrong
**Files**:
- Theme.java label size calculation
- Theme.kt label size calculation
**Action**: Compare font scaling logic

### 5. Fix key press handling
**Problem**: Keys still don't work
**Files**:
- Pointers.java onPointerUp()
- Pointers.kt onPointerUp()
- KeyEventHandler.java handleKeyUp()
- KeyEventHandler.kt handleKeyUp()
**Action**: Trace entire key press → text input flow

## EXECUTION STRATEGY

**Week 1 (NOW):**
- Read KeyValueParser.java completely (1000+ lines)
- Port to KeyValueParser.kt
- Fix Chinese character bug
- Read Keyboard2.java onCreateInputView()
- Fix bottom bar visibility
- Read SuggestionBar.java completely
- Fix prediction bar visibility

**Week 2:**
- Read Keyboard2.java entirely (2000+ lines)
- Compare with CleverKeysService.kt entirely
- Document ALL differences
- Fix key press handling
- Fix text input flow

**Week 3:**
- Read Keyboard2View.java entirely (3000+ lines)
- Compare with Keyboard2View.kt entirely
- Fix all rendering issues
- Fix all touch handling

**Weeks 4-17:**
- Systematic file-by-file comparison
- Port all missing files
- Fix all discovered bugs
- Achieve 100% feature parity

## TRACKING PROGRESS

**Files Read Completely**: 0 / 251
**Files Compared**: 0 / 80 (files present in both)
**Missing Files Ported**: 0 / 25
**Bugs Fixed**: 50 / 500+ (estimated)
**Feature Parity**: ~5% (severely incomplete)

## COMMIT STRATEGY

After EACH file comparison:
1. Document all findings in this file
2. Fix critical bugs found
3. Commit with detailed message
4. Move to next file

NO shortcuts. NO assumptions. Read EVERY line.
