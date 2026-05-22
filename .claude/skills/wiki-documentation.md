# Wiki & Documentation Skill

Use this skill when creating new wiki pages, updating specs, or maintaining the CleverKeys documentation system. This skill ensures consistency in documentation structure, format, and cross-linking — and reflects the v1.4.0 architectural overhaul where Astro now renders BOTH wiki guides and technical specs from markdown.

## When to Use This Skill

- Creating new user guide pages in `docs/wiki/`
- Writing or updating technical specifications in `docs/wiki/specs/`
- Auditing duplicate doc content across `docs/wiki/specs/`, `docs/specs/`, and `web_demo/specs/`
- Verifying that wiki + spec cross-references resolve on the live site

## Documentation System Overview (post v1.4.0)

CleverKeys has THREE doc trees with distinct roles:

| Tree | Role | Rendered? | URL prefix |
|------|------|-----------|------------|
| `docs/wiki/**/*.md` (excluding `specs/`) | User-facing guides | Astro `wiki` collection | `/wiki/<category>/<page>/` |
| `docs/wiki/specs/**/*.md` | Paired technical specs | Astro `specs` collection (NEW v1.4.0) | `/specs/<category>/<page>-spec/` |
| `docs/specs/**/*.md` | **Internal engineering notes only** | Not rendered | n/a (GitHub source only) |
| `web_demo/specs/*.html` | **Legacy static HTML fallbacks** | Copied as-is (cp -n, no clobber) | `/specs/<name>.html` |

**Single source of truth principle**: Every documented feature should have its canonical content under `docs/wiki/` (guide) + `docs/wiki/specs/` (spec). The `docs/specs/` versions retain banner notes pointing to the canonical location and are preserved only for cross-references that haven't been updated yet (README, CLAUDE.md, etc.).

When you find a feature documented in BOTH `docs/specs/foo.md` AND `docs/wiki/specs/<cat>/foo-spec.md`, the wiki/specs version is canonical. Audit and merge into wiki/specs; leave a banner on the docs/specs version.

## Astro Architecture (key files)

| File | Purpose |
|------|---------|
| `site/src/content.config.ts` | Defines `wiki` + `specs` collections (specs collection added v1.4.0) |
| `site/src/lib/wiki.ts` | Wiki entry helpers (titleFor, categorySlug, pageSlug, CATEGORIES) |
| `site/src/lib/specs.ts` | Spec entry helpers (mirrors wiki helpers) |
| `site/src/layouts/WikiLayout.astro` | Wiki page chrome (sidebar, breadcrumbs) |
| `site/src/layouts/SpecsLayout.astro` | Spec page chrome (adds version/status badges + user_guide chip) |
| `site/src/pages/wiki/[...slug].astro` | Wiki dynamic route |
| `site/src/pages/wiki/index.astro` | Wiki landing |
| `site/src/pages/specs/[...slug].astro` | Spec dynamic route |
| `site/src/pages/specs/index.astro` | Spec landing |
| `site/src/lib/remark-wiki-links.mjs` | Rewrites `.md` cross-refs to live URLs |

### Markdown link rewriting

The remark plugin rewrites internal links at build time:

```
./foo.md                  ->  /wiki/<cat>/foo/
../FAQ.md                 ->  /wiki/faq/
../specs/typing/x-spec.md ->  /specs/typing/x-spec/
../../wiki/typing/x.md    ->  /wiki/typing/x/
```

Specs rewrite to a trailing-slash route (Astro convention), NOT `.html`. The legacy `.html` URLs continue to resolve via the no-clobber `cp -rn` step in `.github/workflows/deploy-web-demo.yml`.

## Directory Structure

```
docs/wiki/
├── TABLE_OF_CONTENTS.md          # Master index (update manually)
├── FAQ.md                        # Renders at /wiki/faq/
├── getting-started/              # Installation, setup, basics
├── typing/                       # Swipe typing, autocorrect, emoji, URL sanitization
├── gestures/                     # Swipe gestures, cursor navigation
├── customization/                # Per-key actions, themes, extra keys, timestamp keys
├── layouts/                      # Layout management, multi-language
├── settings/                     # All settings categories
├── clipboard/                    # Clipboard features, privacy
├── troubleshooting/              # Common issues, performance
└── specs/                        # Paired technical specifications
    ├── getting-started/
    ├── typing/
    ├── gestures/
    ├── customization/
    ├── layouts/
    ├── settings/
    ├── clipboard/
    └── troubleshooting/

docs/specs/                       # Internal engineering notes (with banners
│                                 # pointing to canonical docs/wiki/specs/ paths)
├── README.md                     # Index
├── SPEC_TEMPLATE.md
├── architectural-decisions.md    # Engineering-only, no wiki counterpart
├── kv-cache-optimization.md      # Engineering-only
├── memory-pool-optimization.md   # Engineering-only
└── ...
```

## Wiki Page Format

### Frontmatter (YAML)

All wiki pages must include this frontmatter block at the top. Astro's `wiki` collection schema validates these fields (only `title`, `description`, `category` are schema-typed; the rest are advisory):

