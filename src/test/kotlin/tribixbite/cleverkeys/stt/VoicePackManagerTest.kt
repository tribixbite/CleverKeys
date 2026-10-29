package tribixbite.cleverkeys.stt

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.IoFailureReason
import tribixbite.cleverkeys.langpack.PackFileOps
import tribixbite.cleverkeys.stt.VoicePackTestFixtures.APP_VERSION
import tribixbite.cleverkeys.stt.VoicePackTestFixtures.FILES
import tribixbite.cleverkeys.stt.VoicePackTestFixtures.REGISTRY
import tribixbite.cleverkeys.stt.VoicePackTestFixtures.manifest
import tribixbite.cleverkeys.stt.VoicePackTestFixtures.validPack
import tribixbite.cleverkeys.stt.VoicePackTestFixtures.zip
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/**
 * [VoicePackManager] import, listing, replacement and removal over real temp directories:
 * caps, sha256/size mismatches, unsafe members, unknown engines, a full disk, a refused swap
 * (the old pack survives), crash recovery and removal.
 */
class VoicePackManagerTest {

    private lateinit var scratch: File
    private lateinit var root: File
    private lateinit var cache: File

    @Before
    fun setUp() {
        scratch = Files.createTempDirectory("ck-voicepack").toFile()
        root = File(scratch, "files/voicepacks")
        cache = File(scratch, "cache").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        scratch.deleteRecursively()
    }

    private fun manager(ops: PackFileOps = PackFileOps.Real, space: (File) -> Long = { Long.MAX_VALUE }) =
        VoicePackManager(root, cache, APP_VERSION, REGISTRY, ops, usableSpace = space)

    private fun import(bytes: ByteArray, m: VoicePackManager = manager()) = m.import(ByteArrayInputStream(bytes))

    private fun failure(bytes: ByteArray, m: VoicePackManager = manager()): VoicePackImportFailure {
        val r = import(bytes, m)
        assertWithMessage("expected refusal").that(r).isInstanceOf(VoicePackManager.ImportResult.Error::class.java)
        return (r as VoicePackManager.ImportResult.Error).failure
    }

