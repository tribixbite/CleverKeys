#!/usr/bin/env node
/**
 * Generate inverted emoji keyword index as TSV for fast loading.
 * Format: keyword\temoji1,emoji2,emoji3
 *
 * Sources: Emojibase, GitHub shortcodes, CLDR
 */

const fs = require('fs');
const path = require('path');

const projectRoot = path.join(__dirname, '..');

// Load data files
const data = JSON.parse(fs.readFileSync(path.join(projectRoot, 'emoji_data.json'), 'utf8'));
const github = JSON.parse(fs.readFileSync(path.join(projectRoot, 'emoji_github.json'), 'utf8'));
const emojibase = JSON.parse(fs.readFileSync(path.join(projectRoot, 'emoji_emojibase.json'), 'utf8'));
const cldr = JSON.parse(fs.readFileSync(path.join(projectRoot, 'emoji_cldr.json'), 'utf8'));

// Build keyword → emojis map (inverted index)
const keywordToEmojis = new Map();

function addKeyword(keyword, emoji) {
    // Normalize keyword
    const kw = keyword.toLowerCase().trim();
    if (kw.length < 2 || kw.length > 30) return;
    if (kw.includes('\t') || kw.includes('\n')) return;

    if (!keywordToEmojis.has(kw)) {
        keywordToEmojis.set(kw, new Set());
    }
    keywordToEmojis.get(kw).add(emoji);
}

// Process main data (labels + tags)
for (const entry of data) {
    if (!entry.emoji) continue;
    const emoji = entry.emoji;

    // Add label
    if (entry.label) {
        addKeyword(entry.label, emoji);
        // Also add individual words from multi-word labels
        for (const word of entry.label.split(/\s+/)) {
            if (word.length >= 2) addKeyword(word, emoji);
        }
    }

    // Add tags
    if (entry.tags) {
        for (const tag of entry.tags) {
            addKeyword(tag, emoji);
        }
    }
}

// Helper to convert hexcode to emoji
function hexToEmoji(hex) {
    const codepoints = hex.split('-').map(h => parseInt(h, 16));
    return String.fromCodePoint(...codepoints);
}

// Process shortcode files
function processShortcodes(shortcodeData) {
    for (const [hex, codes] of Object.entries(shortcodeData)) {
        try {
            const emoji = hexToEmoji(hex);
            const codeList = Array.isArray(codes) ? codes : [codes];

            for (const code of codeList) {
                // Convert shortcode to searchable terms
                const term = code.replace(/_/g, ' ').replace(/:/g, '').toLowerCase();
                addKeyword(term, emoji);

                // Add individual words
                for (const word of term.split(/\s+/)) {
                    if (word.length >= 2) addKeyword(word, emoji);
                }
            }
        } catch (e) {
            // Skip invalid codepoints
        }
    }
}

processShortcodes(github);
processShortcodes(emojibase);
processShortcodes(cldr);

// Generate TSV output
const lines = [];
const sortedKeywords = [...keywordToEmojis.keys()].sort();

for (const keyword of sortedKeywords) {
    const emojis = [...keywordToEmojis.get(keyword)];
    // Limit emojis per keyword to prevent huge lines
    const limited = emojis.slice(0, 20);
    lines.push(`${keyword}\t${limited.join(',')}`);
}

// Write to assets folder
const assetsDir = path.join(projectRoot, 'src/main/assets');
if (!fs.existsSync(assetsDir)) {
    fs.mkdirSync(assetsDir, { recursive: true });
}

const outputPath = path.join(assetsDir, 'emoji_keywords.tsv');
fs.writeFileSync(outputPath, lines.join('\n'));

console.log(`Generated TSV with ${lines.length} keywords`);
console.log(`Total emoji mappings: ${[...keywordToEmojis.values()].reduce((sum, set) => sum + set.size, 0)}`);
console.log(`Output: ${outputPath}`);
console.log(`File size: ${(fs.statSync(outputPath).size / 1024).toFixed(1)} KB`);
