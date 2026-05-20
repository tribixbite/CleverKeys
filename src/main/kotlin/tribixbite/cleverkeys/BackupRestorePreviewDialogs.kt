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
                        // Diff section (above the radio) shows exactly which
                        // key+direction mappings change. Null when current
                        // state couldn't be captured — radio still works.
                        val diff = computeShortSwipeDiff(
                            currentJson = plan.currentShortSwipeRawJson,
                            proposedJson = plan.shortSwipeImportRawJson,
                        )
                        if (diff != null && !diff.isEmpty) {
                            item { ShortSwipeDiffSummary(diff) }
                        }
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

internal fun renderDelta(change: SettingsChange): String {
    // Specialized JSON-blob diff path: when BOTH sides are JsonBlob, compute
    // a structured diff (added/removed/changed names) instead of rendering
    // each side independently as a count. The combined-string output is more
    // informative than "[3 layouts] → [5 layouts]".
    val cur = change.current
    val prop = change.proposed
    if (cur is PrefValue.JsonBlob && prop is PrefValue.JsonBlob) {
        return renderJsonBlobDelta(cur.raw, prop.raw)
    }
    // Asymmetric case (ADDED with Unset → JsonBlob, or vice versa): fall
    // through to per-side rendering so the dialog shows "(none) → [3 layouts]".
    val current = renderPrefValue(cur)
    val proposed = renderPrefValue(prop)
    return "$current  \u2192  $proposed"
}

internal fun renderPrefValue(v: PrefValue): String = when (v) {
    // "(none)" reads more naturally than "(unset)" for multi-language slots
    // (`pref_secondary_language` → "de" → "(none) → 'de'") and for any
    // unknown key that falls through to the Unset sentinel.
    PrefValue.Unset -> "(none)"
    is PrefValue.Bool -> v.v.toString()
    is PrefValue.IntV -> v.v.toString()
    is PrefValue.FloatV -> v.v.toString()
    is PrefValue.Str -> "\"${v.v}\""
    is PrefValue.JsonBlob -> renderJsonBlobSummary(v.raw)
}

/**
 * Summarize a JSON blob for a SINGLE-side render (used when the other side
 * is Unset, i.e. ADDED rows). Output examples:
 *   - `[3 layouts: qwerty, dvorak, azerty]` for the `layouts` key
 *   - `{4 keys}` for `extra_keys` / `custom_extra_keys` (JSON object shape)
 *   - `[N items]` for other array shapes
 *   - `(JSON change)` for malformed input
 */
internal fun renderJsonBlobSummary(raw: String): String = try {
    val el = com.google.gson.JsonParser.parseString(raw)
    when {
        el.isJsonArray -> renderArraySummary(el.asJsonArray)
        el.isJsonObject -> "{${el.asJsonObject.size()} keys}"
        else -> "(JSON change)"
    }
} catch (_: Exception) {
    "(JSON change)"
}

private fun renderArraySummary(arr: com.google.gson.JsonArray): String {
    if (arr.size() == 0) return "[empty]"
    val isLayouts = arr.all { it.isJsonObject && it.asJsonObject.has("name") }
    if (!isLayouts) return "[${arr.size()} item${if (arr.size() == 1) "" else "s"}]"
    val names = arr.map { it.asJsonObject.get("name").asString }
    val label = "${arr.size()} layout${if (arr.size() == 1) "" else "s"}"
    // Truncate the names list at ~50 chars so the row stays readable on
    // narrow phone widths. Excess names land in a "+N more" suffix.
    val joined = truncateNameList(names, maxLen = 50)
    return "[$label: $joined]"
}

/**
 * Diff between current and proposed JSON blobs. Specialized for the known
 * blob shapes (`layouts` array, `extra_keys`/`custom_extra_keys` objects).
 *
 * Output examples:
 *   - `+ azerty, − dvorak  (3 unchanged)`     for `layouts`
 *   - `+ key_dollar, key_euro  (5 unchanged)` for `extra_keys`
 *   - `[3 items → 5 items]`                   for other JSON arrays
 *   - `[3 keys → 4 keys]`                     for other JSON objects
 *   - `(JSON change)`                         for malformed / mixed shapes
 */
internal fun renderJsonBlobDelta(curRaw: String, propRaw: String): String = try {
    val cur = com.google.gson.JsonParser.parseString(curRaw)
    val prop = com.google.gson.JsonParser.parseString(propRaw)
    when {
        cur.isJsonArray && prop.isJsonArray -> renderArrayDelta(cur.asJsonArray, prop.asJsonArray)
        cur.isJsonObject && prop.isJsonObject -> renderObjectDelta(cur.asJsonObject, prop.asJsonObject)
        else -> "(JSON change)"
    }
} catch (_: Exception) {
    "(JSON change)"
}

private fun renderArrayDelta(
    cur: com.google.gson.JsonArray,
    prop: com.google.gson.JsonArray,
): String {
    // Layouts: array of objects with `name` field — diff by name + per-layout
    // deep-diff (key-count delta) for shared names.
    val curIsLayouts = cur.size() > 0 && cur.all { it.isJsonObject && it.asJsonObject.has("name") }
    val propIsLayouts = prop.size() > 0 && prop.all { it.isJsonObject && it.asJsonObject.has("name") }
    if (curIsLayouts && propIsLayouts) {
        return renderLayoutsDelta(cur, prop)
    }
    // Generic array — count delta.
    return "[${cur.size()} → ${prop.size()} item${if (prop.size() == 1 && cur.size() == 1) "" else "s"}]"
}

/**
 * Layouts-specific deep diff:
 *  - Set diff by `name` (added / removed / unchanged-name).
 *  - For each shared-name layout, compare key counts; report
 *    "name: M→N keys" if counts differ, otherwise drop from output.
 *  - If shared names match exactly AND every layout's key count is equal,
 *    the difference is in some sub-field (e.g. key glyphs / script tag) —
 *    report as "[N unchanged; internal field change]".
 */
