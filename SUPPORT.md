# Support

Thank you for using CleverKeys! We're here to help.

---

## 📚 **Documentation**

Before asking for help, please check our comprehensive documentation:

- **[User Manual](USER_MANUAL.md)** - Complete guide (12 sections, 70+ subsections)
- **[FAQ](FAQ.md)** - Frequently asked questions (80+ Q&A pairs)
- **[Installation Guide](TESTING_NEXT_STEPS.md)** - Step-by-step setup
- **[Troubleshooting](USER_MANUAL.md#10-troubleshooting)** - Common issues and solutions
- **[README](README.md)** - Quick start and feature overview

---

## 💬 **Getting Help**

### GitHub Discussions (Recommended)
**Best for**: General questions, feature discussions, community help

- **Q&A**: Ask questions and get answers from the community
- **Ideas**: Discuss feature suggestions
- **Show and Tell**: Share your customizations and experiences

👉 [Start a Discussion](https://github.com/OWNER/cleverkeys/discussions)

### GitHub Issues
**Best for**: Bug reports, feature requests

⚠️ **Please check existing issues first** to avoid duplicates

- **Bug Reports**: Use the [bug report template](.github/ISSUE_TEMPLATE/bug_report.yml)
- **Feature Requests**: Use the [feature request template](.github/ISSUE_TEMPLATE/feature_request.yml)

👉 [Browse Issues](https://github.com/OWNER/cleverkeys/issues)
👉 [New Bug Report](https://github.com/OWNER/cleverkeys/issues/new?template=bug_report.yml)
👉 [New Feature Request](https://github.com/OWNER/cleverkeys/issues/new?template=feature_request.yml)

---

## 🐛 **Reporting Bugs**

When reporting bugs, please include:

1. **CleverKeys version** (see Settings → About)
2. **Android version** (e.g., Android 13)
3. **Device model** (e.g., Pixel 7 Pro)
4. **Steps to reproduce** (detailed, step-by-step)
5. **Expected behavior** (what should happen)
6. **Actual behavior** (what actually happens)
7. **Screenshots** (if applicable)
8. **Logs** (if available - see below)

### Getting Logs

```bash
# Filter for CleverKeys logs
adb logcat -s "CleverKeys"

# Or save to file
adb logcat -s "CleverKeys" > cleverkeys.log
```

See [MANUAL_TESTING_GUIDE.md](MANUAL_TESTING_GUIDE.md) for detailed debugging instructions.

---

## 💡 **Feature Requests**

We love feature suggestions! When requesting a feature:

1. **Check existing requests** - Your idea might already exist
2. **Describe the problem** - What issue would this solve?
3. **Explain your solution** - How should it work?
4. **Consider alternatives** - Are there other approaches?
5. **Additional context** - Screenshots, examples, use cases

Remember: CleverKeys is privacy-first and 100% local. Features requiring network connectivity will not be accepted.

---

## 🔒 **Security Vulnerabilities**

**DO NOT** report security vulnerabilities publicly in GitHub Issues.

Instead, please:
- **Email**: security@cleverkeys.org *(to be set up)*
- **GitHub Security Advisories**: [Private disclosure](https://github.com/OWNER/cleverkeys/security/advisories/new)

See [SECURITY.md](SECURITY.md) for our complete security policy and responsible disclosure process.

---

## 🤝 **Contributing**

Want to help make CleverKeys better?

- **[Contributing Guide](CONTRIBUTING.md)** - How to contribute code, docs, or testing
- **[Code of Conduct](CODE_OF_CONDUCT.md)** - Our community standards
- **[Pull Request Template](.github/pull_request_template.md)** - How to submit changes

---

## 📬 **Contact**

### Official Channels
- **GitHub Discussions**: Community support and discussions
- **GitHub Issues**: Bug reports and feature requests
- **Email**: support@cleverkeys.org *(to be set up)*

### Response Time
- **GitHub Discussions**: Community-driven (usually < 48 hours)
- **GitHub Issues**:
  - Critical bugs: < 24 hours
  - High priority: < 48 hours
  - Medium priority: < 7 days
  - Low priority: Best effort

### Social Media
*(To be added after launch)*
- Twitter/X: @CleverKeys
- Reddit: r/CleverKeys
- Discord: *(if demand exists)*

---

## ❓ **Common Questions**

**Q: Is CleverKeys free?**
A: Yes! CleverKeys is 100% free and open source under GPL-3.0 license. No ads, no subscriptions, no in-app purchases.

**Q: Does CleverKeys collect my data?**
A: **NO**. CleverKeys collects ZERO data. Everything runs locally on your device. See [PRIVACY_POLICY.md](PRIVACY_POLICY.md).

**Q: How do I enable CleverKeys?**
A: Settings → Languages & input → Manage keyboards → Toggle CleverKeys ON. See [USER_MANUAL.md](USER_MANUAL.md#2-installation--setup).

**Q: Why isn't swipe typing working?**
A: Check Settings → Enable swipe typing. Neural models need to load (takes 2-3 seconds on first use). See [FAQ.md](FAQ.md#basic-usage).

**Q: Can I use CleverKeys with other languages?**
A: Yes! CleverKeys supports 20 languages with automatic detection. See [USER_MANUAL.md](USER_MANUAL.md#7-multi-language-support).

**Q: How do I customize the keyboard layout?**
A: Settings → Keyboard Layouts → Select from 89+ layouts or customize extra keys. See [USER_MANUAL.md](USER_MANUAL.md#6-customization).

**Q: Is CleverKeys accessible?**
A: Yes! Full support for TalkBack, Switch Access, and other accessibility services. See [USER_MANUAL.md](USER_MANUAL.md#8-accessibility-features).

---

## 📖 **Additional Resources**

### For Users
- [Release Notes](RELEASE_NOTES_v1.0.0.md) - What's new in v1.0.0
- [Privacy Policy](PRIVACY_POLICY.md) - Our privacy commitment
- [Changelog](CHANGELOG.md) - Version history

### For Developers
- [Architecture Specs](docs/specs/) - System specifications
- [Table of Contents](docs/TABLE_OF_CONTENTS.md) - Complete file index
- [Project Status](migrate/project_status.md) - Development history

### For Contributors
- [Contributors](CONTRIBUTORS.md) - Recognition and how to contribute
- [Release Checklist](RELEASE_CHECKLIST.md) - Release process (for maintainers)

---

## 🙏 **Thank You!**

We appreciate your interest in CleverKeys. Whether you're reporting a bug, requesting a feature, or just asking a question - your feedback helps make CleverKeys better for everyone.

**Remember**: Be respectful, patient, and helpful. We're all here to build a better keyboard together.

---

**Last Updated**: 2025-11-16
**Version**: 1.0
**Support Channels**: GitHub Discussions, GitHub Issues

🧠 **Think Faster** • ⌨️ **Type Smarter** • 🔒 **Stay Private**
