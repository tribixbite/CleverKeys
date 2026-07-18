package tribixbite.cleverkeys.customization

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.KeyValue

/**
 * Pure JVM unit tests wiring the #156 "Private Copy" editing action into the
 * Short Swipe Customization command surfaces.
 *
 * Verifies that:
 * - [CommandRegistry] exposes exactly one `copy_private` command in the CLIPBOARD category.
 * - That command name resolves through [KeyValue.getKeyByName] to the
 *   [KeyValue.Editing.COPY_PRIVATE] editing key — the exact contract used by
 *   [CustomShortSwipeExecutor] at execution time.
 * - [AvailableCommand.PRIVATE_COPY] exists and appears in the Clipboard grouping.
 *
 * KeyValue name resolution and Editing extraction are pure bit operations, so
 * these run without any Android dependencies.
 */
class PrivateCopyCommandTest {

    // =========================================================================
    // CommandRegistry
    // =========================================================================

    @Test
    fun `CommandRegistry exposes copy_private`() {
        val cmd = CommandRegistry.getByName("copy_private")
        assertThat(cmd).isNotNull()
        assertThat(cmd!!.displayName).isEqualTo("Private Copy")
        assertThat(cmd.category).isEqualTo(CommandRegistry.Category.CLIPBOARD)
    }

    @Test
    fun `copy_private appears exactly once in CommandRegistry (no duplicate-name trap)`() {
        val matches = CommandRegistry.ALL_COMMANDS.filter { it.name == "copy_private" }
        assertThat(matches).hasSize(1)
    }

    @Test
    fun `copy_private is searchable by its private keyword`() {
        val results = CommandRegistry.searchRanked("private")
        assertThat(results.map { it.name }).contains("copy_private")
    }

    @Test
    fun `copy_private resolves via KeyValue to Editing COPY_PRIVATE`() {
        val keyValue = KeyValue.getKeyByName("copy_private")
        assertThat(keyValue).isNotNull()
        assertThat(keyValue!!.getKind()).isEqualTo(KeyValue.Kind.Editing)
        assertThat(keyValue.getEditing()).isEqualTo(KeyValue.Editing.COPY_PRIVATE)
    }

    // =========================================================================
    // AvailableCommand
    // =========================================================================

    @Test
    fun `AvailableCommand includes PRIVATE_COPY with display metadata`() {
        assertThat(AvailableCommand.entries).contains(AvailableCommand.PRIVATE_COPY)
        assertThat(AvailableCommand.PRIVATE_COPY.displayName).isEqualTo("Private Copy")
        assertThat(AvailableCommand.PRIVATE_COPY.description).isNotEmpty()
    }

    @Test
    fun `PRIVATE_COPY is listed under the Clipboard grouping`() {
        val clipboard = AvailableCommand.groupedByCategory()["Clipboard"]
        assertThat(clipboard).isNotNull()
        assertThat(clipboard!!).contains(AvailableCommand.PRIVATE_COPY)
    }

    @Test
    fun `AvailableCommand fromString does not hijack the copy_private registry name`() {
        // The registry name "copy_private" must NOT resolve to any AvailableCommand,
        // so executeCommandByName routes it through CommandRegistry (the working path).
        assertThat(AvailableCommand.fromString("copy_private")).isNull()
    }
}
