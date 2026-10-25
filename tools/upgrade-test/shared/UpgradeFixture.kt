package tribixbite.cleverkeys

/**
 * The data the real 1.x → 2.0 over-install test seeds and then checks
 * (`tools/upgrade-test/`, recipe in `.claude/skills/ew-cli-testing.md`).
 *
 * This file is copied VERBATIM into two different source trees — the v1.5.0
 * worktree's androidTest (seeder) and the 2.0 worktree's androidTest
 * (verifier) — so it must compile against both: plain Kotlin constants only,
 * no references to app classes.
 */
object UpgradeFixture {
    /** Directory under the app's credential-encrypted filesDir that carries the
     *  seed dump, the upgraded schema and the backup between phases. */
    const val WORK_DIR = "upgrade-test"
    const val SEED_DUMP = "seed_dump.json"
    const val UPGRADED_SCHEMA = "upgraded_schema.json"
    const val BACKUP_ZIP = "backup.zip"
    const val RESULTS_PREFIX = "results_"

    /** Log tag every phase writes its per-item verdicts under (`UPGRADE_CHECK`). */
    const val LOG_TAG = "UPGRADE_CHECK"

    // ── Clipboard history (added through the 1.x ClipboardHistoryService.addClip path) ──
    const val HISTORY_PLAIN = "upgrade-history-plain"
    /** Added twice; 1.x dedupes it to ONE row (the second add moves it to the top). */
    const val HISTORY_DUP = "upgrade-history-dup"
    const val HISTORY_UNICODE = "Ünïcödé 😀 line one\nline two — 中文 العربية"

    /** 1.x per-item cap is `clipboard_max_item_size_kb` (default 512 KB, UTF-8 bytes). */
    const val SIZE_CAP_BYTES = 512 * 1024
    /** The large entry is this many bytes UNDER the cap (near-cap, still accepted). */
    const val LARGE_MARGIN_BYTES = 100

    /** Builds a deterministic UTF-8 string of exactly [bytes] bytes (multibyte chars included). */
    fun largeText(bytes: Int): String {
        val head = "upgrade-large-entry ✓ " // '✓' is 3 UTF-8 bytes
        val sb = StringBuilder(head)
        var size = head.toByteArray(Charsets.UTF_8).size
        var i = 0
        while (size < bytes) {
            val c = ('a' + (i % 26))
            sb.append(c)
            size += 1
            i++
        }
        return sb.toString()
    }

    val LARGE_TEXT: String by lazy { largeText(SIZE_CAP_BYTES - LARGE_MARGIN_BYTES) }
    /** One byte OVER the cap: 1.x must refuse it, so it must not exist anywhere. */
    val OVERSIZE_TEXT: String by lazy { largeText(SIZE_CAP_BYTES + 1) }

    // ── Old-dated entries (seeded through the 1.x importFromJSON restore path) ──
    const val HISTORY_OLD = "upgrade-history-old-2019"
    const val HISTORY_OLD_TS = 1_546_300_800_000L // 2019-01-01T00:00:00Z
    const val PINNED_OLD = "upgrade-pinned-old-2020"
    const val PINNED_OLD_CREATED_TS = 1_580_601_600_000L // 2020-02-02T00:00:00Z
    const val PINNED_OLD_PINNED_TS = 1_583_020_800_000L // 2020-03-01T00:00:00Z
    const val TODO_OLD = "upgrade-todo-old-2021"
    const val TODO_OLD_CREATED_TS = 1_612_137_600_000L // 2021-02-01T00:00:00Z
    const val TODO_OLD_ADDED_TS = 1_614_556_800_000L // 2021-03-01T00:00:00Z

    // ── Pinned (1.x pinEntry + setPinnedEntryTags) ──
    const val PINNED_PLAIN = "upgrade-pinned-plain"
    val PINNED_PLAIN_TAGS = listOf("work")
    /** HISTORY_PLAIN is pinned too: COPY semantics put the same text in both tables. */
    val PINNED_COPY_TAGS = listOf("multi", "tags", "日本語", "émoji🏷️")
    val PINNED_OLD_TAGS = listOf("old")

