package tribixbite.cleverkeys.customization

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import tribixbite.cleverkeys.KeyValue

/**
 * Executes custom short swipe actions.
 * Handles TEXT, COMMAND, and KEY_EVENT action types.
 */
class CustomShortSwipeExecutor(private val context: Context) {

    /**
     * Execute a custom short swipe mapping.
     *
     * @param mapping The mapping to execute
     * @param inputConnection The input connection to the text field
     * @param editorInfo The editor info for the current text field
     * @return true if the action was executed successfully
     */
    fun execute(
        mapping: ShortSwipeMapping,
        inputConnection: InputConnection?,
        editorInfo: EditorInfo?
    ): Boolean {
        if (inputConnection == null) {
            Log.w(TAG, "Cannot execute mapping: no input connection")
            return false
        }

        return when (mapping.actionType) {
            ActionType.TEXT -> executeTextInput(mapping.actionValue, inputConnection)
            ActionType.COMMAND -> executeCommandByName(mapping.actionValue, inputConnection, editorInfo)
            ActionType.KEY_EVENT -> executeKeyEvent(mapping.getKeyEventCode(), inputConnection)
            ActionType.INTENT -> executeIntent(mapping.actionValue)
        }
    }

    /**
     * Execute a command by name, supporting both AvailableCommand enum (SCREAMING_SNAKE)
     * and CommandRegistry names (camelCase).
     */
    private fun executeCommandByName(
        commandName: String,
        inputConnection: InputConnection,
        editorInfo: EditorInfo?
    ): Boolean {
        // First try the legacy AvailableCommand enum
        val legacyCommand = AvailableCommand.fromString(commandName)
        if (legacyCommand != null) {
            return executeCommand(legacyCommand, inputConnection, editorInfo)
        }

        // Try CommandRegistry for the full 143+ command set
        val registryCommand = CommandRegistry.getByName(commandName)
        if (registryCommand != null) {
            return executeRegistryCommand(registryCommand, inputConnection, editorInfo)
        }

        Log.w(TAG, "Unknown command: $commandName")
        return false
    }

