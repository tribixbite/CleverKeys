package tribixbite.cleverkeys.swipe.ctc

import java.io.File
import java.security.MessageDigest

/**
 * The pinned-hash gate for a CTC encoder delivered by an imported language pack.
 *
 * ## Why this exists
 *
 * Until 2026-09-10 the six per-script encoders (`{ru,el,uk,bg,mk,he}_synth_v3_ch80_fp16w.onnx`,
 * 589,406 B each) were APK assets — 3.1 MB of compressed payload that only ever ran for a user
 * who had *also* imported that language's pack, because all six languages are
 * [CtcLanguageSupport.LexiconSource.CKDT_LANGPACK] and cannot build a trie without one. The
 * model therefore travels in the pack, and the assets are gone.
 *
 * ## The rule that keeps this at ZERO new attack surface
 *
 * A pack is a file the USER chose; its bytes are not trusted. An ONNX graph is parsed by a large
 * native library, so "the pack supplies the model" would ordinarily mean "ORT parses
 * attacker-controlled bytes" — a real, new attack surface for a change whose entire purpose is
 * to save disk. It does not, because of this:
 *
 * > A pack-provided model is handed to ORT **only** when its sha256 equals the hash this app
 * > pins for that language ([CtcScriptSupport.expectedModelSha256]). A pin is byte-identity
 * > with the artifact the APK used to ship, so ORT parses exactly the bytes it parsed before
 * > and nothing else. Every other outcome — no pin, no file, wrong hash, over the size cap —
 * > is reported as model-ABSENT, which the existing gates already handle by falling through to
 * > the geometric engine.
 *
 * Two consequences worth stating plainly, because they are the honest limits of the design:
 *
 *  - This is **not** a signature scheme and cannot bless a model the app has never seen. A new
 *    script model still ships as an app change (a new pin) alongside the pack. That is
 *    deliberate: the point is byte-identity with a reviewed artifact, not extensibility.
 *  - The hash is computed over the bytes that are then handed to ORT — the same array, never a
 *    re-read — so there is no time-of-check/time-of-use window for a pack file swapped between
 *    the check and the load.
 *
 * Pure `java.io`/`java.security` (no `android.*`) so `runPureTests` covers the gate itself
 * rather than a mock of it.
 */
object CtcPackModel {

    /** The pack member holding the encoder. One name, shared by the importer and the loader. */
    const val PACK_MODEL_FILE = "model.onnx"

    /**
     * Largest pack model this app will read, in bytes.
     *
     * The cap is enforced on the file's LENGTH, before a byte is read. It has to be: the hash
     * gate can only reject a model after the bytes exist in memory, so without a length check a
     * pack naming a multi-gigabyte `model.onnx` would OOM the decode thread on its way to being
     * rejected. 8 MB clears the 589,406-byte generation-4 graphs by more than an order of
     * magnitude while staying far below anything that could pressure the heap.
     */
    const val MAX_PACK_MODEL_BYTES: Long = 8L * 1024 * 1024

    /** Where an installed pack's model lives, relative to the app's files dir. */
    fun packModelRelativePath(code: String): String = "langpacks/$code/$PACK_MODEL_FILE"

    /**
     * The file an installed pack's model for [language] would occupy under [filesDir] — the
     * SAME path `LanguagePackManager` installs to — or null when [language] pins no model and
     * therefore may not be served one.
     *
     * Returns the path whether or not it exists; existence is [verifiedPackModel]'s business.
     */
    fun packModelFile(filesDir: File, language: String?): File? {
        if (CtcScriptSupport.expectedModelSha256(language) == null) return null
        val code = CtcLanguageSupport.normalize(language)
        return File(filesDir, packModelRelativePath(code))
    }

    /**
     * [language]'s pack-delivered encoder bytes, verified against this app's pin, or null.
     *
     * THE resolution seam: `CtcEngineAdapter` calls exactly this and loads the returned bytes
     * directly, so the bytes hashed are the bytes ORT parses. Null means "no model for this
     * language" for every reason — unpinned language, no pack, no `model.onnx` in the pack,
     * oversize, or wrong hash — and the caller's response to all of them is identical: let the
     * load fail and fall through to the geometric engine.
     */
    fun verifiedPackModel(filesDir: File, language: String?): ByteArray? =
        verifiedBytes(packModelFile(filesDir, language), CtcScriptSupport.expectedModelSha256(language))

    /** Lowercase hex sha256 of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * The bytes of [file] if — and only if — it is a readable regular file within
     * [MAX_PACK_MODEL_BYTES] whose sha256 equals [expectedSha256].
     *
     * @param file the installed pack's `model.onnx`, or null when the pack has none.
     * @param expectedSha256 this language's pinned hash, or null when the language pins none
     *   (every Latin language: it decodes against the APK's own Latin encoder, and an imported
     *   pack must never be able to displace that).
     * @return the verified bytes, or null for **every** failure mode — absent, unreadable,
     *   oversize, unpinned or mismatched are deliberately indistinguishable to the caller,
     *   because the caller's response to all of them is the same: treat the model as absent.
     */
    fun verifiedBytes(file: File?, expectedSha256: String?): ByteArray? {
        if (expectedSha256 == null || file == null) return null
        if (!file.isFile || !file.canRead()) return null
        // Length first — see MAX_PACK_MODEL_BYTES. A 0-length or truncated file also fails the
        // hash, but failing it here avoids the read entirely.
        val length = file.length()
        if (length <= 0L || length > MAX_PACK_MODEL_BYTES) return null
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            return null
        }
        // Constant-time comparison is pointless here (the expected value is a public constant
        // compiled into the app), but exact equality is not: one differing nibble must refuse.
        return if (sha256Hex(bytes).equals(expectedSha256, ignoreCase = true)) bytes else null
    }

    /**
     * A cheap identity for whatever is at [file] right now — `absent` when there is nothing.
     *
     * The load path records this alongside a failed load so that re-importing a pack can
     * un-latch a language whose model previously failed: without it, a user who upgrades from a
     * model-less pack keeps the geometric fallback until the IME process restarts, with nothing
     * on screen to explain why. Length+mtime is the same fingerprint basis
     * [CtcImportedPackSupport.packFingerprint] already uses for the lexicon memo, so the model
     * and the trie invalidate on the same event.
     */
    fun sourceFingerprint(file: File?): String {
        if (file == null || !file.isFile) return "absent"
        return CtcImportedPackSupport.packFingerprint(file.length(), file.lastModified())
    }
}
