package tribixbite.cleverkeys

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.onFocusChanged
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LauncherScreen(
    onEnableKeyboard: () -> Unit,
    onSelectKeyboard: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGitHub: () -> Unit
) {
    val context = LocalContext.current
    var testText by remember { mutableStateOf("") }

    // Track keyboard visibility using WindowInsets IME bottom inset
    val density = LocalDensity.current
    val imeBottom = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(density)
    val isKeyboardVisible = imeBottom > 0

    // Check if CleverKeys is enabled and selected
    var isKeyboardEnabled by remember { mutableStateOf(isCleverKeysEnabled(context)) }
    var isKeyboardSelected by remember { mutableStateOf(isCleverKeysSelected(context)) }

    // Auto-refresh completion status every 500ms when screen is visible
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            isKeyboardEnabled = isCleverKeysEnabled(context)
            isKeyboardSelected = isCleverKeysSelected(context)
        }
    }

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
            .statusBarsPadding() // Properly handle status bar
    ) {
        // 1. Background Animation layer
        MatrixSwipeRainBackground()

        // 2. Top Bar with GitHub (left) and Settings (right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // GitHub icon - top left
            IconButton(
                onClick = onOpenGitHub,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            ) {
                GitHubIcon(
                    modifier = Modifier.size(22.dp),
                    tint = Color.White.copy(alpha = 0.8f)
                )
            }

            // Settings gear icon - top right
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // 3. Content Layer
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 72.dp) // Account for top bar
                .navigationBarsPadding()
                .imePadding(), // Adjust for keyboard
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Logo / Header Section - Hide when keyboard visible
            if (!isKeyboardVisible) {
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
                        text = "Privacy, power, and control— with a brain.",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFB0B0E0),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Setup Cards with completion indicators
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SetupCard(
                    number = "1",
                    title = "Enable Keyboard",
                    description = "Turn on CleverKeys in system settings",
                    icon = Icons.Default.Settings,
                    isCompleted = isKeyboardEnabled,
                    onClick = onEnableKeyboard
                )

                SetupCard(
                    number = "2",
                    title = "Select Keyboard",
                    description = "Switch your default input method",
                    icon = Icons.Default.CheckCircle,
                    isCompleted = isKeyboardSelected,
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

            // Footer text
            if (!isKeyboardVisible) {
                Text(
                    text = "An uncompromising open source keyboard.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB0B0E0).copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
        }
    }
}

// Helper function to check if CleverKeys is enabled
private fun isCleverKeysEnabled(context: Context): Boolean {
    return try {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val enabledInputMethods = imm.enabledInputMethodList
        enabledInputMethods.any { it.packageName == context.packageName }
    } catch (e: Exception) {
        false
    }
}

// Helper function to check if CleverKeys is the selected keyboard
private fun isCleverKeysSelected(context: Context): Boolean {
    return try {
        val currentIme = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
        currentIme?.contains(context.packageName) == true
    } catch (e: Exception) {
        false
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SetupCard(
    number: String,
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isCompleted: Boolean = false,
    onClick: () -> Unit
) {
    // Brand purple for completed state
    val brandPurple = Color(0xFF9B59B6)
    val brandPurpleLight = Color(0xFFBB8FCE)

    val borderColor = if (isCompleted) {
        Brush.horizontalGradient(
            colors = listOf(
                brandPurple.copy(alpha = 0.8f),
                brandPurpleLight.copy(alpha = 0.8f)
            )
        )
    } else {
        Brush.horizontalGradient(
            colors = listOf(
                brandPurple.copy(alpha = 0.5f),
                Color(0xFF64B5F6).copy(alpha = 0.5f)
            )
        )
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF151525).copy(alpha = 0.8f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Number Badge or Checkmark
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        if (isCompleted) brandPurple else MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isCompleted) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Completed",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        text = number,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isCompleted) brandPurpleLight else Color.White
                )
                Text(
                    text = if (isCompleted) "✓ Done" else description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCompleted) brandPurpleLight.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.7f)
                )
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isCompleted) brandPurple else Color.White.copy(alpha = 0.8f)
            )
        }
    }
}

// --- GitHub Icon Composable (Official GitHub Mark) ---

