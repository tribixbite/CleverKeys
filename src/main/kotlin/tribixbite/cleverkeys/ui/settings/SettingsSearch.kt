package tribixbite.cleverkeys.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity

/**
 * Search/scroll subsystem extracted from SettingsActivity.
 * All declarations are internal extension functions/top-level data class.
 */

/** Record the Y position of a setting for scroll targeting */
internal fun SettingsActivity.recordSettingPosition(settingId: String, yPosition: Int) {
    settingPositions[settingId] = yPosition
}

/**
 * Y (px) of [coords]' top inside the settings scroll CONTENT: its root-space position, minus the
 * viewport's root-space top, plus the current scroll. The single conversion every scroll-target
 * registration uses. (Before 2026-09-30 the viewport offset — status bar + padding — was left
 * in, so every search result scrolled that far past its target.) Call it only from a layout
 * lambda (issue #79: a composition-body read of the scroll value recomposes every pixel).
 */
internal fun SettingsActivity.contentYOf(coords: LayoutCoordinates): Int =
    (coords.positionInRoot().y - scrollViewportTop + (mainScrollState?.value ?: 0)).toInt()

/**
 * Expand-then-scroll for a search result: forget [settingId]'s old position, run [expand], wait
 * until the target's registered position is SETTLED, then scroll and highlight it.
 *
 * Sections open with an expand animation, and `onGloballyPositioned` reports the target every
 * frame of it at an in-flight offset (seen on the Seeker 2026-09-30: the new popover toggle read
 * -931 px 50 ms in, so the scroll clamped to the top; Vibration read 8000 and overshot). The fixed
 * 200 ms delay this replaces read those in-flight values. Settled = registered and unchanged
 * across two polls, capped at [SCROLL_TARGET_WAIT_MS].
 */
internal fun SettingsActivity.expandAndScrollTo(settingId: String, expand: () -> Unit) {
    val self = this
    settingPositions.remove(settingId)
    expand()
    lifecycleScope.launch {
        var waited = 0L
        var previous: Int? = null
        while (waited < SCROLL_TARGET_WAIT_MS) {
            kotlinx.coroutines.delay(SCROLL_TARGET_POLL_MS)
            waited += SCROLL_TARGET_POLL_MS
            val current = settingPositions[settingId]
            if (current != null && current == previous) break
            previous = current
        }
        if (tribixbite.cleverkeys.BuildConfig.ENABLE_VERBOSE_LOGGING) android.util.Log.d(
            "SettingsSearch",
            "scroll target $settingId: waited=${waited}ms pos=${settingPositions[settingId]} " +
                "viewportTop=$scrollViewportTop scroll=${mainScrollState?.value} max=${mainScrollState?.maxValue} " +
                "scope=${composeScope != null}"
        )
        self.scrollToSetting(settingId)
        self.highlightedSettingId = settingId
        kotlinx.coroutines.delay(2000)
        self.highlightedSettingId = null
    }
}

private const val SCROLL_TARGET_POLL_MS = 80L
private const val SCROLL_TARGET_WAIT_MS = 1_500L

/** Scroll to a setting by ID, positioning it at the top of the screen */
internal fun SettingsActivity.scrollToSetting(settingId: String) {
    val position = settingPositions[settingId] ?: return
    val scrollState = mainScrollState ?: return
    // Must use composeScope (has MonotonicFrameClock) instead of lifecycleScope
    // for Compose animated scroll — lifecycleScope lacks the frame clock
    val scope = composeScope ?: return
    scope.launch {
        scrollState.animateScrollTo(maxOf(0, position - 16))
    }
}

/** Collapse all sections */
internal fun SettingsActivity.collapseAllSections() {
    activitiesSectionExpanded = false
    multiLangSectionExpanded = false
    privacySectionExpanded = false
    swipeTypingSectionExpanded = false
    appearanceSectionExpanded = false
    swipeTrailSectionExpanded = false
    inputSectionExpanded = false
    swipeCorrectionsSectionExpanded = false
    gestureTuningSectionExpanded = false
    accessibilitySectionExpanded = false
    // v1.2.6: dictionarySectionExpanded removed
    clipboardSectionExpanded = false
    gifSectionExpanded = false
    backupRestoreSectionExpanded = false
    advancedSectionExpanded = false
    infoSectionExpanded = false
    helpSectionExpanded = false
    testKeyboardExpanded = false
}

/**
 * Searchable settings index. Each entry maps a setting name to its action.
 * title: shown and matched, in the UI language
 * englishTitle: matched as well (English habits keep working under any UI language)
 * activityClass: if not null, clicking navigates to that activity
 * expandSection: if activityClass is null, clicking expands this section
 * gatedBy: if set, this setting requires another toggle to be enabled first
 * settingId: locale-independent ID for scrolling and highlighting (see [settingIdsFor])
 */
internal data class SearchableSetting(
    val title: String,
    val englishTitle: String,
    val keywords: List<String>,
    val sectionName: String,
    val activityClass: Class<*>? = null,
    val expandSection: () -> Unit = {},
    val gatedBy: String? = null,  // e.g., "swipe_typing" means needs swipe typing enabled
    val settingId: String = ""    // For highlighting
)

/** Compiled once. [settingSlug] runs in the composition body of every switch, slider and
 *  dropdown, so an inline `Regex(...)` here is a `Pattern.compile` on every recomposition
 *  of every one of them (issue #79's amplifier). */
