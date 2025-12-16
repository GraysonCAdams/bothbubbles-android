# Spam Detection Service

## Purpose

Detect and report spam messages. Provides filtering and reporting functionality for unwanted messages.

## Files

| File | Description |
|------|-------------|
| `SpamDetector.kt` | Detect spam messages using heuristics/ML |
| `SpamReportingService.kt` | Report spam to carriers/services |
| `SpamRepository.kt` | Manage spam sender database |

## Architecture

```
Spam Detection Flow:

IncomingMessage → SpamDetector
               → Check sender against spam list
               → Analyze message content
               → Apply ML classification
               → Return spam score/classification

Spam Reporting Flow:

User Reports Spam → SpamReportingService
                 → Add to local spam list
                 → Report to carrier (if supported)
                 → Block sender
```

## Required Patterns

### Spam Detection

```kotlin
class SpamDetector @Inject constructor(
    private val spamRepository: SpamRepository
) {
    suspend fun isSpam(message: Message, sender: Handle): SpamResult {
        // Check blocked list
        if (spamRepository.isBlocked(sender.address)) {
            return SpamResult.Blocked
        }

        // Check known spam patterns
        val score = calculateSpamScore(message)

        return when {
            score > SPAM_THRESHOLD -> SpamResult.Spam(score)
            score > SUSPICIOUS_THRESHOLD -> SpamResult.Suspicious(score)
            else -> SpamResult.Safe
        }
    }

    private fun calculateSpamScore(message: Message): Float {
        var score = 0f

        // Short message from unknown sender
        if (message.text?.length ?: 0 < 20) score += 0.1f

        // Contains suspicious URLs
        if (containsSuspiciousUrl(message.text)) score += 0.3f

        // Contains common spam keywords
        if (containsSpamKeywords(message.text)) score += 0.2f

        // From short code (promotional)
        if (isShortCode(message.senderHandle)) score += 0.2f

        return score
    }
}
```

### Spam Reporting

```kotlin
class SpamReportingService @Inject constructor(
    private val spamRepository: SpamRepository,
    private val contactBlockingService: ContactBlockingService
) {
    suspend fun reportSpam(message: Message, sender: Handle) {
        // Add to local spam list
        spamRepository.addSpamSender(sender.address)

        // Block the sender
        contactBlockingService.blockContact(sender.address)

        // Report to carrier (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            reportToCarrier(message)
        }
    }
}
```

## Best Practices

1. Combine multiple signals for accuracy
2. Allow user override (mark as not spam)
3. Respect carrier reporting APIs
4. Store spam list locally
5. Don't auto-delete, just filter from inbox
