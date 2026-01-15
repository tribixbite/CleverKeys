#!/usr/bin/env node
/**
 * CleverKeys Wiki Page Generator
 *
 * Reads wiki-config.json and generates HTML pages for each wiki article.
 * Features: search index, prev/next navigation, table of contents sidebar.
 *
 * Run with: node generate-wiki.js
 */

const fs = require('fs');
const path = require('path');

// Enhanced markdown to HTML converter with wiki-specific features
function markdownToHtml(markdown, options = {}) {
    let html = markdown
        // Remove YAML frontmatter
        .replace(/^---[\s\S]*?---\n*/m, '')
        // Code blocks (must be before inline code)
        .replace(/```(\w+)?\n([\s\S]*?)```/g, (_, lang, code) =>
            `<pre class="bg-wiki-dark p-4 rounded-lg overflow-x-auto my-4"><code class="language-${lang || 'text'}">${escapeHtml(code.trim())}</code></pre>`)
        // Inline code
        .replace(/`([^`]+)`/g, '<code class="bg-wiki-dark px-1.5 py-0.5 rounded text-wiki-primary">$1</code>')
        // Callout boxes: > [!TIP], > [!NOTE], > [!WARNING]
        .replace(/^>\s*\[!TIP\]\s*\n((?:>.*\n?)+)/gm, (_, content) => {
            const text = content.replace(/^>\s?/gm, '').trim();
            return `<div class="callout callout-tip bg-emerald-900/20 border-l-4 border-emerald-500 p-4 my-4 rounded-r">
                <div class="font-semibold text-emerald-400 mb-1">Tip</div>
                <div class="text-gray-300">${text}</div>
            </div>`;
        })
        .replace(/^>\s*\[!NOTE\]\s*\n((?:>.*\n?)+)/gm, (_, content) => {
            const text = content.replace(/^>\s?/gm, '').trim();
            return `<div class="callout callout-note bg-blue-900/20 border-l-4 border-blue-500 p-4 my-4 rounded-r">
                <div class="font-semibold text-blue-400 mb-1">Note</div>
                <div class="text-gray-300">${text}</div>
            </div>`;
        })
        .replace(/^>\s*\[!WARNING\]\s*\n((?:>.*\n?)+)/gm, (_, content) => {
            const text = content.replace(/^>\s?/gm, '').trim();
            return `<div class="callout callout-warning bg-amber-900/20 border-l-4 border-amber-500 p-4 my-4 rounded-r">
                <div class="font-semibold text-amber-400 mb-1">Warning</div>
                <div class="text-gray-300">${text}</div>
            </div>`;
        })
        // Keyboard shortcuts: [[Ctrl+K]] -> <kbd>
        .replace(/\[\[([^\]]+)\]\]/g, '<kbd class="bg-wiki-card px-2 py-1 rounded border border-wiki-border text-sm">$1</kbd>')
        // Tables
        .replace(/^\|(.+)\|\n\|[-:\s|]+\|\n((?:\|.+\|\n?)+)/gm, (_, header, body) => {
            const headers = header.split('|').filter(h => h.trim()).map(h =>
                `<th class="px-4 py-2 text-left border-b border-wiki-border">${h.trim()}</th>`
            ).join('');
            const rows = body.trim().split('\n').map(row => {
                const cells = row.split('|').filter(c => c.trim()).map(c =>
                    `<td class="px-4 py-2 border-b border-wiki-border/50">${c.trim()}</td>`
                ).join('');
                return `<tr>${cells}</tr>`;
            }).join('');
            return `<div class="overflow-x-auto my-4"><table class="w-full border-collapse"><thead class="bg-wiki-card"><tr>${headers}</tr></thead><tbody>${rows}</tbody></table></div>`;
        })
        // Headers with IDs for TOC
        .replace(/^### (.+)$/gm, (_, text) => {
            const id = text.toLowerCase().replace(/[^a-z0-9]+/g, '-');
            return `<h3 id="${id}" class="text-xl font-semibold mt-8 mb-3 text-wiki-primary-light scroll-mt-20">${text}</h3>`;
        })
        .replace(/^## (.+)$/gm, (_, text) => {
            const id = text.toLowerCase().replace(/[^a-z0-9]+/g, '-');
            return `<h2 id="${id}" class="text-2xl font-bold mt-10 mb-4 text-white scroll-mt-20">${text}</h2>`;
        })
        .replace(/^# (.+)$/gm, (_, text) => `<h1 class="text-3xl font-bold mb-6 text-white">${text}</h1>`)
        // Bold and italic
        .replace(/\*\*([^*]+)\*\*/g, '<strong class="font-semibold text-white">$1</strong>')
        .replace(/\*([^*]+)\*/g, '<em>$1</em>')
        // Lists
        .replace(/^- \[x\] (.+)$/gm, '<li class="flex items-start gap-2 py-1"><span class="text-emerald-400 mt-0.5">&#10003;</span> <span>$1</span></li>')
        .replace(/^- \[ \] (.+)$/gm, '<li class="flex items-start gap-2 py-1"><span class="text-gray-500 mt-0.5">&#9633;</span> <span>$1</span></li>')
        .replace(/^- (.+)$/gm, '<li class="ml-4 py-1">&#8226; $1</li>')
        .replace(/^(\d+)\. (.+)$/gm, '<li class="ml-4 py-1"><span class="text-wiki-primary mr-2">$1.</span> $2</li>')
        // Horizontal rules
        .replace(/^---$/gm, '<hr class="border-wiki-border my-8">')
        // Links
        .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" class="text-wiki-accent hover:underline">$1</a>')
        // Blockquotes (general)
        .replace(/^>\s(.+)$/gm, '<blockquote class="border-l-4 border-wiki-border pl-4 my-4 text-gray-400 italic">$1</blockquote>')
        // Paragraphs
        .replace(/^(?!<[h123lptdbu]|<pre|<hr|<ul|<ol|<div|<blockquote)(.+)$/gm, '<p class="mb-4 text-gray-300 leading-relaxed">$1</p>');

    // Wrap consecutive list items in ul
    html = html.replace(/(<li[^>]*>[\s\S]*?<\/li>\s*)+/g, '<ul class="mb-4 space-y-1">$&</ul>');

    return html;
}

function escapeHtml(text) {
    return text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}

// Extract table of contents from HTML
function extractToc(html) {
    const toc = [];
    const regex = /<h([23]) id="([^"]+)"[^>]*>([^<]+)<\/h[23]>/g;
    let match;
    while ((match = regex.exec(html)) !== null) {
        toc.push({
            level: parseInt(match[1]),
            id: match[2],
            text: match[3]
        });
    }
    return toc;
}

// Get prev/next pages for navigation
function getPrevNext(currentIndex, pages) {
    return {
        prev: currentIndex > 0 ? pages[currentIndex - 1] : null,
        next: currentIndex < pages.length - 1 ? pages[currentIndex + 1] : null
    };
}

// Generate search index
function generateSearchIndex(pages, wikiDir) {
    return pages.map(page => {
        const mdPath = path.join(wikiDir, page.file);
        let excerpt = '';
        if (fs.existsSync(mdPath)) {
            const content = fs.readFileSync(mdPath, 'utf-8')
                .replace(/^---[\s\S]*?---\n*/m, '') // Remove frontmatter
                .replace(/[#*`\[\]|]/g, '') // Remove markdown
                .substring(0, 200);
            excerpt = content.trim();
        }
        return {
            file: page.file.replace('.md', '.html'),
            title: page.title,
            description: page.description,
            category: page.category,
            tags: page.tags || [],
            excerpt
        };
    });
}

