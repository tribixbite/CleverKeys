# Wiki & Documentation Skill

Use this skill when creating new wiki pages, updating specs, or maintaining the CleverKeys documentation system. This skill ensures consistency in documentation structure, format, and cross-linking.

## When to Use This Skill

- Creating new user guide pages in `docs/wiki/`
- Writing technical specifications in `docs/specs/` or `docs/wiki/specs/`
- Updating existing documentation
- Creating paired wiki + spec pages for new features

## Documentation System Overview

CleverKeys maintains a **dual-documentation system**:

1. **User Wiki** (`docs/wiki/`) - End-user focused, task-oriented guides
2. **Technical Specs** (`docs/wiki/specs/` + `docs/specs/`) - Developer-focused technical details

Each user-facing feature typically has:
- A **wiki page** explaining how to use it (in `docs/wiki/{category}/`)
- A paired **spec file** explaining how it's implemented (in `docs/wiki/specs/{category}/`)

## Directory Structure

```
docs/wiki/
├── TABLE_OF_CONTENTS.md          # Master index (update manually)
├── getting-started/               # Installation, setup, basics
├── typing/                        # Swipe typing, autocorrect, emoji
├── gestures/                      # Swipe gestures, cursor navigation
├── customization/                 # Per-key actions, themes, extra keys
├── layouts/                       # Layout management, multi-language
├── settings/                      # All settings categories
├── clipboard/                     # Clipboard features
├── troubleshooting/               # Common issues, performance
└── specs/                         # Paired technical specifications
    └── {same subfolder structure as above}

docs/specs/                        # Broader architectural specs
├── README.md                      # Index of all specs
├── SPEC_TEMPLATE.md              # Template for new specs
└── {feature specifications}
```

## Wiki Page Format

### Frontmatter (YAML)

All wiki pages must include this frontmatter block at the top:

```yaml
---
title: Page Title (matches H1)
description: One-line description for TOC
category: Category Name
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

| Setting | Location | Description |
|---------|----------|-------------|
| **Setting Name** | Menu Path | What it does |

## Tips and Tricks

> [!TIP]
> Helpful hints for users

## Related Features

- [Feature Name](./link-to-page.md) - Brief description

## Technical Details

See [Feature Name Technical Specification](../specs/{category}/{page-name}-spec.md).
```

## Technical Specification Format

### Paired Specs (in `docs/wiki/specs/`)

```yaml
---
title: Feature Name - Technical Specification
user_guide: ../../{category}/{page-name}.md
status: implemented|planning|in-progress
version: v1.2.7
---

# Feature Name Technical Specification

## Overview
Brief technical summary

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Component A | File.kt:line-range | What it does |

## Architecture
Diagram or description of system design

## Key Code Patterns
Code snippets with context and line numbers

## Configuration
Settings, thresholds, defaults

## Related Specifications
Links to other specs
```

## Naming Conventions

| Element | Format | Example |
|---------|--------|---------|
| Wiki file | `lowercase-with-hyphens.md` | `user-dictionary.md` |
| Spec file | `{name}-spec.md` | `user-dictionary-spec.md` |
| Folder | lowercase | `typing/`, `settings/` |
| Links | Relative paths | `../specs/typing/page-spec.md` |

## Categories

| Category | Purpose |
|----------|---------|
| `getting-started` | New user guides |
| `typing` | Text input methods |
| `gestures` | Touch gestures |
| `customization` | Personalizing keyboard |
| `layouts` | Keyboard layouts |
| `settings` | Configuration options |
| `clipboard` | Clipboard features |
| `troubleshooting` | Problem solving |

## Creating a New Wiki + Spec Pair

### Step 1: Create Wiki Page

Create `docs/wiki/{category}/{feature-name}.md` with:
- Complete frontmatter
- User-focused content
- Link to spec in Technical Details section

### Step 2: Create Paired Spec

Create `docs/wiki/specs/{category}/{feature-name}-spec.md` with:
- Frontmatter including `user_guide` backlink
- Technical implementation details
- Code references with file:line format

### Step 3: Update TABLE_OF_CONTENTS.md

Add entry in appropriate section:
```markdown
| [Feature Name](./category/feature-name.md) | Brief description |
```

### Step 4: Verify Links

- Wiki → Spec link works
- Spec → Wiki backlink works
- Related features links work

## Style Guidelines

- **Wiki tone**: Friendly, clear, non-technical
- **Spec tone**: Precise, detailed, code-focused
- **Language**: American English, present tense
- **Tables**: Use for settings, comparisons, options
- **Code**: Use triple backticks with language (```kotlin)
- **Tips**: Use `> [!TIP]` for helpful hints
- **Notes**: Use `> [!NOTE]` for clarifications

## Commit Message Format

```bash
docs: add wiki page for feature name
docs(spec): add technical spec for feature name
docs: update TABLE_OF_CONTENTS with new pages
```
