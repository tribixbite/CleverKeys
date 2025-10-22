# UI Material 3 Modernization Specification

**Status**: 📝 PLANNING
**Priority**: P1 HIGH
**Created**: 2025-10-21
**Owner**: CleverKeys Core Team

---

## 🎯 EXECUTIVE SUMMARY

**Objective**: Modernize CleverKeys UI to Material Design 3 (Material You) for consistent, accessible, and beautiful user experience across all components.

**Current State**: Mixed implementation
- ✅ SettingsActivity: Already using Material 3 (Compose + Material3)
- ⚠️ Theme.kt: Basic Android theming (NOT Material 3)
- ❌ SuggestionBar: Plain buttons with hardcoded styling
- ❌ Other views: Legacy XML-based or programmatic layouts
- ❌ No animation system
- ❌ No dynamic color (Material You)

**Target State**: Complete Material 3 implementation
- Material 3 design language across all components
- Dynamic color support (Material You)
- Proper theming system with dark/light modes
- Smooth animations and transitions
- Accessibility-first design
- Consistent spacing, typography, elevation

**Impact**:
- Better UX consistency with Android 12+ system
- Improved accessibility (larger touch targets, better contrast)
- Modern visual appeal
- Easier maintenance with Material 3 components

---

## 📊 CURRENT STATE ANALYSIS

### UI Components Inventory (14 files)

| Component | Lines | Current State | Material 3 | Priority | Bugs |
|-----------|-------|---------------|------------|----------|------|
| **SettingsActivity.kt** | 935 | ✅ M3 Compose | ✅ Complete | ✓ DONE | 0 |
| **Theme.kt** | ~400 | ⚠️ Basic theming | ❌ No M3 | P0 | File 8 |
| **SuggestionBar.kt** | 87 | ❌ Plain buttons | ❌ No M3 | P0 | File 5 (11 bugs) |
| **Keyboard2View.kt** | ~800 | ❌ Custom canvas | △ Partial | P1 | File 9 (5 bugs) |
| **ClipboardHistoryView.kt** | ~250 | ❌ LinearLayout | ❌ No M3 | P0 | File 24 (12 bugs) |
| **ClipboardPinView.kt** | ~200 | ❌ Programmatic | ❌ No M3 | P1 | File 23 (5 bugs) |
| **EmojiGridView.kt** | ~180 | ❌ GridLayout | ❌ No M3 | P1 | File 55 (8 bugs) |
| **EmojiGroupButtonsBar.kt** | ~120 | ❌ LinearLayout | ❌ No M3 | P1 | File 56 (3 bugs) |
| **CustomLayoutEditDialog.kt** | ~200 | ❌ AlertDialog | ❌ No M3 | P2 | Unknown |
| **SwipeCalibrationActivity.kt** | 942 | ✅ M3 Compose | ✅ Complete | ✓ DONE | 0 |
| **NeuralBrowserActivity.kt** | 538 | △ Basic compose | △ Partial | P2 | File 86 |
| **NeuralSettingsActivity.kt** | ~300 | △ Basic compose | △ Partial | P2 | Unknown |
| **LauncherActivity.kt** | ~150 | ❌ Basic | ❌ No M3 | P3 | Unknown |
| **TestActivity.kt** | 164 | ✅ M3 Compose | ✅ Complete | ✓ DONE | 0 |

**Statistics**:
- ✅ Complete M3: 3/14 (21.4%)
- △ Partial M3: 3/14 (21.4%)
- ❌ No M3: 8/14 (57.1%)

---

## 🐛 IDENTIFIED UI BUGS

### Critical (P0) - 24 bugs
1. **Theme System (File 8 - Theme.kt)**:
   - No Material 3 color scheme integration
   - No dynamic color (Material You)
   - Manual color adjustments vs semantic tokens
   - No elevation system
   - Missing typography scale

2. **SuggestionBar (File 5 - 11 bugs)**:
   - 73% feature parity missing
   - No theme integration
   - Hardcoded colors (Color.WHITE, Color.TRANSPARENT)
   - No Material 3 surface colors
   - No elevation
   - No ripple effects
   - Missing swipe gestures
   - No autocomplete preview
   - No confidence indicators
   - No animation transitions
   - No accessibility labels

3. **ClipboardHistoryView (File 24 - 12 bugs)**:
   - Wrong base class (LinearLayout vs NonScrollListView)
   - No adapter pattern
   - Broken pin/paste functionality
   - Missing lifecycle methods
   - Wrong API calls
   - No Material 3 cards/lists
   - Hardcoded styling

### High Priority (P1) - 21 bugs
4. **Keyboard2View (File 9 - 5 bugs)**:
   - Gesture exclusion rects missing
   - Inset handling incomplete
   - Indication rendering issues
   - No ripple feedback on key press
   - No Material motion

5. **EmojiGridView (File 55 - 8 bugs)**:
   - Wrong base class (GridLayout vs GridView)
   - No adapter pattern
   - No Material 3 grid styling
   - Missing accessibility

6. **EmojiGroupButtonsBar (File 56 - 3 bugs)**:
   - Nullable AttributeSet issues
   - No Material 3 button styles
   - Hardcoded colors

