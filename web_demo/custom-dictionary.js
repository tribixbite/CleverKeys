/**
 * Enhanced SwipeVocabulary with Custom Dictionary Support
 * Supports importing personal dictionaries, Android word lists, and custom terms
 */

class CustomDictionaryManager {
    constructor(swipeVocabulary) {
        this.vocab = swipeVocabulary;
        this.customWords = new Map();
        this.personalWords = new Set();
        this.androidDictionary = new Map();
        this.loadFromLocalStorage();
    }

    /**
     * Load saved custom dictionaries from localStorage
     */
    loadFromLocalStorage() {
        try {
            const saved = localStorage.getItem('customDictionaries');
            if (saved) {
                const data = JSON.parse(saved);
                this.customWords = new Map(data.customWords || []);
                this.personalWords = new Set(data.personalWords || []);
                this.androidDictionary = new Map(data.androidDictionary || []);
                console.log(`Loaded ${this.customWords.size} custom words from storage`);
            }
        } catch (error) {
            console.error('Error loading custom dictionaries:', error);
        }
    }

    /**
     * Save custom dictionaries to localStorage
     */
    saveToLocalStorage() {
        try {
            const data = {
                customWords: Array.from(this.customWords.entries()),
                personalWords: Array.from(this.personalWords),
                androidDictionary: Array.from(this.androidDictionary.entries()),
                lastUpdated: new Date().toISOString()
            };
            localStorage.setItem('customDictionaries', JSON.stringify(data));
            console.log('Custom dictionaries saved to storage');
        } catch (error) {
            console.error('Error saving custom dictionaries:', error);
        }
    }

    /**
     * Import Android personal dictionary (text format)
     * Format: word\tfrequency\tlocale\tappid\tshortcut
     */
    importAndroidDictionary(text) {
        const lines = text.split('\n');
        let imported = 0;
        
        lines.forEach(line => {
            const parts = line.split('\t');
            if (parts.length >= 1) {
                const word = parts[0].toLowerCase().trim();
                const frequency = parts[1] ? parseInt(parts[1]) : 100;
                
                if (word && word.match(/^[a-z]+$/)) {
                    // Convert Android frequency (0-255) to our scale
                    const normalizedFreq = 1e-5 * (frequency / 255);
                    this.androidDictionary.set(word, normalizedFreq);
                    
                    // Also add to personal words for quick access
                    this.personalWords.add(word);
                    imported++;
                }
            }
        });
        
        this.saveToLocalStorage();
        this.mergeIntoVocabulary();
        
        return imported;
    }

    /**
     * Import custom word list (simple text format, one word per line)
     */
    importCustomWordList(text, defaultFrequency = 5e-6) {
        const words = text.split(/[\n,;]+/);
        let imported = 0;
        let skipped = 0;
        
        words.forEach(word => {
            const clean = word.toLowerCase().trim();
            if (clean && clean.match(/^[a-z]+$/) && clean.length >= 2) {
                // Check if word already exists in main vocabulary
                if (this.vocab && this.vocab.isLoaded && this.vocab.hasWord(clean)) {
                    skipped++;
                    return;
                }
                
                this.customWords.set(clean, defaultFrequency);
                this.personalWords.add(clean);
                imported++;
            }
        });
        
        if (skipped > 0) {
            console.log(`Imported ${imported} words, skipped ${skipped} words already in main vocabulary`);
        }
        
        this.saveToLocalStorage();
        this.mergeIntoVocabulary();
        
        return imported;
    }

    /**
     * Import JSON dictionary with frequencies
     */
    importJSONDictionary(jsonData) {
        let imported = 0;
        
        if (jsonData.words) {
            // Format from our custom dictionary
            for (const [word, data] of Object.entries(jsonData.words)) {
                const freq = data.frequency || data;
                this.customWords.set(word, freq);
                this.personalWords.add(word);
                imported++;
            }
        } else if (jsonData.word_frequencies) {
            // Format from main vocabulary
            for (const [word, freq] of Object.entries(jsonData.word_frequencies)) {
                this.customWords.set(word, freq);
                this.personalWords.add(word);
                imported++;
            }
        } else {
            // Simple object format
            for (const [word, freq] of Object.entries(jsonData)) {
                if (typeof word === 'string' && typeof freq === 'number') {
                    this.customWords.set(word, freq);
                    this.personalWords.add(word);
                    imported++;
                }
            }
        }
        
        this.saveToLocalStorage();
        this.mergeIntoVocabulary();
        
        return imported;
    }

    /**
     * Add a single word manually
     */
    addWord(word, frequency = 1e-5) {
        const clean = word.toLowerCase().trim();
        if (clean && clean.match(/^[a-z]+$/)) {
            // Check if word already exists in main vocabulary
            if (this.vocab && this.vocab.isLoaded && this.vocab.hasWord(clean)) {
                console.log(`Word "${clean}" already exists in main vocabulary, skipping custom addition`);
                return false;
            }
            
            this.customWords.set(clean, frequency);
            this.personalWords.add(clean);
            this.saveToLocalStorage();
            this.mergeIntoVocabulary();
            return true;
        }
        return false;
    }

