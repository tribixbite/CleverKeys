package tribixbite.cleverkeys

import android.content.res.AssetManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tribixbite.cleverkeys.langpack.NoticeTextLayout
import tribixbite.cleverkeys.legal.OpenSourceLicenses
import tribixbite.cleverkeys.theme.KeyboardTheme

/**
 * Settings → Help & FAQ → Open-source licenses: the repository's NOTICE, one card per section,
 * followed by the full text of every licence it cites ([OpenSourceLicenses]). The texts are
 * plain-text assets the build copies from NOTICE / LICENSE / LICENSES (`generateLicenseAssets`),
 * read here on a background thread (about 156 KB in total).
 *
 * Cards start collapsed: the full list is about 25 titles, and a licence text runs to hundreds
 * of paragraphs, so each expanded card emits its paragraphs as separate lazy items instead of
 * one very tall Text.
 */
class OpenSourceLicensesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KeyboardTheme {
                LicensesScreen()
            }
        }
    }

    @Composable
    private fun LicensesScreen() {
        // null while loading; the read is local and fast, so no spinner (it would only flash).
        val screen by produceState<OpenSourceLicenses.Screen?>(initialValue = null) {
            value = withContext(Dispatchers.IO) {
                OpenSourceLicenses.buildScreen { path -> readAssetText(assets, path) }
            }
        }
        // Expanded card keys, kept across rotation.
        var expanded by rememberSaveable { mutableStateOf(emptySet<String>()) }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "title") {
                Text(
                    text = stringResource(R.string.licenses_title),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.semantics { heading() },
                )
            }
            item(key = "intro") {
                Text(
                    text = stringResource(R.string.licenses_intro),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )
            }
            val loaded = screen ?: return@LazyColumn
            loaded.preamble?.takeIf { it.isNotBlank() }?.let { preamble ->
                item(key = "preamble") {
                    SelectionContainer {
                        Text(
                            text = preamble,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
            }
            sectionHeader("header:notices", R.string.licenses_notices_header)
            entries(loaded.entries.filter { it.kind == OpenSourceLicenses.Kind.NOTICE_SECTION }, expanded) {
                expanded = expanded.toggle(it)
            }
            sectionHeader("header:texts", R.string.licenses_texts_header)
            entries(loaded.entries.filter { it.kind == OpenSourceLicenses.Kind.LICENSE_TEXT }, expanded) {
                expanded = expanded.toggle(it)
            }
        }
    }

    private fun LazyListScope.sectionHeader(key: String, titleRes: Int) {
        item(key = key) {
            Text(
                text = stringResource(titleRes),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 16.dp, bottom = 4.dp)
                    .semantics { heading() },
            )
        }
    }

    /** One header row per entry; an expanded entry adds one lazy item per text block. */
    private fun LazyListScope.entries(
        entries: List<OpenSourceLicenses.Entry>,
        expanded: Set<String>,
        onToggle: (String) -> Unit,
    ) {
        for (entry in entries) {
            val isOpen = entry.key in expanded
            item(key = entry.key) {
                EntryHeader(entry, isOpen) { onToggle(entry.key) }
            }
            if (!isOpen) continue
            if (entry.missing) {
                item(key = "${entry.key}#missing") {
                    Text(
                        text = stringResource(R.string.licenses_text_missing),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                continue
            }
            entry.blocks.forEachIndexed { index, block ->
                item(key = "${entry.key}#$index") { BlockText(block) }
            }
        }
    }

    @Composable
    private fun EntryHeader(entry: OpenSourceLicenses.Entry, isOpen: Boolean, onClick: () -> Unit) {
        // TalkBack: "double-tap to expand / collapse" names the action; the arrow is decoration.
        val action = stringResource(if (isOpen) R.string.common_collapse else R.string.common_expand)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                .clickable(role = Role.Button, onClickLabel = action, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entry.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (isOpen) "▲" else "▼",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clearAndSetSemantics {},
            )
        }
    }

    /**
     * Same block rendering as the language-pack NOTICE dialog (MultiLanguageSection): rules as
     * dividers, re-flowed prose in the body font, NOTICE's indented tables and quoted licence
     * texts in monospace — except unwrapped one-line paragraphs, which read as prose.
     */
    @Composable
    private fun BlockText(block: NoticeTextLayout.Block) {
        val padding = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        when (block) {
            NoticeTextLayout.Block.Rule -> HorizontalDivider(padding)
            is NoticeTextLayout.Block.Paragraph -> SelectionContainer {
                Text(
                    text = block.text,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = padding,
                )
            }
            is NoticeTextLayout.Block.Preformatted -> SelectionContainer {
                val prose = remember(block) { OpenSourceLicenses.isUnwrappedProse(block) }
                Text(
                    text = if (prose) block.text.trimIndent() else block.text,
                    fontFamily = if (prose) FontFamily.Default else FontFamily.Monospace,
                    fontSize = if (prose) 13.sp else 11.sp,
                    lineHeight = if (prose) 18.sp else 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = padding,
                )
            }
        }
    }

    private fun Set<String>.toggle(key: String): Set<String> = if (key in this) this - key else this + key

    companion object {
        /** An asset's UTF-8 text, or null when the APK does not carry it. */
        internal fun readAssetText(assets: AssetManager, path: String): String? = try {
            assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: FileNotFoundException) {
            null
        }
    }
}
