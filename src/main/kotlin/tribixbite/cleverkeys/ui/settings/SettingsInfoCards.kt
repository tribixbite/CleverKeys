@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package tribixbite.cleverkeys.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
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

/** One FAQ entry; both texts are string resources resolved when the card composes. */
internal data class FAQItem(@StringRes val question: Int, @StringRes val answer: Int)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsActivity.VersionInfoCard() {
    val context = LocalContext.current
    val versionInfo = loadVersionInfo()
    val title = stringResource(R.string.settings_version_title)
    // A missing/unreadable version_info.txt shows a localized "unknown" (never an English literal).
    val buildText = stringResource(
        R.string.settings_version_build,
        versionInfo.getProperty("version") ?: stringResource(R.string.settings_version_unknown)
    )
    val toastCopied = stringResource(R.string.settings_version_copied)
    // Hoisted out of the semantics {} lambda (not a composable scope).
    val copyVersionDesc = stringResource(R.string.settings_copy_version_desc)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = copyVersionDesc }
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    val payload = buildVersionCopyPayload(
                        title = title,
                        buildText = buildText,
                        commit = versionInfo.getProperty("commit"),
                        date = versionInfo.getProperty("date"),
                    )
                    // #94: a deliberate NORMAL system copy (setPrimaryClip), not the private
                    // no-history path — version info is not sensitive, and entering the
                    // clipboard history is desirable for the bug-reporting flow it serves.
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
                text = stringResource(R.string.settings_github_repo_title),
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
                text = stringResource(R.string.settings_github_repo_desc),
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
    // FAQ data - each item is a question/answer pair (verified against source code).
    // Text lives in string resources so the FAQ follows the app language.
    val faqItems = listOf(
        FAQItem(R.string.settings_faq_numbers_q, R.string.settings_faq_numbers_a),
        FAQItem(R.string.settings_faq_cursor_q, R.string.settings_faq_cursor_a),
        FAQItem(R.string.settings_faq_select_delete_q, R.string.settings_faq_select_delete_a),
        FAQItem(R.string.settings_faq_switch_language_q, R.string.settings_faq_switch_language_a),
        FAQItem(R.string.settings_faq_emoji_q, R.string.settings_faq_emoji_a),
        FAQItem(R.string.settings_faq_clipboard_q, R.string.settings_faq_clipboard_a),
        FAQItem(R.string.settings_faq_swipe_typing_q, R.string.settings_faq_swipe_typing_a),
        FAQItem(R.string.settings_faq_other_languages_q, R.string.settings_faq_other_languages_a)
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
                    text = stringResource(item.question),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.common_collapse else R.string.common_expand
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Text(
                    text = stringResource(item.answer),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

/**
 * Assemble the text a long-press on the Version Information card puts on the clipboard (#94).
 *
 * [title] and [buildText] are the exact strings the card displays (already localized/resolved),
 * so what the user copies is what the user sees. [commit] and [date] come from
 * `version_info.txt`; the release build recipe deliberately omits them for reproducibility
 * (see `generateVersionInfo` in build.gradle), so both are usually null and each contributes a
 * line only when present.
 *
 * Pure — extracted from the composable so `VersionCopyPayloadTest` can pin the assembly
 * without an instrumented host.
 */
internal fun buildVersionCopyPayload(
    title: String,
    buildText: String,
    commit: String?,
    date: String?,
): String = buildString {
    appendLine(title)
    append(buildText)
    commit?.let { append("\n").append(it) }
    date?.let { append("\n").append(it) }
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
        // No default "version" property: the card substitutes the localized
        // R.string.settings_version_unknown when the property is absent.
    }
    return props
}