function generateWikiPage(page, markdown, config, pageIndex, allPages) {
    const content = markdownToHtml(markdown);
    const toc = extractToc(content);
    const { prev, next } = getPrevNext(pageIndex, allPages);
    const category = config.categories.find(c => c.name === page.category);
    const categoryColor = category?.color || '#10b981';

    const tocHtml = toc.length > 0 ? `
        <nav class="hidden lg:block sticky top-24 w-64 shrink-0">
            <div class="text-sm font-semibold text-gray-400 uppercase mb-3">On This Page</div>
            <ul class="space-y-2 text-sm border-l border-wiki-border pl-4">
                ${toc.map(item => `
                    <li class="${item.level === 3 ? 'ml-3' : ''}">
                        <a href="#${item.id}" class="text-gray-400 hover:text-wiki-primary transition-colors">${item.text}</a>
                    </li>
                `).join('')}
            </ul>
        </nav>
    ` : '';

    const prevNextHtml = `
        <div class="flex justify-between items-center mt-12 pt-8 border-t border-wiki-border">
            ${prev ? `
                <a href="${prev.file.replace('.md', '.html')}" class="group flex items-center gap-2 text-gray-400 hover:text-wiki-primary transition-colors">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 19l-7-7 7-7"/></svg>
                    <div class="text-right">
                        <div class="text-xs uppercase text-gray-500">Previous</div>
                        <div class="font-medium">${prev.title}</div>
                    </div>
                </a>
            ` : '<div></div>'}
            ${next ? `
                <a href="${next.file.replace('.md', '.html')}" class="group flex items-center gap-2 text-gray-400 hover:text-wiki-primary transition-colors">
                    <div>
                        <div class="text-xs uppercase text-gray-500">Next</div>
                        <div class="font-medium">${next.title}</div>
                    </div>
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7"/></svg>
                </a>
            ` : '<div></div>'}
        </div>
    `;

    const tagsHtml = page.tags?.length > 0 ? `
        <div class="flex flex-wrap gap-2 mt-4">
            ${page.tags.map(tag => `<span class="px-2 py-1 text-xs rounded-full bg-wiki-card text-gray-400">#${tag}</span>`).join('')}
        </div>
    ` : '';

    return `<!DOCTYPE html>
<html lang="en" class="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${page.title} - CleverKeys Wiki</title>
    <meta name="description" content="${page.description}">
    <script src="https://cdn.tailwindcss.com"></script>
    <script>
        tailwind.config = {
            darkMode: 'class',
            theme: {
                extend: {
                    colors: {
                        'wiki-primary': '#10b981',
                        'wiki-primary-light': '#34d399',
                        'wiki-accent': '#3b82f6',
                        'wiki-dark': '#0d1117',
                        'wiki-surface': '#161b22',
                        'wiki-card': '#21262d',
                        'wiki-border': '#30363d',
                    }
                }
            }
        }
    </script>
</head>
<body class="bg-wiki-dark text-gray-100 min-h-screen">
    <!-- Nav -->
    <nav class="sticky top-0 bg-wiki-dark/95 backdrop-blur border-b border-wiki-border z-50">
        <div class="container mx-auto px-6 py-4 flex justify-between items-center">
            <a href="../" class="flex items-center gap-3">
                <img src="https://raw.githubusercontent.com/tribixbite/CleverKeys/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="CleverKeys" class="w-8 h-8 rounded-lg">
                <span class="font-bold">CleverKeys</span>
            </a>
            <div class="flex gap-4">
                <a href="./" class="text-wiki-primary font-medium">Wiki</a>
                <a href="../specs/" class="text-gray-400 hover:text-white transition-colors">Docs</a>
                <a href="../" class="text-gray-400 hover:text-white transition-colors">Home</a>
            </div>
        </div>
    </nav>

    <!-- Breadcrumb -->
    <div class="container mx-auto px-6 py-4">
        <div class="flex items-center gap-2 text-sm text-gray-500">
            <a href="../" class="hover:text-wiki-primary">Home</a>
            <span>/</span>
            <a href="./" class="hover:text-wiki-primary">Wiki</a>
            <span>/</span>
            <span class="text-gray-300">${page.title}</span>
        </div>
    </div>

    <!-- Header -->
    <header class="container mx-auto px-6 pb-6">
        <div class="flex items-center gap-3 mb-3">
            <span class="px-3 py-1 text-sm rounded-full" style="background: ${categoryColor}20; color: ${categoryColor}">${page.category}</span>
            <span class="px-3 py-1 text-sm rounded-full bg-wiki-card text-gray-400">${page.difficulty || 'beginner'}</span>
        </div>
        <h1 class="text-4xl font-bold text-white mb-2">${page.title}</h1>
        <p class="text-xl text-gray-400">${page.description}</p>
        ${tagsHtml}
    </header>

    <!-- Content -->
    <main class="container mx-auto px-6 pb-20">
        <div class="flex gap-8">
            <article class="flex-1 bg-wiki-surface rounded-2xl p-8 max-w-4xl">
                ${content}
                ${prevNextHtml}
            </article>
            ${tocHtml}
        </div>
    </main>

    <!-- Footer -->
    <footer class="container mx-auto px-6 py-8 text-center text-gray-500 text-sm border-t border-wiki-border">
        <p>CleverKeys Wiki - Open Source Neural Keyboard</p>
    </footer>
</body>
</html>`;
}

function generateIndexPage(config, searchIndex) {
    const pagesByCategory = {};
    config.wiki_pages.forEach(page => {
        if (!pagesByCategory[page.category]) {
            pagesByCategory[page.category] = [];
        }
        pagesByCategory[page.category].push(page);
    });

    const featuredPages = config.wiki_pages.filter(p => p.featured);
    const featuredHtml = featuredPages.length > 0 ? `
        <section class="mb-12">
            <h2 class="text-xl font-bold mb-4 text-wiki-primary">Featured Guides</h2>
            <div class="grid md:grid-cols-3 gap-4">
                ${featuredPages.map(page => {
                    const cat = config.categories.find(c => c.name === page.category);
                    return `
                        <a href="${page.file.replace('.md', '.html')}" class="block bg-wiki-card p-5 rounded-xl hover:bg-wiki-surface transition-colors border border-transparent hover:border-wiki-primary/30">
                            <span class="text-xs px-2 py-0.5 rounded-full mb-2 inline-block" style="background: ${cat?.color || '#10b981'}20; color: ${cat?.color || '#10b981'}">${page.category}</span>
                            <h3 class="font-semibold text-lg mb-1">${page.title}</h3>
                            <p class="text-sm text-gray-400">${page.description}</p>
                        </a>
                    `;
                }).join('')}
            </div>
        </section>
    ` : '';

    let categorySections = '';
    for (const category of config.categories) {
        const pages = pagesByCategory[category.name] || [];
        if (pages.length === 0) continue;

        const pageCards = pages.map(page => `
            <a href="${page.file.replace('.md', '.html')}" class="block bg-wiki-card p-4 rounded-xl hover:bg-wiki-surface transition-colors border border-transparent hover:border-wiki-primary/30">
                <h3 class="font-semibold mb-1">${page.title}</h3>
                <p class="text-sm text-gray-400">${page.description}</p>
                <div class="flex items-center gap-2 mt-2">
                    <span class="text-xs px-2 py-0.5 rounded bg-wiki-dark text-gray-500">${page.difficulty || 'beginner'}</span>
                </div>
            </a>
        `).join('');

        categorySections += `
        <section class="mb-12">
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
    <title>${config.title}</title>
    <meta name="description" content="${config.description}">
    <script src="https://cdn.tailwindcss.com"></script>
    <script>
        tailwind.config = {
            darkMode: 'class',
            theme: {
                extend: {
                    colors: {
                        'wiki-primary': '#10b981',
                        'wiki-primary-light': '#34d399',
                        'wiki-accent': '#3b82f6',
                        'wiki-dark': '#0d1117',
                        'wiki-surface': '#161b22',
                        'wiki-card': '#21262d',
                        'wiki-border': '#30363d',
                    }
                }
            }
        }
    </script>
</head>
<body class="bg-wiki-dark text-gray-100 min-h-screen">
    <!-- Nav -->
    <nav class="sticky top-0 bg-wiki-dark/95 backdrop-blur border-b border-wiki-border z-50">
        <div class="container mx-auto px-6 py-4 flex justify-between items-center">
            <a href="../" class="flex items-center gap-3">
                <img src="https://raw.githubusercontent.com/tribixbite/CleverKeys/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="CleverKeys" class="w-8 h-8 rounded-lg">
                <span class="font-bold">CleverKeys</span>
            </a>
            <div class="flex gap-4">
                <a href="./" class="text-wiki-primary font-medium">Wiki</a>
                <a href="../specs/" class="text-gray-400 hover:text-white transition-colors">Docs</a>
                <a href="../" class="text-gray-400 hover:text-white transition-colors">Home</a>
            </div>
        </div>
    </nav>

    <!-- Header -->
    <header class="container mx-auto px-6 py-12 text-center">
        <h1 class="text-4xl font-bold text-white mb-4">${config.title}</h1>
        <p class="text-xl text-gray-400 max-w-2xl mx-auto mb-8">${config.description}</p>

        <!-- Search -->
        <div class="max-w-md mx-auto">
            <div class="relative">
                <input type="text" id="search-input" placeholder="${config.search?.placeholder || 'Search wiki...'}"
                    class="w-full bg-wiki-surface border border-wiki-border rounded-xl px-4 py-3 pl-10 text-white placeholder-gray-500 focus:outline-none focus:border-wiki-primary">
                <svg class="absolute left-3 top-3.5 w-5 h-5 text-gray-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"/>
                </svg>
            </div>
            <div id="search-results" class="hidden mt-2 bg-wiki-surface border border-wiki-border rounded-xl overflow-hidden text-left"></div>
        </div>
    </header>

    <!-- Content -->
    <main class="container mx-auto px-6 pb-20 max-w-5xl">
        ${featuredHtml}
        ${categorySections}
    </main>

    <!-- Footer -->
    <footer class="container mx-auto px-6 py-8 text-center text-gray-500 text-sm border-t border-wiki-border">
        <p>CleverKeys - Open Source Neural Keyboard</p>
    </footer>

    <!-- Search Script -->
    <script>
        const searchIndex = ${JSON.stringify(searchIndex)};
        const searchInput = document.getElementById('search-input');
        const searchResults = document.getElementById('search-results');

        searchInput.addEventListener('input', (e) => {
            const query = e.target.value.toLowerCase().trim();
            if (query.length < 2) {
                searchResults.classList.add('hidden');
                return;
            }

            const results = searchIndex.filter(page =>
                page.title.toLowerCase().includes(query) ||
                page.description.toLowerCase().includes(query) ||
                page.tags.some(t => t.toLowerCase().includes(query)) ||
                page.excerpt.toLowerCase().includes(query)
            ).slice(0, 5);

            if (results.length === 0) {
                searchResults.innerHTML = '<div class="p-4 text-gray-400">No results found</div>';
            } else {
                searchResults.innerHTML = results.map(r => \`
                    <a href="\${r.file}" class="block p-4 hover:bg-wiki-card border-b border-wiki-border last:border-0">
                        <div class="font-medium">\${r.title}</div>
                        <div class="text-sm text-gray-400">\${r.description}</div>
                    </a>
                \`).join('');
            }
            searchResults.classList.remove('hidden');
        });

        document.addEventListener('click', (e) => {
            if (!searchInput.contains(e.target) && !searchResults.contains(e.target)) {
                searchResults.classList.add('hidden');
            }
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

    // Create output directory
    if (!fs.existsSync(outputDir)) {
        fs.mkdirSync(outputDir, { recursive: true });
    }

    console.log('Generating CleverKeys wiki pages...');
    console.log(`Source: ${wikiDir}`);
    console.log(`Output: ${outputDir}`);
    console.log('');

    // Generate search index
    const searchIndex = generateSearchIndex(config.wiki_pages, wikiDir);

    // Generate individual wiki pages
    let generated = 0;
    let skipped = 0;
    for (let i = 0; i < config.wiki_pages.length; i++) {
        const page = config.wiki_pages[i];
        const mdPath = path.join(wikiDir, page.file);

        if (!fs.existsSync(mdPath)) {
            console.log(`  SKIP: ${page.file} (not found)`);
            skipped++;
            continue;
        }

        const markdown = fs.readFileSync(mdPath, 'utf-8');
        const html = generateWikiPage(page, markdown, config, i, config.wiki_pages);

        // Create subdirectory if needed
        const outputPath = path.join(outputDir, page.file.replace('.md', '.html'));
        const outputSubdir = path.dirname(outputPath);
        if (!fs.existsSync(outputSubdir)) {
            fs.mkdirSync(outputSubdir, { recursive: true });
        }

        fs.writeFileSync(outputPath, html);
        console.log(`  OK: ${page.title} -> ${page.file.replace('.md', '.html')}`);
        generated++;
    }

    // Generate index page
    const indexHtml = generateIndexPage(config, searchIndex);
    fs.writeFileSync(path.join(outputDir, 'index.html'), indexHtml);
    console.log(`  OK: Index page -> index.html`);

    // Save search index
    fs.writeFileSync(path.join(outputDir, 'search-index.json'), JSON.stringify(searchIndex, null, 2));
    console.log(`  OK: Search index -> search-index.json`);

    console.log('');
    console.log(`Generated ${generated + 2} files (${skipped} pages skipped - content not yet created).`);
}

main();
