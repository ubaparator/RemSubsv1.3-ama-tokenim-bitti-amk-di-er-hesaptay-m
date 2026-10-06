package com.example.encode

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.example.encode.db.AppDatabase
import com.example.encode.db.EncodeTaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Central Queue and Lifecycle Manager for Persistent Video Hardsub Encode Tasks.
 */
object EncodeQueueManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun getAllTasks(context: Context): Flow<List<EncodeTaskEntity>> {
        return AppDatabase.getInstance(context).encodeTaskDao().getAllTasksFlow()
    }

    fun getActiveQueueCount(context: Context): Flow<Int> {
        return AppDatabase.getInstance(context).encodeTaskDao().getActiveQueueCountFlow()
    }

    fun getActiveOrPendingTask(context: Context): Flow<EncodeTaskEntity?> {
        return AppDatabase.getInstance(context).encodeTaskDao().getActiveOrPendingTaskFlow()
    }

    suspend fun getTaskById(context: Context, id: String): EncodeTaskEntity? {
        return AppDatabase.getInstance(context).encodeTaskDao().getTaskById(id)
    }

    /**
     * Enqueues a new encode task into the persistent Room queue and ensures the foreground service is active.
     */
    fun enqueueTask(
        context: Context,
        videoUri: Uri,
        videoFileName: String,
        assContent: String,
        introVideoUri: Uri? = null,
        introDurationMs: Long = 0L,
        introKeepAudio: Boolean = false,
        settings: EncodingSettings = EncodingSettings()
    ): String {
        val taskId = UUID.randomUUID().toString()
        val task = EncodeTaskEntity(
            id = taskId,
            videoUriString = videoUri.toString(),
            videoFileName = videoFileName,
            assContent = assContent,
            introVideoUriString = introVideoUri?.toString(),
            introDurationMs = introDurationMs,
            introKeepAudio = introKeepAudio,
            encoderName = settings.encoderOption.name,
            crf = settings.crf,
            preset = settings.preset,
            resolution = settings.resolution,
            fps = settings.fps,
            bitrate = settings.bitrate,
            audioOption = settings.audioOption,
            status = EncodeTaskEntity.STATUS_PENDING,
            createdAt = System.currentTimeMillis()
        )

        scope.launch {
            val dao = AppDatabase.getInstance(context).encodeTaskDao()
            dao.insertTask(task)
            android.util.Log.i("EncodeQueue", "[EncodeQueue] Task inserted: $taskId")
            android.util.Log.i("EncodeQueue", "[EncodeQueue] Enqueuing HardsubEncodeWorker")

            try {
                HardsubEncodeWorker.enqueueWork(context)
                android.util.Log.i("EncodeQueue", "[EncodeQueue] HardsubEncodeWorker enqueued successfully for task: $taskId")
            } catch (t: Throwable) {
                android.util.Log.e("EncodeQueue", "[EncodeQueue] Failed to start encode worker: ${t.message}", t)
                try {
                    dao.updateTask(
                        task.copy(
                            status = EncodeTaskEntity.STATUS_FAILED,
                            errorMessage = "Encode servisi başlatılamadı: ${t.localizedMessage ?: t.javaClass.simpleName}",
                            completedAt = System.currentTimeMillis()
                        )
                    )
                } catch (dbErr: Throwable) {
                    android.util.Log.e("EncodeQueue", "[EncodeQueue] Failed to mark task failed in DB", dbErr)
                }
            }
        }

        return taskId
    }

    /**
     * Cancels an active or pending task. If active, worker halts it and immediately advances to the next task.
     */
    fun cancelTask(context: Context, taskId: String) {
        android.util.Log.i("EncodeQueue", "[EncodeQueue] Cancelling task: $taskId")
        HardsubEncodeWorker.cancelTaskIfRunning(taskId)

        val intent = Intent(context, HardsubEncodeService::class.java).apply {
            action = HardsubEncodeService.ACTION_CANCEL_TASK
            putExtra(HardsubEncodeService.EXTRA_TASK_ID, taskId)
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Throwable) {
            try {
                context.startService(intent)
            } catch (_: Throwable) {}
        }

        scope.launch {
            val dao = AppDatabase.getInstance(context).encodeTaskDao()
            val task = dao.getTaskById(taskId)
            if (task != null && (task.status == EncodeTaskEntity.STATUS_PENDING || task.status == EncodeTaskEntity.STATUS_ENCODING || task.status == EncodeTaskEntity.STATUS_RETRYING)) {
                dao.updateTask(
                    task.copy(
                        status = EncodeTaskEntity.STATUS_CANCELLED,
                        errorMessage = "Kullanıcı tarafından iptal edildi.",
                        completedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    /**
     * Retries a failed or cancelled task.
     */
    fun retryTask(context: Context, taskId: String) {
        scope.launch {
            val dao = AppDatabase.getInstance(context).encodeTaskDao()
            val task = dao.getTaskById(taskId) ?: return@launch
            val resetTask = task.copy(
                status = EncodeTaskEntity.STATUS_PENDING,
                progressPercent = 0,
                progressFraction = 0f,
                processedDurationMs = 0L,
                currentFrame = 0L,
                speed = 0f,
                errorMessage = null,
                completedAt = null
            )
            dao.updateTask(resetTask)
            android.util.Log.i("EncodeQueue", "[EncodeQueue] Task retried and set to PENDING: $taskId")

            try {
                HardsubEncodeWorker.enqueueWork(context)
            } catch (t: Throwable) {
                android.util.Log.e("EncodeQueue", "[EncodeQueue] Failed to enqueue HardsubEncodeWorker on retry", t)
                dao.updateTask(
                    resetTask.copy(
                        status = EncodeTaskEntity.STATUS_FAILED,
                        errorMessage = "Encode servisi başlatılamadı: ${t.localizedMessage ?: t.javaClass.simpleName}",
                        completedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    fun deleteTask(context: Context, taskId: String) {
        cancelTask(context, taskId)
        scope.launch {
            AppDatabase.getInstance(context).encodeTaskDao().deleteTask(taskId)
            android.util.Log.i("EncodeQueue", "[EncodeQueue] Task deleted: $taskId")
            // Ensure queue continues if any tasks remain
            try {
                HardsubEncodeWorker.enqueueWork(context)
            } catch (_: Throwable) {}
        }
    }

    fun clearFinishedTasks(context: Context) {
        scope.launch {
            AppDatabase.getInstance(context).encodeTaskDao().clearFinishedTasks()
        }
    }

    /**
     * Called on app startup to recover any orphaned tasks that were interrupted by a system reboot or crash.
     */
    fun recoverInterruptedTasks(context: Context) {
        scope.launch {
            val dao = AppDatabase.getInstance(context).encodeTaskDao()
            dao.resetInterruptedTasksToRetrying()
            HardsubEncodeWorker.enqueueWork(context)
        }
    }
}
