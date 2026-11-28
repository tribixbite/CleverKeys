package tribixbite.cleverkeys.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.ClipboardDatabase
import tribixbite.cleverkeys.ClipboardHistoryLimits
import tribixbite.cleverkeys.ClipboardHistoryService
import tribixbite.cleverkeys.ClipboardMediaManager
import tribixbite.cleverkeys.ClipboardSanitizeSettings
import tribixbite.cleverkeys.ClipboardSizePolicy
import tribixbite.cleverkeys.DirectBootAwarePreferences
import tribixbite.cleverkeys.TypedFakePreferences
import tribixbite.cleverkeys.clipboard.ClipboardEditorRowFixture.Companion.setField
import tribixbite.cleverkeys.clipboard.sanitize.SanitizationConfig
import tribixbite.cleverkeys.clipboard.sanitize.UrlSanitizer

/**
 * Saga r26 (2026-11-28) History-loss report, replayed through the REAL capture, limit and delete
 * code: [ClipboardHistoryService.addClip] → [ClipboardDatabase.addClipboardEntry] (de-duplication,
 * #108) → the History limits → [ClipboardHistoryService.removeHistoryEntry] →
 * [ClipboardDatabase.removeClipboardEntry]. SQLite is faked: an in-memory `clipboard_entries`
 * table that answers each statement those methods run, and fails on any statement it does not know
 * (so an unexpected deletion path cannot hide). The two database writes that build a ContentValues
 * (the insert/de-dup and the expiry rescue) are restated over that table, see setup.
 *
 * Reported: 18 rows before a 44-minute debug-app instrumented run with History set to Unlimited and
 * Never expire; the run set the system clipboard ~24 times and the release keyboard captured each;
 * the agent then deleted the 24 rows that carried in-run times; 5 rows were left, so "13 old rows
 * were gone".
 *
 * What the code does: no limit, expiry or migration path deletes anything with those settings in
 * any of their stored forms. A capture of text History already holds does not insert a row: it
 * moves the existing row to the top with a NEW timestamp. When 13 of the 24 captured texts were
 * already in History (fixed test-fixture strings captured by an earlier run on the same phone),
 * History held 18 + 11 = 29 rows, 24 of them with in-run times, and deleting those 24 deleted the
 * 13 old rows themselves. Mock tier.
 */
class ClipboardHistoryLossScenarioTest {

    /** One `clipboard_entries` row (text only: no media, no thumbnail). */
    private data class Row(
        val id: Long,
        var content: String,
        var timestamp: Long,
        var expiry: Long,
        var hash: String,
        var isPrivate: Int = 0,
        var source: String? = null,
    )

    private val table = mutableListOf<Row>()
    private var nextId = 1L

    /** Every row deletion any statement made, with the statement kind, for "who deleted it". */
    private val deletions = mutableListOf<Pair<String, String>>()

    /** Every Log.e message (a swallowed exception would otherwise read as "nothing happened"). */
    private val errors = mutableListOf<String>()

    private lateinit var settings: TypedFakePreferences
    private lateinit var database: ClipboardDatabase
    private lateinit var service: ClipboardHistoryService

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        // Errors are kept: the database layer swallows exceptions into Log.e and a return value.
        every { Log.e(any(), any<String>()) } answers { errors += secondArg<String>(); 0 }
        every { Log.e(any(), any<String>(), any()) } answers { errors += secondArg<String>(); 0 }

        settings = TypedFakePreferences()
        mockkStatic(DirectBootAwarePreferences::class)
        every { DirectBootAwarePreferences.get_shared_preferences(any()) } returns settings

