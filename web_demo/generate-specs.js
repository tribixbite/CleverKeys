#!/usr/bin/env node
/**
 * CleverKeys Spec Page Generator
 *
 * Reads specs-config.json and generates HTML pages for each reviewed spec.
 * Run with: node generate-specs.js
 */

const fs = require('fs');
const path = require('path');

// Simple markdown to HTML converter (handles common patterns)
function markdownToHtml(markdown) {
    let html = markdown
        // Code blocks (must be before inline code)
        .replace(/```(\w+)?\n([\s\S]*?)```/g, (_, lang, code) =>
            `<pre class="bg-ck-dark p-4 rounded-lg overflow-x-auto"><code class="language-${lang || 'text'}">${escapeHtml(code.trim())}</code></pre>`)
        // Inline code
        .replace(/`([^`]+)`/g, '<code class="bg-ck-dark px-1 rounded">$1</code>')
        // Headers
        .replace(/^### (.+)$/gm, '<h3 class="text-xl font-semibold mt-6 mb-3 text-ck-purple-light">$1</h3>')
        .replace(/^## (.+)$/gm, '<h2 class="text-2xl font-bold mt-8 mb-4 text-ck-purple">$1</h2>')
        .replace(/^# (.+)$/gm, '<h1 class="text-3xl font-bold mb-6 gradient-text">$1</h1>')
        // Bold and italic
        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
        .replace(/\*([^*]+)\*/g, '<em>$1</em>')
        // Lists
        .replace(/^- \[x\] (.+)$/gm, '<li class="flex items-center gap-2"><span class="text-green-400">&#10003;</span> $1</li>')
        .replace(/^- \[ \] (.+)$/gm, '<li class="flex items-center gap-2"><span class="text-gray-500">&#9633;</span> $1</li>')
        .replace(/^- (.+)$/gm, '<li class="ml-4">&#8226; $1</li>')
        .replace(/^(\d+)\. (.+)$/gm, '<li class="ml-4">$1. $2</li>')
        // Horizontal rules
        .replace(/^---$/gm, '<hr class="border-gray-700 my-6">')
        // Links
        .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" class="text-ck-purple hover:underline">$1</a>')
        // Paragraphs (simple approach - wrap non-tagged lines)
        .replace(/^(?!<[h123lp]|<pre|<hr|<ul|<ol)(.+)$/gm, '<p class="mb-4 text-gray-300">$1</p>');

    // Wrap consecutive list items in ul
    html = html.replace(/(<li[^>]*>.*?<\/li>\n?)+/g, '<ul class="mb-4 space-y-1">$&</ul>');

    return html;
}

function escapeHtml(text) {
    return text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}

function generateSpecPage(spec, markdown, config) {
    const content = markdownToHtml(markdown);
    const categoryColor = config.categories.find(c => c.name === spec.category)?.color || '#9b59b6';

    return `<!DOCTYPE html>
