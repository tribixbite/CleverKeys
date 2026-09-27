// @ts-check
import { defineConfig } from 'astro/config'
import svelte from '@astrojs/svelte'
import sitemap from '@astrojs/sitemap'
import { unified } from '@astrojs/markdown-remark'
import tailwind from '@tailwindcss/vite'
import remarkWikiLinks from './src/lib/remark-wiki-links.mjs'

// https://astro.build/config
export default defineConfig({
  site: 'https://cleverkeys.app',
  integrations: [svelte(), sitemap()],
  vite: {
    plugins: [tailwind()],
  },
  markdown: {
    // Keep the wiki link-rewriting plugin on Astro's explicit remark processor.
    // Top-level remarkPlugins is deprecated in the installed Astro 7 API.
    processor: unified({ remarkPlugins: [remarkWikiLinks] }),
    shikiConfig: {
      theme: 'one-dark-pro',
      wrap: true,
    },
  },
  build: {
    inlineStylesheets: 'auto',
  },
  compressHTML: true,
})
