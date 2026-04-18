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
