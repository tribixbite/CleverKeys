package tribixbite.cleverkeys.stt

import tribixbite.cleverkeys.IoFailureReason
import tribixbite.cleverkeys.langpack.LanguagePackSwap
import tribixbite.cleverkeys.langpack.PackFileOps
import tribixbite.cleverkeys.langpack.PackFiles
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Imports, lists and removes voice packs under `files/voicepacks/<id>/`
 * (docs/specs/voice-input.md "Voice pack format").
 *
 * The same safety model as the language-pack importer, sharing its pieces rather than copying
 * them: [PackFiles] for streamed bounded extraction and hashing, [LanguagePackSwap] for the
 * crash-safe install/replace/remove and its recovery (with only `manifest.json` as the
 * completeness marker — it is copied into staging last). Specific to voice packs:
 *  - the manifest DECLARES every model file with size and sha256; each must be present and
 *    match exactly (a corrupt or truncated download is refused, never installed);
 *  - the engine is resolved through [SpeechEngineRegistry] AT IMPORT, so a pack for an engine
 *    this build does not have is refused with "needs a newer CleverKeys" instead of installing
 *    files nothing can run;
 *  - caps sized for speech models (128 MiB a file, 300 MiB extracted) and a free-space check
 *    before staging.
 *
 * Pure JVM (no Context, no android.util.Log): the settings screen and the voice controller wrap
 * it with real directories; `VoicePackManagerTest` drives it with temp dirs and a
 * fault-injecting [PackFileOps]. Log lines are returned through [log].
 */
