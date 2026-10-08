package tribixbite.cleverkeys.customization

import android.view.KeyEvent
import androidx.annotation.StringRes
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.ResultText

/**
 * Comprehensive registry of ALL available keyboard commands.
 *
 * This registry enumerates every command from KeyValue.kt's getSpecialKeyByName() function,
 * organized into searchable categories. Used by the Short Swipe Customization UI to present
 * the COMPLETE list of available actions to users.
 *
 * Display text is localized (2026-09-30): each command's name and description are string
 * resources `cmd_<id>` / `cmd_<id>_desc` (ids snake-cased; `b(`-style ids spelled out, see
 * `CommandCatalogLocalizationTest`), and each category's header is `command_category_<cat>`.
 * The command [Command.name] ids themselves never change: they are persisted in short-swipe
 * bindings and backups.
 *
 * Categories:
 * - Modifiers (shift, ctrl, alt, meta, fn)
 * - Events (switch layouts, config, etc.)
 * - Key Events (esc, enter, arrows, function keys, etc.)
 * - Editing (copy, paste, undo, cursor movement, etc.)
 * - Characters (special chars, spaces, bidi markers)
 * - Diacritics (combining marks, dead keys)
 */
object CommandRegistry {

    /**
     * Represents a single command that can be bound to a short swipe.
     */
    data class Command(
        /** Internal name used in KeyValue.getKeyByName() */
        val name: String,
        /** Display name, a string resource keyed by [name] (`cmd_<id>`); localized per locale. */
        @StringRes val nameRes: Int,
        /** One-line description, `cmd_<id>_desc`; localized per locale. */
        @StringRes val descriptionRes: Int,
        /** Category for grouping in UI */
        val category: Category,
        /** Symbol shown on keyboard (from KeyValue font) */
        val symbol: String? = null,
        /**
         * Search keywords for filtering. English on purpose: they are extra search aids next to
         * the localized name and description (and the English ones), not user-visible text.
         */
        val keywords: List<String> = emptyList()
    )

    /**
     * Command categories for UI organization.
     */
    enum class Category(@StringRes val labelRes: Int, val sortOrder: Int) {
        CLIPBOARD(R.string.command_category_clipboard, 0),
        EDITING(R.string.command_category_editing, 1),
        CURSOR(R.string.command_category_cursor, 2),
        NAVIGATION(R.string.command_category_navigation, 3),
        SELECTION(R.string.command_category_selection, 4),
        DELETE(R.string.command_category_delete, 5),
        EVENTS(R.string.command_category_events, 6),
        MODIFIERS(R.string.command_category_modifiers, 7),
        FUNCTION_KEYS(R.string.command_category_function_keys, 8),
        SPECIAL_KEYS(R.string.command_category_special_keys, 9),
        MEDIA(R.string.command_category_media, 10),
        SYSTEM(R.string.command_category_system, 11),
        SPACES(R.string.command_category_spaces, 12),
        DIACRITICS(R.string.command_category_diacritics, 13),
        DIACRITICS_SLAVONIC(R.string.command_category_diacritics_slavonic, 14),
        DIACRITICS_ARABIC(R.string.command_category_diacritics_arabic, 15),
        HEBREW(R.string.command_category_hebrew, 16),
        TEXT(R.string.command_category_text, 17),
        LANGUAGE(R.string.command_category_language, 18),
        TEXT_ACTIONS(R.string.command_category_text_actions, 19),
        TIMESTAMP(R.string.command_category_timestamp, 20)
    }

