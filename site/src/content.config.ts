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

export const collections = { wiki }
