# Gemini-2.5-pro Enterprise Compose Test List — v1

Generated 2026-04-27 via pal-mcp `chat` with `gemini-2.5-pro` (`thinking_mode: high`, `temperature: 0.2`).
Inputs: FEATURES.md (full v1.4.0 feature list + current 91-test coverage) + 5 phoneScreenshots (swipe typing, theme manager, settings list, gesture customization, DIY theme editor).
Continuation ID: `dee9a5bc-f43a-4448-83b1-607f6fd8724d` (49 turns remaining).

---

## LauncherActivity

*   **T1:** `initialStateRendersCorrectly`
    *   **Target:** `LauncherActivity`
    *   **Asserts:** The activity displays the "CleverKeys" brand, the tagline "Privacy, power, and control— with a brain.", and the three setup cards ("Enable Keyboard", "Select Keyboard", "Calibrate Per-Key Gestures").
    *   **Why:** Ensures the first-run experience is not broken and presents the user with the necessary setup steps. High user impact.
    *   **Type:** Smoke
    *   **Status:** `[INFEASIBLE]` - Blocked by `RaccoonAnimationView` continuous animation preventing the Compose test rule from reaching an idle state.

*   **T2:** `step1EnableKeyboardLaunchesCorrectIntent`
    *   **Target:** `LauncherActivity`
    *   **Asserts:** Tapping the "Enable Keyboard" card launches the `android.settings.INPUT_METHOD_SETTINGS` intent.
    *   **Why:** This is the primary action for the first setup step. If it fails, users cannot enable the keyboard.
    *   **Type:** Functional
    *   **Status:** `[INFEASIBLE]` - Blocked by animation.

*   **T3:** `step2SelectKeyboardLaunchesCorrectIntent`
    *   **Target:** `LauncherActivity`
    *   **Asserts:** Tapping the "Select Keyboard" card launches the `InputMethodManager.showInputMethodPicker()` dialog.
    *   **Why:** This is the primary action for the second setup step. If it fails, users cannot switch to the keyboard.
    *   **Type:** Functional
    *   **Status:** `[INFEASIBLE]` - Blocked by animation.

*   **T4:** `step3CalibrateGesturesNavigatesToCalibrationScreen`
    *   **Target:** `LauncherActivity`
    *   **Asserts:** Tapping the "Calibrate Per-Key Gestures" card navigates to `ShortSwipeCalibrationActivity`.
    *   **Why:** Ensures users can access the gesture calibration flow from the initial setup.
    *   **Type:** Interaction
    *   **Status:** `[INFEASIBLE]` - Blocked by animation.

*   **T5:** `inlineTestFieldActivatesKeyboard`
    *   **Target:** `LauncherActivity`
    *   **Asserts:** Tapping the inline test text field brings up the CleverKeys keyboard (once enabled and selected).
    *   **Why:** Validates that the test area works, allowing users to confirm the keyboard is active before leaving the setup screen.
    *   **Type:** Interaction
    *   **Status:** `[INFEASIBLE]` - Blocked by animation.

*   **T6:** `setupStepsHaveAccessibilityContent`
    *   **Target:** `LauncherActivity`
    *   **Asserts:** Each of the three setup cards has a `contentDescription` that accurately describes its purpose for screen readers.
    *   **Why:** Critical for making the setup process accessible to users with visual impairments.
    *   **Type:** Accessibility
    *   **Status:** `[INFEASIBLE]` - Blocked by animation.

## SettingsActivity

*   **T7:** `initialStateRendersSections`
    *   **Target:** `SettingsActivity`
    *   **Asserts:** The activity loads and displays the list of setting sections (e.g., "Neural Prediction", "Themes & Appearance", "Clipboard") in their collapsed state.
    *   **Why:** Basic smoke test to ensure the main settings screen is not broken.
    *   **Type:** Smoke

*   **T8:** `sectionExpansionAndCollapse`
    *   **Target:** `SettingsActivity`
    *   **Asserts:** Tapping a collapsed section header expands it to show its contents. Tapping it again collapses it.
    *   **Why:** Core interaction model for the settings screen. A failure here makes the app unconfigurable.
    *   **Type:** Interaction

