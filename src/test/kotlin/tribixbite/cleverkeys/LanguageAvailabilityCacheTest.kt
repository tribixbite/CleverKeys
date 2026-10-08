package tribixbite.cleverkeys

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.langpack.LanguagePackManager
import java.io.File
import java.nio.file.Files

/**
 * 2026-10-08 audit: [LanguageAvailability] was rescanned on the main thread by every caller —
 * `assets.list` plus a manifest parse per installed pack — on Layout Manager's first
 * composition and in the keyboard's message on every bound-layout switch. It now caches one
 * scan per process, and [LanguagePackManager]'s import and delete invalidate it.
 *
 * Driven with a real [LanguagePackManager] over a temp `filesDir` (installed as the process
 * singleton) and a mocked AssetManager whose `list` calls are counted.
 */
class LanguageAvailabilityCacheTest {

    private lateinit var scratch: File
    private lateinit var langpacks: File
    private lateinit var assets: AssetManager
    private lateinit var context: Context
    private lateinit var manager: LanguagePackManager

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        scratch = Files.createTempDirectory("ck-availability").toFile()
        val filesDir = File(scratch, "files").apply { mkdirs() }
        val cacheDir = File(scratch, "cache").apply { mkdirs() }
        langpacks = File(filesDir, "langpacks").apply { mkdirs() }
        assets = mockk()
        every { assets.list("dictionaries") } returns arrayOf("en_enhanced.bin", "fr_enhanced.bin", "README.txt")
        context = mockk()
        every { context.assets } returns assets
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns cacheDir
        every { context.applicationContext } returns context

        manager = LanguagePackManager(context)
        setSingleton(manager)
        LanguageAvailability.invalidate()
    }

    @After
    fun tearDown() {
        LanguageAvailability.invalidate()
        setSingleton(null)
        unmockkAll()
        scratch.deleteRecursively()
    }

    private fun setSingleton(value: LanguagePackManager?) {
        val field = LanguagePackManager::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, value)
    }

    /** An installed pack as the importer leaves it: manifest plus dictionary file. */
    private fun installPack(code: String, withDictionary: Boolean = true) {
        val dir = File(langpacks, code).apply { mkdirs() }
        File(dir, "manifest.json").writeText("""{"code":"$code","name":"Lang $code"}""")
        if (withDictionary) File(dir, "dictionary.bin").writeBytes(ByteArray(48))
    }

    @Test
    fun repeatedQueriesScanOnce() {
        installPack("ru")
        repeat(5) {
            assertThat(LanguageAvailability.availableLanguages(context)).containsExactly("en", "fr", "ru").inOrder()
            assertThat(LanguageAvailability.isAvailable(context, "ru")).isTrue()
            assertThat(LanguageAvailability.isAvailable(context, "de")).isFalse()
        }
        verify(exactly = 1) { assets.list("dictionaries") }
    }

    @Test
    fun deletingAPackInvalidatesTheCache() {
        installPack("ru")
        assertThat(LanguageAvailability.isAvailable(context, "ru")).isTrue()

        assertThat(manager.deletePack("ru")).isTrue()

        assertThat(LanguageAvailability.isAvailable(context, "ru")).isFalse()
        assertThat(LanguageAvailability.availableLanguages(context)).containsExactly("en", "fr").inOrder()
        verify(exactly = 2) { assets.list("dictionaries") }
    }

    /**
     * An import — even a failed one — invalidates: a late failure in the swap can remove the old
     * pack. Without invalidation the pack placed on disk below would stay invisible.
     */
    @Test
    fun anImportAttemptInvalidatesTheCache() {
        assertThat(LanguageAvailability.isAvailable(context, "ru")).isFalse()
        installPack("ru")
        assertThat(LanguageAvailability.isAvailable(context, "ru")).isFalse()  // cached scan

        val resolver = mockk<android.content.ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(any()) } returns null
        manager.importLanguagePack(mockk())

        assertThat(LanguageAvailability.isAvailable(context, "ru")).isTrue()
    }

    @Test
    fun aPackWithoutItsDictionaryIsListedButNotAvailable() {
        // Unchanged semantics: pickers list every pack with a manifest; isAvailable (the
        // "not installed" warning) requires the dictionary file.
        installPack("uk", withDictionary = false)
        assertThat(LanguageAvailability.availableLanguages(context)).contains("uk")
        assertThat(LanguageAvailability.isAvailable(context, "uk")).isFalse()
    }

    @Test
    fun aFailedScanIsNotCachedAndReportsAvailable() {
        every { assets.list("dictionaries") } throws java.io.IOException("boom")
        assertThat(LanguageAvailability.isAvailable(context, "en")).isTrue()
        every { assets.list("dictionaries") } returns arrayOf("en_enhanced.bin")
        assertThat(LanguageAvailability.availableLanguages(context)).containsExactly("en")
    }
}
