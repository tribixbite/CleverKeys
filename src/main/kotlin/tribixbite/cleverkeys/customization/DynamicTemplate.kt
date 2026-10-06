package tribixbite.cleverkeys.customization

import android.content.Context
import android.content.ClipboardManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import tribixbite.cleverkeys.SuggestionBar
import tribixbite.cleverkeys.EditorReadback
import java.util.UUID

/** Bounded, nonrecursive templates. TEXT mappings never call this parser. */
object DynamicTemplate {
    const val MAX_EXPANDED_LENGTH = 65_536
    data class Expansion(val text: String, val cursorOffset: Int?)
    private sealed interface Part {
        data class Literal(val text: String) : Part
        data class Token(val name: String) : Part
    }

    private fun parse(template: String): List<Part> {
        require(template.length <= ShortSwipeMapping.MAX_ACTION_LENGTH)
        val parts = mutableListOf<Part>()
        val literal = StringBuilder()
        var cursorSeen = false
        var i = 0
        fun flush() { if (literal.isNotEmpty()) { parts.add(Part.Literal(literal.toString())); literal.setLength(0) } }
        while (i < template.length) {
            when {
                template.startsWith("{{", i) -> { literal.append('{'); i += 2 }
                template.startsWith("}}", i) -> { literal.append('}'); i += 2 }
                template[i] == '{' -> {
                    flush()
                    val end = template.indexOf('}', i + 1)
                    require(end >= 0) { "Unclosed template token" }
                    val name = template.substring(i + 1, end)
                    require(name in setOf("clipboard", "selection", "uuid", "cursor")) { "Unknown template token" }
                    if (name == "cursor") { require(!cursorSeen) { "Duplicate cursor token" }; cursorSeen = true }
                    parts.add(Part.Token(name)); i = end + 1
                }
                template[i] == '}' -> throw IllegalArgumentException("Unescaped closing brace")
                else -> literal.append(template[i++])
            }
        }
        flush()
        return parts
    }

    fun isValid(template: String): Boolean = runCatching { parse(template) }.isSuccess

    /** Providers are read only when requested; repeated tokens reuse one invocation value. */
    fun expand(template: String, clipboard: () -> String?, selection: () -> String?, uuid: () -> String = { UUID.randomUUID().toString() }): Expansion {
        val parts = parse(template)
        val values = mutableMapOf<String, String>()
        val text = StringBuilder()
        var cursor: Int? = null
        for (part in parts) {
            val value = when (part) {
                is Part.Literal -> part.text
                is Part.Token -> if (part.name == "cursor") { cursor = text.length; "" } else values.getOrPut(part.name) {
                    when (part.name) {
                        "clipboard" -> requireNotNull(clipboard()) { "Clipboard text unavailable" }
                        "selection" -> requireNotNull(selection()) { "Selection unavailable" }
                        else -> uuid()
                    }
                }
            }
            require(value.length <= MAX_EXPANDED_LENGTH - text.length) { "Expanded template too large" }
            text.append(value)
        }
        return Expansion(text.toString(), cursor)
    }

    /** No coercion of media/URI clips, no provider read during assignment, no retry after edits. */
    fun execute(template: String, context: Context, ic: InputConnection, editor: EditorInfo, onEditing: () -> Unit = {}, stillCurrent: () -> Boolean): Boolean {
        if (SuggestionBar.isPasswordField(editor)) return false
        return runCatching {
            val before = EditorReadback.capture(ic) ?: return false
            val expanded = expand(template, clipboard = {
                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                manager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.takeIf { it.length <= MAX_EXPANDED_LENGTH }?.toString()
            }, selection = { before.selectedText })
            // Reject absolute UTF-16 offset overflow before changing composition or editor text.
            val end = Math.addExact(before.rangeStart, expanded.text.length)
            if (!stillCurrent() || !before.matches(ic)) return false
            // Finish composition first: commitText would otherwise replace a composing range
            // that may differ from the verified selection. This does not authorize retries.
            if (!ic.finishComposingText() || !before.matches(ic) || !stillCurrent()) return false
            // commitText already replaces the selection; batch edit is not an atomic transaction.
            onEditing()
            if (!before.matches(ic) || !stillCurrent()) return false
            if (!ic.commitText(expanded.text, 1)) return false
            if (!EditorReadback.matchesReplacement(ic, before, expanded.text, end) || !stillCurrent()) return false
            val cursor = expanded.cursorOffset ?: return true
            if (!stillCurrent()) return false
            val target = before.rangeStart + cursor // Android offsets are UTF-16, including surrogate pairs.
            ic.setSelection(target, target) && EditorReadback.capture(ic)?.let { it.start == target && it.end == target } == true
        }.getOrDefault(false)
    }
}