*   **T9:** `searchExpandsAndScrollsToTarget`
    *   **Target:** `SettingsActivity`
    *   **Asserts:** Entering a search query (e.g., "beam") filters the list, expands the relevant section ("Neural Prediction"), and scrolls the specific setting ("Beam Width") into view.
    *   **Why:** Validates the primary discovery mechanism for settings, which is crucial for a feature-rich app.
    *   **Type:** Functional

*   **T10:** `searchQueryPersistsOnRotation`
    *   **Target:** `SettingsActivity`
    *   **Asserts:** After entering a search query and rotating the device, the search query is still present in the search bar and the results are still filtered.
    *   **Why:** Prevents data loss and user frustration during configuration changes.
    *   **Type:** State Restoration

*   **T11:** `expandedSectionsPersistOnRotation`
    *   **Target:** `SettingsActivity`
    *   **Asserts:** After expanding one or more sections and rotating the device, the same sections remain expanded.
    *   **Why:** Ensures a stable and predictable UI across configuration changes.
    *   **Type:** State Restoration

*   **T12:** `searchForNonExistentSettingShowsEmptyState`
    *   **Target:** `SettingsActivity`
    *   **Asserts:** Searching for a term that does not match any setting title or description (e.g., "xyz_nonexistent_term") displays a "No results found" message.
    *   **Why:** Provides clear feedback to the user when their search yields no results.
    *   **Type:** Negative

*   **T13:** `clearSearchQueryRestoresFullList`
    *   **Target:** `SettingsActivity`
    *   **Asserts:** After performing a search, tapping the 'X' icon in the search bar clears the query and restores the full, unfiltered list of settings sections.
    *   **Why:** Ensures the search UI is fully functional and reversible.
    *   **Type:** Interaction

*   **T14:** `gatingHidesNeuralSettingsWhenSwipeDisabled`
    *   **Target:** `SettingsActivity`
    *   **Asserts:** When the "Enable Swipe Typing" switch is toggled OFF, dependent settings like "Enable Neural Swipe Prediction" and "Beam Width" become disabled or hidden.
    *   **Why:** Prevents users from configuring a disabled feature, reducing confusion.
    *   **Type:** Functional

*   **T15:** `navigateToThemeSettings`
    *   **Target:** `SettingsActivity`
    *   **Asserts:** Tapping the "Themes & Appearance" entry navigates to `ThemeSettingsActivity`.
    *   **Why:** Verifies navigation between the main settings and its sub-screens.
    *   **Type:** Interaction

*   **T16:** `navigateToAutoCorrectionSettings`
    *   **Target:** `SettingsActivity`
    *   **Asserts:** Tapping the "Autocorrection" entry navigates to `AutoCorrectionSettingsActivity`.
    *   **Why:** Verifies navigation between the main settings and its sub-screens.
    *   **Type:** Interaction

*   **T17:** `navigateToNeuralSettings`
    *   **Target:** `SettingsActivity`
    *   **Asserts:** Tapping the "Neural Prediction" entry navigates to `NeuralSettingsActivity`.
    *   **Why:** Verifies navigation between the main settings and its sub-screens.
    *   **Type:** Interaction

*   **T18:** `settingsItemsHaveAccessibilityContent`
    *   **Target:** `SettingsActivity`
    *   **Asserts:** All interactive elements (switches, sliders, navigation items) have `contentDescription`s that are read correctly by screen readers.
    *   **Why:** Ensures the settings screen is fully navigable and configurable for visually impaired users.
    *   **Type:** Accessibility

*   **T19:** `aboutPageVersionCopy`
    *   **Target:** About Page (launched from `SettingsActivity`)
    *   **Asserts:** Long-pressing the version info row copies the version string to the clipboard.
    *   **Why:** Confirms that the Issue #94 feature for easy bug reporting is functional.
    *   **Type:** Functional

## ThemeSettingsActivity

*   **T20:** `rendersBuiltInThemeList`
    *   **Target:** `ThemeSettingsActivity`
    *   **Asserts:** The activity displays a list of built-in themes, each with a name, description, and a preview of QWERT keys.
    *   **Why:** Smoke test to ensure the theme selection screen loads correctly.
    *   **Type:** Smoke