    /**
     * Complete list of ALL available commands.
     * Extracted from KeyValue.getSpecialKeyByName() in KeyValue.kt
     */
    val ALL_COMMANDS: List<Command> = listOf(
        // ========== CLIPBOARD ==========
        Command("copy", R.string.cmd_copy, R.string.cmd_copy_desc, Category.CLIPBOARD,
            keywords = listOf("copy", "clipboard", "ctrl+c")),
        // #156: Private copy — stores the selection in CleverKeys' clipboard only, never the OS clipboard.
        Command("copy_private", R.string.cmd_copy_private, R.string.cmd_copy_private_desc, Category.CLIPBOARD,
            keywords = listOf("private", "copy", "clipboard", "secure", "lock")),
        Command("append_possessive", R.string.cmd_append_possessive, R.string.cmd_append_possessive_desc, Category.EDITING),
        Command("append_apostrophe", R.string.cmd_append_apostrophe, R.string.cmd_append_apostrophe_desc, Category.EDITING),
        Command("clear_clipboard", R.string.cmd_clear_clipboard, R.string.cmd_clear_clipboard_desc, Category.CLIPBOARD,
            keywords = listOf("clear", "clipboard", "system", "empty")),
        Command("paste", R.string.cmd_paste, R.string.cmd_paste_desc, Category.CLIPBOARD,
            keywords = listOf("paste", "clipboard", "ctrl+v")),
        Command("cut", R.string.cmd_cut, R.string.cmd_cut_desc, Category.CLIPBOARD,
            keywords = listOf("cut", "clipboard", "ctrl+x")),
        Command("selectAll", R.string.cmd_select_all, R.string.cmd_select_all_desc, Category.CLIPBOARD,
            keywords = listOf("select", "all", "ctrl+a")),
        Command("pasteAsPlainText", R.string.cmd_paste_as_plain_text, R.string.cmd_paste_as_plain_text_desc, Category.CLIPBOARD,
            keywords = listOf("paste", "plain", "text", "no format")),
        Command("shareText", R.string.cmd_share_text, R.string.cmd_share_text_desc, Category.CLIPBOARD,
            keywords = listOf("share", "send")),
        // Pinned clipboard entry insertion — dynamically pastes the Nth pinned entry.
        // These dispatch by NAME (CustomShortSwipeExecutor), not by KeyValue, so each
        // declares a symbol — without one the palette label degraded to "past" (name.take(4)).
        Command("paste_pinned_1", R.string.cmd_paste_pinned_1, R.string.cmd_paste_pinned_1_desc, Category.CLIPBOARD,
            symbol = "📌1",
            keywords = listOf("pin", "pinned", "clipboard", "paste", "1", "first")),
        Command("paste_pinned_2", R.string.cmd_paste_pinned_2, R.string.cmd_paste_pinned_2_desc, Category.CLIPBOARD,
            symbol = "📌2",
            keywords = listOf("pin", "pinned", "clipboard", "paste", "2", "second")),
        Command("paste_pinned_3", R.string.cmd_paste_pinned_3, R.string.cmd_paste_pinned_3_desc, Category.CLIPBOARD,
            symbol = "📌3",
            keywords = listOf("pin", "pinned", "clipboard", "paste", "3", "third")),
        Command("paste_pinned_4", R.string.cmd_paste_pinned_4, R.string.cmd_paste_pinned_4_desc, Category.CLIPBOARD,
            symbol = "📌4",
            keywords = listOf("pin", "pinned", "clipboard", "paste", "4", "fourth")),
        Command("paste_pinned_5", R.string.cmd_paste_pinned_5, R.string.cmd_paste_pinned_5_desc, Category.CLIPBOARD,
            symbol = "📌5",
            keywords = listOf("pin", "pinned", "clipboard", "paste", "5", "fifth")),

        // ========== EDITING ==========
        Command("undo", R.string.cmd_undo, R.string.cmd_undo_desc, Category.EDITING,
            keywords = listOf("undo", "ctrl+z", "back", "revert")),
        Command("redo", R.string.cmd_redo, R.string.cmd_redo_desc, Category.EDITING,
            keywords = listOf("redo", "ctrl+y", "forward")),
        Command("clear", R.string.cmd_clear, R.string.cmd_clear_desc, Category.EDITING,
            keywords = listOf("clear", "erase", "wipe", "empty", "select all delete")),
        Command("delete_word", R.string.cmd_delete_word, R.string.cmd_delete_word_desc, Category.DELETE,
            keywords = listOf("delete", "word", "backspace", "ctrl+backspace")),
        Command("forward_delete_word", R.string.cmd_forward_delete_word, R.string.cmd_forward_delete_word_desc, Category.DELETE,
            keywords = listOf("delete", "word", "forward", "ctrl+delete")),
        Command("delete_last_word", R.string.cmd_delete_last_word, R.string.cmd_delete_last_word_desc, Category.DELETE,
            keywords = listOf("delete", "word", "last", "smart")),
        Command("backspace", R.string.cmd_backspace, R.string.cmd_backspace_desc, Category.DELETE,
            keywords = listOf("backspace", "delete", "back")),
        Command("delete", R.string.cmd_delete, R.string.cmd_delete_desc, Category.DELETE,
            keywords = listOf("delete", "forward", "del")),

        // ========== CURSOR MOVEMENT ==========
        Command("cursor_left", R.string.cmd_cursor_left, R.string.cmd_cursor_left_desc, Category.CURSOR,
            keywords = listOf("cursor", "left", "arrow", "move")),
        Command("cursor_right", R.string.cmd_cursor_right, R.string.cmd_cursor_right_desc, Category.CURSOR,
            keywords = listOf("cursor", "right", "arrow", "move")),
        Command("cursor_up", R.string.cmd_cursor_up, R.string.cmd_cursor_up_desc, Category.CURSOR,
            keywords = listOf("cursor", "up", "arrow", "move")),
        Command("cursor_down", R.string.cmd_cursor_down, R.string.cmd_cursor_down_desc, Category.CURSOR,
            keywords = listOf("cursor", "down", "arrow", "move")),
        Command("left", R.string.cmd_left, R.string.cmd_left_desc, Category.CURSOR,
            keywords = listOf("left", "arrow", "dpad")),
        Command("right", R.string.cmd_right, R.string.cmd_right_desc, Category.CURSOR,
            keywords = listOf("right", "arrow", "dpad")),
        Command("up", R.string.cmd_up, R.string.cmd_up_desc, Category.CURSOR,
            keywords = listOf("up", "arrow", "dpad")),
        Command("down", R.string.cmd_down, R.string.cmd_down_desc, Category.CURSOR,
            keywords = listOf("down", "arrow", "dpad")),

        // ========== NAVIGATION ==========
        Command("home", R.string.cmd_home, R.string.cmd_home_desc, Category.NAVIGATION,
            keywords = listOf("home", "line", "start", "beginning")),
        Command("end", R.string.cmd_end, R.string.cmd_end_desc, Category.NAVIGATION,
            keywords = listOf("end", "line", "end")),
        Command("doc_home", R.string.cmd_doc_home, R.string.cmd_doc_home_desc, Category.NAVIGATION,
            keywords = listOf("home", "document", "start", "beginning", "top")),
        Command("doc_end", R.string.cmd_doc_end, R.string.cmd_doc_end_desc, Category.NAVIGATION,
            keywords = listOf("end", "document", "bottom")),
        Command("page_up", R.string.cmd_page_up, R.string.cmd_page_up_desc, Category.NAVIGATION,
            keywords = listOf("page", "up", "scroll")),
        Command("page_down", R.string.cmd_page_down, R.string.cmd_page_down_desc, Category.NAVIGATION,
            keywords = listOf("page", "down", "scroll")),

        // ========== SELECTION ==========
        Command("selection_cursor_left", R.string.cmd_selection_cursor_left, R.string.cmd_selection_cursor_left_desc, Category.SELECTION,
            keywords = listOf("select", "left", "extend", "shift")),
        Command("selection_cursor_right", R.string.cmd_selection_cursor_right, R.string.cmd_selection_cursor_right_desc, Category.SELECTION,
            keywords = listOf("select", "right", "extend", "shift")),
        Command("selection_cancel", R.string.cmd_selection_cancel, R.string.cmd_selection_cancel_desc, Category.SELECTION,
            keywords = listOf("select", "cancel", "deselect", "esc")),

        // ========== KEYBOARD EVENTS ==========
        Command("config", R.string.cmd_config, R.string.cmd_config_desc, Category.EVENTS,
            keywords = listOf("settings", "config", "configure", "options")),
        Command("switch_text", R.string.cmd_switch_text, R.string.cmd_switch_text_desc, Category.EVENTS,
            keywords = listOf("switch", "text", "abc", "letters")),
        Command("switch_numeric", R.string.cmd_switch_numeric, R.string.cmd_switch_numeric_desc, Category.EVENTS,
            keywords = listOf("switch", "numbers", "numeric", "123", "symbols")),
        Command("switch_emoji", R.string.cmd_switch_emoji, R.string.cmd_switch_emoji_desc, Category.EVENTS,
            keywords = listOf("switch", "emoji", "emoticon", "smiley")),
        Command("switch_back_emoji", R.string.cmd_switch_back_emoji, R.string.cmd_switch_back_emoji_desc, Category.EVENTS,
            keywords = listOf("switch", "back", "emoji", "abc")),
        Command("switch_clipboard", R.string.cmd_switch_clipboard, R.string.cmd_switch_clipboard_desc, Category.EVENTS,
            keywords = listOf("clipboard", "history", "paste", "recent")),
        Command("switch_back_clipboard", R.string.cmd_switch_back_clipboard, R.string.cmd_switch_back_clipboard_desc, Category.EVENTS,
            keywords = listOf("switch", "back", "clipboard", "abc")),
        Command("switch_forward", R.string.cmd_switch_forward, R.string.cmd_switch_forward_desc, Category.EVENTS,
            keywords = listOf("switch", "next", "layout", "forward")),
        Command("switch_backward", R.string.cmd_switch_backward, R.string.cmd_switch_backward_desc, Category.EVENTS,
            keywords = listOf("switch", "previous", "layout", "back")),
        Command("switch_greekmath", R.string.cmd_switch_greekmath, R.string.cmd_switch_greekmath_desc, Category.EVENTS,
            keywords = listOf("greek", "math", "symbols", "pi")),
        Command("change_method", R.string.cmd_change_method, R.string.cmd_change_method_desc, Category.EVENTS,
            keywords = listOf("change", "keyboard", "input", "method", "picker", "switch")),
        Command("change_method_prev", R.string.cmd_change_method_prev, R.string.cmd_change_method_prev_desc, Category.EVENTS,
            keywords = listOf("change", "keyboard", "previous", "auto")),
        Command("action", R.string.cmd_action, R.string.cmd_action_desc, Category.EVENTS,
            keywords = listOf("action", "go", "search", "send", "enter")),
        Command("capslock", R.string.cmd_capslock, R.string.cmd_capslock_desc, Category.EVENTS,
            keywords = listOf("caps", "lock", "uppercase", "capital")),
        Command("voice_typing", R.string.cmd_voice_typing, R.string.cmd_voice_typing_desc, Category.EVENTS,
            keywords = listOf("voice", "speech", "dictate", "microphone")),
        Command("voice_typing_chooser", R.string.cmd_voice_typing_chooser, R.string.cmd_voice_typing_chooser_desc, Category.EVENTS,
            keywords = listOf("voice", "speech", "picker", "choose")),
        // gh #175: collapse to a low-profile bar or a floating button; a tap expands it again.
        Command("minimize_bar", R.string.cmd_minimize_bar, R.string.cmd_minimize_bar_desc, Category.EVENTS,
            keywords = listOf("minimize", "minimise", "collapse", "hide", "bar", "small", "shrink")),
        Command("minimize_fab", R.string.cmd_minimize_fab, R.string.cmd_minimize_fab_desc, Category.EVENTS,
            keywords = listOf("minimize", "minimise", "collapse", "hide", "floating", "button", "fab", "bubble")),
        // gh #175: dismiss the keyboard outright.
        Command("hide_keyboard", R.string.cmd_hide_keyboard, R.string.cmd_hide_keyboard_desc, Category.EVENTS,
            keywords = listOf("hide", "dismiss", "close", "keyboard", "down")),

        // ========== MODIFIERS ==========
        Command("shift", R.string.cmd_shift, R.string.cmd_shift_desc, Category.MODIFIERS,
            keywords = listOf("shift", "uppercase", "capital")),
        Command("ctrl", R.string.cmd_ctrl, R.string.cmd_ctrl_desc, Category.MODIFIERS,
            keywords = listOf("ctrl", "control", "modifier")),
        Command("alt", R.string.cmd_alt, R.string.cmd_alt_desc, Category.MODIFIERS,
            keywords = listOf("alt", "alternate", "modifier")),
        Command("meta", R.string.cmd_meta, R.string.cmd_meta_desc, Category.MODIFIERS,
            keywords = listOf("meta", "windows", "super", "modifier")),
        Command("fn", R.string.cmd_fn, R.string.cmd_fn_desc, Category.MODIFIERS,
            keywords = listOf("fn", "function", "modifier")),

        // ========== FUNCTION KEYS ==========
        Command("f1", R.string.cmd_f1, R.string.cmd_f1_desc, Category.FUNCTION_KEYS,
            keywords = listOf("f1", "function", "help")),
        Command("f2", R.string.cmd_f2, R.string.cmd_f2_desc, Category.FUNCTION_KEYS,
            keywords = listOf("f2", "function", "rename")),
        Command("f3", R.string.cmd_f3, R.string.cmd_f3_desc, Category.FUNCTION_KEYS,
            keywords = listOf("f3", "function", "find")),
        Command("f4", R.string.cmd_f4, R.string.cmd_f4_desc, Category.FUNCTION_KEYS,
            keywords = listOf("f4", "function", "close")),
        Command("f5", R.string.cmd_f5, R.string.cmd_f5_desc, Category.FUNCTION_KEYS,
            keywords = listOf("f5", "function", "refresh")),
        Command("f6", R.string.cmd_f6, R.string.cmd_f6_desc, Category.FUNCTION_KEYS,
            keywords = listOf("f6", "function")),
        Command("f7", R.string.cmd_f7, R.string.cmd_f7_desc, Category.FUNCTION_KEYS,
            keywords = listOf("f7", "function", "spell")),
        Command("f8", R.string.cmd_f8, R.string.cmd_f8_desc, Category.FUNCTION_KEYS,
            keywords = listOf("f8", "function")),
        Command("f9", R.string.cmd_f9, R.string.cmd_f9_desc, Category.FUNCTION_KEYS,
            keywords = listOf("f9", "function")),
        Command("f10", R.string.cmd_f10, R.string.cmd_f10_desc, Category.FUNCTION_KEYS,
            keywords = listOf("f10", "function", "menu")),
        Command("f11", R.string.cmd_f11, R.string.cmd_f11_desc, Category.FUNCTION_KEYS,
            keywords = listOf("f11", "function", "fullscreen")),
        Command("f12", R.string.cmd_f12, R.string.cmd_f12_desc, Category.FUNCTION_KEYS,
            keywords = listOf("f12", "function", "devtools")),

        // ========== SPECIAL KEYS ==========
        Command("esc", R.string.cmd_esc, R.string.cmd_esc_desc, Category.SPECIAL_KEYS,
            keywords = listOf("esc", "escape", "cancel", "close")),
        Command("enter", R.string.cmd_enter, R.string.cmd_enter_desc, Category.SPECIAL_KEYS,
            keywords = listOf("enter", "return", "newline")),
        Command("tab", R.string.cmd_tab, R.string.cmd_tab_desc, Category.SPECIAL_KEYS,
            keywords = listOf("tab", "indent", "next")),
        Command("menu", R.string.cmd_menu, R.string.cmd_menu_desc, Category.SPECIAL_KEYS,
            keywords = listOf("menu", "context", "right click")),
        Command("insert", R.string.cmd_insert, R.string.cmd_insert_desc, Category.SPECIAL_KEYS,
            keywords = listOf("insert", "ins", "overwrite")),
        Command("scroll_lock", R.string.cmd_scroll_lock, R.string.cmd_scroll_lock_desc, Category.SPECIAL_KEYS,
            keywords = listOf("scroll", "lock")),
        Command("compose", R.string.cmd_compose, R.string.cmd_compose_desc, Category.SPECIAL_KEYS,
            keywords = listOf("compose", "accent", "dead key")),
        Command("compose_cancel", R.string.cmd_compose_cancel, R.string.cmd_compose_cancel_desc, Category.SPECIAL_KEYS,
            keywords = listOf("compose", "cancel")),

        // ========== SPACES & FORMATTING ==========
        Command("space", R.string.cmd_space, R.string.cmd_space_desc, Category.SPACES,
            keywords = listOf("space", "blank")),
        Command("nbsp", R.string.cmd_nbsp, R.string.cmd_nbsp_desc, Category.SPACES,
            keywords = listOf("nbsp", "space", "non-breaking", "no wrap")),
        Command("nnbsp", R.string.cmd_nnbsp, R.string.cmd_nnbsp_desc, Category.SPACES,
            keywords = listOf("nnbsp", "narrow", "space", "thin")),
        Command("\\t", R.string.cmd_backslash_t, R.string.cmd_backslash_t_desc, Category.SPACES,
            keywords = listOf("tab", "character", "indent")),
        Command("\\n", R.string.cmd_backslash_n, R.string.cmd_backslash_n_desc, Category.SPACES,
            keywords = listOf("newline", "line break", "enter")),
        Command("zwj", R.string.cmd_zwj, R.string.cmd_zwj_desc, Category.SPACES,
            keywords = listOf("zwj", "zero width", "joiner", "ligature")),
        Command("zwnj", R.string.cmd_zwnj, R.string.cmd_zwnj_desc, Category.SPACES,
            keywords = listOf("zwnj", "zero width", "non joiner", "halfspace")),
        Command("lrm", R.string.cmd_lrm, R.string.cmd_lrm_desc, Category.SPACES,
            keywords = listOf("lrm", "left to right", "bidi", "direction")),
        Command("rlm", R.string.cmd_rlm, R.string.cmd_rlm_desc, Category.SPACES,
            keywords = listOf("rlm", "right to left", "bidi", "direction")),

        // ========== DIACRITICS (Dead Keys) ==========
        Command("accent_aigu", R.string.cmd_accent_aigu, R.string.cmd_accent_aigu_desc, Category.DIACRITICS,
            keywords = listOf("accent", "acute", "aigu", "diacritic")),
        Command("accent_grave", R.string.cmd_accent_grave, R.string.cmd_accent_grave_desc, Category.DIACRITICS,
            keywords = listOf("accent", "grave", "diacritic")),
        Command("accent_circonflexe", R.string.cmd_accent_circonflexe, R.string.cmd_accent_circonflexe_desc, Category.DIACRITICS,
            keywords = listOf("accent", "circumflex", "hat", "diacritic")),
        Command("accent_tilde", R.string.cmd_accent_tilde, R.string.cmd_accent_tilde_desc, Category.DIACRITICS,
            keywords = listOf("accent", "tilde", "diacritic")),
        Command("accent_trema", R.string.cmd_accent_trema, R.string.cmd_accent_trema_desc, Category.DIACRITICS,
            keywords = listOf("accent", "umlaut", "trema", "diaeresis", "diacritic")),
        Command("accent_cedille", R.string.cmd_accent_cedille, R.string.cmd_accent_cedille_desc, Category.DIACRITICS,
            keywords = listOf("accent", "cedilla", "diacritic")),
        Command("accent_caron", R.string.cmd_accent_caron, R.string.cmd_accent_caron_desc, Category.DIACRITICS,
            keywords = listOf("accent", "caron", "hacek", "diacritic")),
        Command("accent_macron", R.string.cmd_accent_macron, R.string.cmd_accent_macron_desc, Category.DIACRITICS,
            keywords = listOf("accent", "macron", "bar", "diacritic")),
        Command("accent_ring", R.string.cmd_accent_ring, R.string.cmd_accent_ring_desc, Category.DIACRITICS,
            keywords = listOf("accent", "ring", "circle", "diacritic")),
        Command("accent_ogonek", R.string.cmd_accent_ogonek, R.string.cmd_accent_ogonek_desc, Category.DIACRITICS,
            keywords = listOf("accent", "ogonek", "tail", "diacritic")),
        Command("accent_dot_above", R.string.cmd_accent_dot_above, R.string.cmd_accent_dot_above_desc, Category.DIACRITICS,
            keywords = listOf("accent", "dot", "above", "diacritic")),
        Command("accent_dot_below", R.string.cmd_accent_dot_below, R.string.cmd_accent_dot_below_desc, Category.DIACRITICS,
            keywords = listOf("accent", "dot", "below", "diacritic")),
        Command("accent_double_aigu", R.string.cmd_accent_double_aigu, R.string.cmd_accent_double_aigu_desc, Category.DIACRITICS,
            keywords = listOf("accent", "double", "acute", "diacritic")),
        Command("accent_breve", R.string.cmd_accent_breve, R.string.cmd_accent_breve_desc, Category.DIACRITICS,
            keywords = listOf("accent", "breve", "short", "diacritic")),
        Command("accent_slash", R.string.cmd_accent_slash, R.string.cmd_accent_slash_desc, Category.DIACRITICS,
            keywords = listOf("accent", "slash", "stroke", "diacritic")),
        Command("accent_bar", R.string.cmd_accent_bar, R.string.cmd_accent_bar_desc, Category.DIACRITICS,
            keywords = listOf("accent", "bar", "stroke", "diacritic")),
        Command("accent_horn", R.string.cmd_accent_horn, R.string.cmd_accent_horn_desc, Category.DIACRITICS,
            keywords = listOf("accent", "horn", "vietnamese", "diacritic")),
        Command("accent_hook_above", R.string.cmd_accent_hook_above, R.string.cmd_accent_hook_above_desc, Category.DIACRITICS,
            keywords = listOf("accent", "hook", "above", "vietnamese", "diacritic")),
        Command("accent_double_grave", R.string.cmd_accent_double_grave, R.string.cmd_accent_double_grave_desc, Category.DIACRITICS,
            keywords = listOf("accent", "double", "grave", "diacritic")),
        Command("accent_arrow_right", R.string.cmd_accent_arrow_right, R.string.cmd_accent_arrow_right_desc, Category.DIACRITICS,
            keywords = listOf("accent", "arrow", "vector", "diacritic")),
        Command("superscript", R.string.cmd_superscript, R.string.cmd_superscript_desc, Category.DIACRITICS,
            keywords = listOf("superscript", "sup", "exponent", "power")),
        Command("subscript", R.string.cmd_subscript, R.string.cmd_subscript_desc, Category.DIACRITICS,
            keywords = listOf("subscript", "sub", "index")),
        Command("ordinal", R.string.cmd_ordinal, R.string.cmd_ordinal_desc, Category.DIACRITICS,
            keywords = listOf("ordinal", "ord", "degree")),
        Command("arrows", R.string.cmd_arrows, R.string.cmd_arrows_desc, Category.DIACRITICS,
            keywords = listOf("arrows", "modifier")),
        Command("box", R.string.cmd_box, R.string.cmd_box_desc, Category.DIACRITICS,
            keywords = listOf("box", "drawing", "lines")),

        // ========== COMBINING DIACRITICS (Type characters with accents) ==========
        Command("combining_aigu", R.string.cmd_combining_aigu, R.string.cmd_combining_aigu_desc, Category.DIACRITICS,
            keywords = listOf("combining", "acute", "accent")),
        Command("combining_grave", R.string.cmd_combining_grave, R.string.cmd_combining_grave_desc, Category.DIACRITICS,
            keywords = listOf("combining", "grave", "accent")),
        Command("combining_circonflexe", R.string.cmd_combining_circonflexe, R.string.cmd_combining_circonflexe_desc, Category.DIACRITICS,
            keywords = listOf("combining", "circumflex", "hat")),
        Command("combining_tilde", R.string.cmd_combining_tilde, R.string.cmd_combining_tilde_desc, Category.DIACRITICS,
            keywords = listOf("combining", "tilde")),
        Command("combining_trema", R.string.cmd_combining_trema, R.string.cmd_combining_trema_desc, Category.DIACRITICS,
            keywords = listOf("combining", "umlaut", "trema", "diaeresis")),
        Command("combining_cedille", R.string.cmd_combining_cedille, R.string.cmd_combining_cedille_desc, Category.DIACRITICS,
            keywords = listOf("combining", "cedilla")),
        Command("combining_caron", R.string.cmd_combining_caron, R.string.cmd_combining_caron_desc, Category.DIACRITICS,
            keywords = listOf("combining", "caron", "hacek")),
        Command("combining_macron", R.string.cmd_combining_macron, R.string.cmd_combining_macron_desc, Category.DIACRITICS,
            keywords = listOf("combining", "macron", "bar")),
        Command("combining_ring", R.string.cmd_combining_ring, R.string.cmd_combining_ring_desc, Category.DIACRITICS,
            keywords = listOf("combining", "ring", "circle")),
        Command("combining_ogonek", R.string.cmd_combining_ogonek, R.string.cmd_combining_ogonek_desc, Category.DIACRITICS,
            keywords = listOf("combining", "ogonek", "tail")),
        Command("combining_dot_above", R.string.cmd_combining_dot_above, R.string.cmd_combining_dot_above_desc, Category.DIACRITICS,
            keywords = listOf("combining", "dot", "above")),
        Command("combining_dot_below", R.string.cmd_combining_dot_below, R.string.cmd_combining_dot_below_desc, Category.DIACRITICS,
            keywords = listOf("combining", "dot", "below")),
        Command("combining_double_aigu", R.string.cmd_combining_double_aigu, R.string.cmd_combining_double_aigu_desc, Category.DIACRITICS,
            keywords = listOf("combining", "double", "acute")),
        Command("combining_breve", R.string.cmd_combining_breve, R.string.cmd_combining_breve_desc, Category.DIACRITICS,
            keywords = listOf("combining", "breve", "short")),
        Command("combining_slash", R.string.cmd_combining_slash, R.string.cmd_combining_slash_desc, Category.DIACRITICS,
            keywords = listOf("combining", "slash", "stroke")),
        Command("combining_bar", R.string.cmd_combining_bar, R.string.cmd_combining_bar_desc, Category.DIACRITICS,
            keywords = listOf("combining", "bar", "stroke")),
        Command("combining_horn", R.string.cmd_combining_horn, R.string.cmd_combining_horn_desc, Category.DIACRITICS,
            keywords = listOf("combining", "horn", "vietnamese")),
        Command("combining_hook_above", R.string.cmd_combining_hook_above, R.string.cmd_combining_hook_above_desc, Category.DIACRITICS,
            keywords = listOf("combining", "hook", "vietnamese")),
        Command("combining_arrow_right", R.string.cmd_combining_arrow_right, R.string.cmd_combining_arrow_right_desc, Category.DIACRITICS,
            keywords = listOf("combining", "arrow", "vector")),

        // NOTE: compose, compose_cancel, doc_home, doc_end already defined above - removed duplicates

        // ========== BIDI (Bidirectional Text) ==========
        Command("b(", R.string.cmd_bidi_paren_open, R.string.cmd_bidi_paren_open_desc, Category.TEXT,
            keywords = listOf("bidi", "parenthesis", "rtl", "arabic", "hebrew")),
        Command("b)", R.string.cmd_bidi_paren_close, R.string.cmd_bidi_paren_close_desc, Category.TEXT,
            keywords = listOf("bidi", "parenthesis", "rtl")),
        Command("b[", R.string.cmd_bidi_bracket_open, R.string.cmd_bidi_bracket_open_desc, Category.TEXT,
            keywords = listOf("bidi", "bracket", "rtl")),
        Command("b]", R.string.cmd_bidi_bracket_close, R.string.cmd_bidi_bracket_close_desc, Category.TEXT,
            keywords = listOf("bidi", "bracket", "rtl")),
        Command("b{", R.string.cmd_bidi_brace_open, R.string.cmd_bidi_brace_open_desc, Category.TEXT,
            keywords = listOf("bidi", "brace", "rtl")),
        Command("b}", R.string.cmd_bidi_brace_close, R.string.cmd_bidi_brace_close_desc, Category.TEXT,
            keywords = listOf("bidi", "brace", "rtl")),
        Command("blt", R.string.cmd_blt, R.string.cmd_blt_desc, Category.TEXT,
            keywords = listOf("bidi", "less", "angle", "rtl")),
        Command("bgt", R.string.cmd_bgt, R.string.cmd_bgt_desc, Category.TEXT,
            keywords = listOf("bidi", "greater", "angle", "rtl")),

        // NOTE: zwj and zwnj are defined above in the SPACES category (lines ~236-239)
        Command("halfspace", R.string.cmd_halfspace, R.string.cmd_halfspace_desc, Category.SPACES,
            keywords = listOf("halfspace", "zero", "width", "persian", "arabic")),

        // ========== REMOVED/PLACEHOLDER KEYS ==========
        Command("removed", R.string.cmd_removed, R.string.cmd_removed_desc, Category.SPECIAL_KEYS,
            keywords = listOf("removed", "placeholder", "none", "empty")),

        // ========== TEXT EDITING (additional) ==========
        // NOTE: replaceText / textAssist live ONLY under TEXT_ACTIONS below (the category
        // v1.2.0 announced them in). They were briefly duplicated here as EDITING rows
        // (v1.1.98) with identical by-name KeyValue resolution — deduped 2026-09; the
        // catalogue must stay duplicate-free (see ReleaseClaimCommandCatalogueTest).
        Command("autofill", R.string.cmd_autofill, R.string.cmd_autofill_desc, Category.EDITING,
            keywords = listOf("autofill", "password", "form")),

        // ========== MEDIA CONTROLS ==========
        Command("media_play_pause", R.string.cmd_media_play_pause, R.string.cmd_media_play_pause_desc, Category.MEDIA,
            keywords = listOf("media", "play", "pause", "music", "video")),
        Command("media_play", R.string.cmd_media_play, R.string.cmd_media_play_desc, Category.MEDIA,
            keywords = listOf("media", "play", "start")),
        Command("media_pause", R.string.cmd_media_pause, R.string.cmd_media_pause_desc, Category.MEDIA,
            keywords = listOf("media", "pause", "stop")),
        Command("media_stop", R.string.cmd_media_stop, R.string.cmd_media_stop_desc, Category.MEDIA,
            keywords = listOf("media", "stop")),
        Command("media_next", R.string.cmd_media_next, R.string.cmd_media_next_desc, Category.MEDIA,
            keywords = listOf("media", "next", "skip", "forward")),
        Command("media_previous", R.string.cmd_media_previous, R.string.cmd_media_previous_desc, Category.MEDIA,
            keywords = listOf("media", "previous", "back", "rewind")),
        Command("media_rewind", R.string.cmd_media_rewind, R.string.cmd_media_rewind_desc, Category.MEDIA,
            keywords = listOf("media", "rewind", "back")),
        Command("media_fast_forward", R.string.cmd_media_fast_forward, R.string.cmd_media_fast_forward_desc, Category.MEDIA,
            keywords = listOf("media", "fast", "forward", "skip")),
        Command("media_record", R.string.cmd_media_record, R.string.cmd_media_record_desc, Category.MEDIA,
            keywords = listOf("media", "record", "capture")),

        // ========== VOLUME CONTROLS ==========
        Command("volume_up", R.string.cmd_volume_up, R.string.cmd_volume_up_desc, Category.MEDIA,
            keywords = listOf("volume", "up", "louder", "sound")),
        Command("volume_down", R.string.cmd_volume_down, R.string.cmd_volume_down_desc, Category.MEDIA,
            keywords = listOf("volume", "down", "quieter", "sound")),
        Command("volume_mute", R.string.cmd_volume_mute, R.string.cmd_volume_mute_desc, Category.MEDIA,
            keywords = listOf("volume", "mute", "silent", "sound")),

        // ========== SYSTEM & APPS ==========
        Command("brightness_up", R.string.cmd_brightness_up, R.string.cmd_brightness_up_desc, Category.SYSTEM,
            keywords = listOf("brightness", "up", "brighter", "screen")),
        Command("brightness_down", R.string.cmd_brightness_down, R.string.cmd_brightness_down_desc, Category.SYSTEM,
            keywords = listOf("brightness", "down", "dimmer", "screen")),
        Command("zoom_in", R.string.cmd_zoom_in, R.string.cmd_zoom_in_desc, Category.SYSTEM,
            keywords = listOf("zoom", "in", "magnify", "larger")),
        Command("zoom_out", R.string.cmd_zoom_out, R.string.cmd_zoom_out_desc, Category.SYSTEM,
            keywords = listOf("zoom", "out", "reduce", "smaller")),
        Command("search", R.string.cmd_search, R.string.cmd_search_desc, Category.SYSTEM,
            keywords = listOf("search", "find", "lookup")),
        Command("calculator", R.string.cmd_calculator, R.string.cmd_calculator_desc, Category.SYSTEM,
            keywords = listOf("calculator", "calc", "math")),
        Command("calendar", R.string.cmd_calendar, R.string.cmd_calendar_desc, Category.SYSTEM,
            keywords = listOf("calendar", "date", "schedule")),
        Command("contacts", R.string.cmd_contacts, R.string.cmd_contacts_desc, Category.SYSTEM,
            keywords = listOf("contacts", "people", "address")),
        Command("explorer", R.string.cmd_explorer, R.string.cmd_explorer_desc, Category.SYSTEM,
            keywords = listOf("explorer", "files", "folder", "manager")),
        Command("notification", R.string.cmd_notification, R.string.cmd_notification_desc, Category.SYSTEM,
            keywords = listOf("notification", "alert", "shade")),

        // ========== SELECTION MODE ==========
        Command("selection_mode", R.string.cmd_selection_mode, R.string.cmd_selection_mode_desc, Category.SELECTION,
            keywords = listOf("selection", "mode", "select", "highlight")),

        // ========== SLAVONIC COMBINING DIACRITICS ==========
        Command("combining_vertical_tilde", R.string.cmd_combining_vertical_tilde, R.string.cmd_combining_vertical_tilde_desc, Category.DIACRITICS_SLAVONIC,
            keywords = listOf("combining", "vertical", "tilde", "slavonic")),
        Command("combining_inverted_breve", R.string.cmd_combining_inverted_breve, R.string.cmd_combining_inverted_breve_desc, Category.DIACRITICS_SLAVONIC,
            keywords = listOf("combining", "inverted", "breve", "slavonic")),
        Command("combining_pokrytie", R.string.cmd_combining_pokrytie, R.string.cmd_combining_pokrytie_desc, Category.DIACRITICS_SLAVONIC,
            keywords = listOf("combining", "pokrytie", "slavonic", "church")),
        Command("combining_slavonic_psili", R.string.cmd_combining_slavonic_psili, R.string.cmd_combining_slavonic_psili_desc, Category.DIACRITICS_SLAVONIC,
            keywords = listOf("combining", "psili", "slavonic", "church")),
        Command("combining_slavonic_dasia", R.string.cmd_combining_slavonic_dasia, R.string.cmd_combining_slavonic_dasia_desc, Category.DIACRITICS_SLAVONIC,
            keywords = listOf("combining", "dasia", "slavonic", "church")),
        Command("combining_payerok", R.string.cmd_combining_payerok, R.string.cmd_combining_payerok_desc, Category.DIACRITICS_SLAVONIC,
            keywords = listOf("combining", "payerok", "slavonic", "church")),
        Command("combining_titlo", R.string.cmd_combining_titlo, R.string.cmd_combining_titlo_desc, Category.DIACRITICS_SLAVONIC,
            keywords = listOf("combining", "titlo", "slavonic", "church")),
        Command("combining_vzmet", R.string.cmd_combining_vzmet, R.string.cmd_combining_vzmet_desc, Category.DIACRITICS_SLAVONIC,
            keywords = listOf("combining", "vzmet", "slavonic", "church")),
        Command("combining_kavyka", R.string.cmd_combining_kavyka, R.string.cmd_combining_kavyka_desc, Category.DIACRITICS_SLAVONIC,
            keywords = listOf("combining", "kavyka", "slavonic", "church")),
        Command("combining_palatalization", R.string.cmd_combining_palatalization, R.string.cmd_combining_palatalization_desc, Category.DIACRITICS_SLAVONIC,
            keywords = listOf("combining", "palatalization", "slavonic")),

        // ========== ARABIC COMBINING DIACRITICS ==========
        Command("combining_arabic_v", R.string.cmd_combining_arabic_v, R.string.cmd_combining_arabic_v_desc, Category.DIACRITICS_ARABIC,
            keywords = listOf("combining", "arabic", "v", "above")),
        Command("combining_arabic_inverted_v", R.string.cmd_combining_arabic_inverted_v, R.string.cmd_combining_arabic_inverted_v_desc, Category.DIACRITICS_ARABIC,
            keywords = listOf("combining", "arabic", "inverted", "v")),
        Command("combining_shaddah", R.string.cmd_combining_shaddah, R.string.cmd_combining_shaddah_desc, Category.DIACRITICS_ARABIC,
            keywords = listOf("combining", "shaddah", "arabic", "double")),
        Command("combining_sukun", R.string.cmd_combining_sukun, R.string.cmd_combining_sukun_desc, Category.DIACRITICS_ARABIC,
            keywords = listOf("combining", "sukun", "arabic", "silent")),
        Command("combining_fatha", R.string.cmd_combining_fatha, R.string.cmd_combining_fatha_desc, Category.DIACRITICS_ARABIC,
            keywords = listOf("combining", "fatha", "arabic", "vowel")),
        Command("combining_dammah", R.string.cmd_combining_dammah, R.string.cmd_combining_dammah_desc, Category.DIACRITICS_ARABIC,
            keywords = listOf("combining", "dammah", "arabic", "vowel")),
        Command("combining_kasra", R.string.cmd_combining_kasra, R.string.cmd_combining_kasra_desc, Category.DIACRITICS_ARABIC,
            keywords = listOf("combining", "kasra", "arabic", "vowel")),
        Command("combining_hamza_above", R.string.cmd_combining_hamza_above, R.string.cmd_combining_hamza_above_desc, Category.DIACRITICS_ARABIC,
            keywords = listOf("combining", "hamza", "above", "arabic")),
        Command("combining_hamza_below", R.string.cmd_combining_hamza_below, R.string.cmd_combining_hamza_below_desc, Category.DIACRITICS_ARABIC,
            keywords = listOf("combining", "hamza", "below", "arabic")),
        Command("combining_alef_above", R.string.cmd_combining_alef_above, R.string.cmd_combining_alef_above_desc, Category.DIACRITICS_ARABIC,
            keywords = listOf("combining", "alef", "above", "arabic")),
        Command("combining_fathatan", R.string.cmd_combining_fathatan, R.string.cmd_combining_fathatan_desc, Category.DIACRITICS_ARABIC,
            keywords = listOf("combining", "fathatan", "tanwin", "arabic")),
        Command("combining_kasratan", R.string.cmd_combining_kasratan, R.string.cmd_combining_kasratan_desc, Category.DIACRITICS_ARABIC,
            keywords = listOf("combining", "kasratan", "tanwin", "arabic")),
        Command("combining_dammatan", R.string.cmd_combining_dammatan, R.string.cmd_combining_dammatan_desc, Category.DIACRITICS_ARABIC,
            keywords = listOf("combining", "dammatan", "tanwin", "arabic")),
        Command("combining_alef_below", R.string.cmd_combining_alef_below, R.string.cmd_combining_alef_below_desc, Category.DIACRITICS_ARABIC,
            keywords = listOf("combining", "alef", "below", "arabic")),

        // ========== HEBREW NIQQUD (Vowel Points) ==========
        Command("qamats", R.string.cmd_qamats, R.string.cmd_qamats_desc, Category.HEBREW,
            keywords = listOf("hebrew", "qamats", "kamatz", "vowel", "niqqud")),
        Command("patah", R.string.cmd_patah, R.string.cmd_patah_desc, Category.HEBREW,
            keywords = listOf("hebrew", "patah", "patach", "vowel", "niqqud")),
        Command("sheva", R.string.cmd_sheva, R.string.cmd_sheva_desc, Category.HEBREW,
            keywords = listOf("hebrew", "sheva", "vowel", "niqqud")),
        Command("dagesh", R.string.cmd_dagesh, R.string.cmd_dagesh_desc, Category.HEBREW,
            keywords = listOf("hebrew", "dagesh", "mapiq", "niqqud")),
        Command("hiriq", R.string.cmd_hiriq, R.string.cmd_hiriq_desc, Category.HEBREW,
            keywords = listOf("hebrew", "hiriq", "vowel", "niqqud")),
        Command("segol", R.string.cmd_segol, R.string.cmd_segol_desc, Category.HEBREW,
            keywords = listOf("hebrew", "segol", "vowel", "niqqud")),
        Command("tsere", R.string.cmd_tsere, R.string.cmd_tsere_desc, Category.HEBREW,
            keywords = listOf("hebrew", "tsere", "vowel", "niqqud")),
        Command("holam", R.string.cmd_holam, R.string.cmd_holam_desc, Category.HEBREW,
            keywords = listOf("hebrew", "holam", "vowel", "niqqud")),
        Command("qubuts", R.string.cmd_qubuts, R.string.cmd_qubuts_desc, Category.HEBREW,
            keywords = listOf("hebrew", "qubuts", "kubuts", "vowel", "niqqud")),
        Command("hataf_patah", R.string.cmd_hataf_patah, R.string.cmd_hataf_patah_desc, Category.HEBREW,
            keywords = listOf("hebrew", "hataf", "patah", "reduced", "niqqud")),
        Command("hataf_qamats", R.string.cmd_hataf_qamats, R.string.cmd_hataf_qamats_desc, Category.HEBREW,
            keywords = listOf("hebrew", "hataf", "qamats", "reduced", "niqqud")),
        Command("hataf_segol", R.string.cmd_hataf_segol, R.string.cmd_hataf_segol_desc, Category.HEBREW,
            keywords = listOf("hebrew", "hataf", "segol", "reduced", "niqqud")),
        Command("geresh", R.string.cmd_geresh, R.string.cmd_geresh_desc, Category.HEBREW,
            keywords = listOf("hebrew", "geresh", "punctuation")),
        Command("gershayim", R.string.cmd_gershayim, R.string.cmd_gershayim_desc, Category.HEBREW,
            keywords = listOf("hebrew", "gershayim", "punctuation", "quote")),
        Command("maqaf", R.string.cmd_maqaf, R.string.cmd_maqaf_desc, Category.HEBREW,
            keywords = listOf("hebrew", "maqaf", "hyphen", "dash")),
        Command("rafe", R.string.cmd_rafe, R.string.cmd_rafe_desc, Category.HEBREW,
            keywords = listOf("hebrew", "rafe", "rapheh", "niqqud")),
        Command("ole", R.string.cmd_ole, R.string.cmd_ole_desc, Category.HEBREW,
            keywords = listOf("hebrew", "ole", "cantillation", "trope")),
        Command("meteg", R.string.cmd_meteg, R.string.cmd_meteg_desc, Category.HEBREW,
            keywords = listOf("hebrew", "meteg", "siluq", "niqqud")),
        Command("shindot", R.string.cmd_shindot, R.string.cmd_shindot_desc, Category.HEBREW,
            keywords = listOf("hebrew", "shin", "dot", "niqqud")),
        Command("sindot", R.string.cmd_sindot, R.string.cmd_sindot_desc, Category.HEBREW,
            keywords = listOf("hebrew", "sin", "dot", "niqqud")),

        // ========== LANGUAGE (v1.2.0) ==========
        // Name-dispatched (Keyboard2View), no KeyValue — symbols required, as for paste_pinned_N.
        Command("primaryLangToggle", R.string.cmd_primary_lang_toggle, R.string.cmd_primary_lang_toggle_desc, Category.LANGUAGE,
            symbol = "🌐1",
            keywords = listOf("language", "toggle", "primary", "switch", "swap")),
        Command("secondaryLangToggle", R.string.cmd_secondary_lang_toggle, R.string.cmd_secondary_lang_toggle_desc, Category.LANGUAGE,
            symbol = "🌐2",
            keywords = listOf("language", "toggle", "secondary", "switch", "swap")),

        // ========== TEXT ACTIONS (v1.2.0) ==========
        Command("textAssist", R.string.cmd_text_assist, R.string.cmd_text_assist_desc, Category.TEXT_ACTIONS,
            keywords = listOf("text", "assist", "ai", "process", "google")),
        Command("replaceText", R.string.cmd_replace_text, R.string.cmd_replace_text_desc, Category.TEXT_ACTIONS,
            keywords = listOf("replace", "text", "substitute", "change")),
        Command("showTextMenu", R.string.cmd_show_text_menu, R.string.cmd_show_text_menu_desc, Category.TEXT_ACTIONS,
            symbol = "☰", // name-dispatched (Keyboard2View), no KeyValue — see paste_pinned_N note
            keywords = listOf("text", "menu", "toolbar", "cut", "copy", "paste", "translate", "select")),

        // ========== TIMESTAMPS ==========
        Command("timestamp_date", R.string.cmd_timestamp_date, R.string.cmd_timestamp_date_desc, Category.TIMESTAMP,
            symbol = "📅",
            keywords = listOf("timestamp", "date", "iso", "today", "current")),
        Command("timestamp_time", R.string.cmd_timestamp_time, R.string.cmd_timestamp_time_desc, Category.TIMESTAMP,
            symbol = "🕐",
            keywords = listOf("timestamp", "time", "clock", "now", "24h")),
        Command("timestamp_datetime", R.string.cmd_timestamp_datetime, R.string.cmd_timestamp_datetime_desc, Category.TIMESTAMP,
            symbol = "📆",
            keywords = listOf("timestamp", "datetime", "date", "time", "now")),
        Command("timestamp_time_seconds", R.string.cmd_timestamp_time_seconds, R.string.cmd_timestamp_time_seconds_desc, Category.TIMESTAMP,
            symbol = "⏱",
            keywords = listOf("timestamp", "time", "seconds", "precise")),
        Command("timestamp_date_short", R.string.cmd_timestamp_date_short, R.string.cmd_timestamp_date_short_desc, Category.TIMESTAMP,
            symbol = "📅",
            keywords = listOf("timestamp", "date", "short", "american")),
        Command("timestamp_date_long", R.string.cmd_timestamp_date_long, R.string.cmd_timestamp_date_long_desc, Category.TIMESTAMP,
            symbol = "🗓",
            keywords = listOf("timestamp", "date", "long", "full", "weekday")),
        Command("timestamp_time_12h", R.string.cmd_timestamp_time_12h, R.string.cmd_timestamp_time_12h_desc, Category.TIMESTAMP,
            symbol = "🕐",
            keywords = listOf("timestamp", "time", "12h", "am", "pm")),
        Command("timestamp_iso", R.string.cmd_timestamp_iso, R.string.cmd_timestamp_iso_desc, Category.TIMESTAMP,
            symbol = "📋",
            keywords = listOf("timestamp", "iso", "8601", "full", "standard"))
    )

