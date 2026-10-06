package com.example.encode

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale

/**
 * Dedicated internal FFmpeg Utilities.
 *
 * All actual encoding execution is centrally and exclusively handled by [HardsubEncodeWorker].
 * This object provides string escaping, media track inspections, and friendly error parsing.
 */
object FfmpegEncodeManager {
    private const val TAG = "FfmpegEncodeManager"

    val encodeState: StateFlow<EncodeState>
        get() = HardsubEncoder.encodeState

    fun resetState() {
        HardsubEncoder.resetState()
    }

    fun cancelEncode() {
        HardsubEncoder.cancelEncoding()
    }

    fun escapeForAssFilter(path: String): String {
        return path
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace(":", "\\:")
            .replace(",", "\\,")
            .replace("[", "\\[")
            .replace("]", "\\]")
    }

    fun resolveUniqueOutputFile(parentDir: File, originalFileName: String): File {
        val base = originalFileName.substringBeforeLast(".")
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "video" }

        var candidate = File(parentDir, "${base}_encoded.mp4")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(parentDir, "${base}_encoded_$counter.mp4")
            counter++
        }
        return candidate
    }

    internal fun parseFriendlyError(logs: String): String {
        val lower = logs.lowercase(Locale.ROOT)
        return when {
            lower.contains("no such filter: 'ass'") -> "libass subtitle filtresi bulunamadı."
            lower.contains("cannot open font") || lower.contains("font not found") -> "Font dosyası yüklenemedi."
            lower.contains("unknown encoder 'libx264'") -> "libx264 video kodlayıcı bulunamadı."
            lower.contains("no space left on device") -> "Cihazda yeterli depolama alanı yok."
            lower.contains("permission denied") -> "Depolama izni hatası."
            lower.contains("invalid data found") -> "Video veya altyazı dosyası bozuk."
            else -> "Video encode edilemedi. FFmpeg hatası oluştu."
        }
    }

    internal fun hasAudioTrack(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) return true
            }
            false
        } catch (_: Exception) {
            false
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }
}
