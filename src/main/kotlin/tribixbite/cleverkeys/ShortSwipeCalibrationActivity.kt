package tribixbite.cleverkeys

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tribixbite.cleverkeys.Defaults
import tribixbite.cleverkeys.theme.KeyboardTheme
import kotlin.math.sqrt

/**
 * Short Swipe Calibration Activity
 *
 * Allows users to:
 * 1. See a tutorial graphic showing how short swipes work
 * 2. Configure min/max distance thresholds with sliders
 * 3. Practice on an interactive area with real-time feedback
 */
@OptIn(ExperimentalMaterial3Api::class)
class ShortSwipeCalibrationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)

        setContent {
            KeyboardTheme {
                ShortSwipeCalibrationScreen(
                    initialMinDistance = prefs.getInt("short_gesture_min_distance", Defaults.SHORT_GESTURE_MIN_DISTANCE),
                    initialMaxDistance = prefs.getInt("short_gesture_max_distance", Defaults.SHORT_GESTURE_MAX_DISTANCE),
                    onSave = { min, max ->
                        prefs.edit()
                            .putInt("short_gesture_min_distance", min)
                            .putInt("short_gesture_max_distance", max)
                            .apply()
                        Config.globalConfig().refresh(resources, null)
                    },
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShortSwipeCalibrationScreen(
    initialMinDistance: Int,
    initialMaxDistance: Int,
    onSave: (Int, Int) -> Unit,
    onBack: () -> Unit
) {
    var minDistance by remember { mutableStateOf(initialMinDistance.toFloat()) }
    var maxDistance by remember { mutableStateOf(initialMaxDistance.toFloat()) }
    var feedbackText by remember { mutableStateOf("Touch and drag to test") }
    var feedbackColor by remember { mutableStateOf(Color.Gray) }
    var lastDistance by remember { mutableStateOf(0f) }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Short Swipe Calibration") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        minDistance = Defaults.SHORT_GESTURE_MIN_DISTANCE.toFloat()
                        maxDistance = Defaults.SHORT_GESTURE_MAX_DISTANCE.toFloat()
                        onSave(minDistance.toInt(), maxDistance.toInt())
                    }) {
                        Icon(Icons.Default.Refresh, "Reset to defaults")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Section 1: Tutorial Graphic
            TutorialSection()

            Spacer(Modifier.height(24.dp))

            // Section 2: Configuration Sliders
            ConfigurationSection(
                minDistance = minDistance,
                maxDistance = maxDistance,
                onMinChange = {
                    minDistance = it
                    if (minDistance > maxDistance) maxDistance = minDistance
                    onSave(minDistance.toInt(), maxDistance.toInt())
                },
                onMaxChange = {
                    maxDistance = it
                    if (maxDistance < minDistance) minDistance = maxDistance
                    onSave(minDistance.toInt(), maxDistance.toInt())
                }
            )

            Spacer(Modifier.height(24.dp))

            // Section 3: Practice Area
            PracticeSection(
                minDistance = minDistance,
                maxDistance = maxDistance,
                feedbackText = feedbackText,
                feedbackColor = feedbackColor,
                lastDistance = lastDistance,
                onGestureDetected = { distance ->
                    lastDistance = distance
                    when {
                        distance < minDistance -> {
                            feedbackText = "TAP (${distance.toInt()}px)"
                            feedbackColor = Color.White
                        }
                        distance <= maxDistance -> {
                            feedbackText = "SHORT SWIPE ✓ (${distance.toInt()}px)"
                            feedbackColor = Color(0xFF4CAF50) // Green
                        }
                        else -> {
                            feedbackText = "LONG SWIPE (${distance.toInt()}px)"
                            feedbackColor = Color(0xFF2196F3) // Blue
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun TutorialSection() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "How Short Swipes Work",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(16.dp))

            // Tutorial Graphic: Key with gesture arrows
            SwipeTutorialGraphic(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Short swipes trigger quick actions on keys. " +
                       "Move your finger a small distance to activate.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                GestureLegendItem("TAP", Color.White, "< Min")
                GestureLegendItem("SHORT", Color(0xFF4CAF50), "Min - Max")
                GestureLegendItem("LONG", Color(0xFF2196F3), "> Max")
            }
        }
    }
}

@Composable
private fun SwipeTutorialGraphic(modifier: Modifier = Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val surfaceColor = MaterialTheme.colorScheme.surface

    Canvas(modifier = modifier) {
        val keyWidth = size.width * 0.25f
        val keyHeight = size.height * 0.6f
        val keyY = (size.height - keyHeight) / 2

        // Draw 3 keys
        val keySpacing = (size.width - keyWidth * 3) / 4

        for (i in 0 until 3) {
            val keyX = keySpacing + i * (keyWidth + keySpacing)

            // Key background
            drawRoundRect(
                color = surfaceColor,
                topLeft = Offset(keyX, keyY),
                size = androidx.compose.ui.geometry.Size(keyWidth, keyHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f)
            )

            // Key border
            drawRoundRect(
                color = onSurfaceColor.copy(alpha = 0.3f),
                topLeft = Offset(keyX, keyY),
                size = androidx.compose.ui.geometry.Size(keyWidth, keyHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f),
                style = Stroke(width = 2f)
            )

            // Key labels
            val centerX = keyX + keyWidth / 2
            val centerY = keyY + keyHeight / 2

            when (i) {
                0 -> {
                    // Tap indicator (dot)
                    drawCircle(
                        color = Color.White,
                        radius = 8f,
                        center = Offset(centerX, centerY)
                    )
                }
                1 -> {
                    // Short swipe arrow
                    val arrowPath = Path().apply {
                        moveTo(centerX - 15f, centerY)
                        lineTo(centerX + 15f, centerY)
                        lineTo(centerX + 8f, centerY - 7f)
                        moveTo(centerX + 15f, centerY)
                        lineTo(centerX + 8f, centerY + 7f)
                    }
                    drawPath(
                        path = arrowPath,
                        color = Color(0xFF4CAF50),
                        style = Stroke(width = 3f)
                    )
                }
                2 -> {
                    // Long swipe arrow
                    val arrowPath = Path().apply {
                        moveTo(centerX - 25f, centerY)
                        lineTo(centerX + 25f, centerY)
                        lineTo(centerX + 18f, centerY - 7f)
                        moveTo(centerX + 25f, centerY)
                        lineTo(centerX + 18f, centerY + 7f)
                    }
                    drawPath(
                        path = arrowPath,
                        color = Color(0xFF2196F3),
                        style = Stroke(width = 3f)
                    )
                }
            }
        }
    }
}

@Composable
private fun GestureLegendItem(label: String, color: Color, range: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = color
        )
        Text(
            text = range,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConfigurationSection(
    minDistance: Float,
    maxDistance: Float,
    onMinChange: (Float) -> Unit,
    onMaxChange: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Distance Thresholds",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(20.dp))

            // Minimum Distance Slider
            Text(
                text = "Tap / Swipe Threshold",
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Gestures shorter than this are taps",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Slider(
                    value = minDistance,
                    onValueChange = onMinChange,
                    valueRange = 10f..100f,
                    steps = 17,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${minDistance.toInt()}px",
                    modifier = Modifier.width(50.dp),
                    textAlign = TextAlign.End,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(16.dp))

            // Maximum Distance Slider
            Text(
                text = "Short / Long Swipe Threshold",
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Short swipes must be shorter than this",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Slider(
                    value = maxDistance,
                    onValueChange = onMaxChange,
                    valueRange = 50f..250f,
                    steps = 19,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${maxDistance.toInt()}px",
                    modifier = Modifier.width(50.dp),
                    textAlign = TextAlign.End,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun PracticeSection(
    minDistance: Float,
    maxDistance: Float,
    feedbackText: String,
    feedbackColor: Color,
    lastDistance: Float,
    onGestureDetected: (Float) -> Unit
) {
    var startOffset by remember { mutableStateOf(Offset.Zero) }
    var currentOffset by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Practice Area",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Touch and drag to test your settings",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            // Feedback display
            Text(
                text = feedbackText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = feedbackColor,
                modifier = Modifier.height(30.dp)
            )

            Spacer(Modifier.height(16.dp))

            // Interactive practice area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                startOffset = offset
                                currentOffset = offset
                                isDragging = true
                            },
                            onDragEnd = {
                                val dx = currentOffset.x - startOffset.x
                                val dy = currentOffset.y - startOffset.y
                                val distance = sqrt(dx * dx + dy * dy)
                                onGestureDetected(distance)
                                isDragging = false
                            },
                            onDragCancel = {
                                isDragging = false
                            },
                            onDrag = { change, _ ->
                                currentOffset = change.position
                                change.consume()
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                // Draw drag indicator
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (isDragging) {
                        // Draw start point
                        drawCircle(
                            color = Color.White.copy(alpha = 0.5f),
                            radius = 20f,
                            center = startOffset
                        )

                        // Draw current point
                        val dx = currentOffset.x - startOffset.x
                        val dy = currentOffset.y - startOffset.y
                        val currentDistance = sqrt(dx * dx + dy * dy)

                        val lineColor = when {
                            currentDistance < minDistance -> Color.White
                            currentDistance <= maxDistance -> Color(0xFF4CAF50)
                            else -> Color(0xFF2196F3)
                        }

                        // Draw line from start to current
                        drawLine(
                            color = lineColor,
                            start = startOffset,
                            end = currentOffset,
                            strokeWidth = 4f
                        )

                        // Draw current point
                        drawCircle(
                            color = lineColor,
                            radius = 12f,
                            center = currentOffset
                        )
                    }

                    // Draw threshold circles around center
                    val center = Offset(size.width / 2, size.height / 2)

                    // Min threshold circle
                    drawCircle(
                        color = Color.White.copy(alpha = 0.2f),
                        radius = minDistance,
                        center = center,
                        style = Stroke(width = 1f)
                    )

                    // Max threshold circle
                    drawCircle(
                        color = Color(0xFF4CAF50).copy(alpha = 0.2f),
                        radius = maxDistance,
                        center = center,
                        style = Stroke(width = 1f)
                    )
                }

                if (!isDragging) {
                    Text(
                        text = "👆 Touch here",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Visual threshold indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Min: ${minDistance.toInt()}px",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Text(
                    text = "Max: ${maxDistance.toInt()}px",
                    fontSize = 12.sp,
                    color = Color(0xFF4CAF50).copy(alpha = 0.7f)
                )
            }
        }
    }
}
