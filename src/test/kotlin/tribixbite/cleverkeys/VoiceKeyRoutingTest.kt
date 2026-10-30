package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.VoiceKeyRouting.Facts
import tribixbite.cleverkeys.VoiceKeyRouting.Route

/**
 * The voice keys' routing (maintainer report 2026-10-30): with voice input on and a pack
 * installed, the mic key used only Google's voice IME and, with that disabled, said "No voice
 * typing app installed". Every combination of (built-in ready, system voice IMEs 0/1/2).
 */
class VoiceKeyRoutingTest {

    @Test
    fun voiceTyping_usesTheBuiltInEngineWheneverItIsReady() {
        for (imes in 0..2) assertEquals("imes=$imes", Route.BUILT_IN, VoiceKeyRouting.forVoiceTyping(Facts(true, imes)))
    }

    @Test
    fun voiceTyping_keepsTheSystemImeWhenTheBuiltInIsNotReady() {
        for (imes in 1..2) assertEquals("imes=$imes", Route.SYSTEM_IME, VoiceKeyRouting.forVoiceTyping(Facts(false, imes)))
    }

    /** Nothing at all: the built-in path, whose message points to Settings → Voice input. */
    @Test
    fun voiceTyping_withNoVoiceAnywhere_goesToTheBuiltInPathNotADeadEnd() {
        assertEquals(Route.BUILT_IN, VoiceKeyRouting.forVoiceTyping(Facts(false, 0)))
    }

    @Test
    fun chooser_listsTheBuiltInEngineBesideTheSystemImes() {
        assertEquals(Route.CHOOSER, VoiceKeyRouting.forChooser(Facts(true, 1)))
        assertEquals(Route.CHOOSER, VoiceKeyRouting.forChooser(Facts(true, 2)))
        assertTrue(VoiceKeyRouting.chooserListsBuiltIn(Facts(true, 1)))
        assertFalse(VoiceKeyRouting.chooserListsBuiltIn(Facts(false, 2)))
    }

    @Test
    fun chooser_withOneEntry_takesItDirectly() {
        assertEquals(Route.BUILT_IN, VoiceKeyRouting.forChooser(Facts(true, 0)))
        assertEquals(Route.SYSTEM_IME, VoiceKeyRouting.forChooser(Facts(false, 1)))
    }

    @Test
    fun chooser_withSeveralSystemImes_stillChooses() {
        assertEquals(Route.CHOOSER, VoiceKeyRouting.forChooser(Facts(false, 2)))
    }

    @Test
    fun chooser_withNothing_goesToTheBuiltInPathForItsSettingsMessage() {
        assertEquals(Route.BUILT_IN, VoiceKeyRouting.forChooser(Facts(false, 0)))
    }
}
