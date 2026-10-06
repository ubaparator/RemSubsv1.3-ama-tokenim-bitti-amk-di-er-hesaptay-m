package com.example.encode.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EncodeTaskDao {

    @Query("SELECT * FROM encode_tasks ORDER BY createdAt DESC")
    fun getAllTasksFlow(): Flow<List<EncodeTaskEntity>>

    @Query("SELECT * FROM encode_tasks WHERE id = :id")
    suspend fun getTaskById(id: String): EncodeTaskEntity?

    @Query("SELECT * FROM encode_tasks WHERE id = :id")
    fun getTaskByIdFlow(id: String): Flow<EncodeTaskEntity?>

    @Query("SELECT * FROM encode_tasks WHERE status IN ('PENDING', 'RETRYING') ORDER BY createdAt ASC")
    suspend fun getPendingTasks(): List<EncodeTaskEntity>

    @Query("SELECT * FROM encode_tasks WHERE status IN ('ENCODING', 'RETRYING') LIMIT 1")
    suspend fun getRunningTask(): EncodeTaskEntity?

    @Query("SELECT COUNT(*) FROM encode_tasks WHERE status IN ('ENCODING', 'RETRYING', 'PENDING')")
    fun getActiveQueueCountFlow(): Flow<Int>

    @Query("SELECT * FROM encode_tasks WHERE status IN ('ENCODING', 'RETRYING', 'PENDING') ORDER BY CASE WHEN status = 'ENCODING' THEN 0 WHEN status = 'RETRYING' THEN 1 ELSE 2 END, createdAt ASC LIMIT 1")
    fun getActiveOrPendingTaskFlow(): Flow<EncodeTaskEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: EncodeTaskEntity)

    @Update
    suspend fun updateTask(task: EncodeTaskEntity)

    @Query("UPDATE encode_tasks SET lastHeartbeatTimeMs = :heartbeatTimeMs WHERE id = :id")
    suspend fun updateHeartbeat(id: String, heartbeatTimeMs: Long)

    @Query("DELETE FROM encode_tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    @Query("DELETE FROM encode_tasks WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    suspend fun clearFinishedTasks()

    @Query("""
        UPDATE encode_tasks SET 
            progressPercent = :percent,
            progressFraction = :fraction,
            processedDurationMs = :processedMs,
            totalDurationMs = :totalMs,
            currentFrame = :frame,
            totalFrames = :totalFrames,
            speed = :speed,
            currentFps = :fps,
            bitrateKbps = :bitrate,
            estimatedRemainingSeconds = :remainingSec,
            lastHeartbeatTimeMs = :heartbeatTimeMs
        WHERE id = :id AND status = 'ENCODING'
    """)
    suspend fun updateProgressMetrics(
        id: String,
        percent: Int,
        fraction: Float,
        processedMs: Long,
        totalMs: Long,
        frame: Long,
        totalFrames: Long,
        speed: Float,
        fps: Float,
        bitrate: Float,
        remainingSec: Long,
        heartbeatTimeMs: Long
    )

    @Query("UPDATE encode_tasks SET status = 'RETRYING', errorMessage = 'İşlem kurtarılıyor ve baştan başlatılıyor...' WHERE status = 'ENCODING'")
    suspend fun resetInterruptedTasksToRetrying()
}
