package tribixbite.keyboard2

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Properties
import tribixbite.keyboard2.theme.KeyboardTheme

/**
 * Modern settings activity for CleverKeys.
 *
 * Migrated from SettingsActivity.java with enhanced functionality:
 * - Modern Compose UI with Material Design 3
 * - Reactive settings with live preview
 * - Neural parameter configuration
 * - Enhanced version management
 * - Performance monitoring integration
 * - Accessibility improvements
 */
@OptIn(ExperimentalMaterial3Api::class)
class SettingsActivity : ComponentActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val TAG = "SettingsActivity"
    }

    // Configuration state
    private lateinit var config: Config
    private lateinit var prefs: SharedPreferences

    // Settings state for reactive UI
    private var neuralPredictionEnabled by mutableStateOf(true)
    private var beamWidth by mutableStateOf(8)
    private var maxLength by mutableStateOf(35)
    private var confidenceThreshold by mutableStateOf(0.1f)
    private var currentTheme by mutableStateOf(R.style.Dark)
    private var keyboardHeight by mutableStateOf(35)
    private var vibrationEnabled by mutableStateOf(false)
    private var debugEnabled by mutableStateOf(false)
    private var clipboardHistoryEnabled by mutableStateOf(true)
    private var autoCapitalizationEnabled by mutableStateOf(true)

    // Accessibility settings (Bug #373, #368, #377)
    private var stickyKeysEnabled by mutableStateOf(false)
    private var stickyKeysTimeout by mutableStateOf(5000) // milliseconds
    private var voiceGuidanceEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize configuration
        try {
            prefs = DirectBootAwarePreferences.get_shared_preferences(this)

            // Run config migration
            Config.migrate(prefs)

            // Initialize global config if not already initialized
            try {
                config = Config.globalConfig()
            } catch (e: IllegalStateException) {
                // Config not initialized yet, initialize it
                Config.initGlobalConfig(prefs, resources, null, null)
                config = Config.globalConfig()
            }

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error initializing settings", e)
            fallbackEncrypted()
            return
        }

        // Load current settings
        loadCurrentSettings()

        try {
            setContent {
                KeyboardTheme(darkTheme = true) {
                    SettingsScreen()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error setting up Compose UI", e)
            // Fallback to XML-based settings if Compose fails
            useLegacySettingsUI()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Register for preference changes
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        // Unregister preference listener (balanced with onResume)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        // Save all settings changes to protected storage
        DirectBootAwarePreferences.copy_preferences_to_protected_storage(this, prefs)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        // Handle preference changes for reactive updates
        when (key) {
            "neural_prediction_enabled" -> {
                neuralPredictionEnabled = prefs.getBoolean(key, true)
            }
            "neural_beam_width" -> {
                beamWidth = prefs.getInt(key, 8)
            }
            "neural_max_length" -> {
                maxLength = prefs.getInt(key, 35)
            }
            "neural_confidence_threshold" -> {
                confidenceThreshold = prefs.getFloat(key, 0.1f)
            }
            "theme" -> {
                currentTheme = prefs.getInt(key, R.style.Dark)
            }
            "keyboard_height_percent" -> {
                keyboardHeight = prefs.getInt(key, 35)
            }
            "vibration_enabled" -> {
                vibrationEnabled = prefs.getBoolean(key, false)
            }
            "debug_enabled" -> {
                debugEnabled = prefs.getBoolean(key, false)
                Logs.setDebugEnabled(debugEnabled)
            }
            "clipboard_history_enabled" -> {
                clipboardHistoryEnabled = prefs.getBoolean(key, true)
            }
            "auto_capitalization_enabled" -> {
                autoCapitalizationEnabled = prefs.getBoolean(key, true)
            }
            "sticky_keys_enabled" -> {
                stickyKeysEnabled = prefs.getBoolean(key, false)
            }
            "sticky_keys_timeout_ms" -> {
                stickyKeysTimeout = prefs.getInt(key, 5000)
            }
            "voice_guidance_enabled" -> {
                voiceGuidanceEnabled = prefs.getBoolean(key, false)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsScreen() {
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Text(
                text = stringResource(R.string.settings_title),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = stringResource(R.string.settings_description),
                fontSize = 14.sp,
                color = ComposeColor.Gray,
                lineHeight = 20.sp
            )

            // Neural Prediction Section
            SettingsSection(stringResource(R.string.settings_section_neural)) {
                SettingsSwitch(
                    title = stringResource(R.string.settings_neural_enable_title),
                    description = stringResource(R.string.settings_neural_enable_desc),
                    checked = neuralPredictionEnabled,
                    onCheckedChange = {
                        neuralPredictionEnabled = it
                        saveSetting("neural_prediction_enabled", it)
                    }
                )

                if (neuralPredictionEnabled) {
                    SettingsSlider(
                        title = stringResource(R.string.settings_neural_beam_width_title),
                        description = stringResource(R.string.settings_neural_beam_width_desc),
                        value = beamWidth.toFloat(),
                        valueRange = 1f..32f,
                        steps = 31,
                        onValueChange = {
                            beamWidth = it.toInt()
                            saveSetting("neural_beam_width", beamWidth)
                        },
                        displayValue = beamWidth.toString()
                    )

                    SettingsSlider(
                        title = stringResource(R.string.settings_neural_max_length_title),
                        description = stringResource(R.string.settings_neural_max_length_desc),
                        value = maxLength.toFloat(),
                        valueRange = 10f..50f,
                        steps = 40,
                        onValueChange = {
                            maxLength = it.toInt()
                            saveSetting("neural_max_length", maxLength)
                        },
                        displayValue = maxLength.toString()
                    )

                    SettingsSlider(
                        title = stringResource(R.string.settings_neural_confidence_title),
                        description = stringResource(R.string.settings_neural_confidence_desc),
                        value = confidenceThreshold,
                        valueRange = 0.0f..1.0f,
                        steps = 100,
                        onValueChange = {
                            confidenceThreshold = it
                            saveSetting("neural_confidence_threshold", confidenceThreshold)
                        },
                        displayValue = "%.3f".format(confidenceThreshold)
                    )

                    Button(
                        onClick = { openNeuralSettings() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.settings_neural_advanced_button))
                    }
                }
            }

            // Appearance Section
            SettingsSection(stringResource(R.string.settings_section_appearance)) {
                SettingsDropdown(
                    title = stringResource(R.string.settings_theme_title),
                    description = stringResource(R.string.settings_theme_desc),
                    options = listOf(
                        stringResource(R.string.settings_theme_system),
                        stringResource(R.string.settings_theme_light),
                        stringResource(R.string.settings_theme_dark),
                        stringResource(R.string.settings_theme_black)
                    ),
                    selectedIndex = getThemeIndex(currentTheme),
                    onSelectionChange = { index ->
                        val newTheme = getThemeFromIndex(index)
                        currentTheme = newTheme
                        saveSetting("theme", newTheme)
                    }
                )

                SettingsSlider(
                    title = "Keyboard Height",
                    description = "Adjust keyboard height as percentage of screen (20-60%).",
                    value = keyboardHeight.toFloat(),
                    valueRange = 20f..60f,
                    steps = 40,
                    onValueChange = {
                        keyboardHeight = it.toInt()
                        saveSetting("keyboard_height_percent", keyboardHeight)
                    },
                    displayValue = "${keyboardHeight}%"
                )
            }

            // Input Behavior Section
            SettingsSection("📝 Input Behavior") {
                SettingsSwitch(
                    title = "Auto-Capitalization",
                    description = "Automatically capitalize words at sentence start",
                    checked = autoCapitalizationEnabled,
                    onCheckedChange = {
                        autoCapitalizationEnabled = it
                        saveSetting("auto_capitalization_enabled", it)
                    }
                )

                SettingsSwitch(
                    title = "Clipboard History",
                    description = "Remember copied text for easy access",
                    checked = clipboardHistoryEnabled,
                    onCheckedChange = {
                        clipboardHistoryEnabled = it
                        saveSetting("clipboard_history_enabled", it)
                    }
                )

                SettingsSwitch(
                    title = "Vibration Feedback",
                    description = "Vibrate when keys are pressed",
                    checked = vibrationEnabled,
                    onCheckedChange = {
                        vibrationEnabled = it
                        saveSetting("vibration_enabled", it)
                    }
                )
            }

            // Accessibility Section (Fix for Bug #373, #368, #377)
            SettingsSection("♿ Accessibility") {
                SettingsSwitch(
                    title = "Sticky Keys",
                    description = "Enable single-handed typing with modifier latching (OFF → LATCHED → LOCKED)",
                    checked = stickyKeysEnabled,
                    onCheckedChange = {
                        stickyKeysEnabled = it
                        saveSetting("sticky_keys_enabled", it)
                    }
                )

                if (stickyKeysEnabled) {
                    SettingsSlider(
                        title = "Sticky Keys Timeout",
                        description = "Auto-release delay for latched modifiers (1-10 seconds)",
                        value = (stickyKeysTimeout / 1000f),
                        valueRange = 1f..10f,
                        steps = 9,
                        onValueChange = {
                            stickyKeysTimeout = (it * 1000).toInt()
                            saveSetting("sticky_keys_timeout_ms", stickyKeysTimeout)
                        },
                        displayValue = "${stickyKeysTimeout / 1000}s"
                    )
                }

                SettingsSwitch(
                    title = "Voice Guidance",
                    description = "Speak keys aloud for blind users (requires TTS)",
                    checked = voiceGuidanceEnabled,
                    onCheckedChange = {
                        voiceGuidanceEnabled = it
                        saveSetting("voice_guidance_enabled", it)

                        // Show restart prompt
                        if (it) {
                            Toast.makeText(this@SettingsActivity,
                                "Voice guidance will activate on keyboard restart",
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                Text(
                    text = "✓ Screen Reader (TalkBack) support is always enabled",
                    fontSize = 12.sp,
                    color = ComposeColor.Gray,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Advanced Section
            SettingsSection("🔧 Advanced") {
                SettingsSwitch(
                    title = "Debug Information",
                    description = "Show detailed logging and performance metrics",
                    checked = debugEnabled,
                    onCheckedChange = {
                        debugEnabled = it
                        saveSetting("debug_enabled", it)
                    }
                )

                Button(
                    onClick = { openCalibration() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🧪 Swipe Calibration")
                }
            }

            // Version and Actions Section
            SettingsSection("📋 Information & Actions") {
                VersionInfoCard()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { resetAllSettings() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text("🔄 Reset All")
                    }

                    Button(
                        onClick = { checkForUpdates() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("📥 Check Updates")
                    }
                }
            }
        }
    }

    // Composable helper components
    @Composable
    private fun SettingsSection(
        title: String,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                content()
            }
        }
    }

    @Composable
    private fun SettingsSwitch(
        title: String,
        description: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp
                )
                Text(
                    text = description,
                    color = ComposeColor.Gray,
                    fontSize = 12.sp
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }

    @Composable
    private fun SettingsSlider(
        title: String,
        description: String,
        value: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        steps: Int,
        onValueChange: (Float) -> Unit,
        displayValue: String
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp
                )
                Text(
                    text = displayValue,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = description,
                color = ComposeColor.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }

    @Composable
    private fun SettingsDropdown(
        title: String,
        description: String,
        options: List<String>,
        selectedIndex: Int,
        onSelectionChange: (Int) -> Unit
    ) {
        var expanded by remember { mutableStateOf(false) }

        Column {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp
            )
            Text(
                text = description,
                color = ComposeColor.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = options[selectedIndex],
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ComposeColor.White,
                        unfocusedTextColor = ComposeColor.White
                    )
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEachIndexed { index, option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                onSelectionChange(index)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun VersionInfoCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = ComposeColor(0xFF2A2A2A)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "📱 Version Information",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                val versionInfo = loadVersionInfo()
                Text(
                    text = "Build: ${versionInfo.getProperty("build_number", "unknown")}\n" +
                           "Commit: ${versionInfo.getProperty("commit", "unknown")}\n" +
                           "Built: ${versionInfo.getProperty("build_date", "unknown")}",
                    color = ComposeColor.Gray,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }

    // Helper functions
    private fun loadCurrentSettings() {
        neuralPredictionEnabled = prefs.getBoolean("neural_prediction_enabled", true)
        beamWidth = prefs.getInt("neural_beam_width", 8)
        maxLength = prefs.getInt("neural_max_length", 35)
        confidenceThreshold = prefs.getFloat("neural_confidence_threshold", 0.1f)
        currentTheme = prefs.getInt("theme", R.style.Dark)
        keyboardHeight = prefs.getInt("keyboard_height_percent", 35)
        vibrationEnabled = prefs.getBoolean("vibration_enabled", false)
        debugEnabled = prefs.getBoolean("debug_enabled", false)
        clipboardHistoryEnabled = prefs.getBoolean("clipboard_history_enabled", true)
        autoCapitalizationEnabled = prefs.getBoolean("auto_capitalization_enabled", true)
        stickyKeysEnabled = prefs.getBoolean("sticky_keys_enabled", false)
        stickyKeysTimeout = prefs.getInt("sticky_keys_timeout_ms", 5000)
        voiceGuidanceEnabled = prefs.getBoolean("voice_guidance_enabled", false)
    }

    private fun saveSetting(key: String, value: Any) {
        lifecycleScope.launch {
            try {
                val editor = prefs.edit()
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Long -> editor.putLong(key, value)
                }
                editor.apply()

                // Update configuration object
                updateConfigFromSettings()

                android.util.Log.d(TAG, "Setting saved: $key = $value")

            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error saving setting: $key = $value", e)
                Toast.makeText(this@SettingsActivity,
                    "Error saving setting: ${e.message}",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateConfigFromSettings() {
        // Update global config from current settings
        config.apply {
            theme = currentTheme
            keyboardHeightPercent = keyboardHeight
            vibrate_custom = vibrationEnabled
            neural_beam_width = beamWidth
            neural_max_length = maxLength
            neural_confidence_threshold = confidenceThreshold
        }
    }

    private fun getThemeIndex(theme: Int): Int {
        return when (theme) {
            R.style.Light -> 1
            R.style.Dark -> 2
            R.style.Black -> 3
            else -> 2 // Default to Dark
        }
    }

    private fun getThemeFromIndex(index: Int): Int {
        return when (index) {
            1 -> R.style.Light
            2 -> R.style.Dark
            3 -> R.style.Black
            else -> R.style.Dark
        }
    }

    private fun loadVersionInfo(): Properties {
        val props = Properties()
        try {
            val reader = BufferedReader(
                InputStreamReader(
                    resources.openRawResource(
                        resources.getIdentifier("version_info", "raw", packageName)
                    )
                )
            )
            props.load(reader)
            reader.close()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to load version info", e)
            // Set default values
            props.setProperty("build_number", "dev")
            props.setProperty("commit", "unknown")
            props.setProperty("build_date", "unknown")
        }
        return props
    }

    private fun openNeuralSettings() {
        startActivity(Intent(this, NeuralSettingsActivity::class.java))
    }

    private fun openCalibration() {
        startActivity(Intent(this, SwipeCalibrationActivity::class.java))
    }

    private fun resetAllSettings() {
        lifecycleScope.launch {
            android.app.AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Reset Settings")
                .setMessage("This will reset all settings to defaults. Continue?")
                .setPositiveButton("Reset") { _, _ ->
                    // Reset all settings to safe defaults
                    val editor = prefs.edit()
                    editor.clear()

                    // Set essential defaults to prevent crashes
                    editor.putBoolean("neural_prediction_enabled", true)
                    editor.putInt("neural_beam_width", 8)
                    editor.putInt("neural_max_length", 35)
                    editor.putFloat("neural_confidence_threshold", 0.1f)
                    editor.putInt("theme", R.style.Dark)
                    editor.putInt("keyboard_height_percent", 35)
                    editor.putBoolean("vibration_enabled", false)
                    editor.putBoolean("debug_enabled", false)
                    editor.putBoolean("clipboard_history_enabled", true)
                    editor.putBoolean("auto_capitalization_enabled", true)
                    editor.putBoolean("sticky_keys_enabled", false)
                    editor.putInt("sticky_keys_timeout_ms", 5000)
                    editor.putBoolean("voice_guidance_enabled", false)

                    editor.apply()

                    // Reset UI state
                    loadCurrentSettings()

                    Toast.makeText(this@SettingsActivity,
                        "All settings reset to defaults",
                        Toast.LENGTH_SHORT).show()

                    // Recreate activity to refresh UI
                    recreate()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            try {
                // Check for APK updates in common locations
                val possibleLocations = arrayOf(
                    "/sdcard/Download/cleverkeys-debug.apk",
                    "/storage/emulated/0/Download/cleverkeys-debug.apk",
                    "/sdcard/Download/tribixbite.keyboard2.debug.apk",
                    "/storage/emulated/0/Download/tribixbite.keyboard2.debug.apk",
                    "/sdcard/unexpected/debug-kb.apk",
                    "/storage/emulated/0/unexpected/debug-kb.apk",
                    "${getExternalFilesDir(null)?.parent}/files/home/storage/downloads/cleverkeys-debug.apk"
                )

                var updateApk: File? = null
                for (location in possibleLocations) {
                    val file = File(location)
                    if (file.exists() && file.canRead()) {
                        updateApk = file
                        android.util.Log.d(TAG, "Found update APK at: $location")
                        break
                    }
                }

                if (updateApk != null) {
                    showUpdateDialog(updateApk)
                } else {
                    showNoUpdateDialog()
                }

            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error checking for updates", e)
                Toast.makeText(this@SettingsActivity,
                    "Error checking for updates: ${e.message}",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showUpdateDialog(apkFile: File) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Update Available")
            .setMessage("APK found at:\n${apkFile.name}\n\nSize: ${apkFile.length() / 1024} KB")
            .setPositiveButton("Install") { _, _ ->
                installUpdate(apkFile)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNoUpdateDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("No Updates Found")
            .setMessage("Place APK file in one of these locations:\n\n" +
                       "• /sdcard/Download/cleverkeys-debug.apk\n" +
                       "• /sdcard/Download/tribixbite.keyboard2.debug.apk\n" +
                       "• /sdcard/unexpected/debug-kb.apk\n\n" +
                       "Or run from Termux:\n" +
                       "  ./install.sh")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun installUpdate(apkFile: File) {
        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Use FileProvider for Android 7.0+
                androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    apkFile
                )
            } else {
                android.net.Uri.fromFile(apkFile)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            startActivity(intent)
            android.util.Log.d(TAG, "Launched installer for: ${apkFile.absolutePath}")

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error installing update", e)

            // Fallback: try to copy to accessible location
            try {
                val publicApk = File("/sdcard/Download/cleverkeys-update.apk")
                apkFile.copyTo(publicApk, overwrite = true)

                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        android.net.Uri.fromFile(publicApk),
                        "application/vnd.android.package-archive"
                    )
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(fallbackIntent)

                Toast.makeText(this,
                    "Opening installer (copied to Downloads)",
                    Toast.LENGTH_SHORT).show()

            } catch (fallbackError: Exception) {
                Toast.makeText(this,
                    "Install failed: ${e.message}\nTry installing manually from file manager",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun fallbackEncrypted() {
        // Handle direct boot mode failure
        android.util.Log.w(TAG, "Settings unavailable in direct boot mode")
        finish()
    }

    /**
     * Fallback to legacy XML-based settings UI if Compose fails
     */
    private fun useLegacySettingsUI() {
        try {
            // Create simple scrollable settings UI with XML views
            val scrollView = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            }

            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Title
            val title = TextView(this).apply {
                text = "⚙️ CleverKeys Settings"
                textSize = 24f
                setPadding(0, 0, 0, 24)
                setTextColor(android.graphics.Color.WHITE)
            }
            layout.addView(title)

            // Neural prediction toggle
            val neuralSwitch = Switch(this).apply {
                text = "Enable Neural Swipe Prediction"
                isChecked = neuralPredictionEnabled
                setPadding(0, 16, 0, 16)
                setOnCheckedChangeListener { _, isChecked ->
                    neuralPredictionEnabled = isChecked
                    saveSetting("neural_prediction_enabled", isChecked)
                }
            }
            layout.addView(neuralSwitch)

            // Beam width setting
            val beamWidthLabel = TextView(this).apply {
                text = "Beam Width: $beamWidth"
                setPadding(0, 16, 0, 8)
                setTextColor(android.graphics.Color.WHITE)
            }
            layout.addView(beamWidthLabel)

            val beamWidthSeekBar = SeekBar(this).apply {
                max = 31 // 1-32 range
                progress = beamWidth - 1
                setPadding(0, 0, 0, 16)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        beamWidth = progress + 1
                        beamWidthLabel.text = "Beam Width: $beamWidth"
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        saveSetting("neural_beam_width", beamWidth)
                    }
                })
            }
            layout.addView(beamWidthSeekBar)

            // Open advanced neural settings button
            val advancedButton = Button(this).apply {
                text = "🔧 Advanced Neural Settings"
                setOnClickListener {
                    openNeuralSettings()
                }
            }
            layout.addView(advancedButton)

            // Calibration button
            val calibrationButton = Button(this).apply {
                text = "📊 Neural Calibration"
                setOnClickListener {
                    openCalibration()
                }
            }
            layout.addView(calibrationButton)

            // System info
            val versionInfo = TextView(this).apply {
                text = "CleverKeys v2.0 (Kotlin)\n\nNote: Compose UI unavailable. Using legacy settings."
                setPadding(0, 32, 0, 0)
                textSize = 12f
                setTextColor(android.graphics.Color.GRAY)
            }
            layout.addView(versionInfo)

            scrollView.addView(layout)
            setContentView(scrollView)

            android.util.Log.i(TAG, "Legacy settings UI initialized successfully")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to create legacy UI", e)
            // If even this fails, show error and close
            Toast.makeText(this, "Settings UI failed to load: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
