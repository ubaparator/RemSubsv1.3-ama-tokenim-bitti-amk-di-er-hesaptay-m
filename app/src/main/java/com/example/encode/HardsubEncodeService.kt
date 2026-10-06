package com.example.encode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.encode.db.AppDatabase
import com.example.encode.db.EncodeTaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Compatibility & Notification Action Bridge Service.
 *
 * All actual encoding execution is centrally and reliably managed by [HardsubEncodeWorker]
 * via WorkManager. This service routes intent actions (such as notification cancel clicks
 * or legacy start commands) directly to [HardsubEncodeWorker] and cleans up immediately.
 */
class HardsubEncodeService : Service() {

    companion object {
        private const val TAG = "HardsubEncodeService"
        const val CHANNEL_ID = "remsubs_encode_channel_v3"
        const val COMPLETE_CHANNEL_ID = "remsubs_encode_complete_v3"
        const val NOTIFICATION_ID = 2001
        const val COMPLETION_NOTIFICATION_BASE_ID = 3000

        const val ACTION_PROCESS_QUEUE = "com.example.encode.ACTION_PROCESS_QUEUE"
        const val ACTION_CANCEL_TASK = "com.example.encode.ACTION_CANCEL_TASK"
        const val EXTRA_TASK_ID = "extra_task_id"

        @Volatile
        var isServiceRunning: Boolean = false
            private set
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "[EncodeService] onCreate")
        isServiceRunning = true
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        // Required foreground elevation when started via startForegroundService
        val initialNotif = buildInitialNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    initialNotif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotif)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "[EncodeService] startForeground warning: ${t.localizedMessage}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_PROCESS_QUEUE
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
        Log.i(TAG, "[EncodeService] onStartCommand action=$action taskId=$taskId")

        when (action) {
            ACTION_CANCEL_TASK -> {
                if (taskId != null) {
                    HardsubEncodeWorker.cancelTaskIfRunning(taskId)
                    serviceScope.launch {
                        try {
                            val dao = AppDatabase.getInstance(applicationContext).encodeTaskDao()
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
                        } catch (t: Throwable) {
                            Log.w(TAG, "Error updating cancelled task: ${t.localizedMessage}")
                        }
                    }
                }
            }
            ACTION_PROCESS_QUEUE -> {
                HardsubEncodeWorker.enqueueWork(applicationContext)
            }
        }

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {}
        stopSelf()
        isServiceRunning = false
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "[EncodeService] onDestroy")
        isServiceRunning = false
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildInitialNotification(): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_encode)
            .setContentTitle("Video Encode Servisi")
            .setContentText("Kuyruk yönlendiriliyor...")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
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
}
