# Parsing Utilities

## Purpose

Utilities for parsing dates, URLs, phone numbers, and other structured data.

## Files

| File | Description |
|------|-------------|
| `AbsoluteDateParser.kt` | Parse absolute dates from text |
| `DateFormatters.kt` | Date formatting utilities |
| `DateParsingUtils.kt` | Date parsing helpers |
| `DatePatterns.kt` | Regex patterns for dates |
| `PhoneAndCodeParsingUtils.kt` | Extract phone numbers and codes from text |
| `RelativeDateParser.kt` | Parse relative dates ("tomorrow", "next week") |
| `UrlParsingUtils.kt` | Extract and validate URLs |

## Required Patterns

### URL Parsing

```kotlin
object UrlParsingUtils {
    private val URL_PATTERN = Pattern.compile(
        "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)",
        Pattern.CASE_INSENSITIVE
    )

    fun extractUrls(text: String): List<String> {
        val matcher = URL_PATTERN.matcher(text)
        val urls = mutableListOf<String>()
        while (matcher.find()) {
            urls.add(matcher.group(1))
        }
        return urls
    }

    fun isValidUrl(url: String): Boolean {
        return try {
            URI(url)
            url.startsWith("http://") || url.startsWith("https://")
        } catch (e: Exception) {
            false
        }
    }
}
```

### Date Parsing

```kotlin
object DateParsingUtils {
    fun parseRelativeDate(text: String): Long? {
        val lowercase = text.lowercase()
        val now = Calendar.getInstance()

        return when {
            lowercase == "today" -> now.timeInMillis
            lowercase == "tomorrow" -> {
                now.add(Calendar.DAY_OF_YEAR, 1)
                now.timeInMillis
            }
            lowercase.startsWith("next ") -> {
                parseNextDay(lowercase.removePrefix("next "), now)
            }
            else -> null
        }
    }

    fun formatRelativeTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60_000 -> "Just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            else -> formatDate(timestamp)
        }
    }
}
```

### Phone/Code Extraction

```kotlin
object PhoneAndCodeParsingUtils {
    // OTP pattern: 4-8 digit codes
    private val OTP_PATTERN = Regex("\\b(\\d{4,8})\\b")

    // Phone pattern
    private val PHONE_PATTERN = Regex(
        "(?:\\+?1[-.\\s]?)?(?:\\([0-9]{3}\\)|[0-9]{3})[-.\\s]?[0-9]{3}[-.\\s]?[0-9]{4}"
    )

    fun extractOtpCodes(text: String): List<String> {
        return OTP_PATTERN.findAll(text).map { it.value }.toList()
    }

    fun extractPhoneNumbers(text: String): List<String> {
        return PHONE_PATTERN.findAll(text).map { it.value }.toList()
    }
}
```

## Best Practices

1. Use compiled regex patterns for performance
2. Handle null/invalid input gracefully
3. Provide both parsing and validation
4. Support multiple formats where appropriate
5. Document expected input formats
