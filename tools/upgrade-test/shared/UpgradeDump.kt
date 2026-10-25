package tribixbite.cleverkeys

import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Read-only snapshots used by the real over-install test: clipboard rows, the
 * clipboard schema, a SharedPreferences file and arbitrary files.
 *
 * Copied VERBATIM into the v1.5.0 seeder tree and the 2.0 verifier tree, so it
 * uses Android/JSON APIs only. Nothing here writes to the app's data: seeding
 * goes through the 1.x app's own APIs, and these functions only read.
 */
object UpgradeDump {
    val CLIPBOARD_TABLES = listOf("clipboard_entries", "pinned_entries", "todo_entries")

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256(file: File): String = sha256(file.readBytes())

    /** One JSON value per column; a BLOB becomes {blob_sha256, blob_len} so dumps stay small. */
    private fun cell(c: Cursor, i: Int): Any = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).let { b ->
            JSONObject().put("blob_sha256", sha256(b)).put("blob_len", b.size)
        }
        else -> c.getString(i)
    }

    /** Every row of [table] (all columns), ordered by id. */
    fun dumpTable(db: SQLiteDatabase, table: String): JSONArray {
        val rows = JSONArray()
        db.rawQuery("SELECT * FROM $table ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val row = JSONObject()
                for (i in 0 until c.columnCount) row.put(c.getColumnName(i), cell(c, i))
                rows.put(row)
            }
        }
        return rows
    }

    fun dumpClipboard(db: SQLiteDatabase): JSONObject =
        JSONObject().also { o -> CLIPBOARD_TABLES.forEach { o.put(it, dumpTable(db, it)) } }

    /**
     * Semantic schema: user_version, per-table PRAGMA table_info (name/type/notnull/
     * default/pk) and per-index (table, unique, ordered columns), plus the raw
     * sqlite_master SQL for reference. Android-internal tables are excluded.
     */
    fun schema(db: SQLiteDatabase): JSONObject {
        val tables = JSONObject()
        val indexes = JSONObject()
        val rawSql = JSONObject()
        db.rawQuery(
            "SELECT type, name, tbl_name, sql FROM sqlite_master " +
                "WHERE name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY type, name",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val type = c.getString(0)
                val name = c.getString(1)
                rawSql.put("$type:$name", if (c.isNull(3)) JSONObject.NULL else c.getString(3))
                if (type == "table") tables.put(name, JSONArray())
                if (type == "index") indexes.put(name, JSONObject().put("table", c.getString(2)))
            }
        }
        for (t in tables.keys().asSequence().toList()) {
            val cols = JSONArray()
            db.rawQuery("PRAGMA table_info($t)", null).use { c ->
                while (c.moveToNext()) {
                    cols.put(
                        JSONObject()
                            .put("name", c.getString(c.getColumnIndexOrThrow("name")))
                            .put("type", c.getString(c.getColumnIndexOrThrow("type")))
                            .put("notnull", c.getInt(c.getColumnIndexOrThrow("notnull")))
                            .put("dflt_value", c.getString(c.getColumnIndexOrThrow("dflt_value")) ?: JSONObject.NULL)
                            .put("pk", c.getInt(c.getColumnIndexOrThrow("pk")))
                    )
                }
            }
            tables.put(t, cols)
            db.rawQuery("PRAGMA index_list($t)", null).use { c ->
                while (c.moveToNext()) {
                    val idx = c.getString(c.getColumnIndexOrThrow("name"))
                    if (!indexes.has(idx)) continue // auto-indexes are not in sqlite_master
                    indexes.getJSONObject(idx).put("unique", c.getInt(c.getColumnIndexOrThrow("unique")))
                }
            }
        }
        for (idx in indexes.keys().asSequence().toList()) {
            val cols = JSONArray()
            db.rawQuery("PRAGMA index_xinfo($idx)", null).use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(c.getColumnIndexOrThrow("key")) == 0) continue
                    cols.put(
                        (c.getString(c.getColumnIndexOrThrow("name")) ?: "<expr>") +
                            if (c.getInt(c.getColumnIndexOrThrow("desc")) == 1) " DESC" else ""
                    )
                }
            }
            indexes.getJSONObject(idx).put("columns", cols)
        }
        return JSONObject()
            .put("user_version", db.version)
            .put("tables", tables)
            .put("indexes", indexes)
            .put("raw_sql", rawSql)
    }

    /** Every key of [prefs] with its stored type, so a type change shows up too. */
    fun dumpPrefs(prefs: SharedPreferences): JSONObject {
        val out = JSONObject()
        for ((k, v) in prefs.all.toSortedMap()) {
            val (type, value) = when (v) {
                null -> "null" to JSONObject.NULL
                is Set<*> -> "set" to JSONArray(v.map { it.toString() }.sorted())
                is Float -> "float" to v.toDouble()
                else -> v.javaClass.simpleName.lowercase() to v
            }
            out.put(k, JSONObject().put("type", type).put("value", value))
        }
        return out
    }
}