@Composable
fun GitHubIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val size = this.size.minDimension
        val scale = size / 24f // GitHub mark is designed on 24x24 grid

        val path = androidx.compose.ui.graphics.Path().apply {
            // GitHub Invertocat mark path (scaled from official SVG)
            moveTo(12f * scale, 0.297f * scale)
            cubicTo(5.37f * scale, 0.297f * scale, 0f * scale, 5.67f * scale, 0f * scale, 12.297f * scale)
            cubicTo(0f * scale, 17.6f * scale, 3.438f * scale, 22.097f * scale, 8.205f * scale, 23.682f * scale)
            cubicTo(8.805f * scale, 23.795f * scale, 9.025f * scale, 23.424f * scale, 9.025f * scale, 23.105f * scale)
            cubicTo(9.025f * scale, 22.82f * scale, 9.015f * scale, 22.065f * scale, 9.01f * scale, 21.065f * scale)
            cubicTo(5.672f * scale, 21.79f * scale, 4.968f * scale, 19.455f * scale, 4.968f * scale, 19.455f * scale)
            cubicTo(4.422f * scale, 18.07f * scale, 3.633f * scale, 17.7f * scale, 3.633f * scale, 17.7f * scale)
            cubicTo(2.546f * scale, 16.956f * scale, 3.717f * scale, 16.971f * scale, 3.717f * scale, 16.971f * scale)
            cubicTo(4.922f * scale, 17.055f * scale, 5.555f * scale, 18.207f * scale, 5.555f * scale, 18.207f * scale)
            cubicTo(6.625f * scale, 20.042f * scale, 8.364f * scale, 19.512f * scale, 9.05f * scale, 19.205f * scale)
            cubicTo(9.158f * scale, 18.429f * scale, 9.467f * scale, 17.9f * scale, 9.81f * scale, 17.6f * scale)
            cubicTo(7.145f * scale, 17.3f * scale, 4.344f * scale, 16.268f * scale, 4.344f * scale, 11.67f * scale)
            cubicTo(4.344f * scale, 10.36f * scale, 4.809f * scale, 9.29f * scale, 5.579f * scale, 8.45f * scale)
            cubicTo(5.444f * scale, 8.147f * scale, 5.039f * scale, 6.927f * scale, 5.684f * scale, 5.274f * scale)
            cubicTo(5.684f * scale, 5.274f * scale, 6.689f * scale, 4.952f * scale, 8.984f * scale, 6.504f * scale)
            cubicTo(9.944f * scale, 6.237f * scale, 10.964f * scale, 6.105f * scale, 11.984f * scale, 6.099f * scale)
            cubicTo(13.004f * scale, 6.105f * scale, 14.024f * scale, 6.237f * scale, 14.984f * scale, 6.504f * scale)
            cubicTo(17.264f * scale, 4.952f * scale, 18.269f * scale, 5.274f * scale, 18.269f * scale, 5.274f * scale)
            cubicTo(18.914f * scale, 6.927f * scale, 18.509f * scale, 8.147f * scale, 18.389f * scale, 8.45f * scale)
            cubicTo(19.154f * scale, 9.29f * scale, 19.619f * scale, 10.36f * scale, 19.619f * scale, 11.67f * scale)
            cubicTo(19.619f * scale, 16.28f * scale, 16.814f * scale, 17.295f * scale, 14.144f * scale, 17.59f * scale)
            cubicTo(14.564f * scale, 17.95f * scale, 14.954f * scale, 18.686f * scale, 14.954f * scale, 19.81f * scale)
            cubicTo(14.954f * scale, 21.416f * scale, 14.939f * scale, 22.706f * scale, 14.939f * scale, 23.096f * scale)
            cubicTo(14.939f * scale, 23.411f * scale, 15.149f * scale, 23.786f * scale, 15.764f * scale, 23.666f * scale)
            cubicTo(20.565f * scale, 22.092f * scale, 24f * scale, 17.592f * scale, 24f * scale, 12.297f * scale)
            cubicTo(24f * scale, 5.67f * scale, 18.627f * scale, 0.297f * scale, 12f * scale, 0.297f * scale)
            close()
        }

        drawPath(path = path, color = tint)
    }
}

// --- Matrix Swipe Rain Animation (Refactored to Wizard/Sparkle Style) ---

data class Sparkle(
    val id: Long,
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val color: Color,
    val startTime: Long,
    val duration: Long,
    val maxRadius: Float
)

data class MagicSpell(
    val id: Long,
    val points: List<Offset>,
    val color: Color,
    val startTime: Long,
    val duration: Long,
    val scale: Float
)

