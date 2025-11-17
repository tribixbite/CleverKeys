# Security Policy

## Our Security Commitment

Security and privacy are core principles of CleverKeys. We take security vulnerabilities seriously and appreciate the responsible disclosure of any issues you discover.

---

## Supported Versions

| Version | Supported          | Status |
| ------- | ------------------ | ------ |
| 1.0.x   | :white_check_mark: | Current stable release |
| < 1.0   | :x:                | Development versions (not released) |

---

## Reporting a Vulnerability

### Please Report Security Vulnerabilities Responsibly

**DO NOT** open a public GitHub issue for security vulnerabilities.

### How to Report

**Email**: security@cleverkeys.org *(to be set up)*

**Temporary**: Use GitHub Security Advisories (private disclosure)
- Go to: https://github.com/OWNER/cleverkeys/security/advisories/new
- Or email the maintainers privately

### What to Include

Please include as much information as possible:

1. **Vulnerability Type**
   - What type of vulnerability is it? (e.g., injection, authentication bypass, data exposure)

2. **Impact**
   - What could an attacker do with this vulnerability?
   - Who is affected? (all users, specific configurations, etc.)

3. **Steps to Reproduce**
   - Detailed steps to reproduce the vulnerability
   - Sample code or proof of concept (if applicable)

4. **Affected Versions**
   - Which versions are affected?
   - Have you tested on the latest version?

5. **Suggested Fix** (optional)
   - Do you have suggestions for how to fix it?
   - Can you provide a patch?

6. **Your Contact Information**
   - How can we reach you for follow-up questions?
   - Do you want to be credited? (optional, you can remain anonymous)

### Example Report

```
Subject: [SECURITY] Potential data exposure in clipboard history

Vulnerability Type: Data exposure
Severity: Medium

Description:
Clipboard history may expose sensitive data when [specific conditions]

Impact:
An attacker with physical access to the device could potentially
access clipboard history containing sensitive information.

Steps to Reproduce:
1. Enable clipboard history
2. Copy sensitive data (password, credit card)
3. [Specific actions to expose data]
4. Data is accessible without authentication

Affected Versions:
- v1.0.0
- v1.0.1

Suggested Fix:
Implement encryption for clipboard history using Android KeyStore

Contact: researcher@example.com
Credit: Yes, please credit as "Security Researcher"
```

---

## Response Timeline

We are committed to responding promptly to security reports:

| Timeline | Action |
|----------|--------|
| **< 24 hours** | Initial acknowledgment of your report |
| **< 7 days** | Preliminary assessment and severity rating |
| **< 30 days** | Detailed response and fix timeline |
| **< 90 days** | Fix released (for most vulnerabilities) |

**For critical vulnerabilities**, we aim to release a fix within **7 days**.

### What to Expect

1. **Acknowledgment** (< 24 hours)
   - We'll confirm receipt of your report
   - We may ask for clarification or additional details

2. **Assessment** (< 7 days)
   - We'll validate the vulnerability
   - Assign severity rating (see below)
   - Determine affected versions

3. **Fix Development** (timeline varies)
   - Develop and test a fix
   - Keep you updated on progress
   - Share patch for your review (if desired)

4. **Coordinated Disclosure** (90 days default)
   - We'll coordinate disclosure timeline with you
   - Default: 90 days from report or when fix is released (whichever is sooner)
   - Can be adjusted for critical issues or special circumstances

5. **Public Disclosure**
   - Security advisory published
   - CVE assigned (if applicable)
   - Credit given to reporter (if desired)
   - Fix released to all users

---

## Severity Rating

We use the following severity ratings based on impact and exploitability:

### Critical
**Impact**: Complete compromise of security or privacy
**Examples**:
- Remote code execution
- Unauthorized access to all user data
- Complete bypass of privacy protections
- Data sent to external servers without consent

**Response**: Fix within 7 days, immediate advisory

### High
**Impact**: Significant security or privacy violation
**Examples**:
- Local privilege escalation
- Access to sensitive user data
- Bypass of security features
- Unauthorized data access

**Response**: Fix within 30 days

### Medium
**Impact**: Moderate security or privacy concern
**Examples**:
- Information disclosure (limited)
- Partial bypass of security features
- Denial of service (local)

**Response**: Fix within 60 days

### Low
**Impact**: Minor security or privacy issue
**Examples**:
- Information disclosure (minimal impact)
- Issues requiring complex attack scenarios
- Theoretical vulnerabilities with no known exploit

**Response**: Fix in next regular release

---

## Privacy-Specific Vulnerabilities

Given CleverKeys' privacy-first commitment, we consider the following as **HIGH or CRITICAL** severity:

**Automatic CRITICAL**:
- Any network communication (CleverKeys should never use network)
- Data sent to external servers
- Telemetry or analytics code
- Third-party tracking

