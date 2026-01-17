#!/usr/bin/env node
/**
 * Generate comprehensive emoji keyword mappings from multiple sources:
 * - Emojibase data (labels + tags)
 * - GitHub shortcodes
 * - Emojibase shortcodes
 * - CLDR shortcodes
 *
 * Output: Kotlin map entries for Emoji.kt
 */

const fs = require('fs');
const path = require('path');

const projectRoot = path.join(__dirname, '..');

// Load data files
const data = JSON.parse(fs.readFileSync(path.join(projectRoot, 'emoji_data.json'), 'utf8'));
const github = JSON.parse(fs.readFileSync(path.join(projectRoot, 'emoji_github.json'), 'utf8'));
const emojibase = JSON.parse(fs.readFileSync(path.join(projectRoot, 'emoji_emojibase.json'), 'utf8'));
const cldr = JSON.parse(fs.readFileSync(path.join(projectRoot, 'emoji_cldr.json'), 'utf8'));

// Build emoji → keywords map
const emojiKeywords = new Map();

// Process main data (labels + tags)
for (const entry of data) {
    if (!entry.emoji) continue;

    const emoji = entry.emoji;
    const keywords = new Set();

    // Add label
    if (entry.label) {
        keywords.add(entry.label.toLowerCase());
    }

    // Add tags
    if (entry.tags) {
        for (const tag of entry.tags) {
            keywords.add(tag.toLowerCase());
        }
    }

    if (!emojiKeywords.has(emoji)) {
        emojiKeywords.set(emoji, new Set());
    }
    for (const kw of keywords) {
        emojiKeywords.get(emoji).add(kw);
    }
}

// Helper to convert hexcode to emoji
function hexToEmoji(hex) {
    const codepoints = hex.split('-').map(h => parseInt(h, 16));
    return String.fromCodePoint(...codepoints);
}

// Process shortcode files
function addShortcodes(shortcodeData) {
    for (const [hex, codes] of Object.entries(shortcodeData)) {
        try {
            const emoji = hexToEmoji(hex);
            if (!emojiKeywords.has(emoji)) {
                emojiKeywords.set(emoji, new Set());
            }

            const codeList = Array.isArray(codes) ? codes : [codes];
            for (const code of codeList) {
                // Convert shortcode to searchable term (remove underscores, colons)
                const term = code.replace(/_/g, ' ').replace(/:/g, '').toLowerCase();
                emojiKeywords.get(emoji).add(term);

                // Also add without spaces for single-word search
                if (term.includes(' ')) {
                    emojiKeywords.get(emoji).add(term.replace(/ /g, ''));
                }
            }
        } catch (e) {
            // Skip invalid codepoints
        }
    }
}

addShortcodes(github);
addShortcodes(emojibase);
addShortcodes(cldr);

// Generate Kotlin code
let kotlinCode = '// Auto-generated emoji keywords from emojibase + GitHub + CLDR\n';
kotlinCode += '// Sources: Discord/Twemoji, Slack, GitHub, Google Noto, CLDR\n';
kotlinCode += '// Total emojis: ' + emojiKeywords.size + '\n\n';

const entries = [];
for (const [emoji, keywords] of emojiKeywords) {
    // Skip emoji with no useful keywords
    if (keywords.size === 0) continue;

    // Skip very long keywords or entries with too many
    const filtered = [...keywords].filter(kw =>
        kw.length >= 2 &&
        kw.length <= 30 &&
        !kw.includes('"') &&
        !kw.includes('\\')
    );

    if (filtered.length === 0) continue;

    // Limit to 8 keywords per emoji to keep file size reasonable
    const limited = filtered.slice(0, 8);

    for (const kw of limited) {
        entries.push(`"${kw}" to "${emoji}"`);
    }
}

// Sort by keyword
entries.sort();

// Group into lines of ~3-4 entries
const lines = [];
for (let i = 0; i < entries.length; i += 3) {
    lines.push(entries.slice(i, i + 3).join(', ') + ',');
}

kotlinCode += lines.join('\n');

// Write output
const outputPath = path.join(projectRoot, 'generated_emoji_keywords.kt');
fs.writeFileSync(outputPath, kotlinCode);

console.log(`Generated ${entries.length} keyword mappings for ${emojiKeywords.size} emojis`);
console.log(`Output: ${outputPath}`);

// Also output stats
const stats = {
    totalEmojis: emojiKeywords.size,
    totalKeywords: entries.length,
    sources: ['emojibase', 'github', 'cldr']
};
console.log('\nStats:', JSON.stringify(stats, null, 2));
