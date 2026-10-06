package com.example.encode

import android.content.Context
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.example.model.SubtitleCue
import com.example.model.SubtitleStyle
import com.example.parser.AssGenerator
import com.example.util.FontManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale

/**
 * Unified HardsubEncoder API bridge.
 *
 * All encoding execution is centralized in [HardsubEncodeWorker] via WorkManager
 * and [EncodeQueueManager]. This ensures a single encoding engine without duplicate
 * pipelines, parallel encodes, or competing services.
 */
object HardsubEncoder {
    private const val TAG = "HardsubEncoder"

    private val _encodeState = MutableStateFlow(EncodeState())
    val encodeState: StateFlow<EncodeState> = _encodeState.asStateFlow()

    fun updateState(state: EncodeState) {
        _encodeState.value = state
    }

    fun updateState(transform: (EncodeState) -> EncodeState) {
        _encodeState.update(transform)
    }

    fun isFFmpegAvailable(): Boolean {
        return try {
            val version = FFmpegKitConfig.getVersion()
            Log.d(TAG, "Dahili FFmpeg sürümü: $version")
            version != null
        } catch (t: Throwable) {
            Log.w(TAG, "Dahili FFmpeg kontrolü: ${t.localizedMessage}")
            false
        }
    }

    fun resetState() {
        _encodeState.value = EncodeState()
    }

    fun cancelEncoding(context: Context? = null) {
        val currentTaskId = HardsubEncodeWorker.currentRunningTaskId
        if (currentTaskId != null && context != null) {
            EncodeQueueManager.cancelTask(context, currentTaskId)
        } else {
            HardsubEncodeWorker.cancelCurrentRunningTask()
        }
        _encodeState.update {
            it.copy(
                isPreparing = false,
                isEncoding = false,
                isCancelled = true,
                currentPhaseText = "İşlem iptal edildi",
                errorMessage = "Encode kullanıcı tarafından iptal edildi."
            )
        }
    }

    /**
     * Entry point to centralized Hardsub encoding via [EncodeQueueManager].
     */
    suspend fun startHardsubEncode(
        context: Context,
        videoUri: Uri,
        cues: List<SubtitleCue>,
        style: SubtitleStyle,
        customFontFile: File? = null,
        additionalStyles: List<String> = emptyList(),
        settings: EncodingSettings = EncodingSettings(),
        sourceMetadata: SourceVideoMetadata = SourceVideoMetadata(),
        introVideoUri: Uri? = null,
        introDurationMs: Long = 0L,
        introKeepAudio: Boolean = false
    ) {
        val videoWidth = if (sourceMetadata.width > 0) sourceMetadata.width else 1920
        val videoHeight = if (sourceMetadata.height > 0) sourceMetadata.height else 1080
        val assContent = AssGenerator.generateAss(
            title = "remsubs_hardsub",
            subtitles = cues,
            style = style,
            applyTimeOffset = false,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            additionalStyles = additionalStyles,
            introOffsetMs = 0L
        )
        val videoFileName = FontManager.getFileName(context, videoUri) ?: "video.mp4"

        EncodeQueueManager.enqueueTask(
            context = context,
            videoUri = videoUri,
            videoFileName = videoFileName,
            assContent = assContent,
            introVideoUri = introVideoUri,
            introDurationMs = introDurationMs,
            introKeepAudio = introKeepAudio,
            settings = settings
        )
    }

    fun formatTimeSeconds(totalSeconds: Long): String {
        val mins = totalSeconds / 60
        val secs = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}
