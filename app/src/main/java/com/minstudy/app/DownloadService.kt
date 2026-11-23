package com.minstudy.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import kotlin.math.max

data class DownloadQueueEntry(val id: String, val title: String, val category: String, val active: Boolean, val paused: Boolean, val itemKey: String = "", val bytes: Long = 0L, val total: Long = -1L, val speed: Long = 0L)

data class DownloadUiState(
    val status: String = "",
    val active: Boolean = false,
    val paused: Boolean = false,
    val bytes: Long = 0L,
    val total: Long = -1L,
    val speed: Long = 0L,
    val title: String = "",
    val category: String = "",
    val itemKey: String = "",
    val runningCount: Int = 0,
    val queuedCount: Int = 0,
    val queueItems: List<DownloadQueueEntry> = emptyList(),
    val maxParallel: Int = 3
)

class DownloadService : Service() {
    companion object {
        private const val CHANNEL = "mine_study_downloads"
        private const val NOTIFICATION_ID = 4107
        private const val NOTIFICATION_GROUP = "mine_study_individual_downloads"
        private const val ACTION_START = "com.minstudy.app.download.START"
        private const val ACTION_PAUSE = "com.minstudy.app.download.PAUSE"
        private const val ACTION_RESUME = "com.minstudy.app.download.RESUME"
        private const val ACTION_CANCEL = "com.minstudy.app.download.CANCEL"
        private const val ACTION_CANCEL_ITEM = "com.minstudy.app.download.CANCEL_ITEM"
        private const val ACTION_PAUSE_ITEM = "com.minstudy.app.download.PAUSE_ITEM"
        private const val ACTION_RESUME_ITEM = "com.minstudy.app.download.RESUME_ITEM"
        private const val EXTRA_CONTROL_KEY = "control_key"
        private const val EXTRA_LIMIT = "parallel_limit"
        private const val ACTION_SET_LIMIT = "com.minstudy.app.download.SET_LIMIT"
        private const val ACTION_MOVE = "com.minstudy.app.download.MOVE"
        private const val ACTION_REMOVE_QUEUED = "com.minstudy.app.download.REMOVE_QUEUED"
        private const val ACTION_CLEAR_QUEUE = "com.minstudy.app.download.CLEAR_QUEUE"
        private const val EXTRA_MOVE = "move_direction"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CATEGORY = "category"
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_ITEM_KEY = "item_key"
        private val mutableState = MutableStateFlow(DownloadUiState())
        val state = mutableState.asStateFlow()

        fun start(context: Context, url: String, title: String, category: String, video: Boolean, itemKey: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CATEGORY, category)
                putExtra(EXTRA_VIDEO, video)
                putExtra(EXTRA_ITEM_KEY, itemKey)
            }
            ContextCompat.startForegroundService(context, intent)
        }
        fun pause(context: Context) = command(context, ACTION_PAUSE)
        fun resume(context: Context) = command(context, ACTION_RESUME)
        fun cancel(context: Context) = command(context, ACTION_CANCEL)
        fun cancelItem(context: Context, id: String) = itemCommand(context, ACTION_CANCEL_ITEM, id)
        fun pauseItem(context: Context, key: String) = itemCommand(context, ACTION_PAUSE_ITEM, key)
        fun resumeItem(context: Context, key: String) = itemCommand(context, ACTION_RESUME_ITEM, key)
        fun setParallelLimit(context: Context, value: Int) { context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_SET_LIMIT).putExtra(EXTRA_LIMIT, value.coerceIn(1, 5))) }
        fun moveQueued(context: Context, id: String, direction: String) { context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_MOVE).putExtra(EXTRA_CONTROL_KEY, id).putExtra(EXTRA_MOVE, direction)) }
        fun removeQueued(context: Context, id: String) = itemCommand(context, ACTION_REMOVE_QUEUED, id)
        fun clearQueue(context: Context) = command(context, ACTION_CLEAR_QUEUE)
        private fun itemCommand(context: Context, action: String, key: String) {
            context.startService(Intent(context, DownloadService::class.java).setAction(action).putExtra(EXTRA_CONTROL_KEY, key))
        }
        private fun command(context: Context, action: String) {
            context.startService(Intent(context, DownloadService::class.java).setAction(action))
        }
    }

    private data class Task(
        val id: String,
        val url: String,
        val title: String,
        val category: String,
        val video: Boolean,
        val itemKey: String
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = ArrayDeque<Task>()
    private val active = linkedMapOf<String, Pair<Task, DownloadControl>>()
    private val resumeRequested = linkedSetOf<String>()
    private var maxParallel = 3
    private var foregroundStarted = false
    private var currentTitle = "Study material"
    private var lastProgressAt = 0L
    private var lastProgressBytes = 0L
    private val itemNotificationTimes = mutableMapOf<String, Long>()

    override fun onCreate() {
        super.onCreate()
        maxParallel = getSharedPreferences("download_manager_settings", MODE_PRIVATE).getInt("parallel_limit", 3).coerceIn(1, 5)
        mutableState.value = mutableState.value.copy(maxParallel = maxParallel)
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Study downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress and controls for Mine Study downloads"
                setShowBadge(true)
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent ?: return START_NOT_STICKY
        when (command.action) {
            ACTION_START -> enqueue(command)
            ACTION_PAUSE -> {
                active.values.forEach { it.second.paused = true }
                mutableState.value = mutableState.value.copy(paused = true, status = "Pausing downloads…")
                updateNotification()
            }
            ACTION_RESUME -> {
                active.values.forEach { it.second.paused = false }
                mutableState.value = mutableState.value.copy(paused = false, status = "Downloading ${active.size} files")
                updateNotification()
            }
            ACTION_SET_LIMIT -> {
                maxParallel = command.getIntExtra(EXTRA_LIMIT, 3).coerceIn(1, 5)
                getSharedPreferences("download_manager_settings", MODE_PRIVATE).edit().putInt("parallel_limit", maxParallel).apply()
                pumpQueue(); refreshQueueState("Parallel downloads: $maxParallel")
            }
            ACTION_MOVE -> {
                val id = command.getStringExtra(EXTRA_CONTROL_KEY).orEmpty()
                val direction = command.getStringExtra(EXTRA_MOVE).orEmpty()
                val list = pending.toMutableList(); val index = list.indexOfFirst { it.id == id }
                if (index >= 0) {
                    val target = when (direction) { "top" -> 0; "up" -> (index - 1).coerceAtLeast(0); "down" -> (index + 1).coerceAtMost(list.lastIndex); "bottom" -> list.lastIndex; else -> index }
                    val task = list.removeAt(index); list.add(target, task); pending.clear(); list.forEach { pending.addLast(it) }
                }
                refreshQueueState("Queue order updated")
            }
            ACTION_CLEAR_QUEUE -> {
                pending.clear()
                refreshQueueState("Queued downloads cleared")
            }
            ACTION_REMOVE_QUEUED -> {
                val id = command.getStringExtra(EXTRA_CONTROL_KEY).orEmpty()
                val remaining = pending.filterNot { it.id == id }; pending.clear(); remaining.forEach { pending.addLast(it) }
                refreshQueueState("Removed from queue")
            }
            ACTION_PAUSE_ITEM -> {
                val key = command.getStringExtra(EXTRA_CONTROL_KEY).orEmpty()
                active[key]?.second?.paused = true
                pumpQueue()
                refreshQueueState("Paused • ${active[key]?.first?.title ?: "Download"}")
            }
            ACTION_RESUME_ITEM -> {
                val key = command.getStringExtra(EXTRA_CONTROL_KEY).orEmpty()
                if (active.containsKey(key)) resumeRequested.add(key)
                pumpQueue()
                refreshQueueState("Resume queued")
            }
            ACTION_CANCEL_ITEM -> {
                val id = command.getStringExtra(EXTRA_CONTROL_KEY).orEmpty()
                val runningTask = active[id]
                if (runningTask != null) {
                    runningTask.second.cancelled = true
                    refreshQueueState("Cancelling • ${runningTask.first.title}")
                } else {
                    val remaining = pending.filterNot { it.id == id }
                    pending.clear(); remaining.forEach { pending.addLast(it) }
                    refreshQueueState("Removed from queue")
                }
            }
            ACTION_CANCEL -> {
                pending.clear()
                active.values.forEach { it.second.cancelled = true }
                refreshQueueState("Cancelling downloads…")
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun enqueue(intent: Intent) {
        val task = Task(
            java.util.UUID.randomUUID().toString(),
            intent.getStringExtra(EXTRA_URL).orEmpty(),
            intent.getStringExtra(EXTRA_TITLE) ?: "Study material",
            intent.getStringExtra(EXTRA_CATEGORY) ?: "Lectures",
            intent.getBooleanExtra(EXTRA_VIDEO, true),
            intent.getStringExtra(EXTRA_ITEM_KEY).orEmpty()
        )
        if (task.url.isBlank()) {
            mutableState.value = mutableState.value.copy(status = "Download failed: URL missing")
            return
        }
        val duplicate = pending.any { it.itemKey == task.itemKey && it.itemKey.isNotBlank() } ||
            active.values.any { it.first.itemKey == task.itemKey && it.first.itemKey.isNotBlank() }
        if (duplicate) return
        pending.addLast(task)
        if (!foregroundStarted) {
            startForegroundCompat(buildNotification())
            foregroundStarted = true
        }
        pumpQueue()
        refreshQueueState("Queued ${task.title}")
    }

    private fun pumpQueue() {
        var running = active.values.count { !it.second.paused }
        for (key in resumeRequested.toList()) {
            if (running >= maxParallel) break
            val entry = active[key]
            if (entry == null) {
                resumeRequested.remove(key)
            } else {
                entry.second.paused = false
                resumeRequested.remove(key)
                running++
            }
        }
        while (running < maxParallel && pending.isNotEmpty()) {
            val task = pending.removeFirst()
            val id = task.id
            val taskControl = DownloadControl()
            active[id] = task to taskControl
            running++
            serviceScope.launch { runTask(id, task, taskControl) }
        }
    }

    private suspend fun runTask(id: String, task: Task, taskControl: DownloadControl) {
        currentTitle = task.title
        var taskProgressAt = System.currentTimeMillis()
        var taskProgressBytes = 0L
        refreshQueueState("Downloading • ${task.title}")
        try {
            val record = DownloadRepository.download(
                this@DownloadService, task.url, task.title, task.category, task.video, task.itemKey, taskControl
            ) { bytes, total ->
                val now = System.currentTimeMillis()
                val elapsed = (now - taskProgressAt).coerceAtLeast(1L)
                val taskSpeed = if (elapsed >= 700L) ((bytes - taskProgressBytes).coerceAtLeast(0L) * 1000L / elapsed) else 0L
                // Parallel workers report progress concurrently; update atomically
                // so one worker cannot overwrite another worker's queue row.
                mutableState.update { state ->
                    val updatedQueue = state.queueItems.map { entry ->
                        if (entry.id == id) entry.copy(bytes = bytes, total = total, speed = if (taskControl.paused) 0L else taskSpeed) else entry
                    }
                    state.copy(
                        status = "Downloading • ${task.title}",
                        active = true,
                        bytes = bytes,
                        total = total,
                        speed = updatedQueue.filter { it.active && !it.paused }.sumOf { it.speed },
                        title = task.title,
                        category = task.category,
                        itemKey = task.itemKey,
                        queueItems = updatedQueue
                    )
                }
                val lastItemAt = itemNotificationTimes[id] ?: 0L
                if (now - lastItemAt >= 700L) {
                    itemNotificationTimes[id] = now
                    notifyItem(task, bytes, total, taskControl.paused)
                }
                if (elapsed >= 700L) {
                    taskProgressAt = now
                    taskProgressBytes = bytes
                    updateNotification()
                }
            }
            refreshQueueState("Completed • ${record.name}")
        } catch (e: CancellationException) {
            refreshQueueState("Cancelled • ${task.title}")
        } catch (e: Exception) {
            val reason = e.message ?: "Network error"
            refreshQueueState("Failed • ${task.title}: $reason")
        } finally {
            active.remove(id)
            getSystemService(NotificationManager::class.java).cancel(itemNotificationId(id))
            itemNotificationTimes.remove(id)
            resumeRequested.remove(id)
            pumpQueue()
            if (active.isEmpty() && pending.isEmpty()) {
                val previous = mutableState.value
                mutableState.value = previous.copy(
                    active = false,
                    paused = false,
                    bytes = 0L,
                    total = -1L,
                    speed = 0L,
                    runningCount = 0,
                    queuedCount = 0,
                    queueItems = emptyList()
                )
                updateNotification(completed = true)
                stopForeground(STOP_FOREGROUND_DETACH)
                foregroundStarted = false
                stopSelf()
            } else {
                refreshQueueState("Downloading ${active.size} • Queued ${pending.size}")
            }
        }
    }

    private fun queueSnapshot(previousItems: List<DownloadQueueEntry>): List<DownloadQueueEntry> {
        val previous = previousItems.associateBy { it.id }
        return active.map { (id, pair) ->
            val progress = previous[id]
            DownloadQueueEntry(id, pair.first.title, pair.first.category, true, pair.second.paused, pair.first.itemKey, progress?.bytes ?: 0L, progress?.total ?: -1L, progress?.speed ?: 0L)
        } + pending.map { task -> DownloadQueueEntry(task.id, task.title, task.category, false, false, task.itemKey) }
    }

    private fun refreshQueueState(message: String) {
        val first = active.values.firstOrNull { !it.second.paused }?.first ?: active.values.firstOrNull()?.first
        mutableState.update { previous ->
            previous.copy(
                status = message,
                active = active.isNotEmpty() || pending.isNotEmpty(),
                paused = active.isNotEmpty() && active.values.all { it.second.paused },
                title = first?.title ?: previous.title,
                category = first?.category ?: previous.category,
                itemKey = first?.itemKey ?: previous.itemKey,
                runningCount = active.values.count { !it.second.paused },
                queuedCount = pending.size,
                queueItems = queueSnapshot(previous.queueItems),
                maxParallel = maxParallel
            )
        }
        if (foregroundStarted) updateNotification()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(completed: Boolean = false, failed: Boolean = false): Notification {
        val s = mutableState.value
        val activeItems = s.queueItems.filter { it.active }
        val running = activeItems.count { !it.paused }
        val queued = s.queueItems.count { !it.active }
        val knownTotals = activeItems.filter { it.total > 0L }
        val totalBytes = knownTotals.sumOf { it.total }
        val downloadedBytes = knownTotals.sumOf { it.bytes.coerceAtMost(it.total) }
        val hasAggregateProgress = activeItems.isNotEmpty() && knownTotals.size == activeItems.size && totalBytes > 0L
        val percent = if (hasAggregateProgress) ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 99) else 0
        val title = when {
            completed -> if (failed) "Download failed" else "Downloads finished"
            running > 0 -> "Downloading $running file${if (running == 1) "" else "s"}"
            activeItems.isNotEmpty() -> "Downloads paused"
            queued > 0 -> "Downloads queued"
            else -> "Preparing downloads"
        }
        val detail = when {
            completed -> s.status
            else -> "$running downloading • $queued queued · each file has its own progress notification"
        }
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val content = openIntent?.let { PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(if (completed) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setSubText("Mine Study")
            .setContentText(detail)
            .setContentIntent(content)
            .setOnlyAlertOnce(true)
            .setOngoing(!completed)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setGroup(NOTIFICATION_GROUP)
            .setGroupSummary(true)
        if (!completed && activeItems.isNotEmpty()) {
            val style = NotificationCompat.InboxStyle()
            activeItems.forEach { item ->
                val pct = if (item.total > 0L) ((item.bytes * 100L) / item.total).toInt().coerceIn(0, 99) else 0
                style.addLine("${item.title}: ${if (item.paused) "Paused" else "$pct%"}")
            }
            if (queued > 0) style.addLine("$queued waiting in queue")
            builder.setStyle(style)
        }
        if (!completed) builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel all", servicePending(ACTION_CANCEL, 3))
        return builder.build()
    }
    private fun servicePending(action: String, request: Int) =
        PendingIntent.getService(this, request, Intent(this, DownloadService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun updateNotification(completed: Boolean = false, failed: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(completed, failed))
        if (!completed) {
            active.forEach { (id, pair) ->
                val entry = mutableState.value.queueItems.firstOrNull { it.id == id }
                if (entry != null) notifyItem(pair.first, entry.bytes, entry.total, pair.second.paused)
            }
        } else {
            active.keys.forEach { manager.cancel(itemNotificationId(it)) }
        }
    }

    private fun itemNotificationId(id: String): Int = 50000 + (id.hashCode() and 0x3fffffff)

    private fun notifyItem(task: Task, bytes: Long, total: Long, paused: Boolean) {
        val percent = if (total > 0L) ((bytes * 100L) / total).toInt().coerceIn(0, 99) else 0
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(task.title)
            .setSubText("Mine Study · ${task.category}")
            .setContentText(if (paused) "Paused · ${human(bytes)} downloaded" else if (total > 0L) "$percent% · ${human(bytes)} / ${human(total)}" else "${human(bytes)} downloaded")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setGroup(NOTIFICATION_GROUP)
            .addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "Resume" else "Pause",
                serviceItemPending(if (paused) ACTION_RESUME_ITEM else ACTION_PAUSE_ITEM, task.id)
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", serviceItemPending(ACTION_CANCEL_ITEM, task.id))
        if (total > 0L) builder.setProgress(100, percent, false) else builder.setProgress(0, 0, true)
        getSystemService(NotificationManager::class.java).notify(itemNotificationId(task.id), builder.build())
    }

    private fun serviceItemPending(action: String, id: String): PendingIntent =
        PendingIntent.getService(
            this,
            itemNotificationId(id) xor action.hashCode(),
            Intent(this, DownloadService::class.java).setAction(action).putExtra(EXTRA_CONTROL_KEY, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun human(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
        else String.format(java.util.Locale.US, "%.1f MB", mb)
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
