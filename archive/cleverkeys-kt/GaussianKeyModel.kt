package tribixbite.cleverkeys

import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import kotlin.math.*

/**
 * Gaussian probability model for swipe typing key detection.
 * Each key has a 2D Gaussian distribution centered at the key center.
 * This provides probabilistic key detection instead of binary hit/miss.
 *
 * Based on FlorisBoard's approach, this should improve accuracy by 30-40%.
 *
 * Ported from Java to Kotlin with improvements.
 */
class GaussianKeyModel {

    companion object {
        private const val TAG = "GaussianKeyModel"

        // Standard deviation factors for key dimensions
        // Smaller values = tighter distribution around key center
        // Larger values = more tolerance for inaccuracy
        private const val SIGMA_X_FACTOR = 0.4f // 40% of key width
        private const val SIGMA_Y_FACTOR = 0.35f // 35% of key height

        // Minimum probability threshold to consider a key
        private const val MIN_PROBABILITY = 0.01f
    }

    /**
     * Information about a key's position and size
     */
    private data class KeyInfo(
        val center: PointF,
        val width: Float,
        val height: Float
    ) {
        val sigmaX: Float = width * SIGMA_X_FACTOR
        val sigmaY: Float = height * SIGMA_Y_FACTOR
    }

    // Key layout information
    private val keyLayout = mutableMapOf<Char, KeyInfo>()
    private var keyboardWidth = 1.0f
    private var keyboardHeight = 1.0f

    init {
        initializeQwertyLayout()
    }

    /**
     * Initialize with QWERTY layout (default)
     * Positions are normalized [0,1]
     */
    private fun initializeQwertyLayout() {
        // Key dimensions (approximate for QWERTY)
        val keyWidth = 0.1f // 10% of keyboard width
        val keyHeight = 0.25f // 25% of keyboard height (4 rows)

        // Top row - Q W E R T Y U I O P
        addKey('q', 0.05f, 0.125f, keyWidth, keyHeight)
        addKey('w', 0.15f, 0.125f, keyWidth, keyHeight)
        addKey('e', 0.25f, 0.125f, keyWidth, keyHeight)
        addKey('r', 0.35f, 0.125f, keyWidth, keyHeight)
        addKey('t', 0.45f, 0.125f, keyWidth, keyHeight)
        addKey('y', 0.55f, 0.125f, keyWidth, keyHeight)
        addKey('u', 0.65f, 0.125f, keyWidth, keyHeight)
        addKey('i', 0.75f, 0.125f, keyWidth, keyHeight)
        addKey('o', 0.85f, 0.125f, keyWidth, keyHeight)
        addKey('p', 0.95f, 0.125f, keyWidth, keyHeight)

        // Middle row - A S D F G H J K L (offset by half key)
        addKey('a', 0.10f, 0.375f, keyWidth, keyHeight)
        addKey('s', 0.20f, 0.375f, keyWidth, keyHeight)
        addKey('d', 0.30f, 0.375f, keyWidth, keyHeight)
        addKey('f', 0.40f, 0.375f, keyWidth, keyHeight)
        addKey('g', 0.50f, 0.375f, keyWidth, keyHeight)
        addKey('h', 0.60f, 0.375f, keyWidth, keyHeight)
        addKey('j', 0.70f, 0.375f, keyWidth, keyHeight)
        addKey('k', 0.80f, 0.375f, keyWidth, keyHeight)
        addKey('l', 0.90f, 0.375f, keyWidth, keyHeight)

        // Bottom row - Z X C V B N M (offset by full key)
        addKey('z', 0.15f, 0.625f, keyWidth, keyHeight)
        addKey('x', 0.25f, 0.625f, keyWidth, keyHeight)
        addKey('c', 0.35f, 0.625f, keyWidth, keyHeight)
        addKey('v', 0.45f, 0.625f, keyWidth, keyHeight)
        addKey('b', 0.55f, 0.625f, keyWidth, keyHeight)
        addKey('n', 0.65f, 0.625f, keyWidth, keyHeight)
        addKey('m', 0.75f, 0.625f, keyWidth, keyHeight)
    }

    /**
     * Add a key to the layout
     */
    private fun addKey(key: Char, centerX: Float, centerY: Float, width: Float, height: Float) {
        keyLayout[key] = KeyInfo(PointF(centerX, centerY), width, height)
    }

    /**
     * Update key layout from actual keyboard data
     * @param keyBounds Map of character to actual key bounds
     * @param keyboardWidth Total keyboard width in pixels
     * @param keyboardHeight Total keyboard height in pixels
     */
    fun updateKeyLayout(
        keyBounds: Map<Char, RectF>,
        keyboardWidth: Float,
        keyboardHeight: Float
    ) {
        this.keyboardWidth = keyboardWidth
        this.keyboardHeight = keyboardHeight
        keyLayout.clear()

        for ((char, bounds) in keyBounds) {
            val centerX = (bounds.left + bounds.right) / 2f / keyboardWidth
            val centerY = (bounds.top + bounds.bottom) / 2f / keyboardHeight
            val width = bounds.width() / keyboardWidth
            val height = bounds.height() / keyboardHeight

            keyLayout[char] = KeyInfo(PointF(centerX, centerY), width, height)
        }

        Log.d(TAG, "Updated layout with ${keyLayout.size} keys")
    }