*   **T21:** `selectBuiltInTheme`
    *   **Target:** `ThemeSettingsActivity`
    *   **Asserts:** Tapping on a theme (e.g., "CleverKeys Light") marks it as selected and persists this choice in preferences.
    *   **Why:** Core functionality of the screen; allows users to change the keyboard's appearance.
    *   **Type:** Functional

*   **T22:** `addThemeButtonOpensCreateDialog`
    *   **Target:** `ThemeSettingsActivity`
    *   **Asserts:** Tapping the '+' (add) icon button opens the "Create Theme" dialog.
    *   **Why:** Entry point for the DIY theme feature.
    *   **Type:** Interaction

*   **T23:** `createThemeDialogInitialState`
    *   **Target:** Create Theme Dialog
    *   **Asserts:** The "Create Theme" dialog opens with an empty "Theme Name" field and default color values for all attributes.
    *   **Why:** Ensures the dialog starts in a clean, predictable state.
    *   **Type:** Functional

*   **T24:** `createThemeAndSave`
    *   **Target:** Create Theme Dialog
    *   **Asserts:** After entering a unique name, modifying at least one color, and tapping "Save", the dialog closes and the new theme appears in the list of themes.
    *   **Why:** Validates the end-to-end flow for creating a custom theme.
    *   **Type:** Functional

*   **T25:** `createThemeDialogDismissOnCancel`
    *   **Target:** Create Theme Dialog
    *   **Asserts:** After making changes in the dialog, tapping the "Cancel" button (or 'X' icon, or back press) closes the dialog without adding a new theme to the list.
    *   **Why:** Ensures that incomplete or unwanted theme edits can be safely discarded.
    *   **Type:** Interaction

*   **T26:** `createThemeDialogStateRestorationOnRotation`
    *   **Target:** Create Theme Dialog
    *   **Asserts:** If the user enters a theme name and changes colors, rotating the device preserves these unsaved changes within the dialog.
    *   **Why:** Prevents data loss during device configuration changes, which is a common source of user frustration.
    *   **Type:** State Restoration

*   **T27:** `saveThemeWithEmptyNameFails`
    *   **Target:** Create Theme Dialog
    *   **Asserts:** Tapping "Save" when the "Theme Name" field is empty displays an error message (e.g., a Toast or a TextField error state) and the dialog remains open.
    *   **Why:** Validates input handling and prevents the creation of invalid data.
    *   **Type:** Negative

*   **T28:** `saveThemeWithDuplicateNameFails`
    *   **Target:** Create Theme Dialog
    *   **Asserts:** Tapping "Save" with a name that already exists displays an error and prevents the save.
    *   **Why:** Ensures data integrity and prevents user confusion from identically named themes.
    *   **Type:** Negative

*   **T29:** `deleteCustomThemeFlow`
    *   **Target:** `ThemeSettingsActivity`
    *   **Asserts:** Long-pressing a custom theme shows a "Delete" option. Tapping it shows a confirmation dialog. Confirming the deletion removes the theme from the list.
    *   **Why:** Allows users to manage their custom themes.
    *   **Type:** Interaction

*   **T30:** `deleteConfirmationDialogCancel`
    *   **Target:** Delete Theme Confirmation Dialog
    *   **Asserts:** When the delete confirmation dialog is shown, tapping "Cancel" dismisses the dialog and the theme is not removed.
    *   **Why:** Ensures the destructive delete action can be safely aborted.
    *   **Type:** Interaction

*   **T31:** `colorPickerDialogOpensAndAppliesColor`
    *   **Target:** Create Theme Dialog -> Color Picker
    *   **Asserts:** Tapping a color attribute (e.g., "Key Default") opens the color picker dialog. Selecting a color and confirming closes the picker and updates the hex code and color swatch in the "Create Theme" dialog.
    *   **Why:** Validates the core interaction for customizing theme colors.
    *   **Type:** Interaction