    /**
     * Get all commands grouped by category.
     */
    fun getByCategory(): Map<Category, List<Command>> {
        return ALL_COMMANDS.groupBy { it.category }
            .toSortedMap(compareBy { it.sortOrder })
    }

    /**
     * The texts a query is matched against, most specific first: the localized name and
     * description from [text], then the English ones from [english] (so a user who knows the
     * English command names still finds them under any UI language). Lower-cased with
     * [java.util.Locale.ROOT] like the query.
     */
    private fun displayTexts(cmd: Command, text: ResultText, english: ResultText?): Pair<List<String>, List<String>> {
        val names = mutableListOf(text.string(cmd.nameRes).lowercase())
        val descriptions = mutableListOf(text.string(cmd.descriptionRes).lowercase())
        if (english != null) {
            english.string(cmd.nameRes).lowercase().let { if (it !in names) names += it }
            english.string(cmd.descriptionRes).lowercase().let { if (it !in descriptions) descriptions += it }
        }
        return names to descriptions
    }

    /**
     * Search commands by query string.
     * Matches against the internal name, the localized (and optionally English) display name
     * and description, and the keywords.
     */
    internal fun search(query: String, text: ResultText, english: ResultText? = null): List<Command> {
        if (query.isBlank()) return ALL_COMMANDS

        val lowerQuery = query.lowercase().trim()
        return ALL_COMMANDS.filter { cmd ->
            val (names, descriptions) = displayTexts(cmd, text, english)
            cmd.name.lowercase().contains(lowerQuery) ||
            names.any { it.contains(lowerQuery) } ||
            descriptions.any { it.contains(lowerQuery) } ||
            cmd.keywords.any { it.lowercase().contains(lowerQuery) }
        }
    }