    @Test
    fun aValidPackInstallsWithItsFilesAndNotices() {
        val r = import(validPack())
        assertThat(r).isInstanceOf(VoicePackManager.ImportResult.Success::class.java)
        val dir = File(root, "en-fake")
        for ((name, bytes) in FILES) assertThat(File(dir, name).readBytes()).isEqualTo(bytes)
        assertThat(File(dir, "manifest.json").isFile).isTrue()
        val m = manager()
        assertThat(m.installedPacks().map { it.manifest.id }).containsExactly("en-fake")
        assertThat(m.readNotice("en-fake")).isEqualTo("Fake notice")
        assertThat(m.readLicense("en-fake")).isEqualTo("MIT License")
        assertThat(m.activePack("en")?.manifest?.id).isEqualTo("en-fake")
        assertWithMessage("another language falls back to the only pack").that(m.activePack("de")?.manifest?.id).isEqualTo("en-fake")
        assertThat(cache.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun wrapperFoldersAreFlattenedAndUndeclaredMembersDropped() {
        val members = listOf("pack/" to null, "pack/manifest.json" to manifest().toString().toByteArray()) +
            FILES.map { "pack/${it.key}" to it.value } + listOf("pack/extra.bin" to ByteArray(10))
        assertThat(import(zip(members))).isInstanceOf(VoicePackManager.ImportResult.Success::class.java)
        assertThat(File(root, "en-fake/extra.bin").exists()).isFalse()
    }

    @Test
    fun aCorruptFileIsRefusedAndNothingInstalls() {
        val corrupt = FILES.toMutableMap().apply { put("encoder.ort", ByteArray(4096) { 1 }) }
        assertThat(failure(validPack(manifest = manifest(FILES), files = corrupt)))
            .isEqualTo(VoicePackImportFailure.ChecksumMismatch("encoder.ort"))
        val truncated = FILES.toMutableMap().apply { put("decoder.ort", ByteArray(100)) }
        assertThat(failure(validPack(manifest = manifest(FILES), files = truncated)))
            .isEqualTo(VoicePackImportFailure.ChecksumMismatch("decoder.ort"))
        assertThat(root.listFiles().orEmpty().filter { !it.name.startsWith(".") }).isEmpty()
    }

    @Test
    fun aDeclaredFileMissingFromTheZipIsRefused() {
        val missing = FILES - "tokenizer.bin"
        assertThat(failure(validPack(manifest = manifest(FILES), files = missing)))
            .isEqualTo(VoicePackImportFailure.MissingFile("tokenizer.bin"))
    }

    @Test
    fun aZipWithoutAManifestOrWithALanguagePackManifestIsNotAVoicePack() {
        assertThat(failure(zip(FILES.map { it.key to it.value }))).isEqualTo(VoicePackImportFailure.NotAVoicePack)
        val langPack = zip(listOf("manifest.json" to """{"code":"de","name":"German"}""".toByteArray(), "dictionary.bin" to ByteArray(64)))
        assertThat(failure(langPack)).isEqualTo(VoicePackImportFailure.NotAVoicePack)
    }

    @Test
    fun anUnknownEngineIsRefusedAsNeedingANewerApp() {
        val r = failure(validPack(manifest = manifest(engineId = "moonshine-v3-streaming")))
        assertThat(r).isInstanceOf(VoicePackImportFailure.NeedsNewerApp::class.java)
        val v = failure(validPack(manifest = manifest(engineVersion = 9)))
        assertThat(v).isInstanceOf(VoicePackImportFailure.NeedsNewerApp::class.java)
    }

    @Test
    fun unsafeAndDuplicateMemberNamesAreRefused() {
        for (bad in listOf("../evil.ort", "/abs.ort", "a\\b.ort", "pack/./x", ".hidden")) {
            val f = failure(validPack(extra = listOf(bad to ByteArray(1))))
            assertWithMessage("member $bad").that(f).isInstanceOf(VoicePackImportFailure.InvalidMember::class.java)
        }
        val dup = validPack(extra = listOf("sub/encoder.ort" to FILES.getValue("encoder.ort")))
        assertThat(failure(dup)).isEqualTo(VoicePackImportFailure.InvalidMember("encoder.ort"))
    }

    @Test
    fun entryCountAndTextMemberCapsAreEnforcedWhileExtracting() {
        val many = (0..VoicePackManager.MAX_ENTRIES).map { "f$it.bin" to ByteArray(1) }
        assertThat(failure(validPack(extra = many))).isEqualTo(VoicePackImportFailure.TooManyEntries(VoicePackManager.MAX_ENTRIES))
        val bigNotice = zip(listOf("NOTICE.txt" to ByteArray((VoicePackManager.MAX_TEXT_MEMBER_BYTES + 1).toInt())))
        assertThat(failure(bigNotice)).isInstanceOf(VoicePackImportFailure.MemberTooLarge::class.java)
    }

    @Test
    fun aFullDiskRefusesBeforeStaging() {
        val f = failure(validPack(), manager(space = { 1024L }))
        assertThat(f).isInstanceOf(VoicePackImportFailure.Io::class.java)
        assertThat((f as VoicePackImportFailure.Io).reason).isEqualTo(IoFailureReason.OUT_OF_SPACE)
        assertThat(File(root, "en-fake").exists()).isFalse()
    }

    @Test
    fun aDamagedStreamIsAnIoFailure() {
        val broken = object : InputStream() {
            override fun read(): Int = throw java.io.IOException("read error")
        }
        val r = manager().import(broken)
        assertThat((r as VoicePackManager.ImportResult.Error).failure).isInstanceOf(VoicePackImportFailure.Io::class.java)
    }

    @Test
    fun reimportReplacesThePackAtomically() {
        import(validPack())
        val newFiles = FILES.toMutableMap().apply { put("tokenizer.bin", "xyz-new".toByteArray()) }
        val r = import(validPack(manifest = manifest(newFiles).put("version", 4), files = newFiles))
        assertThat(r).isInstanceOf(VoicePackManager.ImportResult.Success::class.java)
        assertThat(File(root, "en-fake/tokenizer.bin").readText()).isEqualTo("xyz-new")
        assertThat(manager().installedPacks().single().manifest.version).isEqualTo(4)
        assertWithMessage("no staging/backup left").that(root.list().orEmpty().filter { it.startsWith(".") }).isEmpty()
    }

    @Test
    fun aRefusedSwapKeepsTheOldPack() {
        import(validPack())
        // Refuse renaming a staging directory into place: the old pack must be renamed back.
        val refusing = object : PackFileOps by PackFileOps.Real {
            override fun rename(from: File, to: File): Boolean =
                if (from.name.startsWith(".staging-")) false else PackFileOps.Real.rename(from, to)
        }
        val newFiles = FILES.toMutableMap().apply { put("tokenizer.bin", "new".toByteArray()) }
        val f = failure(validPack(manifest = manifest(newFiles), files = newFiles), manager(ops = refusing))
        assertThat(f).isEqualTo(VoicePackImportFailure.InstallFailed)
        assertThat(File(root, "en-fake/tokenizer.bin").readText()).isEqualTo("abc")
        assertThat(manager().installedPacks()).hasSize(1)
    }

    @Test
    fun recoveryRestoresABackupLeftByACrash() {
        import(validPack())
        // Simulate a crash after step 1 of the swap: the installed pack renamed to its backup.
        assertThat(File(root, "en-fake").renameTo(File(root, ".backup-en-fake"))).isTrue()
        val fresh = manager()
        assertThat(fresh.installedPacks().map { it.manifest.id }).containsExactly("en-fake")
        assertThat(File(root, ".backup-en-fake").exists()).isFalse()
    }

    @Test
    fun removeDeletesThePackAndRefusesUnsafeIds() {
        import(validPack())
        val m = manager()
        assertThat(m.remove("../files")).isFalse()
        assertThat(m.remove("en-fake")).isTrue()
        assertThat(m.installedPacks()).isEmpty()
        assertThat(m.activePack("en")).isNull()
        assertThat(m.remove("en-fake")).isFalse()
    }

    @Test
    fun aPackWhoseEngineIsNoLongerRegisteredIsListedAsUnsupported() {
        import(validPack())
        val other = VoicePackManager(root, cache, APP_VERSION, SpeechEngineRegistry(listOf(VoicePackTestFixtures.FakeFactory("other"))))
        assertThat(other.installedPacks()).isEmpty()
        assertThat(other.unsupportedPacks().map { it.manifest.id }).containsExactly("en-fake")
    }

    @Test
    fun activePackPrefersTheLanguageMatch() {
        import(validPack())
        val deFiles = mapOf("model.ort" to ByteArray(32) { 3 })
        val de = manifest(deFiles, id = "de-fake").put("name", "German (fake)").put("languages", org.json.JSONArray(listOf("de")))
        assertThat(import(validPack(manifest = de, files = deFiles))).isInstanceOf(VoicePackManager.ImportResult.Success::class.java)
        val m = manager()
        assertThat(m.activePack("de-DE")?.manifest?.id).isEqualTo("de-fake")
        assertThat(m.activePack("en_US")?.manifest?.id).isEqualTo("en-fake")
    }
}
