package com.bothbubbles.services.categorization

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityAnnotation
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractor
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for extracting entities from message text using Google ML Kit.
 *
 * Handles:
 * - Model download management (WiFi vs cellular)
 * - Entity extraction for categorization
 * - Graceful fallback when model unavailable
 *
 * Extracted entity types used for categorization:
 * - MONEY → Transactions
 * - TRACKING_NUMBER → Deliveries
 * - DATE_TIME → Reminders (with keyword context)
 */
@Singleton
class EntityExtractionService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "EntityExtractionService"

        // Estimated model size for user display
        const val MODEL_SIZE_MB = 20
    }

    private val entityExtractor: EntityExtractor by lazy {
        EntityExtraction.getClient(
            EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH)
                .build()
        )
    }

    private val _modelDownloaded = MutableStateFlow(false)
    val modelDownloaded: StateFlow<Boolean> = _modelDownloaded.asStateFlow()

    private val _downloadProgress = MutableStateFlow<DownloadState>(DownloadState.NotStarted)
    val downloadProgress: StateFlow<DownloadState> = _downloadProgress.asStateFlow()

    sealed class DownloadState {
        data object NotStarted : DownloadState()
        data object Downloading : DownloadState()
        data object Completed : DownloadState()
        data class Failed(val error: String) : DownloadState()
    }

    /**
     * Check if the ML model is already downloaded.
     */
    suspend fun checkModelDownloaded(): Boolean = withContext(Dispatchers.IO) {
        try {
            // ML Kit doesn't provide a direct "is downloaded" API,
            // so we check by attempting a simple extraction
            val params = EntityExtractionParams.Builder("test").build()
            entityExtractor.annotate(params).await()
            _modelDownloaded.value = true
            _downloadProgress.value = DownloadState.Completed
            true
        } catch (e: Exception) {
            // Model not downloaded or other error
            _modelDownloaded.value = false
            false
        }
    }

    /**
     * Download the ML model.
     *
     * @param allowCellular If true, download even on cellular. If false, only download on WiFi.
     * @return true if download succeeded, false otherwise
     */
    suspend fun downloadModel(allowCellular: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        // Check network type
        if (!allowCellular && !isOnWifi()) {
            Log.d(TAG, "Not on WiFi and cellular download not allowed")
            _downloadProgress.value = DownloadState.Failed("WiFi required for download")
            return@withContext false
        }

        try {
            _downloadProgress.value = DownloadState.Downloading
            Log.d(TAG, "Starting ML model download...")

            entityExtractor.downloadModelIfNeeded().await()

            _modelDownloaded.value = true
            _downloadProgress.value = DownloadState.Completed
            Log.i(TAG, "ML model downloaded successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download ML model", e)
            _downloadProgress.value = DownloadState.Failed(e.message ?: "Unknown error")
            false
        }
    }

    /**
     * Extract entities from text.
     * Returns empty map if model not downloaded.
     */
    suspend fun extractEntities(text: String): ExtractedEntities = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            return@withContext ExtractedEntities.EMPTY
        }

        if (!_modelDownloaded.value) {
            // Try to check if model is available
            if (!checkModelDownloaded()) {
                Log.d(TAG, "Model not downloaded, skipping entity extraction")
                return@withContext ExtractedEntities.EMPTY
            }
        }

        try {
            val params = EntityExtractionParams.Builder(text).build()
            val annotations = entityExtractor.annotate(params).await()

            parseAnnotations(annotations)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting entities", e)
            ExtractedEntities.EMPTY
        }
    }

    private fun parseAnnotations(annotations: List<EntityAnnotation>): ExtractedEntities {
        val moneyEntities = mutableListOf<MoneyEntity>()
        val trackingNumbers = mutableListOf<String>()
        val dateTimes = mutableListOf<DateTimeEntity>()

        for (annotation in annotations) {
            val annotatedText = annotation.annotatedText

            for (entity in annotation.entities) {
                when (entity.type) {
                    Entity.TYPE_MONEY -> {
                        val moneyEntity = entity.asMoneyEntity()
                        if (moneyEntity != null) {
                            moneyEntities.add(
                                MoneyEntity(
                                    text = annotatedText,
                                    currency = moneyEntity.unnormalizedCurrency,
                                    amount = moneyEntity.integerPart.toDouble() +
                                            (moneyEntity.fractionalPart / 100.0)
                                )
                            )
                        }
                    }
                    Entity.TYPE_TRACKING_NUMBER -> {
                        val trackingEntity = entity.asTrackingNumberEntity()
                        if (trackingEntity != null) {
                            trackingNumbers.add(annotatedText)
                            Log.d(TAG, "Found tracking number: $annotatedText (carrier: ${trackingEntity.parcelCarrier})")
                        }
                    }
                    Entity.TYPE_DATE_TIME -> {
                        val dateTimeEntity = entity.asDateTimeEntity()
                        if (dateTimeEntity != null) {
                            dateTimes.add(
                                DateTimeEntity(
                                    text = annotatedText,
                                    timestampMillis = dateTimeEntity.timestampMillis
                                )
                            )
                        }
                    }
                }
            }
        }

        return ExtractedEntities(
            moneyEntities = moneyEntities,
            trackingNumbers = trackingNumbers,
            dateTimes = dateTimes
        )
    }

    private fun isOnWifi(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Release resources when no longer needed.
     */
    fun close() {
        entityExtractor.close()
    }
}

/**
 * Container for extracted entities from a message.
 */
data class ExtractedEntities(
    val moneyEntities: List<MoneyEntity>,
    val trackingNumbers: List<String>,
    val dateTimes: List<DateTimeEntity>
) {
    val hasMoney: Boolean get() = moneyEntities.isNotEmpty()
    val hasTrackingNumber: Boolean get() = trackingNumbers.isNotEmpty()
    val hasDateTime: Boolean get() = dateTimes.isNotEmpty()
    val isEmpty: Boolean get() = !hasMoney && !hasTrackingNumber && !hasDateTime

    companion object {
        val EMPTY = ExtractedEntities(emptyList(), emptyList(), emptyList())
    }
}

data class MoneyEntity(
    val text: String,
    val currency: String,
    val amount: Double
)

data class DateTimeEntity(
    val text: String,
    val timestampMillis: Long
)
