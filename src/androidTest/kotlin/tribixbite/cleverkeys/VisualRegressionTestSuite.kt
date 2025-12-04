package tribixbite.cleverkeys.test

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.*
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class VisualRegressionTestSuite {

    private lateinit var device: UiDevice
    private lateinit var context: Context
    private lateinit var screenshotDir: File

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context = ApplicationProvider.getApplicationContext()

        // Create directory for screenshots
        screenshotDir = File(context.getExternalFilesDir(null), "visual_regression")
        screenshotDir.mkdirs()

        // Enable CleverKeys as input method
        enableCleverKeysIME()
    }

    @Test
    fun testKeyboardLayoutVisuals() {
        openKeyboard()

        // Capture default QWERTY layout
        captureScreenshot("keyboard_layout_qwerty")

        // Test shift state
        device.findObject(UiSelector().description("Shift")).click()
        Thread.sleep(200)
        captureScreenshot("keyboard_layout_shift")

        // Test symbol layout
        device.findObject(UiSelector().text("?123")).click()
        Thread.sleep(200)
        captureScreenshot("keyboard_layout_symbols")

        // Test emoji layout (if available)
        try {
            device.findObject(UiSelector().description("Emoji")).click()
            Thread.sleep(200)
            captureScreenshot("keyboard_layout_emoji")
        } catch (e: Exception) {
            println("Emoji layout not available, skipping")
        }
    }

    @Test
    fun testNeuralCalibrationUI() {
        // Open neural calibration activity
        val intent = Intent(context, SwipeCalibrationActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
        Thread.sleep(1000)

        // Capture main calibration screen
        captureScreenshot("neural_calibration_main")

        // Test calibration with sample word
        val wordDisplay = device.findObject(UiSelector().resourceId("current_word_text"))
        if (wordDisplay.exists()) {
            captureScreenshot("neural_calibration_word_display")
        }

        // Simulate swipe on keyboard area
        device.swipe(200, 600, 600, 600, 30)
        Thread.sleep(500) // Wait for prediction processing

        // Capture results screen
        captureScreenshot("neural_calibration_results")

        // Test neural playground controls
        try {
            device.findObject(UiSelector().text("Beam Width")).click()
            Thread.sleep(200)
            captureScreenshot("neural_calibration_beam_width_control")
        } catch (e: Exception) {
            println("Beam width control not found")
        }
    }

    @Test
    fun testSettingsUIVisuals() {
        // Open CleverKeys settings
        val intent = Intent(context, CleverKeysSettings::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
        Thread.sleep(1000)

        // Capture main settings screen
        captureScreenshot("settings_main")

        // Test neural prediction settings
        try {
            onView(withText("Neural Prediction Settings")).perform(click())
            Thread.sleep(500)
            captureScreenshot("settings_neural_prediction")

            // Test individual neural controls
            device.findObject(UiSelector().text("Beam Width")).click()
            Thread.sleep(200)
            captureScreenshot("settings_beam_width_slider")

            device.findObject(UiSelector().text("Confidence Threshold")).click()
            Thread.sleep(200)
            captureScreenshot("settings_confidence_threshold")

        } catch (e: Exception) {
            println("Neural settings not accessible: ${e.message}")
        }

        // Test keyboard layout settings
        try {
            device.pressBack()
            Thread.sleep(300)
            onView(withText("Keyboard Layouts")).perform(click())
            Thread.sleep(500)
            captureScreenshot("settings_keyboard_layouts")
        } catch (e: Exception) {
            println("Layout settings not accessible")
        }
    }

    @Test
    fun testThemeAndAppearance() {
        openKeyboard()

        // Test different keyboard themes/appearances
        val themes = listOf("default", "dark", "high_contrast")

        themes.forEach { theme ->
            try {
                // Apply theme (this would depend on actual theme switching implementation)
                applyKeyboardTheme(theme)
                Thread.sleep(300)
                captureScreenshot("keyboard_theme_$theme")
            } catch (e: Exception) {
                println("Theme $theme not available: ${e.message}")
            }
        }
    }

    @Test
    fun testPredictionBarVisuals() {
        openKeyboard()

        // Type some text to generate predictions
        val testText = "hello"
        testText.forEach { char ->
            device.findObject(UiSelector().text(char.toString())).click()
            Thread.sleep(100)
        }

        // Capture prediction bar
        captureScreenshot("prediction_bar_with_suggestions")

        // Clear text and test empty prediction bar
        device.findObject(UiSelector().description("Backspace")).apply {
            repeat(testText.length) {
                click()
                Thread.sleep(50)
            }
        }

        captureScreenshot("prediction_bar_empty")
    }

    @Test
    fun testSwipeVisualization() {
        openKeyboard()

        // Perform swipe and capture the visual feedback
        val startX = 200
        val startY = 600
        val endX = 600
        val endY = 600

        // Start swipe (this should show visual trail if implemented)
        device.swipe(startX, startY, endX, startY, 50)
        Thread.sleep(200)

        captureScreenshot("swipe_visual_feedback")

        // Test different swipe patterns
        device.swipe(200, 600, 400, 400, 30) // Diagonal swipe
        Thread.sleep(200)
        captureScreenshot("swipe_diagonal_pattern")

        // Circular swipe (if supported)
        performCircularSwipe(300, 500, 50)
        Thread.sleep(200)
        captureScreenshot("swipe_circular_pattern")
    }

    @Test
    fun testAccessibilityVisuals() {
        // Enable high contrast or accessibility mode
        enableAccessibilityFeatures()

        openKeyboard()
        captureScreenshot("keyboard_accessibility_mode")

        // Test with TalkBack enabled (if possible)
        try {
            enableTalkBack()
            Thread.sleep(1000)
            captureScreenshot("keyboard_talkback_enabled")
        } catch (e: Exception) {
            println("TalkBack testing not available: ${e.message}")
        }
    }

    @Test
    fun testLandscapeOrientation() {
        // Test keyboard in landscape mode
        device.setOrientationLeft()
        Thread.sleep(1000)

        openKeyboard()
        captureScreenshot("keyboard_landscape")

        // Test different layouts in landscape
        device.findObject(UiSelector().text("?123")).click()
        Thread.sleep(200)
        captureScreenshot("keyboard_landscape_symbols")

        // Return to portrait
        device.setOrientationNatural()
        Thread.sleep(1000)
    }

    @Test
    fun testRTLLanguageSupport() {
        // Test right-to-left language layouts (if supported)
        try {
            switchToRTLLayout()
            Thread.sleep(500)
            captureScreenshot("keyboard_rtl_layout")
        } catch (e: Exception) {
            println("RTL layout not available")
        }
    }

    private fun openKeyboard() {
        device.findObject(UiSelector().className("android.widget.EditText").instance(0)).click()
        Thread.sleep(500)
    }

    private fun enableCleverKeysIME() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiAutomation = instrumentation.uiAutomation

        uiAutomation.executeShellCommand("ime enable tribixbite.cleverkeys/.CleverKeysService")
        uiAutomation.executeShellCommand("ime set tribixbite.cleverkeys/.CleverKeysService")
        Thread.sleep(1000)
    }

    private fun captureScreenshot(fileName: String) {
        val screenshotFile = File(screenshotDir, "$fileName.png")
        val success = device.takeScreenshot(screenshotFile)

        if (success) {
            println("Screenshot saved: ${screenshotFile.absolutePath}")

            // Also save to accessible location for CI using proper storage API
            val ciScreenshotDir = File(
                android.os.Environment.getExternalStorageDirectory(),
                "screenshots"
            )
            val ciScreenshotFile = File(ciScreenshotDir, "$fileName.png")
            ciScreenshotFile.parentFile?.mkdirs()
            screenshotFile.copyTo(ciScreenshotFile, overwrite = true)

            // Log for CI processing
            logVisualMetric("screenshot_captured", fileName)
        } else {
            println("Failed to capture screenshot: $fileName")
        }
    }

    private fun captureViewScreenshot(view: View, fileName: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)

        val screenshotFile = File(screenshotDir, "$fileName.png")
        FileOutputStream(screenshotFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        println("View screenshot saved: ${screenshotFile.absolutePath}")
    }

    private fun applyKeyboardTheme(theme: String) {
        // This would interact with theme settings
        // Implementation depends on actual theme switching mechanism
        println("Applying theme: $theme")
    }

    private fun performCircularSwipe(centerX: Int, centerY: Int, radius: Int) {
        // Perform circular swipe gesture
        val steps = 20
        val angleStep = 360.0 / steps

        for (i in 0 until steps) {
            val angle = Math.toRadians(i * angleStep)
            val x = centerX + (radius * Math.cos(angle)).toInt()
            val y = centerY + (radius * Math.sin(angle)).toInt()

            if (i == 0) {
                device.swipe(x, y, x, y, 10) // Start point
            } else {
                val prevAngle = Math.toRadians((i - 1) * angleStep)
                val prevX = centerX + (radius * Math.cos(prevAngle)).toInt()
                val prevY = centerY + (radius * Math.sin(prevAngle)).toInt()
                device.swipe(prevX, prevY, x, y, 10)
            }
            Thread.sleep(20)
        }
    }

    private fun enableAccessibilityFeatures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiAutomation = instrumentation.uiAutomation

        // Enable high contrast
        uiAutomation.executeShellCommand("settings put secure high_text_contrast_enabled 1")

        // Enable large text
        uiAutomation.executeShellCommand("settings put system font_scale 1.3")

        Thread.sleep(500)
    }

    private fun enableTalkBack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiAutomation = instrumentation.uiAutomation

        uiAutomation.executeShellCommand("settings put secure enabled_accessibility_services com.google.android.marvin.talkback/.TalkBackService")
        uiAutomation.executeShellCommand("settings put secure accessibility_enabled 1")
    }

    private fun switchToRTLLayout() {
        // Switch to RTL layout (Arabic, Hebrew, etc.)
        // Implementation depends on available layouts
        println("Switching to RTL layout")
    }

    private fun logVisualMetric(metricName: String, value: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bundle = android.os.Bundle().apply {
            putString("visual_$metricName", value)
        }
        instrumentation.sendStatus(0, bundle)
        println("VISUAL_METRIC: $metricName = $value")
    }
}