private val SETTING_SLUG_SEPARATORS = Regex("[^a-z0-9]+")

/** Fallback scroll/highlight key for a control whose title is not in the generated index
 *  (a literal title): a slug of that title, like scripts/generate_settings_search_index.py's
 *  slugify. Index entries use the title's string-resource name instead — see [settingIdsFor]. */
internal fun SettingsActivity.settingSlug(title: String): String =
    title.lowercase().replace(SETTING_SLUG_SEPARATORS, "_").trim('_')

/**
 * The ids a control with the visible [title] registers its scroll position (and highlight)
 * under: the locale-independent ids of the index entries whose title, in the UI language, is
 * [title]; or the slug fallback for a title the index does not know.
 */
internal fun SettingsActivity.settingIdsFor(title: String): List<String> =
    searchIdsByTitle[title] ?: listOf(settingSlug(title))

/** The section title resource for a generated entry's `sectionKey`. */
@androidx.annotation.StringRes
internal fun sectionTitleRes(sectionKey: String): Int = when (sectionKey) {
    "swipeTyping" -> R.string.settings_section_swipe_typing
    "appearance" -> R.string.settings_section_appearance
    "swipeTrail" -> R.string.settings_section_swipe_trail
    "input" -> R.string.settings_section_input
    "swipeCorrections" -> R.string.settings_section_autocorrection
    "gestureTuning" -> R.string.settings_section_gesture_tuning
    "accessibility" -> R.string.settings_section_accessibility
    "clipboard" -> R.string.settings_section_clipboard
    "gif" -> R.string.settings_section_gif_panel
    "multiLang" -> R.string.settings_section_multilang
    "privacy" -> R.string.settings_section_privacy
    "advanced" -> R.string.settings_section_advanced
    "activities" -> R.string.activities_section_title
    "backupRestore" -> R.string.settings_section_backup_restore
    "help" -> R.string.settings_section_help
    "testKeyboard" -> R.string.test_keyboard_section_title
    "info" -> R.string.settings_section_info
    else -> R.string.settings_section_advanced
}

/** "📱 Activities" -> "Activities": section titles carry a leading emoji. */
internal fun bareTitle(text: String): String = text.dropWhile { !it.isLetterOrDigit() }.trim()

/** Section name shown as "in <section>" for a search result, in the UI language. */
internal fun SettingsActivity.sectionDisplayName(sectionKey: String): String =
    bareTitle(getString(sectionTitleRes(sectionKey)))

/** Expand action for an auto-derived search result's enclosing section. */
internal fun SettingsActivity.expanderFor(sectionKey: String): () -> Unit = {
    when (sectionKey) {
        "swipeTyping" -> swipeTypingSectionExpanded = true
        "appearance" -> appearanceSectionExpanded = true
        "swipeTrail" -> swipeTrailSectionExpanded = true
        "input" -> inputSectionExpanded = true
        "swipeCorrections" -> swipeCorrectionsSectionExpanded = true
        "gestureTuning" -> gestureTuningSectionExpanded = true
        "accessibility" -> accessibilitySectionExpanded = true
        "clipboard" -> clipboardSectionExpanded = true
        "gif" -> gifSectionExpanded = true
        "multiLang" -> multiLangSectionExpanded = true
        "privacy" -> privacySectionExpanded = true
        "advanced" -> advancedSectionExpanded = true
        "activities" -> activitiesSectionExpanded = true
        "backupRestore" -> backupRestoreSectionExpanded = true
        "help" -> helpSectionExpanded = true
        "testKeyboard" -> testKeyboardExpanded = true
        "info" -> infoSectionExpanded = true
    }
}

/** Check if a gating toggle is enabled */
internal fun SettingsActivity.isGateEnabled(gateId: String): Boolean {
    return when (gateId) {
        "swipe_typing" -> swipeTypingEnabled
        "short_gestures" -> shortGesturesEnabled
        "multilang" -> multiLangEnabled
        "gif_enabled" -> gifEnabled
        else -> true
    }
}

/** Execute search result action - collapse others, expand target, handle gating */
internal fun SettingsActivity.executeSearchAction(setting: SearchableSetting) {
    // Check if gated by a disabled toggle: land on the gating toggle instead.
    if (setting.gatedBy != null && !isGateEnabled(setting.gatedBy)) {
        val targetId = setting.gatedBy
        expandAndScrollTo(targetId) {
            collapseAllSections()
            when (targetId) {
                "swipe_typing" -> swipeTypingSectionExpanded = true
                "short_gestures" -> gestureTuningSectionExpanded = true
                "multilang" -> multiLangSectionExpanded = true
                "gif_enabled" -> gifSectionExpanded = true
            }
        }
        return
    }

    // Navigate to activity or expand section
    if (setting.activityClass != null) {
        startActivity(Intent(this, setting.activityClass))
    } else if (setting.settingId == "whats_new") {
        // Special handling for What's New - opens external URL
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/tribixbite/CleverKeys/releases/latest")))
    } else if (setting.settingId.isNotEmpty()) {
        expandAndScrollTo(setting.settingId) {
            collapseAllSections()
            setting.expandSection()
        }
    } else {
        collapseAllSections()
        setting.expandSection()
    }
}

internal fun SettingsActivity.getFilteredSettings(query: String): List<SearchableSetting> {
    if (query.isBlank()) return emptyList()
    return searchableSettings.filter { setting ->
        SettingsSearchMatch.matches(query, setting.title, setting.englishTitle, setting.keywords)
    }
}
