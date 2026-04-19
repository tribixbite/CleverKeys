/**
 * SwipeVocabulary - Optimized vocabulary filtering for swipe gesture predictions
 * 
 * This class provides ultra-fast word validation and ranking for neural network
 * swipe gesture outputs, using frequency data and intelligent caching.
 */

class SwipeVocabulary {
    constructor() {
        this.wordFreq = new Map();
        this.commonWords = new Set();
        this.wordsByLength = new Map();
        this.top5000 = new Set();
        this.keyboardAdjacency = null;
        this.minFreqByLength = null;
        this.isLoaded = false;
    }

    /**
     * Load a flat frequency dictionary — the APK's en_enhanced.json shape
     * ({word: freq, ...}). Synthesizes common_words / top5000 / wordsByLength
     * from the raw frequencies so the rest of the filter pipeline works
     * identically regardless of which dict is loaded.
     */
    async loadFromFlatFreq(url) {
        try {
            console.log('Loading flat-freq dictionary from:', url);
            const response = await fetch(url, { cache: 'force-cache' });
            if (!response.ok) throw new Error(`Flat-freq fetch failed: HTTP ${response.status}`);
            const data = await response.json();
            const entries = Object.entries(data);
            if (!entries.length) throw new Error('Flat-freq dict is empty');

            this.wordFreq = new Map(entries);

            // Highest-frequency 5000 become the "common" pool used to promote
            // swipe candidates that look like real words.
            entries.sort((a, b) => b[1] - a[1]);
            const top5k = entries.slice(0, Math.min(5000, entries.length));
            this.top5000 = new Set(top5k.map((e) => e[0]));
            this.commonWords = new Set(entries.slice(0, Math.min(1000, entries.length)).map((e) => e[0]));

            this.wordsByLength = new Map();
            for (const [word] of entries) {
                const len = word.length;
                if (!this.wordsByLength.has(len)) this.wordsByLength.set(len, []);
                this.wordsByLength.get(len).push(word);
            }

            // No adjacency graph in the flat dict; the filter tolerates null.
            this.keyboardAdjacency = null;
            // Per-length 20th-percentile frequency as a minimum floor so the
            // filter's sanity thresholds don't admit ultra-rare words ahead of
            // common ones.
            this.minFreqByLength = {};
            for (const [len, words] of this.wordsByLength) {
                const freqs = words.map((w) => this.wordFreq.get(w) || 0);
                freqs.sort((a, b) => a - b);
                this.minFreqByLength[len] = freqs[Math.floor(freqs.length * 0.2)] || 0;
            }

            this.isLoaded = true;
            console.log(`Loaded ${this.wordFreq.size} words from flat-freq dict`);
            return true;
        } catch (error) {
            console.error('Error loading flat-freq dictionary:', error);
            return false;
        }
    }

    /**
     * Load vocabulary from JSON file with frequency data
     */
    async loadFromJSON(url) {
        try {
            console.log('Loading optimized vocabulary from:', url);
            const response = await fetch(url);
            
            if (!response.ok) {
                throw new Error(`Failed to load vocabulary: ${response.status}`);
            }
            
            const data = await response.json();
            console.log('Vocabulary data loaded:', data.metadata);
            
            // Load word frequencies
            this.wordFreq = new Map(Object.entries(data.word_frequencies));
            
            // Load common words set for fast path
            this.commonWords = new Set(data.common_words);
            
            // Load top 5000 for quick filtering
            this.top5000 = new Set(data.top_5000 || data.top5000);
            
            // Load words by length
            this.wordsByLength = new Map();
            for (const [length, words] of Object.entries(data.words_by_length)) {
                this.wordsByLength.set(parseInt(length), words);
            }
            
            // Load configuration
            this.keyboardAdjacency = data.keyboard_adjacency;
            this.minFreqByLength = data.min_frequency_by_length;
            
            this.isLoaded = true;
            console.log(`Loaded ${this.wordFreq.size} words with frequency data`);
            
            return true;
            
        } catch (error) {
            console.error('Error loading vocabulary JSON:', error);
            this.loadFallback();
            return false;
        }
    }

    /**
     * Load fallback vocabulary if JSON fails
     */
    loadFallback() {
        console.log('Loading fallback vocabulary...');
        
        const fallbackWords = [
            'the', 'of', 'and', 'to', 'a', 'in', 'for', 'is', 'on', 'that',
            'by', 'this', 'with', 'i', 'you', 'it', 'not', 'or', 'be', 'are',
            'from', 'at', 'as', 'your', 'all', 'would', 'will', 'there', 'their',
            'what', 'so', 'if', 'about', 'which', 'when', 'one', 'can', 'had'
        ];
        
        this.wordFreq = new Map();
        fallbackWords.forEach((word, index) => {
            // Assign decreasing frequency based on order
            this.wordFreq.set(word, 1e-4 * Math.pow(0.9, index));
        });
        
        this.commonWords = new Set(fallbackWords.slice(0, 10));
        this.top5000 = new Set(fallbackWords);
        this.isLoaded = true;
    }

