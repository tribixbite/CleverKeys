package tribixbite.cleverkeys.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.ClipboardDatabase
import tribixbite.cleverkeys.ClipboardHistoryService
import tribixbite.cleverkeys.DirectBootAwarePreferences
import tribixbite.cleverkeys.EditEntryResult
import tribixbite.cleverkeys.TypedFakePreferences
import tribixbite.cleverkeys.clipboard.ClipboardEditorRowFixture.Companion.entry
import tribixbite.cleverkeys.clipboard.ClipboardEditorRowFixture.Companion.setField
import tribixbite.cleverkeys.clipboard.sanitize.SanitizationConfig

/**
 * Maintainer 2026-11-26: after editing `https://github.com/rustdesk/rustdesk-server` into
 * `https://github.com/tribixbite/rustdesk-server` — a URL they had just copied — "short swipe paste
 * function didn't paste it". The paste key asks the app to paste ANDROID's clipboard, and an edit
 * puts its text there only when the edited History row is still the clip there
 * ([EditedClipFollow], 2026-11-23). Cutting (or copying) inside the editor itself — Ctrl+X, the
 * cut key, the text toolbar — replaces that clip with the fragment, so at Save the row was no
 * longer "the clip" and the paste gave the cut fragment.
 *
 * The narrowest rule that makes this work: a clip the editor put there itself, while the clip it
 * replaced was the one this edit works with (the row as opened, an empty clipboard, or the
 * editor's own previous cut/copy), still counts as that clipping at Save. An old row's cut leaves
 * its fragment on the clipboard, and a copy made in another app meanwhile is never overwritten.
 *
 * REAL view (getView, cut, typing, save) and REAL service (the follow decision and the write) over
 * a fake Android clipboard and a mocked database.
 */
class ClipboardEditCutFollowTest {

    private val url = "https://github.com/rustdesk/rustdesk-server"
    private val edited = "https://github.com/tribixbite/rustdesk-server"

    private lateinit var rowFixture: ClipboardEditorRowFixture
    private lateinit var database: ClipboardDatabase

    /** Android's clipboard: the text of its one plain-text item, or null when empty. */
    private var systemClip: String? = null

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        mockkStatic(ClipData::class)
        every { ClipData.newPlainText(any(), any()) } answers { clipOf(secondArg<CharSequence>().toString()) }
        mockkStatic(DirectBootAwarePreferences::class)
        every { DirectBootAwarePreferences.get_shared_preferences(any()) } returns TypedFakePreferences()

        val clipboard = mockk<ClipboardManager>(relaxed = true)
        every { clipboard.primaryClip } answers { systemClip?.let(::clipOf) }
        every { clipboard.setPrimaryClip(any()) } answers {
            systemClip = firstArg<ClipData>().getItemAt(0).text.toString()
        }
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboard

        database = mockk(relaxed = true)
        every { database.updateHistoryEntryContent(any(), any()) } returns EditEntryResult.Success
        every { database.historyEntryIsPrivate(any()) } returns false
        val sanitization = mockk<SanitizationConfig>(relaxed = true)
        every { sanitization.sanitizer() } returns object : tribixbite.cleverkeys.clipboard.sanitize.UrlSanitizer {
            override fun process(text: String): String = text
        }
        val service = ObjenesisStd().newInstance(ClipboardHistoryService::class.java)
        service.setField("_context", context)
        service.setField("_database", database)
        service.setField("_cm", clipboard)
        service.setField("_sanitizationConfig\$delegate", lazyOf(sanitization))
        service.setField("serviceScope", CoroutineScope(Dispatchers.Unconfined))

