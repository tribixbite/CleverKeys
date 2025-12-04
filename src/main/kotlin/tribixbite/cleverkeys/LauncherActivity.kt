package tribixbite.cleverkeys

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.BounceInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import tribixbite.cleverkeys.theme.KeyboardTheme

/**
 * Material 3 Compose launcher activity for CleverKeys.
 *
 * Features:
 * - Animated cute raccoon mascot
 * - Clear setup instructions
 * - Quick access to system keyboard settings
 * - GitHub repository link
 */
class LauncherActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LauncherActivity"
        private const val GITHUB_URL = "https://github.com/tribixbite/CleverKeys"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContent {
                KeyboardTheme {
                    LauncherScreen(
                        onEnableKeyboard = { launchKeyboardSettings() },
                        onOpenSettings = { launchAppSettings() },
                        onOpenGitHub = { openGitHub() }
                    )
                }
            }
            Log.i(TAG, "LauncherActivity created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating LauncherActivity", e)
        }
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

    private fun openGitHub() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
            startActivity(intent)
            Log.d(TAG, "Opened GitHub repository")
        } catch (e: Exception) {
            Log.e(TAG, "Error opening GitHub", e)
            android.widget.Toast.makeText(
                this,
                "Could not open browser",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
}

@Composable
fun LauncherScreen(
    onEnableKeyboard: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGitHub: () -> Unit
) {
    val scrollState = rememberScrollState()
    var testText by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Load raccoon logo from assets
    val raccoonBitmap = remember {
        try {
            context.assets.open("raccoon_logo.webp").use { inputStream ->
                android.graphics.BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            null
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // Raccoon logo from assets
            if (raccoonBitmap != null) {
                Image(
                    bitmap = raccoonBitmap.asImageBitmap(),
                    contentDescription = "CleverKeys Raccoon Logo",
                    modifier = Modifier
                        .size(180.dp)
                        .padding(bottom = 24.dp)
                )
            } else {
                // Fallback to animated mascot if image not found
                RaccoonMascot(
                    modifier = Modifier
                        .size(180.dp)
                        .padding(bottom = 24.dp)
                )
            }

            // App title
            Text(
                text = "CleverKeys",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 36.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Subtitle
            Text(
                text = "Neural Keyboard",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Instructions card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "This application is a virtual keyboard. Go to the system settings by clicking on the button below and enable CleverKeys.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Test input field
            OutlinedTextField(
                value = testText,
                onValueChange = { testText = it },
                label = { Text("Test Keyboard Here") },
                placeholder = { Text("Type to test...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 3,
                maxLines = 5
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Enable keyboard button (primary)
            Button(
                onClick = onEnableKeyboard,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = "Enable Keyboard",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // App settings button (secondary)
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "App Settings",
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // GitHub link at bottom
            Text(
                text = "tribixbite/CleverKeys",
                style = MaterialTheme.typography.bodyMedium.copy(
                    textDecoration = TextDecoration.Underline
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onOpenGitHub() }
                    .padding(16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun RaccoonMascot(modifier: Modifier = Modifier) {
    var raccoonView by remember { mutableStateOf<RaccoonAnimationView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            raccoonView?.stopBlinking()
        }
    }

    AndroidView(
        factory = { context ->
            RaccoonAnimationView(context).also {
                raccoonView = it
                it.startBlinking()
            }
        },
        modifier = modifier,
        update = { view ->
            // Start animations when view is updated
            view.startAnimations()
        }
    )
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

    private var animatorSet: AnimatorSet? = null

    fun startBlinking() {
        isBlinking = true
        blinkHandler.postDelayed(blinkRunnable, 1500)
    }

    fun stopBlinking() {
        isBlinking = false
        blinkHandler.removeCallbacks(blinkRunnable)
        animatorSet?.cancel()
    }

    fun startAnimations() {
        if (animatorSet?.isRunning == true) return

        // Bounce animation
        val bounceY = ObjectAnimator.ofFloat(this, "translationY", 0f, -15f, 0f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = BounceInterpolator()
        }

        // Gentle rotation for playfulness
        val tilt = ObjectAnimator.ofFloat(this, "rotation", -3f, 3f, -3f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }

        animatorSet = AnimatorSet().apply {
            playTogether(bounceY, tilt)
            start()
        }
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