    /**
     * Filter and rank neural network predictions
     * @param {Array} predictions - Array of {word: string, confidence: number}
     * @param {Object} swipeStats - Optional statistics about the swipe
     * @returns {Array} Filtered and ranked predictions
     */
    filterPredictions(predictions, swipeStats = {}) {
        if (!this.isLoaded) {
            console.warn('Vocabulary not loaded, returning raw predictions');
            return predictions;
        }
        
        const validPredictions = [];
        const swipeLength = swipeStats.pathLength || 0;
        const swipeSpeed = swipeStats.speed || 1.0;
        
        for (const {word, confidence} of predictions) {
            const wordClean = word.toLowerCase().trim();
            
            // Skip if not a valid word format
            if (!wordClean.match(/^[a-z]+$/)) {
                continue;
            }
            
            // Fast path for common words
            if (this.commonWords.has(wordClean)) {
                const freq = this.wordFreq.get(wordClean);
                const score = this.calculateScore(confidence, freq, 1.2); // Boost common words
                validPredictions.push({
                    word: wordClean,
                    score,
                    confidence,
                    frequency: freq,
                    source: 'common'
                });
                continue;
            }
            
            // Check top 5000 words
            if (this.top5000.has(wordClean)) {
                const freq = this.wordFreq.get(wordClean);
                const score = this.calculateScore(confidence, freq, 1.0);
                validPredictions.push({
                    word: wordClean,
                    score,
                    confidence,
                    frequency: freq,
                    source: 'top5000'
                });
                continue;
            }
            
            // Check full vocabulary with frequency threshold
            if (this.wordFreq.has(wordClean)) {
                const freq = this.wordFreq.get(wordClean);
                const minFreq = this.getMinFrequency(wordClean.length);
                
                if (freq >= minFreq) {
                    const score = this.calculateScore(confidence, freq, 0.9); // Slight penalty for rare words
                    validPredictions.push({
                        word: wordClean,
                        score,
                        confidence,
                        frequency: freq,
                        source: 'vocabulary'
                    });
                }
            }
        }
        
        // Sort by score and return top results
        validPredictions.sort((a, b) => b.score - a.score);
        
        // Apply swipe-specific filtering
        if (swipeStats.expectedLength) {
            return this.filterByExpectedLength(validPredictions, swipeStats.expectedLength);
        }
        
        return validPredictions.slice(0, 10);
    }

    /**
     * Calculate combined score from NN confidence and word frequency
     */
    calculateScore(confidence, frequency, boost = 1.0) {
        // Weighted combination with logarithmic frequency scaling
        const freqScore = Math.log10(frequency + 1e-10) / -10; // Normalize to ~0-1
        const combinedScore = (0.6 * confidence + 0.4 * freqScore) * boost;
        return combinedScore;
    }

    /**
     * Get minimum frequency threshold for word length
     */
    getMinFrequency(length) {
        if (!this.minFreqByLength) {
            // Default thresholds if not loaded
            return length <= 3 ? 1e-6 : 1e-8;
        }
        
        const key = length >= 10 ? '10+' : String(length);
        return parseFloat(this.minFreqByLength[key] || 1e-9);
    }

    /**
     * Filter predictions by expected word length
     */
    filterByExpectedLength(predictions, expectedLength, tolerance = 2) {
        const filtered = predictions.filter(p => {
            const lengthDiff = Math.abs(p.word.length - expectedLength);
            return lengthDiff <= tolerance;
        });
        
        return filtered.length > 0 ? filtered : predictions.slice(0, 5);
    }

    /**
     * Get words similar to a swipe pattern (for error correction)
     */
    getSimilarWords(targetWord, maxResults = 5) {
        if (!this.isLoaded || !targetWord) return [];
        
        const targetLength = targetWord.length;
        const similar = [];
        
        // Check words of similar length
        for (let len = Math.max(2, targetLength - 1); len <= targetLength + 1; len++) {
            if (this.wordsByLength.has(len)) {
                const words = this.wordsByLength.get(len);
                
                for (const wordData of words.slice(0, 100)) { // Check top 100 of each length
                    const word = wordData.word || wordData;
                    const distance = this.levenshteinDistance(targetWord, word);
                    
                    if (distance <= 2) {
                        similar.push({
                            word,
                            distance,
                            frequency: this.wordFreq.get(word) || 0
                        });
                    }
                }
            }
        }
        
        // Sort by distance then frequency
        similar.sort((a, b) => {
            if (a.distance !== b.distance) return a.distance - b.distance;
            return b.frequency - a.frequency;
        });
        
        return similar.slice(0, maxResults);
    }

    /**
     * Calculate Levenshtein distance between two strings
     */
    levenshteinDistance(s1, s2) {
        const len1 = s1.length;
        const len2 = s2.length;
        const matrix = [];

        for (let i = 0; i <= len1; i++) {
            matrix[i] = [i];
        }

        for (let j = 0; j <= len2; j++) {
            matrix[0][j] = j;
        }

        for (let i = 1; i <= len1; i++) {
            for (let j = 1; j <= len2; j++) {
                const cost = s1[i - 1] === s2[j - 1] ? 0 : 1;
                matrix[i][j] = Math.min(
                    matrix[i - 1][j] + 1,      // deletion
                    matrix[i][j - 1] + 1,      // insertion
                    matrix[i - 1][j - 1] + cost // substitution
                );
            }
        }

        return matrix[len1][len2];
    }

    /**
     * Get word frequency (returns 0 if not found)
     */
    getWordFrequency(word) {
        return this.wordFreq.get(word.toLowerCase()) || 0;
    }

    /**
     * Check if word exists in vocabulary
     */
    hasWord(word) {
        return this.wordFreq.has(word.toLowerCase());
    }

    /**
     * Get vocabulary statistics
     */
    getStats() {
        return {
            totalWords: this.wordFreq.size,
            commonWords: this.commonWords.size,
            top5000: this.top5000.size,
            isLoaded: this.isLoaded,
            lengthDistribution: Array.from(this.wordsByLength.entries())
                .map(([len, words]) => ({length: len, count: words.length}))
                .sort((a, b) => a.length - b.length)
        };
    }
}

// Export for use in browser
if (typeof window !== 'undefined') {
    window.SwipeVocabulary = SwipeVocabulary;
}

// Export for Node.js/module systems
if (typeof module !== 'undefined' && module.exports) {
    module.exports = SwipeVocabulary;
}
