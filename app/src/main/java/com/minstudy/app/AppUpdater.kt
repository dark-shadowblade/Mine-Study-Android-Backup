package com.minstudy.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private data class AppRelease(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val notes: String
)

@Composable
internal fun AutoUpdateChecker() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember {
        context.getSharedPreferences("mine_study_updates", Context.MODE_PRIVATE)
    }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        val latest = withContext(Dispatchers.IO) { findAppUpdate(context) }
        if (latest != null &&
            preferences.getLong("dismissed_version", -1L) != latest.versionCode
        ) {
            release = latest
        }
    }

    val available = release ?: return

    fun dismissUpdate() {
        preferences.edit().putLong("dismissed_version", available.versionCode).apply()
        release = null
    }

    AlertDialog(
        onDismissRequest = { if (!downloading) dismissUpdate() },
        title = { Text("Update available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Mine Study ${available.versionName} is ready.")
                if (available.notes.isNotBlank()) {
                    Text(
                        available.notes,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4
                    )
                }
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Downloading ${(progress * 100).toInt()}%")
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !downloading,
                onClick = {
                    val readyFile = downloadedApk?.takeIf { it.isFile && it.length() > 0L }
                    if (readyFile != null) {
                        installDownloadedUpdate(context, readyFile)
                    } else {
                        downloading = true
                        progress = 0f
                        scope.launch {
                            try {
                                downloadedApk = downloadUpdate(
                                    available.apkUrl,
                                    context,
                                    available.versionCode
                                ) { value ->
                                    progress = value
                                }
                                progress = 1f
                            } catch (_: Exception) {
                                downloadedApk = null
                                Toast.makeText(
                                    context,
                                    "Update download failed. Please try again.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } finally {
                                downloading = false
                            }
                        }
                    }
                }
            ) {
                if (downloading) {
                    CircularProgressIndicator()
                } else {
                    Text(if (downloadedApk?.exists() == true) "Install update" else "Update")
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = !downloading,
                    onClick = { dismissUpdate() }
                ) { Text("Later") }
            }
        }
    )
}

private fun findAppUpdate(context: Context): AppRelease? {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(
            "https://api.github.com/repos/dark-shadowblade/Mine-Study-Android-Backup/releases/latest"
        ).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Mine-Study-Android")
        }
        if (connection.responseCode !in 200..299) return null

        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null

        val tag = json.optString("tag_name")
        val match = Regex("^v?\\d+\\.\\d+\\.(\\d+)$").matchEntire(tag) ?: return null
        val remoteCode = match.groupValues[1].toLongOrNull() ?: return null

        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val installedCode = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        if (remoteCode <= installedCode) return null

        val assets = json.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") == "app-debug.apk") {
                apkUrl = asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                break
            }
        }
        val validUrl = apkUrl ?: return null
        AppRelease(remoteCode, tag.removePrefix("v"), validUrl, json.optString("body").trim())
    } catch (_: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

private suspend fun downloadUpdate(
    url: String,
    context: Context,
    expectedVersionCode: Long,
    onProgress: (Float) -> Unit
): File = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    val directory = File(context.cacheDir, "updates").apply { mkdirs() }
    val target = File(directory, "mine-study-update.apk")
    val temporary = File(directory, "mine-study-update.apk.part")
    try {
        temporary.delete()
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mine-Study-Android")
        }
        val response = connection.responseCode
        if (response !in 200..299) {
            throw IllegalStateException("HTTP $response")
        }

        val expectedLength = connection.contentLengthLong
        var received = 0L
        connection.inputStream.use { input ->
            temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    received += count
                    if (expectedLength > 0L) {
                        val value = (received.toFloat() / expectedLength).coerceIn(0f, 1f)
                        withContext(Dispatchers.Main) { onProgress(value) }
                    }
                }
            }
        }

        if (received < 1024L || (expectedLength > 0L && received != expectedLength)) {
            throw IllegalStateException("Incomplete APK")
        }

        @Suppress("DEPRECATION")
        val archiveInfo = context.packageManager.getPackageArchiveInfo(
            temporary.absolutePath,
            PackageManager.GET_ACTIVITIES
        ) ?: throw IllegalStateException("Invalid APK")
        @Suppress("DEPRECATION")
        val archiveVersion = if (Build.VERSION.SDK_INT >= 28) {
            archiveInfo.longVersionCode
        } else {
            archiveInfo.versionCode.toLong()
        }
        if (archiveInfo.packageName != context.packageName ||
            archiveVersion != expectedVersionCode
        ) {
            throw IllegalStateException("APK does not match this update")
        }

        if (target.exists() && !target.delete()) {
            throw IllegalStateException("Could not replace cached APK")
        }
        if (!temporary.renameTo(target)) {
            throw IllegalStateException("Could not save APK")
        }
        target
    } catch (error: Exception) {
        temporary.delete()
        throw error
    } finally {
        connection?.disconnect()
    }
}

private fun installDownloadedUpdate(context: Context, apk: File) {
    if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
        Toast.makeText(
            context,
            "Allow installs for Mine Study, then return and tap Install.",
            Toast.LENGTH_LONG
        ).show()
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return
    }

    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(
            context,
            "Could not open the Android installer.",
            Toast.LENGTH_SHORT
        ).show()
    }
}
