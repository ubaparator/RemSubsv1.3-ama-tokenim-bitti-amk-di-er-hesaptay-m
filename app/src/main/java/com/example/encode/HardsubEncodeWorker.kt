package com.example.encode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.example.MainActivity
import com.example.R
import com.example.encode.db.AppDatabase
import com.example.encode.db.EncodeTaskEntity
import com.example.util.FontManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.Locale

/**
 * High-performance, Persistent WorkManager Foreground CoroutineWorker for Video Hardsub Encoding.
 *
 * Requirements fulfilled:
 * 1. Intro + Subtitle:
 *    - Intro video NEVER has subtitles applied.
 *    - Main video has ASS/libass applied first: [main -> ASS -> normalize].
 *    - Intro is normalized without subtitles: [intro -> normalize].
 *    - Concat: [v_intro][v_main_sub]concat. Subtitle timings for main video stay 100% exact.
 * 2. FPS:
 *    - Never forced to 25. Source video FPS is preserved by default.
 *    - No unnecessary FPS conversion filters.
 * 3. Resolution:
 *    - Never forced to 1920x1080. Source resolution preserved by default.
 *    - Scale/pad only applied when resolution changes or intro normalization is required.
 * 4. Hardware Encoder:
 *    - DeviceCodecDetector.resolveBestEncoder() result is applied directly to FFmpeg.
 *    - h264_mediacodec and hevc_mediacodec used with bitrate parameters (no -crf / -preset).
 *    - Automatic software fallback only if hardware encoder is unsupported or fails at runtime.
 * 5. Maximum Performance:
 *    - Single encode at a time via WorkManager unique work.
 *    - Bounded 16KB log ring buffer (zero RAM bloat).
 *    - Single worker CoroutineScope (zero coroutine thrashing).
 *    - Room and Notification progress throttled to ~1 update per second.
 *    - Audio stream copy (-c:a copy) when compatible and no intro.
 * 6. Single Unified System:
 *    - HardsubEncodeWorker is the sole encoding engine.
 *    - Keeps HardsubEncoder.encodeState synchronized for UI observing.
 * 7. Progress:
 *    - Real FFmpeg time / duration calculation. No fake progress or speed.
 *    - Progress notification cancelled on completion, replaced solely by completion notification.
 */
class HardsubEncodeWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "HardsubEncodeWorker"
        const val CHANNEL_ID = "remsubs_encode_channel_v3"
        const val COMPLETE_CHANNEL_ID = "remsubs_encode_complete_v3"
        const val NOTIFICATION_ID = 2001
        const val COMPLETION_NOTIFICATION_BASE_ID = 3000
        const val UNIQUE_WORK_NAME = "remsubs_hardsub_encode_work"

        @Volatile
        var currentRunningTaskId: String? = null
            private set

        @Volatile
        var isCancelledRequested: Boolean = false
            private set

        @Volatile
        var activeSession: FFmpegSession? = null
            private set

        @Volatile
        var isWorkerExecuting: Boolean = false
            private set

        fun enqueueWork(context: Context) {
            try {
                val workRequest = OneTimeWorkRequestBuilder<HardsubEncodeWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    workRequest
                )
                Log.i(TAG, "[EncodeWorker] Work enqueued successfully")
            } catch (t: Throwable) {
                Log.e(TAG, "[EncodeWorker] Error enqueuing expedited work, falling back to standard", t)
                val standardRequest = OneTimeWorkRequestBuilder<HardsubEncodeWorker>().build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    standardRequest
                )
            }
        }

        fun cancelCurrentRunningTask() {
            val taskId = currentRunningTaskId
            if (taskId != null) {
                cancelTaskIfRunning(taskId)
            }
        }

        fun cancelTaskIfRunning(taskId: String) {
            Log.i(TAG, "[EncodeWorker] cancelTaskIfRunning: $taskId, current=$currentRunningTaskId")
            if (currentRunningTaskId == taskId) {
                isCancelledRequested = true
                try {
                    activeSession?.cancel()
                    val sid = activeSession?.sessionId
                    if (sid != null) FFmpegKit.cancel(sid) else FFmpegKit.cancel()
                } catch (t: Throwable) {
                    Log.w(TAG, "Cancel error: ${t.localizedMessage}")
                }
            }
        }
    }

    private var notificationManager: NotificationManager? = null
    private var workerScope: CoroutineScope? = null

    private var lastNotificationUpdateTime = 0L
    private var lastReportedPercent = -1
    private var lastDbUpdateTime = 0L

    @Volatile
    private var lastProgressTimeMs: Long = 0L

    private val speedHistory = ArrayDeque<Float>()
    @Volatile
    private var smoothedSpeed: Float = 0f

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "[EncodeWorker] doWork started (id=$id)")
        isWorkerExecuting = true
        notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()

        val supervisor = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + supervisor)
        workerScope = scope

        // Establish mediaProcessing foreground status
        val initialNotif = buildInitialNotification()
        try {
            setForeground(createForegroundInfo(initialNotif))
            Log.i(TAG, "[EncodeWorker] Foreground service established with mediaProcessing")
        } catch (t: Throwable) {
            Log.e(TAG, "[EncodeWorker] setForeground failed", t)
        }

        val dao = AppDatabase.getInstance(applicationContext).encodeTaskDao()

        // Recovery check: If process was killed previously while ENCODING, reset to RETRYING
        try {
            dao.resetInterruptedTasksToRetrying()
        } catch (t: Throwable) {
            Log.w(TAG, "Error resetting interrupted tasks", t)
        }

        try {
            // Process persistent queue loop - exactly one task at a time
            while (!isStopped) {
                val pendingTasks = dao.getPendingTasks()
                if (pendingTasks.isEmpty()) {
                    Log.i(TAG, "[EncodeWorker] No more pending tasks in queue.")
                    break
                }

                val task = pendingTasks.first()
                currentRunningTaskId = task.id
                isCancelledRequested = false
                lastReportedPercent = -1
                lastNotificationUpdateTime = 0L
                lastDbUpdateTime = 0L

                // Mark ENCODING in Room
                val startedTask = task.copy(
                    status = EncodeTaskEntity.STATUS_ENCODING,
                    progressPercent = 0,
                    progressFraction = 0f,
                    startTimeMs = System.currentTimeMillis(),
                    lastHeartbeatTimeMs = System.currentTimeMillis(),
                    errorMessage = null
                )
                dao.updateTask(startedTask)
                Log.i(TAG, "[EncodeWorker] Task state: ${task.status} -> ENCODING (${task.id})")

                // Update foreground notification for this specific task
                val taskNotif = buildNotification(startedTask, "Hardsub hazırlanıyor...", 0, 0f, 0L, 0L, 0L)
                try {
                    setForeground(createForegroundInfo(taskNotif))
                } catch (_: Throwable) {
                    notificationManager?.notify(NOTIFICATION_ID, taskNotif)
                }

                HardsubEncoder.updateState {
                    it.copy(
                        isPreparing = true,
                        isEncoding = false,
                        isCompleted = false,
                        isCancelled = false,
                        currentPhaseText = "Hazırlanıyor...",
                        progress = 0f,
                        progressPercentage = 0,
                        errorMessage = null
                    )
                }

                // Execute FFmpeg encode
                executeTask(startedTask)

                currentRunningTaskId = null
                activeSession = null

                if (isStopped) {
                    Log.w(TAG, "[EncodeWorker] Worker was stopped during/after task ${task.id}")
                    break
                }
            }
        } finally {
            currentRunningTaskId = null
            activeSession = null
            isWorkerExecuting = false
            scope.cancel()
            workerScope = null

            // Ensure any remaining pending tasks get re-enqueued if worker stopped unexpectedly
            try {
                val remaining = dao.getPendingTasks()
                if (remaining.isNotEmpty() && !isStopped) {
                    enqueueWork(applicationContext)
                }
            } catch (_: Throwable) {}
        }

        if (isStopped) {
            Log.w(TAG, "[EncodeWorker] Worker returning Result.retry() due to stoppage")
            Result.retry()
        } else {
            Log.i(TAG, "[EncodeWorker] Worker returning Result.success()")
            Result.success()
        }
    }

    private suspend fun executeTask(task: EncodeTaskEntity) {
        val dao = AppDatabase.getInstance(applicationContext).encodeTaskDao()
        val cacheWorkingDir = File(applicationContext.cacheDir, "remsubs_encode_${task.id}").apply { mkdirs() }
        val startTime = System.currentTimeMillis()

        var tempInputVideo: File? = null
        var tempIntroVideo: File? = null
        var tempAssFile: File? = null
        var tempOutputFile: File? = null

        try {
            // 1. Prepare Input Video
            updateTaskProgress(task.id, task.videoFileName, 2, "Girdi videosu hazırlanıyor...")
            val videoUri = Uri.parse(task.videoUriString)
            val safeBaseName = task.videoFileName.substringBeforeLast(".").replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "video" }
            val inputVideo = File(cacheWorkingDir, "in_${safeBaseName}.mp4")
            tempInputVideo = inputVideo

            copyUriToFile(videoUri, inputVideo)

            if (!inputVideo.exists() || inputVideo.length() <= 0L) {
                failTask(task.id, task.videoFileName, "Girdi videosu açılamadı veya dosya boş.")
                cleanDirectory(cacheWorkingDir)
                return
            }

            if (isCancelledRequested || isStopped) {
                handleCancellationOrStop(task.id, cacheWorkingDir)
                return
            }

            // 2. Prepare Optional Intro Video
            var effectiveIntroDurMs = 0L
            if (!task.introVideoUriString.isNullOrBlank()) {
                updateTaskProgress(task.id, task.videoFileName, 5, "İntro hazırlanıyor...")
                val introUri = Uri.parse(task.introVideoUriString)
                val introFile = File(cacheWorkingDir, "intro.mp4")
                copyUriToFile(introUri, introFile)
                if (introFile.exists() && introFile.length() > 0L) {
                    tempIntroVideo = introFile
                    effectiveIntroDurMs = if (task.introDurationMs > 0) task.introDurationMs else extractDuration(introFile)
                }
            }

            if (isCancelledRequested || isStopped) {
                handleCancellationOrStop(task.id, cacheWorkingDir)
                return
            }

            // 3. Extract Metadata from Main Video
            val mainMetadata = InputVideoAnalyzer.analyzeVideo(applicationContext, inputVideo, null)
            val mainWidth = if (mainMetadata.width > 0) mainMetadata.width else 1920
            val mainHeight = if (mainMetadata.height > 0) mainMetadata.height else 1080
            val mainFps = if (mainMetadata.fps > 0) mainMetadata.fps else 30.0
            val mainDurationMs = if (mainMetadata.durationMs > 0) mainMetadata.durationMs else extractDuration(inputVideo)

            val totalDurationMs = mainDurationMs + effectiveIntroDurMs

            // 4. Target Resolution (NEVER arbitrarily force 1920x1080)
            val (targetWidth, targetHeight) = when (task.resolution) {
                "1080p", "1080p (FHD)" -> {
                    val w = (mainWidth * 1080 / mainHeight).let { if (it % 2 != 0) it + 1 else it }
                    Pair(w, 1080)
                }
                "720p", "720p (HD)" -> {
                    val w = (mainWidth * 720 / mainHeight).let { if (it % 2 != 0) it + 1 else it }
                    Pair(w, 720)
                }
                "480p", "480p (SD)" -> {
                    val w = (mainWidth * 480 / mainHeight).let { if (it % 2 != 0) it + 1 else it }
                    Pair(w, 480)
                }
                "360p" -> {
                    val w = (mainWidth * 360 / mainHeight).let { if (it % 2 != 0) it + 1 else it }
                    Pair(w, 360)
                }
                else -> {
                    // "Kaynakla Aynı" or blank: Keep native source video resolution
                    val w = if (mainWidth % 2 != 0) mainWidth + 1 else mainWidth
                    val h = if (mainHeight % 2 != 0) mainHeight + 1 else mainHeight
                    Pair(w, h)
                }
            }

            // 5. Target FPS (NEVER arbitrarily force 25 FPS)
            val targetFps = when {
                task.fps.contains("60") -> 60.0
                task.fps.contains("30") -> 30.0
                task.fps.contains("24") -> 24.0
                task.fps.toDoubleOrNull() != null -> task.fps.toDouble()
                else -> mainFps // "Kaynakla Aynı": Keep native source FPS
            }
            val targetFpsStr = if (targetFps == targetFps.toLong().toDouble()) {
                targetFps.toLong().toString()
            } else {
                String.format(Locale.US, "%.3f", targetFps)
            }

            val totalFrames = ((totalDurationMs / 1000.0) * targetFps).toLong().coerceAtLeast(100L)

            val mainNeedsScale = (mainWidth != targetWidth || mainHeight != targetHeight)
            val mainNeedsFps = (task.fps != "Kaynakla Aynı" && task.fps.isNotBlank() && kotlin.math.abs(targetFps - mainFps) > 0.5)

            // 6. Prepare Fonts & ASS Subtitle File
            updateTaskProgress(task.id, task.videoFileName, 8, "Altyazı ve fontlar yükleniyor...")
            val encodeFontsDir = File(cacheWorkingDir, "fonts")
            FontSetupHelper.setupFonts(
                context = applicationContext,
                targetFontsDir = encodeFontsDir,
                customFontFile = null,
                additionalCustomFonts = FontManager.getAllCustomFonts(applicationContext),
                referencedFontNames = FontSetupHelper.extractAllReferencedFontNames(task.assContent, emptyList())
            )

            val assFile = File(cacheWorkingDir, "subtitles.ass")
            tempAssFile = assFile
            assFile.writeText(task.assContent, Charsets.UTF_8)

            // 7. Output Destination
            val outputVideo = File(cacheWorkingDir, "out_${safeBaseName}.mp4")
            if (outputVideo.exists()) outputVideo.delete()
            tempOutputFile = outputVideo

            // 8. Resolve Hardware / Software Video Encoder via DeviceCodecDetector
            val encoderOpt = when (task.encoderName.uppercase(Locale.ROOT)) {
                "MEDIA_CODEC_H264", "H264_MEDIACODEC" -> EncoderOption.MEDIA_CODEC_H264
                "MEDIA_CODEC_H265", "HEVC_MEDIACODEC" -> EncoderOption.MEDIA_CODEC_H265
                "LIBX265" -> EncoderOption.LIBX265
                "LIBX264" -> EncoderOption.LIBX264
                "VP9", "LIBVPX-VP9" -> EncoderOption.VP9
                "AV1", "LIBAOM-AV1" -> EncoderOption.AV1
                else -> EncoderOption.AUTO
            }
            val settings = EncodingSettings(
                encoderOption = encoderOpt,
                resolution = task.resolution,
                fps = task.fps,
                bitrate = task.bitrate,
                crf = task.crf,
                preset = task.preset,
                audioOption = task.audioOption
            )
            val (_, resolvedEncoder) = DeviceCodecDetector.resolveBestEncoder(settings, mainMetadata)
            val isHardwareEncoder = (resolvedEncoder == "h264_mediacodec" || resolvedEncoder == "hevc_mediacodec")

            Log.i(TAG, "[Encode] Target dimensions: ${targetWidth}x${targetHeight}, Target FPS: $targetFpsStr (Source: ${mainFps}fps)")
            Log.i(TAG, "[Encode] Selected encoder: $resolvedEncoder, Hardware: $isHardwareEncoder")

            HardsubEncoder.updateState {
                it.copy(
                    isPreparing = false,
                    isEncoding = true,
                    currentPhaseText = "Encode yapılıyor...",
                    currentEncoderName = resolvedEncoder,
                    totalFrames = totalFrames,
                    sourceMetadata = mainMetadata
                )
            }

            // 9. Execute FFmpeg Pipeline
            var sessionResult = runFfmpegPipeline(
                inputVideo = inputVideo,
                tempIntroVideo = tempIntroVideo,
                assFile = assFile,
                encodeFontsDir = encodeFontsDir,
                outputVideo = outputVideo,
                task = task,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                targetFpsStr = targetFpsStr,
                mainNeedsScale = mainNeedsScale,
                mainNeedsFps = mainNeedsFps,
                effectiveIntroDurMs = effectiveIntroDurMs,
                mainDurationMs = mainDurationMs,
                totalDurationMs = totalDurationMs,
                totalFrames = totalFrames,
                encoderName = resolvedEncoder,
                isHardware = isHardwareEncoder,
                startTime = startTime
            )

            // Clean fallback to software if hardware MediaCodec failed at runtime
            if (isHardwareEncoder && !sessionResult.first && !isCancelledRequested && !isStopped) {
                val fallbackEncoder = if (resolvedEncoder == "hevc_mediacodec") "libx265" else "libx264"
                Log.w(TAG, "[Encode] Hardware encoder ($resolvedEncoder) failed. Falling back to software $fallbackEncoder...")
                updateTaskProgress(task.id, task.videoFileName, 8, "Donanım encoder başarısız oldu, $fallbackEncoder encoder'a geçiliyor...")

                if (outputVideo.exists()) outputVideo.delete()

                HardsubEncoder.updateState {
                    it.copy(currentEncoderName = "$fallbackEncoder (Yazılım Fallback)")
                }

                sessionResult = runFfmpegPipeline(
                    inputVideo = inputVideo,
                    tempIntroVideo = tempIntroVideo,
                    assFile = assFile,
                    encodeFontsDir = encodeFontsDir,
                    outputVideo = outputVideo,
                    task = task,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    targetFpsStr = targetFpsStr,
                    mainNeedsScale = mainNeedsScale,
                    mainNeedsFps = mainNeedsFps,
                    effectiveIntroDurMs = effectiveIntroDurMs,
                    mainDurationMs = mainDurationMs,
                    totalDurationMs = totalDurationMs,
                    totalFrames = totalFrames,
                    encoderName = fallbackEncoder,
                    isHardware = false,
                    startTime = startTime
                )
            }

            if (isCancelledRequested || isStopped) {
                handleCancellationOrStop(task.id, cacheWorkingDir)
                return
            }

            if (!sessionResult.first) {
                val failureLog = sessionResult.second
                Log.e(TAG, "[Encode] FFmpeg failed:\n$failureLog")
                val friendly = FfmpegEncodeManager.parseFriendlyError(failureLog)
                failTask(task.id, task.videoFileName, friendly)
                cleanDirectory(cacheWorkingDir)
                return
            }

            // 10. Validate output file
            if (!outputVideo.exists() || outputVideo.length() <= 1024L) {
                failTask(task.id, task.videoFileName, "Çıktı videosu oluşturulamadı veya dosya boyutu sıfır.")
                cleanDirectory(cacheWorkingDir)
                return
            }

            // 11. Publish to MediaStore (Movies/RemSubs)
            updateTaskProgress(task.id, task.videoFileName, 99, "Galeriye kaydediliyor...")
            val publishedUri = publishToGallery(outputVideo, task.videoFileName)

            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000
            val durationText = formatSeconds(elapsedSec)

            // 12. Mark COMPLETED at 100% in Room
            val completedTask = task.copy(
                status = EncodeTaskEntity.STATUS_COMPLETED,
                progressPercent = 100,
                progressFraction = 1.0f,
                outputFilePath = outputVideo.absolutePath,
                publishedMediaStoreUri = publishedUri?.toString(),
                completedAt = System.currentTimeMillis()
            )
            dao.updateTask(completedTask)

            HardsubEncoder.updateState {
                it.copy(
                    isPreparing = false,
                    isEncoding = false,
                    isCompleted = true,
                    progress = 1.0f,
                    progressPercentage = 100,
                    elapsedSeconds = elapsedSec,
                    outputVideoFile = outputVideo,
                    currentPhaseText = "Encode tamamlandı."
                )
            }

            // 13. Progress notification is cleared; only completion notification remains.
            // If next tasks exist, next task immediately re-uses NOTIFICATION_ID.
            val nextPendingTasks = dao.getPendingTasks()
            val completionNotifId = COMPLETION_NOTIFICATION_BASE_ID + (completedTask.id.hashCode() % 1000).let { if (it < 0) -it else it }

            if (nextPendingTasks.isNotEmpty()) {
                showCompletionNotification(completedTask, durationText, completionNotifId)
            } else {
                notificationManager?.cancel(NOTIFICATION_ID)
                showCompletionNotification(completedTask, durationText, completionNotifId)
            }

            cleanDirectory(cacheWorkingDir)

        } catch (t: Throwable) {
            Log.e(TAG, "Error during encode execution for task ${task.id}", t)
            failTask(task.id, task.videoFileName, "Encode hatası: ${t.localizedMessage ?: t.javaClass.simpleName}")
            cleanDirectory(cacheWorkingDir)
        }
    }

    private suspend fun runFfmpegPipeline(
        inputVideo: File,
        tempIntroVideo: File?,
        assFile: File,
        encodeFontsDir: File,
        outputVideo: File,
        task: EncodeTaskEntity,
        targetWidth: Int,
        targetHeight: Int,
        targetFpsStr: String,
        mainNeedsScale: Boolean,
        mainNeedsFps: Boolean,
        effectiveIntroDurMs: Long,
        mainDurationMs: Long,
        totalDurationMs: Long,
        totalFrames: Long,
        encoderName: String,
        isHardware: Boolean,
        startTime: Long
    ): Pair<Boolean, String> {
        val escapedAssPath = FfmpegEncodeManager.escapeForAssFilter(assFile.absolutePath)
        val escapedFontsDirPath = FfmpegEncodeManager.escapeForAssFilter(encodeFontsDir.absolutePath)

        val args = mutableListOf<String>()
        args.add("-y")

        if (tempIntroVideo != null && tempIntroVideo.exists()) {
            // PROPER INTRO PIPELINE:
            // 1. Intro -> normalize to target dimensions, SAR, and FPS. NO SUBTITLE FILTER ON INTRO!
            // 2. Main -> ASS subtitle filter FIRST, then normalize (scale/fps/setsar).
            // 3. Concat: [v_intro][v_main_sub]concat. Subtitle timings on main video stay 100% exact!
            val introNorm = "[0:v]scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease,pad=$targetWidth:$targetHeight:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=$targetFpsStr[v_intro];"

            val mainSub = buildString {
                append("[1:v]ass='${escapedAssPath}':fontsdir='${escapedFontsDirPath}'")
                if (mainNeedsScale) append(",scale=$targetWidth:$targetHeight")
                if (mainNeedsFps) append(",fps=$targetFpsStr")
                append(",setsar=1[v_main_sub];")
            }

            val concatV = "[v_intro][v_main_sub]concat=n=2:v=1:a=0[vfinal]"

            val mainHasAudio = FfmpegEncodeManager.hasAudioTrack(inputVideo)
            val introHasAudio = FfmpegEncodeManager.hasAudioTrack(tempIntroVideo)
            var hasMappedAudio = false

            val audioFilterPart = when {
                task.audioOption.startsWith("Sessiz", ignoreCase = true) || task.audioOption.startsWith("Yok", ignoreCase = true) -> {
                    hasMappedAudio = false
                    ""
                }
                mainHasAudio -> {
                    hasMappedAudio = true
                    if (task.introKeepAudio && introHasAudio) {
                        ";[0:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo[a0];[1:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo[a1];[a0][a1]concat=n=2:v=0:a=1[afinal]"
                    } else {
                        val introSecStr = String.format(Locale.US, "%.3f", (effectiveIntroDurMs / 1000.0).coerceAtLeast(0.1))
                        ";anullsrc=channel_layout=stereo:sample_rate=44100,atrim=end=$introSecStr[a0];[1:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo[a1];[a0][a1]concat=n=2:v=0:a=1[afinal]"
                    }
                }
                introHasAudio && task.introKeepAudio -> {
                    hasMappedAudio = true
                    val mainSecStr = String.format(Locale.US, "%.3f", (mainDurationMs / 1000.0).coerceAtLeast(0.1))
                    ";[0:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo[a0];anullsrc=channel_layout=stereo:sample_rate=44100,atrim=end=$mainSecStr[a1];[a0][a1]concat=n=2:v=0:a=1[afinal]"
                }
                else -> {
                    hasMappedAudio = false
                    ""
                }
            }

            val filterComplex = "$introNorm$mainSub$concatV$audioFilterPart"

            args.add("-i")
            args.add(tempIntroVideo.absolutePath)
            args.add("-i")
            args.add(inputVideo.absolutePath)
            args.add("-filter_complex")
            args.add(filterComplex)
            args.add("-map")
            args.add("[vfinal]")
            if (hasMappedAudio) {
                args.add("-map")
                args.add("[afinal]")
            }
        } else {
            // NO INTRO: Main video with ASS filter applied directly
            val vf = buildString {
                append("ass='${escapedAssPath}':fontsdir='${escapedFontsDirPath}'")
                if (mainNeedsScale) append(",scale=$targetWidth:$targetHeight")
                if (mainNeedsFps) append(",fps=$targetFpsStr")
            }

            args.add("-i")
            args.add(inputVideo.absolutePath)
            args.add("-vf")
            args.add(vf)
            args.add("-map")
            args.add("0:v:0")
            args.add("-map")
            args.add("0:a?")
        }

        // Video codec & options
        args.add("-c:v")
        args.add(encoderName)

        val effectiveBitrate = when {
            task.bitrate != "Otomatik" && task.bitrate.isNotBlank() -> task.bitrate
            targetHeight >= 2160 -> if (encoderName.contains("hevc")) "11000k" else "15000k"
            targetHeight >= 1080 -> if (encoderName.contains("hevc")) "3800k" else "5500k"
            targetHeight >= 720 -> if (encoderName.contains("hevc")) "2200k" else "3200k"
            targetHeight >= 480 -> if (encoderName.contains("hevc")) "1100k" else "1600k"
            else -> "1000k"
        }

        if (isHardware) {
            // Hardware MediaCodec encoder: use bitrate parameters without software CRF/preset flags
            args.add("-b:v")
            args.add(effectiveBitrate)
            args.add("-maxrate")
            args.add(effectiveBitrate)
            val bufSizeKb = (effectiveBitrate.removeSuffix("k").toIntOrNull() ?: 4500) * 2
            args.add("-bufsize")
            args.add("${bufSizeKb}k")
            args.add("-pix_fmt")
            args.add("yuv420p")
        } else {
            // Software encoder (libx264 / libx265)
            val preset = task.preset.ifBlank { "veryfast" }
            args.add("-preset")
            args.add(preset)
            if (task.bitrate != "Otomatik" && task.bitrate.isNotBlank()) {
                args.add("-b:v")
                args.add(task.bitrate)
            } else {
                args.add("-crf")
                args.add(task.crf.coerceIn(14, 28).toString())
            }
            val threadCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            args.add("-threads")
            args.add(threadCount.toString())
            args.add("-pix_fmt")
            args.add("yuv420p")
        }

        // Audio stream configuration
        if (tempIntroVideo != null) {
            args.add("-c:a")
            args.add("aac")
            args.add("-b:a")
            when {
                task.audioOption.contains("320") -> args.add("320k")
                task.audioOption.contains("128") -> args.add("128k")
                else -> args.add("192k")
            }
        } else {
            val canCopyAudio = task.audioOption.contains("Orijinal", ignoreCase = true) ||
                    task.audioOption.contains("Copy", ignoreCase = true) ||
                    task.audioOption.contains("Koru", ignoreCase = true)

            if (task.audioOption.startsWith("Sessiz", ignoreCase = true) || task.audioOption.startsWith("Yok", ignoreCase = true)) {
                args.add("-an")
            } else if (canCopyAudio) {
                Log.i(TAG, "[Encode] Audio stream copy mode ACTIVE (-c:a copy)")
                args.add("-c:a")
                args.add("copy")
            } else {
                args.add("-c:a")
                args.add("aac")
                args.add("-b:a")
                when {
                    task.audioOption.contains("320") -> args.add("320k")
                    task.audioOption.contains("128") -> args.add("128k")
                    else -> args.add("192k")
                }
            }
        }

        args.add("-movflags")
        args.add("+faststart")
        args.add(outputVideo.absolutePath)

        Log.i(TAG, "[EncodeWorker] Executing FFmpeg: ${args.joinToString(" ")}")

        synchronized(speedHistory) { speedHistory.clear() }
        smoothedSpeed = 0f
        lastProgressTimeMs = System.currentTimeMillis()

        val completionDeferred = CompletableDeferred<FFmpegSession>()
        // Bounded 16KB log ring buffer (eliminates RAM bloat)
        val logBuffer = StringBuilder()

        val session = FFmpegKit.executeWithArgumentsAsync(
            args.toTypedArray(),
            { completedSession ->
                completionDeferred.complete(completedSession)
            },
            { log ->
                val msg = log.message ?: ""
                synchronized(logBuffer) {
                    if (logBuffer.length > 16384) {
                        logBuffer.delete(0, 8192)
                    }
                    logBuffer.append(msg).append("\n")
                }
            },
            { stats ->
                val timeMs = stats.time.toLong()
                val frame = stats.videoFrameNumber.toLong()
                val speed = stats.speed.toFloat()
                val fps = stats.videoFps.toFloat()
                val bitrate = stats.bitrate.toFloat()

                lastProgressTimeMs = System.currentTimeMillis()

                if (speed > 0.02f) {
                    synchronized(speedHistory) {
                        speedHistory.addLast(speed)
                        if (speedHistory.size > 8) speedHistory.removeFirst()
                        smoothedSpeed = speedHistory.average().toFloat()
                    }
                }
                val effectiveSpeed = if (smoothedSpeed > 0.02f) smoothedSpeed else speed

                val progressFraction = if (totalDurationMs > 0 && timeMs > 0) {
                    (timeMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 0.99f)
                } else if (totalFrames > 0 && frame > 0) {
                    (frame.toFloat() / totalFrames.toFloat()).coerceIn(0f, 0.99f)
                } else 0f

                val pct = (progressFraction * 100).toInt().coerceIn(0, 99)

                val remainingSec = if (totalDurationMs > timeMs && effectiveSpeed > 0.05f) {
                    ((totalDurationMs - timeMs) / (effectiveSpeed * 1000f)).toLong().coerceAtLeast(0L)
                } else 0L

                handleLiveProgress(
                    taskId = task.id,
                    fileName = task.videoFileName,
                    percent = pct,
                    fraction = progressFraction,
                    processedMs = timeMs,
                    totalMs = totalDurationMs,
                    frame = frame,
                    totalFrames = totalFrames,
                    speed = effectiveSpeed,
                    fps = fps,
                    bitrate = bitrate,
                    remainingSec = remainingSec
                )
            }
        )

        activeSession = session

        // Lightweight watchdog loop (runs every 15 seconds)
        val scope = workerScope
        val watchdogJob = scope?.launch {
            while (isActive && completionDeferred.isActive) {
                if (isCancelledRequested || isStopped) {
                    Log.i(TAG, "[EncodeWorker] Interruption detected in watchdog. Halting FFmpeg.")
                    try {
                        session.cancel()
                        FFmpegKit.cancel(session.sessionId)
                    } catch (_: Throwable) {}
                    delay(500L)
                    if (completionDeferred.isActive) {
                        completionDeferred.complete(session)
                    }
                    break
                }

                delay(15000L)
                val checkNow = System.currentTimeMillis()
                val silenceProgress = checkNow - lastProgressTimeMs
                if (silenceProgress > 120_000L) {
                    val isStillRunning = session.state == com.arthenica.ffmpegkit.SessionState.RUNNING
                    if (!isStillRunning || silenceProgress > 180_000L) {
                        Log.e(TAG, "FFmpeg timeout detected for task ${task.id}. Terminating.")
                        try {
                            session.cancel()
                            FFmpegKit.cancel(session.sessionId)
                        } catch (_: Throwable) {}
                        completionDeferred.cancel()
                        break
                    }
                }
            }
        }

        val completedSession = try {
            completionDeferred.await()
        } finally {
            watchdogJob?.cancel()
        }
        activeSession = null

        val isSuccess = ReturnCode.isSuccess(completedSession.returnCode)
        val fullLogs = synchronized(logBuffer) { logBuffer.toString() }
        return Pair(isSuccess, fullLogs)
    }

    private fun handleLiveProgress(
        taskId: String,
        fileName: String,
        percent: Int,
        fraction: Float,
        processedMs: Long,
        totalMs: Long,
        frame: Long,
        totalFrames: Long,
        speed: Float,
        fps: Float,
        bitrate: Float,
        remainingSec: Long
    ) {
        val now = System.currentTimeMillis()

        // Throttled notification updates (at most once per second)
        val shouldUpdateNotification = (now - lastNotificationUpdateTime >= 1000L) || (percent != lastReportedPercent)
        if (shouldUpdateNotification) {
            lastNotificationUpdateTime = now
            lastReportedPercent = percent

            val currentId = currentRunningTaskId
            if (currentId == taskId) {
                val notif = buildLiveProgressNotification(
                    fileName = fileName,
                    percent = percent,
                    speed = speed,
                    processedMs = processedMs,
                    totalMs = totalMs,
                    remainingSec = remainingSec,
                    bitrateKbps = bitrate,
                    taskId = taskId
                )
                try {
                    notificationManager?.notify(NOTIFICATION_ID, notif)
                } catch (t: Throwable) {
                    Log.w(TAG, "Notification notify warning: ${t.localizedMessage}")
                }
            }
        }

        // Throttled Room DB updates (at most once per second, zero scope storm)
        if (now - lastDbUpdateTime >= 1000L) {
            lastDbUpdateTime = now

            HardsubEncoder.updateState {
                it.copy(
                    progress = fraction,
                    progressPercentage = percent,
                    currentFrame = frame,
                    currentFps = fps.toDouble(),
                    estimatedRemainingSeconds = remainingSec,
                    averageEstimatedFinishText = if (remainingSec > 0) formatSeconds(remainingSec) else "--:--"
                )
            }

            workerScope?.launch {
                try {
                    val dao = AppDatabase.getInstance(applicationContext).encodeTaskDao()
                    dao.updateProgressMetrics(
                        id = taskId,
                        percent = percent,
                        fraction = fraction,
                        processedMs = processedMs,
                        totalMs = totalMs,
                        frame = frame,
                        totalFrames = totalFrames,
                        speed = speed,
                        fps = fps,
                        bitrate = bitrate,
                        remainingSec = remainingSec,
                        heartbeatTimeMs = now
                    )
                } catch (_: Throwable) {}
            }
        }
    }

    private suspend fun updateTaskProgress(taskId: String, fileName: String, percent: Int, phase: String) {
        val notif = buildLiveProgressNotification(
            fileName = fileName,
            percent = percent,
            taskId = taskId,
            phaseOverride = phase
        )
        try {
            notificationManager?.notify(NOTIFICATION_ID, notif)
        } catch (_: Throwable) {}

        HardsubEncoder.updateState {
            it.copy(
                currentPhaseText = phase,
                progressPercentage = percent,
                progress = percent / 100f
            )
        }

        val dao = AppDatabase.getInstance(applicationContext).encodeTaskDao()
        val existing = dao.getTaskById(taskId)
        if (existing != null) {
            dao.updateTask(
                existing.copy(
                    progressPercent = percent,
                    progressFraction = percent / 100f,
                    lastHeartbeatTimeMs = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun failTask(taskId: String, fileName: String, errorMessage: String) {
        val dao = AppDatabase.getInstance(applicationContext).encodeTaskDao()
        val existing = dao.getTaskById(taskId)
        if (existing != null) {
            val failedTask = existing.copy(
                status = EncodeTaskEntity.STATUS_FAILED,
                errorMessage = errorMessage,
                completedAt = System.currentTimeMillis()
            )
            dao.updateTask(failedTask)

            HardsubEncoder.updateState {
                it.copy(
                    isPreparing = false,
                    isEncoding = false,
                    currentPhaseText = "Encode başarısız",
                    errorMessage = errorMessage
                )
            }

            val nextPendingTasks = dao.getPendingTasks()
            val failureNotifId = COMPLETION_NOTIFICATION_BASE_ID + (failedTask.id.hashCode() % 1000).let { if (it < 0) -it else it }

            if (nextPendingTasks.isNotEmpty()) {
                showFailureNotification(failedTask, failureNotifId)
            } else {
                notificationManager?.cancel(NOTIFICATION_ID)
                showFailureNotification(failedTask, failureNotifId)
            }
        }
    }

    private suspend fun handleCancellationOrStop(taskId: String, workingDir: File) {
        val dao = AppDatabase.getInstance(applicationContext).encodeTaskDao()
        val existing = dao.getTaskById(taskId)
        if (existing != null) {
            val statusToSet = if (isCancelledRequested) EncodeTaskEntity.STATUS_CANCELLED else EncodeTaskEntity.STATUS_RETRYING
            dao.updateTask(
                existing.copy(
                    status = statusToSet,
                    errorMessage = if (isCancelledRequested) "Kullanıcı tarafından iptal edildi." else "Sistem tarafından durduruldu, tekrar başlatılacak.",
                    completedAt = if (isCancelledRequested) System.currentTimeMillis() else null,
                    progressPercent = 0,
                    progressFraction = 0f
                )
            )
        }
        cleanDirectory(workingDir)

        HardsubEncoder.updateState {
            it.copy(
                isPreparing = false,
                isEncoding = false,
                isCancelled = isCancelledRequested,
                currentPhaseText = if (isCancelledRequested) "İşlem iptal edildi" else "Durduruldu"
            )
        }

        val remaining = dao.getPendingTasks()
        if (remaining.isEmpty()) {
            notificationManager?.cancel(NOTIFICATION_ID)
        }
        Log.i(TAG, "[EncodeWorker] Cleanup complete for cancelled/stopped task $taskId")
    }

    private fun createForegroundInfo(notification: Notification): ForegroundInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildInitialNotification(): Notification {
        val contentIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_encode)
            .setContentTitle("Video Encode Servisi")
            .setContentText("Kuyruk hazırlanıyor...")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun buildNotification(
        task: EncodeTaskEntity,
        phase: String,
        percent: Int,
        speed: Float,
        processedMs: Long,
        totalMs: Long,
        remainingSec: Long,
        bitrateKbps: Float = 0f
    ): Notification {
        return buildLiveProgressNotification(
            fileName = task.videoFileName,
            percent = percent,
            speed = speed,
            processedMs = processedMs,
            totalMs = totalMs,
            remainingSec = remainingSec,
            bitrateKbps = bitrateKbps,
            taskId = task.id,
            phaseOverride = phase
        )
    }

    private fun buildLiveProgressNotification(
        fileName: String,
        percent: Int = 0,
        speed: Float = 0f,
        processedMs: Long = 0L,
        totalMs: Long = 0L,
        remainingSec: Long = 0L,
        bitrateKbps: Float = 0f,
        taskId: String? = null,
        phaseOverride: String? = null
    ): Notification {
        val currentTaskId = taskId ?: currentRunningTaskId ?: ""

        val cancelIntent = Intent(applicationContext, HardsubEncodeService::class.java).apply {
            action = HardsubEncodeService.ACTION_CANCEL_TASK
            putExtra(HardsubEncodeService.EXTRA_TASK_ID, currentTaskId)
        }
        val cancelPendingIntent = PendingIntent.getService(
            applicationContext,
            currentTaskId.hashCode(),
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val speedText = if (speed > 0.05f) String.format(Locale.US, "%.1fx", speed) else ""
        val mbPerSecText = if (bitrateKbps > 500f) {
            String.format(Locale.US, "%.1f MB/s", bitrateKbps / 8192f)
        } else ""

        val etaText = if (remainingSec > 0) formatSeconds(remainingSec) else "Kalan süre hesaplanıyor..."
        val durationProgressText = if (totalMs > 0 && processedMs > 0) {
            "${formatMs(processedMs)} / ${formatMs(totalMs)}"
        } else ""

        val lineText = buildString {
            if (phaseOverride != null && phaseOverride.contains("Galeri", ignoreCase = true)) {
                append("Galeriye kaydediliyor... (%99)")
            } else if (phaseOverride != null && percent < 10) {
                append("$phaseOverride (%$percent)")
            } else {
                append("%$percent tamamlandı")
                if (remainingSec > 0) {
                    append(" • Yaklaşık $etaText kaldı")
                } else if (percent > 0) {
                    append(" • Kalan süre hesaplanıyor...")
                }
            }
        }

        val subText = buildString {
            if (speedText.isNotBlank()) append("$speedText • ")
            if (mbPerSecText.isNotBlank()) append("$mbPerSecText • ")
            append("Hardsub yapılıyor")
            if (durationProgressText.isNotBlank()) append(" ($durationProgressText)")
        }

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_encode)
            .setContentTitle("Encode ediliyor • $fileName")
            .setContentText(lineText)
            .setSubText(subText)
            .setProgress(100, percent, percent <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (currentTaskId.isNotBlank()) {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "İptal", cancelPendingIntent)
        }

        return builder.build()
    }

    private fun showCompletionNotification(
        task: EncodeTaskEntity,
        durationText: String,
        notificationId: Int
    ) {
        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            applicationContext,
            task.id.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val videoUri = task.publishedMediaStoreUri?.let { Uri.parse(it) }
            ?: if (task.outputFilePath.isNotBlank()) Uri.fromFile(File(task.outputFilePath)) else null

        val playPending = videoUri?.let { uri ->
            val playIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            PendingIntent.getActivity(
                applicationContext,
                (task.id + "_play").hashCode(),
                Intent.createChooser(playIntent, "Videoyu Oynat"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val sharePending = videoUri?.let { uri ->
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            PendingIntent.getActivity(
                applicationContext,
                (task.id + "_share").hashCode(),
                Intent.createChooser(shareIntent, "Videoyu Paylaş"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(applicationContext, COMPLETE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_encode)
            .setContentTitle("Encode tamamlandı")
            .setContentText("${task.videoFileName} • $durationText sürdü")
            .setSubText("Galeriye kaydedildi")
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(playPending ?: openPending)

        if (playPending != null) {
            builder.addAction(android.R.drawable.ic_media_play, "Oynat", playPending)
        }
        if (sharePending != null) {
            builder.addAction(android.R.drawable.ic_menu_share, "Paylaş", sharePending)
        }

        notificationManager?.notify(notificationId, builder.build())
    }

    private fun showFailureNotification(task: EncodeTaskEntity, notificationId: Int) {
        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            applicationContext,
            task.id.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(applicationContext, COMPLETE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_encode)
            .setContentTitle("Encode başarısız")
            .setContentText("${task.videoFileName}: ${task.errorMessage ?: "Hata oluştu"}")
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPending)
            .build()

        notificationManager?.notify(notificationId, notif)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val progressChannel = NotificationChannel(
                CHANNEL_ID,
                "Video Encode Durumu",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Hardsub video kodlama ve anlık ilerleme bildirimleri"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager?.createNotificationChannel(progressChannel)

            val completeChannel = NotificationChannel(
                COMPLETE_CHANNEL_ID,
                "Video Encode Tamamlandı",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Video encode işlemi bittiğinde veya hata aldığında bildirir"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
            }
            notificationManager?.createNotificationChannel(completeChannel)
        }
    }

    private fun copyUriToFile(uri: Uri, destFile: File) {
        applicationContext.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }
    }

    private fun extractDuration(file: File): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            retriever.release()
            dur
        } catch (_: Throwable) {
            0L
        }
    }

    private fun publishToGallery(outputFile: File, originalFileName: String): Uri? {
        val baseName = originalFileName.substringBeforeLast(".")
        val finalFileName = "${baseName}_hardsub_${System.currentTimeMillis()}.mp4"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, finalFileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RemSubs")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val uri = applicationContext.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                applicationContext.contentResolver.openOutputStream(uri)?.use { os ->
                    FileInputStream(outputFile).use { fis ->
                        fis.copyTo(os, bufferSize = 64 * 1024)
                    }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                applicationContext.contentResolver.update(uri, values, null, null)
            }
            uri
        } else {
            @Suppress("DEPRECATION")
            val moviesDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), "RemSubs").apply { mkdirs() }
            val destFile = File(moviesDir, finalFileName)
            FileInputStream(outputFile).use { fis ->
                FileOutputStream(destFile).use { fos ->
                    fis.copyTo(fos)
                }
            }
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = Uri.fromFile(destFile)
            }
            applicationContext.sendBroadcast(intent)
            Uri.fromFile(destFile)
        }
    }

    private fun cleanDirectory(dir: File) {
        try {
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        } catch (_: Throwable) {}
    }

    private fun formatSeconds(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val mins = totalSec / 60
        val secs = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}