    /**
     * Search commands with ranking.
     * Returns commands sorted by relevance (exact matches first, then partial). Display-name
     * matches score the best of the localized and English names.
     */
    internal fun searchRanked(query: String, text: ResultText, english: ResultText? = null): List<Command> {
        if (query.isBlank()) return ALL_COMMANDS

        val lowerQuery = query.lowercase().trim()

        return ALL_COMMANDS
            .map { cmd ->
                val score = calculateScore(cmd, lowerQuery, displayTexts(cmd, text, english))
                cmd to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun calculateScore(cmd: Command, query: String, texts: Pair<List<String>, List<String>>): Int {
        var score = 0

        // Exact name match (highest)
        if (cmd.name.lowercase() == query) score += 100
        else if (cmd.name.lowercase().startsWith(query)) score += 50
        else if (cmd.name.lowercase().contains(query)) score += 20

        // Display name match: the best of the localized/English variants
        score += texts.first.maxOf { name ->
            when {
                name == query -> 80
                name.startsWith(query) -> 40
                name.contains(query) -> 15
                else -> 0
            }
        }

        // Description match
        if (texts.second.any { it.contains(query) }) score += 10

        // Keyword match
        cmd.keywords.forEach { keyword ->
            if (keyword == query) score += 60
            else if (keyword.startsWith(query)) score += 30
            else if (keyword.contains(query)) score += 10
        }

        return score
    }

    /**
     * Get a command by its internal name.
     */
    fun getByName(name: String): Command? {
        return ALL_COMMANDS.find { it.name == name }
    }

    /**
     * Get the KeyValue for a command name.
     * Returns null if the command doesn't exist.
     */
    fun getKeyValue(commandName: String): KeyValue? {
        return try {
            KeyValue.getKeyByName(commandName)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Display info for a command, including the symbol and font flag.
     */
    data class CommandDisplayInfo(
        /** The display text/symbol for the command */
        val displayText: String,
        /** Whether the special keyboard icon font is needed to render this */
        val useKeyFont: Boolean
    )

    /**
     * Get the display info (symbol and font flag) for a command.
     * This extracts the proper icon from KeyValue if available.
     *
     * @param commandName The command name (e.g., "cursor_left", "tab")
     * @return DisplayInfo with the symbol and font flag, or a fallback based on command name
     */
    fun getDisplayInfo(commandName: String): CommandDisplayInfo {
        // Resolve only REAL special keys here — getKeyValue()/getKeyByName never returns
        // null for a plain word: an unknown name falls through to makeStringKey(name),
        // whose getString() is the raw command name, and take(4) then rendered truncated
        // palette labels ("past" for paste_pinned_1). Name-dispatched commands must take
        // the symbol fallback below instead.
        val keyValue = KeyValue.getSpecialKeyByName(commandName)
        return if (keyValue != null) {
            CommandDisplayInfo(
                displayText = keyValue.getString().take(4),
                useKeyFont = keyValue.hasFlagsAny(KeyValue.FLAG_KEY_FONT)
            )
        } else {
            // Fallback: the command's declared symbol, or the first 4 chars of its id
            val command = getByName(commandName)
            CommandDisplayInfo(
                displayText = command?.symbol ?: commandName.take(4),
                useKeyFont = false
            )
        }
    }

    /**
     * Default labels a command used to have, as saved into short-swipe mappings created before
     * the label changed (command name → old default text). A mapping still showing one of these
     * was never customised by the user — it carries a default that is no longer drawable the
     * way it was meant — so [upgradeLegacyDefaultLabel] swaps in the current default.
     */
    val LEGACY_DEFAULT_LABELS: Map<String, String> = mapOf(
        // Text label "🔒⎘" (colour emoji + U+2398) until 2026-10-08; now key-font glyph U+E039.
        "copy_private" to "\uD83D\uDD12\u2398"
    )

    /**
     * The current default (label, useKeyFont) for a saved COMMAND mapping of [commandName]
     * whose label is that command's LEGACY default and was saved without the key font; null
     * when the mapping should be kept as saved (a user-typed label, or already current).
     */
    fun upgradeLegacyDefaultLabel(
        commandName: String,
        displayText: String,
        useKeyFont: Boolean
    ): CommandDisplayInfo? {
        if (useKeyFont || LEGACY_DEFAULT_LABELS[commandName] != displayText) return null
        return getDisplayInfo(commandName)
    }

    /**
     * Get all commands in a specific category.
     */
    fun getByCategory(category: Category): List<Command> {
        return ALL_COMMANDS.filter { it.category == category }
    }

    /**
     * Get total count of available commands.
     */
    val totalCount: Int get() = ALL_COMMANDS.size
}
