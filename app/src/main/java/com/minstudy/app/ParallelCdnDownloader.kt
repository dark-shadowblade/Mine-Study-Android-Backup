package com.minstudy.app

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay

/**
 * Parallel range downloader with durable chunk checkpoints.
 * Only chunks recorded as complete in the sidecar are trusted after a restart.
 */
object ParallelCdnDownloader {
    private const val CHUNK = 1024 * 1024
    private const val WORKERS = 4
    private data class Part(val start: Long, val end: Long, val total: Long, val bytes: ByteArray)

    private class Checkpoint(
        private val file: File,
        val fingerprint: String,
        val total: Long,
        val chunkCount: Int
    ) {
        private val positions = LongArray(chunkCount) { it.toLong() * CHUNK }

        @Synchronized fun nextPosition(index: Int): Long = positions[index]

        @Synchronized fun isComplete(index: Int): Boolean {
            val end = minOf((index.toLong() + 1L) * CHUNK, total)
            return positions[index] >= end
        }

        @Synchronized fun advance(index: Int, next: Long) {
            val end = minOf((index.toLong() + 1L) * CHUNK, total)
            if (next < positions[index] || next > end) throw IOException("Invalid download checkpoint position")
            positions[index] = next
            persist()
        }

        @Synchronized fun completedBytes(): Long = positions.indices.sumOf { index ->
            (positions[index] - index.toLong() * CHUNK).coerceAtLeast(0L)
        }

        @Synchronized private fun persist() {
            val array = JSONArray()
            positions.forEach { array.put(it) }
            val json = JSONObject()
                .put("fingerprint", fingerprint)
                .put("total", total)
                .put("chunkSize", CHUNK)
                .put("positions", array)
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.toString())
            if (file.exists() && !file.delete()) throw IOException("Could not replace download checkpoint")
            if (!temp.renameTo(file)) throw IOException("Could not save download checkpoint")
        }