@Composable
fun MatrixSwipeRainBackground() {
    // Stardust Silver & Ethereal Magic Palette
    val baseColors = listOf(
        Color(0xFFFFFFFF), // Pure Light
        Color(0xFFE0E0E0), // Silver
        Color(0xFFF0F8FF), // Alice Blue
        Color(0xFFB0C4DE)  // Light Steel Blue
    )

    val density = LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidth = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeight = with(density) { configuration.screenHeightDp.dp.toPx() }

    // State
    val spells = remember { mutableStateListOf<MagicSpell>() }
    val sparkles = remember { mutableStateListOf<Sparkle>() }
    var lastSpawnTime by remember { mutableStateOf(0L) }

    // Animation Loop
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameTime ->
                val currentTime = System.currentTimeMillis()

                // 1. Spawn new spells (Gestures)
                // Less frequent but more impactful
                if (currentTime - lastSpawnTime > Random.nextLong(600, 1200) && spells.size < 6) {
                    spells.add(generateMagicSpell(baseColors, currentTime, screenWidth, screenHeight))
                    lastSpawnTime = currentTime
                }

                // 2. Update & Spawn Sparkles from active spells
                spells.forEach { spell ->
                    val elapsed = currentTime - spell.startTime
                    val progress = (elapsed.toFloat() / spell.duration).coerceIn(0f, 1f)
                    
                    if (progress < 1f && spell.points.isNotEmpty()) {
                        val totalPoints = spell.points.size
                        val currentPointIndex = (totalPoints * progress).toInt().coerceIn(0, totalPoints - 1)
                        val currentPoint = spell.points[currentPointIndex]

                        // Emit sparkles at the "tip" of the wand (The Casting Point)
                        // Higher chance when moving
                        if (progress < 0.9f) {
                            // Multi-spawn for density
                            repeat(Random.nextInt(1, 3)) {
                                sparkles.add(generateSparkle(currentPoint, spell.color, currentTime))
                            }
                        }
                    }
                }

                // 3. Cleanup
                spells.removeAll { currentTime - it.startTime > it.duration }
                sparkles.removeAll { currentTime - it.startTime > it.duration }
            }
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val currentTime = System.currentTimeMillis()

        // Draw Spells (The Light Traces)
        spells.forEach { spell ->
            val elapsed = currentTime - spell.startTime
            val progress = (elapsed.toFloat() / spell.duration).coerceIn(0f, 1f)
            if (spell.points.size < 2) return@forEach

            val visiblePoints = (spell.points.size * progress).toInt()
            if (visiblePoints < 2) return@forEach

            val path = androidx.compose.ui.graphics.Path()
            path.moveTo(spell.points[0].x, spell.points[0].y)
            
            // Draw the path
            for (i in 1 until visiblePoints) {
                path.lineTo(spell.points[i].x, spell.points[i].y)
            }

            // Fade out logic: Fades tail-first or overall fade?
            // Spell traces linger and then fizzle.
            val alpha = if (progress > 0.8f) (1f - progress) * 5f else 1f
            
            // 1. Ethereal Glow (Wide, diffuse)
            drawPath(
                path = path,
                color = spell.color.copy(alpha = alpha * 0.2f),
                style = Stroke(
                    width = 25f * spell.scale, 
                    cap = StrokeCap.Round, 
                    join = StrokeJoin.Round
                )
            )
            
            // 2. Secondary Glow
            drawPath(
                path = path,
                color = spell.color.copy(alpha = alpha * 0.4f),
                style = Stroke(
                    width = 12f * spell.scale, 
                    cap = StrokeCap.Round, 
                    join = StrokeJoin.Round
                )
            )

            // 3. Core Beam (Focused, bright)
            drawPath(
                path = path,
                color = Color.White.copy(alpha = alpha),
                style = Stroke(
                    width = 4f * spell.scale, 
                    cap = StrokeCap.Round, 
                    join = StrokeJoin.Round
                )
            )
        }

        // Draw Sparkles (Magic Dust)
        sparkles.forEach { sparkle ->
            val life = (currentTime - sparkle.startTime).toFloat() / sparkle.duration
            if (life >= 1f) return@forEach
            
            // Physics update (done in draw for simplicity in this tight loop)
            val t = (currentTime - sparkle.startTime) / 15f
            val currentX = sparkle.x + sparkle.vx * t
            val currentY = sparkle.y + sparkle.vy * t
            
            // Twinkle: Oscillate alpha rapidly
            // sin wave based on time + unique ID to desync
            val twinklePhase = (currentTime / 100.0) + sparkle.id
            val twinkle = (sin(twinklePhase).toFloat() + 1f) / 2f
            val base = 1f - life
            val baseAlpha = base * base
            
            val finalAlpha = (baseAlpha * (0.5f + 0.5f * twinkle)).coerceIn(0f, 1f)
            
            drawCircle(
                color = sparkle.color.copy(alpha = finalAlpha),
                radius = sparkle.maxRadius * (1f - life * 0.5f),
                center = Offset(currentX, currentY)
            )
        }
    }
}

