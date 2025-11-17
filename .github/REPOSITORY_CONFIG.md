# GitHub Repository Configuration

This file contains recommended configuration for the CleverKeys GitHub repository.

---

## Repository Information

### Description
```
Privacy-first Android keyboard with neural swipe typing. 100% local processing, ONNX-powered predictions, Material 3 design. Complete Kotlin rewrite of Unexpected-Keyboard with accessibility features.
```

### Website
```
https://github.com/OWNER/cleverkeys
```

### Topics (GitHub Tags)
```
android
keyboard
ime
input-method
swipe-typing
neural-network
onnx
privacy
kotlin
material-design
accessibility
open-source
machine-learning
on-device-ml
predictive-text
autocorrect
material3
android-keyboard
privacy-first
offline
```

---

## Repository Settings

### General Settings
- **Template repository**: No
- **Allow forking**: Yes
- **Require contributors to sign off on web-based commits**: No (optional, recommended)
- **Include all branches**: No (only main)

### Features
- ✅ **Issues**: Enabled (for bug reports and feature requests)
- ✅ **Projects**: Enabled (for roadmap tracking)
- ✅ **Discussions**: Enabled (for Q&A and community)
- ✅ **Wiki**: Disabled (use docs/ folder instead)
- ✅ **Sponsorships**: Enabled (when ready to accept donations)

### Pull Requests
- ✅ **Allow merge commits**: Yes
- ✅ **Allow squash merging**: Yes
- ✅ **Allow rebase merging**: Yes
- ✅ **Always suggest updating pull request branches**: Yes
- ✅ **Automatically delete head branches**: Yes

### Security
- ✅ **Private vulnerability reporting**: Enabled
- ✅ **Dependency graph**: Enabled
- ✅ **Dependabot alerts**: Enabled
- ✅ **Dependabot security updates**: Enabled
- ✅ **Dependabot version updates**: Optional (can create noise)
- ✅ **Code scanning**: Enabled (CodeQL)
- ✅ **Secret scanning**: Enabled

### Branch Protection (main branch)
- ✅ **Require pull request reviews before merging**: Yes (1 approving review)
- ✅ **Dismiss stale pull request approvals**: Yes
- ✅ **Require status checks to pass**: Yes (CI workflow)
- ✅ **Require conversation resolution**: Yes
- ✅ **Require signed commits**: Optional (recommended for security)
- ✅ **Require linear history**: Optional (cleaner history)
- ✅ **Include administrators**: No (allow maintainers to bypass for hotfixes)
- ✅ **Restrict pushes**: No
- ✅ **Allow force pushes**: No
- ✅ **Allow deletions**: No

---

## GitHub Actions Permissions

### Workflow Permissions
- **Read repository contents and packages**: Yes
- **Read and write permissions**: Yes (for uploading artifacts)
- **Allow GitHub Actions to create and approve pull requests**: No

### Fork Pull Request Workflows
- **Require approval for all outside collaborators**: Yes (security)
- **Require approval for first-time contributors**: Yes

---

## Social Preview

### Image Specifications
- Size: 1280x640 pixels
- Format: PNG or JPG
- Content: CleverKeys logo + tagline + feature highlights
- Background: Material 3 theme colors

### Suggested Design
```
Background: Material 3 blue gradient
Content:
  - CleverKeys logo (top-left)
  - "Privacy-First Neural Keyboard" (headline)
  - Icons: 🔒 100% Local | 🧠 Neural AI | ⌨️ 20 Languages | ♿ Accessible
```

---

## About Section

### Short Description (160 chars max)
```
Privacy-first Android keyboard with neural swipe typing, 20 languages, and full accessibility. 100% local - no data collection.
```

---

## Releases

### Release Naming Convention
```
v{MAJOR}.{MINOR}.{PATCH} - {Codename}

Examples:
- v1.0.0 - Genesis
- v1.1.0 - Polaris
- v2.0.0 - Aurora
```

### Release Notes Format
- Follow CHANGELOG.md format (Keep a Changelog)
- Include "What's New" section
- Include "Bug Fixes" section
- Include "Known Issues" section
- Link to full CHANGELOG.md
- Include download links (APK + source)
- Include SHA256 checksums for APK

---

## License Display

### Header (in GitHub UI)
```
GPL-3.0 license
```

### License Badge (already in README)
```markdown
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
```

---

## Community Health Files

All community health files are in place:
- ✅ LICENSE (GPL-3.0)
- ✅ README.md
- ✅ CONTRIBUTING.md
- ✅ CODE_OF_CONDUCT.md
- ✅ SECURITY.md
- ✅ SUPPORT.md
- ✅ .github/ISSUE_TEMPLATE/
- ✅ .github/pull_request_template.md
- ✅ .github/workflows/ci.yml
- ✅ .github/FUNDING.yml (template)

---

## Additional Integrations (Optional)

### Recommended Bots/Apps
1. **Codecov** - Code coverage reporting
2. **Renovate** - Automated dependency updates (alternative to Dependabot)
3. **Snyk** - Security vulnerability scanning
4. **SonarCloud** - Code quality analysis
5. **Stale** - Close inactive issues/PRs

### Badges to Add (After Publication)
```markdown
[![CI](https://github.com/OWNER/cleverkeys/workflows/CI/badge.svg)](https://github.com/OWNER/cleverkeys/actions)
[![codecov](https://codecov.io/gh/OWNER/cleverkeys/branch/main/graph/badge.svg)](https://codecov.io/gh/OWNER/cleverkeys)
[![GitHub release](https://img.shields.io/github/release/OWNER/cleverkeys.svg)](https://github.com/OWNER/cleverkeys/releases)
[![GitHub downloads](https://img.shields.io/github/downloads/OWNER/cleverkeys/total.svg)](https://github.com/OWNER/cleverkeys/releases)
```

---

## Labels

### Recommended Issue/PR Labels

**Type:**
- `bug` - Something isn't working (red)
- `feature` - New feature or request (green)
- `enhancement` - Improvement to existing feature (blue)
- `documentation` - Documentation improvements (light blue)
- `security` - Security vulnerability (red, urgent)

**Priority:**
- `P0` - Critical, blocking (red)
- `P1` - High priority (orange)
- `P2` - Medium priority (yellow)
- `P3` - Low priority (gray)

**Component:**
- `neural` - Neural prediction system
- `ui` - User interface
- `accessibility` - Accessibility features
- `i18n` - Internationalization
- `performance` - Performance optimization
- `build` - Build system

**Status:**
- `good first issue` - Good for newcomers (purple)
- `help wanted` - Extra attention needed (green)
- `wontfix` - Will not be worked on (white)
- `duplicate` - Duplicate issue (gray)
- `invalid` - Invalid issue (gray)

---

## Milestones

### Suggested Milestones
1. **v1.0.0 - Genesis** (Current)
   - Complete Kotlin rewrite
   - Feature parity with Unexpected-Keyboard
   - Production release

2. **v1.1.0 - Polaris** (Next)
   - Emoji picker UI
   - Long-press popup UI
   - 50k dictionaries (20 languages)
   - Theme customization UI

3. **v2.0.0 - Aurora** (Future)
   - Voice input integration
   - Handwriting recognition improvements
   - Custom gesture support
   - Plugin system

---

**Last Updated**: 2025-11-16
**Version**: 1.0
**For**: GitHub Repository Setup
