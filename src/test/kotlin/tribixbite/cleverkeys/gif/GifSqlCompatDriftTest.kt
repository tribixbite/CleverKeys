package tribixbite.cleverkeys.gif

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Audit E-1 (2026-09-06, P1) — SQLite syntax floor for minSdk 24.
 *
 * `INSERT ... ON CONFLICT ... DO UPDATE` (upsert) requires SQLite >= 3.24, which Android
 * ships only from API 29; API 24-28 carry 3.9.2-3.22 and fail at PARSE time
 * (`near "ON": syntax error`). `GifDatabase.recordGifUsage` used exactly that syntax, so
 * every GIF tap on those devices threw a SQLiteException out of an unhandled
 * `scope.launch` — an IME process crash. The fix is a portable two-statement upsert
 * (UPDATE, then INSERT OR IGNORE) inside a transaction.
 *
 * This ratchet bans upsert syntax from every on-device SQL string under src/main so the
 * class of bug cannot come back while minSdk < 30. (`INSERT OR REPLACE` / `OR IGNORE`
 * are fine — supported since forever.)
 *
 * RED (2026-09-06, pre-fix): GifDatabase.kt matched `ON CONFLICT(gif_id) DO UPDATE`.
 */
class GifSqlCompatDriftTest {

    @Test
    fun noSqliteUpsertSyntaxUnderSrcMain() {
        val mainKotlin = File("src/main/kotlin")
        check(mainKotlin.isDirectory) {
            "Drift test must run with the project root as CWD."
        }
        val upsert = Regex("""ON\s+CONFLICT\s*\([^)]*\)\s*DO\s+(UPDATE|NOTHING)""", RegexOption.IGNORE_CASE)
        val offenders = mainKotlin.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { upsert.containsMatchIn(it.readText()) }
            .map { it.path }
            .toList()
        assertWithMessage(
            "SQLite upsert syntax (ON CONFLICT ... DO UPDATE/NOTHING) needs SQLite >= 3.24 " +
                "(API 29+); minSdk is 24 (SQLite 3.9.2) where it is a parse-time " +
                "SQLiteException — the audit E-1 IME crash. Use a two-statement upsert " +
                "(UPDATE then INSERT OR IGNORE) instead."
        ).that(offenders).isEmpty()
    }
}
