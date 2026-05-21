import type { CollectionEntry } from 'astro:content'
import { CATEGORIES as WIKI_CATEGORIES } from './wiki'

/**
 * Specs share the wiki's category taxonomy — same 8 categories used in
 * `docs/wiki/specs/<category>/foo-spec.md` mirror the structure under
 * `docs/wiki/<category>/foo.md`.
 *
 * Specs are stored separately because they have their own URL prefix
 * (`/specs/`), a slightly different visual treatment, and metadata
 * fields (version, status) that wiki pages don't need.
 */
export const SPEC_CATEGORIES = WIKI_CATEGORIES

export const SPEC_CATEGORY_BY_SLUG = Object.fromEntries(
  SPEC_CATEGORIES.map((c) => [c.slug, c]),
)

/** Resolve a display title for a spec entry. */
export function specTitleFor(entry: CollectionEntry<'specs'>): string {
  if (entry.data.title && entry.data.title.length) return entry.data.title
  const body = entry.body ?? ''
  const m = body.match(/^#\s+(.+)$/m)
  if (m && m[1]) return m[1].trim()
  const tail = entry.id.split('/').pop() ?? entry.id
  return tail
    .replace(/\.md$/, '')
    .replace(/-spec$/, '')
    .replace(/-/g, ' ')
    .replace(/\b\w/g, (c) => c.toUpperCase())
}

/** First path segment is the category slug. Top-level specs land in 'general'. */
export function specCategorySlug(entry: CollectionEntry<'specs'>): string {
  if (!entry.id.includes('/')) return 'general'
  return entry.id.split('/')[0] ?? 'general'
}

/** Strip `.md` and normalize separators. */
export function specPageSlug(entry: CollectionEntry<'specs'>): string {
  return entry.id.replace(/\.md$/, '').replace(/\\/g, '/')
}

/** Pull the first non-heading line for sidebar/card descriptions. */
export function specBlurb(entry: CollectionEntry<'specs'>): string {
  if (entry.data.description) return entry.data.description
  const body = entry.body ?? ''
  const lines = body.split(/\r?\n/).filter((l) => l.trim().length > 0 && !l.startsWith('#'))
  const first = lines[0] ?? ''
  return first.replace(/[*_`]/g, '').slice(0, 140)
}

/**
 * Order specs within a category by the same priority table used for
 * wiki pages where possible, so the spec sidebar matches its paired
 * wiki sidebar ordering.
 */
export function specOrderedWithinCategory(entries: CollectionEntry<'specs'>[], _category: string) {
  return [...entries].sort((a, b) => specTitleFor(a).localeCompare(specTitleFor(b)))
}