7. **ClipboardPinView (File 23 - 5 bugs)**:
   - Programmatic layout workarounds
   - Hardcoded strings/emojis (no i18n)
   - No Material 3 components

### Medium Priority (P2) - 9 bugs
8. **UI Internationalization (4 bugs)**:
   - Bug #116: Hardcoded header text
   - Bug #117: Hardcoded button text
   - Bug #119: Hardcoded emoji icons
   - Bug #121: Hardcoded toast messages

9. **Animation System (Bug #325 - HIGH)**:
   - AnimationManager.java COMPLETELY MISSING
   - No Material motion system
   - No transition animations
   - No key press animations

10. **Layout Issues (5 bugs)**:
    - Bug #128: Blocking initialization (potential ANR)
    - LayoutAnimator missing
    - OneHanded mode UI missing
    - Floating keyboard UI missing
    - Split keyboard UI missing

---

## ⚠️ ADDITIONAL KNOWN ISSUES (From Historical Review)

### HIGH PRIORITY Issues

**✅ Issue #11: Missing Error Feedback**
- **File**: `src/main/kotlin/tribixbite/keyboard2/CleverKeysService.kt:507`
- **Status**: ⚠️ NEEDS VERIFICATION
- **Original Problem**: `// TODO: Implement user-visible error feedback`
- **Impact**: Users don't see errors when predictions fail
- **Action Required**:
  - [ ] Review CleverKeysService.kt:507
  - [ ] Implement Snackbar for transient errors
  - [ ] Implement Toast for critical errors
  - [ ] Use Material 3 Snackbar component
  - [ ] Test error scenarios (model load failure, prediction timeout, etc.)
  - [ ] Add user-friendly error messages (not technical stack traces)

### MEDIUM PRIORITY Issues

**✅ Issue #15: Theme Propagation Incomplete**
- **File**: `src/main/kotlin/tribixbite/keyboard2/CleverKeysService.kt:654`
- **Status**: ⚠️ NEEDS VERIFICATION (Duplicate of settings-system.md issue)
- **Original Problem**: `// TODO: Propagate theme changes to active UI components`
- **Impact**: Theme changes may not apply until restart
- **Action Required**: See settings-system.md for full details
  - [ ] Review CleverKeysService.kt:654
  - [ ] Implement theme update notification system
  - [ ] Test theme switching without restart

**✅ Issue #18: ConfigurationManager Theme Application**
- **File**: `src/main/kotlin/tribixbite/keyboard2/ConfigurationManager.kt:306`
- **Status**: ⚠️ NEEDS VERIFICATION (Duplicate of settings-system.md issue)
- **Original Problem**: `// TODO: Fix Theme.initialize(context).applyThemeToView(view, theme)`
- **Impact**: Theme not fully applied to all UI components
- **Action Required**: See settings-system.md for full details
  - [ ] Review ConfigurationManager.kt:306
  - [ ] Fix theme application to all views
  - [ ] Verify Material 3 theming works correctly

### LOW PRIORITY Issues

**Issue #24: Hardcoded Strings**
- **File**: `src/main/kotlin/tribixbite/keyboard2/CustomLayoutEditDialog.kt:46,54`
- **Status**: FUTURE WORK
- **Problem**: Hardcoded "Custom layout" and "Remove layout" strings
- **Impact**: Cannot localize/translate these strings
- **Action Required** (Future):
  - [ ] Create string resources in res/values/strings.xml
  - [ ] Add: `<string name="custom_layout">Custom layout</string>`
  - [ ] Add: `<string name="remove_layout">Remove layout</string>`
  - [ ] Replace hardcoded strings with `getString(R.string.custom_layout)`
  - [ ] Prepare for full i18n/localization effort
- **Note**: This is part of larger i18n effort (Bugs #116, #117, #119, #121 above)

---

## 🎨 MATERIAL 3 DESIGN SYSTEM

### Color System

**Adopt Material 3 Color Roles**:
```kotlin
// Replace hardcoded colors with semantic tokens
MaterialTheme.colorScheme.primary          // Key actions
MaterialTheme.colorScheme.secondary        // Less prominent actions
MaterialTheme.colorScheme.tertiary         // Accent colors
MaterialTheme.colorScheme.surface          // Component backgrounds
MaterialTheme.colorScheme.surfaceVariant   // Alternative surfaces
MaterialTheme.colorScheme.background       // App background
MaterialTheme.colorScheme.error            // Error states
MaterialTheme.colorScheme.onPrimary        // Text on primary
MaterialTheme.colorScheme.onSurface        // Text on surface
// ... and 20+ more semantic colors
```

**Dynamic Color (Material You)**:
```kotlin
// Adapt to user's wallpaper colors
val colorScheme = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        if (darkTheme) dynamicDarkColorScheme(context)
        else dynamicLightColorScheme(context)
    }
    darkTheme -> darkColorScheme()
    else -> lightColorScheme()
}
```

**Current Issues**:
- ❌ Theme.kt uses `Color.WHITE`, `Color.BLACK`, `Color.LTGRAY`
- ❌ SuggestionBar.kt: `setBackgroundColor(Color.TRANSPARENT)`
- ❌ Manual color adjustments: `adjustColorBrightness()`
- ❌ No semantic color roles

**Fix Strategy**:
- Replace all hardcoded colors with `MaterialTheme.colorScheme.*`
- Implement dynamic color support
- Define custom color scheme for keyboard keys
- Use color roles consistently

### Typography System

**Material 3 Type Scale**:
```kotlin
MaterialTheme.typography.displayLarge     // 57sp
MaterialTheme.typography.displayMedium    // 45sp
MaterialTheme.typography.displaySmall     // 36sp
MaterialTheme.typography.headlineLarge    // 32sp
MaterialTheme.typography.headlineMedium   // 28sp
MaterialTheme.typography.headlineSmall    // 24sp
MaterialTheme.typography.titleLarge       // 22sp (Key labels)
MaterialTheme.typography.titleMedium      // 16sp (Suggestions)
MaterialTheme.typography.titleSmall       // 14sp (Sub-labels)
MaterialTheme.typography.bodyLarge        // 16sp (Body text)
MaterialTheme.typography.bodyMedium       // 14sp
MaterialTheme.typography.bodySmall        // 12sp
MaterialTheme.typography.labelLarge       // 14sp (Buttons)
MaterialTheme.typography.labelMedium      // 12sp
MaterialTheme.typography.labelSmall       // 11sp
```

**Current Issues**:
- ❌ Hardcoded text sizes: `labelTextSize = 16f`
- ❌ No consistent type scale
- ❌ Custom font loading but not integrated with M3 typography

**Fix Strategy**:
- Define custom typography for keyboard (larger touch targets)
- Map keyboard labels to appropriate type scale
- Integrate `special_font.ttf` into MaterialTheme

### Elevation System

**Material 3 Elevation Tokens**:
```kotlin
// Surface elevation for layering
0.dp   // Level 0 - Background
1.dp   // Level 1 - Cards, buttons at rest
3.dp   // Level 2 - FAB at rest, suggestion bar
6.dp   // Level 3 - Modal dialogs
8.dp   // Level 4 - Modal side sheets
12.dp  // Level 5 - Tooltip
```

**Current Issues**:
- ❌ No elevation system in Theme.kt
- ❌ Flat UI with no layering
- ❌ No shadow/depth perception

**Fix Strategy**:
- Apply surface tonal elevation for keyboard
- Use elevation for suggestion bar (3.dp)
- Add elevation to floating elements (emoji picker, clipboard)

### Shape System

**Material 3 Shape Scale**:
```kotlin
MaterialTheme.shapes.extraSmall  // 4.dp  - Checkboxes
MaterialTheme.shapes.small       // 8.dp  - Chips, buttons
MaterialTheme.shapes.medium      // 12.dp - Cards
MaterialTheme.shapes.large       // 16.dp - Dialogs
MaterialTheme.shapes.extraLarge  // 28.dp - Large cards
```

**Current Issues**:
- ❌ `keyBorderRadius` not using shape tokens
- ❌ Inconsistent corner radii

**Fix Strategy**:
- Define keyboard-specific shapes
- Use consistent corner radii from shape scale

### Motion System

**Material 3 Motion Principles**:
- **Easing**: Emphasized decelerate (enter), Emphasized accelerate (exit)
- **Durations**:
  - Short (50-150ms): Micro-interactions (key press)
  - Medium (200-300ms): Component transitions
  - Long (400-500ms): Container transformations
- **Patterns**:
  - Fade through: Changing content
  - Shared axis: Sibling transitions
  - Container transform: Element expansion

**Current Issues**:
- ❌ AnimationManager.java COMPLETELY MISSING (Bug #325)
- ❌ No key press animations
- ❌ No transition animations
- ❌ Instant state changes (jarring UX)

**Fix Strategy**:
- Implement AnimationManager using Material motion
- Add key press ripple with emphasized easing
- Animate suggestion bar updates
- Smooth keyboard show/hide transitions

---

## 🏗️ IMPLEMENTATION PLAN

### Phase 1: Foundation (Week 1-2) - P0

**Goal**: Establish Material 3 theming foundation

#### 1.1 Theme System Overhaul
**File**: `Theme.kt` (REWRITE)
**Bug**: File 8 (1 critical bug)
**Effort**: 16-24 hours

**Tasks**:
- [ ] Create `MaterialThemeManager.kt`:
  ```kotlin
  class MaterialThemeManager(private val context: Context) {
      // Dynamic color support
      fun getColorScheme(darkTheme: Boolean): ColorScheme

      // Custom typography for keyboard
      fun getKeyboardTypography(): Typography

      // Keyboard-specific shapes
      fun getKeyboardShapes(): Shapes

      // Reactive theme updates
      val themeFlow: StateFlow<ThemeConfig>
  }
  ```

- [ ] Define keyboard color tokens:
  ```kotlin
  // Custom color scheme extension
  @Immutable
  data class KeyboardColorScheme(
      val keyDefault: Color,
      val keyActivated: Color,
      val keyLocked: Color,
      val keyLabel: Color,
      val keySubLabel: Color,
      val keyBorder: Color,
      val swipeTrail: Color,
      val suggestion: Color,
      val suggestionBg: Color
  )
  ```

- [ ] Implement dynamic color (Material You):
  ```kotlin
  val colorScheme = when {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
          val context = LocalContext.current
          if (darkTheme) dynamicDarkColorScheme(context)
          else dynamicLightColorScheme(context)
      }
      darkTheme -> darkColorScheme(/* custom colors */)
      else -> lightColorScheme(/* custom colors */)
  }
  ```

- [ ] Replace all hardcoded colors in existing code
- [ ] Create `@Composable KeyboardTheme` wrapper
- [ ] Add theme preview in settings

**Success Criteria**:
- ✅ No hardcoded colors in codebase
- ✅ Dynamic color works on Android 12+
- ✅ Dark/light themes fully functional
- ✅ Theme changes propagate reactively

#### 1.2 SuggestionBar Material 3 Rewrite
**File**: `SuggestionBar.kt` (REWRITE)
**Bug**: File 5 (11 critical bugs)
**Effort**: 12-16 hours

**Current**: 87 lines, plain buttons
**Target**: 200-250 lines, full Material 3

**Tasks**:
- [ ] Rewrite as Compose with Material 3:
  ```kotlin
  @Composable
  fun SuggestionBar(
      suggestions: List<Suggestion>,
      onSuggestionClick: (String) -> Unit,
      modifier: Modifier = Modifier
  ) {
      Surface(
          modifier = modifier.fillMaxWidth(),
          tonalElevation = 3.dp,
          color = MaterialTheme.colorScheme.surfaceVariant
      ) {
          LazyRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
          ) {
              items(suggestions) { suggestion →
                  SuggestionChip(
                      onClick = { onSuggestionClick(suggestion.word) },
                      label = { Text(suggestion.word) },
                      leadingIcon = if (suggestion.confidence > 0.8) {
                          { Icon(Icons.Default.Star, "High confidence") }
                      } else null,
                      colors = SuggestionChipDefaults.suggestionChipColors(
                          containerColor = MaterialTheme.colorScheme.surface
                      )
                  )
              }
          }
      }
  }
  ```

- [ ] Add features:
  - Confidence indicators (star icon for >80% confidence)
  - Swipe to dismiss
  - Long-press for word info
  - Animated transitions between suggestion sets
  - Accessibility labels
  - Theme integration

- [ ] Integrate with CleverKeysService

**Success Criteria**:
- ✅ All 11 bugs fixed
- ✅ Material 3 SuggestionChip components
- ✅ Smooth animations
- ✅ Confidence indicators visible
- ✅ Proper elevation and theming

#### 1.3 Animation System Implementation
**File**: `AnimationManager.kt` (NEW)
**Bug**: #325 (HIGH)
**Effort**: 20-24 hours

**Tasks**:
- [ ] Create animation manager:
  ```kotlin
  class AnimationManager {
      // Key press animations
      fun animateKeyPress(key: KeyboardData.Key, view: View)
      fun animateKeyRelease(key: KeyboardData.Key, view: View)

      // Suggestion animations
      fun animateSuggestionChange(fromWords: List<String>, toWords: List<String>)

      // Keyboard transitions
      fun animateKeyboardShow()
      fun animateKeyboardHide()

      // Swipe trail animation
      fun animateSwipeTrail(points: List<PointF>)

      // Compose animations
      val keyPressScale: Animatable<Float, AnimationVector1D>
      val suggestionFade: Animatable<Float, AnimationVector1D>
  }
  ```

- [ ] Implement Material motion:
  - Emphasized easing for key presses
  - Fade through for suggestion changes
  - Shared axis for keyboard transitions
  - Custom spring for swipe trail

- [ ] Add to Keyboard2View.kt
- [ ] Add to SuggestionBar.kt

**Success Criteria**:
- ✅ Smooth key press feedback
- ✅ Animated suggestion updates
- ✅ Material motion curves
- ✅ 60fps performance

---

### Phase 2: Core Components (Week 3-4) - P1

**Goal**: Modernize core keyboard UI components

#### 2.1 ClipboardHistoryView Rewrite
**File**: `ClipboardHistoryView.kt` (REWRITE)
**Bug**: File 24 (12 CATASTROPHIC bugs)
**Effort**: 24-32 hours

**Current**: ~250 lines, broken implementation
**Target**: 400-500 lines, full Material 3

**Tasks**:
- [ ] Rewrite as LazyColumn with Material 3:
  ```kotlin
  @Composable
  fun ClipboardHistoryView(
      viewModel: ClipboardViewModel,
      onPaste: (ClipboardEntry) -> Unit,
      modifier: Modifier = Modifier
  ) {
      val history by viewModel.history.collectAsState()

      LazyColumn(
          modifier = modifier.fillMaxSize(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
          contentPadding = PaddingValues(16.dp)
      ) {
          items(history, key = { it.id }) { entry →
              ClipboardCard(
                  entry = entry,
                  onPaste = { onPaste(entry) },
                  onPin = { viewModel.pin(entry) },
                  onDelete = { viewModel.delete(entry) },
                  modifier = Modifier.animateItemPlacement()
              )
          }
      }
  }

  @Composable
  fun ClipboardCard(
      entry: ClipboardEntry,
      onPaste: () -> Unit,
      onPin: () -> Unit,
      onDelete: () -> Unit,
      modifier: Modifier = Modifier
  ) {
      Card(
          modifier = modifier.fillMaxWidth(),
          elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
          colors = CardDefaults.cardColors(
              containerColor = if (entry.isPinned) {
                  MaterialTheme.colorScheme.primaryContainer
              } else {
                  MaterialTheme.colorScheme.surface
              }
          )
      ) {
          Column(modifier = Modifier.padding(16.dp)) {
              Text(
                  text = entry.text,
                  style = MaterialTheme.typography.bodyMedium,
                  maxLines = 3,
                  overflow = TextOverflow.Ellipsis
              )

              Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.End
              ) {
                  IconButton(onClick = onPin) {
                      Icon(
                          if (entry.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                          contentDescription = "Pin"
                      )
                  }
                  IconButton(onClick = onPaste) {
                      Icon(Icons.Default.ContentPaste, "Paste")
                  }
                  IconButton(onClick = onDelete) {
                      Icon(Icons.Default.Delete, "Delete")
                  }
              }
          }
      }
  }
  ```

- [ ] Fix all 12 bugs:
  - Correct base class (LazyColumn)
  - Proper adapter pattern (Compose)
  - Functional pin/paste/delete
  - Complete lifecycle
  - Correct API usage

**Success Criteria**:
- ✅ All 12 bugs fixed
- ✅ Material 3 Card components
- ✅ Smooth animations
- ✅ Pin functionality works
- ✅ MVVM architecture

#### 2.2 Keyboard2View Material Updates
**File**: `Keyboard2View.kt` (ENHANCE)
**Bug**: File 9 (5 bugs)
**Effort**: 16-20 hours

**Tasks**:
- [ ] Add Material ripple effects for key presses
- [ ] Fix gesture exclusion rects
- [ ] Improve inset handling for edge-to-edge
- [ ] Add key press animations
- [ ] Integrate with theme system

**Success Criteria**:
- ✅ All 5 bugs fixed
- ✅ Ripple feedback on keys
- ✅ Proper edge-to-edge support
- ✅ Theme integration

#### 2.3 Emoji Components Material 3
**Files**: `EmojiGridView.kt`, `EmojiGroupButtonsBar.kt` (REWRITE)
**Bugs**: File 55 (8 bugs), File 56 (3 bugs)
**Effort**: 20-24 hours

**Tasks**:
- [ ] Rewrite EmojiGridView as Compose LazyVerticalGrid:
  ```kotlin
  @Composable
  fun EmojiGrid(
      emojis: List<String>,
      onEmojiClick: (String) -> Unit,
      modifier: Modifier = Modifier
  ) {
      LazyVerticalGrid(
          columns = GridCells.Adaptive(48.dp),
          modifier = modifier.fillMaxSize(),
          contentPadding = PaddingValues(8.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
      ) {
          items(emojis) { emoji →
              EmojiButton(
                  emoji = emoji,
                  onClick = { onEmojiClick(emoji) }
              )
          }
      }
  }

  @Composable
  fun EmojiButton(emoji: String, onClick: () -> Unit) {
      FilledIconButton(
          onClick = onClick,
          modifier = Modifier.size(48.dp),
          colors = IconButtonDefaults.filledIconButtonColors(
              containerColor = MaterialTheme.colorScheme.surfaceVariant
          )
      ) {
          Text(emoji, fontSize = 24.sp)
      }
  }
  ```

- [ ] Rewrite EmojiGroupButtonsBar as Compose HorizontalPager
- [ ] Fix all identified bugs

**Success Criteria**:
- ✅ All 11 bugs fixed
- ✅ Material 3 grid/buttons
- ✅ Smooth scrolling
- ✅ Proper touch targets (48dp)

#### 2.4 ClipboardPinView Material 3
**File**: `ClipboardPinView.kt` (REWRITE)
**Bug**: File 23 (5 bugs)
**Effort**: 12-16 hours

**Tasks**:
- [ ] Rewrite as Compose
- [ ] Use Material 3 chips/buttons
- [ ] Fix i18n (externalize strings)
- [ ] Add proper accessibility

**Success Criteria**:
- ✅ All 5 bugs fixed
- ✅ Proper i18n
- ✅ Material 3 components

---

### Phase 3: Polish & Enhancement (Week 5-6) - P2

**Goal**: Complete Material 3 migration and add enhancements

#### 3.1 Remaining Activities
**Files**: `NeuralBrowserActivity.kt`, `NeuralSettingsActivity.kt`, `CustomLayoutEditDialog.kt`
**Effort**: 20-24 hours

**Tasks**:
- [ ] Complete M3 implementation for partial activities
- [ ] Rewrite dialogs with Material 3
- [ ] Add proper navigation transitions
- [ ] Ensure consistent theming

#### 3.2 UI Internationalization
**Bugs**: #116, #117, #119, #121
**Effort**: 8-12 hours

**Tasks**:
- [ ] Create `strings.xml` resources
- [ ] Replace all hardcoded strings
- [ ] Add content descriptions for accessibility
- [ ] Support RTL languages

#### 3.3 Advanced Features
**Effort**: 16-20 hours

**Tasks**:
- [ ] OneHanded mode UI
- [ ] Floating keyboard UI
- [ ] Split keyboard UI
- [ ] Layout animator
- [ ] Theme customization UI

---

## 📐 COMPONENT SPECIFICATIONS

### SuggestionBar (Detailed Spec)

**Location**: `src/main/kotlin/tribixbite/keyboard2/SuggestionBar.kt`

**Current Issues**:
1. Plain `Button` components (not Material 3)
2. Hardcoded colors: `Color.TRANSPARENT`, `Color.WHITE`
3. No elevation
4. No animations
5. Missing features (confidence indicators, swipe gestures, etc.)

**Material 3 Design**:
```kotlin
@Composable
fun SuggestionBar(
    suggestions: List<Suggestion>,
    onSuggestionClick: (String) -> Unit,
    onSuggestionLongPress: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Surface with elevation for depth
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 2.dp
    ) {
        AnimatedContent(
            targetState = suggestions,
            transitionSpec = {
                fadeIn(tween(150)) + slideInVertically() with
                fadeOut(tween(150)) + slideOutVertically()
            }
        ) { currentSuggestions →
            LazyRow(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(
                    items = currentSuggestions,
                    key = { it.word }
                ) { suggestion →
                    SuggestionChip(
                        suggestion = suggestion,
                        onClick = { onSuggestionClick(suggestion.word) },
                        onLongClick = { onSuggestionLongPress(suggestion.word) },
                        modifier = Modifier.animateItemPlacement()
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(
    suggestion: Suggestion,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }

    SuggestionChip(
        onClick = onClick,
        label = {
            Text(
                text = suggestion.word,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (suggestion.confidence > 0.8) {
                    FontWeight.Bold
                } else {
                    FontWeight.Normal
                }
            )
        },
        leadingIcon = if (suggestion.confidence > 0.8) {
            {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "High confidence",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else null,
        modifier = modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                interactionSource = interactionSource,
                indication = rememberRipple()
            ),
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurface,
            iconContentColor = MaterialTheme.colorScheme.primary
        ),
        border = SuggestionChipDefaults.suggestionChipBorder(
            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
            borderWidth = 1.dp
        ),
        interactionSource = interactionSource
    )
}

data class Suggestion(
    val word: String,
    val confidence: Float,
    val source: PredictionSource = PredictionSource.NEURAL
)

enum class PredictionSource {
    NEURAL,      // From ONNX model
    DICTIONARY,  // From dictionary lookup
    USER         // From user history
}
```

**Features**:
- ✅ Material 3 `SuggestionChip` component
- ✅ Semantic colors from theme
- ✅ 3.dp tonal elevation
- ✅ Confidence indicators (star icon for >80%)
- ✅ Animated transitions (fade + slide)
- ✅ Long-press for word info
- ✅ Ripple effect
- ✅ Accessibility (content descriptions)
- ✅ Proper touch targets (48dp height)

---

### Theme System (Detailed Spec)

**Location**: `src/main/kotlin/tribixbite/keyboard2/theme/`

**New Structure**:
```
theme/
├── MaterialThemeManager.kt    # Main theme manager
├── KeyboardColorScheme.kt     # Color extensions
├── KeyboardTypography.kt      # Typography definitions
├── KeyboardShapes.kt          # Shape definitions
├── ThemePreview.kt            # Preview composables
└── DynamicColor.kt            # Material You support
```

**KeyboardColorScheme.kt**:
```kotlin
@Immutable
data class KeyboardColorScheme(
    // Key colors
    val keyDefault: Color,
    val keyActivated: Color,
    val keyLocked: Color,
    val keyModifier: Color,
    val keySpecial: Color,

    // Label colors
    val keyLabel: Color,
    val keySubLabel: Color,
    val keySecondaryLabel: Color,

    // Border colors
    val keyBorder: Color,
    val keyBorderActivated: Color,

    // Interactive colors
    val swipeTrail: Color,
    val ripple: Color,

    // Suggestion colors
    val suggestionText: Color,
    val suggestionBackground: Color,
    val suggestionHighConfidence: Color,

    // Background colors
    val keyboardBackground: Color,
    val keyboardSurface: Color
)

fun lightKeyboardColorScheme(
    primary: Color = Color(0xFF1976D2),
    secondary: Color = Color(0xFF424242)
): KeyboardColorScheme = KeyboardColorScheme(
    keyDefault = Color(0xFFF5F5F5),
    keyActivated = Color(0xFFE0E0E0),
    keyLocked = primary.copy(alpha = 0.2f),
    keyModifier = primary.copy(alpha = 0.1f),
    keySpecial = secondary.copy(alpha = 0.1f),

    keyLabel = Color(0xFF212121),
    keySubLabel = Color(0xFF757575),
    keySecondaryLabel = Color(0xFF9E9E9E),

    keyBorder = Color(0xFFBDBDBD),
    keyBorderActivated = primary,

    swipeTrail = primary.copy(alpha = 0.6f),
    ripple = primary.copy(alpha = 0.3f),

    suggestionText = Color(0xFF212121),
    suggestionBackground = Color(0xFFFFFFFF),
    suggestionHighConfidence = primary,

    keyboardBackground = Color(0xFFEEEEEE),
    keyboardSurface = Color(0xFFFFFFFF)
)

fun darkKeyboardColorScheme(
    primary: Color = Color(0xFF64B5F6),
    secondary: Color = Color(0xFFB0B0B0)
): KeyboardColorScheme = KeyboardColorScheme(
    keyDefault = Color(0xFF2C2C2C),
    keyActivated = Color(0xFF3A3A3A),
    keyLocked = primary.copy(alpha = 0.2f),
    keyModifier = primary.copy(alpha = 0.15f),
    keySpecial = secondary.copy(alpha = 0.15f),

    keyLabel = Color(0xFFE0E0E0),
    keySubLabel = Color(0xFFB0B0B0),
    keySecondaryLabel = Color(0xFF808080),

    keyBorder = Color(0xFF424242),
    keyBorderActivated = primary,

    swipeTrail = primary.copy(alpha = 0.7f),
    ripple = primary.copy(alpha = 0.4f),

    suggestionText = Color(0xFFE0E0E0),
    suggestionBackground = Color(0xFF2C2C2C),
    suggestionHighConfidence = primary,

    keyboardBackground = Color(0xFF1E1E1E),
    keyboardSurface = Color(0xFF2C2C2C)
)
```

**MaterialThemeManager.kt**:
```kotlin
class MaterialThemeManager(private val context: Context) {

    private val _themeConfig = MutableStateFlow(loadThemeConfig())
    val themeConfig: StateFlow<ThemeConfig> = _themeConfig.asStateFlow()

    fun getColorScheme(darkTheme: Boolean): ColorScheme {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && _themeConfig.value.useDynamicColor -> {
                if (darkTheme) dynamicDarkColorScheme(context)
                else dynamicLightColorScheme(context)
            }
            darkTheme -> darkColorScheme()
            else -> lightColorScheme()
        }
    }

    fun getKeyboardColorScheme(darkTheme: Boolean): KeyboardColorScheme {
        val baseColorScheme = getColorScheme(darkTheme)
        return if (darkTheme) {
            darkKeyboardColorScheme(
                primary = baseColorScheme.primary,
                secondary = baseColorScheme.secondary
            )
        } else {
            lightKeyboardColorScheme(
                primary = baseColorScheme.primary,
                secondary = baseColorScheme.secondary
            )
        }
    }

    fun updateTheme(config: ThemeConfig) {
        _themeConfig.value = config
        saveThemeConfig(config)
    }

    private fun loadThemeConfig(): ThemeConfig {
        val prefs = context.getSharedPreferences("theme", Context.MODE_PRIVATE)
        return ThemeConfig(
            darkMode = prefs.getBoolean("dark_mode", false),
            useDynamicColor = prefs.getBoolean("dynamic_color", true),
            keyBorderRadius = prefs.getFloat("key_border_radius", 8f),
            enableAnimations = prefs.getBoolean("enable_animations", true)
        )
    }

    private fun saveThemeConfig(config: ThemeConfig) {
        context.getSharedPreferences("theme", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("dark_mode", config.darkMode)
            .putBoolean("dynamic_color", config.useDynamicColor)
            .putFloat("key_border_radius", config.keyBorderRadius)
            .putBoolean("enable_animations", config.enableAnimations)
            .apply()
    }
}

data class ThemeConfig(
    val darkMode: Boolean = false,
    val useDynamicColor: Boolean = true,
    val keyBorderRadius: Float = 8f,
    val enableAnimations: Boolean = true
)

@Composable
fun KeyboardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val themeManager = remember { MaterialThemeManager(context) }

    val colorScheme = themeManager.getColorScheme(darkTheme)
    val keyboardColorScheme = themeManager.getKeyboardColorScheme(darkTheme)

    CompositionLocalProvider(LocalKeyboardColorScheme provides keyboardColorScheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = KeyboardTypography,
            shapes = KeyboardShapes,
            content = content
        )
    }
}

val LocalKeyboardColorScheme = staticCompositionLocalOf {
    lightKeyboardColorScheme()
}
```

---

## 🎯 SUCCESS METRICS

### Quantitative Goals

1. **UI Bug Resolution**: 54 bugs → 0 bugs (100% fixed)
   - P0: 24 bugs → 0 bugs
   - P1: 21 bugs → 0 bugs
   - P2: 9 bugs → 0 bugs

2. **Material 3 Coverage**: 21.4% → 100%
   - Phase 1: 21.4% → 50%
   - Phase 2: 50% → 85%
   - Phase 3: 85% → 100%

3. **Performance**:
   - Key press latency: <16ms (60fps)
   - Animation frame drops: <1%
   - Theme switching: <100ms

4. **Accessibility**:
   - All interactive elements: ≥48dp touch targets
   - Color contrast: WCAG AA (4.5:1 for text)
   - Screen reader support: 100% coverage

### Qualitative Goals

1. **Visual Consistency**: All components use Material 3 design language
2. **User Experience**: Smooth, delightful interactions
3. **Maintainability**: Clean, composable, testable code
4. **Accessibility**: Inclusive design for all users

---

## 📋 IMPLEMENTATION CHECKLIST

### Phase 1: Foundation (Week 1-2)
- [ ] **Theme System**
  - [ ] Create MaterialThemeManager.kt
  - [ ] Define KeyboardColorScheme
  - [ ] Implement dynamic color support
  - [ ] Replace all hardcoded colors
  - [ ] Create KeyboardTheme composable
  - [ ] Add theme preview in settings

- [ ] **SuggestionBar**
  - [ ] Rewrite as Material 3 Compose
  - [ ] Add confidence indicators
  - [ ] Implement animations
  - [ ] Add swipe gestures
  - [ ] Fix all 11 bugs
  - [ ] Integration testing

- [ ] **Animation System**
  - [ ] Create AnimationManager.kt
  - [ ] Implement key press animations
  - [ ] Add suggestion animations
  - [ ] Add keyboard transitions
  - [ ] Add swipe trail animation
  - [ ] Performance testing

### Phase 2: Core Components (Week 3-4)
- [ ] **ClipboardHistoryView**
  - [ ] Rewrite as LazyColumn
  - [ ] Create ClipboardCard component
  - [ ] Implement pin/paste/delete
  - [ ] Add animations
  - [ ] Fix all 12 bugs

- [ ] **Keyboard2View**
  - [ ] Add Material ripple effects
  - [ ] Fix gesture exclusion rects
  - [ ] Improve inset handling
  - [ ] Add key press animations
  - [ ] Theme integration

- [ ] **Emoji Components**
  - [ ] Rewrite EmojiGridView as LazyVerticalGrid
  - [ ] Rewrite EmojiGroupButtonsBar
  - [ ] Fix all 11 bugs
  - [ ] Add animations

- [ ] **ClipboardPinView**
  - [ ] Rewrite as Compose
  - [ ] Use Material 3 components
  - [ ] Fix i18n issues
  - [ ] Add accessibility

### Phase 3: Polish (Week 5-6)
- [ ] **Remaining Activities**
  - [ ] Complete NeuralBrowserActivity
  - [ ] Complete NeuralSettingsActivity
  - [ ] Rewrite CustomLayoutEditDialog

- [ ] **Internationalization**
  - [ ] Create strings.xml
  - [ ] Replace hardcoded strings
  - [ ] Add content descriptions
  - [ ] RTL support

- [ ] **Advanced Features**
  - [ ] OneHanded mode UI
  - [ ] Floating keyboard UI
  - [ ] Split keyboard UI
  - [ ] Layout animator
  - [ ] Theme customization

---

## 🧪 TESTING STRATEGY

### Unit Tests
- Theme system color generation
- Animation timing calculations
- Suggestion bar state management

### Integration Tests
- Theme switching updates all components
- Animations don't block UI thread
- Material 3 components render correctly

### Visual Regression Tests
- Screenshot tests for each component
- Dark/light theme variations
- Dynamic color variations

### Accessibility Tests
- Touch target sizes ≥48dp
- Screen reader compatibility
- Color contrast ratios

### Performance Tests
- Key press latency <16ms
- Animation frame rate 60fps
- Memory usage stable

---

## 📦 DEPENDENCIES

### Required Libraries
```gradle
// Material 3
implementation "androidx.compose.material3:material3:1.2.0"
implementation "androidx.compose.material3:material3-window-size-class:1.2.0"

// Compose
implementation "androidx.compose.ui:ui:1.6.0"
implementation "androidx.compose.foundation:foundation:1.6.0"
implementation "androidx.compose.animation:animation:1.6.0"

// Dynamic color
implementation "com.google.android.material:material:1.11.0"
```

### Minimum SDK
- Target: Android 14 (API 34)
- Minimum: Android 8.0 (API 26)
- Dynamic color: Android 12+ (API 31)

---

## 🚀 ROLLOUT PLAN

### Alpha Release (Internal)
- Phase 1 complete
- Basic Material 3 theming functional
- SuggestionBar rewritten
- Animation system implemented

### Beta Release (Early Adopters)
- Phase 2 complete
- All core components Material 3
- Major bugs fixed
- Performance optimized

### Stable Release (All Users)
- Phase 3 complete
- 100% Material 3 coverage
- All bugs fixed
- Full accessibility support
- Comprehensive testing

---

## 📚 REFERENCES

### Material Design 3
- [Material 3 Design](https://m3.material.io/)
- [Material 3 Components](https://m3.material.io/components)
- [Material You](https://material.io/blog/announcing-material-you)
- [Dynamic Color](https://m3.material.io/styles/color/dynamic-color/overview)

### Android Documentation
- [Compose Material 3](https://developer.android.com/jetpack/compose/designsystems/material3)
- [Theming in Compose](https://developer.android.com/jetpack/compose/designsystems/material/theming)
- [Animations in Compose](https://developer.android.com/jetpack/compose/animation)

### CleverKeys
- [COMPLETE_REVIEW_STATUS.md](../COMPLETE_REVIEW_STATUS.md)
- [migrate/todo/ui.md](../../migrate/todo/ui.md)
- [migrate/todo/critical.md](../../migrate/todo/critical.md)

---

**Document Status**: ✅ COMPLETE
**Next Steps**: Begin Phase 1 implementation
**Estimated Timeline**: 6 weeks total
**Team Size**: 2-3 developers
