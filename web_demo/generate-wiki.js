#!/usr/bin/env node
/**
 * CleverKeys Wiki Page Generator
 *
 * Reads wiki-config.json and generates HTML pages for the user guide.
 * Run with: node generate-wiki.js
 */

const fs = require('fs');
const path = require('path');

// Simple markdown to HTML converter (handles common patterns)
function markdownToHtml(markdown) {
    let html = markdown
        // Tables (must be before other processing)
        .replace(/^\|(.+)\|$/gm, (match, content) => {
            const cells = content.split('|').map(c => c.trim());
            if (cells.every(c => /^[-:]+$/.test(c))) {
                return '<!-- table separator -->';
            }
            const isHeader = markdown.indexOf(match) < markdown.indexOf('|---');
            const tag = isHeader ? 'th' : 'td';
            const cellClass = isHeader ? 'px-4 py-2 text-left font-semibold bg-ck-card' : 'px-4 py-2 border-t border-gray-700';
            return `<tr>${cells.map(c => `<${tag} class="${cellClass}">${c}</${tag}>`).join('')}</tr>`;
        })
        // Wrap table rows
        .replace(/((?:<tr>.*<\/tr>\n?)+)/g, '<table class="w-full mb-6 border-collapse">$1</table>')
        // Remove table separator comments
        .replace(/<!-- table separator -->\n?/g, '')
        // Code blocks (must be before inline code)
        .replace(/```(\w+)?\n([\s\S]*?)```/g, (_, lang, code) =>
            `<pre class="bg-ck-dark p-4 rounded-lg overflow-x-auto mb-4"><code class="language-${lang || 'text'}">${escapeHtml(code.trim())}</code></pre>`)
        // Inline code
        .replace(/`([^`]+)`/g, '<code class="bg-ck-dark px-1.5 py-0.5 rounded text-green-300">$1</code>')
        // Headers
        .replace(/^### (.+)$/gm, '<h3 class="text-xl font-semibold mt-6 mb-3 text-green-300">$1</h3>')
        .replace(/^## (.+)$/gm, '<h2 class="text-2xl font-bold mt-8 mb-4 text-green-400">$1</h2>')
        .replace(/^# (.+)$/gm, '<h1 class="text-3xl font-bold mb-6 gradient-text-wiki">$1</h1>')
        // Bold and italic
        .replace(/\*\*([^*]+)\*\*/g, '<strong class="text-white">$1</strong>')
        .replace(/\*([^*]+)\*/g, '<em>$1</em>')
        // Lists
        .replace(/^- \[x\] (.+)$/gm, '<li class="flex items-center gap-2"><span class="text-green-400">&#10003;</span> $1</li>')
        .replace(/^- \[ \] (.+)$/gm, '<li class="flex items-center gap-2"><span class="text-gray-500">&#9633;</span> $1</li>')
        .replace(/^- (.+)$/gm, '<li class="ml-4 text-gray-300">&#8226; $1</li>')
        .replace(/^(\d+)\. (.+)$/gm, '<li class="ml-4 text-gray-300">$1. $2</li>')
        // Horizontal rules
        .replace(/^---$/gm, '<hr class="border-gray-700 my-6">')
        // Links
        .replace(/\[([^\]]+)\]\(([^)]+)\)/g, (_, text, url) => {
            // Convert .md links to .html for internal links
            const href = url.endsWith('.md') ? url.replace('.md', '.html') : url;
            return `<a href="${href}" class="text-green-400 hover:underline">${text}</a>`;
        })
        // Blockquotes
        .replace(/^> (.+)$/gm, '<blockquote class="border-l-4 border-green-500 pl-4 py-2 my-4 bg-ck-card rounded-r text-gray-300">$1</blockquote>')
        // Paragraphs (simple approach - wrap non-tagged lines)
        .replace(/^(?!<[h123ltpbu]|<pre|<hr|<ul|<ol|<table|<block)(.+)$/gm, '<p class="mb-4 text-gray-300 leading-relaxed">$1</p>');

    // Wrap consecutive list items in ul
    html = html.replace(/(<li[^>]*>.*?<\/li>\n?)+/g, '<ul class="mb-4 space-y-2">$&</ul>');

    return html;
}

function escapeHtml(text) {
    return text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}

function generateWikiPage(page, markdown, config, allPages) {
    const content = markdownToHtml(markdown);
    const category = config.categories.find(c => c.name === page.category);
    const categoryColor = category?.color || '#10b981';
    
    // Find prev/next pages
    const pageIndex = allPages.findIndex(p => p.file === page.file);
    const prevPage = pageIndex > 0 ? allPages[pageIndex - 1] : null;
    const nextPage = pageIndex < allPages.length - 1 ? allPages[pageIndex + 1] : null;

    return `<!DOCTYPE html>