class VoicePackManager(
    private val root: File,
    private val scratchDir: File,
    private val appVersionCode: Int,
    private val registry: SpeechEngineRegistry = SpeechEngineRegistry.DEFAULT,
    private val fileOps: PackFileOps = PackFileOps.Real,
    private val log: (String) -> Unit = {},
    /** Free bytes available under [root]; injectable so tests can simulate a full disk. */
    private val usableSpace: (File) -> Long = { it.usableSpace },
) {

    /** An installed, readable pack. */
    data class InstalledPack(val manifest: VoicePackManifest, val dir: File) {
        /** Bytes the pack occupies on disk (declared files only). */
        val bytes: Long get() = manifest.totalBytes
    }

    sealed interface ImportResult {
        data class Success(val manifest: VoicePackManifest) : ImportResult
        data class Error(val failure: VoicePackImportFailure) : ImportResult
    }

    companion object {
        /** The directory under the app's files dir. */
        const val DIR_NAME = "voicepacks"

        const val MAX_ENTRIES = 48
        const val MAX_EXTRACTED_BYTES = 300L * 1024 * 1024
        const val MAX_TEXT_MEMBER_BYTES = 64L * 1024
        const val MAX_NOTICE_CHARS = 64 * 1024

        /** Extra free space kept beyond the pack itself before staging (bytes). */
        const val FREE_SPACE_MARGIN = 16L * 1024 * 1024
    }

    private val swap = LanguagePackSwap(root, fileOps, completeMembers = listOf(VoicePackManifest.MANIFEST_FILE))
    private val lock = Any()

    @Volatile
    private var recovered = false

    /** [root], after repairing anything an interrupted install or removal left (once). */
    private fun packsRoot(): File {
        if (!recovered) {
            synchronized(lock) {
                if (!recovered) {
                    root.mkdirs()
                    try {
                        swap.recover().forEach { log("Voice pack recovery: $it") }
                    } catch (e: Exception) {
                        log("Voice pack recovery failed: $e")
                    }
                    recovered = true
                }
            }
        }
        return root
    }

    /** Import a voice pack ZIP from [input] (closed here). */
    fun import(input: InputStream): ImportResult {
        val temp = File(scratchDir, "voicepack_import_${System.nanoTime()}")
        return try {
            temp.mkdirs()
            input.use { importInto(it, temp) }
        } catch (e: Exception) {
            log("Voice pack import failed: $e")
            ImportResult.Error(VoicePackImportFailure.fromException(e))
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun importInto(input: InputStream, temp: File): ImportResult {
        val extracted = HashSet<String>()
        var entries = 0
        var total = 0L
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (++entries > MAX_ENTRIES) return error(VoicePackImportFailure.TooManyEntries(MAX_ENTRIES))
                if (!PackFiles.isSafeEntryName(entry.name)) {
                    return error(VoicePackImportFailure.InvalidMember(entry.name.take(128)))
                }
                if (!entry.isDirectory) {
                    val fileName = entry.name.substringAfterLast('/')
                    if (fileName.isEmpty() || fileName.startsWith(".") || !extracted.add(fileName)) {
                        return error(VoicePackImportFailure.InvalidMember(fileName.take(128)))
                    }
                    val memberCap = if (fileName in VoicePackManifest.RESERVED_MEMBERS) {
                        MAX_TEXT_MEMBER_BYTES
                    } else {
                        VoicePackManifest.MAX_FILE_BYTES
                    }
                    val cap = minOf(memberCap, MAX_EXTRACTED_BYTES - total)
                    val out = File(temp, fileName)
                    val fit = FileOutputStream(out).use { PackFiles.copyBounded(zis, it, cap) }
                    if (!fit) {
                        val label = if (cap < memberCap) "ZIP" else fileName
                        return error(VoicePackImportFailure.MemberTooLarge(label, cap))
                    }
                    total += out.length()
                }
                entry = zis.nextEntry
            }
        }

        if (VoicePackManifest.MANIFEST_FILE !in extracted) return error(VoicePackImportFailure.NotAVoicePack)
        val manifestText = PackFiles.readTextCapped(File(temp, VoicePackManifest.MANIFEST_FILE), MAX_NOTICE_CHARS)
            ?: return error(VoicePackImportFailure.InvalidManifest("unreadable"))
        val manifest = when (val parsed = VoicePackManifest.parse(manifestText, appVersionCode)) {
            is VoicePackManifest.Result.Invalid -> return error(parsed.failure)
            is VoicePackManifest.Result.Valid -> parsed.manifest
        }
        // Refuse a pack this build cannot run BEFORE hashing 50 MB of it.
        val resolution = registry.resolve(manifest)
        if (resolution is SpeechEngineRegistry.Resolution.Unsupported) return error(resolution.failure)

        for (file in manifest.files) {
            val f = File(temp, file.path)
            if (!f.isFile) return error(VoicePackImportFailure.MissingFile(file.path))
            if (f.length() != file.size || !PackFiles.sha256OfFile(f).equals(file.sha256, ignoreCase = true)) {
                return error(VoicePackImportFailure.ChecksumMismatch(file.path))
            }
        }
        val declared = manifest.files.map { it.path }.toSet()
        (extracted - declared - VoicePackManifest.RESERVED_MEMBERS).forEach {
            log("Ignoring undeclared voice pack member $it")
        }

        return synchronized(lock) { stageAndSwap(manifest, temp) }
    }

    /** Copy declared files + notices into staging (manifest last) and swap it in. Holds [lock]. */
    private fun stageAndSwap(manifest: VoicePackManifest, temp: File): ImportResult {
        val rootDir = packsRoot()
        val target = File(rootDir, manifest.id)
        if (target.canonicalFile.parentFile != rootDir.canonicalFile) {
            return error(VoicePackImportFailure.InvalidId(manifest.id))
        }
        val needed = manifest.totalBytes + FREE_SPACE_MARGIN
        if (usableSpace(rootDir) < needed) {
            return error(VoicePackImportFailure.Io(IoFailureReason.OUT_OF_SPACE, "need $needed bytes"))
        }
        val staging = swap.stagingDir(manifest.id, System.currentTimeMillis())
        staging.mkdirs()
        try {
            for (file in manifest.files) {
                File(temp, file.path).copyTo(File(staging, file.path), overwrite = true)
            }
            for (notice in listOf(VoicePackManifest.NOTICE_FILE, VoicePackManifest.LICENSE_FILE)) {
                val src = File(temp, notice)
                if (src.isFile) src.copyTo(File(staging, notice), overwrite = true)
            }
            // Manifest LAST: only now is the staged directory "complete" for the swap/recovery.
            File(temp, VoicePackManifest.MANIFEST_FILE).copyTo(File(staging, VoicePackManifest.MANIFEST_FILE), overwrite = true)

            val outcome = try {
                swap.install(staging, manifest.id)
            } catch (e: Exception) {
                swap.recover().forEach { log("Voice pack recovery: $it") }
                throw e
            }
            when (outcome) {
                is LanguagePackSwap.Outcome.Installed -> if (outcome.backupLeft) {
                    log("Previous ${manifest.id} voice pack not fully deleted; recovery retries it")
                }
                LanguagePackSwap.Outcome.Refused -> return error(VoicePackImportFailure.InstallFailed)
                LanguagePackSwap.Outcome.RollbackPending -> {
                    swap.recover().forEach { log("Voice pack recovery: $it") }
                    return error(VoicePackImportFailure.InstallFailed)
                }
            }
        } finally {
            if (fileOps.exists(staging)) fileOps.deleteRecursively(staging)
        }
        log("Installed voice pack ${manifest.id} (${manifest.engineId} v${manifest.engineVersion}, ${manifest.totalBytes} bytes)")
        return ImportResult.Success(manifest)
    }

    /**
     * Installed packs whose manifest still parses for this app, sorted by name. A pack this
     * build can no longer run (engine removed) is listed by [unsupportedPacks] instead.
     */
    fun installedPacks(): List<InstalledPack> = scan().filter { registry.resolve(it.manifest) is SpeechEngineRegistry.Resolution.Supported }

    /** Installed packs whose engine this build does not register (kept so they can be removed). */
    fun unsupportedPacks(): List<InstalledPack> = scan().filter { registry.resolve(it.manifest) is SpeechEngineRegistry.Resolution.Unsupported }

    private fun scan(): List<InstalledPack> {
        val dirs = packsRoot().listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }
        return dirs.mapNotNull { dir ->
            val text = PackFiles.readTextCapped(File(dir, VoicePackManifest.MANIFEST_FILE), MAX_NOTICE_CHARS) ?: return@mapNotNull null
            when (val parsed = VoicePackManifest.parse(text, Int.MAX_VALUE)) {
                is VoicePackManifest.Result.Valid -> InstalledPack(parsed.manifest, dir)
                is VoicePackManifest.Result.Invalid -> null
            }
        }.sortedBy { it.manifest.name }
    }

    /**
     * The pack to use for [language] (`en`, `de`, …): the first installed pack that lists it,
     * else the first installed pack (a model for another language still beats none — the user
     * chose to install it). Null when nothing usable is installed.
     */
    fun activePack(language: String?): InstalledPack? {
        val packs = installedPacks()
        val lang = language?.lowercase()?.substringBefore('-')?.substringBefore('_')
        return packs.firstOrNull { lang != null && lang in it.manifest.languages } ?: packs.firstOrNull()
    }

    /** Remove pack [id]. True when it no longer exists under its own name. */
    fun remove(id: String): Boolean {
        if (!LanguagePackSwap.VALID_PACK_CODE.matches(id)) return false
        return synchronized(lock) { swap.remove(id, System.currentTimeMillis()) }
    }

    /** The pack's NOTICE.txt (capped), or null. */
    fun readNotice(id: String): String? = readMember(id, VoicePackManifest.NOTICE_FILE)

    /** The pack's LICENSE (capped), or null. */
    fun readLicense(id: String): String? = readMember(id, VoicePackManifest.LICENSE_FILE)

    private fun readMember(id: String, name: String): String? {
        if (!LanguagePackSwap.VALID_PACK_CODE.matches(id)) return null
        val f = File(packsRoot(), "$id/$name")
        return if (f.isFile) PackFiles.readTextCapped(f, MAX_NOTICE_CHARS) else null
    }

    private fun error(failure: VoicePackImportFailure): ImportResult {
        log(failure.logMessage)
        return ImportResult.Error(failure)
    }
}
