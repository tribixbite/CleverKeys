package tribixbite.cleverkeys

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.ContextThemeWrapper
import androidx.emoji2.text.EmojiCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #181 on a device WITHOUT a downloadable-fonts provider (no GMS `com.google.android.gms.fonts`).
 *
 * There EmojiCompat cannot load a font, so the system font alone decides what the emoji grid can
 * draw. The test checks that premise first (no provider answers the emoji2 font intent or the
 * framework font-provider action, and EmojiCompat is not loaded), then that every category and a
 * search keep exactly the entries the cell's Paint can draw: shown entries all pass
 * `Paint.hasGlyph`, every dropped entry fails it.
 *
 * On a device that HAS a provider the premise is false and the test is skipped (assumption), so
 * it only reports on a GMS-free image — on emulator.wtf the `*Atd` models (AOSP ATD images).
 */
@RunWith(AndroidJUnit4::class)
class Issue181NoFontProviderTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        Emoji.init(context.resources)
        EmojiKeywordIndex.prewarm(context)
        runBlocking { EmojiKeywordIndex.awaitReady() }
        TestConfigHelper.ensureConfigInitialized(context)
    }

    /** Content providers answering [action], as "package/authority" strings. */
    private fun providersFor(action: String): List<String> =
        context.packageManager.queryIntentContentProviders(Intent(action), PackageManager.GET_META_DATA)
            .map { "${it.providerInfo.packageName}/${it.providerInfo.authority}" }

    @Test
    fun withoutFontProviderGridsShowOnlySystemFontGlyphsAndHideTheRest() {
        // emoji2's DefaultEmojiCompatConfig looks for this action; FontsContract providers
        // (GMS fonts) declare the framework one.
        val emojiFontProviders = providersFor("androidx.content.action.LOAD_EMOJI_FONT")
        val fontsProviders = providersFor("android.content.action.FONTS_PROVIDER") +
            providersFor("android.support.FONTS_PROVIDER")
        val gmsInstalled = runCatching {
            context.packageManager.getPackageInfo("com.google.android.gms", 0)
        }.isSuccess
        Log.i(TAG, "sdk=${android.os.Build.VERSION.SDK_INT} gms=$gmsInstalled " +
            "emojiFontProviders=$emojiFontProviders fontsProviders=$fontsProviders " +
            "emojiCompatConfigured=${EmojiCompat.isConfigured()}")
        assumeTrue("Device has a font provider; #181 no-provider case needs a GMS-free image",
            emojiFontProviders.isEmpty() && fontsProviders.isEmpty())

        val compatLoaded = EmojiCompat.isConfigured() &&
            EmojiCompat.get().loadState == EmojiCompat.LOAD_STATE_SUCCEEDED
        assertFalse("EmojiCompat must not have a font without a provider", compatLoaded)

        var shownTotal = 0
        var hiddenTotal = 0
        val hiddenSamples = mutableListOf<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val themed = ContextThemeWrapper(context, R.style.Dark)
            val grid = EmojiGridView(themed, null)
            val paint = EmojiGridView.EmojiView(ContextThemeWrapper(themed, R.style.emojiGridButton)).paint
            val textGroup = Emoji.getNumGroups() - 1
            val textFaces = Emoji.getEmojisByGroup(textGroup).toSet()
            fun drawable(e: Emoji): Boolean = EmojiGlyphSupport.hasDisplayGlyph(
                e.kv().getString(), e in textFaces
            ) { paint.hasGlyph(it) }

            fun checkAgainst(source: List<Emoji>, where: String) {
                val shown = (0 until grid.adapter.count).map { grid.adapter.getItem(it) as Emoji }
                val shownSet = shown.toSet()
                assertEquals("$where: grid keeps source order of drawable entries",
                    source.filter(::drawable), shown)
                for (e in shown) {
                    val text = e.kv().getString()
                    if (e !in textFaces) assertTrue("$where shows undrawable $text", paint.hasGlyph(text))
                }
                for (e in source) if (e !in shownSet) {
                    assertFalse("$where hid drawable ${e.kv().getString()}", drawable(e))
                    if (hiddenSamples.size < 12) hiddenSamples += e.kv().getString()
                    hiddenTotal++
                }
                shownTotal += shown.size
            }

            for (group in 0 until Emoji.getNumGroups()) {
                grid.setEmojiGroup(group)
                checkAgainst(Emoji.getEmojisByGroup(group), "group $group")
            }
            for (query in listOf("face", "hand", "heart", "person")) {
                grid.searchEmojis(query)
                checkAgainst(Emoji.searchByName(query), "search '$query'")
            }
        }
        Log.i(TAG, "shown=$shownTotal hidden=$hiddenTotal hiddenSamples=$hiddenSamples")
        assertTrue("Must exercise real font-backed entries", shownTotal > 1000)
    }

    private companion object { const val TAG = "Issue181NoFont" }
}