    // ── To-dos (1.x addTodoEntry + setTodoEntryStatus + setTodoEntryTags) ──
    const val TODO_ACTIVE = "upgrade-todo-active"
    const val TODO_PLANNED = "upgrade-todo-planned"
    const val TODO_COMPLETED = "upgrade-todo-completed"
    val TODO_ACTIVE_TAGS = listOf("ß-tag")
    val TODO_PLANNED_TAGS = listOf("a", "b")
    const val STATUS_ACTIVE = "active"
    const val STATUS_PLANNED = "planned"
    const val STATUS_COMPLETED = "completed"

    // ── Media (1.x ClipboardMediaManager.saveMedia + addMediaClipboardEntry; the large one is also pinned) ──
    const val MEDIA_MIME = "image/png"
    /** 200×150: 1.x stores a WebP thumbnail, which must survive byte-identical. */
    const val MEDIA_LARGE_NAME = "upgrade-image-large.png"
    const val MEDIA_LARGE_WIDTH = 200
    const val MEDIA_LARGE_HEIGHT = 150
    /**
     * 64×48, i.e. already inside the 80 px thumbnail box: v1.5.0's generateThumbnail
     * recycled the bitmap it was about to compress (fixed in 2.0 by c1bf9530), so 1.x
     * stored this row with thumbnail_blob NULL. Seeded to see how 2.0 treats such rows.
     */
    const val MEDIA_SMALL_NAME = "upgrade-image-small.png"
    const val MEDIA_SMALL_WIDTH = 64
    const val MEDIA_SMALL_HEIGHT = 48

    // ── Custom dictionary words (1.x CustomDictionarySource, language "en") ──
    const val DICT_LANG = "en"
    val CUSTOM_WORDS = linkedMapOf("upgradezyx" to 150, "naïveté" to 200, "UpgradeCase" to 255)

    // ── Custom layout (1.x LayoutsPreference) ──
    const val CUSTOM_LAYOUT_NAME = "Upgrade Test Layout"
    const val CUSTOM_LAYOUT_XML =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<keyboard name=\"$CUSTOM_LAYOUT_NAME\" script=\"latin\">\n" +
            "  <row>\n" +
            "    <key c=\"q\" ne=\"1\"/><key c=\"w\" ne=\"2\"/><key c=\"e\" ne=\"3\"/>\n" +
            "    <key c=\"r\"/><key c=\"t\"/><key c=\"y\"/>\n" +
            "  </row>\n" +
            "  <row>\n" +
            "    <key c=\"a\"/><key c=\"s\"/><key c=\"d\"/>\n" +
            "    <key c=\"f\"/><key c=\"g\"/><key c=\"h\"/>\n" +
            "  </row>\n" +
            "  <row>\n" +
            "    <key width=\"1.5\" c=\"shift\"/><key c=\"z\"/><key c=\"x\"/>\n" +
            "    <key c=\"c\"/><key width=\"1.5\" c=\"backspace\"/>\n" +
            "  </row>\n" +
            "</keyboard>\n"
    const val NAMED_LAYOUT = "latn_qwerty_us"

    // ── Short-swipe mapping (1.x ShortSwipeCustomizationManager.setMapping) ──
    const val SHORT_SWIPE_KEY = "a"
    const val SHORT_SWIPE_DIRECTION = "NE"
    const val SHORT_SWIPE_DISPLAY = "@@"
    const val SHORT_SWIPE_TEXT = "upgrade-mapping-text"

    // ── Non-default settings (written with the types the 1.x settings screen writes) ──
    const val THEME = "everforestlight"
    const val CHARACTER_SIZE = 1.35f
    const val VIBRATE_DURATION = 42
    const val KEY_OPACITY = 73
    const val LONGPRESS_TIMEOUT = 450
    const val SWIPE_TRAIL_WIDTH = 9.5f
}
