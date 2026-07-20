@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package tribixbite.cleverkeys.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Properties
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity

internal data class FAQItem(val question: String, val answer: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsActivity.VersionInfoCard() {
    val context = LocalContext.current
    val versionInfo = loadVersionInfo()
    val title = stringResource(R.string.settings_version_title)
    val buildText = stringResource(R.string.settings_version_build, versionInfo.getProperty("version", "unknown"))
    val toastCopied = stringResource(R.string.settings_version_copied)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Copy version info" }
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    val payload = buildString {
                        appendLine(title)
                        append(buildText)
                        versionInfo.getProperty("commit")?.let { append("\n").append(it) }
                        versionInfo.getProperty("date")?.let { append("\n").append(it) }
                    }
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("CleverKeys version", payload))
                    Toast.makeText(context, toastCopied, Toast.LENGTH_SHORT).show()
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = buildText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
internal fun SettingsActivity.GitHubInfoCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openGitHubReleases() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "GitHub Repository",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "tribixbite/cleverkeys",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp
            )
            Text(
                text = "Tap to view releases and download updates",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * FAQ Section with expandable items covering common questions.
 * NOTE: All answers verified against actual code implementation.
 */
@Composable
internal fun SettingsActivity.FAQSection() {
    // FAQ data - each item is a question/answer pair (verified against source code)
    val faqItems = listOf(
        FAQItem(
            question = "How do I type numbers and symbols?",
            answer = "Use short swipes (subkeys) on letter keys. Each key has up to 8 subkeys mapped to the cardinal directions N, NE, E, SE, S, SW, W, NW. For example, swipe NORTHEAST on Q for '1' (hint: start in the SW corner). Use Settings → Activities → Per-Key Customization to add your own subkey assignments."
        ),
        FAQItem(
            question = "How do I move the cursor?",
            answer = "Swipe on the spacebar - cursor movement speed is proportional to how far you swipe. For precision navigation, long-press the nav key (between spacebar and enter) to enter TrackPoint mode, then move your finger like a joystick."
        ),
        FAQItem(
            question = "How do I select and delete text?",
            answer = "For Selection-Delete mode: short swipe on backspace then HOLD - move your finger like a joystick to select text (left/right for characters, up/down for lines). Release to delete selected text. Swipe left on backspace deletes the word before cursor."
        ),
        FAQItem(
            question = "How do I quickly switch between languages?",
            answer = "Add the primary and/or secondary language toggle subkeys. Set your languages in Settings → Multi-Language (Primary and Secondary). The toggle subkeys cycle between them, and both languages contribute to swipe predictions when Multi-Language mode is enabled."
        ),
        FAQItem(
            question = "How do I access emojis?",
            answer = "Swipe SOUTHWEST on the Fn key to open the emoji picker. Search will auto-populate with nearby text. The picker includes categories, recents, search, and 119 text emoticons (kaomoji). You can search by keyword or emoji name."
        ),
        FAQItem(
            question = "How do I use the clipboard?",
            answer = "Swipe SOUTHWEST on the Ctrl key to open clipboard history. The panel has tabs for History, Pinned, and Todos. Tap an item's content to expand it; use the icon buttons to paste, move to pinned, or copy as todo. Note: re-copying text already in history won't duplicate or reorder it (tip: use search instead). Password manager and 'sensitive' flagged clippings are excluded by default."
        ),
        FAQItem(
            question = "How does swipe typing work?",
            answer = "Touch the first letter of your word, slide your finger through each letter without lifting, then release on the last letter. Faster may yield better results. Tip: increase 'Length Penalty (Alpha)' for English (~1.5), decrease for other languages (and increase Vocab Frequency Weight)."
        ),
        FAQItem(
            question = "Can I swipe other languages?",
            answer = "Yes, but this currently requires using the qwerty latin layout and manually tuning several settings to achieve useable output- see FAQ entry above and Prefix Boost settings (these are very sensitive; try small changes)."
        )
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        faqItems.forEach { item ->
            FAQItemCard(item)
        }
    }
}

@Composable
internal fun SettingsActivity.FAQItemCard(item: FAQItem) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.question,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Text(
                    text = item.answer,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

internal fun SettingsActivity.loadVersionInfo(): Properties {
    val props = Properties()
    try {
        // Static resource name → direct R reference (no reflection needed).
        val reader = BufferedReader(
            InputStreamReader(
                resources.openRawResource(R.raw.version_info)
            )
        )
        props.load(reader)
        reader.close()
    } catch (e: Exception) {
        android.util.Log.e(SettingsActivity.TAG, "Failed to load version info", e)
        // Set default version
        props.setProperty("version", "unknown")
    }
    return props
}
