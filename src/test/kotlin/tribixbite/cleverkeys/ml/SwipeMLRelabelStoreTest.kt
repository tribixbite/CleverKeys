package tribixbite.cleverkeys.ml

import android.content.ContentValues
import android.content.Context
import android.content.res.Resources
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.DisplayMetrics
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.MLDataCollector
import tribixbite.cleverkeys.PrivacyManager

/**
 * Feature B wiring below [tribixbite.cleverkeys.SuggestionHandler]: the collector hands back the
 * trace id of the row it stored, and [SwipeMLDataStore.relabelRow] rewrites exactly that row.
 * (The SQLite layer is mocked — ARM64 Termux has no Robolectric runner.)
 */
class SwipeMLRelabelStoreTest {

    private val objenesis = ObjenesisStd()

    /** What the mocked ContentValues received (the android.jar stub has no storage). */
    private val putValues = HashMap<String, Any?>()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    /** A ContentValues mock that records every put into [putValues]. */
    private fun recordingValues(): ContentValues {
        val values = mockk<ContentValues>(relaxed = true)
        every { values.put(any<String>(), any<String>()) } answers { putValues[firstArg()] = secondArg<String>() }
        every { values.put(any<String>(), any<Int>()) } answers { putValues[firstArg()] = secondArg<Int>() }
        return values
    }

    @After
    fun teardown() = unmockkAll()

    private fun storedJson(word: String): String {
        val data = SwipeMLData(word, "user_selection", 1080, 2400, 900, "qwerty", SwipeMLData.ENGINE_CTC)
        data.addRawPoint(100f, 2000f, data.timestampUtc)
        data.addRawPoint(300f, 2010f, data.timestampUtc + 40)
        return data.toJSON().toString()
    }

    private fun dbWithRow(json: String?): SQLiteDatabase {
        val cursor = mockk<Cursor>(relaxed = true)
        every { cursor.moveToFirst() } returns (json != null)
        every { cursor.getString(0) } returns json
        val db = mockk<SQLiteDatabase>(relaxed = true)
        every { db.query(any(), any(), any(), any(), any(), any(), any()) } returns cursor
        every { db.update(any(), any(), any(), any()) } returns 1
        return db
    }

    @Test
    fun relabelRowRewritesTheLabelColumnAndTheJson() {
        val db = dbWithRow(storedJson("got"))

        val rewritten = SwipeMLDataStore.relabelRow(db, "trace-1", "git", ::recordingValues)

        assertThat(rewritten).isTrue()
        verify { db.query("swipe_data", arrayOf("json_data"), "trace_id=?", arrayOf("trace-1"), null, null, null) }
        verify { db.update("swipe_data", any(), "trace_id=?", arrayOf("trace-1")) }
        assertWithMessage("label column").that(putValues["target_word"]).isEqualTo("git")
        assertWithMessage("re-exported").that(putValues["is_exported"]).isEqualTo(0)
        val json = JSONObject(putValues["json_data"] as String)
        assertThat(json.getString("target_word")).isEqualTo("git")
        assertThat(json.getJSONObject("metadata").getString("corrected_from")).isEqualTo("got")
        assertWithMessage("collection source is not a column this touches")
            .that(putValues.keys).doesNotContain("collection_source")
    }

    @Test
    fun anUnknownTraceIdIsANoOp() {
        val db = dbWithRow(null)
        assertThat(SwipeMLDataStore.relabelRow(db, "missing", "git", ::recordingValues)).isFalse()
        verify(exactly = 0) { db.update(any(), any(), any(), any()) }
    }

    // ------------------------------------------------------------------ collector trace id

    private fun collector(canCollect: Boolean): MLDataCollector {
        val privacy = mockk<PrivacyManager>()
        every { privacy.canCollectSwipeData() } returns canCollect
        every { privacy.shouldPerformCleanup() } returns false
        val metrics = objenesis.newInstance(DisplayMetrics::class.java).apply {
            widthPixels = 1080
            heightPixels = 2400
        }
        val resources = mockk<Resources>()
        every { resources.displayMetrics } returns metrics
        val context = mockk<Context>()
        every { context.resources } returns resources
        val collector = objenesis.newInstance(MLDataCollector::class.java)
        collector.setField("context", context)
        collector.setField("privacyManager", privacy)
        return collector
    }

    private fun capture(): SwipeMLData = SwipeMLData("", "swipe_capture", 1080, 2400, 900, "qwerty", "ctc").apply {
        addRawPoint(100f, 2000f, timestampUtc)
        addRawPoint(300f, 2010f, timestampUtc + 40)
    }

    @Test
    fun theCollectorReportsTheTraceIdOfTheRowItStored() {
        val store = mockk<SwipeMLDataStore>()
        var stored: SwipeMLData? = null
        every { store.storeSwipeData(any()) } answers { stored = firstArg() }
        var reported: String? = null

        val ok = collector(canCollect = true).collectAndStoreSwipeData("got", capture(), 900, store) { reported = it }

        assertThat(ok).isTrue()
        assertThat(reported).isNotNull()
        assertWithMessage("the id of the row handed to the store, not the capture's")
            .that(reported).isEqualTo(stored!!.traceId)
    }

    @Test
    fun nothingIsReportedWhenCollectionIsOff() {
        val store = mockk<SwipeMLDataStore>(relaxed = true)
        var reported: String? = null
        collector(canCollect = false).collectAndStoreSwipeData("got", capture(), 900, store) { reported = it }
        assertThat(reported).isNull()
        verify(exactly = 0) { store.storeSwipeData(any()) }
    }

    private fun Any.setField(name: String, value: Any?) {
        val field = javaClass.declaredFields.first { it.name == name }
        field.isAccessible = true
        field.set(this, value)
    }
}