        companion object {
            fun loadOrCreate(file: File, fingerprint: String, total: Long): Checkpoint {
                val countLong = (total + CHUNK - 1L) / CHUNK
                if (countLong <= 0L || countLong > Int.MAX_VALUE) throw IOException("Invalid video chunk count")
                val count = countLong.toInt()
                val checkpoint = Checkpoint(file, fingerprint, total, count)
                val saved = runCatching { JSONObject(file.readText()) }.getOrNull()
                val positions = saved?.optJSONArray("positions")
                val valid = saved?.optString("fingerprint") == fingerprint &&
                    saved.optLong("total", -1L) == total &&
                    saved.optInt("chunkSize", -1) == CHUNK &&
                    positions?.length() == count
                if (valid && positions != null) {
                    for (i in 0 until count) {
                        val start = i.toLong() * CHUNK
                        val end = minOf(start + CHUNK, total)
                        val savedPosition = positions.optLong(i, -1L)
                        if (savedPosition !in start..end) {
                            file.delete()
                            return Checkpoint(file, fingerprint, total, count)
                        }
                        checkpoint.positions[i] = savedPosition
                    }
                } else {
                    file.delete()
                    File(file.parentFile, file.name + ".tmp").delete()
                }
                return checkpoint
            }
        }
    }

    fun checkpointFile(target: File): File = File(target.parentFile, target.name + ".json")

    fun clearCheckpoint(target: File) {
        target.delete()
        checkpointFile(target).delete()
        File(target.parentFile, target.name + ".json.tmp").delete()
    }

    private fun sourceFingerprint(source: Uri): String {
        // Ignore expiring signature/query parameters; item identity plus CDN path is stable.
        val stable = "${source.scheme}://${source.host}${source.encodedPath}"
        return MessageDigest.getInstance("SHA-256")
            .digest(stable.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    suspend fun download(
        source: String,
        target: File,
        control: DownloadControl,
        progress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val base = Uri.parse(source)
        if (!base.scheme.equals("https", true) ||
            !Regex("a\\d+\\.stmvv1\\.xyz", RegexOption.IGNORE_CASE).matches(base.host ?: "")) {
            throw IOException("Invalid video CDN host")
        }
        target.parentFile?.mkdirs()
        val checkpointFile = checkpointFile(target)
        val first = fetch(base, 0L, CHUNK - 1L)
        val total = first.total
        if (total <= 0L) throw IOException("CDN returned an invalid video size")

        var checkpoint = Checkpoint.loadOrCreate(checkpointFile, sourceFingerprint(base), total)
        if (target.exists() && target.length() != total) {
            target.delete()
            checkpointFile.delete()
            checkpoint = Checkpoint.loadOrCreate(checkpointFile, sourceFingerprint(base), total)
        }
        RandomAccessFile(target, "rw").use { file ->
            file.setLength(total)
            val count = checkpoint.chunkCount
            progress(checkpoint.completedBytes(), total)

            suspend fun writeChunk(index: Int, initial: Part? = null) {
                control.checkpoint()
                if (checkpoint.isComplete(index)) return
                val start = index.toLong() * CHUNK
                val end = minOf(start + CHUNK - 1L, total - 1L)
                var cursor = checkpoint.nextPosition(index)
                var pending = if (cursor == start) initial else null
                while (cursor <= end) {
                    control.checkpoint()
                    val part = pending ?: retryingFetch(base, cursor, end, total)
                    pending = null
                    if (part.start != cursor || part.end > end || part.bytes.isEmpty()) {
                        throw IOException("CDN returned a non-contiguous byte range")
                    }
                    synchronized(file) {
                        file.seek(part.start)
                        file.write(part.bytes)
                        file.fd.sync()
                    }
                    cursor = part.end + 1L
                    checkpoint.advance(index, cursor)
                    progress(checkpoint.completedBytes(), total)
                }
            }

            coroutineScope {
                val next = AtomicInteger(0)
                (0 until WORKERS).map {
                    async(Dispatchers.IO) {
                        while (true) {
                            control.checkpoint()
                            val index = next.getAndIncrement()
                            if (index >= count) break
                            if (checkpoint.isComplete(index)) continue
                            val initial = if (index == 0) first else null
                            writeChunk(index, initial)
                        }
                    }
                }.awaitAll()
            }
            if (checkpoint.completedBytes() != total) {
                throw IOException("Download incomplete: saved ${checkpoint.completedBytes()} of $total bytes")
            }
        }
    }

    private suspend fun retryingFetch(base: Uri, start: Long, end: Long, total: Long): Part {
        var last: Exception? = null
        repeat(4) { attempt ->
            try {
                return fetch(base, start, end).also {
                    if (it.total != total || it.start != start || it.end > end) {
                        throw IOException("CDN returned an inconsistent byte range")
                    }
                }
            } catch (e: Exception) {
                last = e
                if (attempt < 3) delay(350L * (attempt + 1))
            }
        }
        throw IOException("Range request failed after 4 attempts: ${last?.message}", last)
    }

    private suspend fun fetch(base: Uri, start: Long, end: Long): Part = withContext(Dispatchers.IO) {
        var last: Exception? = null
        repeat(4) { attempt ->
            var connection: HttpURLConnection? = null
            try {
                val builder = base.buildUpon().clearQuery()
                for (name in base.queryParameterNames) {
                    if (name != "s" && name != "e") {
                        base.getQueryParameters(name).forEach { builder.appendQueryParameter(name, it) }
                    }
                }
                builder.appendQueryParameter("s", start.toString())
                builder.appendQueryParameter("e", end.toString())
                val conn = (URL(builder.build().toString()).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 20000
                    readTimeout = 45000
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("User-Agent", "ExoPlayer")
                }
                connection = conn
                val status = conn.responseCode
                if (status != HttpURLConnection.HTTP_PARTIAL) {
                    val detail = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText().take(200) } }.getOrNull()
                    throw IOException("CDN HTTP $status: ${detail ?: "unexpected response"}")
                }
                val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
                    .find(conn.getHeaderField("Content-Range") ?: "")
                    ?: throw IOException("CDN returned invalid Content-Range")
                val actualStart = match.groupValues[1].toLong()
                val actualEnd = match.groupValues[2].toLong()
                val actualTotal = match.groupValues[3].toLong()
                if (actualStart != start || actualEnd < start || actualEnd > end || actualEnd >= actualTotal) {
                    throw IOException("CDN returned an unexpected byte range")
                }
                val output = ByteArrayOutputStream((actualEnd - start + 1L).toInt())
                conn.inputStream.use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val amount = input.read(buffer)
                        if (amount < 0) break
                        output.write(buffer, 0, amount)
                    }
                }
                val bytes = output.toByteArray()
                if (bytes.size.toLong() != actualEnd - start + 1L) throw IOException("CDN range was truncated")
                return@withContext Part(actualStart, actualEnd, actualTotal, bytes)
            } catch (e: Exception) {
                last = e
                if (attempt < 3) delay(350L * (attempt + 1))
            } finally {
                connection?.disconnect()
            }
        }
        throw IOException("CDN request failed after retries: ${last?.message}", last)
    }
}
