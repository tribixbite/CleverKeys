package tribixbite.cleverkeys.langpack

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Streaming file helpers shared by the pack importers (language packs, voice packs).
 *
 * Both importers take untrusted ZIPs, so both share one invariant: no entry is ever materialised
 * whole, whatever size it claims — peak memory is one [DEFAULT_BUFFER_SIZE] buffer (the
 * v1.1.96/v1.1.97 OOM fix, pinned for the language importer by
 * `LanguagePackImportTest.theImportPathNeverReadsAWholeEntryIntoMemory`). Pure `java.io`.
 */
object PackFiles {

    /**
     * Copy at most [limit] bytes from [input] to [output].
     *
     * @return true when the whole stream fit, false the moment it would exceed [limit] (the
     *   output then holds a truncated prefix, which the caller discards with its temp dir).
     */
    fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Boolean {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) return true
            written += read
            if (written > limit) return false
            output.write(buffer, 0, read)
        }
    }

    /** Lowercase hex sha256 of [file], read in fixed-size chunks. */
    fun sha256OfFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * True when a ZIP entry name is safe to flatten: not absolute, no backslash or NUL, no `.`
     * or `..` component. Benign wrapper folders (`pack/manifest.json`) are allowed; the caller
     * flattens to the last component and refuses duplicates.
     */
    fun isSafeEntryName(name: String): Boolean {
        if (name.startsWith('/') || name.contains('\\') || name.contains('\u0000')) return false
        return name.split('/').none { it == ".." || it == "." }
    }

    /**
     * Read at most [maxChars] characters of UTF-8 text from [file] in chunks (a hostile
     * multi-megabyte notice is never materialised whole), or null when it cannot be read.
     */
    fun readTextCapped(file: File, maxChars: Int): String? = try {
        file.reader(Charsets.UTF_8).use { reader ->
            val out = StringBuilder()
            val buffer = CharArray(DEFAULT_BUFFER_SIZE)
            while (out.length < maxChars) {
                val read = reader.read(buffer, 0, minOf(buffer.size, maxChars - out.length))
                if (read <= 0) break
                out.append(buffer, 0, read)
            }
            out.toString()
        }
    } catch (e: Exception) {
        null
    }
}
