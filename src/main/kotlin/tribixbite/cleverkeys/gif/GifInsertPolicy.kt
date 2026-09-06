package tribixbite.cleverkeys.gif

/**
 * Pure decision seam for what a GIF-panel tap inserts (#149).
 *
 * The old tap path committed a Giphy URL rebuilt from lowercased search keywords —
 * a guaranteed-dead link (Giphy IDs are case-sensitive, and the "ID" token was often
 * a compound keyword). The pack ships the media offline (the app has no INTERNET
 * permission), so the PRIMARY delivery is the locally-stored animated WebP via
 * InputConnection.commitContent; the URL is only a share artifact for editors that
 * do not accept content, and only when a trustworthy case-preserved ID exists
 * ([Gif.getGiphyId]).
 *
 * Kept free of Android types so the full decision matrix is pure-JVM testable
 * (GifInsertPolicyTest); KeyboardReceiver supplies the runtime facts and executes
 * the chosen action.
 */
object GifInsertPolicy {

    /** MIME type of the locally-stored full animations (webp files under gifs/full/). */
    const val MIME_WEBP = "image/webp"

    /** commitContent requires API 25 (InputConnectionCompat routes the grant). */
    const val MIN_COMMIT_CONTENT_API = 25

    enum class Action {
        /** Insert the local animated WebP via commitContent. Works for every pack. */
        COMMIT_MEDIA,

        /** Insert the case-preserved Giphy URL as text (editor refuses content). */
        COMMIT_URL_TEXT,

        /** No InputConnection or no URL: put the local media on the clipboard. */
        COPY_MEDIA_TO_CLIPBOARD,

        /** No InputConnection but a valid URL exists: copy the URL. */
        COPY_URL_TO_CLIPBOARD,

        /** Nothing usable — surface feedback, insert nothing. */
        NONE,
    }

    /**
     * Decide the tap action.
     *
     * @param apiLevel Build.VERSION.SDK_INT
     * @param icAvailable whether currentInputConnection is non-null
     * @param hasLocalMedia whether the full animated WebP exists on disk
     * @param editorAcceptsWebp whether the editor's contentMimeTypes accept image/webp
     * @param hasUrl whether a case-preserved Giphy URL exists (marked ID — never true
     *   for legacy packs, whose reconstructed URLs are dead)
     */
    fun decide(
        apiLevel: Int,
        icAvailable: Boolean,
        hasLocalMedia: Boolean,
        editorAcceptsWebp: Boolean,
        hasUrl: Boolean,
    ): Action = when {
        apiLevel >= MIN_COMMIT_CONTENT_API && icAvailable && hasLocalMedia && editorAcceptsWebp ->
            Action.COMMIT_MEDIA
        icAvailable && hasUrl -> Action.COMMIT_URL_TEXT
        hasLocalMedia -> Action.COPY_MEDIA_TO_CLIPBOARD
        hasUrl -> Action.COPY_URL_TO_CLIPBOARD
        else -> Action.NONE
    }

    /**
     * Fallback when the chosen COMMIT_MEDIA fails at runtime (editor returned false
     * from commitContent despite advertising support). Same ladder minus the media
     * commit; the clipboard copy still works because it does not involve the editor.
     */
    fun afterFailedMediaCommit(icAvailable: Boolean, hasUrl: Boolean): Action = when {
        icAvailable && hasUrl -> Action.COMMIT_URL_TEXT
        else -> Action.COPY_MEDIA_TO_CLIPBOARD
    }

    /**
     * Pure editor MIME acceptance check (EditorInfo.contentMimeTypes semantics):
     * exact match, a type wildcard ("image/&#42;"), or the full wildcards "&#42;" and
     * "&#42;/&#42;". Case-insensitive per RFC 2045. A null/empty declaration means the
     * editor accepts no content.
     */
    fun editorAcceptsMime(declared: Array<String>?, mime: String): Boolean {
        if (declared == null || declared.isEmpty()) return false
        val wanted = mime.lowercase()
        val wantedType = wanted.substringBefore('/')
        return declared.any { decl ->
            val d = decl.trim().lowercase()
            d == wanted || d == "*" || d == "*/*" || d == "$wantedType/*"
        }
    }
}
