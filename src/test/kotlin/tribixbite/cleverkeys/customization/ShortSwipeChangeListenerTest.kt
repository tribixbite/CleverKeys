package tribixbite.cleverkeys.customization

import android.content.Context
import android.util.Log
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Saga 2026-10-21: "Reset all" in Short Swipe Customization cleared the mappings, but the
 * keyboard on that screen kept drawing the deleted mappings' labels until it was rebuilt —
 * nothing told a live Keyboard2View that its per-key index changed. The manager now notifies
 * change listeners after every persisted mutation; Keyboard2View registers while attached and
 * invalidates.
 *
 * Mock tier, same harness as [ShortSwipeImportSemanticsTest] (storage file in a temp dir,
 * android.util.Log mocked, the manager constructed directly instead of the singleton).
 */
class ShortSwipeChangeListenerTest {

    private lateinit var scratch: File
    private lateinit var context: Context
    private lateinit var manager: ShortSwipeCustomizationManager
    private var calls = 0
    private val listener: () -> Unit = { calls++ }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        scratch = Files.createTempDirectory("ck-shortswipe-listener").toFile()
        context = mockk()
        every { context.filesDir } returns scratch
        every { context.applicationContext } returns context

        manager = ShortSwipeCustomizationManager::class.java
            .getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(context)
        manager.addChangeListener(listener)
    }

    @After
    fun teardown() {
        unmockkAll()
        scratch.deleteRecursively()
    }

    private fun mapping(key: String, dir: SwipeDirection) = ShortSwipeMapping.textInput(key, dir, "@", "@")

    @Test
    fun everyPersistedMutationNotifies() = runBlocking {
        manager.setMapping(mapping("a", SwipeDirection.NE))
        assertWithMessage("setMapping").that(calls).isEqualTo(1)
        manager.setMapping(mapping("a", SwipeDirection.SW))
        manager.removeMapping("a", SwipeDirection.NE)
        assertWithMessage("removeMapping").that(calls).isEqualTo(3)
        manager.removeMappingsForKey("a")
        assertWithMessage("removeMappingsForKey").that(calls).isEqualTo(4)
        manager.importFromMappings(listOf(mapping("b", SwipeDirection.N)), merge = false)
        assertWithMessage("import").that(calls).isEqualTo(5)
        manager.resetAll()
        assertWithMessage("resetAll — the Saga case").that(calls).isEqualTo(6)
    }

    /**
     * Saga 2026-10-30: Reset All removed everything with no way back. It now returns what it
     * removed and Undo (`restoreAfterReset`) puts those mappings back — persisted and notified,
     * so the screen's keyboard draws them again — without overwriting one made since.
     */
    @Test
    fun resetAllCanBeUndone() = runBlocking {
        manager.setMapping(mapping("a", SwipeDirection.NE))
        manager.setMapping(mapping("s", SwipeDirection.SW))
        val removed = manager.resetAll()
        assertWithMessage("Reset All reports what it removed").that(removed.map { it.keyCode to it.direction })
            .containsExactly("a" to SwipeDirection.NE, "s" to SwipeDirection.SW)
        assertWithMessage("nothing left after the reset").that(manager.mappingCount).isEqualTo(0)

        // A mapping made before Undo is kept.
        val newer = ShortSwipeMapping.textInput("a", SwipeDirection.NE, "new", "new")
        manager.setMapping(newer)
        val before = calls
        assertWithMessage("restored count").that(manager.restoreAfterReset(removed)).isEqualTo(1)
        assertWithMessage("Undo persists and notifies").that(calls).isEqualTo(before + 1)
        assertWithMessage("the newer a NE survives Undo").that(manager.getMapping("a", SwipeDirection.NE)).isEqualTo(newer)
        assertWithMessage("s SW is back").that(manager.getMapping("s", SwipeDirection.SW)).isNotNull()
        // Persisted: a fresh manager over the same file sees both.
        val reread = ShortSwipeCustomizationManager::class.java
            .getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(context)
        reread.loadMappings()
        assertWithMessage("persisted after Undo").that(reread.mappingCount).isEqualTo(2)
    }

    @Test
    fun aNoOpRemoveDoesNotNotify() = runBlocking {
        manager.removeMapping("z", SwipeDirection.E)
        manager.removeMappingsForKey("z")
        assertWithMessage("nothing changed, nothing persisted").that(calls).isEqualTo(0)
    }

    @Test
    fun aRemovedListenerIsNotCalled() = runBlocking {
        manager.removeChangeListener(listener)
        manager.setMapping(mapping("a", SwipeDirection.NE))
        manager.resetAll()
        assertWithMessage("detached views must not be invalidated").that(calls).isEqualTo(0)
    }
}
