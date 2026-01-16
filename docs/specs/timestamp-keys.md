# Timestamp Keys

## Overview

Timestamp keys insert the current date and/or time formatted according to a user-specified pattern when pressed. This allows users to quickly insert dates, times, or combined datetime strings without typing them manually.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/KeyValue.kt:126` | `Kind.Timestamp` | Enum value for timestamp keys |
| `src/main/kotlin/tribixbite/cleverkeys/KeyValue.kt:162-173` | `TimestampFormat` | Data class holding pattern and symbol |
| `src/main/kotlin/tribixbite/cleverkeys/KeyValue.kt:460-466` | `makeTimestampKey()` | Factory method to create keys |
| `src/main/kotlin/tribixbite/cleverkeys/KeyValueParser.kt:121-127` | `parseTimestampKeydef()` | Parser for short syntax |
| `src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt:377-401` | `handleTimestampKey()` | Formats and inserts time |

## Architecture

```
Key Press (timestamp key)
       |
       v
+----------------------+
| KeyEventHandler      | -- Receives timestamp KeyValue
| handleKeyPress()     |
+----------------------+
       |
       v
+----------------------+
| handleTimestampKey() | -- Extract pattern from KeyValue.timestampFormat
+----------------------+
       |
       v
+----------------------+
| DateTimeFormatter    | -- Format current time with pattern
| or SimpleDateFormat  |
+----------------------+
       |
       v
+----------------------+
| InputConnection      | -- Commit formatted text
| commitText()         |
+----------------------+
```

## Syntax

### Short Syntax

```
symbol:timestamp:'pattern'
```

Example: `📅:timestamp:'yyyy-MM-dd'` displays "📅" and inserts "2026-01-15"

### Long Syntax (Legacy)

```
:timestamp symbol='symbol':'pattern'
```

Example: `:timestamp symbol='📅':'yyyy-MM-dd HH:mm'`

## Pattern Format

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

## Pre-defined Timestamp Keys

Available without specifying patterns:

| Key Name | Symbol | Pattern | Output Example |
|----------|--------|---------|----------------|
| `timestamp_date` | 📅 | `yyyy-MM-dd` | 2026-01-15 |
| `timestamp_time` | 🕐 | `HH:mm` | 14:30 |
| `timestamp_datetime` | 📆 | `yyyy-MM-dd HH:mm` | 2026-01-15 14:30 |
| `timestamp_time_seconds` | ⏱ | `HH:mm:ss` | 14:30:45 |
| `timestamp_date_short` | 📅 | `MM/dd/yy` | 01/15/26 |
| `timestamp_date_long` | 🗓 | `EEEE, MMMM d, yyyy` | Wednesday, January 15, 2026 |
| `timestamp_time_12h` | 🕐 | `h:mm a` | 2:30 PM |
| `timestamp_iso` | 📋 | `yyyy-MM-dd'T'HH:mm:ss` | 2026-01-15T14:30:45 |

## Implementation Details

### Data Class

```kotlin
data class TimestampFormat(
    val pattern: String,
    val symbol: String
)
```

### Factory Method

```kotlin
fun makeTimestampKey(pattern: String, symbol: String): KeyValue {
    return KeyValue(
        kind = Kind.Timestamp,
        char = '\u0000',
        timestampFormat = TimestampFormat(pattern, symbol)
    )
}
```

### Handler Implementation

```kotlin
private fun handleTimestampKey(key: KeyValue) {
    val format = key.timestampFormat ?: return
    val formattedTime = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // API 26+: Use java.time
            val formatter = DateTimeFormatter.ofPattern(format.pattern)
            LocalDateTime.now().format(formatter)
        } else {
            // API < 26: Use SimpleDateFormat
            val formatter = SimpleDateFormat(format.pattern, Locale.getDefault())
            formatter.format(Date())
        }
    } catch (e: Exception) {
        Log.w(TAG, "Invalid timestamp format: ${format.pattern}")
        format.pattern  // Fallback: insert pattern itself
    }
    inputConnection.commitText(formattedTime, 1)
}
```

### Parser Implementation

```kotlin
fun parseTimestampKeydef(keydef: String): KeyValue? {
    // Short syntax: symbol:timestamp:'pattern'
    val shortMatch = """(.+):timestamp:'(.+)'""".toRegex().matchEntire(keydef)
    if (shortMatch != null) {
        val (symbol, pattern) = shortMatch.destructured
        return makeTimestampKey(pattern, symbol)
    }
    return null
}
```

### API Compatibility

- **API 26+ (Android 8.0+)**: Uses `java.time.LocalDateTime` and `DateTimeFormatter`
- **API < 26**: Falls back to `java.text.SimpleDateFormat` with `java.util.Date`

### Error Handling

If the pattern is invalid:
1. Logs warning: `"Invalid timestamp format: [pattern]"`
2. Falls back to inserting the pattern string itself
