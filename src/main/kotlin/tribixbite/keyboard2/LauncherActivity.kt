package tribixbite.keyboard2

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.BounceInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Cute raccoon-themed launcher activity for CleverKeys.
 *
 * Features:
 * - Animated cute raccoon mascot
 * - Clear setup instructions
 * - Quick access to system keyboard settings
 */
class LauncherActivity : Activity() {

    companion object {
        private const val TAG = "LauncherActivity"
    }

    private lateinit var raccoonView: RaccoonAnimationView
    private var animatorSet: AnimatorSet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            createUI()
            Log.i(TAG, "LauncherActivity created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating LauncherActivity", e)
        }
    }

    override fun onResume() {
        super.onResume()
        startAnimations()
    }

    override fun onPause() {
        super.onPause()
        stopAnimations()
    }

    override fun onDestroy() {
        super.onDestroy()
        animatorSet?.cancel()
    }

    private fun createUI() {
        val density = resources.displayMetrics.density

        // Root scroll view for small screens
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(0xFF1A1A2E.toInt()) // Dark blue-purple background
            isFillViewport = true
        }

        // Main container
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                (32 * density).toInt(),
                (48 * density).toInt(),
                (32 * density).toInt(),
                (32 * density).toInt()
            )
        }

        // Cute raccoon animation view
        raccoonView = RaccoonAnimationView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (200 * density).toInt(),
                (200 * density).toInt()
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (32 * density).toInt()
            }
        }
        mainLayout.addView(raccoonView)

        // App title
        val titleText = TextView(this).apply {
            text = "CleverKeys"
            setTextColor(0xFFE94560.toInt()) // Vibrant pink/red
            textSize = 32f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (24 * density).toInt()
            }
        }
        mainLayout.addView(titleText)

        // Instructions text
        val instructionsText = TextView(this).apply {
            text = "This application is a virtual keyboard. Go to the system settings by clicking on the button below and enable CleverKeys."
            setTextColor(0xFFE0E0E0.toInt()) // Light gray
            textSize = 16f
            gravity = Gravity.CENTER
            setLineSpacing(8f, 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (32 * density).toInt()
            }
        }
        mainLayout.addView(instructionsText)

        // Enable keyboard button
        val enableButton = Button(this).apply {
            text = "⚙️ Enable Keyboard"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            isAllCaps = false

            // Create gradient background
            val gradientDrawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 24 * density
                colors = intArrayOf(0xFFE94560.toInt(), 0xFF0F3460.toInt())
                orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
            }
            background = gradientDrawable

            setPadding(
                (32 * density).toInt(),
                (16 * density).toInt(),
                (32 * density).toInt(),
                (16 * density).toInt()
            )

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (24 * density).toInt()
            }

            setOnClickListener {
                launchKeyboardSettings()
            }
        }
        mainLayout.addView(enableButton)

        // Settings button (secondary)
        val settingsButton = Button(this).apply {
            text = "🎛️ App Settings"
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 14f
            isAllCaps = false

            // Outline style button
            val outlineDrawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 16 * density
                setStroke((2 * density).toInt(), 0xFFE94560.toInt())
                setColor(Color.TRANSPARENT)
            }
            background = outlineDrawable

            setPadding(
                (24 * density).toInt(),
                (12 * density).toInt(),
                (24 * density).toInt(),
                (12 * density).toInt()
            )

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }

            setOnClickListener {
                launchAppSettings()
            }
        }
        mainLayout.addView(settingsButton)

        scrollView.addView(mainLayout)
        setContentView(scrollView)
    }

    private fun startAnimations() {
        // Bounce animation for raccoon
        val bounceY = ObjectAnimator.ofFloat(raccoonView, "translationY", 0f, -20f, 0f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = BounceInterpolator()
        }

        // Gentle rotation for playfulness
        val tilt = ObjectAnimator.ofFloat(raccoonView, "rotation", -5f, 5f, -5f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }

        // Eye blink animation (scale raccoon slightly)
        val blink = ObjectAnimator.ofFloat(raccoonView, "scaleY", 1f, 0.95f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            startDelay = 2000
        }

        animatorSet = AnimatorSet().apply {
            playTogether(bounceY, tilt, blink)
            start()
        }

        // Start internal raccoon animations
        raccoonView.startBlinking()
    }

    private fun stopAnimations() {
        animatorSet?.cancel()
        raccoonView.stopBlinking()
    }

    private fun launchKeyboardSettings() {
        try {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            Log.d(TAG, "Launched keyboard settings")
        } catch (e: Exception) {
            Log.e(TAG, "Error launching keyboard settings", e)
            android.widget.Toast.makeText(
                this,
                "Could not open keyboard settings",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun launchAppSettings() {
        try {
            startActivity(Intent(this, SettingsActivity::class.java))
            Log.d(TAG, "Launched app settings")
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app settings", e)
        }
    }
}

/**
 * Custom view that draws and animates a cute raccoon character.
 */
class RaccoonAnimationView(context: Context) : View(context) {

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8B7355.toInt() // Brown-gray fur color
        style = Paint.Style.FILL
    }

    private val darkFurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4A4A4A.toInt() // Dark gray for mask and ears
        style = Paint.Style.FILL
    }

    private val lightFurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD4C4B0.toInt() // Light cream for snout
        style = Paint.Style.FILL
    }

    private val eyeWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    private val eyePupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2C2C2C.toInt()
        style = Paint.Style.FILL
    }

    private val eyeHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    private val nosePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3D3D3D.toInt()
        style = Paint.Style.FILL
    }

    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FF69B4.toInt() // Semi-transparent pink
        style = Paint.Style.FILL
    }

    // Blink animation state
    private var blinkProgress = 0f
    private var isBlinking = false
    private val blinkHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val blinkRunnable = object : Runnable {
        override fun run() {
            // Quick blink animation
            animateBlink()
            // Schedule next blink (random interval 2-5 seconds)
            blinkHandler.postDelayed(this, (2000..5000).random().toLong())
        }
    }

    fun startBlinking() {
        isBlinking = true
        blinkHandler.postDelayed(blinkRunnable, 1500)
    }

    fun stopBlinking() {
        isBlinking = false
        blinkHandler.removeCallbacks(blinkRunnable)
    }

    private fun animateBlink() {
        val animator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 150
            addUpdateListener {
                blinkProgress = it.animatedValue as Float
                invalidate()
            }
        }
        animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val size = minOf(width, height) * 0.4f

        // Draw ears (dark gray triangular shapes)
        drawEars(canvas, centerX, centerY, size)

        // Draw face (main brown-gray circle)
        canvas.drawCircle(centerX, centerY, size, facePaint)

        // Draw mask (dark patches around eyes)
        drawMask(canvas, centerX, centerY, size)

        // Draw snout (light cream area)
        drawSnout(canvas, centerX, centerY, size)

        // Draw eyes
        drawEyes(canvas, centerX, centerY, size)

        // Draw nose
        val noseY = centerY + size * 0.2f
        canvas.drawCircle(centerX, noseY, size * 0.12f, nosePaint)

        // Draw cute blush marks
        drawBlush(canvas, centerX, centerY, size)

        // Draw whisker dots
        drawWhiskers(canvas, centerX, centerY, size)
    }

    private fun drawEars(canvas: Canvas, centerX: Float, centerY: Float, size: Float) {
        val earSize = size * 0.5f
        val earOffset = size * 0.7f

        // Left ear
        val leftEarPath = Path().apply {
            moveTo(centerX - earOffset, centerY - size * 0.3f)
            lineTo(centerX - earOffset - earSize * 0.3f, centerY - size - earSize * 0.5f)
            lineTo(centerX - earOffset + earSize * 0.5f, centerY - size * 0.5f)
            close()
        }
        canvas.drawPath(leftEarPath, darkFurPaint)

        // Right ear
        val rightEarPath = Path().apply {
            moveTo(centerX + earOffset, centerY - size * 0.3f)
            lineTo(centerX + earOffset + earSize * 0.3f, centerY - size - earSize * 0.5f)
            lineTo(centerX + earOffset - earSize * 0.5f, centerY - size * 0.5f)
            close()
        }
        canvas.drawPath(rightEarPath, darkFurPaint)
    }

    private fun drawMask(canvas: Canvas, centerX: Float, centerY: Float, size: Float) {
        // Left mask patch
        val leftMaskRect = RectF(
            centerX - size * 0.8f,
            centerY - size * 0.5f,
            centerX - size * 0.1f,
            centerY + size * 0.1f
        )
        canvas.drawOval(leftMaskRect, darkFurPaint)

        // Right mask patch
        val rightMaskRect = RectF(
            centerX + size * 0.1f,
            centerY - size * 0.5f,
            centerX + size * 0.8f,
            centerY + size * 0.1f
        )
        canvas.drawOval(rightMaskRect, darkFurPaint)
    }

    private fun drawSnout(canvas: Canvas, centerX: Float, centerY: Float, size: Float) {
        val snoutRect = RectF(
            centerX - size * 0.4f,
            centerY - size * 0.1f,
            centerX + size * 0.4f,
            centerY + size * 0.6f
        )
        canvas.drawOval(snoutRect, lightFurPaint)
    }

    private fun drawEyes(canvas: Canvas, centerX: Float, centerY: Float, size: Float) {
        val eyeY = centerY - size * 0.15f
        val eyeSpacing = size * 0.35f
        val eyeRadius = size * 0.18f
        val pupilRadius = size * 0.1f

        // Calculate eye height based on blink progress (1.0 = fully closed)
        val eyeScaleY = 1f - blinkProgress * 0.9f

        // Left eye white
        canvas.save()
        canvas.scale(1f, eyeScaleY, centerX - eyeSpacing, eyeY)
        canvas.drawCircle(centerX - eyeSpacing, eyeY, eyeRadius, eyeWhitePaint)

        if (eyeScaleY > 0.3f) {
            // Left pupil
            canvas.drawCircle(centerX - eyeSpacing, eyeY, pupilRadius, eyePupilPaint)
            // Left highlight
            canvas.drawCircle(
                centerX - eyeSpacing - pupilRadius * 0.3f,
                eyeY - pupilRadius * 0.3f,
                pupilRadius * 0.3f,
                eyeHighlightPaint
            )
        }
        canvas.restore()

        // Right eye white
        canvas.save()
        canvas.scale(1f, eyeScaleY, centerX + eyeSpacing, eyeY)
        canvas.drawCircle(centerX + eyeSpacing, eyeY, eyeRadius, eyeWhitePaint)

        if (eyeScaleY > 0.3f) {
            // Right pupil
            canvas.drawCircle(centerX + eyeSpacing, eyeY, pupilRadius, eyePupilPaint)
            // Right highlight
            canvas.drawCircle(
                centerX + eyeSpacing - pupilRadius * 0.3f,
                eyeY - pupilRadius * 0.3f,
                pupilRadius * 0.3f,
                eyeHighlightPaint
            )
        }
        canvas.restore()
    }

    private fun drawBlush(canvas: Canvas, centerX: Float, centerY: Float, size: Float) {
        val blushY = centerY + size * 0.05f
        val blushSpacing = size * 0.55f
        val blushRadius = size * 0.15f

        // Left blush
        canvas.drawCircle(centerX - blushSpacing, blushY, blushRadius, blushPaint)
        // Right blush
        canvas.drawCircle(centerX + blushSpacing, blushY, blushRadius, blushPaint)
    }

    private fun drawWhiskers(canvas: Canvas, centerX: Float, centerY: Float, size: Float) {
        val whiskerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF6B6B6B.toInt()
            style = Paint.Style.FILL
        }

        val whiskerY = centerY + size * 0.25f
        val whiskerSpacing = size * 0.25f
        val dotSize = size * 0.04f

        // Left whisker dots
        canvas.drawCircle(centerX - whiskerSpacing, whiskerY - size * 0.05f, dotSize, whiskerPaint)
        canvas.drawCircle(centerX - whiskerSpacing - size * 0.1f, whiskerY, dotSize, whiskerPaint)
        canvas.drawCircle(centerX - whiskerSpacing, whiskerY + size * 0.05f, dotSize, whiskerPaint)

        // Right whisker dots
        canvas.drawCircle(centerX + whiskerSpacing, whiskerY - size * 0.05f, dotSize, whiskerPaint)
        canvas.drawCircle(centerX + whiskerSpacing + size * 0.1f, whiskerY, dotSize, whiskerPaint)
        canvas.drawCircle(centerX + whiskerSpacing, whiskerY + size * 0.05f, dotSize, whiskerPaint)
    }
}