*   **T32:** `colorPickerHexInputInvalid`
    *   **Target:** Color Picker Dialog
    *   **Asserts:** Entering an invalid hex code (e.g., "#123", "GHIJKL") into the text input is either prevented or shows an error state, and does not update the color.
    *   **Why:** Robustness test for the hex input feature (Issue #93).
    *   **Type:** Negative

*   **T33:** `themePreviewsRenderCorrectlyInLightAndDarkMode`
    *   **Target:** `ThemeSettingsActivity`
    *   **Asserts:** The QWERT key previews for both light and dark themes are visually distinct and legible when the system is in both light and dark mode.
    *   **Why:** Ensures the theme previews themselves are not broken by the system theme.
    *   **Type:** Theme / Visual

*   **T34:** `swipeTrailColorCustomization`
    *   **Target:** Create Theme Dialog
    *   **Asserts:** The color picker for "Swipe trail color" correctly applies the selected color, which is reflected in the theme data upon saving.
    *   **Why:** Verifies customization of a key visual feedback element.
    *   **Type:** Functional

## AutoCorrectionSettingsActivity

*   **T35:** `rendersAllControls`
    *   **Target:** `AutoCorrectionSettingsActivity`
    *   **Asserts:** The activity displays all relevant controls: toggles for autocorrect, auto-space, etc., and sliders for thresholds.
    *   **Why:** Smoke test for the autocorrection configuration screen.
    *   **Type:** Smoke

*   **T36:** `toggleAutocorrectAndVerifyState`
    *   **Target:** `AutoCorrectionSettingsActivity`
    *   **Asserts:** Toggling the main "Autocorrect" switch updates its state and persists the value to preferences.
    *   **Why:** Verifies the master control for the autocorrection feature.
    *   **Type:** Functional

*   **T37:** `gatingHidesThresholdWhenAutocorrectDisabled`
    *   **Target:** `AutoCorrectionSettingsActivity`
    *   **Asserts:** When the "Autocorrect" switch is OFF, the "Levenshtein distance" slider and other related controls are disabled or hidden.
    *   **Why:** Prevents configuration of a disabled feature.
    *   **Type:** Functional

*   **T38:** `sliderValueUpdatesAndPersists`
    *   **Target:** `AutoCorrectionSettingsActivity`
    *   **Asserts:** Moving the "Levenshtein distance" slider updates the displayed value and saves the new value to preferences.
    *   **Why:** Ensures sliders, a primary input control, are working correctly.
    *   **Type:** Interaction

*   **T39:** `allControlStatesRestoreOnRotation`
    *   **Target:** `AutoCorrectionSettingsActivity`
    *   **Asserts:** After changing all toggle and slider values, rotating the device restores the screen with the exact same values.
    *   **Why:** Guarantees a seamless user experience across configuration changes.
    *   **Type:** State Restoration

*   **T40:** `accessibilityForTogglesAndSliders`
    *   **Target:** `AutoCorrectionSettingsActivity`
    *   **Asserts:** All switches have a state description (e.g., "On/Off") and all sliders have a `contentDescription` that includes their current value and purpose.
    *   **Why:** Critical for allowing visually impaired users to configure autocorrection behavior.
    *   **Type:** Accessibility

## ClipboardSettingsActivity

*Note: These tests assume the orphan activity is fixed and integrated.*

*   **T41:** `rendersInitialState`
    *   **Target:** `ClipboardSettingsActivity`
    *   **Asserts:** The activity displays the history limit slider, privacy toggles, and action buttons ("Clear-all").
    *   **Why:** Smoke test for the clipboard configuration screen.
    *   **Type:** Smoke

*   **T42:** `historyLimitSliderUpdatesPreference`
    *   **Target:** `ClipboardSettingsActivity`
    *   **Asserts:** Adjusting the "History limit" slider from 1 to 100 (and the sentinel 0) updates the displayed value and saves it to preferences.
    *   **Why:** Verifies configuration of a key clipboard parameter that impacts performance and storage.
    *   **Type:** Functional

*   **T43:** `clearAllActionShowsConfirmationDialog`
    *   **Target:** `ClipboardSettingsActivity`
    *   **Asserts:** Tapping the "Clear-all" button displays a confirmation dialog warning the user about the destructive action.
    *   **Why:** Prevents accidental data loss.
    *   **Type:** Interaction

*   **T44:** `clearAllConfirmationDialogConfirmAction`
    *   **Target:** Clear All Confirmation Dialog
    *   **Asserts:** Tapping "Confirm" in the dialog triggers the clipboard clearing logic and dismisses the dialog.
    *   **Why:** Verifies the positive path for a critical, destructive action.
    *   **Type:** Functional

*   **T45:** `clearAllConfirmationDialogCancelAction`
    *   **Target:** Clear All Confirmation Dialog
    *   **Asserts:** Tapping "Cancel" in the dialog dismisses it without clearing the clipboard history.
    *   **Why:** Verifies the negative/abort path for a critical, destructive action.
    *   **Type:** Interaction

*   **T46:** `controlStatesRestoreOnRotation`
    *   **Target:** `ClipboardSettingsActivity`
    *   **Asserts:** The current value of the history limit slider is preserved after device rotation.
    *   **Why:** Ensures UI state persistence.
    *   **Type:** State Restoration

## NeuralSettingsActivity

*   **T47:** `rendersAllAdvancedControls`
    *   **Target:** `NeuralSettingsActivity`
    *   **Asserts:** The activity displays all advanced neural prediction controls, including sliders for Beam Width, ONNX threads, Confidence Threshold, etc.
    *   **Why:** Smoke test for the advanced configuration screen.
    *   **Type:** Smoke

*   **T48:** `beamWidthSliderUpdatesPreference`
    *   **Target:** `NeuralSettingsActivity`
    *   **Asserts:** Adjusting the "Beam Width" slider between 1 and 16 updates the displayed value and persists the change.
    *   **Why:** Verifies a key performance vs. accuracy tuning parameter.
    *   **Type:** Functional

*   **T49:** `onnxThreadsSliderUpdatesPreference`
    *   **Target:** `NeuralSettingsActivity`
    *   **Asserts:** Adjusting the "ONNX threads" slider between 1 and 8 updates the displayed value and persists the change.
    *   **Why:** Verifies a key performance tuning parameter.
    *   **Type:** Functional

*   **T50:** `gatingHidesBeamSettingsWhenGreedySearchEnabled`
    *   **Target:** `NeuralSettingsActivity`
    *   **Asserts:** Toggling "Greedy search" ON disables or hides controls that are only relevant to beam search (e.g., Beam Width, Beam alpha, pruning settings).
    *   **Why:** Creates a cleaner, less confusing UI by hiding irrelevant options.
    *   **Type:** Functional

*   **T51:** `allSliderAndToggleStatesRestoreOnRotation`
    *   **Target:** `NeuralSettingsActivity`
    *   **Asserts:** All modified slider and toggle values are correctly restored after a device rotation.
    *   **Why:** Ensures a non-frustrating user experience for power users tuning many parameters.
    *   **Type:** State Restoration

*   **T52:** `resetToDefaultsShowsConfirmationAndResets`
    *   **Target:** `NeuralSettingsActivity`
    *   **Asserts:** Tapping "Reset to defaults" shows a confirmation dialog. Confirming resets all controls on the screen to their default values.
    *   **Why:** Provides an escape hatch for users who have misconfigured the settings.
    *   **Type:** Interaction

## ShortSwipeCustomizationActivity

*   **T53:** `rendersKeyGrid`
    *   **Target:** `ShortSwipeCustomizationActivity`
    *   **Asserts:** The activity displays a grid of keyboard keys that are tappable.
    *   **Why:** Smoke test for the gesture customization UI.
    *   **Type:** Smoke

*   **T54:** `tapKeyOpensDirectionSelector`
    *   **Target:** `ShortSwipeCustomizationActivity`
    *   **Asserts:** Tapping a key (e.g., 'Q') opens a dialog or bottom sheet showing the 8 swipe directions.
    *   **Why:** Verifies the first step in the gesture assignment flow.
    *   **Type:** Interaction

*   **T55:** `selectDirectionOpensActionList`
    *   **Target:** Direction Selector Dialog
    *   **Asserts:** Tapping a direction (e.g., "Swipe Up") opens a new screen or dialog listing all 208 possible actions.
    *   **Why:** Verifies the second step in the gesture assignment flow.
    *   **Type:** Interaction

*   **T56:** `selectActionAssignsAndCloses`
    *   **Target:** Action List
    *   **Asserts:** Selecting an action from the list (e.g., "Delete word") saves this assignment for the chosen key and direction, and closes all dialogs, returning to the key grid.
    *   **Why:** Validates the complete, end-to-end gesture assignment workflow.
    *   **Type:** Functional

*   **T57:** `accessibilityOfKeyGrid`
    *   **Target:** `ShortSwipeCustomizationActivity`
    *   **Asserts:** Each key in the grid has a `contentDescription` (e.g., "Key Q") so it can be identified by screen readers.
    *   **Why:** Makes the gesture customization feature accessible.
    *   **Type:** Accessibility

## ShortSwipeCalibrationActivity

*   **T58:** `rendersCalibrationSliders`
    *   **Target:** `ShortSwipeCalibrationActivity`
    *   **Asserts:** The activity displays sliders for gesture calibration parameters like "Trajectory resampling modes" and "Touch smoothing window".
    *   **Why:** Smoke test for the calibration screen.
    *   **Type:** Smoke

*   **T59:** `slidersUpdatePreferences`
    *   **Target:** `ShortSwipeCalibrationActivity`
    *   **Asserts:** Adjusting each slider on the screen updates its displayed value and persists the new value.
    *   **Why:** Verifies that calibration controls are functional.
    *   **Type:** Functional

*   **T60:** `sliderValuesRestoreOnRotation`
    *   **Target:** `ShortSwipeCalibrationActivity`
    *   **Asserts:** All slider values are preserved after a device rotation.
    *   **Why:** Ensures UI state persistence.
    *   **Type:** State Restoration

## LayoutManagerActivity

*   **T61:** `rendersTabsAndLayoutLists`
    *   **Target:** `LayoutManagerActivity`
    *   **Asserts:** The activity displays tabs for "System", "Predefined", and "Custom" layouts, and shows a list of layouts under the default tab.
    *   **Why:** Smoke test for the layout management screen.
    *   **Type:** Smoke

*   **T62:** `switchBetweenTabs`
    *   **Target:** `LayoutManagerActivity`
    *   **Asserts:** Tapping on each tab switches the view to show the corresponding list of layouts.
    *   **Why:** Verifies basic navigation within the screen.
    *   **Type:** Interaction

*   **T63:** `importCustomLayoutSuccess`
    *   **Target:** `LayoutManagerActivity`
    *   **Asserts:** Using the import function with a valid custom layout XML file (via a mocked SAF result) adds the new layout to the "Custom" tab list.
    *   **Why:** Validates the primary mechanism for users to extend the app with their own layouts.
    *   **Type:** Functional

*   **T64:** `importMalformedLayoutFailsGracefully`
    *   **Target:** `LayoutManagerActivity`
    *   **Asserts:** Attempting to import a malformed or invalid XML file shows an error message to the user and does not add anything to the layout list.
    *   **Why:** Ensures the app is robust against invalid user input.
    *   **Type:** Negative

*   **T65:** `deleteCustomLayoutFlow`
    *   **Target:** `LayoutManagerActivity`
    *   **Asserts:** Deleting a layout from the "Custom" tab shows a confirmation dialog, and upon confirmation, the layout is removed from the list.
    *   **Why:** Verifies user can manage their custom layouts.
    *   **Type:** Interaction

*   **T66:** `activeTabRestoresOnRotation`
    *   **Target:** `LayoutManagerActivity`
    *   **Asserts:** After switching to the "Custom" tab and rotating the device, the "Custom" tab remains selected.
    *   **Why:** Ensures a stable UI across configuration changes.
    *   **Type:** State Restoration

## ExtraKeysConfigActivity

*   **T67:** `rendersListOfKeys`
    *   **Target:** `ExtraKeysConfigActivity`
    *   **Asserts:** The activity displays a searchable list of extra keys.
    *   **Why:** Smoke test for this configuration screen.
    *   **Type:** Smoke

*   **T68:** `searchFiltersKeyList`
    *   **Target:** `ExtraKeysConfigActivity`
    *   **Asserts:** Entering text into the search bar filters the list to only show keys matching the query.
    *   **Why:** Verifies the search functionality.
    *   **Type:** Functional

*   **T69:** `resetActionShowsConfirmationAndResets`
    *   **Target:** `ExtraKeysConfigActivity`
    *   **Asserts:** Tapping "Reset" shows a confirmation dialog. Confirming restores the default extra keys configuration.
    *   **Why:** Provides a recovery mechanism for users.
    *   **Type:** Interaction

*   **T70:** `searchQueryRestoresOnRotation`
    *   **Target:** `ExtraKeysConfigActivity`
    *   **Asserts:** The search query text is preserved after a device rotation.
    *   **Why:** Prevents data loss on configuration change.
    *   **Type:** State Restoration

## BackupRestoreActivity

*   **T71:** `rendersAllExportImportButtons`
    *   **Target:** `BackupRestoreActivity`
    *   **Asserts:** The activity displays separate export and import buttons for each data category (Settings, Dictionary, Clipboard) and a button for a full ZIP backup.
    *   **Why:** Smoke test for the backup/restore screen.
    *   **Type:** Smoke

*   **T72:** `exportSettingsSuccess`
    *   **Target:** `BackupRestoreActivity`
    *   **Asserts:** Tapping "Export Settings" triggers the SAF file picker to save a JSON file.
    *   **Why:** Verifies the data export functionality.
    *   **Type:** Functional

*   **T73:** `importSettingsSuccess`
    *   **Target:** `BackupRestoreActivity`
    *   **Asserts:** Tapping "Import Settings" and providing a valid settings JSON file (via mocked SAF) results in a success message.
    *   **Why:** Verifies the data import functionality.
    *   **Type:** Functional

*   **T74:** `importMalformedSettingsJsonFails`
    *   **Target:** `BackupRestoreActivity`
    *   **Asserts:** Attempting to import a malformed or incorrect JSON file as settings shows an error dialog.
    *   **Why:** Ensures the import process is robust and handles invalid data gracefully.
    *   **Type:** Negative

*   **T75:** `importFullZipBackupSuccess`
    *   **Target:** `BackupRestoreActivity`
    *   **Asserts:** Importing a valid full ZIP backup restores all relevant data.
    *   **Why:** Validates the all-in-one backup and restore feature.
    *   **Type:** Functional

*   **T76:** `accessibilityForButtons`
    *   **Target:** `BackupRestoreActivity`
    *   **Asserts:** All import and export buttons have clear `contentDescription`s (e.g., "Export settings to a file").
    *   **Why:** Makes this critical data management feature accessible.
    *   **Type:** Accessibility

## DictionaryManagerActivity

*Note: These tests are for a View-based activity and would require the Espresso framework.*

*   **T77:** `rendersWordList`
    *   **Target:** `DictionaryManagerActivity`
    *   **Asserts:** The activity displays the list of user-added words.
    *   **Why:** Smoke test for the dictionary management screen.
    *   **Type:** Smoke
    *   **Status:** `[ESPRESSO_REQUIRED]`

*   **T78:** `addWordToList`
    *   **Target:** `DictionaryManagerActivity`
    *   **Asserts:** Using the "Add" functionality, a new word can be added to the user dictionary and appears in the list.
    *   **Why:** Core functionality for personalizing the dictionary.
    *   **Type:** Functional
    *   **Status:** `[ESPRESSO_REQUIRED]`

*   **T79:** `deleteWordFromList`
    *   **Target:** `DictionaryManagerActivity`
    *   **Asserts:** A word can be selected and deleted from the list, after which it no longer appears.
    *   **Why:** Core functionality for managing the dictionary.
    *   **Type:** Functional
    *   **Status:** `[ESPRESSO_REQUIRED]`

*   **T80:** `searchFiltersWordList`
    *   **Target:** `DictionaryManagerActivity`
    *   **Asserts:** Searching for a word filters the list to show only matching entries.
    *   **Why:** Improves usability for users with large custom dictionaries.
    *   **Type:** Functional
    *   **Status:** `[ESPRESSO_REQUIRED]`
