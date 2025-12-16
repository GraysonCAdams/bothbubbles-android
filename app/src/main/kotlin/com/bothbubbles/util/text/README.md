# Text Utilities

## Purpose

Text processing and normalization utilities.

## Files

| File | Description |
|------|-------------|
| `TextNormalization.kt` | Text normalization functions |

## Required Patterns

### Text Normalization

```kotlin
object TextNormalization {
    /**
     * Normalize text for comparison (lowercase, trim, collapse whitespace).
     */
    fun normalize(text: String): String {
        return text
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")
    }

    /**
     * Normalize phone number for comparison.
     */
    fun normalizePhoneNumber(phone: String): String {
        return phone.filter { it.isDigit() }
    }

    /**
     * Normalize email for comparison.
     */
    fun normalizeEmail(email: String): String {
        return email.trim().lowercase()
    }

    /**
     * Remove diacritics/accents from text.
     */
    fun removeDiacritics(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{M}"), "")
    }

    /**
     * Get initials from a name (e.g., "John Doe" -> "JD").
     */
    fun getInitials(name: String?, maxLength: Int = 2): String {
        if (name.isNullOrBlank()) return "?"
        return name.split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
            .take(maxLength)
            .map { it.first().uppercaseChar() }
            .joinToString("")
            .ifEmpty { "?" }
    }
}
```

## Best Practices

1. Use object for stateless utilities
2. Handle null/blank input gracefully
3. Document normalization rules
4. Use Unicode-aware operations
5. Provide inverse operations where appropriate
