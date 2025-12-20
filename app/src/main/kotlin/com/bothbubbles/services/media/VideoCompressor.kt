package com.bothbubbles.services.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import timber.log.Timber
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for compressing video files before upload.
 *
 * Uses Android's MediaCodec API for hardware-accelerated video transcoding.
 * Supports configurable quality presets for different use cases.
 */
@Singleton
class VideoCompressor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "VideoCompressor"
        private const val TIMEOUT_US = 10000L

        // Quality presets
        enum class Quality(
            val maxWidth: Int,
            val maxHeight: Int,
            val videoBitrate: Int,
            val audioBitrate: Int,
            val frameRate: Int
        ) {
            LOW(640, 480, 1_000_000, 64_000, 24),       // ~1 Mbps, 480p
            MEDIUM(1280, 720, 2_500_000, 128_000, 30),   // ~2.5 Mbps, 720p
            HIGH(1920, 1080, 5_000_000, 192_000, 30),    // ~5 Mbps, 1080p
            MMS(480, 360, 200_000, 32_000, 15),          // ~200 Kbps, very aggressive for carrier limits
            ORIGINAL(-1, -1, -1, -1, -1)                  // No compression
        }
    }

    private val cacheDir: File by lazy {
        File(context.cacheDir, "compressed_videos").apply { mkdirs() }
    }

    /**
     * Compress a video to the specified quality preset.
     *
     * @param inputUri URI of the source video
     * @param quality Quality preset to use
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return Path to compressed video file, or null if compression failed
     */
    suspend fun compress(
        inputUri: Uri,
        quality: Quality = Quality.MEDIUM,
        onProgress: ((Float) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        if (quality == Quality.ORIGINAL) {
            Timber.d("ORIGINAL quality selected, skipping compression")
            return@withContext null
        }

        val inputFd = try {
            context.contentResolver.openFileDescriptor(inputUri, "r")
        } catch (e: Exception) {
            Timber.e(e, "Failed to open input URI: $inputUri")
            return@withContext null
        } ?: return@withContext null

        val outputFile = File(cacheDir, "compressed_${System.currentTimeMillis()}.mp4")

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var videoDecoder: MediaCodec? = null
        var videoEncoder: MediaCodec? = null
        var audioDecoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(inputFd.fileDescriptor)

            // Find video and audio tracks
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                when {
                    mime.startsWith("video/") && videoTrackIndex == -1 -> {
                        videoTrackIndex = i
                        videoFormat = format
                    }
                    mime.startsWith("audio/") && audioTrackIndex == -1 -> {
                        audioTrackIndex = i
                        audioFormat = format
                    }
                }
            }

            if (videoTrackIndex == -1) {
                Timber.e("No video track found")
                return@withContext null
            }

            // Get source video properties
            val sourceWidth = videoFormat!!.getInteger(MediaFormat.KEY_WIDTH)
            val sourceHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val duration = videoFormat.getLong(MediaFormat.KEY_DURATION)

            // Calculate output dimensions maintaining aspect ratio
            val (outputWidth, outputHeight) = calculateOutputDimensions(
                sourceWidth, sourceHeight, quality.maxWidth, quality.maxHeight
            )

            Timber.d("Compressing video: ${sourceWidth}x${sourceHeight} -> ${outputWidth}x${outputHeight}")
            Timber.d("Bitrate: ${quality.videoBitrate}, Frame rate: ${quality.frameRate}")

            // Create output format for video encoder
            val outputVideoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                outputWidth,
                outputHeight
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, quality.videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, quality.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
            }

            // Create muxer
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Create encoder
            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoEncoder.configure(outputVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderSurface = videoEncoder.createInputSurface()
            videoEncoder.start()

            // Create decoder with encoder's surface as output
            videoDecoder = MediaCodec.createDecoderByType(
                videoFormat.getString(MediaFormat.KEY_MIME)!!
            )
            videoDecoder.configure(videoFormat, encoderSurface, null, 0)
            videoDecoder.start()

            // Select video track
            extractor.selectTrack(videoTrackIndex)

            // Process video frames
            val muxerVideoTrack = processVideoTrack(
                extractor, videoDecoder, videoEncoder, muxer, duration, onProgress
            )

            // Handle audio if present (copy without re-encoding for speed)
            if (audioTrackIndex != -1 && audioFormat != null) {
                extractor.unselectTrack(videoTrackIndex)
                extractor.selectTrack(audioTrackIndex)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                copyAudioTrack(extractor, muxer, audioFormat)
            }

            muxer.stop()
            Timber.d("Compression complete: ${outputFile.absolutePath}")
            Timber.d("Output size: ${outputFile.length() / 1024} KB")

            outputFile.absolutePath
        } catch (e: Exception) {
            Timber.e(e, "Video compression failed")
            outputFile.delete()
            null
        } finally {
            try { videoDecoder?.stop() } catch (e: Exception) { Timber.d(e, "Failed to stop video decoder") }
            try { videoDecoder?.release() } catch (e: Exception) { Timber.d(e, "Failed to release video decoder") }
            try { videoEncoder?.stop() } catch (e: Exception) { Timber.d(e, "Failed to stop video encoder") }
            try { videoEncoder?.release() } catch (e: Exception) { Timber.d(e, "Failed to release video encoder") }
            try { audioDecoder?.stop() } catch (e: Exception) { Timber.d(e, "Failed to stop audio decoder") }
            try { audioDecoder?.release() } catch (e: Exception) { Timber.d(e, "Failed to release audio decoder") }
            try { muxer?.release() } catch (e: Exception) { Timber.d(e, "Failed to release muxer") }
            try { extractor?.release() } catch (e: Exception) { Timber.d(e, "Failed to release extractor") }
            try { inputFd.close() } catch (e: Exception) { Timber.d(e, "Failed to close input file descriptor") }
        }
    }

    /**
     * Compress a video for MMS (very aggressive compression).
     */
    suspend fun compressForMms(
        inputUri: Uri,
        maxSizeBytes: Long,
        onProgress: ((Float) -> Unit)? = null
    ): String? {
        // First try with MMS quality preset
        val compressed = compress(inputUri, Quality.MMS, onProgress)
        if (compressed != null) {
            val file = File(compressed)
            if (file.length() <= maxSizeBytes) {
                return compressed
            }
            // If still too large, video might be too long for MMS
            Timber.w("Compressed video (${file.length()} bytes) exceeds MMS limit ($maxSizeBytes bytes)")
            file.delete()
        }
        return null
    }

    /**
     * Get estimated compressed size without actually compressing.
     */
    fun estimateCompressedSize(
        inputUri: Uri,
        quality: Quality
    ): Long? {
        return try {
            val extractor = MediaExtractor()
            val fd = context.contentResolver.openFileDescriptor(inputUri, "r") ?: return null
            extractor.setDataSource(fd.fileDescriptor)

            var duration = 0L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    duration = format.getLong(MediaFormat.KEY_DURATION)
                    break
                }
            }

            extractor.release()
            fd.close()

            if (duration == 0L) return null

            // Estimate: (bitrate * duration) / 8 + audio overhead
            val durationSeconds = duration / 1_000_000.0
            val videoBits = quality.videoBitrate * durationSeconds
            val audioBits = quality.audioBitrate * durationSeconds
            ((videoBits + audioBits) / 8).toLong()
        } catch (e: Exception) {
            Timber.e(e, "Failed to estimate compressed size")
            null
        }
    }

    /**
     * Clean up old compressed files.
     */
    fun cleanupCache() {
        cacheDir.listFiles()?.forEach { file ->
            if (System.currentTimeMillis() - file.lastModified() > 24 * 60 * 60 * 1000) {
                file.delete()
            }
        }
    }

    private fun calculateOutputDimensions(
        sourceWidth: Int,
        sourceHeight: Int,
        maxWidth: Int,
        maxHeight: Int
    ): Pair<Int, Int> {
        if (sourceWidth <= maxWidth && sourceHeight <= maxHeight) {
            // Already smaller than target, but ensure even dimensions
            return Pair(
                (sourceWidth / 2) * 2,
                (sourceHeight / 2) * 2
            )
        }

        val aspectRatio = sourceWidth.toFloat() / sourceHeight.toFloat()

        val (targetWidth, targetHeight) = if (aspectRatio > maxWidth.toFloat() / maxHeight) {
            // Width is limiting factor
            Pair(maxWidth, (maxWidth / aspectRatio).toInt())
        } else {
            // Height is limiting factor
            Pair((maxHeight * aspectRatio).toInt(), maxHeight)
        }

        // Ensure even dimensions (required by most codecs)
        return Pair(
            (targetWidth / 2) * 2,
            (targetHeight / 2) * 2
        )
    }

    private fun processVideoTrack(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        duration: Long,
        onProgress: ((Float) -> Unit)?
    ): Int {
        val decoderInputBuffers = decoder.inputBuffers
        val bufferInfo = MediaCodec.BufferInfo()
        var muxerTrackIndex = -1
        var inputDone = false
        var outputDone = false
        var lastProgress = 0f

        while (!outputDone) {
            // Feed input to decoder
            if (!inputDone) {
                val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = decoderInputBuffers[inputIndex]
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)

                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inputIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(
                            inputIndex, 0, sampleSize,
                            extractor.sampleTime, 0
                        )
                        extractor.advance()
                    }
                }
            }

            // Get output from decoder (renders to encoder's surface)
            val decoderOutputIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (decoderOutputIndex >= 0) {
                val doRender = bufferInfo.size != 0
                decoder.releaseOutputBuffer(decoderOutputIndex, doRender)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    encoder.signalEndOfInputStream()
                }

                // Report progress
                if (duration > 0 && onProgress != null) {
                    val progress = (bufferInfo.presentationTimeUs.toFloat() / duration).coerceIn(0f, 1f)
                    if (progress - lastProgress >= 0.01f) {
                        lastProgress = progress
                        onProgress(progress)
                    }
                }
            }

            // Get output from encoder
            val encoderOutputIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                encoderOutputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxerTrackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                }
                encoderOutputIndex >= 0 -> {
                    val encodedData = encoder.getOutputBuffer(encoderOutputIndex)
                    if (encodedData != null && bufferInfo.size > 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(muxerTrackIndex, encodedData, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(encoderOutputIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
        }

        return muxerTrackIndex
    }

    private fun copyAudioTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        audioFormat: MediaFormat
    ) {
        val audioTrackIndex = muxer.addTrack(audioFormat)
        val buffer = ByteBuffer.allocate(1024 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = extractor.sampleTime
            bufferInfo.flags = extractor.sampleFlags

            muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
            extractor.advance()
        }
    }
}
