package tribixbite.cleverkeys.stt

import org.json.JSONArray
import org.json.JSONObject
import tribixbite.cleverkeys.langpack.LanguagePackSwap

/**
 * A voice pack's `manifest.json` (format 1), parsed and validated. Pure: org.json only, so the
 * whole refusal matrix runs in `runPureTests` (`VoicePackManifestTest`).
 *
 * A pack is untrusted input written by whoever built the ZIP. Every field that becomes a path,
 * a size or an allocation is validated here, BEFORE the importer acts on it; text fields shown
 * in Settings are trimmed and capped. See docs/specs/voice-input.md "Voice pack format".
 */
data class VoicePackManifest(
    /** Install directory name; a [LanguagePackSwap.VALID_PACK_CODE] shape. */
    val id: String,
    val name: String,
    val version: Int,
    val engineId: String,
    val engineVersion: Int,
    /** BCP-47-ish language codes the model transcribes (lower case, e.g. `en`). */
    val languages: List<String>,
    val sampleRate: Int,
    val minAppVersionCode: Int,
    val files: List<VoicePackFile>,
    val license: String?,
    val attribution: String?,
    val source: String?,
) {
    /** Sum of the declared file sizes (what the installed pack occupies, minus notices). */
    val totalBytes: Long get() = files.sumOf { it.size }

    companion object {
        const val KIND = "voice"
        const val FORMAT = 1
        const val MANIFEST_FILE = "manifest.json"
        const val NOTICE_FILE = "NOTICE.txt"
        const val LICENSE_FILE = "LICENSE"

        /** The only capture rate the keyboard records at. */
        const val SAMPLE_RATE = 16_000

        const val MAX_FILES = 32
        const val MAX_FILE_BYTES = 128L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
        const val MAX_LANGUAGES = 16
        const val MAX_TEXT_FIELD_CHARS = 2_000
        const val MAX_NAME_CHARS = 120

        /** A flat member name: no separators, no leading dot, no reserved names. */
        private val FILE_NAME = Regex("^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val ENGINE_ID = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
        private val LANGUAGE = Regex("^[a-z]{2,3}(?:-[a-z0-9]{2,8})?$")

        /** Members that are never part of `files` (the importer handles them itself). */
        val RESERVED_MEMBERS = setOf(MANIFEST_FILE, NOTICE_FILE, LICENSE_FILE)

        /**
         * Parse [json] and validate it for an app at [appVersionCode].
         *
         * Engine support (is [engineId] registered, is [engineVersion] in range) is NOT decided
         * here — that is [SpeechEngineRegistry]'s job, so this class stays engine-neutral.
         */
        fun parse(json: String, appVersionCode: Int): Result {
            val obj = try {
                JSONObject(json)
            } catch (e: Exception) {
                return Result.Invalid(VoicePackImportFailure.InvalidManifest("not JSON: ${e.message}"))
            }
            val kind = obj.opt("kind") as? String
            if (kind != KIND) return Result.Invalid(VoicePackImportFailure.NotAVoicePack)

            val format = obj.opt("format") as? Int
                ?: return Result.Invalid(VoicePackImportFailure.InvalidManifest("format missing"))
            // A higher format is a pack for a newer app; a lower/zero one never existed.
            if (format > FORMAT) return Result.Invalid(VoicePackImportFailure.NeedsNewerApp("format $format"))
            if (format != FORMAT) return Result.Invalid(VoicePackImportFailure.InvalidManifest("format $format"))

            val id = (obj.opt("id") as? String).orEmpty()
            if (!LanguagePackSwap.VALID_PACK_CODE.matches(id)) {
                return Result.Invalid(VoicePackImportFailure.InvalidId(id.take(64)))
            }
            val name = text(obj, "name", MAX_NAME_CHARS)
                ?: return Result.Invalid(VoicePackImportFailure.InvalidManifest("name missing"))
            val version = (obj.opt("version") as? Int)?.takeIf { it >= 1 } ?: 1

            val engine = obj.optJSONObject("engine")
                ?: return Result.Invalid(VoicePackImportFailure.InvalidManifest("engine missing"))
            val engineId = (engine.opt("id") as? String).orEmpty()
            if (!ENGINE_ID.matches(engineId)) {
                return Result.Invalid(VoicePackImportFailure.InvalidManifest("engine.id \"${engineId.take(64)}\""))
            }
            val engineVersion = (engine.opt("version") as? Int)?.takeIf { it >= 1 }
                ?: return Result.Invalid(VoicePackImportFailure.InvalidManifest("engine.version"))

            val languages = (obj.opt("languages") as? JSONArray)?.let { arr ->
                (0 until arr.length()).map { arr.opt(it) as? String ?: "" }
            }.orEmpty()
            if (languages.isEmpty() || languages.size > MAX_LANGUAGES ||
                languages.any { !LANGUAGE.matches(it) } || languages.toSet().size != languages.size
            ) {
                return Result.Invalid(VoicePackImportFailure.InvalidManifest("languages"))
            }

            val sampleRate = obj.opt("sampleRate") as? Int
                ?: return Result.Invalid(VoicePackImportFailure.InvalidManifest("sampleRate missing"))
            if (sampleRate != SAMPLE_RATE) return Result.Invalid(VoicePackImportFailure.UnsupportedSampleRate(sampleRate))

            val minApp = (obj.opt("minAppVersionCode") as? Int) ?: 0
            if (minApp < 0) return Result.Invalid(VoicePackImportFailure.InvalidManifest("minAppVersionCode"))
            if (minApp > appVersionCode) {
                return Result.Invalid(VoicePackImportFailure.NeedsNewerApp("minAppVersionCode $minApp > $appVersionCode"))
            }

            val files = parseFiles(obj.opt("files")) ?: return Result.Invalid(
                VoicePackImportFailure.InvalidManifest("files")
            )

            return Result.Valid(
                VoicePackManifest(
                    id = id,
                    name = name,
                    version = version,
                    engineId = engineId,
                    engineVersion = engineVersion,
                    languages = languages,
                    sampleRate = sampleRate,
                    minAppVersionCode = minApp,
                    files = files,
                    license = text(obj, "license", MAX_TEXT_FIELD_CHARS),
                    attribution = text(obj, "attribution", MAX_TEXT_FIELD_CHARS),
                    source = text(obj, "source", MAX_TEXT_FIELD_CHARS),
                )
            )
        }

        /** The `files` array, or null when any entry is malformed or the totals break a cap. */
        private fun parseFiles(raw: Any?): List<VoicePackFile>? {
            val arr = raw as? JSONArray ?: return null
            if (arr.length() == 0 || arr.length() > MAX_FILES) return null
            val out = ArrayList<VoicePackFile>(arr.length())
            val seen = HashSet<String>()
            var total = 0L
            for (i in 0 until arr.length()) {
                val f = arr.opt(i) as? JSONObject ?: return null
                val path = f.opt("path") as? String ?: return null
                val sha = f.opt("sha256") as? String ?: return null
                // org.json returns Int or Long depending on magnitude.
                val size = (f.opt("size") as? Number)?.toLong() ?: return null
                if (!FILE_NAME.matches(path) || path in RESERVED_MEMBERS || !seen.add(path)) return null
                if (!SHA256.matches(sha)) return null
                if (size <= 0 || size > MAX_FILE_BYTES) return null
                total += size
                if (total > MAX_TOTAL_BYTES) return null
                out += VoicePackFile(path, sha, size)
            }
            return out
        }

        /**
         * A string field, trimmed and capped, or null when absent, blank or not a JSON string
         * (never `optString`, which turns a number into "42" and JSON null into "null").
         */
        private fun text(obj: JSONObject, key: String, cap: Int): String? =
            (obj.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(cap)
    }

    /** Outcome of [parse]. */
    sealed interface Result {
        data class Valid(val manifest: VoicePackManifest) : Result
        data class Invalid(val failure: VoicePackImportFailure) : Result
    }
}

/** One declared model file. [sha256] is lower-case hex; [size] in bytes. */
data class VoicePackFile(val path: String, val sha256: String, val size: Long)
