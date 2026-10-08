package tribixbite.cleverkeys.customization

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test
import tribixbite.cleverkeys.KeyValue

/**
 * Every catalogue command must reach code that actually performs it (owner report 2026-10-01:
 * "ensure the send intent and other more unique functions/commands are wired right").
 *
 * Before [CommandRouting], a custom mapping to a modifier, dead key, compose, timestamp, macro
 * or slider key reached `Keyboard2View.onCustomShortSwipe`, which logged "Unhandled KeyValue
 * kind" and did nothing. Pure JVM: [CommandRegistry] and [KeyValue] are Android-free here.
 */
class CommandRoutingTest {

    private fun command(name: String) =
        ShortSwipeMapping("g", SwipeDirection.W, "x", ActionType.COMMAND, name)

    @Test
    fun keysThatLatchOrWaitGoThroughTheKeyPipeline() {
        for (name in listOf("shift", "ctrl", "fn", "accent_aigu", "superscript", "compose", "timestamp_date")) {
            assertWithMessage(name).that(CommandRouting.keyPipelineValue(command(name))).isNotNull()
        }
    }

    @Test
    fun executorCommandsStayOnTheExecutor() {
        for (name in listOf("copy", "paste", "shareText", "switch_forward", "config", "left", "SWITCH_FORWARD")) {
            assertWithMessage(name).that(CommandRouting.keyPipelineValue(command(name))).isNull()
        }
    }

    @Test
    fun theRemovedPlaceholderAndMultiCharacterTextNeverRouteToTheKeyPipeline() {
        assertThat(CommandRouting.keyPipelineValue(ShortSwipeMapping.removal("g", SwipeDirection.W))).isNull()
        // A TEXT mapping whose text happens to be a key name is still literal text.
        assertThat(CommandRouting.keyPipelineValue(
            ShortSwipeMapping("g", SwipeDirection.W, "s", ActionType.TEXT, "shift"))).isNull()
        // Multi-character TEXT is a literal macro (CLAUDE.md "Custom apostrophe routing").
        for (text in listOf("'s", "hello", "\n", "\t", "")) {
            assertWithMessage(text).that(CommandRouting.keyPipelineValue(text(text))).isNull()
        }
        // Other action types keep their own executors, even with a one-character value.
        for ((type, value) in listOf(
            ActionType.INTENT to "x", ActionType.TIMESTAMP to "H", ActionType.TEMPLATE to "x", ActionType.KEY_EVENT to "6",
        )) {
            assertWithMessage(type.name).that(CommandRouting.keyPipelineValue(
                ShortSwipeMapping("g", SwipeDirection.W, "x", type, value))).isNull()
        }
    }

    private fun text(value: String) = ShortSwipeMapping("g", SwipeDirection.W, "x", ActionType.TEXT, value)

    /**
     * A single-character TEXT mapping is TYPED through the key pipeline, the value a layout key
     * for that character carries (popover/palette audit, 2026-10-08): the executor's raw
     * commitText skipped autocap, smart punctuation, automatic space and typed-word tracking.
     */
    @Test
    fun singleCharacterTextIsTypedThroughTheKeyPipeline() {
        assertThat(CommandRouting.keyPipelineValue(text("'"))).isEqualTo(KeyValue.makeCharKey('\''))
        assertThat(CommandRouting.keyPipelineValue(text("’"))).isEqualTo(KeyValue.makeCharKey('’'))
        assertThat(CommandRouting.keyPipelineValue(text("€"))).isEqualTo(KeyValue.makeCharKey('€'))
        assertThat(CommandRouting.keyPipelineValue(text(" "))).isEqualTo(KeyValue.makeCharKey(' '))
        // One code point outside the BMP (a surrogate pair) is one typed String key.
        assertThat(CommandRouting.keyPipelineValue(text("😀"))).isEqualTo(KeyValue.makeStringKey("😀"))
    }

    /**
     * Catalogue-wide: every command whose name resolves to a real CHARACTER or STRING key
     * (not getKeyByName's "unknown name" echo) is typed through the key pipeline, never
     * committed raw by the executor. A Char-kind command going to raw commit fails here.
     */
    @Test
    fun everyCharOrStringKindCatalogueCommandIsTypedThroughTheKeyPipeline() {
        val typed = CommandRegistry.ALL_COMMANDS.map { it.name }.filter { name ->
            val kv = KeyValue.getKeyByName(name)
            kv.getKind() == KeyValue.Kind.Char ||
                (kv.getKind() == KeyValue.Kind.String && kv.getString() != name)
        }
        assertWithMessage("the catalogue has typed commands to route").that(typed).isNotEmpty()
        for (name in typed) {
            assertWithMessage(name).that(CommandRouting.keyPipelineValue(command(name)))
                .isEqualTo(KeyValue.getKeyByName(name))
        }
        assertThat(CommandRouting.keyPipelineValue(command("space"))).isEqualTo(KeyValue.getKeyByName("space"))
        assertThat(CommandRouting.keyPipelineValue(command("nbsp"))).isEqualTo(KeyValue.getKeyByName("nbsp"))
    }

    /**
     * Catalogue-wide: a command whose name does not resolve to a real key comes back from the
     * total `getKeyByName` as a String key spelling the name itself. Such a command only works
     * if the executor or Keyboard2View handles it by name, so its name must appear there as a
     * literal. A new catalogue entry without a handler fails here instead of typing its own name.
     */
    @Test
    fun everyCommandWithoutAKeyIsHandledByName() {
        val sources = listOf(
            "src/main/kotlin/tribixbite/cleverkeys/customization/CustomShortSwipeExecutor.kt",
            "src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt",
        ).joinToString("\n") { File(it).readText() }
        val unhandled = CommandRegistry.ALL_COMMANDS.map { it.name }.filter { name ->
            val kv = KeyValue.getKeyByName(name)
            val echoesItsName = kv.getKind() == KeyValue.Kind.String && kv.getString() == name
            echoesItsName && "\"$name\"" !in sources
        }
        assertWithMessage("commands with neither a key nor a by-name handler").that(unhandled).isEmpty()
    }

    /** No catalogue command may land on a key kind that neither path performs. */
    @Test
    fun everyCatalogueKeyKindHasAPath() {
        // Performed by the executor (Char/String/Keyevent) or Keyboard2View (Event/Editing);
        // Placeholder is the `removed` blank, which deliberately does nothing.
        val executorKinds = setOf(
            KeyValue.Kind.Char, KeyValue.Kind.String, KeyValue.Kind.Keyevent,
            KeyValue.Kind.Event, KeyValue.Kind.Editing, KeyValue.Kind.Placeholder,
        )
        val stranded = CommandRegistry.ALL_COMMANDS.map { it.name }.filter { name ->
            val kind = KeyValue.getKeyByName(name).getKind()
            kind !in executorKinds && CommandRouting.keyPipelineValue(name) == null
        }
        assertWithMessage("commands whose key kind has no execution path").that(stranded).isEmpty()
    }
}