private fun renderLayoutsDelta(
    cur: com.google.gson.JsonArray,
    prop: com.google.gson.JsonArray,
): String {
    val curByName = cur.associate { it.asJsonObject.get("name").asString to it.asJsonObject }
    val propByName = prop.associate { it.asJsonObject.get("name").asString to it.asJsonObject }
    val added = propByName.keys - curByName.keys
    val removed = curByName.keys - propByName.keys
    val shared = curByName.keys intersect propByName.keys

    // Per-shared-layout key-count delta.
    val keyCountChanges = shared.mapNotNull { name ->
        val curKeys = safeKeyCount(curByName[name])
        val propKeys = safeKeyCount(propByName[name])
        if (curKeys != propKeys) "$name: $curKeys→$propKeys keys" else null
    }
    // Shared layouts whose objects differ but key count is the same.
    val internalChanges = shared.count { name ->
        val a = curByName[name]
        val b = propByName[name]
        a != b && safeKeyCount(a) == safeKeyCount(b)
    }
    val pureUnchanged = shared.size - keyCountChanges.size - internalChanges

    val parts = mutableListOf<String>()
    if (added.isNotEmpty()) parts += "+ ${truncateNameList(added.toList(), maxLen = 30)}"
    if (removed.isNotEmpty()) parts += "− ${truncateNameList(removed.toList(), maxLen = 30)}"
    if (keyCountChanges.isNotEmpty()) {
        parts += "~ ${truncateNameList(keyCountChanges, maxLen = 40)}"
    }
    if (internalChanges > 0) {
        parts += "$internalChanges layout${if (internalChanges == 1) "" else "s"} with internal changes"
    }
    if (pureUnchanged > 0) parts += "($pureUnchanged unchanged)"
    return if (parts.isEmpty()) "[no differences]" else parts.joinToString("  ")
}

private fun renderObjectDelta(
    cur: com.google.gson.JsonObject,
    prop: com.google.gson.JsonObject,
): String {
    // extra_keys / custom_extra_keys / any object — diff by key membership.
    // Detects value-only changes too (same key, different value).
    val curKeys = cur.keySet()
    val propKeys = prop.keySet()
    val added = propKeys - curKeys
    val removed = curKeys - propKeys
    val both = curKeys intersect propKeys
    val changed = both.filter { cur.get(it) != prop.get(it) }.toSet()
    return formatObjectDelta(both.size - changed.size, added, removed, changed)
}

/** Safe `keys` field length — returns 0 for missing / non-array shapes. */
private fun safeKeyCount(obj: com.google.gson.JsonObject?): Int = try {
    obj?.getAsJsonArray("keys")?.size() ?: 0
} catch (_: Exception) {
    0
}

private fun formatObjectDelta(
    unchangedCount: Int,
    added: Set<String>,
    removed: Set<String>,
    changed: Set<String>,
): String {
    if (added.isEmpty() && removed.isEmpty() && changed.isEmpty()) {
        return "[$unchangedCount unchanged]"
    }
    val parts = mutableListOf<String>()
    if (added.isNotEmpty()) parts += "+ ${truncateNameList(added.toList(), maxLen = 30)}"
    if (removed.isNotEmpty()) parts += "− ${truncateNameList(removed.toList(), maxLen = 30)}"
    if (changed.isNotEmpty()) parts += "~ ${truncateNameList(changed.toList(), maxLen = 30)}"
    if (unchangedCount > 0) parts += "($unchangedCount unchanged)"
    return parts.joinToString("  ")
}

/**
 * Comma-join `names`, ellipsizing at the first name that would push the
 * total over `maxLen` characters. Output suffix is "+N more" when truncated.
 */
private fun truncateNameList(names: List<String>, maxLen: Int): String {
    if (names.isEmpty()) return ""
    val sb = StringBuilder()
    var shown = 0
    for (name in names) {
        val sep = if (sb.isEmpty()) "" else ", "
        val tentative = sep + name
        if (sb.length + tentative.length > maxLen && shown > 0) break
        sb.append(tentative)
        shown++
    }
    if (shown < names.size) {
        sb.append(", +${names.size - shown} more")
    }
    return sb.toString()
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

/**
 * Inline summary of the short-swipe diff (counts of added / removed /
 * changed / unchanged mapping ids). Sample names are shown via the
 * existing `truncateNameList` helper so a user can recognize specific
 * mappings without expanding into a full table.
 *
 * Rendered above [ShortSwipeModeRadio] so the user picks Skip / Merge /
 * Replace with concrete delta data in view.
 */
@Composable
private fun ShortSwipeDiffSummary(diff: ShortSwipeDiff) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        // Headline counts on one line for at-a-glance reading.
        val countParts = buildList {
            if (diff.added.isNotEmpty())    add("+${diff.added.size} new")
            if (diff.removed.isNotEmpty())  add("\u2212${diff.removed.size} removed")
            if (diff.changed.isNotEmpty())  add("~${diff.changed.size} changed")
            if (diff.unchanged > 0)         add("(${diff.unchanged} unchanged)")
        }
        Text(
            countParts.joinToString("  "),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Detail lines (truncated). Each non-empty bucket gets one line.
        if (diff.added.isNotEmpty()) {
            Text(
                "  + ${truncateNameList(diff.added, maxLen = 60)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (diff.removed.isNotEmpty()) {
            Text(
                "  \u2212 ${truncateNameList(diff.removed, maxLen = 60)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (diff.changed.isNotEmpty()) {
            Text(
                "  ~ ${truncateNameList(diff.changed, maxLen = 60)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