```yaml
---
title: Page Title (matches H1; falls back to first H1 if omitted)
description: One-line description for TOC + meta tags
category: Category Name (matches a CATEGORIES slug; falls back to first path segment)
difficulty: beginner|intermediate|advanced
featured: true (optional)
related_spec: ../specs/{category}/{page-name}-spec.md
---
```

### Page Structure Template

```markdown
# Page Title

Clear introductory paragraph explaining what this page covers.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | What problem does this solve |
| **Access** | Where to find in Settings |
| **Methods** | How many ways to use it |

## How It Works

Explain the core concept with plain language and examples.

## How to Use

Step-by-step instructions:
1. First step
2. Second step
3. Result

## Configuration / Settings

| Setting | Default | Description |
|---------|---------|-------------|
| **Setting Name** | value | What it does |

## Tips and Tricks

> [!TIP]
> Helpful hints for users

## Related Features

- [Feature Name](./link-to-page.md) - Brief description

## Technical Details

See [Feature Name Technical Specification](../specs/{category}/{page-name}-spec.md).
```

## Technical Specification Format

### Frontmatter (schema-enforced)

`docs/wiki/specs/**/*.md` files MUST satisfy the `specs` collection schema in `site/src/content.config.ts`. Build will fail otherwise:

```yaml
---
title: Feature Name — Technical Specification
description: One-line summary for the /specs/ landing card
user_guide: ../../{category}/{page-name}.md   # Back-link to paired wiki page
status: implemented                            # enum: implemented | planning | planned | in-progress
version: v1.4.0                                # current app version (or version feature was introduced)
---
```

Valid `status` values are `implemented`, `planning`, `planned`, `in-progress`. Anything else will throw `InvalidContentEntryDataError` at build time. Use `status: implemented` + the current app version for ALL specs unless documenting a planned-but-unbuilt feature.

### Page Structure Template

```markdown
# Feature Name Technical Specification

## Overview
Brief technical summary — what the feature does and where it lives in the codebase.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| ComponentName | `src/main/kotlin/.../ComponentName.kt:NN` | What it does |

## Architecture
Diagram or description of system design. Use ASCII boxes for pipelines.

## Key Code Patterns
Code snippets with context. **COPY VERBATIM from source**, don't paraphrase.
Cite file:line.

## Configuration

| Setting | Key | Default | Range | Source |
|---------|-----|---------|-------|--------|
| **Name** | `pref_key_name` | value | range | `File.kt:NN` |

## Test Coverage

| Suite | File | Cases |
|-------|------|-------|
| Pure JVM | `src/test/kotlin/.../FooTest.kt` | N |
| Instrumented | `src/androidTest/kotlin/.../FooTest.kt` | N |

## Related Specifications

- [Other Spec](./other-spec.md) - Brief relationship description
```

## Naming Conventions

| Element | Format | Example |
|---------|--------|---------|
| Wiki file | `lowercase-with-hyphens.md` | `user-dictionary.md` |
| Spec file | `{name}-spec.md` | `user-dictionary-spec.md` |
| Folder | lowercase | `typing/`, `settings/` |
| Internal links | Relative paths | `../specs/typing/page-spec.md` |

## Categories

The 8 wiki + spec categories are defined in `site/src/lib/wiki.ts:CATEGORIES`. Both `docs/wiki/<cat>/` and `docs/wiki/specs/<cat>/` use the SAME slugs:

| Slug | Purpose | Sidebar accent color |
|------|---------|----------------------|
| `getting-started` | New user guides | green |
| `typing` | Text input methods | blue |
| `gestures` | Touch gestures | purple |
| `customization` | Personalizing keyboard | amber |
| `layouts` | Keyboard layouts | pink |
| `settings` | Configuration options | grey |
| `clipboard` | Clipboard features | teal |
| `troubleshooting` | Problem solving | red |

If you need a 9th category, update BOTH `lib/wiki.ts` (CATEGORIES + PRIORITY map) AND `lib/specs.ts` (re-exports CATEGORIES). The layouts pick up new categories automatically.

## Creating a New Wiki + Spec Pair

### Step 1: Create Wiki Page

Create `docs/wiki/{category}/{feature-name}.md` with:
- Complete frontmatter (title, description, category, difficulty, related_spec)
- User-focused content using the template above
- `## Technical Details` section linking to the paired spec

### Step 2: Create Paired Spec

Create `docs/wiki/specs/{category}/{feature-name}-spec.md` with:
- Schema-required frontmatter (title, description, user_guide, status, version)
- Technical implementation details using the spec template
- Code references with `file:line` format
- Verify all class names + line numbers against current source (see "Verify Before You Cite" below)

### Step 3: Verify Build Locally

Termux build invocation (avoids the `#!/usr/bin/env` shebang issue):

```bash
cd site
bun --bun node_modules/astro/astro.js build
```

Should produce N+2 pages (N existing + new wiki + new spec). Check `site/dist/wiki/<cat>/<name>/index.html` and `site/dist/specs/<cat>/<name>-spec/index.html` both exist.

### Step 4: Update TABLE_OF_CONTENTS.md

Add entry in appropriate section:
```markdown
| [Feature Name](./category/feature-name.md) | Brief description |
```