<html lang="en" class="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${page.title} - CleverKeys User Guide</title>
    <meta name="description" content="${page.description}">
    <script src="https://cdn.tailwindcss.com"></script>
    <script>
        tailwind.config = {
            darkMode: 'class',
            theme: {
                extend: {
                    colors: {
                        'ck-green': '#10b981',
                        'ck-green-dark': '#059669',
                        'ck-green-light': '#34d399',
                        'ck-dark': '#0f0f1a',
                        'ck-surface': '#1a1a2e',
                        'ck-card': '#242438',
                    }
                }
            }
        }
    </script>
    <style>
        .gradient-text-wiki {
            background: linear-gradient(135deg, #10b981 0%, #34d399 50%, #10b981 100%);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            background-clip: text;
        }
    </style>
</head>
<body class="bg-ck-dark text-gray-100 min-h-screen">
    <!-- Nav -->
    <nav class="sticky top-0 bg-ck-dark/95 backdrop-blur border-b border-gray-800 z-50">
        <div class="container mx-auto px-6 py-4 flex justify-between items-center">
            <a href="../" class="flex items-center gap-3">
                <img src="https://raw.githubusercontent.com/tribixbite/CleverKeys/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="CleverKeys" class="w-8 h-8 rounded-lg">
                <span class="font-bold">CleverKeys</span>
            </a>
            <div class="flex gap-4">
                <a href="./" class="text-green-400 font-medium">User Guide</a>
                <a href="../specs/" class="text-gray-400 hover:text-white transition-colors">Tech Specs</a>
                <a href="../" class="text-gray-400 hover:text-white transition-colors">Home</a>
            </div>
        </div>
    </nav>

    <!-- Breadcrumb -->
    <div class="container mx-auto px-6 py-4">
        <div class="flex items-center gap-2 text-sm text-gray-500">
            <a href="../" class="hover:text-ck-green">Home</a>
            <span>/</span>
            <a href="./" class="hover:text-ck-green">User Guide</a>
            <span>/</span>
            <a href="./?category=${encodeURIComponent(page.category)}" class="hover:text-ck-green">${page.category}</a>
            <span>/</span>
            <span class="text-gray-300">${page.title}</span>
        </div>
    </div>

    <!-- Header -->
    <header class="container mx-auto px-6 pb-6">
        <div class="flex items-center gap-3 mb-4">
            <span class="px-3 py-1 text-sm rounded-full" style="background: ${categoryColor}20; color: ${categoryColor}">${page.category}</span>
            <span class="px-3 py-1 text-sm rounded-full bg-ck-card text-gray-400">${page.priority}</span>
        </div>
        <h1 class="text-4xl font-bold gradient-text-wiki mb-2">${page.title}</h1>
        <p class="text-xl text-gray-400">${page.description}</p>
    </header>

    <!-- Content -->
    <main class="container mx-auto px-6 pb-12">
        <div class="bg-ck-surface rounded-2xl p-8 max-w-4xl">
            ${content}
        </div>
        
        <!-- Prev/Next Navigation -->
        <div class="max-w-4xl mt-8 flex justify-between">
            ${prevPage ? `<a href="${prevPage.file.replace('.md', '.html')}" class="flex items-center gap-2 px-4 py-2 bg-ck-card rounded-lg hover:bg-ck-surface transition-colors">
                <span>&larr;</span>
                <span class="text-sm text-gray-400">${prevPage.title}</span>
            </a>` : '<div></div>'}
            ${nextPage ? `<a href="${nextPage.file.replace('.md', '.html')}" class="flex items-center gap-2 px-4 py-2 bg-ck-card rounded-lg hover:bg-ck-surface transition-colors">
                <span class="text-sm text-gray-400">${nextPage.title}</span>
                <span>&rarr;</span>
            </a>` : '<div></div>'}
        </div>
    </main>

    <!-- Footer -->
    <footer class="container mx-auto px-6 py-8 text-center text-gray-500 text-sm border-t border-gray-800">
        <p>CleverKeys User Guide - Making typing smarter</p>
    </footer>
</body>
</html>`;
}

function generateIndexPage(config) {
    const pagesByCategory = {};
    config.pages.forEach(page => {
        if (!pagesByCategory[page.category]) {
            pagesByCategory[page.category] = [];
        }
        pagesByCategory[page.category].push(page);
    });

    let categorySections = '';
    for (const category of config.categories) {
        const pages = pagesByCategory[category.name] || [];
        if (pages.length === 0) continue;

        const pageCards = pages.map(page => `
            <a href="${page.file.replace('.md', '.html')}" class="block bg-ck-card p-4 rounded-xl hover:bg-ck-surface transition-colors border border-transparent hover:border-ck-green/30">
                <h3 class="font-semibold mb-1 text-white">${page.title}</h3>
                <p class="text-sm text-gray-400">${page.description}</p>
            </a>
        `).join('');

        categorySections += `
        <section class="mb-10">
            <h2 class="text-xl font-bold mb-4 flex items-center gap-2">
                <span class="w-3 h-3 rounded-full" style="background: ${category.color}"></span>
                ${category.name}
            </h2>
            <div class="grid md:grid-cols-2 gap-4">
                ${pageCards}
            </div>
        </section>`;
    }

    return `<!DOCTYPE html>
<html lang="en" class="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>User Guide - CleverKeys</title>
    <meta name="description" content="Comprehensive user documentation for CleverKeys keyboard">
    <script src="https://cdn.tailwindcss.com"></script>
    <script>
        tailwind.config = {
            darkMode: 'class',
            theme: {
                extend: {
                    colors: {
                        'ck-green': '#10b981',
                        'ck-green-dark': '#059669',
                        'ck-green-light': '#34d399',
                        'ck-dark': '#0f0f1a',
                        'ck-surface': '#1a1a2e',
                        'ck-card': '#242438',
                    }
                }
            }
        }
    </script>
    <style>
        .gradient-text-wiki {
            background: linear-gradient(135deg, #10b981 0%, #34d399 50%, #10b981 100%);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            background-clip: text;
        }
    </style>
</head>
<body class="bg-ck-dark text-gray-100 min-h-screen">
    <!-- Nav -->
    <nav class="sticky top-0 bg-ck-dark/95 backdrop-blur border-b border-gray-800 z-50">
        <div class="container mx-auto px-6 py-4 flex justify-between items-center">
            <a href="../" class="flex items-center gap-3">
                <img src="https://raw.githubusercontent.com/tribixbite/CleverKeys/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="CleverKeys" class="w-8 h-8 rounded-lg">
                <span class="font-bold">CleverKeys</span>
            </a>
            <div class="flex gap-4">
                <span class="text-green-400 font-medium">User Guide</span>
                <a href="../specs/" class="text-gray-400 hover:text-white transition-colors">Tech Specs</a>
                <a href="../" class="text-gray-400 hover:text-white transition-colors">Home</a>
            </div>
        </div>
    </nav>

    <!-- Header -->
    <header class="container mx-auto px-6 py-12 text-center">
        <h1 class="text-4xl font-bold gradient-text-wiki mb-4">${config.title}</h1>
        <p class="text-xl text-gray-400 max-w-2xl mx-auto">${config.description}</p>
        
        <!-- Search placeholder -->
        <div class="mt-8 max-w-md mx-auto">
            <input type="text" id="wiki-search" placeholder="Search documentation..." 
                   class="w-full px-4 py-3 bg-ck-surface border border-gray-700 rounded-xl focus:outline-none focus:border-ck-green text-gray-100">
        </div>
    </header>

    <!-- Content -->
    <main class="container mx-auto px-6 pb-20 max-w-4xl">
        ${categorySections}
    </main>

    <!-- Footer -->
    <footer class="container mx-auto px-6 py-8 text-center text-gray-500 text-sm border-t border-gray-800">
        <p>CleverKeys - Open Source Neural Keyboard</p>
    </footer>

    <script>
        // Simple client-side search
        document.getElementById('wiki-search').addEventListener('input', function(e) {
            const query = e.target.value.toLowerCase();
            document.querySelectorAll('section a').forEach(card => {
                const text = card.textContent.toLowerCase();
                card.style.display = text.includes(query) ? 'block' : 'none';
            });
        });
    </script>
</body>
</html>`;
}

// Main execution
function main() {
    const configPath = path.join(__dirname, 'wiki-config.json');
    const config = JSON.parse(fs.readFileSync(configPath, 'utf-8'));

    const wikiDir = path.resolve(__dirname, config.wiki_directory);
    const outputDir = path.resolve(__dirname, config.output_directory);

    // Create output directory and subdirectories
    if (!fs.existsSync(outputDir)) {
        fs.mkdirSync(outputDir, { recursive: true });
    }
    
    // Create category subdirectories in output
    for (const cat of config.categories) {
        const catDir = path.join(outputDir, cat.slug);
        if (!fs.existsSync(catDir)) {
            fs.mkdirSync(catDir, { recursive: true });
        }
    }

    console.log('Generating CleverKeys wiki pages...');
    console.log(`Source: ${wikiDir}`);
    console.log(`Output: ${outputDir}`);
    console.log('');

    // Generate individual wiki pages
    let generated = 0;
    let skipped = 0;
    const allPages = config.pages;
    
    for (const page of config.pages) {
        const mdPath = path.join(wikiDir, page.file);
        if (!fs.existsSync(mdPath)) {
            console.log(`  SKIP: ${page.file} (not found)`);
            skipped++;
            continue;
        }

        const markdown = fs.readFileSync(mdPath, 'utf-8');
        const html = generateWikiPage(page, markdown, config, allPages);
        const outputPath = path.join(outputDir, page.file.replace('.md', '.html'));
        
        // Ensure parent directory exists
        const parentDir = path.dirname(outputPath);
        if (!fs.existsSync(parentDir)) {
            fs.mkdirSync(parentDir, { recursive: true });
        }
        
        fs.writeFileSync(outputPath, html);
        console.log(`  OK: ${page.title} -> ${page.file.replace('.md', '.html')}`);
        generated++;
    }

    // Generate index page
    const indexHtml = generateIndexPage(config);
    fs.writeFileSync(path.join(outputDir, 'index.html'), indexHtml);
    console.log(`  OK: Index page -> index.html`);

    console.log('');
    console.log(`Generated ${generated + 1} pages (${skipped} skipped - content pending).`);
}

main();
