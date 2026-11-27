package tribixbite.cleverkeys.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.ClipboardDatabase
import tribixbite.cleverkeys.ClipboardHistoryService
import tribixbite.cleverkeys.ClipboardSanitizeSettings
import tribixbite.cleverkeys.DirectBootAwarePreferences
import tribixbite.cleverkeys.EditEntryResult
import tribixbite.cleverkeys.TypedFakePreferences
import tribixbite.cleverkeys.clipboard.ClipboardEditorRowFixture.Companion.setField
import tribixbite.cleverkeys.clipboard.sanitize.SanitizationConfig
import tribixbite.cleverkeys.clipboard.sanitize.UrlSanitizer

/**
 * A REAL [ClipboardHistoryService] (Objenesis, its fields injected) over a fake Android clipboard
 * and a fake History table (mock tier):
 *
 *  - [systemClip] is Android's clipboard: the text of its one plain-text item, or null when empty.
 *    `setPrimaryClip` sets it and then delivers the change to the service's capture
 *    ([ClipboardHistoryService.addClip]), as its OnPrimaryClipChangedListener does: a cut or copy
 *    becomes a History row, and the service's own write after an edit is skipped once.
 *  - [history] is the History table, newest first: a capture inserts at the top (an existing row
 *    moves there, #108), an edit replaces the row in place keeping its position (the timestamp
 *    is not changed by an edit), a duplicate edit is refused, a missing row is EntryChanged.
 *
 * Call [mockStatics] in the test's setup before constructing it (and `unmockkAll` in teardown).
 */
internal class ClipboardServiceFixture(initialHistory: List<String> = emptyList()) {

    var systemClip: String? = null
    val history: MutableList<String> = initialHistory.toMutableList()

    val clipboard: ClipboardManager = mockk(relaxed = true)
    val context: Context = mockk(relaxed = true)
    val database: ClipboardDatabase = mockk(relaxed = true)
    val service: ClipboardHistoryService = ObjenesisStd().newInstance(ClipboardHistoryService::class.java)

    init {
        every { clipboard.primaryClip } answers { systemClip?.let(::clipOf) }
        every { clipboard.setPrimaryClip(any()) } answers {
            val text = firstArg<ClipData>().getItemAt(0).text.toString()
            systemClip = text
            service.addClip(text)  // the OnPrimaryClipChangedListener's capture
        }
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboard

        every { database.addClipboardEntry(any(), any(), any(), any()) } answers {
            val text = firstArg<String?>()?.trim().orEmpty()
            history.remove(text)
            history.add(0, text)
            true
        }
        every { database.updateHistoryEntryContent(any(), any()) } answers {
            val old = firstArg<String>()
            val new = secondArg<String>()
            when {
                new != old && new in history -> EditEntryResult.DuplicateConflict
                old !in history -> EditEntryResult.EntryChanged
                else -> {
                    history[history.indexOf(old)] = new
                    EditEntryResult.Success
                }
            }
        }
        every { database.historyEntryIsPrivate(any()) } returns false
        // The History limits after a capture: nothing pruned.
        every { database.applySizeLimit(any()) } returns Pair(0, emptyList())
        every { database.applySizeLimitBytes(any(), any()) } returns Pair(0, emptyList())

        val identity = object : UrlSanitizer {
            override fun process(text: String): String = text
        }
        val sanitization = mockk<SanitizationConfig>(relaxed = true)
        every { sanitization.sanitizer() } returns identity
        every { sanitization.sanitizer(any()) } returns identity
        every { sanitization.storedSwitches() } returns ClipboardSanitizeSettings.fromSettings(TypedFakePreferences())

        service.setField("_context", context)
        service.setField("_database", database)
        service.setField("_cm", clipboard)
        service.setField("_sanitizationConfig\$delegate", lazyOf(sanitization))
        service.setField("serviceScope", CoroutineScope(Dispatchers.Unconfined))
    }

    companion object {
        /** The statics the real service and view reach: ClipData and the stored settings. */
        fun mockStatics() {
            mockkStatic(ClipData::class)
            every { ClipData.newPlainText(any(), any()) } answers { clipOf(secondArg<CharSequence>().toString()) }
            mockkStatic(DirectBootAwarePreferences::class)
            every { DirectBootAwarePreferences.get_shared_preferences(any()) } returns TypedFakePreferences()
        }

        fun clipOf(text: String): ClipData {
            val item = mockk<ClipData.Item>(relaxed = true)
            every { item.text } returns text
            every { item.uri } returns null
            every { item.intent } returns null
            val clip = mockk<ClipData>(relaxed = true)
            every { clip.itemCount } returns 1
            every { clip.getItemAt(0) } returns item
            return clip
        }
    }
}
