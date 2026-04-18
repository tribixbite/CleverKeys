import type { CollectionEntry } from 'astro:content'

export type WikiCategory = {
  slug: string
  name: string
  color: string
  order: number
  description: string
}

export const CATEGORIES: WikiCategory[] = [
  { slug: 'getting-started', name: 'Getting Started',   color: '#34d399', order: 1,  description: 'Install, enable, and start typing.' },
  { slug: 'typing',          name: 'Typing',            color: '#60a5fa', order: 2,  description: 'Swipe typing, autocorrect, emoji, special characters.' },
  { slug: 'gestures',        name: 'Gestures',          color: '#c084fc', order: 3,  description: '8-direction swipes, circles, trackpoint mode.' },
  { slug: 'customization',   name: 'Customization',     color: '#fbbf24', order: 4,  description: 'Per-key actions, extra keys, timestamp keys, themes.' },
  { slug: 'layouts',         name: 'Layouts',           color: '#f472b6', order: 5,  description: 'Custom layouts, multi-language, profiles, language packs.' },
  { slug: 'settings',        name: 'Settings',          color: '#9ca3af', order: 6,  description: 'Appearance, input behavior, haptics, neural, accessibility.' },
  { slug: 'clipboard',       name: 'Clipboard',         color: '#2dd4bf', order: 7,  description: 'History, shortcuts, text selection.' },
  { slug: 'troubleshooting', name: 'Troubleshooting',   color: '#f87171', order: 8,  description: 'Common issues, performance, reset defaults.' },
]

export const CATEGORY_BY_SLUG = Object.fromEntries(CATEGORIES.map((c) => [c.slug, c]))

/** Pull the H1 from frontmatter-less markdown; fall back to id-based title. */
export function titleFor(entry: CollectionEntry<'wiki'>): string {
  if (entry.data.title && entry.data.title.length) return entry.data.title
  const body = entry.body ?? ''
  const m = body.match(/^#\s+(.+)$/m)
  if (m && m[1]) return m[1].trim()
  const tail = entry.id.split('/').pop() ?? entry.id
  return tail
    .replace(/\.md$/, '')
    .replace(/\-/g, ' ')
    .replace(/\b\w/g, (c) => c.toUpperCase())
}

export function categorySlug(entry: CollectionEntry<'wiki'>): string {
  if (!entry.id.includes('/')) return 'general'
  return entry.id.split('/')[0] ?? 'general'
}

export function pageSlug(entry: CollectionEntry<'wiki'>): string {
  return entry.id.replace(/\.md$/, '').replace(/\\/g, '/')
}

/** Preferred order for index cards. Missing pages drop to alpha. */
export const PRIORITY: Record<string, string[]> = {
  'getting-started': ['installation', 'enabling-keyboard', 'first-time-setup', 'basic-typing', 'quick-settings'],
  typing:            ['swipe-typing', 'autocorrect', 'emoji', 'smart-punctuation', 'special-characters', 'user-dictionary'],
  gestures:          ['short-swipes', 'circle-gestures', 'cursor-navigation', 'selection-delete', 'trackpoint-mode'],
  customization:     ['per-key-actions', 'extra-keys', 'themes', 'timestamp-keys', 'command-palette'],
  layouts:           ['adding-layouts', 'switching-layouts', 'multi-language', 'language-packs', 'custom-layouts', 'profiles'],
  settings:          ['appearance', 'input-behavior', 'haptics', 'neural-settings', 'accessibility'],
  clipboard:         ['clipboard-history', 'text-selection', 'shortcuts'],
  troubleshooting:   ['common-issues', 'performance', 'reset-defaults'],
}

export function orderedWithinCategory(entries: CollectionEntry<'wiki'>[], category: string) {
  const priority = PRIORITY[category] ?? []
  return [...entries].sort((a, b) => {
    const aSlug = (a.id.split('/').pop() ?? '').replace(/\.md$/, '')
    const bSlug = (b.id.split('/').pop() ?? '').replace(/\.md$/, '')
    const ai = priority.indexOf(aSlug)
    const bi = priority.indexOf(bSlug)
    if (ai !== -1 && bi !== -1) return ai - bi
    if (ai !== -1) return -1
    if (bi !== -1) return 1
    return aSlug.localeCompare(bSlug)
  })
}
