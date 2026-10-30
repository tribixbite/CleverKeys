package tribixbite.cleverkeys.customization

import android.content.res.Resources
import android.content.res.TypedArray
import android.util.Log
import android.util.Xml
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.File
import org.json.JSONArray
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.MockSharedPreferences
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.prefs.LayoutsPreference

/**
 * Maintainer request 2026-10-30: changing a subkey that a BUILT-IN layout defines must not change
 * the built-in layout. The first such edit replaces the entry with a modified copy (a custom
 * layout carrying the edit, the original's bindings and its origin); later edits change the copy;
 * Layout Manager reverts it; the `layouts` preference (and so Backup & Restore) carries it.
 *
 * Mock tier: real SharedPreferences stand-in, mocked Resources serving the real
 * `latn_qwerty_us.xml`, kxml2 for android.util.Xml.
 */
class LayoutSubkeyEditorTest {

    private lateinit var prefs: MockSharedPreferences
    private lateinit var res: Resources
    private val qwertyXml = File("src/main/layouts/latn_qwerty_us.xml").readText()
    private val qwertyId = 0x7f0f0042

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        mockkStatic(Xml::class)
        every { Xml.newPullParser() } answers { KXmlParser() }
        resetLayoutNameCache()

        prefs = MockSharedPreferences()
        res = mockk(relaxed = true)
        every { res.getStringArray(R.array.pref_layout_values) } returns arrayOf("system", "latn_qwerty_us", "custom")
        every { res.getStringArray(R.array.pref_layout_entries) } returns arrayOf("System", "QWERTY (US)", "Custom")
        val ids = mockk<TypedArray>()
        every { ids.getResourceId(1, 0) } returns qwertyId
        every { ids.getResourceId(0, 0) } returns 0
        every { ids.getResourceId(2, 0) } returns 0
        every { res.obtainTypedArray(R.array.layout_ids) } returns ids
        every { res.openRawResource(qwertyId) } answers { qwertyXml.byteInputStream() }
        every { res.getString(R.string.subkey_layout_copy_name, *anyVararg()) } answers {
            "${(secondArg<Array<Any?>>())[0]} (modified)"
        }
    }

    @After
    fun teardown() {
        resetLayoutNameCache()
        unmockkAll()
    }

    /** LayoutsPreference caches the layout-name arrays process-wide; isolate this test's mocks. */
    private fun resetLayoutNameCache() {
        for (name in listOf("unsafeLayoutIdsStr", "unsafeLayoutIdsRes")) {
            LayoutsPreference::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, null)
        }
    }

    private fun store(vararg entries: LayoutsPreference.Layout) {
        val e = prefs.edit()
        LayoutsPreference.saveToPreferences(e, entries.toList())
        e.commit()
    }

    private val voice = KeyValue.getKeyByName("voice_input")
    private val nw = SwipeDirection.NW.subLabelIndex

    @Test
    fun theFirstEditOfABuiltInLayoutCreatesAModifiedCopyInItsPlace() {
        val named = LayoutsPreference.NamedLayout("latn_qwerty_us", language = "de", theme = "rosepine")
        store(LayoutsPreference.SystemLayout(), named)
        val editor = LayoutSubkeyEditor(prefs, res)

        val outcome = editor.apply(1, named, 0, 0, SwipeDirection.NW, "voice_input")

        assertThat(outcome).isInstanceOf(LayoutSubkeyEditor.Outcome.Saved::class.java)
        assertThat((outcome as LayoutSubkeyEditor.Outcome.Saved).copyCreated).isTrue()
        val entries = editor.entries()
        assertThat(entries).hasSize(2)
        assertThat(entries[0]).isInstanceOf(LayoutsPreference.SystemLayout::class.java)
        val copy = entries[1] as LayoutsPreference.CustomLayout
        assertThat(copy.origin).isEqualTo("latn_qwerty_us")
        assertThat(copy.language).isEqualTo("de")
        assertThat(copy.theme).isEqualTo("rosepine")
        assertThat(copy.parsed!!.name).isEqualTo("QWERTY (US) (modified)")
        assertThat(copy.parsed!!.rows[0].keys[0].keys[nw]).isEqualTo(voice)
        // The built-in itself is untouched: it is still read from the resource as before.
        assertThat(editor.sourceXml(named)).isEqualTo(qwertyXml)
    }

    @Test
    fun laterEditsChangeTheCopyItself() {
        val named = LayoutsPreference.NamedLayout("latn_qwerty_us")
        store(named)
        val editor = LayoutSubkeyEditor(prefs, res)
        val first = editor.apply(0, named, 0, 0, SwipeDirection.NW, "voice_input") as LayoutSubkeyEditor.Outcome.Saved

        val second = editor.apply(0, first.entry, 0, 1, SwipeDirection.NW, null)

        assertThat((second as LayoutSubkeyEditor.Outcome.Saved).copyCreated).isFalse()
        val copy = editor.entries().single() as LayoutsPreference.CustomLayout
        assertThat(copy.origin).isEqualTo("latn_qwerty_us")
        assertThat(copy.parsed!!.name).isEqualTo("QWERTY (US) (modified)") // not renamed twice
        assertThat(copy.parsed!!.rows[0].keys[0].keys[nw]).isEqualTo(voice)
        assertThat(copy.parsed!!.rows[0].keys[1].keys.getOrNull(nw)).isNull()
    }

    @Test
    fun theSystemEntryCopiesTheLayoutItShowsAndRevertsToSystem() {
        store(LayoutsPreference.SystemLayout())
        val editor = LayoutSubkeyEditor(prefs, res, resolveSystemName = { "latn_qwerty_us" })

        val outcome = editor.apply(0, LayoutsPreference.SystemLayout(), 0, 0, SwipeDirection.NW, "voice_input")

        val copy = (outcome as LayoutSubkeyEditor.Outcome.Saved).entry
        assertThat(copy.origin).isEqualTo(LayoutsPreference.CustomLayout.ORIGIN_SYSTEM)
        assertThat(copy.revertedFrom()).isEqualTo(LayoutsPreference.SystemLayout())
    }

    @Test
    fun anEntryThatChangedMeanwhileIsNotOverwritten() {
        store(LayoutsPreference.NamedLayout("latn_qwerty_us"))
        val before = prefs.getString(LayoutsPreference.KEY, null)
        val editor = LayoutSubkeyEditor(prefs, res)

        val outcome = editor.apply(0, LayoutsPreference.SystemLayout(), 0, 0, SwipeDirection.NW, "voice_input")

        assertThat(outcome).isInstanceOf(LayoutSubkeyEditor.Outcome.Failed::class.java)
        assertThat(prefs.getString(LayoutsPreference.KEY, null)).isEqualTo(before)
    }

    @Test
    fun anEditThatWouldBreakTheLayoutIsRefused() {
        val named = LayoutsPreference.NamedLayout("latn_qwerty_us")
        store(named)
        val editor = LayoutSubkeyEditor(prefs, res)
        val outcome = editor.apply(0, named, 99, 0, SwipeDirection.NW, "voice_input")
        assertThat(outcome).isInstanceOf(LayoutSubkeyEditor.Outcome.Failed::class.java)
        assertThat(editor.entries().single()).isEqualTo(named)
    }

    @Test
    fun revertKeepsTheBindingsAndTheOriginSurvivesStorage() {
        val named = LayoutsPreference.NamedLayout("latn_qwerty_us", language = "fr", theme = "rosepine")
        store(named)
        val editor = LayoutSubkeyEditor(prefs, res)
        editor.apply(0, named, 0, 0, SwipeDirection.NW, "voice_input")

        // Reloaded from the stored JSON (what Backup & Restore exports and imports verbatim).
        val stored = JSONArray(prefs.getString(LayoutsPreference.KEY, null))
        assertThat(stored.getJSONObject(0).getString("origin")).isEqualTo("latn_qwerty_us")
        val copy = editor.entries().single() as LayoutsPreference.CustomLayout
        assertThat(copy.revertedFrom()).isEqualTo(named)
    }

    @Test
    fun aHostileOriginIsDropped() {
        val json = """[{"kind":"custom","xml":${org.json.JSONObject.quote(qwertyXml)},"origin":"../../etc"}]"""
        prefs.edit().putString(LayoutsPreference.KEY, json).commit()
        val copy = LayoutSubkeyEditor(prefs, res).entries().single() as LayoutsPreference.CustomLayout
        assertThat(copy.origin).isNull()
        assertThat(copy.revertedFrom()).isNull()
    }
}