**Automatic HIGH**:
- Unencrypted storage of sensitive data
- Data exposure to other apps
- Logging of sensitive user input
- Privacy policy violations

---

## Out of Scope

The following are generally **NOT** considered security vulnerabilities:

### Expected Behavior
- Information visible on device screen (user is looking at keyboard)
- Data in Android's app-specific storage (protected by Android OS)
- Theoretical attacks requiring physical device access + unlocked device
- Issues in third-party dependencies (unless we can fix)

### Privacy by Design
- No cloud sync (this is intentional, not a missing feature)
- No backup to Google (this is intentional for privacy)
- No analytics (this is intentional)

### Low Impact
- Bugs that don't affect security or privacy
- Feature requests
- UI/UX issues
- Performance issues

**However**, if you're unsure whether something is a security issue, please report it anyway. We'd rather review it than miss something important.

---

## Security Best Practices

### For Users

**Protect Your Device**:
1. Use screen lock (PIN, pattern, biometric)
2. Enable device encryption
3. Keep Android updated
4. Don't root your device (unless you know what you're doing)

**Using CleverKeys Safely**:
1. Review permissions (should be minimal)
2. Verify network usage (should be zero)
3. Don't share your device unlocked
4. Use password manager for sensitive data entry

**Verify Authenticity**:
1. Download from official sources only
2. Verify APK signatures
3. Check repository URL
4. Review source code (it's open source!)

### For Developers

**Code Review**:
- All code changes require review
- Focus on security-sensitive areas
- No network code permitted
- Privacy impact assessment for all features

**Testing**:
- Security testing before release
- Static analysis (Android Lint)
- Dependency vulnerability scanning
- Manual code review

**Dependencies**:
- Minimal dependencies
- Regular updates
- Vulnerability monitoring
- License compliance

---

## Security Advisories

Published security advisories will be available at:
- GitHub Security Advisories: https://github.com/OWNER/cleverkeys/security/advisories
- SECURITY.md updates (this file)
- Release notes

**Subscribe** to GitHub repository to receive notifications.

---

## Hall of Fame

We appreciate security researchers who help keep CleverKeys secure:

### 2025
*(To be updated when vulnerabilities are responsibly disclosed)*

**Want to see your name here?**
- Responsibly disclose security vulnerabilities
- Help us protect our users
- Follow coordinated disclosure process

---

## Security Features

### Built-in Security

**Local-Only Processing**:
- All typing data stays on device
- No network communication
- No cloud services
- No telemetry

**Minimal Permissions**:
- No internet permission
- No location access
- No camera/microphone access
- No contact access

**Data Protection**:
- Android app sandbox isolation
- Encrypted storage (via Android)
- No logs of sensitive data
- Secure deletion on uninstall

**Open Source**:
- Fully auditable code
- Community security review
- Transparent development
- No hidden functionality

---

## Compliance

CleverKeys security practices align with:

- **OWASP Mobile Top 10**: Addressing common mobile security risks
- **CWE Top 25**: Common Weakness Enumeration
- **Android Security Best Practices**: Following Google's guidelines
- **Privacy by Design**: Security integrated from the start

---

## Legal

### Safe Harbor

We support safe harbor for security researchers:

**We will NOT pursue legal action against researchers who**:
- Make a good faith effort to comply with this policy
- Do not access user data beyond what's necessary to demonstrate the vulnerability
- Do not intentionally harm users or CleverKeys
- Give us reasonable time to fix the issue before public disclosure

### Disclosure

- We may disclose your identity in security advisories (with your permission)
- We may share technical details of the vulnerability in advisories
- We will coordinate disclosure timeline with you

---

## Contact

### Security Team
- **Email**: security@cleverkeys.org *(to be set up)*
- **GitHub**: Private Security Advisories
- **PGP Key**: *(to be provided)*

### General Questions
- **Security questions**: Use email above
- **Non-security bugs**: GitHub Issues
- **General questions**: GitHub Discussions

---

## Updates to This Policy

This security policy may be updated as CleverKeys evolves:

- **Last Updated**: 2025-11-16
- **Version**: 1.0
- **Next Review**: 2026-11-16

**Changes will be announced via**:
- Git commit history (this file)
- GitHub releases
- Security advisories (if significant)

---

## Acknowledgments

We thank the security research community for helping keep CleverKeys secure.

**Special thanks to**:
- The Android Security Team for platform security features
- The open source security community
- All researchers who responsibly disclose vulnerabilities

---

**Thank you for helping keep CleverKeys secure and private!** 🔒

Together we protect user privacy and security.

---

**Version**: 1.0
**Effective**: 2025-11-16
**Contact**: security@cleverkeys.org *(to be set up)*
