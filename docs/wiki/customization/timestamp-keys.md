# Timestamp Keys

Insert the current date or time with a single key press. Perfect for journaling, note-taking, and timestamping documents.

## What Are Timestamp Keys?

Timestamp keys are special keys that insert formatted date and time when pressed. Instead of manually typing "2026-01-15" or "14:30", you can press a single key.

## Quick Start

### Using Pre-defined Keys

CleverKeys includes 8 ready-to-use timestamp keys:

| Key Name | Display | Output Example |
|----------|---------|----------------|
| `timestamp_date` | 📅 | 2026-01-15 |
| `timestamp_time` | 🕐 | 14:30 |
| `timestamp_datetime` | 📆 | 2026-01-15 14:30 |
| `timestamp_time_seconds` | ⏱ | 14:30:45 |
| `timestamp_date_short` | 📅 | 01/15/26 |
| `timestamp_date_long` | 🗓 | Wednesday, January 15, 2026 |
| `timestamp_time_12h` | 🕐 | 2:30 PM |
| `timestamp_iso` | 📋 | 2026-01-15T14:30:45 |

### Adding to Extra Keys

1. Go to **Settings → Layout → Extra Keys**
2. Add any of the key names above (e.g., `timestamp_date`)
3. The key will appear in your extra keys row

## Custom Patterns

Create your own timestamp format using the pattern syntax:

```
timestamp:'pattern'
```

Or with a custom symbol:

```
📅:timestamp:'yyyy-MM-dd'
```

### Pattern Characters

| Pattern | Meaning | Example |
|---------|---------|---------|
| `yyyy` | 4-digit year | 2026 |
| `yy` | 2-digit year | 26 |
| `MM` | Month (01-12) | 01 |
| `MMM` | Month abbreviation | Jan |
| `MMMM` | Full month name | January |
| `dd` | Day of month (01-31) | 15 |
| `HH` | Hour, 24-hour (00-23) | 14 |
| `hh` | Hour, 12-hour (01-12) | 02 |
| `mm` | Minutes (00-59) | 30 |
| `ss` | Seconds (00-59) | 45 |
| `a` | AM/PM marker | PM |
| `E` | Day of week | Wed |
| `EEEE` | Full day name | Wednesday |

### Custom Pattern Examples

| Pattern | Output |
|---------|--------|
| `timestamp:'dd/MM/yyyy'` | 15/01/2026 |
| `timestamp:'HH:mm:ss'` | 14:30:45 |
| `timestamp:'MMM d, yyyy'` | Jan 15, 2026 |
| `timestamp:'yyyy-MM-dd HH:mm'` | 2026-01-15 14:30 |
| `timestamp:'EEEE h:mm a'` | Wednesday 2:30 PM |

## Use Cases

### Journaling

Add `timestamp_datetime` to quickly date your entries:
- Press key → "2026-01-15 14:30"
- Start typing your journal entry

### Meeting Notes

Use `timestamp_time` for timestamps during meetings:
- "14:30 - Discussion started"
- "14:45 - Action items reviewed"

### File Naming

Use `timestamp_iso` for sortable filenames:
- "2026-01-15T14:30:45"
- Works great with note-taking apps

### Quick Date Entry

Use `timestamp_date_short` for forms:
- 01/15/26 format works for most date fields

## Per-Key Customization

You can also assign timestamp keys to any key's short swipe:

1. Go to **Settings → Per-Key Customization**
2. Select a key
3. Choose a direction
4. Enter `timestamp:'yyyy-MM-dd'` or a pre-defined key name

## Troubleshooting

### Wrong Date Format

Make sure your pattern uses the correct characters. Common mistakes:
- `YYYY` should be `yyyy` (lowercase)
- `DD` should be `dd` (lowercase)
- `HH` is 24-hour, `hh` is 12-hour

### Pattern Shows Instead of Date

If the pattern text appears instead of the formatted date:
- Check for typos in the pattern
- Ensure quotes are correct: `timestamp:'pattern'`
- Invalid patterns fall back to showing the pattern text

## Technical Details

For technical implementation details, see the [Timestamp Keys Specification](../../specs/timestamp-keys.md).
