import { visit } from 'unist-util-visit'

/**
 * Rewrite markdown links so that wiki cross-refs resolve on the built site.
 *
 *   ./foo.md                       ->  /wiki/<cat>/foo/
 *   ../FAQ.md                      ->  /wiki/faq/
 *   ../specs/typing/x-spec.md      ->  /specs/typing/x-spec/
 *   ../../wiki/typing/x.md         ->  /wiki/typing/x/
 *   ../../typing/x.md (from spec)  ->  /wiki/typing/x/
 *
 * Specs render as Astro pages now (Phase 1 of the doc-architecture
 * cleanup), so the `.md` extension becomes a trailing slash. Old
 * `.html` URLs continue to resolve via legacy redirect stubs emitted
 * by the deploy workflow.
 *
 * Preserves hash fragments. Leaves http(s), mailto, and anchor-only
 * links alone.
 */
export default function remarkWikiLinks() {
  return (tree, file) => {
    const fileAbs = (file?.path ?? '').replace(/\\/g, '/')

    visit(tree, 'link', (node) => {
      if (typeof node.url !== 'string') return
      const raw = node.url
      if (!raw) return
      if (/^(https?:|mailto:|tel:|#)/i.test(raw)) return
      if (raw.startsWith('//')) return

      const [pathOnly, hash = ''] = raw.split('#')
      if (!pathOnly) {
        // anchor-only
        return
      }
      if (!pathOnly.endsWith('.md')) return

      // Resolve the link target into an absolute path anchored at the repo root.
      const dir = fileAbs.split('/').slice(0, -1)
      let parts
      if (pathOnly.startsWith('/')) {
        parts = pathOnly.split('/').filter(Boolean)
      } else {
        parts = dir.slice()
        for (const seg of pathOnly.split('/')) {
          if (seg === '' || seg === '.') continue
          if (seg === '..') parts.pop()
          else parts.push(seg)
        }
      }
      const abs = '/' + parts.join('/')

      let target
      const wikiIdx = abs.indexOf('/docs/wiki/')
      if (wikiIdx !== -1) {
        const tail = abs.slice(wikiIdx + '/docs/wiki/'.length)
        if (tail.startsWith('specs/')) {
          // Specs live under /specs/<category>/<name>/, rendered by Astro
          // from `docs/wiki/specs/**/*.md` via the `specs` content
          // collection. Trailing slash matches Astro's slug convention.
          const slug = tail.slice('specs/'.length).replace(/\.md$/, '').toLowerCase()
          target = `/specs/${slug}/`
        } else {
          // Wiki pages: strip .md, trailing slash, lowercase
          const slug = tail.replace(/\.md$/, '').toLowerCase()
          target = `/wiki/${slug}/`
        }
      } else {
        // Markdown outside the wiki tree — fall back to a safe relative URL
        target = pathOnly.replace(/\.md$/, '/')
      }

      node.url = target + (hash ? `#${hash}` : '')
    })
  }
}