    /**
     * Set keyboard dimensions for coordinate normalization
     */
    fun setKeyboardDimensions(width: Float, height: Float) {
        keyboardWidth = width
        keyboardHeight = height
    }

    /**
     * Calculate probability of a point belonging to a specific key
     * Uses 2D Gaussian distribution
     *
     * @param point Normalized point coordinates [0,1]
     * @param key The key character
     * @return Probability [0,1] of the point belonging to this key
     */
    fun getKeyProbability(point: PointF, key: Char): Float {
        val keyInfo = keyLayout[key.lowercaseChar()] ?: return 0.0f

        // Calculate normalized distance from key center
        val dx = point.x - keyInfo.center.x
        val dy = point.y - keyInfo.center.y

        // 2D Gaussian probability
        // P(x,y) = exp(-((x-μx)²/(2σx²) + (y-μy)²/(2σy²)))
        val probX = exp(-(dx * dx) / (2 * keyInfo.sigmaX * keyInfo.sigmaX))
        val probY = exp(-(dy * dy) / (2 * keyInfo.sigmaY * keyInfo.sigmaY))

        return probX * probY
    }

    /**
     * Get probabilities for all keys at a given point
     *
     * @param point Normalized point coordinates [0,1]
     * @return Map of character to probability
     */
    fun getAllKeyProbabilities(point: PointF): Map<Char, Float> {
        val probabilities = mutableMapOf<Char, Float>()

        for ((char, _) in keyLayout) {
            val prob = getKeyProbability(point, char)
            if (prob >= MIN_PROBABILITY) {
                probabilities[char] = prob
            }
        }

        return probabilities
    }

    /**
     * Get the most probable key at a given point
     *
     * @param point Normalized point coordinates [0,1]
     * @return Most probable key character, or null if no key above threshold
     */
    fun getMostProbableKey(point: PointF): Char? {
        var bestKey: Char? = null
        var bestProb = MIN_PROBABILITY

        for ((char, _) in keyLayout) {
            val prob = getKeyProbability(point, char)
            if (prob > bestProb) {
                bestProb = prob
                bestKey = char
            }
        }

        return bestKey
    }

    /**
     * Calculate weighted key sequence probability for a swipe path
     *
     * @param swipePath List of normalized points
     * @return Map of characters to their cumulative probability along the path
     */
    fun getPathKeyProbabilities(swipePath: List<PointF>): Map<Char, Float> {
        val cumulativeProbabilities = mutableMapOf<Char, Float>()

        // Weight points by their position in the path
        // Start and end points get higher weight
        for (i in swipePath.indices) {
            val point = swipePath[i]
            val weight = calculatePointWeight(i, swipePath.size)

            val pointProbs = getAllKeyProbabilities(point)
            for ((char, prob) in pointProbs) {
                val weighted = prob * weight
                cumulativeProbabilities[char] = (cumulativeProbabilities[char] ?: 0f) + weighted
            }
        }

        // Normalize probabilities
        val total = cumulativeProbabilities.values.sum()

        if (total > 0) {
            for (char in cumulativeProbabilities.keys) {
                cumulativeProbabilities[char] = cumulativeProbabilities[char]!! / total
            }
        }

        return cumulativeProbabilities
    }

    /**
     * Calculate weight for a point based on its position in the path
     * Start and end points get higher weight (important for word boundaries)
     *
     * @param index Point index in path
     * @param totalPoints Total number of points
     * @return Weight factor [0.5, 1.5]
     */
    private fun calculatePointWeight(index: Int, totalPoints: Int): Float {
        if (totalPoints <= 1) return 1.0f

        val position = index.toFloat() / (totalPoints - 1)

        // Higher weight at start and end (U-shaped curve)
        // Weight = 1.0 + 0.5 * (2|x - 0.5|)
        val weight = 1.0f + 0.5f * abs(2 * position - 1.0f)

        return weight
    }

    /**
     * Calculate confidence score for a word given a swipe path
     * Higher score means the path better matches the word
     *
     * @param word Target word
     * @param swipePath Normalized swipe path
     * @return Confidence score [0, 1]
     */
    fun getWordConfidence(word: String?, swipePath: List<PointF>?): Float {
        if (word.isNullOrEmpty() || swipePath.isNullOrEmpty()) {
            return 0.0f
        }

        val lowerWord = word.lowercase()
        var totalScore = 0.0f
        val samplesPerLetter = max(1, swipePath.size / lowerWord.length)

        for (i in lowerWord.indices) {
            val letter = lowerWord[i]

            // Sample points around the expected position of this letter
            val startIdx = i * samplesPerLetter
            val endIdx = min((i + 1) * samplesPerLetter, swipePath.size)

            var letterScore = 0.0f
            for (j in startIdx until endIdx) {
                letterScore += getKeyProbability(swipePath[j], letter)
            }

            totalScore += letterScore / (endIdx - startIdx)
        }

        return totalScore / lowerWord.length
    }

    /**
     * Get statistics for debugging
     */
    fun getStats(): String {
        return buildString {
            append("GaussianKeyModel Statistics:\n")
            append("- Keyboard: ${keyboardWidth}x${keyboardHeight}\n")
            append("- Keys: ${keyLayout.size}\n")
            append("- Sigma factors: X=$SIGMA_X_FACTOR, Y=$SIGMA_Y_FACTOR\n")
            append("- Min probability threshold: $MIN_PROBABILITY\n")
        }
    }
}
