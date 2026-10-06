package tribixbite.cleverkeys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Selection
import android.text.InputType
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.customization.DynamicTemplate
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.ShortSwipeCustomizations
import tribixbite.cleverkeys.customization.SwipeDirection
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.XmlAttributeMapper

@RunWith(AndroidJUnit4::class)
class DynamicTemplateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private class Editor(view: EditText) : BaseInputConnection(view, true) {
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int) = ExtractedText().apply {
            text = editable.toString(); startOffset = 0
            selectionStart = Selection.getSelectionStart(editable)
            selectionEnd = Selection.getSelectionEnd(editable)
        }
    }
    private fun runEditor(initial: String = "", start: Int = initial.length, end: Int = start, block: (Editor, EditorInfo) -> Unit) {
        instrumentation.runOnMainSync {
            val ic = Editor(EditText(context)); ic.commitText(initial, 1); ic.setSelection(start, end)
            block(ic, EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "test.editor" })
        }
    }
    @Test fun cursorUsesUtf16AfterSupplementaryCharacter() = runEditor("prefix ") { ic, info ->
        assertTrue(DynamicTemplate.execute("😀({cursor})", context, ic, info) { true })
        assertEquals("prefix 😀()", ic.editable.toString())
        assertEquals(10, Selection.getSelectionStart(ic.editable))
    }
    @Test fun selectionWrapPreservesOutsideText() = runEditor("left chosen right", 5, 11) { ic, info ->
        assertTrue(DynamicTemplate.execute("[{selection}]{cursor}", context, ic, info) { true })
        assertEquals("left [chosen] right", ic.editable.toString())
        assertEquals(13, Selection.getSelectionStart(ic.editable))
    }
    @Test fun reversedSelectionWrapsSameRange() = runEditor("abcd", 3, 1) { ic, info ->
        assertTrue(DynamicTemplate.execute("({selection})", context, ic, info) { true })
        assertEquals("a(bc)d", ic.editable.toString())
    }
    @Test fun collapsedSelectionIsActuallyEmpty() = runEditor("ab", 1) { ic, info ->
        assertTrue(DynamicTemplate.execute("[{selection}]", context, ic, info) { true })
        assertEquals("a[]b", ic.editable.toString())
    }
    @Test fun unavailableReadbackRefusesBeforeWrite() = runEditor("safe") { ic, info ->
        val unreadable = object : InputConnectionWrapper(ic, false) {
            override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
        }
        assertFalse(DynamicTemplate.execute("replacement", context, unreadable, info) { true })
        assertEquals("safe", ic.editable.toString())
    }
    @Test fun absoluteOffsetOverflowRefusesBeforeChangingEditor() = runEditor("safe") { ic, info ->
        var writes = 0
        var compositionChanges = 0
        val overflow = object : InputConnectionWrapper(ic, false) {
            override fun getExtractedText(request: ExtractedTextRequest?, flags: Int) = ExtractedText().apply {
                text = "safe"; startOffset = Int.MAX_VALUE; selectionStart = 0; selectionEnd = 0
            }
            override fun finishComposingText(): Boolean { compositionChanges++; return true }
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { writes++; return true }
        }
        assertFalse(DynamicTemplate.execute("x", context, overflow, info) { true })
        assertEquals(0, writes); assertEquals(0, compositionChanges)
        assertEquals("safe", ic.editable.toString())
    }
    @Test fun rejectedCommitNeverRetries() = runEditor("safe") { ic, info ->
        var calls = 0
        val rejecting = object : InputConnectionWrapper(ic, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { calls++; return false }
        }
        assertFalse(DynamicTemplate.execute("payload", context, rejecting, info) { true })
        assertEquals(1, calls); assertEquals("safe", ic.editable.toString())
    }
    @Test fun throwingCommitNeverRetries() = runEditor("safe") { ic, info ->
        var calls = 0
        val rejecting = object : InputConnectionWrapper(ic, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { calls++; throw IllegalStateException("editor unavailable") }
        }
        assertFalse(DynamicTemplate.execute("payload", context, rejecting, info) { true })
        assertEquals(1, calls); assertEquals("safe", ic.editable.toString())
    }
    @Test fun partialCommitIsReportedWithoutCompensation() = runEditor("safe") { ic, info ->
        var calls = 0
        val partial = object : InputConnectionWrapper(ic, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { calls++; ic.commitText("pa",1); return true }
        }
        assertFalse(DynamicTemplate.execute("payload{cursor}", context, partial, info) { true })
        assertEquals(1,calls); assertEquals("safepa",ic.editable.toString())
    }
    @Test fun refusedCaretDoesNotRepeatInsertion() = runEditor("a") { ic, info ->
        val refusing = object : InputConnectionWrapper(ic, false) {
            override fun setSelection(start: Int, end: Int) = false
        }
        assertFalse(DynamicTemplate.execute("({cursor})", context, refusing, info) { true })
        assertEquals("a()", ic.editable.toString())
    }
    @Test fun changedSessionDoesNotWrite() = runEditor("safe") { ic, info ->
        assertFalse(DynamicTemplate.execute("x", context, ic, info) { false })
        assertEquals("safe", ic.editable.toString())
    }
    @Test fun passwordRefusesExpansion() = runEditor("safe") { ic, info ->
        info.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertFalse(DynamicTemplate.execute("{selection}", context, ic, info) { true })
        assertEquals("safe", ic.editable.toString())
    }
    @Test fun uuidIsOncePerInvocationAndChangesAcrossInvocations() = runEditor { ic, info ->
        assertTrue(DynamicTemplate.execute("{uuid}:{uuid}", context, ic, info) { true })
        val first = ic.editable.toString().split(':'); assertEquals(first[0], first[1])
        assertTrue(DynamicTemplate.execute("/{uuid}", context, ic, info) { true })
        assertNotEquals(first[0], ic.editable.toString().substringAfter('/'))
    }
    @Test fun finishesCompositionBeforeInsertingAtVerifiedCaret() = runEditor("word") { ic, info ->
        assertTrue(ic.setComposingRegion(0, 4))
        assertTrue(DynamicTemplate.execute("!", context, ic, info) { true })
        assertEquals("word!", ic.editable.toString())
    }
    @Test fun rejectedCompositionFinishMakesNoTemplateWrite() = runEditor("safe") { ic, info ->
        var writes = 0
        val refusing = object : InputConnectionWrapper(ic, false) {
            override fun finishComposingText() = false
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { writes++; return ic.commitText(text, newCursorPosition) }
        }
        assertFalse(DynamicTemplate.execute("!", context, refusing, info) { true })
        assertEquals(0, writes); assertEquals("safe", ic.editable.toString())
    }
    @Test fun changedSessionAfterWriteIsReportedEvenWithoutCursorToken() = runEditor("safe") { ic, info ->
        var current = true
        val switching = object : InputConnectionWrapper(ic, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val accepted = ic.commitText(text, newCursorPosition); current = false; return accepted
            }
        }
        assertFalse(DynamicTemplate.execute("!", context, switching, info) { current })
        assertEquals("safe!", ic.editable.toString())
    }
    @Test fun continuousGuardRefusesChangedPrefixEvenWithCorrectWordTail() = runEditor("alpha ") { ic, _ ->
        val before = requireNotNull(EditorReadback.capture(ic))
        val guard = EditorCommitGuard(before) { true }
        assertTrue(guard.prepare(ic))
        ic.setSelection(0, 6); ic.commitText("omega cat ", 1)
        assertFalse(guard.accepted(ic, "cat "))
    }
    @Test fun continuousGuardSerializesVerifiedSeparatorAndWordWrites() = runEditor("typed") { ic, _ ->
        val guard = EditorCommitGuard(requireNotNull(EditorReadback.capture(ic))) { true }
        assertTrue(guard.prepare(ic)); ic.commitText(" ", 1); assertTrue(guard.accepted(ic, " "))
        assertTrue(guard.prepare(ic)); ic.commitText("word ", 1); assertTrue(guard.accepted(ic, "word "))
        assertEquals("typed word ", ic.editable.toString())
    }
    @Test fun continuousGuardRefusesChangedSessionAfterAcknowledgedWrite() = runEditor("safe ") { ic, _ ->
        var current = true
        val guard = EditorCommitGuard(requireNotNull(EditorReadback.capture(ic))) { current }
        assertTrue(guard.prepare(ic)); ic.commitText("word ", 1); current = false
        assertFalse(guard.accepted(ic, "word "))
    }
    @Test fun sessionChangedDuringEditNotificationPreventsTemplateWrite() = runEditor("safe") { ic, info ->
        var current = true
        assertFalse(DynamicTemplate.execute("!", context, ic, info, onEditing = { current = false }) { current })
        assertEquals("safe", ic.editable.toString())
    }
    private fun receiver(ic: Editor, info: EditorInfo, mode: Int = -1): KeyEventHandler.IReceiver = object : KeyEventHandler.IReceiver {
                override fun getHandler() = Handler(Looper.getMainLooper())
                override fun getContext() = context
                override fun getCurrentInputConnection() = ic
                override fun getCurrentEditorInfo() = info
                override fun isClipboardTagMode() = mode == 0
                override fun isClipboardEditMode() = mode == 1
                override fun isClipboardSearchMode() = mode == 2
                override fun isEmojiPaneOpen() = mode == 3
                override fun isGifPaneOpen() = mode == 4
                override fun selection_state_changed(selectionIsOngoing: Boolean) {}
                override fun handle_text_typed(text: String) {}
                override fun handle_event_key(event: KeyValue.Event) {}
                override fun set_shift_state(shift: Boolean, lock: Boolean) {}
                override fun set_compose_pending(pending: Boolean) {}
            }

    @Test fun ordinaryTemplateKeyUsesSharedRoutingAndCaretPlacement() = runEditor("a") { ic, info ->
        KeyEventHandler(receiver(ic, info)).key_up(KeyValue.makeTemplateKey("{}","😀({cursor})"),Pointers.Modifiers.EMPTY)
        assertEquals("a😀()",ic.editable.toString())
        assertEquals(4,Selection.getSelectionStart(ic.editable))
    }
    @Test fun hiddenEditorIsNotChangedInAnyInlineMode() = runEditor("safe") { ic, info ->
        for (mode in 0..4) {
            val receiver = receiver(ic, info, mode)
            assertFalse(KeyEventHandler(receiver).execute_template("payload"))
        }
        assertEquals("safe", ic.editable.toString())
    }
    @Test fun actualPlainClipboardIsInsertedWithoutRecursiveExpansion() {
        androidx.test.core.app.ActivityScenario.launch(androidx.activity.ComponentActivity::class.java).use {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val original = manager.primaryClip
            try {
                manager.setPrimaryClip(ClipData.newPlainText("template-test", "{uuid}😀"))
                runEditor { ic, info ->
                    assertTrue(DynamicTemplate.execute("[{clipboard}]",context,ic,info){true})
                    assertEquals("[{uuid}😀]",ic.editable.toString())
                }
            } finally { if(original != null) manager.setPrimaryClip(original) else manager.clearPrimaryClip() }
        }
    }
    @Test fun uriClipboardRefusesBeforeEditing() {
        androidx.test.core.app.ActivityScenario.launch(androidx.activity.ComponentActivity::class.java).use {
            val manager=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val original=manager.primaryClip
            try {
                manager.setPrimaryClip(ClipData.newRawUri("template-test",android.net.Uri.parse("content://test/unreadable")))
                runEditor("safe") {ic,info->assertFalse(DynamicTemplate.execute("{clipboard}",context,ic,info){true});assertEquals("safe",ic.editable.toString())}
            } finally { if(original != null) manager.setPrimaryClip(original) else manager.clearPrimaryClip() }
        }
    }
    @Test fun actualMappingFileReloadAndBackupImportKeepTemplateType() = kotlinx.coroutines.runBlocking {
        val directory=java.io.File(context.cacheDir,"template-mappings-"+java.util.UUID.randomUUID()).apply { assertTrue(mkdirs()) }
        val isolated=object: android.content.ContextWrapper(context) {
            override fun getFilesDir()=directory
            override fun createDeviceProtectedStorageContext(): Context=this
        }
        val constructor=tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager::class.java.getDeclaredConstructor(Context::class.java).apply{isAccessible=true}
        val mapping=ShortSwipeMapping("a",SwipeDirection.N,"wrap",ActionType.TEMPLATE,"[{selection}]")
        try {
            val manager=constructor.newInstance(isolated);manager.loadMappings();manager.setMapping(mapping)
            val backup=manager.exportToJson()
            val reloaded=constructor.newInstance(isolated);reloaded.loadMappings()
            assertEquals(mapping,reloaded.getMapping("a",SwipeDirection.N))
            assertEquals(1,reloaded.importFromJson(backup,merge=false))
            assertEquals(mapping,reloaded.getMapping("a",SwipeDirection.N))
            val malformed=backup.replace("[{selection}]","{unknown}")
            assertEquals(0,reloaded.importFromJson(malformed,merge=false))
            assertEquals(mapping,reloaded.getMapping("a",SwipeDirection.N))
        } finally { directory.deleteRecursively() }
    }
    @Test fun mappingSerializationKeepsExplicitType() {
        val mapping = ShortSwipeMapping("a", SwipeDirection.N, "{}", ActionType.TEMPLATE, "[{selection}]")
        val gson = com.google.gson.Gson()
        val decoded = gson.fromJson(gson.toJson(ShortSwipeCustomizations.fromMappingList(listOf(mapping))), ShortSwipeCustomizations::class.java)
        assertEquals(listOf(mapping), decoded.toMappingList())
        val key = KeyValueParser.parse("{}:" + XmlAttributeMapper.toXmlValue(mapping))
        assertEquals(KeyValue.Kind.Template, key.getKind())
        assertEquals(mapping.actionValue, key.getTemplateFormat().template)
    }
}