        rowFixture = ClipboardEditorRowFixture(listOf(entry(url)), service = service, context = context)
    }

    @After
    fun teardown() {
        rowFixture.clear()
        unmockkAll()
    }

    private fun clipOf(text: String): ClipData {
        val item = mockk<ClipData.Item>(relaxed = true)
        every { item.text } returns text
        every { item.uri } returns null
        every { item.intent } returns null
        val clip = mockk<ClipData>(relaxed = true)
        every { clip.itemCount } returns 1
        every { clip.getItemAt(0) } returns item
        return clip
    }

    /** Open the URL row; select the first `rustdesk` (the org segment). */
    private fun openAndSelectTheOrg() {
        rowFixture.view.edit_entry(0)
        rowFixture.render()
        val org = url.indexOf("rustdesk")
        rowFixture.userPlacesCaret(org, org + "rustdesk".length)
    }

    @Test
    fun anEditAfterCuttingFromTheClipBeingEditedIsWhatThePasteGives() {
        systemClip = url  // just copied
        openAndSelectTheOrg()

        rowFixture.view.cutFromEditText()
        assertWithMessage("precondition: the cut put the fragment on Android's clipboard").that(systemClip).isEqualTo("rustdesk")
        rowFixture.view.insertEditText("tribixbite")
        rowFixture.view.save_edit()

        assertWithMessage("the paste key pastes Android's clipboard: it must hold the edited clipping")
            .that(systemClip).isEqualTo(edited)
    }

    @Test
    fun aCopyInsideTheEditorOfTheClipBeingEditedFollowsToo() {
        systemClip = url
        openAndSelectTheOrg()

        rowFixture.view.copyFromEditText()          // Ctrl+C on "rustdesk"
        rowFixture.view.insertEditText("tribixbite") // then type over the selection
        rowFixture.view.save_edit()

        assertWithMessage("copy then retype: still the clipping the user works with").that(systemClip).isEqualTo(edited)
    }

    @Test
    fun withoutACutTheClipBeingEditedFollowsAsBefore() {
        systemClip = url
        openAndSelectTheOrg()

        rowFixture.view.insertEditText("tribixbite")
        rowFixture.view.save_edit()

        assertWithMessage("the edited History row that is the clip replaces it").that(systemClip).isEqualTo(edited)
    }

    @Test
    fun anOldRowsCutFragmentStaysOnTheClipboard() {
        systemClip = "meet at 5"  // the clip is another clipping; the URL row is an old one
        openAndSelectTheOrg()

        rowFixture.view.cutFromEditText()
        rowFixture.view.insertEditText("tribixbite")
        rowFixture.view.save_edit()

        assertWithMessage("an old row's edit never replaces the clip; the user's cut stays there")
            .that(systemClip).isEqualTo("rustdesk")
    }

    /**
     * The field's own text toolbar (long press or double tap on `rustdesk`, then Cut) is the
     * editor's cut: before, the platform's TextView cut ran, which also skipped the #156 private
     * confirmation and the editor's undo history.
     */
    @Test
    fun theTextToolbarsCutIsTheEditorsCut() {
        var handler: ((Int) -> Boolean)? = null
        val field = mockk<ClipboardEditField>(relaxed = true)
        every { field.textMenuHandler = any() } answers { handler = firstArg() }
        every { field.textMenuHandler } answers { handler }
        val service = rowFixture.service
        val context = rowFixture.context
        rowFixture.clear()
        rowFixture = ClipboardEditorRowFixture(listOf(entry(url)), service = service, field = field, context = context)
        systemClip = url
        openAndSelectTheOrg()

        val handled = handler?.invoke(android.R.id.cut) ?: false
        assertWithMessage("the editor handles the toolbar's Cut").that(handled).isTrue()
        assertWithMessage("the fragment is on Android's clipboard").that(systemClip).isEqualTo("rustdesk")
        assertWithMessage("and out of the field").that(rowFixture.text).isEqualTo("https://github.com//rustdesk-server")
        rowFixture.view.insertEditText("tribixbite")
        rowFixture.view.save_edit()

        assertWithMessage("the toolbar's cut keeps the follow").that(systemClip).isEqualTo(edited)
        rowFixture.view.undoEditText()  // nothing to undo after the save; must not throw
    }

    @Test
    fun theTextToolbarLeavesOtherActionsToThePlatform() {
        var handler: ((Int) -> Boolean)? = null
        val field = mockk<ClipboardEditField>(relaxed = true)
        every { field.textMenuHandler = any() } answers { handler = firstArg() }
        val service = rowFixture.service
        val context = rowFixture.context
        rowFixture.clear()
        rowFixture = ClipboardEditorRowFixture(listOf(entry(url)), service = service, field = field, context = context)
        openAndSelectTheOrg()

        assertWithMessage("select all is the platform's").that(handler?.invoke(android.R.id.selectAll)).isFalse()
        assertWithMessage("share is the platform's").that(handler?.invoke(android.R.id.shareText)).isFalse()
    }

    @Test
    fun aCopyMadeElsewhereAfterTheCutIsNeverOverwritten() {
        systemClip = url
        openAndSelectTheOrg()

        rowFixture.view.cutFromEditText()
        systemClip = "hunter2"  // another app copied meanwhile (a password manager, say)
        rowFixture.view.insertEditText("tribixbite")
        rowFixture.view.save_edit()

        assertWithMessage("a clip CleverKeys' editor did not put there is someone else's").that(systemClip).isEqualTo("hunter2")
    }
}
