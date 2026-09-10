// @ts-check
import { defineConfig } from 'astro/config'
import svelte from '@astrojs/svelte'
import sitemap from '@astrojs/sitemap'
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
    // astro 7: the astro-6 `processor: unified(...)` wrapper is deprecated again
    // (and @astrojs/markdown-remark is no longer a transitive) — the plain
    // top-level `remarkPlugins` field is the supported path.
    remarkPlugins: [remarkWikiLinks],
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
