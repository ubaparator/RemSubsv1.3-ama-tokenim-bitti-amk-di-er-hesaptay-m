package com.example.encode.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent Room Entity storing video hardsub encoding tasks.
 *
 * Ensures tasks survive activity destroy, app backgrounding, app restart, and crashes.
 */
@Entity(tableName = "encode_tasks")
data class EncodeTaskEntity(
    @PrimaryKey
    val id: String,
    val videoUriString: String,
    val videoFileName: String,
    val assContent: String,
    val introVideoUriString: String? = null,
    val introDurationMs: Long = 0L,
    val introKeepAudio: Boolean = false,
    val outputFilePath: String = "",
    val encoderName: String = "libx264",
    val crf: Int = 20,
    val preset: String = "veryfast",
    val resolution: String = "Kaynakla Aynı",
    val fps: String = "Kaynakla Aynı",
    val bitrate: String = "Otomatik",
    val audioOption: String = "Orijinal Sesi Koru",
    val status: String = STATUS_PENDING,
    val progressPercent: Int = 0,
    val progressFraction: Float = 0f,
    val processedDurationMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val currentFrame: Long = 0L,
    val totalFrames: Long = 0L,
    val speed: Float = 0f,
    val currentFps: Float = 0f,
    val bitrateKbps: Float = 0f,
    val estimatedRemainingSeconds: Long = 0L,
    val startTimeMs: Long = 0L,
    val lastHeartbeatTimeMs: Long = 0L,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val publishedMediaStoreUri: String? = null
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_ENCODING = "ENCODING"
        const val STATUS_RETRYING = "RETRYING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
    }

    val isActive: Boolean
        get() = status == STATUS_ENCODING || status == STATUS_RETRYING

    val isFinished: Boolean
        get() = status == STATUS_COMPLETED || status == STATUS_FAILED || status == STATUS_CANCELLED
}
