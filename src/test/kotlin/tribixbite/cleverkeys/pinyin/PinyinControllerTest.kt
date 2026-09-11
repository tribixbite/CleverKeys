package tribixbite.cleverkeys.pinyin

import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.SuggestionBar
import tribixbite.cleverkeys.langpack.LanguagePackManager
import tribixbite.cleverkeys.langpack.LanguagePackManifest
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * Mock-tier tests for the IME glue: mode activation from the installed pack's
 * `inputMethod`, the composing-region path, the committed-text fallback when an editor
 * refuses `setComposingText`, candidate taps, swipe feeding, and password bypass.
 *
 * Runs under `runMockTests` (android.jar stubs + MockK), like the other Android-touching
 * suites. The table file is real CKPY bytes parsed by the real reader.
 */
class PinyinControllerTest {

    private lateinit var bar: SuggestionBar
    private lateinit var ic: InputConnection
    private lateinit var packManager: LanguagePackManager
    private lateinit var scratch: File
    private var language = "zh"
    private lateinit var phrasesFile: File
    private lateinit var controller: PinyinController

    /**
     * Minimal editor mirror: `committedText` is real text, `composingText` is the active
     * composing region, and `getTextBeforeCursor` answers as the concatenation. Real
     * `setComposingText`/`commitText`/`deleteSurroundingText`/`finishComposingText`
     * semantics — the first version of this test stubbed the cursor text to "", which made
     * the controller correctly treat every run as stale.
     */
    private var committedText = ""
    private var composingText = ""

    private fun editorContent(): String = committedText + composingText

    // --------------------------------------------------------------------- fixtures

    private fun u16(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
    )

