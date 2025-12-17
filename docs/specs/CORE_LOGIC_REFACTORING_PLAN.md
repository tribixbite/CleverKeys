# Core Logic Refactoring Plan

## 1. Goal
Decouple `Config.kt` and `KeyEventHandler.kt` from Android framework dependencies (`Context`, `SharedPreferences`, `Resources`, `InputConnection`, `KeyEvent`, etc.) to enable pure Kotlin unit testing without Robolectric.

## 2. Refactoring `Config.kt`

**Problem:**
`Config` directly accesses `SharedPreferences`, `Resources`, and `DisplayMetrics`. It mixes *configuration data* (pure) with *configuration loading* (Android).

**Strategy:**
Separate the data holder from the data loader.

**Steps:**
1.  **Extract Data Interface/Class:** Create a pure Kotlin data class `KeyboardConfig` (or similar) that holds all the `@JvmField` values currently in `Config`.
    *   This class should have NO dependencies on `android.*`.
2.  **Create a Configuration Provider Interface:**
    ```kotlin
    interface ConfigurationProvider {
        fun getInt(key: String, def: Int): Int
        fun getFloat(key: String, def: Float): Float
        fun getString(key: String, def: String): String
        fun getBoolean(key: String, def: Boolean): Boolean
        fun getDimensionPixelSize(resId: Int): Int // Abstract resource lookup
    }
    ```
3.  **Implement Android Provider:** Create `AndroidConfigurationProvider` implementing the above, wrapping `SharedPreferences` and `Resources`.
4.  **Refactor Config Constructor:**
    *   Change `Config` constructor to accept `ConfigurationProvider` instead of `SharedPreferences` and `Resources`.
    *   Alternatively, make `Config` a pure data object and move the `refresh()` logic to a `ConfigLoader` class that takes the Android Context and produces a `Config` object.

**Testability:**
*   You can now mock `ConfigurationProvider` in tests to return specific values without needing a real `SharedPreferences` instance or `Context`.

## 3. Refactoring `KeyEventHandler.kt`

**Problem:**
`KeyEventHandler` relies heavily on `InputConnection` (Android interface), `KeyEvent` (Android class), and `EditorInfo`. It also calls `Config.globalConfig()` statically.

**Strategy:**
Abstract the "Input" and "System" interactions.

**Steps:**
1.  **Abstract InputConnection:**
    *   Create an interface `InputTarget` (or reuse/expand `IReceiver` to be more generic).
    *   Methods needed: `commitText`, `deleteSurroundingText`, `sendKeyEvent`, `getTextBeforeCursor`, `setSelection`, `performContextMenuAction`.
    *   `KeyEventHandler` should talk to `InputTarget`, not `InputConnection` directly.
2.  **Abstract KeyEvents:**
    *   `KeyEvent` is an Android class. Creating them in tests is hard (Robolectric helps, but we want to avoid it).
    *   Create a wrapper `KeyboardEvent` or generic `Event` data class for internal logic.
    *   Only convert to android `KeyEvent` at the very edge (in the `InputTarget` implementation).
3.  **Dependency Injection for Config:**
    *   Instead of calling `Config.globalConfig()`, pass the `KeyboardConfig` object (from step 2) into the `KeyEventHandler` constructor.
4.  **Abstract Handlers:**
    *   `Handler` and `Looper` are Android classes.
    *   Use Kotlin Coroutines (`Dispatcher`) or a custom `Scheduler` interface for delayed tasks (like macros).

**Testability:**
*   You can create a `MockInputTarget` that records calls (`commitText`, `deleteSurroundingText`) in a list.
*   You can pass a custom `KeyboardConfig`.
*   You can verify logic like "double-space-to-period" by calling `key_up` and checking `MockInputTarget.committedText`.

## 4. Specific Refactoring Tasks (Immediate)

### A. Config.kt
1.  **Create `SettingsProvider` Interface:**
    ```kotlin
    interface SettingsProvider {
        fun getBoolean(key: String, def: Boolean): Boolean
        fun getInt(key: String, def: Int): Int
        fun getFloat(key: String, def: Float): Float
        fun getString(key: String, def: String): String
    }
    ```
2.  **Update `Config`:**
    *   Make `Config` take `SettingsProvider` in constructor.
    *   Remove `Context`/`SharedPreferences` from constructor.
    *   Update `refresh()` to use `SettingsProvider`.

### B. KeyEventHandler.kt
1.  **Create `InputInterface`:**
    ```kotlin
    interface InputInterface {
        fun commitText(text: CharSequence, newCursorPosition: Int): Boolean
        fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean
        fun getTextBeforeCursor(n: Int, flags: Int): CharSequence?
        fun sendKeyEvent(keyCode: Int, action: Int, metaState: Int): Boolean
        // ... other InputConnection methods
    }
    ```
2.  **Update `IReceiver`:**
    *   Replace `getCurrentInputConnection(): InputConnection?` with `getInputInterface(): InputInterface?`.
3.  **Refactor `sendKeyevent`:**
    *   Move the creation of `android.view.KeyEvent` object *out* of `KeyEventHandler` and into the implementation of `InputInterface` (e.g., `AndroidInputConnectionWrapper`).

## 5. Summary of Files to Change
1.  `src/main/kotlin/tribixbite/cleverkeys/Config.kt`
2.  `src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt`
3.  `src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt` (To implement the new interfaces and pass them)
