package tribixbite.cleverkeys

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.langpack.LanguagePackManager
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

/**
 * Review 2026-11-14 (direct boot): the IME is directBootAware, so its manager cluster can be
 * built before the first unlock. Typing contractions are loaded then, when the credential-
 * encrypted language-pack directory is unreadable: a pack-only language got no contractions and
 * nothing reloaded them after unlock ([ActiveLanguageSync] reloads only on a language change).
 *
 * [ContractionManager.reloadAfterFirstUnlock] registers one reload with the [DirectBootManager].
 * Real manager over the shipped assets; the pack's contraction file is a temp file that becomes
 * visible to [LanguagePackManager.getContractionsPath] when "unlock" arrives.
 */
class ContractionUnlockResyncTest {

    private lateinit var manager: ContractionManager
    private lateinit var packs: LanguagePackManager
    private lateinit var scratch: File

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        val assets = mockk<AssetManager>()
        every { assets.open(any()) } answers { FileInputStream(File("src/main/assets", firstArg<String>())) }
        val context = mockk<Context>(relaxed = true)
        every { context.assets } returns assets
        every { context.applicationContext } returns context

        packs = mockk(relaxed = true)
        every { packs.getContractionsPath(any()) } returns null  // locked: no pack is readable
        mockkObject(LanguagePackManager.Companion)
        every { LanguagePackManager.getInstance(any()) } returns packs

        scratch = Files.createTempDirectory("ck-contraction-unlock").toFile()
        manager = ContractionManager(context)
    }

    @After
    fun teardown() {
        unmockkAll()
        scratch.deleteRecursively()
    }

    /** "ca" ships no bundled contraction file, so only an installed pack can supply one. */
    private fun unlockWithCatalanPack() {
        val file = File(scratch, "contractions.json").apply { writeText("""{"lhome":"l'home"}""") }
        every { packs.getContractionsPath("ca") } returns file
    }

    @Test
    fun aPackOnlyLanguageGetsItsContractionsAtTheFirstUnlock() {
        manager.loadTypingMappings("ca", null)
        assertWithMessage("locked: the pack file cannot be read").that(manager.getNonPairedMapping("lhome")).isNull()

        val directBoot = mockk<DirectBootManager>(relaxed = true)
        every { directBoot.isUserUnlocked } returns false
        val callback = slot<() -> Unit>()
        every { directBoot.registerUnlockCallback(capture(callback)) } returns Unit

        manager.reloadAfterFirstUnlock(directBoot) { ActiveLanguages("ca", null, null) }
        unlockWithCatalanPack()
        callback.captured.invoke()

        assertWithMessage("after unlock the pack's contraction is loaded")
            .that(manager.getNonPairedMapping("lhome")).isEqualTo("l'home")
    }

    @Test
    fun theLanguagesAreReadAtUnlockTimeNotAtRegistration() {
        manager.loadTypingMappings("en", null)
        val directBoot = mockk<DirectBootManager>(relaxed = true)
        every { directBoot.isUserUnlocked } returns false
        val callback = slot<() -> Unit>()
        every { directBoot.registerUnlockCallback(capture(callback)) } returns Unit

        var active = ActiveLanguages("en", null, null)
        manager.reloadAfterFirstUnlock(directBoot) { active }
        // A layout bound to Catalan was selected on the lock screen.
        active = ActiveLanguages("ca", null, "ca")
        unlockWithCatalanPack()
        callback.captured.invoke()

        assertWithMessage("the reload uses the languages active at unlock")
            .that(manager.getNonPairedMapping("lhome")).isEqualTo("l'home")
    }

    @Test
    fun anUnlockedDeviceRegistersNothing() {
        val directBoot = mockk<DirectBootManager>(relaxed = true)
        every { directBoot.isUserUnlocked } returns true

        manager.reloadAfterFirstUnlock(directBoot) { ActiveLanguages("ca", null, null) }

        verify(exactly = 0) { directBoot.registerUnlockCallback(any()) }
    }
}
