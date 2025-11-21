// MINIMAL onCreate() to replace lines 253-388 in CleverKeysService.kt
// This strips out 130+ initializations and keeps only 3-5 essentials

override fun onCreate() {
    super.onCreate()
    logD("🔧 CleverKeys starting (MINIMAL MODE - crash recovery)...")

    // 1. Initialize lifecycle for Compose support (REQUIRED for Compose views)
    savedStateRegistryController.performRestore(null)
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    logD("✅ Lifecycle initialized")

    try {
        // 2. Initialize ONLY essential configuration (synchronous, no I/O)
        initializeConfiguration()  // Load basic preferences
        logD("✅ Configuration loaded")

        // 3. Load ONE simple keyboard layout (must exist for onCreateInputView)
        loadDefaultKeyboardLayout()  // Loads basic QWERTY
        logD("✅ Default layout loaded")

        // That's it! NO neural prediction, NO suggestion bar, NO databases
        // Just enough to display a basic keyboard

        logD("✅ CleverKeys minimal initialization complete - ready to display")

    } catch (e: Exception) {
        logE("❌ Minimal initialization failed", e)
        // Log the exact error for debugging
        e.printStackTrace()
        throw RuntimeException("CleverKeys minimal mode failed: ${e.message}", e)
    }
}

// ORIGINAL onCreate() had 130+ initialization calls from lines 264-382:
// - initializeConfiguration()           ✅ KEEP (essential)
// - loadDefaultKeyboardLayout()          ✅ KEEP (essential)
// - initializeLanguageManager()          ❌ REMOVE (not essential for display)
// - initializeIMELanguageSelector()      ❌ REMOVE
// - initializeRTLLanguageHandler()       ❌ REMOVE
// - ... 125 MORE REMOVED ...
// - initializePredictionPipeline()       ❌ REMOVE

/*
WHAT THIS ACHIEVES:
- Keyboard WILL display (basic QWERTY layout)
- NO crashes from failed dependencies
- NO timeouts from long initialization
- NO suggestion bar (we'll add later)
- NO neural prediction (we'll add later)
- NO emoji picker (we'll add later)
- NO clipboard (we'll add later)

WHAT TO DO NEXT (once this works):
1. Test this builds and loads
2. Add back suggestion bar (initializeSuggestionBar)
3. Add back emoji picker (initializeEmoji + EmojiPickerView)
4. Add back clipboard (initializeClipboardDatabase + ClipboardHistoryView)
5. Add back neural prediction (initializeNeuralComponents)
6. Gradually restore features one at a time

CRITICAL: Keep onCreateInputView() unchanged - it's well-structured with try-catch
*/