    /**
     * Remove a word from custom dictionary
     */
    removeWord(word) {
        const clean = word.toLowerCase().trim();
        const removed = this.customWords.delete(clean);
        this.personalWords.delete(clean);
        this.androidDictionary.delete(clean);
        
        if (removed) {
            this.saveToLocalStorage();
            this.mergeIntoVocabulary();
        }
        
        return removed;
    }

    /**
     * Merge custom words into main vocabulary
     */
    mergeIntoVocabulary() {
        if (!this.vocab || !this.vocab.isLoaded) {
            console.warn('Main vocabulary not loaded, cannot merge');
            return;
        }
        
        // Add all custom words with boost
        let merged = 0;
        const boostFactor = 1.5; // Boost personal words
        
        // Merge Android dictionary
        for (const [word, freq] of this.androidDictionary) {
            this.vocab.wordFreq.set(word, freq * boostFactor);
            merged++;
        }
        
        // Merge custom words
        for (const [word, freq] of this.customWords) {
            // Use higher frequency if word exists
            const existingFreq = this.vocab.wordFreq.get(word) || 0;
            this.vocab.wordFreq.set(word, Math.max(existingFreq, freq * boostFactor));
            merged++;
        }
        
        // Update common words set if needed
        for (const word of this.personalWords) {
            const freq = this.vocab.wordFreq.get(word);
            if (freq && freq > 1e-5) {
                this.vocab.commonWords.add(word);
            }
        }
        
        console.log(`Merged ${merged} custom words into vocabulary`);
    }

    /**
     * Add a single word to personal dictionary
     */
    addPersonalWord(word) {
        const clean = word.toLowerCase().trim();
        if (clean && clean.match(/^[a-z]+$/)) {
            // Check if word already exists in main vocabulary
            if (this.vocab && this.vocab.isLoaded && this.vocab.hasWord(clean)) {
                console.log(`Word "${clean}" already exists in main vocabulary, not adding to personal dictionary`);
                return false;
            }
            
            this.personalWords.add(clean);
            this.customWords.set(clean, 8e-6); // Higher frequency for personal words
            this.saveToLocalStorage();
            this.mergeIntoVocabulary();
            return true;
        }
        return false;
    }

    /**
     * Remove a word from personal dictionary
     */
    removePersonalWord(word) {
        const clean = word.toLowerCase().trim();
        this.personalWords.delete(clean);
        this.customWords.delete(clean);
        this.saveToLocalStorage();
        // Note: We don't remove from main vocabulary as it might be a base word
        return true;
    }

    /**
     * Export all custom words to text format
     */
    exportAll() {
        const allWords = new Set([
            ...this.personalWords,
            ...this.customWords.keys(),
            ...this.androidDictionary.keys()
        ]);
        
        return Array.from(allWords).sort().join('\n');
    }

    /**
     * Export custom dictionary as JSON
     */
    exportAsJSON() {
        return {
            metadata: {
                totalWords: this.customWords.size + this.androidDictionary.size,
                personalWords: this.personalWords.size,
                exportDate: new Date().toISOString()
            },
            customWords: Object.fromEntries(this.customWords),
            androidDictionary: Object.fromEntries(this.androidDictionary),
            personalWords: Array.from(this.personalWords)
        };
    }

    /**
     * Export as Android-compatible dictionary
     */
    exportAsAndroid() {
        const lines = [];
        const allWords = new Map([...this.androidDictionary, ...this.customWords]);
        
        for (const [word, freq] of allWords) {
            // Convert frequency back to Android scale (0-255)
            const androidFreq = Math.round((freq / 1e-5) * 255);
            lines.push(`${word}\t${androidFreq}\ten_US\tswipe\t`);
        }
        
        return lines.join('\n');
    }

    /**
     * Get statistics about custom dictionaries
     */
    getStats() {
        return {
            customWords: this.customWords.size,
            personalWords: this.personalWords.size,
            androidWords: this.androidDictionary.size,
            totalCustom: this.customWords.size + this.androidDictionary.size
        };
    }

    /**
     * Clear all custom dictionaries
     */
    clearAll() {
        this.customWords.clear();
        this.personalWords.clear();
        this.androidDictionary.clear();
        localStorage.removeItem('customDictionaries');
        console.log('All custom dictionaries cleared');
    }
}

// Extend the main SwipeVocabulary class
if (typeof SwipeVocabulary !== 'undefined') {
    SwipeVocabulary.prototype.initCustomDictionary = function() {
        this.customDict = new CustomDictionaryManager(this);
        return this.customDict;
    };
    
    // Override filterPredictions to prioritize personal words
    const originalFilter = SwipeVocabulary.prototype.filterPredictions;
    SwipeVocabulary.prototype.filterPredictions = function(predictions, swipeStats = {}) {
        const results = originalFilter.call(this, predictions, swipeStats);
        
        // Boost personal words in results
        if (this.customDict && this.customDict.personalWords.size > 0) {
            results.forEach(result => {
                if (this.customDict.personalWords.has(result.word)) {
                    result.score *= 1.3; // 30% boost for personal words
                    result.source = 'personal';
                }
            });
            
            // Re-sort after boosting
            results.sort((a, b) => b.score - a.score);
        }
        
        return results;
    };
}

// Export for use
if (typeof window !== 'undefined') {
    window.CustomDictionaryManager = CustomDictionaryManager;
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = CustomDictionaryManager;
}
