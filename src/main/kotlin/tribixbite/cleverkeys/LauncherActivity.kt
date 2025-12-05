package tribixbite.cleverkeys

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.BounceInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import tribixbite.cleverkeys.theme.KeyboardTheme
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Refactored Launcher Activity with "Matrix Swipe Rain" aesthetic.
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
                KeyboardTheme(darkTheme = true) { // Force dark theme for the matrix look
                    LauncherScreen(
                        onEnableKeyboard = { launchKeyboardSettings() },
                        onSelectKeyboard = { launchInputMethodPicker() },
                        onOpenSettings = { launchAppSettings() },
                        onOpenGitHub = { openGitHub() }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating LauncherActivity", e)
        }
    }

    private fun launchKeyboardSettings() {
        try {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        } catch (e: Exception) {
            Log.e(TAG, "Error launching keyboard settings", e)
        }
    }

    private fun launchInputMethodPicker() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showInputMethodPicker()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing input method picker", e)
        }
    }

    private fun launchAppSettings() {
        try {
            startActivity(Intent(this, SettingsActivity::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app settings", e)
        }
    }

    private fun openGitHub() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening GitHub", e)
        }
    }
}

@Composable
fun LauncherScreen(
    onEnableKeyboard: () -> Unit,
    onSelectKeyboard: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGitHub: () -> Unit
) {
    val context = LocalContext.current
    var testText by remember { mutableStateOf("") }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050510)) // Deep dark background
    ) {
        // 1. Background Animation layer
        MatrixSwipeRainBackground()

        // 2. Content Layer
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .navigationBarsPadding(), // Avoid overlapping with nav bar
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            // Logo / Header Section
            Box(
                contentAlignment = Alignment.Center
            ) {
                // Glow behind logo
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF9B59B6).copy(alpha = 0.3f),
                                    Color.Transparent
                                )
                            )
                        )
                        .blur(32.dp)
                )

                if (raccoonBitmap != null) {
                    Image(
                        bitmap = raccoonBitmap.asImageBitmap(),
                        contentDescription = "CleverKeys Logo",
                        modifier = Modifier.size(120.dp)
                    )
                } else {
                    RaccoonMascot(modifier = Modifier.size(120.dp))
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "CleverKeys",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = Color.White
                )
                Text(
                    text = "Neural Network Keyboard",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFB0B0E0) // Soft purple-grey
                )
            }

            // Setup Cards
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SetupCard(
                    number = "1",
                    title = "Enable Keyboard",
                    description = "Turn on CleverKeys in system settings",
                    icon = Icons.Default.Settings,
                    onClick = onEnableKeyboard
                )

                SetupCard(
                    number = "2",
                    title = "Select Keyboard",
                    description = "Switch your default input method",
                    icon = Icons.Default.CheckCircle,
                    onClick = onSelectKeyboard
                )
            }

            // Test Field
            OutlinedTextField(
                value = testText,
                onValueChange = { testText = it },
                label = { Text("Test your new keyboard here") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            // Bottom Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onOpenSettings) {
                    Text("Settings", color = Color.White.copy(alpha = 0.7f))
                }

                TextButton(onClick = onOpenGitHub) {
                    Text("GitHub", color = Color.White.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
fun SetupCard(
    number: String,
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF151525).copy(alpha = 0.8f) // Semi-transparent dark
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.horizontalGradient(
                colors = listOf(
                    Color(0xFF9B59B6).copy(alpha = 0.5f),
                    Color(0xFF64B5F6).copy(alpha = 0.5f)
                )
            )
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Number Badge
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = number,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}

// --- Matrix Swipe Rain Animation ---

data class SwipeTrace(
    var x: Float,
    var y: Float,
    var speed: Float,
    var points: List<Offset>,
    var color: Color,
    var startDelay: Float
)

@Composable
fun MatrixSwipeRainBackground() {
    val density = LocalDensity.current
    
    // Configuration
    val traceCount = 15
    val colors = listOf(
        Color(0xFF9B59B6), // Purple
        Color(0xFF64B5F6), // Blue
        Color(0xFF00E5FF), // Cyan
        Color(0xFFFF4081)  // Pink
    )

    // State for animation
    val infiniteTransition = rememberInfiniteTransition(label = "rain")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "time"
    )

    // Generate persistent random traces
    val traces = remember {
        List(traceCount) {
            generateRandomTrace(colors)
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        traces.forEachIndexed { index, trace ->
            // Calculate current Y position based on time and speed
            // Loop height is canvasHeight + padding
            val loopHeight = canvasHeight + 300f
            val progress = (time * trace.speed + trace.startDelay) % loopHeight
            val currentY = progress - 150f // Start slightly above screen

            // Draw the trace path relative to current position
            // We'll draw the path points shifted by currentY
            
            val pathAlpha = if (currentY < 0 || currentY > canvasHeight) 0f else 1f
            
            if (pathAlpha > 0) {
                // Draw the main curve
                val pathObj = Path()
                if (trace.points.isNotEmpty()) {
                    pathObj.moveTo(trace.x + trace.points[0].x, currentY + trace.points[0].y)
                    for (i in 1 until trace.points.size) {
                        // Smooth curve between points
                        val p0 = trace.points[i-1]
                        val p1 = trace.points[i]
                        // Simple line for now, could be bezier
                        pathObj.lineTo(trace.x + p1.x, currentY + p1.y)
                    }
                }

                // Draw glowing trail
                drawPath(
                    path = androidx.compose.ui.graphics.Path().apply { addPath(pathObj) },
                    color = trace.color.copy(alpha = 0.6f),
                    style = Stroke(width = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )

                // Draw sparkles/fairy dust around the trace
                val sparkleTime = System.currentTimeMillis() / 1000f
                trace.points.forEachIndexed { i, point ->
                    if (i % 2 == 0) { // Don't draw on every point
                        val seed = (index * 100 + i).toFloat()
                        
                        // Twinkle effect
                        val twinkle = (sin(sparkleTime * 5f + seed) + 1f) / 2f
                        val alpha = twinkle * 0.8f
                        
                        if (alpha > 0.1f) {
                            // Random offset for "dust" look
                            val xOff = (sin(seed * 1.1f) * 15f)
                            val yOff = (cos(seed * 1.3f) * 15f)
                            
                            drawCircle(
                                color = Color.White.copy(alpha = alpha),
                                radius = (1.5f + twinkle), // Pulse size
                                center = Offset(trace.x + point.x + xOff, currentY + point.y + yOff)
                            )
                        }
                    }
                }
            }
        }
    }
}

fun generateRandomTrace(colors: List<Color>): SwipeTrace {
    // Generate a random curved path (list of offsets relative to start)
    val points = mutableListOf<Offset>()
    var currentX = 0f
    var currentY = 0f
    points.add(Offset(0f, 0f))
    
    val length = Random.nextInt(3, 8)
    for (i in 0 until length) {
        // Zig-zag downwards
        currentX += Random.nextFloat() * 100f - 50f
        currentY += Random.nextFloat() * 80f + 40f // Always move down
        points.add(Offset(currentX, currentY))
    }

    return SwipeTrace(
        x = Random.nextFloat() * 1000f, // Will be scaled to width in draw if needed, or just assume wide enough
        y = 0f,
        speed = Random.nextFloat() * 2f + 1f, // Speed multiplier
        points = points,
        color = colors.random(),
        startDelay = Random.nextFloat() * 2000f
    )
}

// --- Legacy Raccoon Implementation (Kept for fallback) ---

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