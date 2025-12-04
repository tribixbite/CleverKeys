package tribixbite.cleverkeys

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Template Browser for debugging template generation
 * Visualizes templates for all words to verify coordinate accuracy
 */
class TemplateBrowserActivity : Activity() {
    private lateinit var templateGenerator: WordGestureTemplateGenerator
    private lateinit var wordList: ListView
    private lateinit var templateView: TemplateVisualizationView
    private lateinit var templateInfo: TextView
    private var allWords: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        // Title
        mainLayout.addView(TextView(this).apply {
            text = "📐 Template Browser"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(16, 16, 16, 8)
        })

        // Instructions
        mainLayout.addView(TextView(this).apply {
            text = "Select word to view its template visualization and coordinates"
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(16, 0, 16, 16)
        })

        // Word list
        wordList = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 300
            )
            setBackgroundColor(0xFF2D2D2D.toInt())
        }
        mainLayout.addView(wordList)

        // Template visualization
        templateView = TemplateVisualizationView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 400
            )
            setBackgroundColor(0xFF1A1A1A.toInt())
        }
        mainLayout.addView(templateView)

        // Template info display
        templateInfo = TextView(this).apply {
            text = "Select a word to see template details..."
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFF2D2D2D.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 200
            )
        }
        mainLayout.addView(templateInfo)

        // Control buttons
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
        }

        buttonLayout.addView(Button(this).apply {
            text = "🔄 Refresh Templates"
            setOnClickListener { refreshTemplates() }
        })

        buttonLayout.addView(Button(this).apply {
            text = "❌ Close"
            setOnClickListener { finish() }
        })

        mainLayout.addView(buttonLayout)

        setContentView(mainLayout)

        // Initialize
        initializeTemplateBrowser()
    }

    private fun initializeTemplateBrowser() {
        // Initialize template generator
        templateGenerator = WordGestureTemplateGenerator()
        templateGenerator.loadDictionary(this)

        // Set keyboard dimensions (use standard size for testing)
        templateGenerator.setKeyboardDimensions(1080f, 400f)

        // Load all dictionary words
        allWords = templateGenerator.getDictionary() ?: emptyList()

        // Set up word list adapter
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            allWords
        )
        wordList.adapter = adapter

        // Set up word selection listener
        wordList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val selectedWord = allWords[position]
            showTemplate(selectedWord)
        }

        Log.d("TemplateBrowser", "Initialized with ${allWords.size} words")
    }

    private fun showTemplate(word: String) {
        val template = templateGenerator.generateWordTemplate(word)

        val info = StringBuilder()
        info.append("WORD: ${word.uppercase()}\n")
        info.append("================\n")

        when {
            template == null -> {
                info.append("❌ FAILED: No template generated\n")
                info.append("Check if word contains valid letters\n")
            }
            template.pts == null -> {
                info.append("❌ FAILED: Template has NULL points\n")
            }
            template.pts.isEmpty() -> {
                info.append("❌ FAILED: Template has 0 points\n")
            }
            else -> {
                info.append("✅ SUCCESS: Template generated\n")
                info.append("Points: ${template.pts.size}\n")

                // Show all coordinates
                for (i in template.pts.indices) {
                    val pt = template.pts[i]
                    val letter = word[i]
                    info.append("  $letter: (${pt.x.toInt()}, ${pt.y.toInt()})\n")
                }

                // Calculate metrics
                val templateLength = calculateTemplateLength(template.pts)
                info.append("\nMetrics:\n")
                info.append("  Total length: ${templateLength.toInt()} px\n")

                if (template.pts.size >= 2) {
                    val start = template.pts[0]
                    val end = template.pts[template.pts.size - 1]
                    info.append("  Start: (${start.x.toInt()}, ${start.y.toInt()})\n")
                    info.append("  End: (${end.x.toInt()}, ${end.y.toInt()})\n")

                    val directDistance = sqrt((end.x - start.x).pow(2) + (end.y - start.y).pow(2))
                    info.append("  Direct distance: ${directDistance.toInt()} px\n")
                    info.append("  Path efficiency: ${"%.2f".format(directDistance / templateLength)}\n")
                }

                // Update visualization
                templateView.setTemplate(template)
            }
        }

        templateInfo.text = info.toString()
        Log.d("TemplateBrowser", "Showing template for: $word")
    }

    private fun calculateTemplateLength(points: List<ContinuousGestureRecognizer.Point>): Double {
        if (points.size < 2) return 0.0

        var length = 0.0
        for (i in 1 until points.size) {
            val p1 = points[i - 1]
            val p2 = points[i]
            length += sqrt((p2.x - p1.x).pow(2) + (p2.y - p1.y).pow(2))
        }
        return length
    }

    private fun refreshTemplates() {
        // Reinitialize template generator
        templateGenerator.setKeyboardDimensions(1080f, 400f)
        Log.d("TemplateBrowser", "Templates refreshed")

        // Clear current display
        templateView.setTemplate(null)
        templateInfo.text = "Templates refreshed. Select a word to view updated template."
    }

    /**
     * Custom view for template visualization
     */
    private inner class TemplateVisualizationView(context: Context) : View(context) {
        private var currentTemplate: ContinuousGestureRecognizer.Template? = null
        private val linePaint: Paint
        private val pointPaint: Paint
        private val labelPaint: Paint

        init {
            linePaint = Paint().apply {
                color = Color.CYAN
                strokeWidth = 4f
                style = Paint.Style.STROKE
            }

            pointPaint = Paint().apply {
                color = Color.YELLOW
                style = Paint.Style.FILL
            }

            labelPaint = Paint().apply {
                color = Color.WHITE
                textSize = 24f
                isAntiAlias = true
            }
        }

        fun setTemplate(template: ContinuousGestureRecognizer.Template?) {
            currentTemplate = template
            invalidate() // Trigger redraw
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val template = currentTemplate
            if (template == null || template.pts == null || template.pts.isEmpty()) {
                canvas.drawText("No template to display", 50f, height / 2f, labelPaint)
                return
            }

            // Draw template path
            val templatePath = Path()
            val points = template.pts

            if (points.size >= 2) {
                // Scale coordinates to fit view
                val scaleX = (width - 100) / 1080f  // Leave margins
                val scaleY = (height - 100) / 400f

                val firstPoint = points[0]
                templatePath.moveTo(firstPoint.x.toFloat() * scaleX + 50, firstPoint.y.toFloat() * scaleY + 50)

                for (i in 1 until points.size) {
                    val point = points[i]
                    templatePath.lineTo(point.x.toFloat() * scaleX + 50, point.y.toFloat() * scaleY + 50)
                }

                canvas.drawPath(templatePath, linePaint)

                // Draw points and labels
                for (i in points.indices) {
                    val point = points[i]
                    val x = point.x.toFloat() * scaleX + 50
                    val y = point.y.toFloat() * scaleY + 50

                    canvas.drawCircle(x, y, 8f, pointPaint)

                    if (i < template.id.length) {
                        val letter = template.id[i]
                        canvas.drawText(letter.toString(), x - 10, y - 15, labelPaint)
                    }
                }
            }
        }
    }
}
