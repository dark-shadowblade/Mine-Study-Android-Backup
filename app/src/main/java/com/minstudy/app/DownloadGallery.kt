package com.minstudy.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.DataSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class DownloadRecord(
    val uri: String,
    val name: String,
    val category: String,
    val mime: String,
    val bytes: Long,
    val itemKey: String = ""
)

class DownloadControl {
    @Volatile var paused: Boolean = false
    @Volatile var cancelled: Boolean = false

    suspend fun checkpoint() {
        while (paused && !cancelled) delay(150)
        if (cancelled) throw CancellationException("Download cancelled")
    }
}

object DownloadRepository {
    private const val PREFS = "mine_study_downloads"
    private const val RECORDS = "records"

    fun list(context: Context): List<DownloadRecord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(RECORDS, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            DownloadRecord(
                item.optString("uri"), item.optString("name"), item.optString("category"),
                item.optString("mime"), item.optLong("bytes"), item.optString("itemKey")
            ).takeIf { it.uri.isNotBlank() }
        }.filter { record ->
            runCatching { context.contentResolver.openFileDescriptor(Uri.parse(record.uri), "r")?.use { true } ?: false }.getOrDefault(false)
        }
    }

    private fun save(context: Context, record: DownloadRecord) {
        val all = list(context).filterNot { it.uri == record.uri } + record
        val array = JSONArray()
        all.forEach { item ->
            array.put(JSONObject().apply {
                put("uri", item.uri); put("name", item.name); put("category", item.category)
                put("mime", item.mime); put("bytes", item.bytes); put("itemKey", item.itemKey)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(RECORDS, array.toString()).apply()
    }

    fun delete(context: Context, record: DownloadRecord) {
        runCatching { context.contentResolver.delete(Uri.parse(record.uri), null, null) }
        val remaining = list(context).filterNot { it.uri == record.uri }
        val array = JSONArray()
        remaining.forEach { item ->
            array.put(JSONObject().apply {
                put("uri", item.uri); put("name", item.name); put("category", item.category)
                put("mime", item.mime); put("bytes", item.bytes); put("itemKey", item.itemKey)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(RECORDS, array.toString()).apply()
    }

    suspend fun download(
        context: Context,
        sourceUrl: String,
        title: String,
        category: String,
        isVideo: Boolean,
        itemKey: String = "",
        control: DownloadControl = DownloadControl(),
        onProgress: (Long, Long) -> Unit
    ): DownloadRecord = withContext(Dispatchers.IO) {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Study material" }
        val extension = if (isVideo) ".mp4" else ".pdf"
        val fileName = (if (clean.endsWith(extension, true)) clean else clean + extension).take(180)
        val mime = if (isVideo) "video/mp4" else "application/pdf"
        val relative = Environment.DIRECTORY_DOWNLOADS + "/Mine Study/" + category
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("Could not create download file")
        var written = 0L
        try {
            val output = resolver.openOutputStream(uri, "w") ?: throw IllegalStateException("Could not open download file")
            output.use { out ->
                if (isVideo) {
                    val identity = itemKey.ifBlank { sourceUrl } + "|" + Uri.parse(sourceUrl).encodedPath.orEmpty()
                    val stableName = MessageDigest.getInstance("SHA-256")
                        .digest(identity.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    val resumeDir = java.io.File(context.noBackupFilesDir, "download-resume").apply { mkdirs() }
                    val temp = java.io.File(resumeDir, "$stableName.mp4.part")
                    try {
                        ParallelCdnDownloader.download(sourceUrl, temp, control) { received, total ->
                            written = received
                            onProgress(received, total)
                        }
                        temp.inputStream().use { input ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                control.checkpoint()
                                val count = input.read(buffer)
                                if (count < 0) break
                                out.write(buffer, 0, count)
                            }
                        }
                        onProgress(written, written)
                    } catch (error: Exception) {
                        if (control.cancelled) ParallelCdnDownloader.clearCheckpoint(temp)
                        throw error
                    }
                    // Keep the completed checkpoint until MediaStore has been finalized below.
                    // A process death during the copy can therefore retry the copy without
                    // downloading the CDN ranges again.
                } else {
                    val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 20000
                        readTimeout = 30000
                        setRequestProperty("Accept", "application/pdf,application/octet-stream,*/*")
                    }
                    try {
                        val code = connection.responseCode
                        if (code !in 200..299) throw IllegalStateException("PDF download failed (HTTP $code)")
                        val total = connection.contentLengthLong
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                control.checkpoint()
                                val count = input.read(buffer)
                                if (count < 0) break
                                out.write(buffer, 0, count)
                                written += count
                                onProgress(written, total)
                            }
                        }
                    } finally { connection.disconnect() }
                }
            }
            if (written <= 0L) throw IllegalStateException("The server returned an empty file")
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            val record = DownloadRecord(uri.toString(), fileName, category, mime, written, itemKey)
            save(context, record)
            if (isVideo) {
                val identity = itemKey.ifBlank { sourceUrl } + "|" + Uri.parse(sourceUrl).encodedPath.orEmpty()
                val stableName = MessageDigest.getInstance("SHA-256")
                    .digest(identity.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                val temp = java.io.File(java.io.File(context.noBackupFilesDir, "download-resume"), "$stableName.mp4.part")
                ParallelCdnDownloader.clearCheckpoint(temp)
            }
            record
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@androidx.compose.runtime.Composable
fun DownloadGallery(
    records: List<DownloadRecord>,
    status: String,
    active: Boolean,
    paused: Boolean,
    bytes: Long,
    total: Long,
    speed: Long = 0L,
    queueItems: List<DownloadQueueEntry> = emptyList(),
    onPauseItem: (String) -> Unit,
    onResumeItem: (String) -> Unit,
    onTogglePause: () -> Unit,
    onCancel: () -> Unit,
    onOpen: (DownloadRecord) -> Unit,
    onDelete: (List<DownloadRecord>) -> Unit,
    onRefresh: () -> Unit
) {
    var selected by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("Lectures") }
    var selectedUris by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(setOf<String>()) }
    var selectionMode by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var confirmDelete by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val categories = listOf("All", "Lectures", "Notes", "DPP", "DPP Videos")
    val visible = if (selected == "All") records else records.filter { it.category == selected }
    val selectedRecords = visible.filter { it.uri in selectedUris }
    val totalStored = records.sumOf { it.bytes }
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                androidx.compose.material3.Text("Downloader", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                androidx.compose.material3.Text(
                    "${records.size} files  •  ${formatDownloadBytes(totalStored)} used",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                )
            }
            androidx.compose.material3.IconButton(onClick = onRefresh) {
                androidx.compose.material3.Icon(Icons.Default.Refresh, contentDescription = "Refresh downloads")
            }
        }
        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(7.dp)
        ) {
            items(categories) { category ->
                androidx.compose.material3.FilterChip(
                    selected = selected == category,
                    onClick = { selected = category; selectedUris = emptySet(); selectionMode = false },
                    label = { androidx.compose.material3.Text(category) }
                )
            }
        }
        if (visible.isNotEmpty()) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 5.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                androidx.compose.material3.Text(
                    "${visible.size} ${if (visible.size == 1) "download" else "downloads"}",
                    modifier = Modifier.weight(1f),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium
                )
                androidx.compose.material3.TextButton(
                    onClick = { confirmDelete = "all" },
                    enabled = records.isNotEmpty()
                ) {
                    androidx.compose.material3.Text("Delete all", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            }
            if (selectedUris.isNotEmpty()) {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer
                ) {
                    androidx.compose.foundation.layout.Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Text(
                            "${selectedUris.size} selected",
                            Modifier.weight(1f),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer,
                            style = androidx.compose.material3.MaterialTheme.typography.labelLarge
                        )
                        androidx.compose.material3.TextButton(onClick = { confirmDelete = "selected" }) {
                            androidx.compose.material3.Text("Delete selected", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        if (visible.isEmpty()) {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    androidx.compose.material3.Icon(Icons.Default.MenuBook, null, modifier = Modifier.size(38.dp), tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.material3.Text("No ${if (selected == "All") "downloads" else selected.lowercase()} yet", modifier = Modifier.padding(top = 8.dp), color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                items(visible, key = { it.uri }) { record ->
                    val isSelected = record.uri in selectedUris
                    androidx.compose.material3.Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(15.dp),
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = if (isSelected) androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer
                            else androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        androidx.compose.foundation.layout.Row(
                            Modifier.fillMaxWidth().combinedClickable(
                                onClick = {
                                    if (selectionMode) {
                                        selectedUris = if (isSelected) selectedUris - record.uri else selectedUris + record.uri
                                        if (selectedUris.isEmpty()) selectionMode = false
                                    } else onOpen(record)
                                },
                                onLongClick = {
                                    selectionMode = true
                                    selectedUris = selectedUris + record.uri
                                }
                            ).padding(start = 10.dp, end = 5.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            if (isSelected) {
                                androidx.compose.material3.Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp).padding(end = 3.dp)
                                )
                            }
                            androidx.compose.material3.Surface(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(11.dp),
                                color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                                modifier = Modifier.size(42.dp)
                            ) {
                                androidx.compose.foundation.layout.Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                                    androidx.compose.material3.Icon(
                                        if (record.mime.startsWith("video")) Icons.Default.PlayArrow else Icons.Default.MenuBook,
                                        null,
                                        tint = androidx.compose.material3.MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                androidx.compose.material3.Text(record.name.substringBeforeLast('.'), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 2, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                                androidx.compose.material3.Text(
                                    "${record.category}  •  ${formatDownloadBytes(record.bytes)}",
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                                )
                            }
                            androidx.compose.material3.IconButton(onClick = { onOpen(record) }) {
                                androidx.compose.material3.Icon(Icons.Default.ArrowForward, contentDescription = "Open in Mine Study")
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete.isNotEmpty()) {
        val deleting = if (confirmDelete == "all") visible else selectedRecords
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = "" },
            title = { androidx.compose.material3.Text(if (confirmDelete == "all") "Delete all ${selected.lowercase()}?" else "Delete selected downloads?") },
            text = { androidx.compose.material3.Text("This will permanently delete ${deleting.size} file(s) from this device. This action cannot be undone.") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        onDelete(deleting)
                        selectedUris = emptySet()
                        selectionMode = false
                        confirmDelete = ""
                    },
                    enabled = deleting.isNotEmpty()
                ) { androidx.compose.material3.Text("Delete", color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = "" }) { androidx.compose.material3.Text("Cancel") }
            }
        )
    }
}

@androidx.compose.runtime.Composable
fun PdfViewerScreen(uri: Uri, title: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pageCount by androidx.compose.runtime.remember(uri) { androidx.compose.runtime.mutableIntStateOf(0) }
    var loadError by androidx.compose.runtime.remember(uri) { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(uri) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                        pageCount = renderer.pageCount
                    }
                } ?: throw IllegalStateException("Could not open this PDF")
            }.onFailure { loadError = it.message ?: "Could not read PDF" }
        }
    }
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                androidx.compose.material3.Icon(Icons.Default.ArrowForward, contentDescription = "Back", modifier = Modifier.graphicsLayer { rotationY = 180f })
            }
            androidx.compose.material3.Text(title, Modifier.weight(1f), maxLines = 1, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            androidx.compose.material3.Text(if (pageCount > 0) "$pageCount pages" else "", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.HorizontalDivider()
        if (loadError.isNotBlank()) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                androidx.compose.material3.Text(loadError, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
        } else if (pageCount == 0) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF252A35)),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(9.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp)
            ) {
                items(pageCount) { pageIndex ->
                    val bitmapState = androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, uri, pageIndex) {
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                                    android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                                        renderer.openPage(pageIndex).use { page ->
                                            val width = 1200
                                            val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                                            android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
                                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                                page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                            }
                                        }
                                    }
                                }
                            }.getOrNull()
                        }
                    }
                    val bitmap = bitmapState.value
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "PDF page ${pageIndex + 1}",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                        )
                    } else {
                        androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

private fun formatDownloadSpeed(bytesPerSecond: Long): String {
    if (bytesPerSecond <= 0L) return "— MB/s"
    val mb = bytesPerSecond / (1024.0 * 1024.0)
    return if (mb >= 1.0) String.format(java.util.Locale.US, "%.2f MB/s", mb)
    else String.format(java.util.Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)
}

private fun formatDownloadBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
    else String.format(java.util.Locale.US, "%.1f MB", mb)
}


