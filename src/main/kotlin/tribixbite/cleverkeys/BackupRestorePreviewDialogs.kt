package tribixbite.cleverkeys

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tribixbite.cleverkeys.backup.*

/**
 * Top-level entry point for the settings import preview.
 *
 * onApply receives the user's exclusion set and the chosen short-swipe
 * mode. Empty exclusion + MERGE mode = "approve everything" path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsImportPreviewDialog(
    plan: SettingsImportPlan,
    onCancel: () -> Unit,
    onApply: (excludedKeys: Set<String>, shortSwipeMode: ShortSwipeImportMode) -> Unit,
) {
    val excluded = remember { mutableStateOf(emptySet<String>()) }
    val shortSwipeMode = remember { mutableStateOf(ShortSwipeImportMode.MERGE) }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                SettingsPreviewTopBar(
                    appliedCount = plan.changes.size - excluded.value.size,
                    onCancel = onCancel,
                    onApply = { onApply(excluded.value, shortSwipeMode.value) },
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    item { SettingsPreviewHeaderCard(plan) }

                    val modified = plan.changes.filter { it.type == ChangeType.MODIFIED }
                    if (modified.isNotEmpty()) {
                        item { SectionHeader("Modified (${modified.size})") }
                        items(modified, key = { it.key }) { change ->
                            SettingsChangeRow(
                                change = change,
                                isExcluded = change.key in excluded.value,
                                onToggle = { excluded.value = toggle(excluded.value, change.key) },
                            )
                        }
                    }

                    val added = plan.changes.filter { it.type == ChangeType.ADDED }
                    if (added.isNotEmpty()) {
                        item { SectionHeader("Added (${added.size})") }
                        items(added, key = { it.key }) { change ->
                            SettingsChangeRow(
                                change = change,
                                isExcluded = change.key in excluded.value,
                                onToggle = { excluded.value = toggle(excluded.value, change.key) },
                            )
                        }
                    }

                    if (plan.shortSwipeImportSize > 0) {
                        item { SectionHeader("Short-swipe customizations") }
                        item {
                            ShortSwipeModeRadio(
                                size = plan.shortSwipeImportSize,
                                selected = shortSwipeMode.value,
                                onSelect = { shortSwipeMode.value = it },
                            )
                        }
                    }

                    if (plan.parseSkippedKeys.isNotEmpty()) {
                        item { SkippedSection(plan.parseSkippedKeys) }
                    }
                }
            }
        }
    }
}

private fun toggle(set: Set<String>, key: String): Set<String> =
    if (key in set) set - key else set + key

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPreviewTopBar(
    appliedCount: Int,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    TopAppBar(
        title = { Text("Import preview") },
        navigationIcon = {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Cancel preview")
            }
        },
        actions = {
            // Always enabled — empty selection means "Nothing imported" result
            // (see spec §Error handling).
            TextButton(onClick = onApply) {
                Text("Apply ($appliedCount)")
            }
        }
    )
}

@Composable
private fun SettingsPreviewHeaderCard(plan: SettingsImportPlan) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Source: ${plan.sourceVersion}", fontWeight = FontWeight.SemiBold)
            val s = plan.sourceScreen
            val c = plan.currentScreen
            Text("Source screen: ${s.width}\u00d7${s.height}@${s.density}", fontSize = 12.sp)
            Text("Current screen: ${c.width}\u00d7${c.height}@${c.density}", fontSize = 12.sp)
            if (s.width != c.width || s.height != c.height) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Screen-size mismatch — some visual settings may need adjustment.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SettingsChangeRow(
    change: SettingsChange,
    isExcluded: Boolean,
    onToggle: () -> Unit,
) {
    // Whole-row toggle so taps on the key text — not just the checkbox glyph —
    // flip exclusion. Without `toggleable`, narrow checkbox hit-targets miss
    // on touch and tests targeting onNodeWithText(key).performClick() fail.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = !isExcluded,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = !isExcluded, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(change.key, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                text = renderDelta(change),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TypeChip(change.proposed)
    }
}

private fun renderDelta(change: SettingsChange): String {
    val current = renderPrefValue(change.current)
    val proposed = renderPrefValue(change.proposed)
    return "$current  \u2192  $proposed"
}

private fun renderPrefValue(v: PrefValue): String = when (v) {
    PrefValue.Unset -> "(unset)"
    is PrefValue.Bool -> v.v.toString()
    is PrefValue.IntV -> v.v.toString()
    is PrefValue.FloatV -> v.v.toString()
    is PrefValue.Str -> "\"${v.v}\""
    // JsonBlob deliberately rendered as a marker — full diff is in a separate
    // section; mixing JSON walls with scalar diffs hurts UX (spec §UI flow).
    // Placeholder is intentionally non-promising: the row's tap action toggles
    // exclusion, not opens a viewer. A real "tap to view raw" disclosure is a
    // future polish pass (spec §168, §226).
    is PrefValue.JsonBlob -> "(JSON change)"
}

@Composable
private fun TypeChip(value: PrefValue) {
    val label = when (value) {
        is PrefValue.Bool -> "Bool"
        is PrefValue.IntV -> "Int"
        is PrefValue.FloatV -> "Float"
        is PrefValue.Str -> "Str"
        is PrefValue.JsonBlob -> "JSON"
        PrefValue.Unset -> "\u2014"
    }
    AssistChip(onClick = {}, label = { Text(label, fontSize = 10.sp) })
}

@Composable
private fun ShortSwipeModeRadio(
    size: Int,
    selected: ShortSwipeImportMode,
    onSelect: (ShortSwipeImportMode) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "$size short-swipe mappings in this backup",
            fontWeight = FontWeight.SemiBold,
        )
        ShortSwipeImportMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = (mode == selected),
                        role = Role.RadioButton,
                        onClick = { onSelect(mode) },
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = (mode == selected), onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(when (mode) {
                    ShortSwipeImportMode.SKIP -> "Skip — don't import"
                    ShortSwipeImportMode.MERGE -> "Merge — fill gaps, preserve existing (recommended)"
                    ShortSwipeImportMode.REPLACE -> "Replace — wipe existing, install file's set"
                })
            }
        }
        // Warning only when REPLACE is the active selection — see spec
        // "Red warning text shown only when REPLACE is selected".
        if (selected == ShortSwipeImportMode.REPLACE) {
            Text(
                text = "This will REPLACE all your existing short-swipe customizations with the file's $size mappings.",
                color = MaterialTheme.colorScheme.error,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 48.dp, top = 4.dp),
            )
        }
    }
}

@Composable
private fun SkippedSection(skipped: List<SkippedKey>) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                if (expanded) "\u25bc Invalid/skipped (${skipped.size}) — tap to collapse"
                else "\u25b6 Invalid/skipped (${skipped.size}) — tap to expand",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            skipped.forEach { sk ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(sk.key, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(
                        sk.reason,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// =============================================================================
// Dictionary import preview
// =============================================================================

@Composable
private fun LanguageSection(
    lang: String,
    changes: LangChanges,
    excludedCustom: Set<LangWord>,
    excludedDisabled: Set<LangWord>,
    onToggleWord: (LangWord, isCustom: Boolean) -> Unit,
    onToggleAll: (lang: String, allOn: Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val totalCount = changes.newCustomWords.size + changes.newDisabledWords.size
    val excludedCount = changes.newCustomWords.keys.count { LangWord(lang, it) in excludedCustom } +
        changes.newDisabledWords.count { LangWord(lang, it) in excludedDisabled }
    val selectedCount = totalCount - excludedCount
    val allSelected = (selectedCount == totalCount && totalCount > 0)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = allSelected,
                    role = Role.Checkbox,
                    onValueChange = { onToggleAll(lang, it) },
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 2-state checkbox: checked iff all selected, unchecked otherwise.
            // Partial state is communicated via the badge — NOT via a tri-state
            // glyph that destroys cherry-picked selections on tap.
            Checkbox(
                checked = allSelected,
                onCheckedChange = null,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = lang.uppercase(),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$selectedCount of $totalCount selected",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Expand")
            }
        }

        if (expanded) {
            LangSubgroup(
                title = "Custom words (${changes.newCustomWords.size})",
                words = changes.newCustomWords.keys.toList(),
                isExcluded = { word -> LangWord(lang, word) in excludedCustom },
                onToggle = { word -> onToggleWord(LangWord(lang, word), true) },
                metadata = { word -> changes.newCustomWords[word]?.let { "freq $it" } ?: "" },
            )
            LangSubgroup(
                title = "Disabled words (${changes.newDisabledWords.size})",
                words = changes.newDisabledWords,
                isExcluded = { word -> LangWord(lang, word) in excludedDisabled },
                onToggle = { word -> onToggleWord(LangWord(lang, word), false) },
                metadata = { "" },
            )
        }
    }
}

@Composable
private fun LangSubgroup(
    title: String,
    words: List<String>,
    isExcluded: (String) -> Boolean,
    onToggle: (String) -> Unit,
    metadata: (String) -> String,
) {
    if (words.isEmpty()) return
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, words) {
        if (query.isBlank()) words else words.filter { it.contains(query, ignoreCase = true) }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search\u2026") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Bounded height — required because this LazyColumn is nested inside
        // a scrollable parent; without heightIn, Compose throws.
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
        ) {
            items(filtered, key = { it }) { word ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = !isExcluded(word),
                            role = Role.Checkbox,
                            onValueChange = { onToggle(word) },
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = !isExcluded(word), onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(word, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    val meta = metadata(word)
                    if (meta.isNotEmpty()) Text(meta, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Top-level entry point for the dictionary import preview.
 *
 * onApply receives two exclusion sets — one for custom-word LangWords, one
 * for disabled-word LangWords. Empty sets + tapping Apply imports the full
 * delta; non-empty sets uncheck individual words within the deltas.
 *
 * Per-language sections start collapsed; expand reveals two LazyColumn
 * sub-groups (Custom / Disabled) each with their own search field. The
 * language-header checkbox is 2-state with a "X of Y selected" badge —
 * NOT Material's TriStateCheckbox (which would destroy cherry-picked
 * deselections on tap).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryImportPreviewDialog(
    plan: DictImportPlan,
    onCancel: () -> Unit,
    onApply: (excludedCustom: Set<LangWord>, excludedDisabled: Set<LangWord>) -> Unit,
) {
    val excludedCustom = remember { mutableStateOf(emptySet<LangWord>()) }
    val excludedDisabled = remember { mutableStateOf(emptySet<LangWord>()) }

    val totalCustom = plan.perLanguage.values.sumOf { it.newCustomWords.size }
    val totalDisabled = plan.perLanguage.values.sumOf { it.newDisabledWords.size }
    val applyCount = (totalCustom - excludedCustom.value.size) +
        (totalDisabled - excludedDisabled.value.size)

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("Dictionary import preview") },
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Cancel preview")
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            onApply(excludedCustom.value, excludedDisabled.value)
                        }) {
                            Text("Apply ($applyCount)")
                        }
                    },
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(plan.perLanguage.entries.toList(), key = { it.key }) { (lang, changes) ->
                        LanguageSection(
                            lang = lang,
                            changes = changes,
                            excludedCustom = excludedCustom.value,
                            excludedDisabled = excludedDisabled.value,
                            onToggleWord = { lw, isCustom ->
                                if (isCustom) {
                                    excludedCustom.value = toggleLW(excludedCustom.value, lw)
                                } else {
                                    excludedDisabled.value = toggleLW(excludedDisabled.value, lw)
                                }
                            },
                            onToggleAll = { l, allOn ->
                                val langCustom = plan.perLanguage[l]!!.newCustomWords.keys
                                    .map { LangWord(l, it) }.toSet()
                                val langDisabled = plan.perLanguage[l]!!.newDisabledWords
                                    .map { LangWord(l, it) }.toSet()
                                if (allOn) {
                                    excludedCustom.value = excludedCustom.value - langCustom
                                    excludedDisabled.value = excludedDisabled.value - langDisabled
                                } else {
                                    excludedCustom.value = excludedCustom.value + langCustom
                                    excludedDisabled.value = excludedDisabled.value + langDisabled
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun toggleLW(set: Set<LangWord>, lw: LangWord): Set<LangWord> =
    if (lw in set) set - lw else set + lw
