package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import tribixbite.cleverkeys.prefs.LayoutsPreference
import java.io.File

/**
 * Phases 2 and 3 of the real 1.x → 2.0 over-install test (`tools/upgrade-test/`).
 *
 *  - [afterOverInstall] runs in the 2.0 app right after `pm install -r` replaced a
 *    seeded v1.5.0 install: every seeded item must survive with identical fields,
 *    the DB must be schema v5 equal to a fresh v5, settings must read back. It
 *    then writes a full backup ZIP for the round trip.
 *  - [afterFreshInstallImport] runs after the orchestrator uninstalled the app,
 *    installed 2.0 fresh and copied the ZIP (and the seed dump) back in: it imports
 *    the backup and checks the same items again.
 *
 * NOT part of the default instrumented suite: it needs the seeded state, so it is
 * kept out of `src/androidTest` and copied into a `git worktree` build by
 * `tools/upgrade-test/run-upgrade-test.sh`. Every check is logged as
 * `UPGRADE_CHECK phase=… name=… result=PASS|FAIL|INFO detail=…` and saved to
 * `filesDir/upgrade-test/results_<phase>.json`; the test fails if any check failed.
 */
@RunWith(AndroidJUnit4::class)
class UpgradeInstallVerifyTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workDir = File(context.filesDir, UpgradeFixture.WORK_DIR)

    /** Collects named verdicts so one run reports EVERY item, not just the first failure. */
    private inner class Checks(private val phase: String) {
        val results = JSONArray()
        var failures = 0

        private fun record(name: String, result: String, detail: String) {
            results.put(JSONObject().put("name", name).put("result", result).put("detail", detail))
            Log.i(UpgradeFixture.LOG_TAG, "phase=$phase name=$name result=$result detail=${detail.take(600)}")
        }

        /** [block] returns null on success or a failure description. */
        fun check(name: String, block: () -> String?) {
            val problem = try {
                block()
            } catch (t: Throwable) {
                "threw ${t.javaClass.simpleName}: ${t.message}"
            }
            if (problem == null) record(name, "PASS", "") else {
                failures++
                record(name, "FAIL", problem)
            }
        }

        fun info(name: String, detail: String) = record(name, "INFO", detail)

        fun finish() {
            workDir.mkdirs()
            File(workDir, "${UpgradeFixture.RESULTS_PREFIX}$phase.json")
                .writeText(JSONObject().put("phase", phase).put("failures", failures).put("checks", results).toString(2))
            Log.i(UpgradeFixture.LOG_TAG, "phase=$phase SUMMARY checks=${results.length()} failures=$failures")
            if (failures > 0) {
                val failed = (0 until results.length()).map { results.getJSONObject(it) }
                    .filter { it.getString("result") == "FAIL" }
                    .joinToString("\n") { "${it.getString("name")}: ${it.getString("detail").take(300)}" }
                fail("$failures upgrade check(s) failed in phase $phase:\n$failed")
            }
        }
    }

    private fun prefs(): SharedPreferences = DirectBootAwarePreferences.get_shared_preferences(context)

    private fun ensureConfig(prefs: SharedPreferences) {
        try {
            Config.globalConfig()
        } catch (e: NullPointerException) {
            Config.initGlobalConfig(prefs, context.resources, null, null)
        }
    }

    private fun loadSeed(): JSONObject = JSONObject(File(workDir, UpgradeFixture.SEED_DUMP).readText())

    /** Re-parse a live dump the same way the seed dump was parsed, so numbers compare alike. */
    private fun normalized(o: JSONObject): JSONObject = JSONObject(o.toString())

    private fun rows(arr: JSONArray): List<JSONObject> = (0 until arr.length()).map { arr.getJSONObject(it) }

    private fun valueString(o: JSONObject, key: String): String =
        if (!o.has(key) || o.isNull(key)) "null" else o.get(key).toString()

    /** Column-by-column difference of two rows over [columns]; empty when identical. */
    private fun diffRow(seed: JSONObject, live: JSONObject, columns: Iterable<String>): List<String> =
        columns.mapNotNull { col ->
            val a = valueString(seed, col)
            val b = valueString(live, col)
            if (a == b) null else "$col: 1.x=${a.take(80)} 2.0=${b.take(80)}"
        }

    // ── Checks shared by both phases (2.0 public API read-back of each seeded item) ──

    private fun itemChecks(c: Checks, seed: JSONObject) {
        val db = ClipboardDatabase.getInstance(context)
        val history = db.getActiveClipboardEntries()
        val pinned = db.getPinnedEntriesFull()
        val todos = db.getTodoEntriesFull()
        val large = seed.getJSONObject("media").getJSONObject("large")
        val small = seed.getJSONObject("media").getJSONObject("small")
        val mediaPath = large.getString("path")
        val mediaName = large.getString("name")

        fun historyOf(content: String) = history.filter { it.content == content }

        c.check("history_plain") { if (historyOf(UpgradeFixture.HISTORY_PLAIN).size == 1) null else "count=${historyOf(UpgradeFixture.HISTORY_PLAIN).size}" }
        c.check("history_duplicate_deduped") {
            val n = historyOf(UpgradeFixture.HISTORY_DUP).size
            if (n == 1) null else "expected exactly 1 row, got $n"
        }
        c.check("history_unicode_multiline") { if (historyOf(UpgradeFixture.HISTORY_UNICODE).size == 1) null else "missing or duplicated" }
        c.check("history_large_near_cap") {
            val e = historyOf(UpgradeFixture.LARGE_TEXT)
            val bytes = UpgradeFixture.LARGE_TEXT.toByteArray(Charsets.UTF_8).size
            when {
                e.size != 1 -> "count=${e.size} (expected 1 entry of $bytes UTF-8 bytes)"
                else -> null
            }
        }
        c.check("history_oversize_absent") {
            if (history.none { it.content == UpgradeFixture.OVERSIZE_TEXT }) null else "1 B over the cap is present"
        }
        c.check("history_old_date_2019") {
            val e = historyOf(UpgradeFixture.HISTORY_OLD)
            if (e.size == 1 && e[0].timestamp == UpgradeFixture.HISTORY_OLD_TS) null
            else "entries=${e.map { it.timestamp }}"
        }
        c.check("history_new_dates_recent") {
            val seededAt = seed.getLong("seeded_at")
            val e = historyOf(UpgradeFixture.HISTORY_PLAIN).firstOrNull() ?: return@check "missing"
            if (e.timestamp in (seededAt - 600_000)..seededAt) null else "timestamp=${e.timestamp} seededAt=$seededAt"
        }
        c.check("history_media_image") {
            val e = historyOf(mediaName)
            if (e.size != 1) return@check "count=${e.size}"
            val m = e[0]
            when {
                m.mimeType != UpgradeFixture.MEDIA_MIME -> "mime=${m.mimeType}"
                m.mediaPath != mediaPath -> "path=${m.mediaPath} expected $mediaPath"
                m.thumbnailBlob == null -> "thumbnail missing"
                else -> null
            }
        }
        c.check("history_media_small_image") {
            val e = historyOf(small.getString("name")).singleOrNull() ?: return@check "count=${historyOf(small.getString("name")).size}"
            c.info(
                "history_media_small_thumbnail",
                "1.x stored thumbnail=${small.getBoolean("thumbnail")} (v1.5.0 bug, fixed in 2.0 by c1bf9530); " +
                    "2.0 now has thumbnail=${e.thumbnailBlob != null}"
            )
            if (e.mimeType == UpgradeFixture.MEDIA_MIME && e.mediaPath == small.getString("path")) null
            else "mime=${e.mimeType} path=${e.mediaPath}"
        }
        c.check("media_files_bytes_intact") {
            val want = seed.getJSONObject("media_files")
            val bad = want.keys().asSequence().mapNotNull { path ->
                val f = File(context.filesDir, path)
                when {
                    !f.exists() -> "missing $path"
                    UpgradeDump.sha256(f) != want.getString(path) -> "sha256 changed $path"
                    else -> null
                }
            }.toList()
            if (bad.isEmpty()) null else bad.joinToString()
        }

        fun pinnedOf(content: String) = pinned.filter { it.content == content }
        c.check("pinned_plain_single_tag") {
            val e = pinnedOf(UpgradeFixture.PINNED_PLAIN)
            if (e.size == 1 && e[0].tags == UpgradeFixture.PINNED_PLAIN_TAGS) null else "entries=${e.map { it.tags }}"
        }
        c.check("pinned_copy_multi_unicode_tags") {
            val e = pinnedOf(UpgradeFixture.HISTORY_PLAIN)
            if (e.size == 1 && e[0].tags == UpgradeFixture.PINNED_COPY_TAGS) null else "entries=${e.map { it.tags }}"
        }
        c.check("pinned_old_dates_and_tag") {
            val e = pinnedOf(UpgradeFixture.PINNED_OLD).singleOrNull() ?: return@check "count=${pinnedOf(UpgradeFixture.PINNED_OLD).size}"
            when {
                e.createdTimestamp != UpgradeFixture.PINNED_OLD_CREATED_TS -> "created=${e.createdTimestamp}"
                e.pinnedTimestamp != UpgradeFixture.PINNED_OLD_PINNED_TS -> "pinned=${e.pinnedTimestamp}"
                e.tags != UpgradeFixture.PINNED_OLD_TAGS -> "tags=${e.tags}"
                else -> null
            }
        }
        c.check("pinned_media_image") {
            val e = pinnedOf(mediaName).singleOrNull() ?: return@check "count=${pinnedOf(mediaName).size}"
            when {
                e.mimeType != UpgradeFixture.MEDIA_MIME -> "mime=${e.mimeType}"
                e.mediaPath != mediaPath -> "path=${e.mediaPath}"
                e.thumbnailBlob == null -> "thumbnail missing"
                else -> null
            }
        }
        c.check("pinned_count") { if (pinned.size == 4) null else "count=${pinned.size}: ${pinned.map { it.content.take(25) }}" }

        fun todoOf(content: String) = todos.filter { it.content == content }
        for ((content, status, tags) in listOf(
            Triple(UpgradeFixture.TODO_ACTIVE, UpgradeFixture.STATUS_ACTIVE, UpgradeFixture.TODO_ACTIVE_TAGS),
            Triple(UpgradeFixture.TODO_PLANNED, UpgradeFixture.STATUS_PLANNED, UpgradeFixture.TODO_PLANNED_TAGS),
            Triple(UpgradeFixture.TODO_COMPLETED, UpgradeFixture.STATUS_COMPLETED, emptyList()),
        )) {
            c.check("todo_$status") {
                val e = todoOf(content).singleOrNull() ?: return@check "count=${todoOf(content).size}"
                if (e.status == status && e.tags == tags) null else "status=${e.status} tags=${e.tags}"
            }
        }
        c.check("todo_old_dates_planned") {
            val e = todoOf(UpgradeFixture.TODO_OLD).singleOrNull() ?: return@check "count=${todoOf(UpgradeFixture.TODO_OLD).size}"
            when {
                e.status != UpgradeFixture.STATUS_PLANNED -> "status=${e.status}"
                e.createdTimestamp != UpgradeFixture.TODO_OLD_CREATED_TS -> "created=${e.createdTimestamp}"
                e.addedTimestamp != UpgradeFixture.TODO_OLD_ADDED_TS -> "added=${e.addedTimestamp}"
                else -> null
            }
        }
        c.check("todo_count") { if (todos.size == 4) null else "count=${todos.size}" }
        c.check("no_entry_marked_private") {
            val p = history.count { it.isPrivate } + pinned.count { it.isPrivate } + todos.count { it.isPrivate }
            if (p == 0) null else "$p entries became private"
        }
        c.info("private_entry", "not seeded: v1.5.0 has no private entries (is_private arrived with schema v5, #156)")

        val prefs = prefs()
        c.check("custom_dictionary_words") {
            val words = runBlocking { CustomDictionarySource(prefs, UpgradeFixture.DICT_LANG).getAllWords() }
                .associate { it.word to it.frequency }
            val missing = UpgradeFixture.CUSTOM_WORDS.filter { (w, f) -> words[w] != f }
            if (missing.isEmpty()) null else "missing/changed=$missing have=$words"
        }
        c.check("custom_layout") {
            val layouts = LayoutsPreference.loadFromPreferences(context.resources, prefs)
            val names = layouts.map { it?.name }
            if (UpgradeFixture.CUSTOM_LAYOUT_NAME in names && layouts.size == 2) null else "layouts=$names"
        }
        c.check("short_swipe_mapping") {
            val mgr = ShortSwipeCustomizationManager.getInstance(context)
            runBlocking { mgr.loadMappings() }
            val m = mgr.getAllMappings().filter {
                it.keyCode == UpgradeFixture.SHORT_SWIPE_KEY && it.direction.name == UpgradeFixture.SHORT_SWIPE_DIRECTION
            }
            val e = m.singleOrNull() ?: return@check "mappings=${mgr.getAllMappings()}"
            if (e.displayText == UpgradeFixture.SHORT_SWIPE_DISPLAY && e.actionValue == UpgradeFixture.SHORT_SWIPE_TEXT &&
                e.actionType.name == "TEXT"
            ) null else "mapping=$e"
        }
        c.check("settings_prefs_values") {
            val bad = mutableListOf<String>()
            if (prefs.getString("theme", null) != UpgradeFixture.THEME) bad += "theme=${prefs.all["theme"]}"
            if (prefs.all["character_size"] != UpgradeFixture.CHARACTER_SIZE) bad += "character_size=${prefs.all["character_size"]}"
            if (prefs.all["vibrate_duration"] != UpgradeFixture.VIBRATE_DURATION) bad += "vibrate_duration=${prefs.all["vibrate_duration"]}"
            if (prefs.all["key_opacity"] != UpgradeFixture.KEY_OPACITY) bad += "key_opacity=${prefs.all["key_opacity"]}"
            if (prefs.all["longpress_timeout"] != UpgradeFixture.LONGPRESS_TIMEOUT) bad += "longpress_timeout=${prefs.all["longpress_timeout"]}"
            if (prefs.all["swipe_trail_width"] != UpgradeFixture.SWIPE_TRAIL_WIDTH) bad += "swipe_trail_width=${prefs.all["swipe_trail_width"]}"
            if (bad.isEmpty()) null else bad.joinToString()
        }
        c.check("settings_config_readback") {
            ensureConfig(prefs)
            val cfg = Config.globalConfig()
            cfg.refresh(context.resources, null)
            val bad = mutableListOf<String>()
            if (cfg.themeName != UpgradeFixture.THEME) bad += "themeName=${cfg.themeName}"
            if (cfg.vibrate_duration != UpgradeFixture.VIBRATE_DURATION.toLong()) bad += "vibrate_duration=${cfg.vibrate_duration}"
            if (cfg.longPressTimeout != UpgradeFixture.LONGPRESS_TIMEOUT.toLong()) bad += "longPressTimeout=${cfg.longPressTimeout}"
            if (cfg.swipe_trail_width != UpgradeFixture.SWIPE_TRAIL_WIDTH) bad += "swipe_trail_width=${cfg.swipe_trail_width}"
            if (bad.isEmpty()) null else bad.joinToString()
        }
    }

    /** Semantic schema comparison (columns by name, indexes by table/unique/columns). */
    private fun schemaDiff(a: JSONObject, b: JSONObject): List<String> {
        val out = mutableListOf<String>()
        val ta = a.getJSONObject("tables")
        val tb = b.getJSONObject("tables")
        val tables = (ta.keys().asSequence() + tb.keys().asSequence()).toSortedSet()
        for (t in tables) {
            if (!ta.has(t) || !tb.has(t)) { out += "table $t only in ${if (ta.has(t)) "first" else "second"}"; continue }
            val ca = rows(ta.getJSONArray(t)).associateBy { it.getString("name") }
            val cb = rows(tb.getJSONArray(t)).associateBy { it.getString("name") }
            for (col in (ca.keys + cb.keys).toSortedSet()) {
                val x = ca[col]?.toString()
                val y = cb[col]?.toString()
                if (x != y) out += "$t.$col: $x vs $y"
            }
        }
        val ia = a.getJSONObject("indexes")
        val ib = b.getJSONObject("indexes")
        for (i in (ia.keys().asSequence() + ib.keys().asSequence()).toSortedSet()) {
            val x = if (ia.has(i)) ia.getJSONObject(i).toString() else null
            val y = if (ib.has(i)) ib.getJSONObject(i).toString() else null
            if (x != y) out += "index $i: $x vs $y"
        }
        return out
    }

    private fun columnOrder(s: JSONObject, table: String): List<String> =
        rows(s.getJSONObject("tables").getJSONArray(table)).map { it.getString("name") }

    private fun freshV5Schema(): JSONObject {
        val mem = SQLiteDatabase.create(null)
        try {
            ClipboardDatabase.getInstance(context).onCreate(mem)
            mem.version = 5
            return UpgradeDump.schema(mem)
        } finally {
            mem.close()
        }
    }

    @Test
    fun afterOverInstall() {
        val c = Checks("afterOverInstall")
        val seed = loadSeed()
        val db = ClipboardDatabase.getInstance(context)
        val live = normalized(UpgradeDump.dumpClipboard(db.readableDatabase))
        val upgradedSchema = UpgradeDump.schema(db.readableDatabase)

        c.info(
            "versions",
            "seeded by ${seed.getString("app_version")} (${seed.getInt("app_version_code")}) on API ${seed.getInt("sdk")}; " +
                "now ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on API ${Build.VERSION.SDK_INT}"
        )
        c.check("seed_was_v1x_schema_v4") {
            val v = seed.getJSONObject("schema").getInt("user_version")
            if (seed.getString("app_version").startsWith("1.") && v == 4) null else "seed app=${seed.getString("app_version")} schema=$v"
        }
        c.check("app_is_2x") { if (BuildConfig.VERSION_NAME.startsWith("2.")) null else BuildConfig.VERSION_NAME }
        c.check("schema_user_version_5") { if (upgradedSchema.getInt("user_version") == 5) null else "user_version=${upgradedSchema.getInt("user_version")}" }
        val fresh = freshV5Schema()
        c.check("schema_equals_fresh_v5") {
            val d = schemaDiff(upgradedSchema, fresh)
            if (d.isEmpty()) null else d.joinToString("; ")
        }
        for (t in UpgradeDump.CLIPBOARD_TABLES) {
            val a = columnOrder(upgradedSchema, t)
            val b = columnOrder(fresh, t)
            c.info("column_order_$t", if (a == b) "same as fresh v5" else "upgraded=$a fresh=$b")
        }

        // Row-for-row identity: every 1.x column unchanged, new v5 columns at their defaults.
        for (t in UpgradeDump.CLIPBOARD_TABLES) {
            c.check("rows_identical_$t") {
                val seedRows = rows(seed.getJSONObject("clipboard").getJSONArray(t)).associateBy { it.getLong("id") }
                val liveRows = rows(live.getJSONArray(t)).associateBy { it.getLong("id") }
                val problems = mutableListOf<String>()
                if (seedRows.keys != liveRows.keys) problems += "ids 1.x=${seedRows.keys} 2.0=${liveRows.keys}"
                for ((id, s) in seedRows) {
                    val l = liveRows[id] ?: continue
                    val d = diffRow(s, l, s.keys().asSequence().toList())
                    if (d.isNotEmpty()) problems += "id=$id ${d.joinToString()}"
                    if (valueString(l, "is_private") != "0") problems += "id=$id is_private=${valueString(l, "is_private")}"
                    if (valueString(l, "source_package") != "null") problems += "id=$id source_package=${valueString(l, "source_package")}"
                }
                if (problems.isEmpty()) null else problems.joinToString("; ")
            }
        }

        itemChecks(c, seed)

        c.check("prefs_1x_keys_unchanged") {
            val before = seed.getJSONObject("prefs")
            val after = UpgradeDump.dumpPrefs(prefs())
            // `version` is Config's own preference-schema version; Config.migrate() is
            // expected to raise it on the first 2.0 start, so it is reported, not compared.
            if (before.has("version") && after.has("version")) {
                c.info("prefs_config_version", "${before.getJSONObject("version")} -> ${after.getJSONObject("version")}")
            }
            val changed = before.keys().asSequence().filter { it != "version" }.filter { k ->
                !after.has(k) || after.getJSONObject(k).toString() != before.getJSONObject(k).toString()
            }.map { k -> "$k: ${before.getJSONObject(k)} -> ${if (after.has(k)) after.getJSONObject(k) else "absent"}" }.toList()
            val added = after.keys().asSequence().filter { !before.has(it) }.toList()
            c.info("prefs_keys_added_by_2x", if (added.isEmpty()) "none" else added.joinToString())
            if (changed.isEmpty()) null else changed.joinToString("; ")
        }
        c.check("short_swipe_file_identical") {
            val dp = context.createDeviceProtectedStorageContext()
            val now = File(dp.filesDir, "short_swipe_customizations.json").readText()
            if (now == seed.getString("short_swipe_file")) null else "1.x=${seed.getString("short_swipe_file").take(200)} 2.0=${now.take(200)}"
        }

        // Round-trip leg 1: a full backup from the upgraded install.
        workDir.mkdirs()
        File(workDir, UpgradeFixture.UPGRADED_SCHEMA).writeText(upgradedSchema.toString())
        val zip = File(workDir, UpgradeFixture.BACKUP_ZIP).apply { delete() }
        c.check("full_backup_export") {
            val r = BackupRestoreManager(context).exportFullBackup(Uri.fromFile(zip), prefs())
            c.info(
                "full_backup_export_counts",
                "config=${r.configIncluded} dictionaries=${r.dictionaryCount} clipboard=${r.clipboardEntryCount} " +
                    "media=${r.mediaFileCount} bytes=${r.totalBytes} privateSkipped=${r.privateSkipped}"
            )
            if (r.success && zip.length() > 0) null else "success=${r.success} error=${r.errorMessage} size=${zip.length()}"
        }
        c.finish()
    }

    @Test
    fun afterFreshInstallImport() {
        val c = Checks("afterFreshInstallImport")
        val seed = loadSeed()
        val db = ClipboardDatabase.getInstance(context)

        c.check("fresh_install_is_empty") {
            val n = UpgradeDump.CLIPBOARD_TABLES.sumOf { t ->
                db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $t", null).use { it.moveToFirst(); it.getInt(0) }
            }
            if (n == 0) null else "$n rows before import"
        }
        val freshFile = UpgradeDump.schema(db.readableDatabase)
        c.check("fresh_install_schema_v5") { if (freshFile.getInt("user_version") == 5) null else "user_version=${freshFile.getInt("user_version")}" }
        c.check("fresh_install_schema_equals_upgraded") {
            val upgraded = JSONObject(File(workDir, UpgradeFixture.UPGRADED_SCHEMA).readText())
            val d = schemaDiff(upgraded, freshFile)
            if (d.isEmpty()) null else d.joinToString("; ")
        }

        val zip = File(workDir, UpgradeFixture.BACKUP_ZIP)
        c.check("full_backup_import") {
            if (!zip.exists()) return@check "backup.zip was not copied in"
            val r = BackupRestoreManager(context).importFullBackup(Uri.fromFile(zip), prefs())
            c.info(
                "full_backup_import_counts",
                "config=${r.configImported} keys=${r.configKeysApplied} words=${r.customWordsImported} " +
                    "clipboard=${r.clipboardEntriesImported} skipped=${r.clipboardEntriesSkipped} " +
                    "media=${r.mediaFilesRestored} from=${r.sourceAppVersion}"
            )
            if (r.success) null else "error=${r.errorMessage} reason=${r.errorReason}"
        }

        // Rows by content: ids and expiry are reassigned on import; thumbnails are regenerated.
        val live = normalized(UpgradeDump.dumpClipboard(db.readableDatabase))
        val ignored = setOf("id", "expiry_timestamp", "thumbnail_blob", "is_private", "source_package")
        for (t in UpgradeDump.CLIPBOARD_TABLES) {
            c.check("rows_round_trip_$t") {
                val seedRows = rows(seed.getJSONObject("clipboard").getJSONArray(t)).associateBy { it.getString("content") }
                val liveRows = rows(live.getJSONArray(t)).associateBy { it.getString("content") }
                val problems = mutableListOf<String>()
                val missing = seedRows.keys - liveRows.keys
                val extra = liveRows.keys - seedRows.keys
                if (missing.isNotEmpty()) problems += "missing=${missing.map { it.take(30) }}"
                if (extra.isNotEmpty()) problems += "extra=${extra.map { it.take(30) }}"
                for ((content, s) in seedRows) {
                    val l = liveRows[content] ?: continue
                    val d = diffRow(s, l, s.keys().asSequence().filter { it !in ignored }.toList())
                    if (d.isNotEmpty()) problems += "${content.take(30)}: ${d.joinToString()}"
                    if (!s.isNull("thumbnail_blob") && l.isNull("thumbnail_blob")) problems += "${content.take(30)}: thumbnail lost"
                }
                if (problems.isEmpty()) null else problems.joinToString("; ")
            }
        }
        itemChecks(c, seed)
        c.finish()
    }
}
