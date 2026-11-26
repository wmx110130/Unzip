package com.dsha.zipforge.task

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dsha.zipforge.MainActivity
import com.dsha.zipforge.R
import com.dsha.zipforge.archive.ArchiveCancelledException
import com.dsha.zipforge.archive.ArchiveCompressor
import com.dsha.zipforge.archive.ArchiveEngines
import com.dsha.zipforge.archive.ArchiveReader
import com.dsha.zipforge.core.ArchiveFormat
import com.dsha.zipforge.core.CompressPlan
import com.dsha.zipforge.core.ExtractPlan
import com.dsha.zipforge.core.Fmt
import com.dsha.zipforge.core.sanitizeEntryPath
import com.dsha.zipforge.io.ArchiveSource
import com.dsha.zipforge.io.SafTreeTarget
import com.dsha.zipforge.io.UriArchiveSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

enum class TaskKind { EXTRACT, COMPRESS }

enum class TaskStatus { RUNNING, DONE, FAILED, CANCELLED }

data class TaskUiState(
    val id: Long,
    val kind: TaskKind,
    val title: String,
    val status: TaskStatus,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val entriesDone: Int = 0,
    val entriesTotal: Int = 0,
    val currentName: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val error: String? = null,
    val resultMessage: String? = null,
    val outputName: String? = null,
    val outputUri: Uri? = null,
) {
    val fraction: Float
        get() = when {
            bytesTotal > 0 -> (bytesDone.toDouble() / bytesTotal).toDouble().toFloat().coerceIn(0f, 1f)
            entriesTotal > 0 -> entriesDone.toFloat() / entriesTotal
            else -> 0f
        }

    val speed: Long
        get() {
            val end = finishedAt ?: System.currentTimeMillis()
            val elapsed = (end - startedAt).coerceAtLeast(1)
            return if (status == TaskStatus.RUNNING) bytesDone * 1000 / elapsed else 0L
        }

    val etaText: String
        get() {
            if (status != TaskStatus.RUNNING || speed <= 0 || bytesTotal <= bytesDone) return ""
            val remain = (bytesTotal - bytesDone) * 1000 / speed
            return Fmt.duration(remain)
        }
}

/**
 * 全局任务管理器：同一时间只运行一个压缩/解压任务。
 */
object TaskManager {

    private const val CHANNEL_ID = "archive_tasks"
    const val NOTIFICATION_ID = 0x2F31

    private val _state = MutableStateFlow<TaskUiState?>(null)
    val state: StateFlow<TaskUiState?> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val cancelFlag = AtomicBoolean(false)
    private var appContext: Context? = null
    private var taskSeq = 1L
    private var lastNotify = 0L
    private var lastProgressEmit = 0L

    val isBusy: Boolean get() = _state.value?.status == TaskStatus.RUNNING

    fun current(): TaskUiState? = _state.value

    fun dismiss() {
        if (_state.value?.status != TaskStatus.RUNNING) _state.value = null
    }

    fun cancel() {
        cancelFlag.set(true)
    }

    fun startExtraction(context: Context, plan: ExtractPlan): Boolean {
        if (isBusy) return false
        appContext = context.applicationContext
        cancelFlag.set(false)
        val total = plan.entries.filter { !it.isDirectory && it.size > 0 }.sumOf { it.size }
        val fileCount = plan.entries.count { !it.isDirectory }
        val id = taskSeq++
        _state.value = TaskUiState(
            id = id,
            kind = TaskKind.EXTRACT,
            title = plan.request.displayName,
            status = TaskStatus.RUNNING,
            bytesTotal = total,
            entriesTotal = fileCount,
            currentName = "",
        )
        startForegroundService(context)
        job = scope.launch { runExtract(context.applicationContext, plan, id) }
        return true
    }

    fun startCompression(context: Context, plan: CompressPlan): Boolean {
        if (isBusy) return false
        appContext = context.applicationContext
        cancelFlag.set(false)
        val id = taskSeq++
        _state.value = TaskUiState(
            id = id,
            kind = TaskKind.COMPRESS,
            title = plan.archiveName,
            status = TaskStatus.RUNNING,
            bytesTotal = plan.totalBytes,
            entriesTotal = plan.files.size,
            currentName = "",
        )
        startForegroundService(context)
        job = scope.launch { runCompress(context.applicationContext, plan, id) }
        return true
    }