<html lang="en" class="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${spec.title} - CleverKeys Documentation</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <script>
        tailwind.config = {
            darkMode: 'class',
            theme: {
                extend: {
                    colors: {
                        'ck-purple': '#9b59b6',
                        'ck-purple-dark': '#6b21a8',
                        'ck-purple-light': '#c39bd3',
                        'ck-dark': '#0f0f1a',
                        'ck-surface': '#1a1a2e',
                        'ck-card': '#242438',
                    }
                }
            }
        }
    </script>
    <style>
        .gradient-text {
            background: linear-gradient(135deg, #9b59b6 0%, #c39bd3 50%, #9b59b6 100%);
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
                <a href="./" class="text-gray-400 hover:text-white transition-colors">All Specs</a>
                <a href="../" class="text-gray-400 hover:text-white transition-colors">Home</a>
            </div>
        </div>
    </nav>

    <!-- Breadcrumb -->
    <div class="container mx-auto px-6 py-4">
        <div class="flex items-center gap-2 text-sm text-gray-500">
            <a href="../" class="hover:text-ck-purple">Home</a>
            <span>/</span>
            <a href="./" class="hover:text-ck-purple">Specs</a>
            <span>/</span>
            <span class="text-gray-300">${spec.title}</span>
        </div>
    </div>

    <!-- Header -->
    <header class="container mx-auto px-6 pb-8">
        <div class="flex items-center gap-3 mb-4">
            <span class="px-3 py-1 text-sm rounded-full" style="background: ${categoryColor}20; color: ${categoryColor}">${spec.category}</span>
            <span class="px-3 py-1 text-sm rounded-full bg-ck-card text-gray-400">${spec.version}</span>
        </div>
        <h1 class="text-4xl font-bold gradient-text mb-2">${spec.title}</h1>
        <p class="text-xl text-gray-400">${spec.description}</p>
    </header>

    <!-- Content -->
    <main class="container mx-auto px-6 pb-20">
        <div class="bg-ck-surface rounded-2xl p-8 max-w-4xl">
            ${content}
        </div>
    </main>

    <!-- Footer -->
    <footer class="container mx-auto px-6 py-8 text-center text-gray-500 text-sm border-t border-gray-800">
        <p>CleverKeys Documentation - Generated from specs</p>
    </footer>
</body>
</html>`;
}

function generateIndexPage(config) {
    const specsByCategory = {};
    config.reviewed_specs.forEach(spec => {
        if (!specsByCategory[spec.category]) {
            specsByCategory[spec.category] = [];
        }
        specsByCategory[spec.category].push(spec);
    });

    let categorySections = '';
    for (const category of config.categories) {
        const specs = specsByCategory[category.name] || [];
        if (specs.length === 0) continue;

        const specCards = specs.map(spec => `
            <a href="${spec.file.replace('.md', '.html')}" class="block bg-ck-card p-4 rounded-xl hover:bg-ck-surface transition-colors border border-transparent hover:border-ck-purple/30">
                <h3 class="font-semibold mb-1">${spec.title}</h3>
                <p class="text-sm text-gray-400">${spec.description}</p>
                <div class="flex items-center gap-2 mt-2">
                    <span class="text-xs px-2 py-0.5 rounded bg-ck-dark text-gray-500">${spec.version}</span>
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
                ${specCards}
            </div>
        </section>`;
    }

    return `<!DOCTYPE html>
<html lang="en" class="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Documentation - CleverKeys</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <script>
        tailwind.config = {
            darkMode: 'class',
            theme: {
                extend: {
                    colors: {
                        'ck-purple': '#9b59b6',
                        'ck-purple-dark': '#6b21a8',
                        'ck-purple-light': '#c39bd3',
                        'ck-dark': '#0f0f1a',
                        'ck-surface': '#1a1a2e',
                        'ck-card': '#242438',
                    }
                }
            }
        }
    </script>
    <style>
        .gradient-text {
            background: linear-gradient(135deg, #9b59b6 0%, #c39bd3 50%, #9b59b6 100%);
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
            <a href="../" class="text-gray-400 hover:text-white transition-colors">Home</a>
        </div>
    </nav>

    <!-- Header -->
    <header class="container mx-auto px-6 py-12 text-center">
        <h1 class="text-4xl font-bold gradient-text mb-4">${config.title}</h1>
        <p class="text-xl text-gray-400 max-w-2xl mx-auto">${config.description}</p>
    </header>

    <!-- Content -->
    <main class="container mx-auto px-6 pb-20 max-w-4xl">
        ${categorySections}
    </main>

    <!-- Footer -->
    <footer class="container mx-auto px-6 py-8 text-center text-gray-500 text-sm border-t border-gray-800">
        <p>CleverKeys - Open Source Neural Keyboard</p>
    </footer>
</body>
</html>`;
}

// Main execution
function main() {
    const configPath = path.join(__dirname, 'specs-config.json');
    const config = JSON.parse(fs.readFileSync(configPath, 'utf-8'));

    const specsDir = path.resolve(__dirname, config.specs_directory);
    const outputDir = path.resolve(__dirname, config.output_directory);

    // Create output directory
    if (!fs.existsSync(outputDir)) {
        fs.mkdirSync(outputDir, { recursive: true });
    }

    console.log('Generating CleverKeys spec pages...');
    console.log(`Source: ${specsDir}`);
    console.log(`Output: ${outputDir}`);
    console.log('');

    // Generate individual spec pages
    let generated = 0;
    for (const spec of config.reviewed_specs) {
        const mdPath = path.join(specsDir, spec.file);
        if (!fs.existsSync(mdPath)) {
            console.log(`  SKIP: ${spec.file} (not found)`);
            continue;
        }

        const markdown = fs.readFileSync(mdPath, 'utf-8');
        const html = generateSpecPage(spec, markdown, config);
        const outputPath = path.join(outputDir, spec.file.replace('.md', '.html'));
        fs.writeFileSync(outputPath, html);
        console.log(`  OK: ${spec.title} -> ${spec.file.replace('.md', '.html')}`);
        generated++;
    }

    // Generate index page
    const indexHtml = generateIndexPage(config);
    fs.writeFileSync(path.join(outputDir, 'index.html'), indexHtml);
    console.log(`  OK: Index page -> index.html`);

    console.log('');
    console.log(`Generated ${generated + 1} pages successfully.`);
}

main();
