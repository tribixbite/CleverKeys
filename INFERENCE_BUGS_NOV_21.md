## Critical Issue Found - Nov 21, 2025

### Problem
CleverKeys keyboard service initializes successfully but keyboard view doesn't render.

### Root Cause Analysis
1. **onCreate() was in test mode**: Had ULTRA-MINIMAL stub that did no initialization
2. **Fixed**: Restored proper initialization (lifecycle + config + layout loading)
3. **New issue**: onCreateInputView() is never being called by Android system

### Evidence
```
Service initialization (WORKING):
✅ CleverKeys service starting...
✅ Lifecycle initialized
✅ Configuration loaded
✅ Default layout loaded
✅ CleverKeys initialization complete
✅ Input started: package=com.microsoft.emmx

View creation (NOT WORKING):
❌ onCreateInputView() - never called
❌ Keyboard view - not rendered
❌ No keyboard visible on screen
```

### Hypothesis
The Android InputMethodService may be caching a null view from previous failed attempts, or there's an issue with how the service declares its capabilities.

### Next Steps
1. Check if onStartInputView() is being called
2. Investigate InputMethodService lifecycle methods
3. May need to check AndroidManifest.xml for proper IME declaration
4. Consider if the service needs to override additional lifecycle methods

### Commits
- 38d74db2: Restored onCreate + added debug logging
- 83e045b9: Added branding to Keyboard2View (spacebar)

