# Name Inference Service

## Purpose

Infer contact names from message content when a contact is not in the address book. Analyzes message signatures and patterns.

## Files

| File | Description |
|------|-------------|
| `NameInferenceService.kt` | Analyze messages to infer sender names |

## Architecture

```
Name Inference Flow:

Unknown Sender Message → NameInferenceService
                       → Analyze message patterns:
                           ├── Email signatures ("- John")
                           ├── Self-introductions ("This is John")
                           ├── Business signatures ("John Smith, CEO")
                           └── Previous inferences
                       → Store inferred name
                       → Update handle display name
```

## Required Patterns

### Name Extraction

```kotlin
class NameInferenceService @Inject constructor(
    private val handleDao: HandleDao
) {
    suspend fun analyzeMessage(message: Message, handle: Handle) {
        if (handle.displayName != null) return  // Already has name

        val inferredName = extractName(message.text)
        if (inferredName != null) {
            handleDao.updateDisplayName(handle.id, inferredName)
        }
    }

    private fun extractName(text: String): String? {
        // Try different patterns
        return extractFromSignature(text)
            ?: extractFromIntroduction(text)
            ?: extractFromEmailSignature(text)
    }

    private fun extractFromSignature(text: String): String? {
        // Pattern: "- John" or "—John" at end of message
        val regex = """[-—]\s*([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)$""".toRegex()
        return regex.find(text.trim())?.groupValues?.get(1)
    }

    private fun extractFromIntroduction(text: String): String? {
        // Pattern: "This is John" or "Hey, it's John"
        val patterns = listOf(
            """(?:This is|It's|I'm|I am)\s+([A-Z][a-z]+)""".toRegex(RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }
    }
}
```

## Best Practices

1. Only infer for contacts without names
2. Require high confidence before storing
3. Allow user override of inferred names
4. Analyze multiple messages for consistency
5. Handle common false positives