        val sqlite = fakeSqlite()
        database = spyk(ObjenesisStd().newInstance(ClipboardDatabase::class.java))
        every { database.writableDatabase } returns sqlite
        every { database.readableDatabase } returns sqlite
        // The two writes that build an android.content.ContentValues cannot run in the mock tier
        // (its android.jar constructor throws "Stub!", and mockkConstructor still runs it). They
        // are restated over the same table, statement for statement, from ClipboardDatabase.kt.
        every { database.addClipboardEntry(any(), any(), any(), any()) } answers {
            addClipboardEntryAsTheDatabaseDoes(firstArg(), secondArg(), thirdArg(), arg(3))
        }
        every { database.rescueExpiredEntries() } answers {
            val now = System.currentTimeMillis()
            val stale = table.filter { it.expiry <= now && it.expiry != Long.MAX_VALUE }
            stale.forEach { it.expiry = Long.MAX_VALUE }
            stale.size
        }

        val identity = object : UrlSanitizer {
            override fun process(text: String): String = text
        }
        val sanitization = mockk<SanitizationConfig>(relaxed = true)
        every { sanitization.sanitizer(any()) } returns identity
        every { sanitization.storedSwitches() } returns ClipboardSanitizeSettings.fromSettings(TypedFakePreferences())

        // Android's clipboard as the release keyboard sees it after the run: not one of these rows
        // (and, unfocused on Android 10+, unreadable anyway), so no delete clears it.
        val clipboard = mockk<ClipboardManager>(relaxed = true)
        every { clipboard.primaryClip } returns null
        service = ObjenesisStd().newInstance(ClipboardHistoryService::class.java)
        service.setField("_context", mockk<Context>(relaxed = true))
        service.setField("_database", database)
        service.setField("_cm", clipboard)
        service.setField("_mediaManager\$delegate", lazyOf(mockk<ClipboardMediaManager>(relaxed = true)))
        service.setField("_sanitizationConfig\$delegate", lazyOf(sanitization))
    }

    @After
    fun teardown() = unmockkAll()

    // ─── The fake SQLite layer ───

    private fun fakeSqlite(): SQLiteDatabase {
        val sqlite = mockk<SQLiteDatabase>()
        every { sqlite.rawQuery(any(), any()) } answers {
            val sql = firstArg<String>().replace(Regex("\\s+"), " ").trim()
            val args = secondArg<Array<String>?>() ?: emptyArray()
            query(sql, args)
        }
        every { sqlite.delete("clipboard_entries", any(), any()) } answers {
            val where = secondArg<String>()
            val arg = thirdArg<Array<String>?>()?.firstOrNull()
            val gone = when (where) {
                "content = ?" -> table.filter { it.content == arg }
                "expiry_timestamp <= ?" -> table.filter { it.expiry <= arg!!.toLong() }
                else -> error("unexpected delete: $where")
            }
            gone.forEach { deletions += where to it.content }
            table.removeAll(gone)
            gone.size
        }
        every { sqlite.execSQL(any()) } answers {
            val sql = firstArg<String>()
            require(sql.startsWith("DELETE FROM clipboard_entries WHERE id IN (")) { "unexpected SQL: $sql" }
            val ids = sql.substringAfter("IN (").substringBefore(")").split(',').map { it.trim().toLong() }
            table.filter { it.id in ids }.forEach { deletions += "limit prune" to it.content }
            table.removeAll { it.id in ids }
        }
        return sqlite
    }

    /**
     * ClipboardDatabase.addClipboardEntry (ClipboardDatabase.kt:326-385): blank → false; the text
     * is trimmed; a NON-EXPIRED row with the same hash and text is NOT duplicated — its timestamp
     * becomes now and its expiry the new one (#108, "moved to the top"), privacy ORs and the
     * newest non-null source wins; otherwise a new row is inserted.
     */
    private fun addClipboardEntryAsTheDatabaseDoes(content: String?, expiry: Long, isPrivate: Boolean, source: String?): Boolean {
        if (content.isNullOrBlank()) return false
        val text = content.trim()
        val now = System.currentTimeMillis()
        val existing = table.firstOrNull { it.hash == text.hashCode().toString() && it.content == text && it.expiry > now }
        if (existing != null) {
            existing.timestamp = now
            existing.expiry = expiry
            existing.isPrivate = if (existing.isPrivate != 0 || isPrivate) 1 else 0
            if (source != null) existing.source = source
            return true
        }
        table += Row(nextId++, text, now, expiry, text.hashCode().toString(), if (isPrivate) 1 else 0, source)
        return true
    }

    /** Answers each History SELECT the code under test runs, the way SQLite would. */
    private fun query(sql: String, args: Array<String>): Cursor = when {
        sql.startsWith("SELECT id, is_private, source_package FROM clipboard_entries WHERE content_hash = ? AND content = ? AND expiry_timestamp > ?") ->
            cursorOf(table.filter { it.hash == args[0] && it.content == args[1] && it.expiry > args[2].toLong() }
                .map { arrayOf(it.id, it.isPrivate, it.source) })
        // The History reader (getActiveClipboardEntries). Since Fable audit H3 its large columns
        // read NULL and its last column (the fits flag) 0 for a row over the read budget.
        sql.startsWith("SELECT CASE WHEN") &&
            sql.contains(" THEN content END, timestamp, mime_type, ") &&
            sql.contains(" FROM clipboard_entries WHERE expiry_timestamp > ? ORDER BY timestamp DESC") ->
            cursorOf(table.filter { it.expiry > args[0].toLong() }.sortedByDescending { it.timestamp }
                .map {
                    val fits = ClipboardSizePolicy.utf8Bytes(it.content) <= ClipboardSizePolicy.MAX_READ_ROW_BYTES
                    arrayOf(if (fits) it.content else null, it.timestamp, null, null, null, it.isPrivate, it.source, it.id, if (fits) 1 else 0)
                })
        sql.startsWith("SELECT id, LENGTH(CAST(content AS BLOB)) + COALESCE(LENGTH(thumbnail_blob), 0), media_path FROM clipboard_entries WHERE expiry_timestamp > ? ORDER BY timestamp DESC") ->
            cursorOf(table.filter { it.expiry > args[0].toLong() }.sortedByDescending { it.timestamp }
                .map { arrayOf(it.id, it.content.toByteArray(Charsets.UTF_8).size.toLong(), null) })
        sql == "SELECT 1 FROM clipboard_entries WHERE content = ? LIMIT 1" ->
            cursorOf(table.filter { it.content == args[0] }.take(1).map { arrayOf(1) })
        sql == "SELECT media_path FROM clipboard_entries WHERE content = ? LIMIT 1" ->
            cursorOf(table.filter { it.content == args[0] }.take(1).map { arrayOf(null) })
        sql.startsWith("SELECT COUNT(*) FROM clipboard_entries WHERE expiry_timestamp > ?") ->
            cursorOf(listOf(arrayOf(table.count { it.expiry > args[0].toLong() })))
        sql.startsWith("SELECT id, media_path FROM clipboard_entries WHERE expiry_timestamp > ? ORDER BY timestamp ASC LIMIT ?") ->
            cursorOf(table.filter { it.expiry > args[0].toLong() }.sortedBy { it.timestamp }.take(args[1].toInt())
                .map { arrayOf(it.id, null) })
        else -> error("unexpected query: $sql")
    }

    private fun cursorOf(rows: List<Array<out Any?>>): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        var position = -1
        every { cursor.moveToFirst() } answers { position = 0; rows.isNotEmpty() }
        every { cursor.moveToNext() } answers { ++position < rows.size }
        every { cursor.getLong(any()) } answers { (rows[position][firstArg<Int>()] as Number).toLong() }
        every { cursor.getInt(any()) } answers { (rows[position][firstArg<Int>()] as Number).toInt() }
        every { cursor.getString(any()) } answers { rows[position][firstArg<Int>()] as String? }
        every { cursor.getBlob(any()) } answers { rows[position][firstArg<Int>()] as ByteArray? }
        every { cursor.isNull(any()) } answers { rows[position][firstArg<Int>()] == null }
        return cursor
    }

    // ─── The scenario ───

    /**
     * Thirteen fixture texts the instrumented tests copy every run (the androidTest sources set
     * fixed strings such as "PASTED", "Planet", "Fresh", "Replaced", "zz-clear-key"; the rest here are
     * illustrative). The report's byte counts fit short fixture text: the 13 lost rows held
     * 387 − 273 = 114 B, about 9 B each, while the 5 left average 55 B.
     */
    private val fixtureTexts = listOf(
        "PASTED", "Planet", "Fresh", "Added", "Replaced", "zz-clear-key", "baseline",
        "original", "fragment", "rustdesk", "tribixbite", "fork", "{uuid}",
    )

    /** The user's own five rows: longer text, never copied by a test. */
    private val userTexts = (1..5).map { "user clipping $it: " + "x".repeat(40) }

    /** Eleven texts this run copied for the first time. */
    private val newRunTexts = (1..11).map { "run-$it" }

    private val day = 24L * 60 * 60 * 1000

    /** The 18 rows History held before the run, captured around 2026-11-25 (three days back). */
    private fun seedOldHistory(expiry: Long = Long.MAX_VALUE) {
        val start = System.currentTimeMillis() - 3 * day
        (fixtureTexts + userTexts).forEachIndexed { i, text ->
            table += Row(nextId++, text, start + i * 60_000L, expiry, text.hashCode().toString())
        }
    }

    /** The run: 24 captures by the release keyboard's listener, interleaved as a test run would. */
    private fun runCaptures(): Long {
        val runStart = System.currentTimeMillis()
        val captures = fixtureTexts.zip(newRunTexts).flatMap { (a, b) -> listOf(a, b) } +
            fixtureTexts.drop(newRunTexts.size)
        assertThat(captures).hasSize(24)
        captures.forEach { service.addClip(it); Thread.sleep(1) }
        assertWithMessage("errors logged during the captures").that(errors).isEmpty()
        return runStart
    }

    /** The agent's cleanup: one pane delete per row whose time falls inside the run. */
    private fun deleteInRunRows(runStart: Long): Int {
        val inRun = service.clearExpiredAndGetHistory().filter { it.timestamp >= runStart }
        inRun.forEach { service.removeHistoryEntry(it.content) }
        return inRun.size
    }

    /** Settings as the Settings screen stores Unlimited + Never expire (saveSetting: Int / String). */
    private fun storeUnlimitedNeverAsSettingsDoes() {
        settings.values["clipboard_history_enabled"] = true
        settings.values["clipboard_limit_type"] = "count"
        settings.values["clipboard_history_limit"] = 0
        settings.values["clipboard_history_duration"] = "-1"
    }

    @Test
    fun theThirteenOldRowsWereDeletedByTheCleanupNotByTheApp() {
        storeUnlimitedNeverAsSettingsDoes()
        seedOldHistory()

        val runStart = runCaptures()

        // Nothing automatic deleted a row: every old text is still in History after the run.
        assertWithMessage("rows the app deleted on its own").that(deletions).isEmpty()
        assertThat(table.map { it.content }).containsAtLeastElementsIn(fixtureTexts + userTexts)
        assertThat(table).hasSize(18 + 11)  // 29, as the report computed — but 13 are old rows
        val inRun = table.filter { it.timestamp >= runStart }
        assertThat(inRun).hasSize(24)
        assertWithMessage("old rows now carrying an in-run time")
            .that(inRun.map { it.content }.filter { it in fixtureTexts }).containsExactlyElementsIn(fixtureTexts)

        val deleted = deleteInRunRows(runStart)

        assertThat(deleted).isEqualTo(24)
        assertThat(table.map { it.content }).containsExactlyElementsIn(userTexts)
        assertThat(table).hasSize(5)
        // Every deletion was one of the pane's single deletes, and each removed exactly one row.
        assertThat(deletions.map { it.first }.toSet()).containsExactly("content = ?")
        assertThat(deletions).hasSize(24)
        println(
            "Saga replay: before=18 rows, after run=${18 + 11} (24 with in-run times, 13 of them old " +
                "rows bumped by #108 de-dup), after deleting the 24 = ${table.size}; old rows lost: " +
                fixtureTexts.size + " (" + fixtureTexts.sumOf { it.toByteArray().size } + " B)"
        )
    }

    /**
     * Suspects (a), (b), (c) and the D6 migration: every stored form Unlimited and Never expire can
     * take — Settings (Int 0, "-1"), the import/default table (String "0"), unset, an Int duration,
     * a pre-b20be6218 non-numeric text that D6 removes, a By Size flip back to By Count with the
     * size slider left at 1 MB — prunes nothing over 24 captures and the service start.
     */
    @Test
    fun noStoredFormOfUnlimitedOrNeverPrunesAnything() {
        val forms: List<Pair<String, Map<String, Any>>> = listOf(
            "Settings screen" to mapOf("clipboard_limit_type" to "count", "clipboard_history_limit" to 0, "clipboard_history_duration" to "-1"),
            "import/default strings" to mapOf("clipboard_limit_type" to "count", "clipboard_history_limit" to "0", "clipboard_history_duration" to "-1"),
            "all unset" to emptyMap(),
            "duration stored as Int" to mapOf("clipboard_history_duration" to -1),
            "limit as non-numeric text (D6 removes it)" to mapOf("clipboard_history_limit" to "unlimited"),
            "limit as padded text (D6 rewrites it)" to mapOf("clipboard_history_limit" to " 0 "),
            "flip By Size 1 MB then back to By Count" to mapOf(
                "clipboard_limit_type" to "count", "clipboard_history_limit" to 0,
                "clipboard_size_limit_mb" to 1, "clipboard_history_duration" to "-1"),
            "still By Size at 1 MB" to mapOf("clipboard_limit_type" to "size", "clipboard_size_limit_mb" to 1),
        )
        for ((name, stored) in forms) {
            table.clear(); deletions.clear(); settings.values.clear()
            settings.values["clipboard_history_enabled"] = true
            settings.values.putAll(stored)
            seedOldHistory()

            // D6: the String→Int repair the service start runs, then the start's expiry pass.
            ClipboardHistoryLimits.rewriteStoredTextAsInt(settings)
            database.rescueExpiredEntries()
            val limits = ClipboardHistoryLimits.fromSettings(settings)
            runCaptures()

            assertWithMessage("$name: limits read $limits; rows deleted").that(deletions).isEmpty()
            assertWithMessage("$name: rows").that(table).hasSize(29)
            assertWithMessage("$name: listed rows").that(service.clearExpiredAndGetHistory()).hasSize(29)
        }
    }

    /**
     * Suspect (c): rows stamped with a finite expiry under an earlier duration are rescued, not
     * deleted, once the duration is Never expire (the service start's [ClipboardDatabase.rescueExpiredEntries];
     * clearExpiredAndGetHistory skips the expiry delete entirely).
     */
    @Test
    fun rowsStampedUnderAnEarlierFiniteDurationSurviveNeverExpire() {
        storeUnlimitedNeverAsSettingsDoes()
        seedOldHistory(expiry = System.currentTimeMillis() - day)  // already past

        database.rescueExpiredEntries()
        runCaptures()

        assertThat(deletions).isEmpty()
        assertThat(table).hasSize(29)
        assertThat(table.all { it.expiry == Long.MAX_VALUE }).isTrue()
    }

    /** Suspect (d): one pane delete removes exactly the one row with that text, never a neighbour. */
    @Test
    fun aSingleDeleteRemovesOnlyItsRow() {
        storeUnlimitedNeverAsSettingsDoes()
        seedOldHistory()

        service.removeHistoryEntry("Planet")

        assertThat(deletions).containsExactly("content = ?" to "Planet")
        assertThat(table).hasSize(17)
    }
}
