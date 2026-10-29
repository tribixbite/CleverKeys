package tribixbite.cleverkeys.stt

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import tribixbite.cleverkeys.stt.VoicePackTestFixtures.FakeFactory

/**
 * [SpeechEngineRegistry]: a pack's engine id + version selects exactly one factory; anything this
 * build does not have is refused with the "needs a newer CleverKeys" failure.
 */
class SpeechEngineRegistryTest {

    private val a = FakeFactory("engine-a", 1..2)
    private val b = FakeFactory("engine-b", 3..3)
    private val registry = SpeechEngineRegistry(listOf(a, b))

    @Test
    fun aRegisteredEngineInRangeIsSupported() {
        assertThat((registry.resolve("engine-a", 1) as SpeechEngineRegistry.Resolution.Supported).factory).isSameInstanceAs(a)
        assertThat((registry.resolve("engine-a", 2) as SpeechEngineRegistry.Resolution.Supported).factory).isSameInstanceAs(a)
        assertThat((registry.resolve("engine-b", 3) as SpeechEngineRegistry.Resolution.Supported).factory).isSameInstanceAs(b)
    }

    @Test
    fun anUnknownEngineNeedsANewerApp() {
        val r = registry.resolve("moonshine-v3-streaming", 1)
        assertThat(r).isInstanceOf(SpeechEngineRegistry.Resolution.Unsupported::class.java)
        assertThat((r as SpeechEngineRegistry.Resolution.Unsupported).failure)
            .isInstanceOf(VoicePackImportFailure.NeedsNewerApp::class.java)
    }

    @Test
    fun aVersionOutsideTheRangeNeedsANewerApp() {
        for (v in listOf(0, 3, 99)) {
            val r = registry.resolve("engine-a", v)
            assertThat((r as SpeechEngineRegistry.Resolution.Unsupported).failure)
                .isInstanceOf(VoicePackImportFailure.NeedsNewerApp::class.java)
        }
    }

    @Test
    fun duplicateIdsAndEmptyRangesAreProgrammingErrors() {
        assertThrows(IllegalArgumentException::class.java) { SpeechEngineRegistry(listOf(a, FakeFactory("engine-a", 5..6))) }
        assertThrows(IllegalArgumentException::class.java) { SpeechEngineRegistry(listOf(FakeFactory("x", 2..1))) }
    }

    @Test
    fun theDefaultRegistryShipsTheMoonshineEngineOnly() {
        // The id a published pack names; changing it orphans every installed pack.
        assertThat(SpeechEngineRegistry.DEFAULT.engineIds).containsExactly("moonshine-v2-streaming")
        assertThat(SpeechEngineRegistry.DEFAULT.resolve("moonshine-v2-streaming", 1))
            .isInstanceOf(SpeechEngineRegistry.Resolution.Supported::class.java)
        assertThat(SpeechEngineRegistry.DEFAULT.resolve("moonshine-v2-streaming", 2))
            .isInstanceOf(SpeechEngineRegistry.Resolution.Unsupported::class.java)
    }
}