@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@androidx.compose.runtime.Composable
fun DownloadCenterScreen(
    queueItems: List<DownloadQueueEntry>,
    runningCount: Int,
    queuedCount: Int,
    maxParallel: Int,
    onSetMaxParallel: (Int) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancelItem: (String) -> Unit,
    onCancelItems: (List<String>) -> Unit,
    onMove: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onCancelAll: () -> Unit,
    onClearQueue: () -> Unit
) {
    var confirmAction by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var confirmCancelId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var selectedDownloadIds by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(setOf<String>()) }
    var parallelMenuExpanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp)
    ) {
        item {
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    androidx.compose.material3.Text("Downloader", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    androidx.compose.material3.Text("$runningCount active  ·  $queuedCount queued", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                }

            }
        }
        item {
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    androidx.compose.material3.Text("Max downloads at a time", style = androidx.compose.material3.MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    androidx.compose.material3.Text("Choose how many files download simultaneously", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.foundation.layout.Box {
                    androidx.compose.material3.OutlinedButton(onClick = { parallelMenuExpanded = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 5.dp)) {
                        androidx.compose.material3.Text("$maxParallel at a time", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                    }
                    androidx.compose.material3.DropdownMenu(expanded = parallelMenuExpanded, onDismissRequest = { parallelMenuExpanded = false }) {
                        (1..5).forEach { limit ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { androidx.compose.material3.Text("$limit " + if (limit == 1) "download" else "downloads") },
                                onClick = { onSetMaxParallel(limit); parallelMenuExpanded = false },
                                trailingIcon = { if (limit == maxParallel) androidx.compose.material3.Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = androidx.compose.material3.MaterialTheme.colorScheme.primary) }
                            )
                        }
                    }
                }
            }
        }
        if (queueItems.isNotEmpty()) {
            item {
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (selectedDownloadIds.isNotEmpty()) androidx.compose.material3.TextButton(onClick = { confirmAction = "selected" }) {
                        androidx.compose.material3.Text("Cancel selected (" + selectedDownloadIds.size + ")", color = androidx.compose.material3.MaterialTheme.colorScheme.error, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                    }
                    if (queueItems.any { !it.active }) androidx.compose.material3.TextButton(onClick = { confirmAction = "clear" }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp, vertical = 2.dp)) { androidx.compose.material3.Text("Clear queue", style = androidx.compose.material3.MaterialTheme.typography.labelSmall) }
                    androidx.compose.material3.TextButton(onClick = { confirmAction = "cancel" }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp, vertical = 2.dp)) { androidx.compose.material3.Text("Cancel all", color = androidx.compose.material3.MaterialTheme.colorScheme.error, style = androidx.compose.material3.MaterialTheme.typography.labelSmall) }
                }
            }
        }
        if (queueItems.isEmpty()) {
            item {
                androidx.compose.material3.Surface(modifier = Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant) {
                    androidx.compose.foundation.layout.Column(Modifier.padding(22.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        androidx.compose.material3.Icon(Icons.Default.ArrowForward, null, tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                        androidx.compose.material3.Text("Nothing downloading", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        androidx.compose.material3.Text("New downloads and queued files will appear here.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            items(queueItems, key = { it.id }) { item ->
                val isSelected = item.id in selectedDownloadIds
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        onClick = {
                            if (selectedDownloadIds.isNotEmpty()) {
                                selectedDownloadIds = if (isSelected) selectedDownloadIds - item.id else selectedDownloadIds + item.id
                            }
                        },
                        onLongClick = { selectedDownloadIds = selectedDownloadIds + item.id }
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(13.dp),
                    color = if (isSelected) androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer else androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
                ) {
                    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp)) {
                        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                                    if (item.active && !item.paused) {
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.size(17.dp),
                                            strokeWidth = 2.dp,
                                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    androidx.compose.material3.Text(item.title, maxLines = 2, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                }
                                androidx.compose.material3.Text(if (item.paused) "Paused · slot released" else if (item.active) "Downloading · ${item.category}" else "Queued · ${item.category}", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                                if (item.active && item.total > 0L) {
                                    androidx.compose.material3.LinearProgressIndicator(progress = (item.bytes.toFloat() / item.total.toFloat()).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                                    androidx.compose.material3.Text("${(item.bytes * 100L / item.total).toInt()}% · ${formatDownloadBytes(item.bytes)} / ${formatDownloadBytes(item.total)} · ${formatDownloadSpeed(item.speed)}", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                                } else if (item.active) {
                                    androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    androidx.compose.material3.Text("${formatDownloadBytes(item.bytes)} · ${formatDownloadSpeed(item.speed)}", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (item.active) {
                                androidx.compose.material3.IconButton(onClick = { if (item.paused) onResume(item.id) else onPause(item.id) }, modifier = Modifier.size(34.dp)) {
                                    androidx.compose.material3.Icon(if (item.paused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = if (item.paused) "Resume" else "Pause", modifier = Modifier.size(19.dp))
                                }
                                androidx.compose.material3.IconButton(onClick = { confirmCancelId = item.id }, modifier = Modifier.size(30.dp)) {
                                    androidx.compose.material3.Icon(Icons.Default.Close, contentDescription = "Cancel this download", tint = androidx.compose.material3.MaterialTheme.colorScheme.error, modifier = Modifier.size(17.dp))
                                }
                            } else {
                                androidx.compose.material3.TextButton(onClick = { onMove(item.id, "top") }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { androidx.compose.material3.Text("Top", style = androidx.compose.material3.MaterialTheme.typography.labelSmall) }
                                androidx.compose.material3.TextButton(onClick = { onMove(item.id, "up") }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { androidx.compose.material3.Text("↑", style = androidx.compose.material3.MaterialTheme.typography.labelLarge) }
                                androidx.compose.material3.TextButton(onClick = { onMove(item.id, "down") }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { androidx.compose.material3.Text("↓", style = androidx.compose.material3.MaterialTheme.typography.labelLarge) }
                                androidx.compose.material3.IconButton(onClick = { onRemove(item.id) }, modifier = Modifier.size(30.dp)) { androidx.compose.material3.Icon(Icons.Default.Close, contentDescription = "Remove from queue", tint = androidx.compose.material3.MaterialTheme.colorScheme.error, modifier = Modifier.size(17.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
    val cancelTarget = queueItems.firstOrNull { it.id == confirmCancelId }
    if (cancelTarget != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmCancelId = null },
            title = { androidx.compose.material3.Text("Cancel this download?") },
            text = { androidx.compose.material3.Text("${cancelTarget.title} will be cancelled and removed from the download list. Other downloads will continue.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    onCancelItem(cancelTarget.id)
                    confirmCancelId = null
                }) { androidx.compose.material3.Text("Cancel download", color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmCancelId = null }) { androidx.compose.material3.Text("Keep downloading") } }
        )
    }
    if (confirmAction.isNotEmpty()) {
        val clearing = confirmAction == "clear"
        val cancellingSelected = confirmAction == "selected"
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmAction = "" },
            title = { androidx.compose.material3.Text(when { clearing -> "Clear queued downloads?"; cancellingSelected -> "Cancel selected downloads?"; else -> "Cancel all downloads?" }) },
            text = { androidx.compose.material3.Text(when { clearing -> "Only waiting downloads will be removed. Active downloads will continue."; cancellingSelected -> "${selectedDownloadIds.size} selected download(s) will be cancelled or removed from the queue. Other downloads will continue."; else -> "All active downloads will be cancelled and the waiting queue will be cleared." }) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    when {
                        clearing -> onClearQueue()
                        cancellingSelected -> {
                            onCancelItems(selectedDownloadIds.toList())
                            selectedDownloadIds = emptySet()
                        }
                        else -> { onCancelAll(); selectedDownloadIds = emptySet() }
                    }
                    confirmAction = ""
                }) { androidx.compose.material3.Text(when { clearing -> "Clear queue"; cancellingSelected -> "Cancel selected"; else -> "Cancel all" }, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmAction = "" }) { androidx.compose.material3.Text("Keep") } }
        )
    }
}
