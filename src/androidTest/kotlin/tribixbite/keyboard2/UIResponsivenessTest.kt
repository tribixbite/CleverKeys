package tribixbite.keyboard2.test

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityTestRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.keyboard2.*
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class UIResponsivenessTest {

    @get:Rule
    val activityRule = ActivityTestRule(LauncherActivity::class.java)

    private lateinit var device: UiDevice
    private lateinit var context: Context

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context = ApplicationProvider.getApplicationContext()

        // Ensure CleverKeys is enabled as input method
        enableCleverKeysIME()
    }

    @Test
    fun testKeyboardOpeningSpeed() {
        // Open a text input to trigger keyboard
        val openTime = measureTimeMillis {
            device.findObject(UiSelector().className("android.widget.EditText").instance(0)).click()

            // Wait for keyboard to appear
            device.wait(
                android.support.test.uiautomator.Until.hasObject(
                    UiSelector().packageName("tribixbite.keyboard2")
                ), 1000
            )
        }

        // Keyboard should open within 500ms
        assert(openTime < 500) { "Keyboard opening too slow: ${openTime}ms" }
        logUIMetric("keyboard_opening_time", openTime)
    }

    @Test
    fun testKeyPressResponsiveness() {
        openKeyboard()

        val keyPressTime = measureTimeMillis {
            // Simulate rapid key presses
            val keys = listOf("h", "e", "l", "l", "o")
            keys.forEach { key ->
                device.findObject(UiSelector().text(key)).click()
                Thread.sleep(50) // Simulate realistic typing speed
            }
        }

        // Should handle rapid typing smoothly
        val avgTimePerKey = keyPressTime / 5
        assert(avgTimePerKey < 16) { "Key press response too slow: ${avgTimePerKey}ms" }
        logUIMetric("key_press_avg_time", avgTimePerKey)
    }

    @Test
    fun testSwipeGestureResponsiveness() {
        openKeyboard()

        val swipeTime = measureTimeMillis {
            // Perform swipe gesture on keyboard
            device.swipe(200, 800, 600, 800, 20) // Horizontal swipe

            // Wait for prediction to appear
            Thread.sleep(200)
        }

        // Swipe should be processed quickly
        assert(swipeTime < 100) { "Swipe gesture too slow: ${swipeTime}ms" }
        logUIMetric("swipe_gesture_time", swipeTime)
    }

    @Test
    fun testSettingsUIResponsiveness() {
        // Open CleverKeys settings
        val settingsOpenTime = measureTimeMillis {
            val intent = Intent(context, CleverKeysSettings::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)

            // Wait for settings to load
            Thread.sleep(500)
        }

        assert(settingsOpenTime < 1000) { "Settings opening too slow: ${settingsOpenTime}ms" }

        // Test neural settings responsiveness
        val neuralSettingsTime = measureTimeMillis {
            onView(withText("Neural Prediction Settings")).perform(click())
            Thread.sleep(200)

            // Adjust beam width slider
            onView(withText("Beam Width")).perform(click())
            Thread.sleep(100)
        }

        assert(neuralSettingsTime < 300) { "Neural settings too slow: ${neuralSettingsTime}ms" }
        logUIMetric("settings_responsiveness", neuralSettingsTime)
    }

    @Test
    fun testCalibrationUIPerformance() {
        // Open neural calibration
        val calibrationTime = measureTimeMillis {
            val intent = Intent(context, SwipeCalibrationActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)

            // Wait for calibration UI to load
            Thread.sleep(1000)
        }

        assert(calibrationTime < 1500) { "Calibration loading too slow: ${calibrationTime}ms" }

        // Test calibration interactions
        val interactionTime = measureTimeMillis {
            // Simulate calibration swipe
            device.swipe(200, 600, 600, 600, 30)
            Thread.sleep(300) // Wait for neural processing

            // Check for prediction results
            device.findObject(UiSelector().textContains("Score:"))
        }

        assert(interactionTime < 500) { "Calibration interaction too slow: ${interactionTime}ms" }
        logUIMetric("calibration_interaction_time", interactionTime)
    }

    @Test
    fun testLayoutSwitchingSpeed() {
        openKeyboard()

        val layoutSwitchTime = measureTimeMillis {
            // Switch to symbols layout
            device.findObject(UiSelector().text("?123")).click()
            Thread.sleep(100)

            // Switch back to letters
            device.findObject(UiSelector().text("ABC")).click()
            Thread.sleep(100)
        }

        // Layout switching should be instant
        assert(layoutSwitchTime < 200) { "Layout switching too slow: ${layoutSwitchTime}ms" }
        logUIMetric("layout_switch_time", layoutSwitchTime)
    }

    @Test
    fun testMemoryPressureHandling() {
        // Simulate memory pressure by opening multiple apps
        repeat(5) {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Thread.sleep(200)
        }

        // Return to keyboard and test responsiveness under pressure
        openKeyboard()

        val pressureResponseTime = measureTimeMillis {
            device.findObject(UiSelector().text("h")).click()
            device.findObject(UiSelector().text("i")).click()
            Thread.sleep(100)
        }

        // Should remain responsive under memory pressure
        assert(pressureResponseTime < 300) { "Poor performance under memory pressure: ${pressureResponseTime}ms" }
        logUIMetric("memory_pressure_response", pressureResponseTime)
    }

    @Test
    fun testOrientationChangeHandling() {
        openKeyboard()

        val orientationChangeTime = measureTimeMillis {
            // Rotate to landscape
            device.setOrientationLeft()
            Thread.sleep(500)

            // Verify keyboard still works
            device.findObject(UiSelector().text("h")).click()

            // Rotate back to portrait
            device.setOrientationNatural()
            Thread.sleep(500)

            // Verify keyboard still works
            device.findObject(UiSelector().text("i")).click()
        }

        // Orientation changes should be handled smoothly
        assert(orientationChangeTime < 2000) { "Orientation change too slow: ${orientationChangeTime}ms" }
        logUIMetric("orientation_change_time", orientationChangeTime)
    }

    @Test
    fun testContinuousTypingPerformance() {
        openKeyboard()

        val continuousTypingTime = measureTimeMillis {
            // Simulate continuous typing for 30 seconds
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 30000) {
                // Type "hello world " repeatedly
                val words = listOf("h", "e", "l", "l", "o", " ", "w", "o", "r", "l", "d", " ")
                words.forEach { key ->
                    device.findObject(UiSelector().text(key)).click()
                    Thread.sleep(50) // Realistic typing speed
                }
            }
        }

        // Should maintain performance during continuous use
        val avgTimePerChar = continuousTypingTime / (30 * 12) // 30 seconds * 12 chars per cycle
        assert(avgTimePerChar < 20) { "Continuous typing performance degraded: ${avgTimePerChar}ms per char" }
        logUIMetric("continuous_typing_avg", avgTimePerChar)
    }

    private fun openKeyboard() {
        // Open any text input to trigger keyboard
        device.findObject(UiSelector().className("android.widget.EditText").instance(0)).click()
        Thread.sleep(300) // Wait for keyboard to appear
    }

    private fun enableCleverKeysIME() {
        // Enable CleverKeys as input method via shell commands
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiAutomation = instrumentation.uiAutomation

        uiAutomation.executeShellCommand("ime enable tribixbite.keyboard2/.CleverKeysService")
        uiAutomation.executeShellCommand("ime set tribixbite.keyboard2/.CleverKeysService")

        Thread.sleep(1000) // Wait for IME to be set
    }

    private fun logUIMetric(metricName: String, value: Long) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bundle = android.os.Bundle().apply {
            putLong("ui_$metricName", value)
        }
        instrumentation.sendStatus(0, bundle)
        println("UI_METRIC: $metricName = ${value}ms")
    }
}