fun generateSparkle(origin: Offset, baseColor: Color, currentTime: Long): Sparkle {
    // Explosion/diffusion velocity
    val angle = Random.nextFloat() * 6.28f
    val speed = Random.nextFloat() * 2f + 0.5f
    
    return Sparkle(
        id = Random.nextLong(),
        x = origin.x,
        y = origin.y,
        vx = cos(angle) * speed,
        vy = sin(angle) * speed,
        color = if (Random.nextBoolean()) Color.White else baseColor,
        startTime = currentTime,
        duration = Random.nextLong(500, 2000), // 0.5 - 2s life
        maxRadius = Random.nextFloat() * 3f + 1f
    )
}

fun generateMagicSpell(colors: List<Color>, currentTime: Long, maxWidth: Float, maxHeight: Float): MagicSpell {
    val points = mutableListOf<Offset>()
    
    // 1. Pick a random central area for this "word"
    val clusterCenterX = Random.nextFloat() * (maxWidth * 0.8f) + (maxWidth * 0.1f)
    val clusterCenterY = Random.nextFloat() * (maxHeight * 0.8f) + (maxHeight * 0.1f)
    
    // Constrain swipes to ~20% of screen size around this center
    val maxRadiusX = maxWidth * 0.15f
    val maxRadiusY = maxHeight * 0.15f

    // Start near the center
    var currentPos = Offset(
        clusterCenterX + (Random.nextFloat() - 0.5f) * maxRadiusX,
        clusterCenterY + (Random.nextFloat() - 0.5f) * maxRadiusY
    )
    points.add(currentPos)

    // 2. Generate "Key Targets" (The word to swipe)
    // 4 to 8 targets (letters) in the localized cluster
    val targets = List(Random.nextInt(4, 9)) {
        Offset(
            clusterCenterX + (Random.nextFloat() - 0.5f) * 2 * maxRadiusX,
            clusterCenterY + (Random.nextFloat() - 0.5f) * 2 * maxRadiusY
        )
    }

    // Physics State
    var velocity = Offset.Zero
    val maxSpeed = 15f          // Slower, more readable movement (was 25f)
    val steeringFactor = 0.12f  // Slightly looser curves
    
    // 3. Trace the path from target to target
    for (target in targets) {
        var distance = (target - currentPos).getDistance()
        
        // Move towards this target until we are close
        var steps = 0
        while (distance > 20f && steps < 150) { 
            val desiredVelocity = (target - currentPos).div(distance) * maxSpeed
            val steering = (desiredVelocity - velocity) * steeringFactor
            
            velocity += steering
            currentPos += velocity
            
            // Subtle Jitter
            if (Random.nextFloat() > 0.8f) {
                val jitter = Offset(
                    (Random.nextFloat() - 0.5f) * 1.5f,
                    (Random.nextFloat() - 0.5f) * 1.5f
                )
                currentPos += jitter
            }

            points.add(currentPos)
            distance = (target - currentPos).getDistance()
            steps++
        }
    }

    return MagicSpell(
        id = currentTime + Random.nextLong(),
        points = points,
        color = colors.random(),
        startTime = currentTime,
        duration = Random.nextLong(2000, 4500), // 2 - 4.5s duration (slower feel)
        scale = Random.nextFloat() * 0.5f + 0.5f
    )
}

// --- Raccoon Mascot Composable ---

/**
 * Composable wrapper for the RaccoonAnimationView.
 * Used as fallback when raccoon_logo.webp asset is not available.
 */
@Composable
fun RaccoonMascot(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            RaccoonAnimationView(context).apply {
                startBlinking()
                startAnimations()
            }
        },
        onRelease = { view ->
            view.stopBlinking()
        }
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