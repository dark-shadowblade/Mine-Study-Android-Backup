package com.minstudy.app

import android.net.Uri
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.BaseDataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.DataSource
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Adapts the CDN's ?s=start&e=end byte-range protocol to ExoPlayer. */
class ChunkQueryDataSource : BaseDataSource(false) {
    companion object { private const val CHUNK_SIZE = 2 * 1024 * 1024 }
    private var originalUri: Uri? = null
    private var connection: HttpURLConnection? = null
    private var stream: java.io.InputStream? = null
    private var position = 0L
    private var totalLength = C.LENGTH_UNSET.toLong()
    private var chunkRemaining = 0L
    private var requestedRemaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    val discoveredLength: Long
        get() = if (totalLength == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else totalLength

    val isAtChunkBoundary: Boolean
        get() = chunkRemaining == 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        originalUri = dataSpec.uri
        position = dataSpec.position
        totalLength = C.LENGTH_UNSET.toLong()
        requestedRemaining = dataSpec.length
        openChunk()
        if (requestedRemaining == C.LENGTH_UNSET.toLong()) {
            requestedRemaining = (totalLength - position).coerceAtLeast(0L)
        } else if (totalLength != C.LENGTH_UNSET.toLong()) {
            requestedRemaining = minOf(requestedRemaining, (totalLength - position).coerceAtLeast(0L))
        }
        opened = true
        transferStarted(dataSpec)
        return requestedRemaining
    }

    private fun openChunk() {
        if (totalLength != C.LENGTH_UNSET.toLong() && position >= totalLength) {
            chunkRemaining = 0
            return
        }
        val maxEnd = position + CHUNK_SIZE - 1
        val end = if (totalLength == C.LENGTH_UNSET.toLong()) maxEnd else minOf(maxEnd, totalLength - 1)
        val base = originalUri ?: throw IOException("Missing stream URL")
        if (!base.scheme.equals("https", true) || !Regex("a\\d+\\.stmvv1\\.xyz", RegexOption.IGNORE_CASE).matches(base.host ?: "")) {
            throw IOException("Invalid video CDN host")
        }
        val builder = base.buildUpon().clearQuery()
        for (name in base.queryParameterNames) {
            if (name != "s" && name != "e") {
                for (value in base.getQueryParameters(name)) builder.appendQueryParameter(name, value)
            }
        }
        builder.appendQueryParameter("s", position.toString())
        builder.appendQueryParameter("e", end.toString())
        val conn = (URL(builder.build().toString()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "ExoPlayer")
        }
        val status = conn.responseCode
        if (status != HttpURLConnection.HTTP_PARTIAL) {
            val detail = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText().take(300) } }.getOrNull()
            conn.disconnect()
            throw IOException("Video range request failed ($status): " + (detail ?: "unexpected response"))
        }
        val contentRange = conn.getHeaderField("Content-Range") ?: ""
        val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE).find(contentRange)
            ?: run { conn.disconnect(); throw IOException("CDN returned an invalid Content-Range") }
        val rangeStart = match.groupValues[1].toLong()
        val rangeEnd = match.groupValues[2].toLong()
        totalLength = match.groupValues[3].toLong()
        val expectedBytes = rangeEnd - position + 1
        if (rangeStart != position || rangeEnd < rangeStart || rangeEnd > end ||
            rangeEnd >= totalLength || expectedBytes <= 0L ||
            (conn.contentLengthLong >= 0L && conn.contentLengthLong != expectedBytes)) {
            conn.disconnect()
            throw IOException("CDN returned an incomplete or unexpected byte range")
        }
        connection = conn
        stream = conn.inputStream
        chunkRemaining = expectedBytes
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (requestedRemaining == 0L || (totalLength != C.LENGTH_UNSET.toLong() && position >= totalLength)) return C.RESULT_END_OF_INPUT
        if (chunkRemaining == 0L) {
            closeChunk()
            openChunk()
            if (chunkRemaining == 0L) return C.RESULT_END_OF_INPUT
        }
        val allowed = minOf(length.toLong(), chunkRemaining, if (requestedRemaining == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else requestedRemaining).toInt()
        val count = stream?.read(buffer, offset, allowed) ?: -1
        if (count == -1) throw IOException("Video chunk ended unexpectedly")
        position += count
        chunkRemaining -= count
        if (requestedRemaining != C.LENGTH_UNSET.toLong()) requestedRemaining -= count
        bytesTransferred(count)
        return count
    }

    private fun closeChunk() {
        runCatching { stream?.close() }
        stream = null
        connection?.disconnect()
        connection = null
    }

    override fun getUri(): Uri? = originalUri
    override fun getResponseHeaders(): Map<String, List<String>> =
        connection?.headerFields?.filterKeys { it != null }?.mapKeys { it.key!! } ?: emptyMap()

    override fun close() {
        closeChunk()
        originalUri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

class ChunkQueryDataSourceFactory : DataSource.Factory {
    override fun createDataSource(): DataSource = ChunkQueryDataSource()
}
