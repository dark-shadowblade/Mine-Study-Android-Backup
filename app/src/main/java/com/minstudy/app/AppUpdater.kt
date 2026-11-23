package com.minstudy.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private data class AppRelease(val versionCode: Long, val versionName: String, val apkUrl: String, val notes: String)

@Composable
internal fun AutoUpdateChecker() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var checking by remember { mutableStateOf(true) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf("") }
    var downloadedApk by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        checking = true
        release = withContext(Dispatchers.IO) { findAppUpdate(context) }
        checking = false
    }

    val available = release
    if (available != null) {
        AlertDialog(
            onDismissRequest = { if (!downloading) release = null },
            title = { Text("Update available") },
            text = {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(androidx.compose.ui.unit.dp(10))) {
                    Text("Mine Study ${available.versionName} is ready to install.")
                    if (available.notes.isNotBlank()) Text(available.notes, style = MaterialTheme.typography.bodySmall, maxLines = 5)
                    if (downloading) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading ${(progress * 100).toInt()}%")
                    }
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val existing = downloadedApk
                        if (existing != null && existing.exists()) {
                            installDownloadedUpdate(context, existing)
                        } else if (!downloading) {
                            downloading = true
                            error = ""
                            scope.launch {
                                try {
                                    downloadedApk = downloadUpdate(available.apkUrl, context) { progress = it }
                                    progress = 1f
                                } catch (e: Exception) {
                                    error = e.message ?: "Download failed. Please try again."
                                } finally {
                                    downloading = false
                                }
                            }
                        }
                    },
                    enabled = !downloading
                ) {
                    if (downloading) CircularProgressIndicator() else Text(if (downloadedApk?.exists() == true) "Install update" else "Update")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(androidx.compose.ui.unit.dp(8))) {
                    if (downloadedApk?.exists() == true) {
                        TextButton(onClick = { installDownloadedUpdate(context, downloadedApk!!) }) { Text("Install") }
                    }
                    TextButton(onClick = { if (!downloading) release = null }, enabled = !downloading) { Text("Later") }
                }
            }
        )
    } else if (checking) {
        // A silent, lightweight check; no blocking launch screen.
    }
}

private fun findAppUpdate(context: Context): AppRelease? {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL("https://api.github.com/repos/dark-shadowblade/Mine-Study-Android-Backup/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Mine-Study-Android")
        }
        if (connection.responseCode !in 200..299) return null
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val tag = json.optString("tag_name")
        val remoteCode = tag.removePrefix("v").substringAfterLast('.').toLongOrNull() ?: return null
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val installedCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
        if (remoteCode <= installedCode) return null
        val assets = json.optJSONArray("assets") ?: return null
        var apkUrl = ""
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name").endsWith(".apk", true)) {
                apkUrl = asset.optString("browser_download_url")
                if (apkUrl.isNotBlank()) break
            }
        }
        if (apkUrl.isBlank()) return null
        AppRelease(remoteCode, tag.removePrefix("v"), apkUrl, json.optString("body").trim())
    } catch (_: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

private suspend fun downloadUpdate(url: String, context: Context, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mine-Study-Android")
        }
        if (connection.responseCode !in 200..299) throw IllegalStateException("Download failed (HTTP ${connection.responseCode})")
        val total = connection.contentLengthLong
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "mine-study-update.apk")
        val temp = File(dir, "mine-study-update.apk.part")
        connection.inputStream.use { input ->
            temp.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var received = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    received += count
                    if (total > 0) onProgress((received.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        if (temp.length() < 1024L) throw IllegalStateException("Downloaded APK is incomplete")
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) throw IllegalStateException("Could not save the update APK")
        target
    } finally {
        connection?.disconnect()
    }
}

private fun installDownloadedUpdate(context: Context, apk: File) {
    if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
        Toast.makeText(context, "Allow Mine Study to install updates, then return and tap Install.", Toast.LENGTH_LONG).show()
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    }
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not start installer: ${e.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
    }
}