    private fun startForegroundService(context: Context) {
        runCatching {
            val intent = Intent(context, ArchiveWorkService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private fun stopForegroundService(context: Context) {
        runCatching { context.stopService(Intent(context, ArchiveWorkService::class.java)) }
    }

    private fun publish(state: TaskUiState, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastProgressEmit < 150) return
        lastProgressEmit = now
        _state.value = state
        val ctx = appContext ?: return
        if (state.status == TaskStatus.RUNNING && now - lastNotify > 600) {
            lastNotify = now
            runCatching { notifyProgress(ctx, state) }
        }
    }

    private suspend fun runExtract(context: Context, plan: ExtractPlan, taskId: Long) {
        var reader: ArchiveReader? = null
        var source: ArchiveSource? = null
        var writtenBytes = 0L
        var writtenCount = 0
        val errors = ArrayList<String>()
        try {
            source = UriArchiveSource.from(context, plan.request.uri)
            reader = ArchiveEngines.open(source, plan.password)
            source = null // 由 reader 管理
            val base = _state.value ?: return
            val target = SafTreeTarget(context, plan.targetUri, plan.targetName)

            // 先创建目录结构
            for (e in plan.entries) {
                if (!e.isDirectory) continue
                val safe = sanitizeEntryPath(e.path) ?: continue
                runCatching { target.createDirectory(safe) }
            }

            val selectedIds = plan.entries.filter { !it.isDirectory }.map { it.id }.toSet()
            var bytesDone = 0L
            var countDone = 0

            reader.extractSelected(selectedIds) { entry, input ->
                if (cancelFlag.get()) throw ArchiveCancelledException()
                val safePath = sanitizeEntryPath(entry.path)
                if (safePath == null) {
                    errors.add("跳过不安全路径：${entry.path}")
                    return@extractSelected
                }
                val finalPath = try {
                    target.resolveConflict(safePath, plan.conflictPolicy)
                } catch (e: Exception) {
                    safePath
                }
                if (finalPath == null) {
                    // 跳过
                } else {
                    try {
                        val out = target.openOutput(finalPath)
                        out.use { os ->
                            val buffer = ByteArray(ArchiveCompressor.BUFFER_SIZE)
                            var lastReport = 0L
                            var entryDone = 0L
                            while (true) {
                                if (cancelFlag.get()) throw ArchiveCancelledException()
                                val read = input.read(buffer)
                                if (read < 0) break
                                os.write(buffer, 0, read)
                                entryDone += read
                                bytesDone += read
                                writtenBytes += read
                                if (bytesDone - lastReport >= 512 * 1024) {
                                    lastReport = bytesDone
                                    publish(
                                        base.copy(
                                            bytesDone = bytesDone,
                                            entriesDone = countDone,
                                            currentName = finalPath,
                                        )
                                    )
                                }
                            }
                        }
                        target.onEntryDone(finalPath, entry.modified)
                    } catch (e: ArchiveCancelledException) {
                        throw e
                    } catch (e: Exception) {
                        errors.add("${entry.name}: ${e.message ?: "写入失败"}")
                    }
                }
                countDone++
                writtenCount++
                publish(
                    base.copy(
                        bytesDone = bytesDone,
                        entriesDone = countDone,
                        currentName = finalPath ?: safePath,
                    ),
                    force = true,
                )
            }

            val done = (_state.value ?: base).copy(
                status = TaskStatus.DONE,
                bytesDone = bytesDone,
                entriesDone = countDone,
                finishedAt = System.currentTimeMillis(),
                resultMessage = context.getString(R.string.extract_done_msg, countDone, Fmt.size(writtenBytes)),
                currentName = "",
            )
            _state.value = done
            notifyFinished(context, done)
        } catch (e: ArchiveCancelledException) {
            val st = _state.value?.copy(
                status = TaskStatus.CANCELLED,
                finishedAt = System.currentTimeMillis(),
            )
            _state.value = st
            if (st != null) notifyFinished(context, st)
        } catch (e: CancellationException) {
            val st = _state.value?.copy(
                status = TaskStatus.CANCELLED,
                finishedAt = System.currentTimeMillis(),
            )
            _state.value = st
            if (st != null) notifyFinished(context, st)
        } catch (e: Throwable) {
            if (errors.isNotEmpty()) {
                // 已经解出部分文件，报告为完成但有错误
                val st = _state.value?.copy(
                    status = TaskStatus.DONE,
                    finishedAt = System.currentTimeMillis(),
                    resultMessage = context.getString(R.string.extract_done_msg, writtenCount, Fmt.size(writtenBytes)),
                    error = errors.take(3).joinToString("\n"),
                )
                _state.value = st
                if (st != null) notifyFinished(context, st)
            } else {
                val st = _state.value?.copy(
                    status = TaskStatus.FAILED,
                    finishedAt = System.currentTimeMillis(),
                    error = e.message ?: e.javaClass.simpleName,
                )
                _state.value = st
                if (st != null) notifyFinished(context, st)
            }
        } finally {
            runCatching { reader?.close() }
            runCatching { source?.close() }
            stopForegroundService(context)
        }
    }

    private suspend fun runCompress(context: Context, plan: CompressPlan, taskId: Long) {
        var tempFile: File? = null
        try {
            val base = _state.value ?: return
            val onBytes: (Long, String) -> Unit = { done, name ->
                publish(base.copy(bytesDone = done, currentName = name))
            }
            val isCancelled: () -> Boolean = { cancelFlag.get() }

            if (plan.format == ArchiveFormat.SEVEN_Z) {
                val temp = File(context.cacheDir, "zipforge-${System.currentTimeMillis()}.7z")
                tempFile = temp
                ArchiveCompressor.compressToSevenZ(
                    context, plan.files, plan.level, temp, onBytes, isCancelled,
                )
                // 复制到目标
                val target = context.contentResolver.openOutputStream(plan.saveUri)
                    ?: throw IOException(context.getString(R.string.error_permission))
                target.use { out ->
                    temp.inputStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (cancelFlag.get()) throw ArchiveCancelledException()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
            } else {
                val target = context.contentResolver.openOutputStream(plan.saveUri)
                    ?: throw IOException(context.getString(R.string.error_permission))
                target.use { out ->
                    ArchiveCompressor.compress(
                        context, plan.format, plan.level, plan.files, out, onBytes, isCancelled,
                    )
                }
            }

            val total = if (plan.format == ArchiveFormat.SEVEN_Z) tempFile?.length() ?: 0L else 0L
            val st = (_state.value ?: base).copy(
                status = TaskStatus.DONE,
                bytesDone = plan.totalBytes,
                entriesDone = plan.files.size,
                finishedAt = System.currentTimeMillis(),
                resultMessage = context.getString(
                    R.string.compress_done_msg,
                    plan.archiveName,
                    if (total > 0) Fmt.size(total) else Fmt.size(plan.totalBytes),
                ),
                outputName = plan.archiveName,
                outputUri = plan.saveUri,
                currentName = "",
            )
            _state.value = st
            notifyFinished(context, st)
        } catch (e: ArchiveCancelledException) {
            runCatching { context.contentResolver.delete(plan.saveUri, null, null) }
            val st = _state.value?.copy(status = TaskStatus.CANCELLED, finishedAt = System.currentTimeMillis())
            _state.value = st
            if (st != null) notifyFinished(context, st)
        } catch (e: CancellationException) {
            runCatching { context.contentResolver.delete(plan.saveUri, null, null) }
            val st = _state.value?.copy(status = TaskStatus.CANCELLED, finishedAt = System.currentTimeMillis())
            _state.value = st
            if (st != null) notifyFinished(context, st)
        } catch (e: Throwable) {
            runCatching { context.contentResolver.delete(plan.saveUri, null, null) }
            val st = _state.value?.copy(
                status = TaskStatus.FAILED,
                finishedAt = System.currentTimeMillis(),
                error = e.message ?: e.javaClass.simpleName,
            )
            _state.value = st
            if (st != null) notifyFinished(context, st)
        } finally {
            tempFile?.let { runCatching { it.delete() } }
            stopForegroundService(context)
        }
    }

    // ---------- 通知 ----------

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notif_channel_desc)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun buildNotification(context: Context, state: TaskUiState?): Notification {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_archive)
            .setContentTitle(state?.title ?: context.getString(R.string.app_name))
            .setContentIntent(pending)
            .setOngoing(state?.status == TaskStatus.RUNNING)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (state == null) {
            builder.setContentText(context.getString(R.string.task_running))
        } else {
            when (state.status) {
                TaskStatus.RUNNING -> {
                    val percent = (state.fraction * 100).toInt()
                    builder.setContentText(
                        context.getString(
                            R.string.notif_running,
                            if (state.kind == TaskKind.EXTRACT) context.getString(R.string.task_extracting)
                            else context.getString(R.string.task_compressing),
                            percent,
                        )
                    )
                    builder.setProgress(100, percent, state.bytesTotal <= 0)
                }
                TaskStatus.DONE -> {
                    builder.setContentText(context.getString(R.string.notif_done, state.title))
                    builder.setProgress(0, 0, false)
                }
                TaskStatus.FAILED -> {
                    builder.setContentText(context.getString(R.string.notif_failed, state.title))
                }
                TaskStatus.CANCELLED -> {
                    builder.setContentText(context.getString(R.string.task_cancelled))
                }
            }
        }
        return builder.build()
    }

    private fun notifyProgress(context: Context, state: TaskUiState) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(context, state))
    }

    private fun notifyFinished(context: Context, state: TaskUiState) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIFICATION_ID, buildNotification(context, state))
        }
    }
}

/**
 * 前台服务：只负责让进程在压缩/解压期间保持前台优先级。
 */
class ArchiveWorkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = TaskManager.buildNotification(this, TaskManager.state.value)
        startForeground(TaskManager.NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }
}