    private fun u32(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    /** Hand-assembled CKPY v1 bytes for `key -> [(text, rank)]`. */
    private fun ckpy(vararg entries: Pair<String, List<Pair<String, Int>>>): ByteArray {
        val body = ByteArrayOutputStream()
        for ((key, candidates) in entries.sortedBy { it.first }) {
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            body.write(u16(keyBytes.size))
            body.write(keyBytes)
            body.write(u16(candidates.size))
            for ((text, rank) in candidates) {
                val textBytes = text.toByteArray(Charsets.UTF_8)
                body.write(u16(textBytes.size))
                body.write(textBytes)
                body.write(byteArrayOf(rank.toByte()))
            }
        }
        val header = ByteArrayOutputStream()
        header.write("CKPY".toByteArray(Charsets.US_ASCII))
        header.write(u32(1))
        header.write("zh".toByteArray(Charsets.UTF_8).copyOf(4))
        header.write(u32(entries.size))
        header.write(u32(48))
        header.write(ByteArray(28))
        return header.toByteArray() + body.toByteArray()
    }

    private fun writePhrases(vararg entries: Pair<String, List<Pair<String, Int>>>): File {
        val file = File(scratch, "phrases-${entries.size}.bin")
        file.writeBytes(ckpy(*entries))
        return file
    }

    private fun pinyinPack(code: String = "zh"): LanguagePackManager {
        val manager = mockk<LanguagePackManager>()
        every { manager.getInstalledPack(code) } returns LanguagePackManifest(
            code, "中文（拼音）", inputMethod = LanguagePackManager.INPUT_METHOD_PINYIN
        )
        every { manager.getPhrasesPath(code) } returns phrasesFile
        return manager
    }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        scratch = Files.createTempDirectory("ck-pinyin").toFile()
        phrasesFile = writePhrases("ni" to listOf("\u4F60" to 0))
        bar = mockk(relaxed = true)
        ic = mockk(relaxed = true)
        committedText = ""
        composingText = ""
        every { ic.setComposingText(any(), any()) } answers {
            composingText = firstArg<CharSequence>().toString()
            true
        }
        every { ic.commitText(any(), any()) } answers {
            committedText += firstArg<CharSequence>().toString()
            composingText = ""
            true
        }
        every { ic.finishComposingText() } answers {
            committedText += composingText
            composingText = ""
            true
        }
        every { ic.deleteSurroundingText(any(), any()) } answers {
            committedText = committedText.dropLast(firstArg<Int>().coerceAtMost(committedText.length))
            true
        }
        every { ic.getTextBeforeCursor(any(), any()) } answers {
            editorContent().takeLast(firstArg<Int>())
        }
        packManager = pinyinPack()

        controller = PinyinController(
            languageProvider = { language },
            inputConnectionProvider = { ic },
            suggestionBarProvider = { bar },
            packManagerProvider = { packManager },
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun teardown() {
        unmockkAll()
        scratch.deleteRecursively()
    }

    // ------------------------------------------------------------------- activation

    @Test
    fun aNonPinyinLanguageLeavesTheModeInactive() {
        language = "en"
        val enManager = mockk<LanguagePackManager>()
        every { enManager.getInstalledPack("en") } returns null
        controller = PinyinController(
            languageProvider = { language },
            inputConnectionProvider = { ic },
            suggestionBarProvider = { bar },
            packManagerProvider = { enManager },
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )

        controller.onStartInputView(isPasswordField = false)

        assertThat(controller.isActive()).isFalse()
        assertThat(controller.handleTypedText("n")).isFalse()
    }

    @Test
    fun passwordFieldsNeverActivateEvenForAPinyinPack() {
        controller.onStartInputView(isPasswordField = true)

        assertThat(controller.isActive()).isFalse()
        assertThat(controller.handleTypedText("n")).isFalse()
    }

    @Test
    fun aPinyinPackWithoutAPhraseTableFallsBackToLatin() {
        val manager = pinyinPack()
        every { manager.getPhrasesPath("zh") } returns null
        controller = PinyinController(
            languageProvider = { language },
            inputConnectionProvider = { ic },
            suggestionBarProvider = { bar },
            packManagerProvider = { manager },
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )

        controller.onStartInputView(isPasswordField = false)

        assertWithMessage("a declared mode with an unusable table must degrade, not break")
            .that(controller.isActive()).isFalse()
        assertThat(controller.handleTypedText("n")).isFalse()
    }

    // -------------------------------------------------------------------- composing

    @Test
    fun typingPublishesThePreeditAndTheCandidatesAndSpaceCommitsTheTopOne() {
        controller.onStartInputView(isPasswordField = false)
        assertThat(controller.isActive()).isTrue()

        assertThat(controller.handleTypedText("n")).isTrue()
        verify { ic.setComposingText("n", 1) }
        assertThat(controller.handleTypedText("i")).isTrue()
        verify { ic.setComposingText("ni", 1) }
        verify { bar.setSuggestionsWithScores(listOf("\u4F60"), any(), any()) }

        assertThat(controller.handleTypedText(" ")).isTrue()
        verify { ic.commitText("\u4F60", 1) }
        verify { bar.clearSuggestions() }
    }

    @Test
    fun aCandidateTapCommitsAndNonCandidateWordsAreNotStolen() {
        controller.onStartInputView(isPasswordField = false)
        controller.handleTypedText("n")
        controller.handleTypedText("i")

        assertThat(controller.onCandidateSelected("hello")).isFalse()
        assertThat(controller.onCandidateSelected("\u4F60")).isTrue()
        verify { ic.commitText("\u4F60", 1) }
    }

    @Test
    fun backspaceShortensTheRunThenHandsBackspaceToTheEditor() {
        controller.onStartInputView(isPasswordField = false)
        controller.handleTypedText("n")
        controller.handleTypedText("i")

        assertThat(controller.handleBackspace()).isTrue()
        verify { ic.setComposingText("n", 1) }
        assertThat(controller.handleBackspace()).isTrue()
        verify { ic.setComposingText("", 1) }
        assertWithMessage("an empty composing run must not eat the backspace")
            .that(controller.handleBackspace()).isFalse()
    }

    @Test
    fun escapeCancelsTheComposingRun() {
        controller.onStartInputView(isPasswordField = false)
        controller.handleTypedText("n")

        assertThat(controller.handleKeyevent(KeyEvent.KEYCODE_ESCAPE)).isTrue()
        verify { ic.setComposingText("", 1) }
    }

    @Test
    fun enterCommitsTheTopCandidateWithoutConsumingTheKey() {
        controller.onStartInputView(isPasswordField = false)
        controller.handleTypedText("n")
        controller.handleTypedText("i")

        assertWithMessage("false lets KeyEventHandler still send the editor action")
            .that(controller.handleKeyevent(KeyEvent.KEYCODE_ENTER)).isFalse()
        verify { ic.commitText("\u4F60", 1) }
    }

    // -------------------------------------------------------- swipe + fallback paths

    @Test
    fun aSwipeFeedsThePinyinSurfaceAndNeverAutoCommits() {
        phrasesFile = writePhrases("nihao" to listOf("\u4F60\u597D" to 0))
        packManager = pinyinPack()
        controller = PinyinController(
            languageProvider = { language },
            inputConnectionProvider = { ic },
            suggestionBarProvider = { bar },
            packManagerProvider = { packManager },
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )
        controller.onStartInputView(isPasswordField = false)

        assertThat(controller.onSwipeDecoded(listOf("nihao"), emptyList())).isTrue()

        verify { ic.setComposingText("nihao", 1) }
        verify(exactly = 0) { ic.commitText(any(), any()) }
        verify { bar.setSuggestionsWithScores(listOf("\u4F60\u597D"), any(), any()) }
    }

    @Test
    fun anEditorThatRefusesComposingGetsTheCommittedTextFallback() {
        every { ic.setComposingText(any(), any()) } returns false
        controller.onStartInputView(isPasswordField = false)

        assertThat(controller.handleTypedText("n")).isTrue()
        verify { ic.commitText("n", 1) }
        assertThat(controller.handleTypedText("i")).isTrue()
        verify { ic.commitText("i", 1) }
        assertThat(editorContent()).isEqualTo("ni")

        assertThat(controller.handleTypedText(" ")).isTrue()
        verify { ic.deleteSurroundingText(2, 0) }
        verify { ic.commitText("\u4F60", 1) }
        assertThat(editorContent()).isEqualTo("\u4F60")
    }
}
