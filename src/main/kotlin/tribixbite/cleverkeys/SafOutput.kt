package tribixbite.cleverkeys

import java.io.FileNotFoundException
import java.io.OutputStream

/**
 * Opens an export destination for a whole-file write (review 2026-11-14, exporters' lows).
 *
 * Two defects shared by every exporter before this:
 * - `ContentResolver.openOutputStream(uri)` opens in mode `"w"`, which some document providers
 *   (Android 10+ external storage among them) do NOT truncate. Re-exporting over an existing, longer
 *   file left its tail after the new bytes: a corrupt JSON or ZIP that the import then rejected.
 *   Mode `"wt"` truncates; a provider that refuses it falls back to `"w"`.
 * - `openOutputStream` returns null when the provider cannot open the document, and callers wrote
 *   `openOutputStream(uri)?.use { … }` and then reported success for a file that was never written.
 *   A null stream is now a [ClassifiedIoException] ([IoFailureReason.FILE_NOT_FOUND]).
 *
 * [open] is `ContentResolver.openOutputStream(uri, mode)` in production; passing it as a function
 * keeps this pure-JVM testable.
 */
object SafOutput {

    /** Truncate-then-write; supported by the framework's file-backed providers. */
    const val MODE_TRUNCATE = "wt"

    /** Plain write, for providers that reject [MODE_TRUNCATE]. */
    const val MODE_WRITE = "w"

    /**
     * Open [describe] for writing from offset 0 with any previous content removed where the provider
     * allows it. Never returns null: an unopenable destination throws.
     */
    fun open(describe: String, open: (mode: String) -> OutputStream?): OutputStream {
        val truncating = try {
            open(MODE_TRUNCATE)
        } catch (e: IllegalArgumentException) {
            null // the provider does not parse "wt"
        } catch (e: UnsupportedOperationException) {
            null
        } catch (e: FileNotFoundException) {
            // Some providers report an unsupported mode as FileNotFoundException. A destination
            // that is really missing fails the "w" attempt below too, and that error is thrown.
            null
        }
        return truncating
            ?: open(MODE_WRITE)
            ?: throw ClassifiedIoException(
                IoFailureReason.FILE_NOT_FOUND,
                "The provider returned no output stream for $describe",
            )
    }
}
