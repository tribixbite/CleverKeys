package tribixbite.cleverkeys

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.langpack.LanguagePackManager
import tribixbite.cleverkeys.langpack.LanguagePackManifest

/**
 * Follow-up review 2026-11-15: [LanguageAvailability] asked whether pack storage was readable
 * AFTER scanning it. An unlock landing between the two cached the empty pre-unlock scan as a
 * readable one, which is the very symptom the readability check was added against. The storage
 * here "unlocks" during the scan: the scan sees nothing, the next readability check says yes.
 */
class LanguageAvailabilityUnlockRaceTest {

    private var unlocked = false
    private lateinit var context: Context

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any<String>(), any()) } returns 0
        val assets = mockk<AssetManager> { every { list("dictionaries") } returns arrayOf("en_enhanced.bin") }
        context = mockk {
            every { this@mockk.assets } returns assets
            every { applicationContext } returns this@mockk
        }
        val manager = mockk<LanguagePackManager> {
            every { isPackStorageReadable() } answers { unlocked }
            every { getInstalledPacks() } answers {
                val seen = if (unlocked) listOf(LanguagePackManifest(code = "ru", name = "Russian")) else emptyList()
                unlocked = true  // the first unlock happens while this scan runs
                seen
            }
            every { isInstalled(any()) } returns true
        }
        setSingleton(manager)
        LanguageAvailability.invalidate()
    }

    @After
    fun tearDown() {
        LanguageAvailability.invalidate()
        setSingleton(null)
        unmockkAll()
    }

    private fun setSingleton(value: LanguagePackManager?) {
        LanguagePackManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, value)
    }

    @Test
    fun aScanThatStartedLockedIsNotCachedEvenIfTheUnlockLandsDuringIt() {
        assertThat(LanguageAvailability.availableLanguages(context)).containsExactly("en")
        assertThat(LanguageAvailability.availableLanguages(context)).containsExactly("en", "ru").inOrder()
    }
}
