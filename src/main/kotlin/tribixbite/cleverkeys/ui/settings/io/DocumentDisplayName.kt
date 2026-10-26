package tribixbite.cleverkeys.ui.settings.io

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The name a person recognises for a picked or created document, for the import/export
 * result dialogs ("File: cleverkeys-dictionary.json").
 *
 * Those dialogs used to print `uri.lastPathSegment`. For a Storage Access Framework or
 * MediaStore document that segment is the provider's document id, not a file name: the
 * Downloads provider answers `content://com.android.providers.downloads.documents/document/932`,
 * so the dialog read "File: 932". The real name lives in the [OpenableColumns.DISPLAY_NAME]
 * column, which every openable provider must serve (and which also reflects a rename the
 * provider applied on create, e.g. "backup (1).zip").
 *
 * Pure policy (no Android types, so `runPureTests` can load it); the resolver call is
 * [resolveDocumentDisplayName] below.
 */
internal object DocumentNamePolicy {

    /**
     * The label to show, in order of preference:
     * 1. [queriedName], the provider's DISPLAY_NAME, when it is not blank;
     * 2. the file-like tail of the (already percent-decoded) [lastPathSegment] — the text after
     *    the last `/` and, for a document id such as `primary:Download/x.json` or `msf:932`,
     *    after the last `:` — when that tail is not blank;
     * 3. [uriString], the whole URI, so the dialog never shows an empty "File: ".
     */
    fun choose(queriedName: String?, lastPathSegment: String?, uriString: String): String {
        queriedName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val tail = lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.trim()
        if (!tail.isNullOrEmpty()) return tail
        return uriString
    }
}

/**
 * Queries [uri]'s [OpenableColumns.DISPLAY_NAME] through [resolver] and applies
 * [DocumentNamePolicy.choose]. Only `content:` URIs are queried (headless `file:` URIs have
 * no provider). Any provider failure (revoked grant, provider crash, missing column) falls
 * back to the path-derived name rather than failing the dialog. Blocking: call off the main
 * thread, or use [documentDisplayName].
 */
internal fun resolveDocumentDisplayName(resolver: ContentResolver, uri: Uri): String {
    val queried = if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getString(column) else null
            }
        } catch (e: Exception) {
            // SecurityException (grant gone), IllegalArgumentException (unknown column),
            // provider death: the fallback name is still useful.
            null
        }
    } else {
        null
    }
    return DocumentNamePolicy.choose(queried, uri.lastPathSegment, uri.toString())
}

/** [resolveDocumentDisplayName] on [Dispatchers.IO], for the result dialogs' coroutines. */
internal suspend fun Context.documentDisplayName(uri: Uri): String =
    withContext(Dispatchers.IO) { resolveDocumentDisplayName(contentResolver, uri) }
