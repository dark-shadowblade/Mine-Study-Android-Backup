package com.minstudy.app

import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.content.FileProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
    val apkUrl: String
)

@Composable
internal fun AutoUpdateChecker(onUpdateAvailable: (Long) -> Unit = {}) {
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
        if (latest != null) {
            release = latest
            onUpdateAvailable(latest.versionCode)
            if (preferences.getLong("notified_version", -1L) != latest.versionCode &&
                showUpdateNotification(context, latest)
            ) {
                preferences.edit().putLong("notified_version", latest.versionCode).apply()
            }
        }
    }

    val available = release ?: return

    fun dismissUpdate() { release = null }

    AlertDialog(
        onDismissRequest = { if (!downloading) dismissUpdate() },
        shape = RoundedCornerShape(22.dp),
        containerColor = androidx.compose.ui.graphics.Color(0xFF171A22),
        tonalElevation = 0.dp,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("UPDATE AVAILABLE", style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color(0xFFAAA2FF),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text("Mine Study · v${available.versionName}",
                    style = MaterialTheme.typography.titleLarge,
                    color = androidx.compose.ui.graphics.Color(0xFFF2F2F7),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
        },
        text = {
            if (!downloading) {
                Text("A newer version is ready to install.",
                    color = androidx.compose.ui.graphics.Color(0xFFADB3C2),
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = androidx.compose.ui.graphics.Color(0xFFAAA2FF),
                        trackColor = androidx.compose.ui.graphics.Color(0xFF343947)
                    )
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                            } catch (error: Exception) {
                                downloadedApk = null
                                val message = if (
                                    error.message?.contains("signing certificate") == true
                                ) "This update can't replace the installed app."
                                else "Update failed"
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            } finally {
                                downloading = false
                            }
                        }
                    }
                }
            ) {
                if (downloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (downloadedApk?.exists() == true) "Install" else "Update")
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !downloading,
                onClick = { dismissUpdate() }
            ) {
                Text("Not now", color = androidx.compose.ui.graphics.Color(0xFFADB3C2))
            }
        }
    )
}

@Composable
internal fun NotificationsScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var checking by remember { mutableStateOf(true) }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var checked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        release = withContext(Dispatchers.IO) { findAppUpdate(context) }
        checking = false
        checked = true
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Notifications", style = MaterialTheme.typography.headlineSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        if (checking) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Checking for notifications…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (release != null) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("UPDATE AVAILABLE", style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color(0xFFAAA2FF),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text("Mine Study · v${release!!.versionName}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text("A newer version is ready. Open Settings to download and install it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Open update settings")
                }
            }
        } else if (checked) {
            Text("You're all caught up. No new updates right now.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}


private fun showUpdateNotification(context: Context, release: AppRelease): Boolean {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return false
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (!manager.areNotificationsEnabled()) return false
    val channelId = "app_updates"
    if (Build.VERSION.SDK_INT >= 26) {
        manager.createNotificationChannel(NotificationChannel(
            channelId, "App updates", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Notifications when a Mine Study update is available" })
    }
    val launchIntent = Intent(context, MainActivity::class.java).apply {
        putExtra("open_update_settings", true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    val pendingIntent = PendingIntent.getActivity(
        context,
        7301,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
    )
    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(context.applicationInfo.icon)
        .setContentTitle("Mine Study update available")
        .setContentText("Version v${release.versionName} is ready. Open the app to update.")
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
    return try {
        manager.notify(7301, notification)
        true
    } catch (_: SecurityException) {
        false
    }
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
        AppRelease(remoteCode, tag.removePrefix("v"), validUrl)
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
        val archiveFlags = PackageManager.GET_ACTIVITIES or
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val archiveInfo = context.packageManager.getPackageArchiveInfo(
            temporary.absolutePath,
            archiveFlags
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

        // Do not pre-empt Android's package installer with a second
        // certificate comparison. PackageInstaller verifies update
        // signing compatibility against the currently installed package.
        // A locally computed mismatch can be a false negative on newer
        // Android signing schemes and incorrectly block a valid update.


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
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setData(uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
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


@Composable
internal fun UpdateSettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installedVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown" }
            .getOrDefault("Unknown")
    }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var latest by remember { mutableStateOf<AppRelease?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf("Check for updates to see if a new version is available.") }

    Column(
        modifier = Modifier.fillMaxSize().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("App version", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("v$installedVersion", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        }
        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (checking || downloading) {
            LinearProgressIndicator(
                progress = { if (downloading) progress else 0f },
                modifier = Modifier.fillMaxWidth()
            )
            if (downloading) Text("${(progress * 100).toInt()}%")
        }
        Button(
            enabled = !checking && !downloading,
            onClick = {
                checking = true
                latest = null
                apk = null
                status = "Checking for updates…"
                scope.launch {
                    val result = withContext(Dispatchers.IO) { findAppUpdate(context) }
                    latest = result
                    status = if (result == null) "You're using the latest version." else "Update available: v${result.versionName}"
                    checking = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Check for updates")
        }
        if (latest != null) {
            Button(
                enabled = !downloading,
                onClick = {
                    val release = latest ?: return@Button
                    downloading = true
                    progress = 0f
                    status = "Downloading v${release.versionName}…"
                    scope.launch {
                        try {
                            apk = downloadUpdate(release.apkUrl, context, release.versionCode) { progress = it }
                            status = "Update downloaded. Ready to install."
                        } catch (error: Exception) {
                            apk = null
                            status = if (error.message?.contains("signing certificate") == true)
                                "This update is signed differently and can't replace this installation."
                            else "Update download failed."
                        } finally {
                            downloading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (apk?.exists() == true) "Download again" else "Download update")
            }
        }
        if (apk?.isFile == true) {
            Button(
                onClick = { apk?.let { installDownloadedUpdate(context, it) } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Install update") }
        }
    }
}
