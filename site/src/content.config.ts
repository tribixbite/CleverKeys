import { defineCollection, z } from 'astro:content'
import { glob } from 'astro/loaders'

const wiki = defineCollection({
  loader: glob({
    pattern: ['**/*.md', '!specs/**', '!TABLE_OF_CONTENTS.md'],
    base: '../docs/wiki',
  }),
  schema: z.object({
    title: z.string().optional(),
    description: z.string().optional(),
    category: z.string().optional(),
  }),
})

/**
 * Technical specifications collection. Mirrors the category-organized
 * markdown tree at `docs/wiki/specs/` and renders at
 * `/specs/<category>/<name>/`.
 *
 * Frontmatter supports:
 *   title       — overrides H1 for the rendered title.
 *   description — short blurb for the landing-page card.
 *   user_guide  — relative link back to the paired wiki page.
 *   status      — `implemented` | `planning` | `in-progress` (badge).
 *   version     — version string like `v1.4.0` (badge).
 */
const specs = defineCollection({
  loader: glob({
    pattern: '**/*.md',
    base: '../docs/wiki/specs',
  }),
  schema: z.object({
    title: z.string().optional(),
    description: z.string().optional(),
    user_guide: z.string().optional(),
    status: z.enum(['implemented', 'planning', 'planned', 'in-progress']).optional(),
    version: z.string().optional(),
  }),
})

export const collections = { wiki, specs }
