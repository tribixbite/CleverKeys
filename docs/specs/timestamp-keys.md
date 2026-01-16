# Timestamp Keys Specification

**Status**: Implemented
**Version**: 1.2.9
**Last Updated**: 2026-01-15
**GitHub Issue**: #1103

## Overview

Timestamp keys insert the current date and/or time formatted according to a user-specified pattern when pressed. This allows users to quickly insert dates, times, or combined datetime strings without typing them manually.

## Syntax

### Short Syntax (New Style)

```
timestamp:'pattern'
```

Example: `Date:timestamp:'yyyy-MM-dd'` displays "Date" and inserts "2026-01-15"

### Long Syntax (Legacy Style)

```
:timestamp symbol='symbol':'pattern'
```

Example: `:timestamp symbol='📅':'yyyy-MM-dd HH:mm'`

### Pattern Format

Uses Java DateTimeFormatter patterns:

| Pattern | Description | Example |
|---------|-------------|---------|
| `yyyy` | 4-digit year | 2026 |
| `yy` | 2-digit year | 26 |
| `MM` | Month (01-12) | 01 |
| `MMM` | Month abbrev | Jan |
| `dd` | Day (01-31) | 15 |
| `HH` | Hour 24h (00-23) | 14 |
| `hh` | Hour 12h (01-12) | 02 |
| `mm` | Minute (00-59) | 30 |
| `ss` | Second (00-59) | 45 |
| `a` | AM/PM | PM |
| `E` | Day of week | Wed |
| `EEEE` | Full day name | Wednesday |

### Common Examples

| Key Definition | Display | Output Example |
|---------------|---------|----------------|
| `📅:timestamp:'yyyy-MM-dd'` | 📅 | 2026-01-15 |
| `🕐:timestamp:'HH:mm'` | 🕐 | 14:30 |
| `📆:timestamp:'yyyy-MM-dd HH:mm:ss'` | 📆 | 2026-01-15 14:30:45 |
| `🗓:timestamp:'EEEE, MMMM d, yyyy'` | 🗓 | Wednesday, January 15, 2026 |
| `⏰:timestamp:'h:mm a'` | ⏰ | 2:30 PM |

## Implementation

### Key Components

| Component | File | Purpose |
|-----------|------|---------|
| `Kind.Timestamp` | `KeyValue.kt:126` | Enum value for timestamp keys |
| `TimestampFormat` | `KeyValue.kt:162-173` | Data class holding pattern and symbol |
| `makeTimestampKey()` | `KeyValue.kt:460-466` | Factory method to create timestamp keys |
| `parseTimestampKeydef()` | `KeyValueParser.kt:121-127` | Parser for short syntax |
| `handleTimestampKey()` | `KeyEventHandler.kt:377-401` | Handler that formats and inserts time |

### API Compatibility

- **API 26+ (Android 8.0+)**: Uses `java.time.LocalDateTime` and `DateTimeFormatter`
- **API < 26**: Falls back to `java.text.SimpleDateFormat` with `java.util.Date`

### Error Handling

If the pattern is invalid:
1. Logs a warning: `"Invalid timestamp format: [pattern]"`
2. Falls back to inserting the pattern string itself

## Usage in Custom Layouts

Add to `custom_extra_keys` setting or custom layout XML:

```
timestamp:'yyyy-MM-dd'
```

Or with custom symbol:
```
📅:timestamp:'yyyy-MM-dd'
```

## Testing

1. Add timestamp key to extra keys
2. Press key - should insert current formatted time
3. Test various patterns (date-only, time-only, combined)
4. Test on API < 26 device for fallback behavior
5. Test invalid pattern handling

## Related

- [Short Swipe Customization](./short-swipe-customization.md) - For adding timestamp to key gestures
- [Custom Layouts](./layout-system.md) - For adding to custom layouts
- [Java DateTimeFormatter](https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html)