    /**
     * Execute an intent action.
     */
    private fun executeIntent(intentJson: String): Boolean {
        return try {
            val intentDef = com.google.gson.Gson().fromJson(intentJson, IntentDefinition::class.java)

            // Validate intent before execution
            val validationError = validateIntent(intentDef)
            if (validationError != null) {
                Log.w(TAG, "Intent validation failed: $validationError")
                return false
            }

            val intent = android.content.Intent()

            // Set basic fields
            if (!intentDef.action.isNullOrBlank()) intent.action = intentDef.action
            if (!intentDef.data.isNullOrBlank()) intent.data = android.net.Uri.parse(intentDef.data)
            if (!intentDef.type.isNullOrBlank()) intent.type = intentDef.type
            if (!intentDef.packageName.isNullOrBlank()) intent.`package` = intentDef.packageName

            // Set component if both package and class are provided
            if (!intentDef.packageName.isNullOrBlank() && !intentDef.className.isNullOrBlank()) {
                intent.component = android.content.ComponentName(intentDef.packageName, intentDef.className)
            }

            // Set extras
            intentDef.extras?.forEach { (key, value) ->
                intent.putExtra(key, value)
            }

            // Add flags
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

            when (intentDef.targetType) {
                IntentTargetType.ACTIVITY -> {
                    // Check if activity can be resolved before starting
                    if (intent.resolveActivity(context.packageManager) == null && intentDef.className.isNullOrBlank()) {
                        Log.w(TAG, "No activity found to handle intent: ${intentDef.name}")
                        return false
                    }
                    context.startActivity(intent)
                }
                IntentTargetType.SERVICE -> context.startService(intent)
                IntentTargetType.BROADCAST -> context.sendBroadcast(intent)
            }

            Log.d(TAG, "Executed INTENT action: ${intentDef.name}")
            true
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e(TAG, "Activity not found for intent", e)
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for intent", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute INTENT action", e)
            false
        }
    }

    /**
     * Validate an IntentDefinition before execution.
     * @return Error message if invalid, null if valid.
     */
    private fun validateIntent(intentDef: IntentDefinition): String? {
        // Must have either action or package+class
        if (intentDef.action.isNullOrBlank() && intentDef.packageName.isNullOrBlank()) {
            return "Intent must have either an action or a package name"
        }

        // If package specified, check it exists (for activities)
        if (!intentDef.packageName.isNullOrBlank() && intentDef.targetType == IntentTargetType.ACTIVITY) {
            try {
                context.packageManager.getPackageInfo(intentDef.packageName, 0)
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                return "Package not installed: ${intentDef.packageName}"
            }
        }

        // Validate URI format if data is provided
        if (!intentDef.data.isNullOrBlank()) {
            try {
                android.net.Uri.parse(intentDef.data)
            } catch (e: Exception) {
                return "Invalid URI: ${intentDef.data}"
            }
        }

        return null
    }

    /**
     * Check if an intent is valid and can be executed.
     * Useful for UI validation before saving.
     */
    fun canExecuteIntent(intentDef: IntentDefinition): Pair<Boolean, String?> {
        val error = validateIntent(intentDef)
        return Pair(error == null, error)
    }

    /**
     * Execute a command from the CommandRegistry.
     */
    private fun executeRegistryCommand(
        command: CommandRegistry.Command,
        inputConnection: InputConnection,
        editorInfo: EditorInfo?
    ): Boolean {
        return try {
            // Map CommandRegistry commands to actions
            val success = when (command.name) {
                // Clipboard operations
                "copy" -> inputConnection.performContextMenuAction(android.R.id.copy)
                "paste" -> inputConnection.performContextMenuAction(android.R.id.paste)
                "cut" -> inputConnection.performContextMenuAction(android.R.id.cut)
                "selectAll" -> inputConnection.performContextMenuAction(android.R.id.selectAll)
                "pasteAsPlainText" -> inputConnection.performContextMenuAction(android.R.id.paste)
                "shareText" -> inputConnection.performContextMenuAction(android.R.id.copy) // Copy first, then share handled elsewhere

                // Edit operations
                "undo" -> inputConnection.performContextMenuAction(android.R.id.undo)
                "redo" -> inputConnection.performContextMenuAction(android.R.id.redo)

                // Cursor movement - character
                "left" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_DPAD_LEFT)
                "right" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_DPAD_RIGHT)
                "up" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_DPAD_UP)
                "down" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_DPAD_DOWN)

                // Cursor movement - line
                "home" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_MOVE_HOME)
                "end" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_MOVE_END)

                // Cursor movement - word
                "word_left" -> sendKeyEventWithModifier(inputConnection, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_CTRL_ON)
                "word_right" -> sendKeyEventWithModifier(inputConnection, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_CTRL_ON)

                // Page navigation
                "page_up" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_PAGE_UP)
                "page_down" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_PAGE_DOWN)

                // Delete operations
                "backspace" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_DEL)
                "delete" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_FORWARD_DEL)
                "delete_word" -> sendKeyEventWithModifier(inputConnection, KeyEvent.KEYCODE_DEL, KeyEvent.META_CTRL_ON)
                "delete_line" -> {
                    // Select to beginning of line, then delete
                    sendKeyEventWithModifier(inputConnection, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.META_SHIFT_ON)
                    sendKeyEvent(inputConnection, KeyEvent.KEYCODE_DEL)
                }

                // Special keys
                "enter" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_ENTER)
                "tab" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_TAB)
                "esc" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_ESCAPE)
                "space" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_SPACE)

                // Function keys
                "f1" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_F1)
                "f2" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_F2)
                "f3" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_F3)
                "f4" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_F4)
                "f5" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_F5)
                "f6" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_F6)
                "f7" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_F7)
                "f8" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_F8)
                "f9" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_F9)
                "f10" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_F10)
                "f11" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_F11)
                "f12" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_F12)

                // Selection with shift
                "selectLeft" -> sendKeyEventWithModifier(inputConnection, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_SHIFT_ON)
                "selectRight" -> sendKeyEventWithModifier(inputConnection, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_SHIFT_ON)
                "selectUp" -> sendKeyEventWithModifier(inputConnection, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_SHIFT_ON)
                "selectDown" -> sendKeyEventWithModifier(inputConnection, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.META_SHIFT_ON)
                "selectWordLeft" -> sendKeyEventWithModifier(inputConnection, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
                "selectWordRight" -> sendKeyEventWithModifier(inputConnection, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
                "selectToLineStart" -> sendKeyEventWithModifier(inputConnection, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.META_SHIFT_ON)
                "selectToLineEnd" -> sendKeyEventWithModifier(inputConnection, KeyEvent.KEYCODE_MOVE_END, KeyEvent.META_SHIFT_ON)

                // Document navigation
                "doc_home" -> sendKeyEventWithModifier(inputConnection, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.META_CTRL_ON)
                "doc_end" -> sendKeyEventWithModifier(inputConnection, KeyEvent.KEYCODE_MOVE_END, KeyEvent.META_CTRL_ON)

                // Media controls
                "media_play_pause" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                "media_play" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_MEDIA_PLAY)
                "media_pause" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_MEDIA_PAUSE)
                "media_stop" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_MEDIA_STOP)
                "media_next" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_MEDIA_NEXT)
                "media_previous" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "media_rewind" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_MEDIA_REWIND)
                "media_fast_forward" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
                "media_record" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_MEDIA_RECORD)

                // Volume controls
                "volume_up" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_VOLUME_UP)
                "volume_down" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_VOLUME_DOWN)
                "volume_mute" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_VOLUME_MUTE)

                // Brightness
                "brightness_up" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_BRIGHTNESS_UP)
                "brightness_down" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_BRIGHTNESS_DOWN)

                // Zoom
                "zoom_in" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_ZOOM_IN)
                "zoom_out" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_ZOOM_OUT)

                // System/app keys
                "search" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_SEARCH)
                "calculator" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_CALCULATOR)
                "calendar" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_CALENDAR)
                "contacts" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_CONTACTS)
                "explorer" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_EXPLORER)
                "notification" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_NOTIFICATION)

                // Menu key
                "menu" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_MENU)
                "insert" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_INSERT)
                "scroll_lock" -> sendKeyEvent(inputConnection, KeyEvent.KEYCODE_SCROLL_LOCK)

                // Language toggle commands (v1.2.0) - handled via KeyValue
                "primaryLangToggle", "secondaryLangToggle",
                // Keyboard event commands - handled via KeyValue
                "config", "switch_text", "switch_numeric", "switch_emoji",
                "switch_back_emoji", "switch_clipboard", "switch_back_clipboard",
                "switch_forward", "switch_backward", "switch_greekmath",
                "change_method", "change_method_prev", "action", "capslock",
                "voice_typing", "voice_typing_chooser",
                // Text action commands
                "textAssist", "replaceText", "showTextMenu",
                // Timestamp commands
                "timestamp_date", "timestamp_time", "timestamp_datetime",
                "timestamp_time_seconds", "timestamp_date_short", "timestamp_date_long",
                "timestamp_time_12h", "timestamp_iso" -> {
                    // These commands need KeyValue-based handling through the keyboard service
                    // Return the KeyValue result which will be processed by Keyboard2View
                    val keyValue = KeyValue.getKeyByName(command.name)
                    if (keyValue != null) {
                        Log.d(TAG, "Command ${command.name} -> KeyValue routing")
                        // Signal that this needs keyboard-level handling
                        // The actual execution happens in Keyboard2View.executeCustomShortSwipeMapping()
                        false // Return false to indicate keyboard-level handling needed
                    } else {
                        Log.w(TAG, "No KeyValue found for command: ${command.name}")
                        false
                    }
                }

                // Fallback: try to get KeyValue for character-based commands
                // (Hebrew niqqud, Arabic vowels, combining diacritics, etc.)
                else -> {
                    val keyValue = KeyValue.getKeyByName(command.name)
                    if (keyValue != null) {
                        when (keyValue.getKind()) {
                            KeyValue.Kind.Char -> {
                                inputConnection.commitText(keyValue.getChar().toString(), 1)
                            }
                            KeyValue.Kind.String -> {
                                inputConnection.commitText(keyValue.getString(), 1)
                            }
                            KeyValue.Kind.Keyevent -> {
                                sendKeyEvent(inputConnection, keyValue.getKeyevent())
                            }
                            else -> {
                                Log.w(TAG, "Unsupported KeyValue kind for command: ${command.name}")
                                false
                            }
                        }
                    } else {
                        Log.w(TAG, "Unimplemented CommandRegistry command: ${command.name}")
                        false
                    }
                }
            }
            Log.d(TAG, "Executed registry command: ${command.name} -> $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute registry command: ${command.name}", e)
            false
        }
    }

    /**
     * Execute a text input action - insert text directly.
     */
    private fun executeTextInput(text: String, inputConnection: InputConnection): Boolean {
        return try {
            inputConnection.commitText(text, 1)
            Log.d(TAG, "Executed TEXT action: $text")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute TEXT action", e)
            false
        }
    }

    /**
     * Execute a command action.
     */
    private fun executeCommand(
        command: AvailableCommand?,
        inputConnection: InputConnection,
        editorInfo: EditorInfo?
    ): Boolean {
        if (command == null) {
            Log.w(TAG, "Cannot execute null command")
            return false
        }

        return try {
            when (command) {
                // Clipboard operations
                AvailableCommand.COPY -> {
                    inputConnection.performContextMenuAction(android.R.id.copy)
                }
                AvailableCommand.PASTE -> {
                    inputConnection.performContextMenuAction(android.R.id.paste)
                }
                AvailableCommand.CUT -> {
                    inputConnection.performContextMenuAction(android.R.id.cut)
                }
                AvailableCommand.SELECT_ALL -> {
                    inputConnection.performContextMenuAction(android.R.id.selectAll)
                }

                // Undo/Redo
                AvailableCommand.UNDO -> {
                    inputConnection.performContextMenuAction(android.R.id.undo)
                }
                AvailableCommand.REDO -> {
                    inputConnection.performContextMenuAction(android.R.id.redo)
                }

                // Cursor movement - character
                AvailableCommand.CURSOR_LEFT -> {
                    sendKeyEvent(inputConnection, KeyEvent.KEYCODE_DPAD_LEFT)
                }
                AvailableCommand.CURSOR_RIGHT -> {
                    sendKeyEvent(inputConnection, KeyEvent.KEYCODE_DPAD_RIGHT)
                }
                AvailableCommand.CURSOR_UP -> {
                    sendKeyEvent(inputConnection, KeyEvent.KEYCODE_DPAD_UP)
                }
                AvailableCommand.CURSOR_DOWN -> {
                    sendKeyEvent(inputConnection, KeyEvent.KEYCODE_DPAD_DOWN)
                }

                // Cursor movement - line
                AvailableCommand.CURSOR_HOME -> {
                    sendKeyEvent(inputConnection, KeyEvent.KEYCODE_MOVE_HOME)
                }
                AvailableCommand.CURSOR_END -> {
                    sendKeyEvent(inputConnection, KeyEvent.KEYCODE_MOVE_END)
                }

                // Cursor movement - document (Ctrl+Home/End)
                AvailableCommand.CURSOR_DOC_START -> {
                    sendKeyEventWithModifier(
                        inputConnection,
                        KeyEvent.KEYCODE_MOVE_HOME,
                        KeyEvent.META_CTRL_ON
                    )
                }
                AvailableCommand.CURSOR_DOC_END -> {
                    sendKeyEventWithModifier(
                        inputConnection,
                        KeyEvent.KEYCODE_MOVE_END,
                        KeyEvent.META_CTRL_ON
                    )
                }

                // Cursor movement - word (Ctrl+Arrow)
                AvailableCommand.WORD_LEFT -> {
                    sendKeyEventWithModifier(
                        inputConnection,
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.META_CTRL_ON
                    )
                }
                AvailableCommand.WORD_RIGHT -> {
                    sendKeyEventWithModifier(
                        inputConnection,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.META_CTRL_ON
                    )
                }

                // Delete operations
                AvailableCommand.DELETE_WORD -> {
                    sendKeyEventWithModifier(
                        inputConnection,
                        KeyEvent.KEYCODE_DEL,
                        KeyEvent.META_CTRL_ON
                    )
                }

                // System commands - these return KeyValue for special handling
                AvailableCommand.SWITCH_IME -> {
                    // This needs to be handled at a higher level (KeyEventHandler)
                    // Return a special result that signals this
                    Log.d(TAG, "SWITCH_IME command - requires IME service handling")
                    false // Let the caller know this needs special handling
                }
                AvailableCommand.VOICE_INPUT -> {
                    // This needs to be handled at a higher level
                    Log.d(TAG, "VOICE_INPUT command - requires IME service handling")
                    false
                }

                // Layout switching commands - require keyboard service handling
                AvailableCommand.SWITCH_FORWARD -> {
                    Log.d(TAG, "SWITCH_FORWARD command - requires keyboard service handling")
                    false // Let the caller know this needs special handling
                }
                AvailableCommand.SWITCH_BACKWARD -> {
                    Log.d(TAG, "SWITCH_BACKWARD command - requires keyboard service handling")
                    false
                }

                // Text processing commands - require keyboard view handling
                AvailableCommand.TEXT_ASSIST -> {
                    Log.d(TAG, "TEXT_ASSIST command - requires keyboard view handling")
                    false // Let the caller know this needs special handling
                }
                AvailableCommand.REPLACE_TEXT -> {
                    Log.d(TAG, "REPLACE_TEXT command - requires keyboard view handling")
                    false
                }
                AvailableCommand.SHOW_TEXT_MENU -> {
                    // Select word under cursor to trigger floating toolbar
                    // First, we'll select the word to make the toolbar appear
                    Log.d(TAG, "SHOW_TEXT_MENU command - selecting word to show toolbar")
                    false // Let the caller know this needs special handling
                }

                // Language switching commands - require keyboard service handling
                AvailableCommand.PRIMARY_LANG_TOGGLE -> {
                    Log.d(TAG, "PRIMARY_LANG_TOGGLE command - requires keyboard service handling")
                    false
                }
                AvailableCommand.SECONDARY_LANG_TOGGLE -> {
                    Log.d(TAG, "SECONDARY_LANG_TOGGLE command - requires keyboard service handling")
                    false
                }
            }
            Log.d(TAG, "Executed COMMAND action: ${command.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute COMMAND action: ${command.name}", e)
            false
        }
    }

    /**
     * Execute a raw key event action.
     */
    private fun executeKeyEvent(keyCode: Int?, inputConnection: InputConnection): Boolean {
        if (keyCode == null) {
            Log.w(TAG, "Cannot execute null key event code")
            return false
        }

        return try {
            sendKeyEvent(inputConnection, keyCode)
            Log.d(TAG, "Executed KEY_EVENT action: $keyCode")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute KEY_EVENT action", e)
            false
        }
    }

    /**
     * Send a simple key event (down + up).
     */
    private fun sendKeyEvent(inputConnection: InputConnection, keyCode: Int): Boolean {
        val downTime = System.currentTimeMillis()
        val downEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        val upEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_UP, keyCode, 0)

        return inputConnection.sendKeyEvent(downEvent) && inputConnection.sendKeyEvent(upEvent)
    }

    /**
     * Send a key event with modifier keys (e.g., Ctrl, Shift).
     */
    private fun sendKeyEventWithModifier(
        inputConnection: InputConnection,
        keyCode: Int,
        metaState: Int
    ): Boolean {
        val downTime = System.currentTimeMillis()
        val downEvent = KeyEvent(
            downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0, metaState
        )
        val upEvent = KeyEvent(
            downTime, downTime, KeyEvent.ACTION_UP, keyCode, 0, metaState
        )

        return inputConnection.sendKeyEvent(downEvent) && inputConnection.sendKeyEvent(upEvent)
    }

    /**
     * Convert a command to a KeyValue for integration with existing keyboard logic.
     * Returns null for commands that should be executed directly via InputConnection.
     */
    fun commandToKeyValue(command: AvailableCommand): KeyValue? {
        return when (command) {
            AvailableCommand.SWITCH_IME -> KeyValue.getKeyByName("switch_im_picker")
            AvailableCommand.VOICE_INPUT -> KeyValue.getKeyByName("voice_input")
            else -> null // Execute directly via InputConnection
        }
    }

    companion object {
        private const val TAG = "ShortSwipeExecutor"
    }
}
