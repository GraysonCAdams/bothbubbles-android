# Message Categorization

## Purpose

ML-powered message categorization to organize messages into categories like transactions, promotions, OTP codes, etc.

## Files

| File | Description |
|------|-------------|
| `CategorizationRepository.kt` | Category data management |
| `EntityExtractionService.kt` | Extract entities (dates, amounts, etc.) from messages |
| `MessageCategorizer.kt` | ML model for message classification |
| `MessageCategory.kt` | Category enum and models |
| `MlModelUpdateWorker.kt` | Background worker for ML model updates |

## Architecture

```
Categorization Flow:

IncomingMessage → MessageCategorizer
                  ├── ML Model Classification
                  ├── Entity Extraction
                  └── Category Assignment

Categories:
├── PERSONAL      - Regular conversations
├── TRANSACTION   - Banking, payments
├── PROMOTION     - Marketing, deals
├── OTP           - One-time passwords
└── SPAM          - Spam/junk messages
```

## Required Patterns

### Message Classification

```kotlin
class MessageCategorizer @Inject constructor(
    private val entityExtractionService: EntityExtractionService
) {
    suspend fun categorize(message: Message): MessageCategory {
        // Extract entities first
        val entities = entityExtractionService.extractEntities(message.text)

        // Check for OTP patterns
        if (entities.hasOtpCode) return MessageCategory.OTP

        // Check for transaction patterns
        if (entities.hasMoneyAmount) return MessageCategory.TRANSACTION

        // Use ML model for remaining classification
        return mlModel.classify(message.text)
    }
}
```

### Entity Extraction

```kotlin
class EntityExtractionService {
    fun extractEntities(text: String): ExtractedEntities {
        return ExtractedEntities(
            otpCodes = extractOtpCodes(text),
            moneyAmounts = extractMoney(text),
            dates = extractDates(text),
            phoneNumbers = extractPhoneNumbers(text)
        )
    }
}
```

### Background Model Updates

```kotlin
class MlModelUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            downloadLatestModel()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
```

## Best Practices

1. Use regex for deterministic patterns (OTP, phone numbers)
2. Use ML for fuzzy classification
3. Update models periodically via WorkManager
4. Cache categorization results
5. Allow user override of categories