### Step 5: Verify Cross-Links

After local build:
```bash
grep -oE 'href="[^"]*"' site/dist/wiki/<cat>/<name>/index.html | grep specs
# Should show /specs/<cat>/<name>-spec/ (trailing slash, no .html)
```

## Verify Before You Cite — The Most Important Rule

**Spec citations rot fast.** Class names get renamed, methods move between files, line numbers shift on every commit. When merging a spec from `docs/specs/` into `docs/wiki/specs/`, OR when reviewing/auditing any spec, ALWAYS verify each `File.kt:line` citation against current source:

```bash
# Does the class actually exist?
find src/main/kotlin -name 'ClassName*.kt' -o -name '*ClassName*.kt'

# Does the line cited actually contain what the spec claims?
grep -n 'methodName\|FLAG_NAME\|CONSTANT' src/main/kotlin/path/to/File.kt

# Are config defaults current?
grep -nE 'const val DEFAULT_X|var x_setting = ' src/main/kotlin/path/to/Config.kt
```

Common categories of spec rot caught in the v1.4.0 audit:
- **Fictional classes**: spec claimed `SubkeyManager.kt` and `CustomizationStore.kt`; actually `ShortSwipeCustomizationManager.kt` and `CustomShortSwipeExecutor.kt`.
- **Wrong flag values**: spec claimed `FLAG_P_TRACKPOINT_MODE = 0x800`; actual `1 shl 10 = 0x400`. Selection-delete claimed `0x1000` or `128`; actual `1 shl 11 = 0x800`.
- **Stale config defaults**: `slider_speed_smoothing` claimed 0.6, actual 0.7. `slider_speed_max` claimed 6.0, actual 4.0.
- **Wrong file shapes**: spec claimed `Pointer` is `data class`; actual `internal class Pointer`. `Pointers.kt` claimed ~1100 lines, actual 1789.
- **Outdated asset paths**: legacy `swipe_*_character_quant.onnx`; current `swipe_{encoder,decoder}_android.onnx`.

When in doubt, RUN THE GREP. A 30-second grep beats shipping a contradiction to the live site.

## Style Guidelines

- **Wiki tone**: Friendly, clear, non-technical. Address the user directly ("you").
- **Spec tone**: Precise, detailed, code-focused. Third-person.
- **Language**: American English, present tense.
- **Tables**: Use for settings, comparisons, options.
- **Code blocks**: Triple backticks with language tag (` ```kotlin `). COPY VERBATIM from source; don't paraphrase.
- **Code references**: Always `file:line` format, e.g., `WordPredictor.kt:1716`.
- **Tips/Notes**: `> [!TIP]` / `> [!NOTE]` blockquotes.
- **No emojis** unless the user explicitly asks for them.

## Commit Message Format

```
docs: add wiki page for feature name
docs(specs): add technical spec for feature name
docs(specs): merge docs/specs/foo into docs/wiki/specs/cat/foo-spec
docs: update TABLE_OF_CONTENTS with new pages
```

For merges that resolve contradictions, list the corrected facts in the body so future readers can audit the merge:

```
docs(specs): fix factual contradictions in selection-delete-spec

  Selection-Delete FLAG:
    wiki:       0x1000              (wrong)
    docs/specs: 128                 (wrong)
    code:       1 shl 11 = 0x800    (Pointers.kt:1716)
  ...
```

## Hard-won Lessons (v1.4.0 doc rearchitecture)

- **Astro content collection schemas catch real bugs.** A typo in `status: planned` vs `status: planning` becomes a build error, not a silently-broken badge. Keep the `status` enum tight.
- **The `docs/wiki/specs/` ↔ `web_demo/specs/` dual rendering is intentional but messy.** Astro generates `/specs/<cat>/<name>-spec/index.html`; static fallbacks live at `/specs/<name>.html`. `cp -rn` (no-clobber) in the deploy workflow ensures Astro wins where they collide. Don't try to "unify" the two — the legacy HTML pages cover topics that haven't been migrated yet.
- **Three doc trees coexist deliberately.** `docs/wiki/specs/` is the canonical source for everything live; `docs/specs/` retains engineering-only specs (perf optimizations, ADRs, testing strategy) that don't need a public URL; `web_demo/specs/` is purely legacy fallbacks. Don't delete `docs/specs/*.md` outright — it's referenced by README.md, CLAUDE.md, TABLE_OF_CONTENTS, and specs-config.json.
- **Spec content rots faster than wiki content.** Wiki guides describe stable user-facing behavior; specs cite specific class names + line numbers that drift on every commit. When auditing, prioritize spec verification over wiki refresh.
- **The remark plugin's `.md` → URL rewriting is path-sensitive.** Links inside `docs/wiki/<cat>/foo.md` rewrite differently than links inside `docs/wiki/specs/<cat>/foo-spec.md`. Test cross-references via `bun --bun node_modules/astro/astro.js build` + grep the dist output before considering a link "working".
- **Termux can't run `bun run build` directly.** The `astro` shebang points to `/usr/bin/env` which doesn't exist on Android. Use `cd site && bun --bun node_modules/astro/astro.js build` instead.
