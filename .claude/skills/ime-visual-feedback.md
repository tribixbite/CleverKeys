# IME Visual Feedback Skill

Use this skill when adding success/error/duplicate feedback to any IME-hosted UI (clipboard, emoji, GIF, tag panels). Explains **why `Toast` is silently broken inside the IME window** and what patterns to use instead.

## The Core Problem

`Toast.makeText(ctx, ..., LENGTH_SHORT).show()` appears to work — no exception, no log warning — but the toast is **never visible to the user** when the keyboard is the foreground view.

**Why:** Android assigns toasts a lower window-layer Z-order (`TYPE_TOAST`, base layer ~2000) than the soft-keyboard input window (`TYPE_INPUT_METHOD`, base layer ~2500). The toast renders *beneath* the keyboard panel, inside the small strip of screen still visible above the keyboard — but when the clipboard pane is expanded, that strip is empty or covered by the target app's text field. Either way, the user sees nothing.

**Exception:** Toasts in regular Activities (e.g., `ClipboardSettingsActivity`, `BackupRestoreActivity`) work fine — the Activity runs in its own window, outside the IME.

## When This Matters

Any of these from within the IME itself:
- "Copied to clipboard"
- "Tag already exists"
- "Entry deleted"
- "Added to pinned" / "Added to todos"
- Any other confirmation or error

All of them will be invisible via Toast.

## Replacement Patterns

### 1. Tab Icon Pulse (Preferred for Cross-Tab Actions)

When an action targets a *different* tab than the current one (e.g., pinning a history entry — the result appears on the Pinned tab), pulse that tab's icon. The user sees motion in their peripheral vision without needing translated text.

**Implementation** (`ClipboardManager.kt`):

```kotlin
private fun pulseTabIcon(icon: ImageView, count: Int = 1) {
    icon.animate().cancel()                        // stop any prior animation
    val restoreAlpha = if (isCurrentlyActiveTab(icon)) 1.0f else 0.5f
    doPulse(icon, count, restoreAlpha)
}

private fun doPulse(icon: ImageView, remaining: Int, restoreAlpha: Float) {
    if (remaining <= 0) {
        // Final restore — return to tab's resting alpha (active vs inactive)
        icon.animate().scaleX(1.0f).scaleY(1.0f).alpha(restoreAlpha)
            .setDuration(150).start()
        return
    }
    icon.animate()
        .scaleX(1.5f).scaleY(1.5f).alpha(1.0f)    // brighten + enlarge
        .setDuration(100)
        .withEndAction {
            icon.animate()
                .scaleX(1.0f).scaleY(1.0f)         // return to size
                .setDuration(100)
                .withEndAction { doPulse(icon, remaining - 1, restoreAlpha) }
                .start()
        }
        .start()
}
```

**Semantics:**
| Pulse count | Meaning | Example |
|-------------|---------|---------|
| 1 | Success — item added to target tab | First pin of a history entry |
| 3 | Duplicate — item already in target tab | Re-pin of an already-pinned entry |

**Timing:** 100ms up + 100ms down per pulse = 200ms each. Single = 200ms, triple = 600ms + final 150ms restore.

**Why recursive:** `withEndAction` chaining lets us chain N pulses without pre-calculating total duration, and guarantees the next pulse doesn't start until the previous one completes even if the view is laid out mid-animation.

**Alpha restore:** Non-active tab icons normally render at 0.5 alpha. We must explicitly restore them — otherwise the final `alpha(1.0f)` sticks and the tab looks "active" permanently. `isCurrentlyActiveTab(icon)` determines which value to restore to.

### 2. Callback Wiring

The view layer (`ClipboardHistoryView`) owns the entry-action logic but doesn't know about the tab icons. The manager layer (`ClipboardManager`) owns the tab icons but doesn't know about per-entry actions. Bridge with a callback:

```kotlin
// In ClipboardHistoryView
var onItemAddedToTab: ((ClipboardTab, Int) -> Unit)? = null

// When the action completes
val added = service.addToTodo(...)
onItemAddedToTab?.invoke(ClipboardTab.TODOS, if (added) 1 else 3)
```

```kotlin
// In ClipboardManager
clipboardHistoryView?.onItemAddedToTab = { tab, pulseCount ->
    val target = when (tab) {
        ClipboardTab.PINNED -> tabPinned
        ClipboardTab.TODOS -> tabTodos
        else -> null
    }
    target?.let { pulseTabIcon(it, pulseCount) }
}

// In cleanup()
clipboardHistoryView?.onItemAddedToTab = null
```

**Service returns Boolean:** The service method (`pinEntry`, `addToTodo`) must return `true` for newly-added and `false` for duplicates so the view can pick the pulse count. If it returns `Unit`, duplicate detection is lost.

### 3. Inline State Visuals (Same-Tab Actions)

When the feedback belongs on the entry itself (e.g., status cycle on a todo), mutate the entry's visual state:
- `[plan]` / `[done]` prefix
- alpha dim (0.4 for completed)
- strikethrough span on completed todos

This is instantaneous and localizable-free because it's structural styling, not translated strings.

### 4. Avoid

- ❌ `Toast.makeText(..., LENGTH_SHORT)` — invisible in IME
- ❌ `Snackbar` — also positioned behind the IME
- ❌ `AlertDialog` — creates separate window, flickers/teleports (see `ime-key-routing.md`)
- ❌ Translated strings in feedback — the tab pulse is language-neutral by design

## Edit-Mode Errors (Where Toast Still Makes Sense)

Edit-mode validation errors use Toast deliberately — they fire from activities or from views that briefly dim the IME. The current `save_edit()` path in `ClipboardHistoryView` does still use Toast for text-too-long errors. If those become a problem, migrate them to an inline TextView above the edit row.

## Testing Feedback Patterns

ADB testing is not allowed (see `CLAUDE.md` testing policy). For animation work:
1. Build and install via `./build-on-termux.sh`
2. Ask the user to verify the pulse count visually
3. Fall back to pure JVM tests for Boolean return values (`pinEntry()` dedup logic) — the animation itself is visual-only and must be human-verified

## Related Skills
- `clipboard-panel-architecture.md` — where tab icons live in the view hierarchy
- `ime-key-routing.md` — why we can't dismiss the IME for a Toast to show
- `clipboard-tag-system.md` / `clipboard-todo-system.md` — specific feedback use cases
