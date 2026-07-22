package tribixbite.cleverkeys.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.SettingsActivity

/**
 * Search/scroll subsystem extracted from SettingsActivity.
 * All declarations are internal extension functions/top-level data class.
 */

/** Record the Y position of a setting for scroll targeting */
internal fun SettingsActivity.recordSettingPosition(settingId: String, yPosition: Int) {
    settingPositions[settingId] = yPosition
}

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
    neuralSectionExpanded = false
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
 * activityClass: if not null, clicking navigates to that activity
 * expandSection: if activityClass is null, clicking expands this section
 * gatedBy: if set, this setting requires another toggle to be enabled first
 * settingId: unique ID for highlighting
 */
internal data class SearchableSetting(
    val title: String,
    val keywords: List<String>,
    val sectionName: String,
    val activityClass: Class<*>? = null,
    val expandSection: () -> Unit = {},
    val gatedBy: String? = null,  // e.g., "swipe_typing" means needs swipe typing enabled
    val settingId: String = ""    // For highlighting
)

/** Stable scroll/highlight key for a control, derived from its visible title.
 *  MUST match scripts/generate_settings_search_index.py's slugify so an auto-derived
 *  search entry's settingId equals the key the control registers its position under. */
internal fun SettingsActivity.settingSlug(title: String): String =
    title.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

/** Display name shown as "in <section>" for an auto-derived (generated) search result. */
internal fun SettingsActivity.sectionDisplayName(sectionKey: String): String = when (sectionKey) {
    "neural" -> "Swipe Typing"
    "appearance" -> "Appearance"
    "swipeTrail" -> "Swipe Trail"
    "input" -> "Word Prediction"
    "swipeCorrections" -> "Swipe Corrections"
    "gestureTuning" -> "Gesture Tuning"
    "accessibility" -> "Accessibility"
    "clipboard" -> "Clipboard"
    "gif" -> "GIF Panel"
    "multiLang" -> "Multi-Language"
    "privacy" -> "Privacy"
    "advanced" -> "Advanced"
    "activities" -> "Activities"
    "backupRestore" -> "Backup & Restore"
    "help" -> "Help & FAQ"
    "testKeyboard" -> "Test Keyboard"
    "info" -> "Information & Actions"
    else -> "Settings"
}

/** Expand action for an auto-derived search result's enclosing section. */
internal fun SettingsActivity.expanderFor(sectionKey: String): () -> Unit = {
    when (sectionKey) {
        "neural" -> neuralSectionExpanded = true
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
        "swipe_typing" -> swipeTypingEnabled && currentLayoutSupportsSwipe
        "short_gestures" -> shortGesturesEnabled
        "multilang" -> multiLangEnabled
        "gif_enabled" -> gifEnabled
        else -> true
    }
}

/** Execute search result action - collapse others, expand target, handle gating */
internal fun SettingsActivity.executeSearchAction(setting: SearchableSetting) {
    val _self = this  // capture extension receiver for use inside non-inline lambdas
    // Check if gated by a disabled toggle
    if (setting.gatedBy != null && !isGateEnabled(setting.gatedBy)) {
        // Find the gating setting and highlight it
        collapseAllSections()
        val targetId = setting.gatedBy
        when (targetId) {
            "swipe_typing" -> neuralSectionExpanded = true
            "short_gestures" -> gestureTuningSectionExpanded = true
            "multilang" -> multiLangSectionExpanded = true
            "gif_enabled" -> gifSectionExpanded = true
        }
        // Delay to let section expand, then scroll and highlight
        // Use lifecycleScope for delay, scrollToSetting uses composeScope internally
        lifecycleScope.launch {
            kotlinx.coroutines.delay(200)  // Wait for layout
            _self.scrollToSetting(targetId)
            _self.highlightedSettingId = targetId
            kotlinx.coroutines.delay(2000)
            _self.highlightedSettingId = null
        }
        return
    }

    // Navigate to activity or expand section
    if (setting.activityClass != null) {
        startActivity(Intent(this, setting.activityClass))
    } else if (setting.settingId == "whats_new") {
        // Special handling for What's New - opens external URL
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/tribixbite/CleverKeys/releases/latest")))
    } else {
        collapseAllSections()
        setting.expandSection()
        // Delay to let section expand, then scroll to top and highlight
        if (setting.settingId.isNotEmpty()) {
            lifecycleScope.launch {
                kotlinx.coroutines.delay(200)  // Wait for layout
                _self.scrollToSetting(setting.settingId)
                _self.highlightedSettingId = setting.settingId
                kotlinx.coroutines.delay(2000)
                _self.highlightedSettingId = null
            }
        }
    }
}

internal fun SettingsActivity.getFilteredSettings(query: String): List<SearchableSetting> {
    if (query.isBlank()) return emptyList()
    val lowerQuery = query.lowercase().trim()
    return searchableSettings.filter { setting ->
        setting.title.lowercase().contains(lowerQuery) ||
        setting.keywords.any { it.lowercase().contains(lowerQuery) }
    }
}
